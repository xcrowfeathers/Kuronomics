package de.kuro.kuronomics.economy;

import de.kuro.kuronomics.block.*;
import de.kuro.kuronomics.economy.data.*;
import de.kuro.kuronomics.item.Prices;
import de.kuro.kuronomics.mailbox.MailboxService;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class PlayerShopService {
    private static final String PLAYER_MARKERS = "kuronomicsPlayerShopTransactions";
    private final MinecraftServer server;
    private final PlayerShopTransactionStore store;
    public PlayerShopService(MinecraftServer server) { this.server = Objects.requireNonNull(server); store = PlayerShopTransactionStore.get(server); }

    public ItemStack buy(ServerPlayer player, MachineBlockEntity machine, int slot) {
        checkThread(); Objects.requireNonNull(player); Objects.requireNonNull(machine);
        if (player.server != server || machine.getLevel() != player.serverLevel() || !machine.kind.shop())
            throw new IllegalArgumentException("This shop is no longer available.");
        if (machine.kind == MachineKind.COMPANY_SHOP && store.pendingAt(player.serverLevel().dimension().location().toString(), machine.getBlockPos().asLong()))
            throw new IllegalArgumentException("This shop has a purchase waiting for recovery.");
        ItemStack stock = machine.getItem(slot); long price = Prices.get(stock);
        if (price <= 0) throw new IllegalArgumentException("This item does not have a valid price.");
        int quantity = machine.kind.wholeStack() ? stock.getCount() : 1;
        ItemStack bought = Prices.clear(stock.copyWithCount(quantity));
        if (!hasCapacity(player.getInventory(), bought)) throw new IllegalArgumentException("Not enough inventory space!");
        UUID company = machine.companyId();
        UUID owner = machine.kind == MachineKind.SERVER_SHOP || machine.kind == MachineKind.COMPANY_SHOP ? null : machine.owner();
        if (machine.kind == MachineKind.COMPANY_SHOP && company == null) throw new IllegalArgumentException("Shop has no company.");
        if (machine.kind != MachineKind.SERVER_SHOP && machine.kind != MachineKind.COMPANY_SHOP && owner == null) throw new IllegalArgumentException("Shop has no owner");
        EconomyService economy = new EconomyService(EconomyLedger.get(server)); economy.requireActive(player.getUUID());
        if (economy.getAvailableBalance(player.getUUID()) < price) throw new IllegalArgumentException("Not enough money!");
        if (owner != null) Math.addExact(economy.account(owner).pendingIncome(), price);
        if (company != null) { economy.requireActive(EconomicOwner.company(company)); Math.addExact(economy.account(EconomicOwner.company(company)).pendingIncome(), price); }
        UUID reference = UUID.randomUUID(); String dimension = player.serverLevel().dimension().location().toString();
        String fingerprint = String.join("|", player.getUUID().toString(), String.valueOf(owner), dimension,
                machine.getBlockPos().toShortString(), Integer.toString(slot), Integer.toString(quantity), Long.toString(price));
        var trade = new PlayerShopTransactionStore.Trade(reference, fingerprint, player.getUUID(), owner, dimension,
                machine.getBlockPos().asLong(), machine.kind, slot, stock.copyWithCount(1), bought, price,
                machine.kind.unlimited(), false, false, company);
        store.prepare(trade); saveData(); execute(player, trade); return bought;
    }

    public void recover(ServerPlayer player) {
        checkThread(); for (var trade : store.pendingFor(player.getUUID())) {
            try { execute(player, trade); }
            catch (RuntimeException error) { com.mojang.logging.LogUtils.getLogger().error("Player-shop transaction {} recovery is pending", trade.reference(), error); }
        }
    }

    private void execute(ServerPlayer player, PlayerShopTransactionStore.Trade original) {
        var trade = Objects.requireNonNull(store.trade(original.reference()));
        if (!trade.buyer().equals(player.getUUID())) throw new IllegalArgumentException("Shop transaction belongs to another player.");
        if (trade.company() == null && trade.owner() == null && !new EconomyService(EconomyLedger.get(server)).isTransactionProcessed(trade.reference()))
            Math.addExact(StateTreasury.balance(EconomyLedger.get(server)), trade.price());
        // Fund the company's income before changing the chunk. Recovery must still work if the company closes.
        if (trade.company() != null && !new EconomyService(EconomyLedger.get(server)).isTransactionProcessed(trade.reference())) {
            var company = EconomyLedger.get(server).companies().company(trade.company());
            if (company == null || company.status() != de.kuro.kuronomics.company.Company.Status.ACTIVE) {
                store.complete(trade.progress(false, false, true)); saveData(); return;
            }
            new EconomyService(EconomyLedger.get(server)).fundedSale(trade.buyer(), EconomicOwner.company(trade.company()), trade.price(),
                    java.math.BigDecimal.ZERO, trade.reference(), "Shop Sales", EconomyLedger.get(server).market()); saveData();
        }
        ServerLevel level = level(trade.dimension()); BlockPos pos = BlockPos.of(trade.position());
        MachineBlockEntity machine = level.getBlockEntity(pos) instanceof MachineBlockEntity value && value.kind == trade.kind() ? value : null;
        if (!trade.kind().unlimited()) {
            if (machine == null) throw new IllegalStateException("The shop for this pending purchase is unavailable.");
            if (!machine.hasShopTransaction(trade.reference())) {
                ItemStack stock = machine.getItem(trade.slot());
                if (!ItemStack.isSameItemSameComponents(stock.copyWithCount(1), trade.stockItem()) || stock.getCount() < trade.boughtItem().getCount())
                    throw new IllegalStateException("The stock for this pending purchase changed.");
                machine.removeItem(trade.slot(), trade.boughtItem().getCount()); machine.markShopTransaction(trade.reference());
            }
            // Retry the checkpoint too; a failed save may have left the marker only in memory.
            if (!trade.stockApplied()) ShopChunkPersistence.save(level, pos);
        }
        if (!trade.stockApplied()) {
            trade = trade.progress(true, trade.delivered(), false); store.update(trade);
        }
        EconomyService economy = new EconomyService(EconomyLedger.get(server));
        if (!economy.isTransactionProcessed(trade.reference())) {
            if (trade.owner() == null) economy.collectState(trade.buyer(), trade.price(), trade.reference(), "server shop purchase");
            else economy.shopSale(trade.buyer(), trade.owner(), trade.price(), trade.reference());
            saveData();
        }
        if (!trade.delivered()) {
            if (!hasPlayerMarker(player, trade.reference())) {
                if (!hasCapacity(player.getInventory(), trade.boughtItem())) throw new IllegalStateException("The purchased item is awaiting inventory space.");
                player.getInventory().placeItemBackInInventory(trade.boughtItem()); markPlayer(player, trade.reference());
                player.inventoryMenu.sendAllDataToRemote();
                player.playNotifySound(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, net.minecraft.sounds.SoundSource.PLAYERS, .7F, 1.1F);
            }
            de.kuro.kuronomics.economy.PlayerPersistence.save(player);
            trade = trade.progress(true, true, false); store.update(trade);
        }
        if (trade.company() == null) new MailboxService(server).payment(trade.reference(), trade.buyer(), trade.owner(), trade.price(),
                economy.getBalance(trade.buyer()), null, (trade.owner()==null?"Bank Shop Purchase: ":"Shop: ") + trade.boughtItem().getHoverName().getString());
        trade = trade.progress(true, true, true); store.update(trade); store.complete(trade); saveData();
        clearPlayerMarker(player, trade.reference());
        if (machine != null && machine.hasShopTransaction(trade.reference())) {
            machine.clearShopTransaction(trade.reference());
        }
    }

    private ServerLevel level(String dimension) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(dimension)));
        if (level == null) throw new IllegalStateException("Shop dimension is unavailable."); return level;
    }
    private static boolean hasCapacity(Inventory inventory, ItemStack incoming) {
        long room = 0; int max = Math.min(inventory.getMaxStackSize(incoming), incoming.getMaxStackSize());
        for (ItemStack stack : inventory.items) {
            if (stack.isEmpty()) room += max;
            else if (ItemStack.isSameItemSameComponents(stack, incoming)) room += Math.max(0, max - stack.getCount());
            if (room >= incoming.getCount()) return true;
        }
        return false;
    }
    private static boolean hasPlayerMarker(ServerPlayer player, UUID reference) {
        for (Tag raw : player.getPersistentData().getList(PLAYER_MARKERS, Tag.TAG_INT_ARRAY))
            if (reference.equals(NbtUtils.loadUUID(raw))) return true;
        return false;
    }
    private static void markPlayer(ServerPlayer player, UUID reference) {
        ListTag list = player.getPersistentData().getList(PLAYER_MARKERS, Tag.TAG_INT_ARRAY);
        if (!hasPlayerMarker(player, reference)) list.add(NbtUtils.createUUID(reference));
        player.getPersistentData().put(PLAYER_MARKERS, list);
    }
    private static void clearPlayerMarker(ServerPlayer player, UUID reference) {
        de.kuro.kuronomics.economy.FinancialWriter.await(player.server); // Wait for the completed transaction to reach disk before clearing its recovery marker.

        ListTag list = player.getPersistentData().getList(PLAYER_MARKERS, Tag.TAG_INT_ARRAY);
        if (!list.removeIf(raw -> reference.equals(NbtUtils.loadUUID(raw)))) return;
        if (list.isEmpty()) player.getPersistentData().remove(PLAYER_MARKERS); else player.getPersistentData().put(PLAYER_MARKERS, list);
        
    }
    private void saveData() { de.kuro.kuronomics.economy.FinancialPersistence.save(server); }
    private void checkThread() { if (!server.isSameThread()) throw new IllegalStateException("Player shops accessed off server thread"); }
}
