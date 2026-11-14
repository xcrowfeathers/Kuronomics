package de.kuro.kuronomics.market;

import de.kuro.kuronomics.economy.EconomicOwner;
import java.util.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;

public record MarketListing(UUID id, EconomicOwner seller, ItemStack item, long price, long stock, long revision, long createdAt, String searchText, CompoundTag encodedItem) {
    public MarketListing(UUID id, EconomicOwner seller, ItemStack item, long price, long stock, long revision, long createdAt, String searchText) {
        this(id, seller, item, price, stock, revision, createdAt, searchText, null);
    }
    public MarketListing {
        Objects.requireNonNull(id); Objects.requireNonNull(seller); Objects.requireNonNull(searchText);
        if ((!seller.isPlayer() && !seller.isCompany()) || item == null || item.isEmpty() || price <= 0 || stock < 0 || revision < 0 || createdAt < 0)
            throw new IllegalArgumentException("Invalid marketplace listing");
        item = item.copyWithCount(1);
        encodedItem = encodedItem == null ? null : encodedItem.copy();
    }
    @Override public ItemStack item() { return item.copy(); }
    @Override public CompoundTag encodedItem() { return encodedItem == null ? null : encodedItem.copy(); }
    public MarketListing stock(long value) { return new MarketListing(id, seller, item, price, value, Math.incrementExact(revision), createdAt, searchText, encodedItem); }
    public MarketListing price(long value) { return new MarketListing(id, seller, item, value, stock, Math.incrementExact(revision), createdAt, searchText, encodedItem); }
    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag(); tag.putUUID("id", id); tag.put("seller", seller.save()); tag.put("item", encodedItem == null ? item.save(registries) : encodedItem.copy());
        tag.putLong("price", price); tag.putLong("stock", stock); tag.putLong("revision", revision); tag.putLong("createdAt", createdAt); tag.putString("search", searchText); return tag;
    }
    public static MarketListing load(CompoundTag tag, HolderLookup.Provider registries) {
        for (String key : List.of("price", "stock", "revision", "createdAt")) if (!tag.contains(key, Tag.TAG_LONG)) throw new IllegalStateException("Missing listing " + key);
        return new MarketListing(tag.getUUID("id"), EconomicOwner.load(tag.getCompound("seller")), ItemStack.parse(registries, tag.get("item")).orElseThrow(),
                tag.getLong("price"), tag.getLong("stock"), tag.getLong("revision"), tag.getLong("createdAt"), tag.getString("search"), tag.getCompound("item"));
    }
}
