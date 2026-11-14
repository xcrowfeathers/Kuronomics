package de.kuro.kuronomics.menu;

import de.kuro.kuronomics.registry.ModContent;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;

public final class WalletMenu extends AbstractContainerMenu {
    private final ItemStack wallet;
    private final int walletSlot;
    private final de.kuro.kuronomics.item.WalletStorage contents;
    public WalletMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) { this(id, inventory, buffer.readVarInt()); }
    public WalletMenu(int id, Inventory inventory, int walletSlot) {
        super(ModContent.WALLET_MENU.get(), id); this.walletSlot = walletSlot; wallet = inventory.getItem(walletSlot);
        contents = new de.kuro.kuronomics.item.WalletStorage(wallet);
        for (int i = 0; i < 18; i++) addSlot(new net.neoforged.neoforge.items.ItemHandlerCopySlot(contents, i, 11 + i % 9 * 18, 28 + i / 9 * 18));
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++) addPlayerSlot(inventory, col + row * 9 + 9, 12 + col * 18, 90 + row * 18);
        for (int col = 0; col < 9; col++) addPlayerSlot(inventory, col, 12 + col * 18, 148);
    }
    private void addPlayerSlot(Inventory inventory, int index, int x, int y) {
        addSlot(new Slot(inventory, index, x, y) {
            @Override public boolean mayPickup(Player player) { return index != walletSlot; }
            @Override public boolean mayPlace(ItemStack stack) { return index != walletSlot; }
        });
    }
    @Override public boolean stillValid(Player player) { return player.getInventory().getItem(walletSlot) == wallet && wallet.is(ModContent.WALLET.get()); }
    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        if (!stillValid(player) || type == ClickType.SWAP && (button == walletSlot || button == 40 && walletSlot == 40)) return;
        super.clicked(slot, button, type, player);
    }
    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (!stillValid(player) || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index); if (!slot.hasItem() || !slot.mayPickup(player)) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem(), original = stack.copy();
        if (index < 18 ? !moveItemStackTo(stack, 18, slots.size(), true) : !moveItemStackTo(stack, 0, 18, false)) return ItemStack.EMPTY;
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY); else slot.setChanged(); return original;
    }
}

