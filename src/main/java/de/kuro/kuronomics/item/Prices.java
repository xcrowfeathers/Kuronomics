package de.kuro.kuronomics.item;

import de.kuro.kuronomics.economy.Money;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public final class Prices {
    private Prices() {}
    public static CompoundTag data(ItemStack stack) { return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag(); }
    public static long get(ItemStack stack) {
        CompoundTag tag = data(stack);
        if (tag.contains("price", Tag.TAG_DOUBLE)) return Money.legacy(tag.getDouble("price"));
        return tag.contains("price", Tag.TAG_ANY_NUMERIC) ? tag.getLong("price") : -1;
    }
    public static void set(ItemStack stack, long cents) {
        if (stack.isEmpty() || cents < 0) throw new IllegalArgumentException("Hold an item and enter a non-negative price");
        removeGeneratedName(stack, data(stack));
        // False means the price only belongs in the tooltip, unlike old items with prices added to their
        // names.
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> { tag.putLong("price", cents); tag.putBoolean("kuronomicsPriceLabel", false); });
    }
    public static boolean hasGeneratedName(ItemStack stack) {
        var tag=data(stack);
        return tag.getBoolean("kuronomicsPriceLabel") && stack.has(DataComponents.ITEM_NAME) || legacyBase(stack,tag)!=null;
    }
    /** Show old priced items without changing their inventory data from a tooltip. */
    public static Component unpricedName(ItemStack stack) {
        var tag=data(stack);var legacy=legacyBase(stack,tag);if(legacy!=null)return legacy;
        var custom=stack.get(DataComponents.CUSTOM_NAME);if(custom!=null)return custom;
        return tag.getBoolean("kuronomicsPriceLabel")?stack.getItem().getName(stack):stack.getHoverName();
    }
    private static Component legacyBase(ItemStack stack,CompoundTag tag) {
        if(tag.contains("kuronomicsPriceLabel",Tag.TAG_BYTE) || !tag.contains("price",Tag.TAG_ANY_NUMERIC))return null;
        var custom=stack.get(DataComponents.CUSTOM_NAME);if(custom==null)return null;
        String text=custom.getString();int split=text.lastIndexOf(" | $");
        if(split<=0 || !text.substring(split+4).matches("[0-9.,]+"))return null;
        return Component.literal(text.substring(0,split)).setStyle(custom.getStyle());
    }
    private static void removeGeneratedName(ItemStack stack,CompoundTag tag) {
        if(tag.getBoolean("kuronomicsPriceLabel"))stack.remove(DataComponents.ITEM_NAME);
        var base=legacyBase(stack,tag);if(base!=null)stack.set(DataComponents.CUSTOM_NAME,base);
    }
    public static ItemStack clear(ItemStack stack) {
        ItemStack result = stack.copy();
        CompoundTag original = data(result);
        removeGeneratedName(result, original);
        CustomData.update(DataComponents.CUSTOM_DATA, result, tag -> { tag.remove("price"); tag.remove("kuronomicsPriceLabel"); });
        return result;
    }
}
