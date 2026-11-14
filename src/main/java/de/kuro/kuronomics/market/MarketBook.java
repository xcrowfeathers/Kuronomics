package de.kuro.kuronomics.market;

import de.kuro.kuronomics.company.SnapshotMap;
import de.kuro.kuronomics.economy.EconomicOwner;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;

/** Virtual stock and pending inventory recovery are part of the ledger snapshot. */
public final class MarketBook {
    public static final MarketBook EMPTY = new MarketBook(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), 0);
    private final Map<UUID, MarketListing> listings;
    private final Map<UUID, MarketTransfer> transfers;
    private final Map<EconomicOwner, Set<UUID>> owners;
    private final Map<UUID, UUID> players, locked;
    private final long catalog;
    private MarketBook(Map<UUID, MarketListing> listings, Map<UUID, MarketTransfer> transfers,
                       Map<EconomicOwner, Set<UUID>> owners, Map<UUID, UUID> players, Map<UUID, UUID> locked, long catalog) {
        this.listings = listings; this.transfers = transfers; this.owners = owners; this.players = players; this.locked = locked; this.catalog = catalog;
    }
    public Map<UUID, MarketListing> listings() { return listings; }
    public Set<UUID> owned(EconomicOwner owner) { return owners.getOrDefault(owner, Set.of()); }
    public MarketListing listing(UUID id) { return listings.get(id); }
    public MarketTransfer pending(UUID player) { UUID id = players.get(player); return id == null ? null : transfers.get(id); }
    public boolean locked(UUID listing) { return locked.containsKey(listing); }
    public long catalogRevision() { return catalog; }
    public void validateIntents(java.util.function.Predicate<UUID> committed) {
        for (UUID id : transfers.keySet()) if (!committed.test(id)) throw new IllegalStateException("Market inventory intent has no committed transaction");
    }
    public MarketBook update(UUID id, MarketListing next, MarketTransfer transfer) {
        var entries = new SnapshotMap<>(listings); var byOwner = new SnapshotMap<>(owners); long nextCatalog = catalog;
        if (id != null) {
            MarketListing before = listings.get(id);
            if (next != null && (!id.equals(next.id()) || before != null && (!before.seller().equals(next.seller())
                    || !net.minecraft.world.item.ItemStack.isSameItemSameComponents(before.item(), next.item()) || before.createdAt() != next.createdAt())))
                throw new IllegalArgumentException("A listing's owner and item cannot change");
            if (before == null && next != null || before != null && next == null) {
                EconomicOwner owner = before == null ? next.seller() : before.seller(); Set<UUID> ids = new HashSet<>(owned(owner));
                if (next == null) ids.remove(id); else ids.add(id);
                if (ids.isEmpty()) byOwner.remove(owner); else byOwner.put(owner, Set.copyOf(ids)); nextCatalog = Math.incrementExact(catalog);
            }
            if (next == null) entries.remove(id); else entries.put(id, next);
        }
        var operations = new SnapshotMap<>(transfers); var playerIndex = new SnapshotMap<>(players); var locks = new SnapshotMap<>(locked);
        if (transfer != null) {
            if (transfers.containsKey(transfer.id()) || players.containsKey(transfer.player()) || locked.containsKey(transfer.listing()))
                throw new IllegalArgumentException("An inventory transfer is still pending");
            operations.put(transfer.id(), transfer); playerIndex.put(transfer.player(), transfer.id()); locks.put(transfer.listing(), transfer.id());
        }
        return new MarketBook(entries.freeze(), operations.freeze(), byOwner.freeze(), playerIndex.freeze(), locks.freeze(), nextCatalog);
    }
    public MarketBook finish(MarketTransfer transfer) {
        if (transfers.get(transfer.id()) != transfer) throw new IllegalArgumentException("Transfer changed");
        var ops = new SnapshotMap<>(transfers); var p = new SnapshotMap<>(players); var l = new SnapshotMap<>(locked);
        ops.remove(transfer.id()); p.remove(transfer.player()); l.remove(transfer.listing());
        return new MarketBook(listings, ops.freeze(), owners, p.freeze(), l.freeze(), catalog);
    }
    public CompoundTag save(HolderLookup.Provider registries) { return new Encoder().encode(this, registries).copy(); }
    private static String bucket(UUID id) { int h = id.hashCode(); return Integer.toHexString((h ^ h >>> 16) & 255); }
    private static CompoundTag shallow(CompoundTag source) { CompoundTag copy = new CompoundTag(); source.getAllKeys().forEach(k -> copy.put(k, source.get(k))); return copy; }
    /** Copy only changed NBT buckets so one sale doesn't re-encode the whole catalog. */
    public static final class Encoder {
        private MarketBook previous; private CompoundTag root;
        public CompoundTag encode(MarketBook book, HolderLookup.Provider registries) {
            if (book == previous) return root;
            CompoundTag next = new CompoundTag(); next.putInt("version", 1); next.putLong("catalog", book.catalog);
            CompoundTag entries = previous == null ? new CompoundTag() : shallow(root.getCompound("listings"));
            Set<UUID> changed = previous == null ? book.listings.keySet() : SnapshotMap.differences(previous.listings, book.listings);
            Map<String, CompoundTag> buckets = new HashMap<>();
            for (UUID id : changed) {
                String key = bucket(id); CompoundTag group = buckets.computeIfAbsent(key, k -> shallow(entries.getCompound(k)));
                MarketListing listing = book.listing(id); if (listing == null) group.remove(id.toString()); else group.put(id.toString(), listing.save(registries));
            }
            buckets.forEach((key, value) -> { if (value.isEmpty()) entries.remove(key); else entries.put(key, value); }); next.put("listings", entries);
            if (previous != null && book.transfers == previous.transfers) next.put("transfers", root.get("transfers"));
            else { CompoundTag ops = new CompoundTag(); book.transfers.forEach((id, transfer) -> ops.put(id.toString(), transfer.save(registries))); next.put("transfers", ops); }
            previous = book; root = next; return next;
        }
    }
    public static MarketBook load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.getInt("version") != 1 || !tag.contains("catalog", Tag.TAG_LONG) || !tag.contains("listings", Tag.TAG_COMPOUND) || !tag.contains("transfers", Tag.TAG_COMPOUND)) throw new IllegalStateException("Invalid marketplace stock data");
        var entries = new SnapshotMap<UUID, MarketListing>(); Map<EconomicOwner, Set<UUID>> owners = new HashMap<>(); CompoundTag groups = tag.getCompound("listings");
        for (String key : groups.getAllKeys()) {
          if (!groups.contains(key, Tag.TAG_COMPOUND)) throw new IllegalStateException("Invalid marketplace listing bucket");
          for (String id : groups.getCompound(key).getAllKeys()) {
            MarketListing listing = MarketListing.load(groups.getCompound(key).getCompound(id), registries);
            if (!listing.id().toString().equals(id) || !bucket(listing.id()).equals(key) || entries.putIfAbsent(listing.id(), listing) != null) throw new IllegalStateException("Invalid listing identity");
            owners.computeIfAbsent(listing.seller(), ignored -> new HashSet<>()).add(listing.id());
          }
        }
        var ops = new SnapshotMap<UUID, MarketTransfer>(); var players = new SnapshotMap<UUID, UUID>(); var locked = new SnapshotMap<UUID, UUID>();
        CompoundTag saved = tag.getCompound("transfers"); for (String id : saved.getAllKeys()) {
            MarketTransfer transfer = MarketTransfer.load(saved.getCompound(id), registries);
            if (!transfer.id().toString().equals(id) || players.putIfAbsent(transfer.player(), transfer.id()) != null || locked.putIfAbsent(transfer.listing(), transfer.id()) != null) throw new IllegalStateException("Duplicate market inventory transfer");
            ops.put(transfer.id(), transfer);
        }
        Map<EconomicOwner, Set<UUID>> frozen = new HashMap<>(); owners.forEach((owner, ids) -> frozen.put(owner, Set.copyOf(ids)));
        if (tag.getLong("catalog") < 0) throw new IllegalStateException("Invalid catalog revision");
        return new MarketBook(entries.freeze(), ops.freeze(), Map.copyOf(frozen), players.freeze(), locked.freeze(), tag.getLong("catalog"));
    }
}
