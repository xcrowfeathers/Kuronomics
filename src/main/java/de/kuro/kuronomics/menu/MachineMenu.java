package de.kuro.kuronomics.menu;

import de.kuro.kuronomics.block.*;
import de.kuro.kuronomics.economy.*;
import de.kuro.kuronomics.economy.data.EconomyLedger;
import de.kuro.kuronomics.item.Prices;
import de.kuro.kuronomics.mailbox.*;
import de.kuro.kuronomics.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;

public final class MachineMenu extends AbstractContainerMenu {
    public final MachineKind kind;
    public final BlockPos pos;
    public final boolean manager;
    public boolean removalAllowed;
    public final int page;
    public final MachineLayout layout;
    public long balance;
    public long profit, price = -1;
    public String suspensionReason = "";
    public boolean suspended;
    public final java.util.List<MailboxView> mailbox = new java.util.ArrayList<>();
    private final int machineSlots;
    private final Container inventory;
    private final MachineBlockEntity machine;
    private final Player viewer;
    private final EconomyLedger ledger;
    private de.kuro.kuronomics.network.MachineSnapshot lastSnapshot;
    private long seenAccountVersion = -1, seenProfit = -1, seenPrice = Long.MIN_VALUE;
    private boolean seenManager;
    private int lastMailboxReconcileTick = -100;
    public MachineMenu(int id, Inventory playerInventory, RegistryFriendlyByteBuf buffer) {
        this(id, playerInventory, buffer.readBlockPos(), buffer.readEnum(MachineKind.class), buffer.readBoolean(), buffer.readVarInt(), null);
        balance = buffer.readLong(); suspended = buffer.readBoolean(); suspensionReason = buffer.readUtf(2048); profit = buffer.readLong(); price = buffer.readLong();
        if (page == 4) mailbox.addAll(MailboxView.readList(buffer));
        removalAllowed = buffer.readBoolean();
    }
    public MachineMenu(int id, Inventory playerInventory, MachineBlockEntity machine) {
        this(id, playerInventory, machine.getBlockPos(), machine.kind, machine.canManage(playerInventory.player), 0, machine);
    }
    private MachineMenu(int id, Inventory playerInventory, BlockPos pos, MachineKind kind, boolean manager, int page, MachineBlockEntity machine) {
        super(ModContent.MACHINE_MENU.get(), id); this.kind = kind; this.pos = pos; this.manager = manager; this.machine = machine; this.viewer = playerInventory.player;
        removalAllowed = machine != null && machine.removalRole(viewer);
        this.ledger = machine != null && viewer instanceof ServerPlayer player ? EconomyLedger.get(player.server) : null;
        if (viewer instanceof ServerPlayer player) lastMailboxReconcileTick = player.server.getTickCount();
        this.inventory = machine == null || kind == MachineKind.ATM ? new SimpleContainer(kind.slots) : machine;
        // Each customer gets their own check workspace so nobody can take someone else's signed output.
        // Move checks left in the old block inventory into the first check session.
        if (machine != null && kind == MachineKind.ATM && page == 3) {
            for (int i = 0; i < kind.slots; i++) inventory.setItem(i, machine.removeItemNoUpdate(i));
            machine.setChanged();
        }
        this.page = page; layout = MachineLayout.of(kind, page); machineSlots = kind == MachineKind.ATM && page != 3 ? 0 : kind.slots;
        for (int i = 0; i < machineSlots; i++) {
            int index = i;
            addSlot(new Slot(inventory, i, MachineLayout.slotX(kind, i), MachineLayout.slotY(kind, i)) {
                @Override public boolean mayPlace(ItemStack stack) {
                    if (kind.shop() || kind.vending()) return canManage(playerInventory.player) && Prices.get(stack) >= 0;
                    if (kind == MachineKind.ATM) return (index == 0 || index == 2) && stack.is(ModContent.CHECK.get());
                    return true;
                }
                @Override public boolean mayPickup(Player player) { return (!kind.shop() && !kind.vending()) || canManage(player); }
            });
        }
        if (layout.playerInventory()) {
            for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++) addSlot(new Slot(playerInventory, col + row * 9 + 9, layout.inventoryX() + col * 18, layout.inventoryY() + row * 18));
            for (int col = 0; col < 9; col++) addSlot(new Slot(playerInventory, col, layout.inventoryX() + col * 18, layout.inventoryY() + 58));
        }
        if (machine != null && page == 4 && viewer instanceof ServerPlayer player)
            mailbox.addAll(new MailboxService(player.server).atmSnapshot(player.getUUID()));
    }
    public static void open(ServerPlayer player, MachineBlockEntity machine, int page) {
        if (!machine.assigned()) { CompanyShopMenu.assignOrOpen(player, machine); return; }
        var economy = new EconomyService(EconomyLedger.get(player.server));
        var account = economy.account(player.getUUID());
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new MachineMenu(id, inv, machine.getBlockPos(), machine.kind, machine.canManage(player), page, machine), machine.getDisplayName()), buffer -> {
            buffer.writeBlockPos(machine.getBlockPos()); buffer.writeEnum(machine.kind); buffer.writeBoolean(machine.canManage(player)); buffer.writeVarInt(page);
            buffer.writeLong(economy.getBalance(player.getUUID())); buffer.writeBoolean(account.suspended()); buffer.writeUtf(account.suspensionReason().substring(0, Math.min(2048, account.suspensionReason().length())), 2048);
            buffer.writeLong(machine.canManage(player) ? machine.profit() : 0); buffer.writeLong(machine.price());
            if (page == 4) MailboxView.writeList(buffer, new MailboxService(player.server).atmSnapshot(player.getUUID()));
            buffer.writeBoolean(machine.removalRole(player));
        });
    }
    public void addMailboxEntry(MailboxView entry) {
        if (page != 4) return;
        mailbox.addFirst(entry);
        while (mailbox.size() > MailboxStore.MAX_ENTRIES) mailbox.removeLast();
    }
    public void replaceMailbox(java.util.List<MailboxView> entries) {
        if (page != 4) return;
        mailbox.clear(); mailbox.addAll(entries);
    }
    public boolean canManage(Player player) { return machine == null ? manager : machine.canManage(player); }
    @Override public void removed(Player player) {
        super.removed(player);
        if (player instanceof ServerPlayer && kind == MachineKind.ATM) clearContainer(player, inventory);
    }
    @Override public void broadcastChanges() {
        super.broadcastChanges();
        if (viewer instanceof ServerPlayer player && machine != null && ledger != null) {
            if (page == 4 && player.server.getTickCount() - lastMailboxReconcileTick >= 100) {
                lastMailboxReconcileTick = player.server.getTickCount();
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                        new de.kuro.kuronomics.network.MailboxSnapshot(containerId,
                                new MailboxService(player.server).atmSnapshot(player.getUUID())));
            }
            long version = ledger.accountVersion(player.getUUID());
            boolean manages = kind == MachineKind.COMPANY_SHOP ? manager : canManage(player);
            long currentProfit = manages ? machine.profit() : 0;
            long currentPrice = machine.price();
            if (lastSnapshot != null && version == seenAccountVersion && currentProfit == seenProfit
                    && currentPrice == seenPrice && manages == seenManager) return;
            var economy = new EconomyService(ledger);
            var account = economy.account(player.getUUID());
            String reason = account.suspensionReason();
            var snapshot = new de.kuro.kuronomics.network.MachineSnapshot(containerId, economy.getBalance(player.getUUID()), currentProfit,
                    currentPrice, account.suspended(), reason.substring(0, Math.min(2048, reason.length())));
            seenAccountVersion = version; seenProfit = currentProfit; seenPrice = currentPrice; seenManager = manages;
            if (!snapshot.equals(lastSnapshot)) {
                lastSnapshot = snapshot;
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player, snapshot);
            }
        }
    }
    @Override public boolean stillValid(Player player) {
        if (kind == MachineKind.COMPANY_SHOP && machine != null && !machine.assigned()) return false;
        return player.level().getBlockState(pos).is(ModContent.block(kind)) && player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) <= 64;
    }
    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        if (!stillValid(player)) return;
        if ((kind.shop() || kind.vending()) && slot >= 0 && slot < kind.slots) {
            if (player instanceof ServerPlayer serverPlayer && type == ClickType.PICKUP && button == 0 && inventory.getItem(slot).getCount() > 0 && getCarried().isEmpty()) {
                ItemStack expected = inventory.getItem(slot).copy();
                de.kuro.kuronomics.economy.EconomyActions.submit(serverPlayer, this, java.util.UUID.randomUUID(), () -> attempt(serverPlayer, () -> {
                    if (!getCarried().isEmpty() || !ItemStack.isSameItemSameComponents(expected, inventory.getItem(slot))
                            || kind.wholeStack() && expected.getCount() != inventory.getItem(slot).getCount())
                        throw new IllegalArgumentException("This shop item changed. Review it and try again.");
                    purchase(serverPlayer, slot);
                })); return;
            }
            if (!canManage(player)) return;
            if (player instanceof ServerPlayer && type == ClickType.PICKUP && button == 1 && getCarried().isEmpty()) {
                ItemStack removed = Prices.clear(inventory.removeItemNoUpdate(slot));
                inventory.setChanged(); player.getInventory().placeItemBackInInventory(removed); broadcastChanges(); return;
            }
        }
        super.clicked(slot, button, type, player);
    }
    private void purchase(ServerPlayer player, int slot) {
        ItemStack stock = inventory.getItem(slot); long price = Prices.get(stock);
        if (price <= 0) throw new IllegalArgumentException("This item does not have a valid price.");
        ItemStack bought = Prices.clear(stock.copyWithCount(kind.wholeStack() ? stock.getCount() : 1));
        String boughtName = bought.getHoverName().getString();
        if (!kind.vending() && !hasRoomFor(player.getInventory(), bought)) throw new IllegalArgumentException("Not enough inventory space!");
        if (kind.vending()) {
            long profit = Math.addExact(machine.profit(), price);
            CashService.pay(player, price, bought); machine.profit(profit);
            if (!kind.unlimited()) inventory.removeItem(slot, bought.getCount());
            player.getInventory().placeItemBackInInventory(bought); inventory.setChanged(); broadcastChanges();
            player.inventoryMenu.sendAllDataToRemote();
            player.playNotifySound(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, net.minecraft.sounds.SoundSource.PLAYERS, .7F, 1.1F);
        } else {
            bought = new PlayerShopService(player.server).buy(player, machine, slot);
            boughtName = bought.getHoverName().getString();
        }
        player.sendSystemMessage(de.kuro.kuronomics.util.ModText.legacy("§aYou bought §e" + boughtName + "§a for §e" + Money.format(price) + "§a!"));
    }
    private static boolean hasRoomFor(Inventory inventory, ItemStack incoming) {
        if (inventory.getFreeSlot() != -1) return true;
        int room = mergeRoom(inventory, inventory.getItem(40), incoming);
        for (ItemStack stack : inventory.items) room += mergeRoom(inventory, stack, incoming);
        return room >= incoming.getCount();
    }
    private static int mergeRoom(Inventory inventory, ItemStack stack, ItemStack incoming) {
        if (stack.isEmpty() || !stack.isStackable() || !ItemStack.isSameItemSameComponents(stack, incoming)) return 0;
        return Math.max(0, inventory.getMaxStackSize(stack) - stack.getCount());
    }
    public void action(ServerPlayer player, String action, long value) { action(player, action, value, java.util.UUID.randomUUID()); }
    public void action(ServerPlayer player, String action, long value, java.util.UUID reference) {
        if (player.containerMenu != this || !stillValid(player)) return;
        attempt(player, () -> {
            if (kind == MachineKind.ATM) {
                if (action.equals("page")) {
                    if (value < 0 || value > 4) throw new IllegalArgumentException("Invalid ATM page");
                    if (value > 0 && value < 4) new EconomyService(EconomyLedger.get(player.server)).requireActive(player.getUUID());
                    open(player, machine, (int) value); return;
                }
                if (!action.equals("balance")) new EconomyService(EconomyLedger.get(player.server)).requireActive(player.getUUID());
                int requiredPage = switch (action) { case "deposit" -> 1; case "withdraw" -> 2; case "blank", "validate", "redeem" -> 3; default -> page; };
                if (page != requiredPage) throw new IllegalArgumentException("Open the appropriate ATM page first");
                switch (action) {
                    case "deposit" -> CashService.deposit(player, Math.toIntExact(value), reference);
                    case "withdraw" -> CashService.withdraw(player, value, reference);
                    case "blank" -> { if (inventory.getItem(3).isEmpty()) { inventory.setItem(3, new ItemStack(ModContent.CHECK.get())); ModContent.play(player.level(), pos, "atm_print"); } }
                    case "validate" -> {
                        if (!inventory.getItem(1).isEmpty()) throw new IllegalArgumentException("Take the validated check first");
                        ItemStack validated = CheckService.validate(player, inventory.getItem(2)); inventory.removeItem(2, 1); inventory.setItem(1, validated);
                        ModContent.play(player.level(), pos, "atm_print");
                    }
                    case "redeem" -> { CheckService.redeem(player, inventory.getItem(0)); inventory.setChanged(); }
                    case "balance" -> player.sendSystemMessage(de.kuro.kuronomics.util.ModText.legacy("Balance: §a" + Money.format(new EconomyService(EconomyLedger.get(player.server)).getBalance(player.getUUID()))));
                    default -> throw new IllegalArgumentException("Unknown ATM action");
                }
            } else if (kind == MachineKind.PRINTER && action.equals("price")) {
                if (!canManage(player)) throw new IllegalArgumentException("Only the owner may set this printer's price"); machine.price(value);
                player.sendSystemMessage(de.kuro.kuronomics.util.ModText.legacy(value == 0 ? "§ePrice printer disabled." : "§aPrice set to §e" + Money.format(value)));
            } else if (action.equals("collect") && kind.vending()) {
                if (!canManage(player)) throw new IllegalArgumentException("Only the owner may collect profits");
                if (machine.profit() <= 0) throw new IllegalArgumentException("This vending machine has not made any money yet!");
                if (player.getInventory().getFreeSlot() == -1) throw new IllegalArgumentException("Not enough inventory space!");
                ItemStack check = CheckService.issue(EconomyLedger.get(player.server), machine.profit()); machine.profit(0); player.getInventory().placeItemBackInInventory(check);
                ModContent.play(player.level(), pos, "atm_print");
            } else if (action.equals("remove") && (kind.shop() || kind.vending())) {
                if (!machine.canRemove(player)) throw new IllegalArgumentException("Only an authorized owner may remove this machine; company shops must be empty.");
                if (machine.profit() > 0) throw new IllegalArgumentException("Collect the profits first"); player.closeContainer(); player.level().destroyBlock(pos, true, player);
            } else throw new IllegalArgumentException("Invalid machine action");
            broadcastChanges();
        });
    }
    private static void attempt(ServerPlayer player, Runnable action) {
        try { action.run(); } catch (ArithmeticException | IllegalArgumentException e) {
            var color = e.getMessage() != null && e.getMessage().startsWith("This vending machine has not made")
                    ? net.minecraft.ChatFormatting.DARK_RED : net.minecraft.ChatFormatting.RED;
            player.sendSystemMessage(Component.literal(e.getMessage() == null ? "Could not complete that action." : e.getMessage()).withStyle(color));
            ModContent.play(player.level(), player.blockPosition(), "error");
        }
    }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (!stillValid(player) || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        if ((kind.shop() || kind.vending()) && !canManage(player)) return ItemStack.EMPTY;
        Slot slot = slots.get(index); if (!slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem(), original = stack.copy();
        if (index < machineSlots ? !moveItemStackTo(stack, machineSlots, slots.size(), true) : !moveItemStackTo(stack, 0, machineSlots, false)) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged(); return original;
    }
}
