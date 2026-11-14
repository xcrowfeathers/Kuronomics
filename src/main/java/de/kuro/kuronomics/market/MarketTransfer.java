package de.kuro.kuronomics.market;

import java.util.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;

/**
 * Commit this recovery request with money and stock first, then save inventory changes and their marker
 * together.
 */
public record MarketTransfer(UUID id, UUID player, UUID listing, List<Change> changes, CompoundTag encoded) {
    public MarketTransfer(UUID id, UUID player, UUID listing, List<Change> changes) { this(id, player, listing, changes, null); }
    public record Change(int slot, ItemStack before, ItemStack after) {
        public Change { if (slot < 0 || slot >= 36 || ItemStack.matches(before, after)) throw new IllegalArgumentException("Invalid inventory change"); before = before.copy(); after = after.copy(); }
        @Override public ItemStack before() { return before.copy(); }
        @Override public ItemStack after() { return after.copy(); }
    }
    public MarketTransfer { Objects.requireNonNull(id); Objects.requireNonNull(player); Objects.requireNonNull(listing); changes = List.copyOf(changes);
        if (changes.isEmpty() || changes.size() > 36 || changes.stream().map(Change::slot).distinct().count() != changes.size()) throw new IllegalArgumentException("Invalid market inventory transfer"); encoded = encoded == null ? null : encoded.copy(); }
    @Override public CompoundTag encoded() { return encoded == null ? null : encoded.copy(); }
    public MarketTransfer freeze(HolderLookup.Provider registries) { return new MarketTransfer(id, player, listing, changes, save(registries)); }
    public CompoundTag save(HolderLookup.Provider registries) {
        if (encoded != null) return encoded.copy();
        CompoundTag tag = new CompoundTag(); tag.putUUID("id", id); tag.putUUID("player", player); tag.putUUID("listing", listing);
        ListTag rows = new ListTag(); for (Change c : changes) { CompoundTag row = new CompoundTag(); row.putInt("slot", c.slot());
            row.put("before", c.before().saveOptional(registries)); row.put("after", c.after().saveOptional(registries)); rows.add(row); } tag.put("changes", rows); return tag;
    }
    public static MarketTransfer load(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.contains("changes", Tag.TAG_LIST)) throw new IllegalStateException("Missing market inventory changes");
        List<Change> changes = new ArrayList<>(); for (Tag raw : tag.getList("changes", Tag.TAG_COMPOUND)) { CompoundTag r = (CompoundTag) raw;
            if (!r.contains("slot", Tag.TAG_INT) || !r.contains("before", Tag.TAG_COMPOUND) || !r.contains("after", Tag.TAG_COMPOUND)) throw new IllegalStateException("Invalid market inventory change");
            changes.add(new Change(r.getInt("slot"), item(r.getCompound("before"), registries), item(r.getCompound("after"), registries))); }
        return new MarketTransfer(tag.getUUID("id"), tag.getUUID("player"), tag.getUUID("listing"), changes, tag);
    }
    private static ItemStack item(CompoundTag tag, HolderLookup.Provider registries) {
        return tag.isEmpty() ? ItemStack.EMPTY : ItemStack.parse(registries, tag).orElseThrow(() -> new IllegalStateException("Unreadable market inventory item"));
    }
}
