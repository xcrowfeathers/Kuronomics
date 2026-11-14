package de.kuro.kuronomics.item;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.items.ComponentItemHandler;

public final class WalletStorage extends ComponentItemHandler {
    private static final TagKey<Item> ALLOWED = TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("kuronomics", "walletitems"));
    public WalletStorage(ItemStack stack) { super(stack, DataComponents.CONTAINER, 18); }
    @Override public boolean isItemValid(int slot, ItemStack stack) { return stack.isEmpty() || !(stack.getItem() instanceof WalletItem) && stack.is(ALLOWED); }
}
