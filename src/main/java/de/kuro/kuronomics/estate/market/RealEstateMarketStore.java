package de.kuro.kuronomics.estate.market;

import de.kuro.kuronomics.Kuronomics;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** Only RealEstateMarketService should change listings. Rebuild lookup indexes from the saved data. */
public final class RealEstateMarketStore extends de.kuro.kuronomics.economy.data.FinancialSavedData {
    private static final String DATA_NAME = Kuronomics.MODID + "_real_estate_market";
    private final Map<UUID, RealEstateListing> listings = new LinkedHashMap<>();
    private final Map<UUID, UUID> byCreationReference = new HashMap<>();
    private final Map<String, UUID> activeByProperty = new HashMap<>();
    private final Map<UUID, LinkedHashSet<UUID>> bySeller = new HashMap<>();
    private final LinkedHashSet<UUID> active = new LinkedHashSet<>();
    private List<RealEstateListing> sortedActive;
    private final Map<UUID, PurchaseSettlement> settlements = new LinkedHashMap<>();
    private final de.kuro.kuronomics.economy.data.FinancialHistory historyRetention = new de.kuro.kuronomics.economy.data.FinancialHistory();
    public int pruneHistory(int maximum, long ageMillis, long now, Set<UUID> lockedOperations) {
        int count = historyRetention.prune(listings, maximum, ageMillis, now, (id, listing) -> {
            if (listing.state() == RealEstateListing.State.ACTIVE || listing.state() == RealEstateListing.State.SETTLEMENT_PENDING
                    || lockedOperations.contains(id)) return false;
            PurchaseSettlement settlement = settlements.get(id);
            return settlement == null || settlement.state() == PurchaseState.COMPLETED || settlement.state() == PurchaseState.FAILED;
        }, (id, listing) -> {
            historyRetention.retire(listing.creationReference(), listing.creationFingerprint());
            historyRetention.retire(id, "closed-listing");
            settlements.remove(id); byCreationReference.remove(listing.creationReference());
            var seller = bySeller.get(listing.sellerId());
            if (seller != null) { seller.remove(id); if (seller.isEmpty()) bySeller.remove(listing.sellerId()); }
        });
        if (count > 0) setDirty(); return count;
    }

    enum PurchaseState { CREATED, PAYMENT_CAPTURED, PROPERTY_TRANSFERRED, SELLER_PAYOUT_QUEUED, ROLLBACK_PENDING, COMPLETED, FAILED }
    record PurchaseSettlement(UUID id, UUID requestId, UUID listingId, UUID sellerId, UUID buyerId,
                              long gross, long tax, long net, PurchaseState state, String failureReason,
                              long createdAt, long updatedAt) {
        PurchaseSettlement {
            Objects.requireNonNull(id); Objects.requireNonNull(requestId); Objects.requireNonNull(listingId);
            Objects.requireNonNull(sellerId); Objects.requireNonNull(buyerId); Objects.requireNonNull(state);
            failureReason = Objects.requireNonNull(failureReason).strip();
            boolean failureState = state == PurchaseState.ROLLBACK_PENDING || state == PurchaseState.FAILED;
            if (gross <= 0 || tax < 0 || net < 0 || Math.addExact(tax, net) != gross || createdAt < 0 || updatedAt < createdAt
                    || failureReason.length() > 256 || failureState != !failureReason.isBlank())
                throw new IllegalArgumentException("Invalid real-estate purchase settlement");
        }
        PurchaseSettlement progress(PurchaseState next, String reason, long now) {
            return new PurchaseSettlement(id, requestId, listingId, sellerId, buyerId, gross, tax, net,
                    next, reason == null ? "" : reason, createdAt, now);
        }
    }

    public static RealEstateMarketStore get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Real-estate market accessed off server thread");
        return server.overworld().getDataStorage().computeIfAbsent(
                new Factory<>(RealEstateMarketStore::new, RealEstateMarketStore::load), DATA_NAME);
    }

    public static void validate(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DATA_NAME + ".dat");
        if (!Files.exists(file)) return;
        try { load(NbtIo.readCompressed(file, NbtAccounter.create(32 * 1024 * 1024)).getCompound("data"), server.registryAccess()); }
        catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Real-estate market data is unreadable; restore a backup before starting this world", error);
        }
    }

    RealEstateListing listing(UUID id) { return listings.get(id); }
    RealEstateListing byReference(UUID reference) {
        historyRetention.requireUnretired(reference);
        UUID id = byCreationReference.get(reference); return id == null ? null : listings.get(id);
    }
    RealEstateListing activeForProperty(String propertyId) {
        UUID id = activeByProperty.get(propertyId); return id == null ? null : listings.get(id);
    }
    List<RealEstateListing> activeListings() {
        if (sortedActive == null) sortedActive = active.stream().map(listings::get).filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(RealEstateListing::createdAt).reversed().thenComparing(RealEstateListing::id)).toList();
        return sortedActive;
    }
    List<RealEstateListing> sellerListings(UUID seller) {
        return bySeller.getOrDefault(seller, new LinkedHashSet<>()).stream().map(listings::get)
                .filter(Objects::nonNull).toList();
    }
    PurchaseSettlement settlement(UUID listingId) { return settlements.get(listingId); }
    Collection<PurchaseSettlement> settlements() { return List.copyOf(settlements.values()); }
    void prepareSettlement(PurchaseSettlement settlement) {
        PurchaseSettlement existing = settlements.get(settlement.listingId());
        if (existing != null && !existing.id().equals(settlement.id())) throw new IllegalStateException("Listing already has a purchase settlement");
        if (existing == null) { settlements.put(settlement.listingId(), settlement); setDirty(); }
    }
    void replaceSettlement(PurchaseSettlement settlement) {
        PurchaseSettlement existing = settlements.get(settlement.listingId());
        if (existing == null || !existing.id().equals(settlement.id())) throw new IllegalStateException("Unknown purchase settlement");
        settlements.put(settlement.listingId(), settlement); setDirty();
    }

    void add(RealEstateListing listing) {
        historyRetention.requireUnretired(listing.id()); historyRetention.requireUnretired(listing.creationReference());
        if (listings.containsKey(listing.id())) throw new IllegalArgumentException("Duplicate real-estate listing ID");
        if (byCreationReference.containsKey(listing.creationReference()))
            throw new IllegalArgumentException("Duplicate real-estate listing request");
        listings.put(listing.id(), listing); byCreationReference.put(listing.creationReference(), listing.id());
        index(listing); if (closed(listing)) historyRetention.record(listing.id()); setDirty();
    }

    void replace(RealEstateListing listing) {
        RealEstateListing old = listings.get(listing.id());
        if (old == null || !old.creationReference().equals(listing.creationReference()))
            throw new IllegalArgumentException("Unknown real-estate listing");
        unindex(old); listings.put(listing.id(), listing); index(listing); setDirty();
        if (closed(listing)) historyRetention.record(listing.id());
    }
    private static boolean closed(RealEstateListing listing) {
        return listing.state() != RealEstateListing.State.ACTIVE && listing.state() != RealEstateListing.State.SETTLEMENT_PENDING;
    }

    private void index(RealEstateListing listing) {
        sortedActive = null;
        bySeller.computeIfAbsent(listing.sellerId(), ignored -> new LinkedHashSet<>()).add(listing.id());
        if (listing.state() != RealEstateListing.State.ACTIVE) return;
        UUID duplicate = activeByProperty.putIfAbsent(listing.propertyId(), listing.id());
        if (duplicate != null && !duplicate.equals(listing.id()))
            throw new IllegalStateException("A property has multiple active market listings");
        active.add(listing.id());
    }

    private void unindex(RealEstateListing listing) {
        if (listing.state() != RealEstateListing.State.ACTIVE) return;
        active.remove(listing.id()); activeByProperty.remove(listing.propertyId(), listing.id());
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", 3); historyRetention.save(tag, "historyRetention"); ListTag values = new ListTag();
        for (RealEstateListing listing : listings.values()) {
            CompoundTag value = new CompoundTag(); value.putUUID("id", listing.id());
            value.putUUID("reference", listing.creationReference()); value.putString("fingerprint", listing.creationFingerprint());
            value.putString("property", listing.propertyId()); value.putUUID("seller", listing.sellerId());
            value.putString("name", listing.listingName()); value.putString("location", listing.location());
            value.putString("description", listing.description()); value.putLong("price", listing.price());
            value.putString("propertyName", listing.propertyName()); value.putString("dimension", listing.dimension());
            value.putLong("width", listing.width()); value.putLong("length", listing.length());
            value.putInt("taxBasisPoints", listing.taxBasisPoints()); value.putString("state", listing.state().name());
            value.putString("invalidReason", listing.invalidReason()); value.putLong("revision", listing.revision());
            value.putLong("createdAt", listing.createdAt()); value.putLong("updatedAt", listing.updatedAt()); values.add(value);
        }
        tag.put("listings", values); ListTag purchaseTags = new ListTag();
        for (PurchaseSettlement settlement : settlements.values()) {
            CompoundTag value = new CompoundTag(); value.putUUID("id", settlement.id()); value.putUUID("request", settlement.requestId());
            value.putUUID("listing", settlement.listingId()); value.putUUID("seller", settlement.sellerId()); value.putUUID("buyer", settlement.buyerId());
            value.putLong("gross", settlement.gross()); value.putLong("tax", settlement.tax()); value.putLong("net", settlement.net());
            value.putString("state", settlement.state().name()); value.putString("failure", settlement.failureReason());
            value.putLong("createdAt", settlement.createdAt()); value.putLong("updatedAt", settlement.updatedAt()); purchaseTags.add(value);
        }
        tag.put("purchaseSettlements", purchaseTags); return tag;
    }

    public static RealEstateMarketStore load(CompoundTag tag, HolderLookup.Provider registries) {
        int version = tag.getInt("version");
        if ((version < 1 || version > 3) || !tag.contains("listings", Tag.TAG_LIST)
                || version >= 2 && !tag.contains("purchaseSettlements", Tag.TAG_LIST))
            throw new IllegalStateException("Unsupported real-estate market data");
        RealEstateMarketStore store = new RealEstateMarketStore();
        for (Tag raw : tag.getList("listings", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) raw;
            try {
                RealEstateListing listing = new RealEstateListing(value.getUUID("id"), value.getUUID("reference"),
                        value.getString("fingerprint"), value.getString("property"), value.getUUID("seller"),
                        value.getString("name"), value.getString("location"), value.getString("description"),
                        value.getLong("price"), value.getString("propertyName"), value.getString("dimension"),
                        value.getLong("width"), value.getLong("length"), value.getInt("taxBasisPoints"),
                        RealEstateListing.State.valueOf(value.getString("state")), value.getString("invalidReason"),
                        value.getLong("revision"), value.getLong("createdAt"), value.getLong("updatedAt"));
                if (store.listings.putIfAbsent(listing.id(), listing) != null)
                    throw new IllegalStateException("Duplicate real-estate listing ID");
                if (store.byCreationReference.putIfAbsent(listing.creationReference(), listing.id()) != null)
                    throw new IllegalStateException("Duplicate real-estate listing request");
                store.index(listing);
            } catch (RuntimeException error) { throw new IllegalStateException("Invalid real-estate listing", error); }
        }
        if (version >= 2) for (Tag raw : tag.getList("purchaseSettlements", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) raw;
            try {
                PurchaseSettlement settlement = new PurchaseSettlement(value.getUUID("id"), value.getUUID("request"), value.getUUID("listing"),
                        value.getUUID("seller"), value.getUUID("buyer"), value.getLong("gross"), value.getLong("tax"), value.getLong("net"),
                        PurchaseState.valueOf(value.getString("state")), value.getString("failure"), value.getLong("createdAt"), value.getLong("updatedAt"));
                RealEstateListing listing = store.listings.get(settlement.listingId());
                if (listing == null || !listing.sellerId().equals(settlement.sellerId())
                        || store.settlements.putIfAbsent(settlement.listingId(), settlement) != null)
                    throw new IllegalStateException("Purchase settlement does not match its listing");
            } catch (RuntimeException error) { throw new IllegalStateException("Invalid real-estate purchase settlement", error); }
        }
        store.historyRetention.load(tag, "historyRetention", store.listings.values().stream().filter(RealEstateMarketStore::closed).map(RealEstateListing::id).toList(), version >= 3);
        for (RealEstateListing listing : store.listings.values()) {
            store.historyRetention.requireUnretired(listing.id()); store.historyRetention.requireUnretired(listing.creationReference());
        }
        store.setDirty(tag.getInt("version") < 3); return store;
    }
}
