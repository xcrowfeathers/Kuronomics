package de.kuro.kuronomics.auction;

import de.kuro.kuronomics.Kuronomics;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** Only AuctionHouseService should change auctions, listing journals or settlements. */
public final class AuctionHouseStore extends de.kuro.kuronomics.economy.data.FinancialSavedData {
    private static final String DATA_NAME = Kuronomics.MODID + "_auctions";
    private final Map<UUID, Auction> auctions = new LinkedHashMap<>();
    private final Map<UUID, Listing> listings = new LinkedHashMap<>();
    private final Map<UUID, PropertyListing> propertyListings = new LinkedHashMap<>();
    private final Map<UUID, Bid> bids = new LinkedHashMap<>();
    private final Map<UUID, CompletedAction> completedActions = new LinkedHashMap<>();
    private final de.kuro.kuronomics.economy.data.FinancialHistory historyRetention = new de.kuro.kuronomics.economy.data.FinancialHistory();
    public int pruneHistory(int maximum, long ageMillis, long now) {
        int count = historyRetention.prune(completedActions, maximum, ageMillis, now, (id, value) -> !auctions.containsKey(value.auctionId()) && !settlements.containsKey(value.auctionId()),
                (id, value) -> historyRetention.retire(id, value.fingerprint()));
        if (count > 0) setDirty(); return count;
    }
    /** Include saved journal entries even if they haven't been applied to the auction yet. */
    public Set<UUID> financialReferences() {
        Set<UUID> result = new HashSet<>();
        for (Auction auction : auctions.values()) {
            result.add(AuctionHouseService.settlementReference(auction.id()));
            for (UUID player : auction.participants()) result.add(AuctionHouseService.reservationReference(auction.id(), player));
            if (auction.highestBidderId() != null) result.add(AuctionHouseService.reservationReference(auction.id(), auction.highestBidderId()));
        }
        for (Settlement value : settlements.values()) {
            result.add(value.id()); if (value.reservationReference() != null) result.add(value.reservationReference());
        }
        for (Bid value : bids.values()) {
            result.add(AuctionHouseService.reservationReference(value.auctionId(), value.bidderId()));
            if (value.previousBidderId() != null) result.add(AuctionHouseService.reservationReference(value.auctionId(), value.previousBidderId()));
        }
        return result;
    }
    private final Map<UUID, Settlement> settlements = new LinkedHashMap<>();
    private final Map<UUID, HistoryWindow> history = new LinkedHashMap<>();
    private final Set<UUID> expiredHistory = new LinkedHashSet<>();
    private long nextDeadline = -1;
    private long nextItemDeadline = -1;
    private List<Auction> orderedAuctions;

    record Listing(UUID reference, String fingerprint, UUID auctionId, UUID sellerId, ItemStack item,
                   long startingBid, long minimumIncrease, long fee, int saleTaxBasisPoints, long createdAt, long deadline,
                   boolean feePaid, boolean itemRemoved, boolean auctionCreated, boolean settled) {
        Listing {
            Objects.requireNonNull(reference); Objects.requireNonNull(fingerprint); Objects.requireNonNull(auctionId);
            Objects.requireNonNull(sellerId);
            if (item == null || item.isEmpty() || startingBid <= 0 || minimumIncrease <= 0 || fee < 0
                    || saleTaxBasisPoints < 0 || saleTaxBasisPoints > 10_000
                    || createdAt < 0 || deadline <= createdAt) throw new IllegalArgumentException("Invalid auction listing journal entry");
            item = item.copy();
        }
        @Override public ItemStack item() { return item.copy(); }
        Listing progress(boolean paid, boolean removed, boolean created, boolean done) {
            return new Listing(reference, fingerprint, auctionId, sellerId, item, startingBid, minimumIncrease,
                    fee, saleTaxBasisPoints, createdAt, deadline, paid, removed, created, done);
        }
    }

    record PropertyListing(UUID reference, String fingerprint, UUID auctionId, UUID sellerId,
                           String propertyId, String dimension, String region,
                           long startingBid, long minimumIncrease, long fee, int saleTaxBasisPoints, long createdAt, long deadline,
                           boolean propertyLocked, boolean feePaid, boolean auctionCreated, boolean settled) {
        PropertyListing {
            Objects.requireNonNull(reference); Objects.requireNonNull(fingerprint); Objects.requireNonNull(auctionId);
            Objects.requireNonNull(sellerId); Objects.requireNonNull(propertyId); Objects.requireNonNull(dimension); Objects.requireNonNull(region);
            if (propertyId.isBlank() || dimension.isBlank() || region.isBlank() || startingBid <= 0 || minimumIncrease <= 0
                    || fee < 0 || saleTaxBasisPoints < 0 || saleTaxBasisPoints > 10_000
                    || createdAt < 0 || deadline <= createdAt) throw new IllegalArgumentException("Invalid property auction listing journal entry");
        }
        PropertyListing progress(boolean locked, boolean paid, boolean created, boolean done) {
            return new PropertyListing(reference, fingerprint, auctionId, sellerId, propertyId, dimension, region,
                    startingBid, minimumIncrease, fee, saleTaxBasisPoints, createdAt, deadline, locked, paid, created, done);
        }
    }

    record Bid(UUID reference, String fingerprint, UUID auctionId, UUID bidderId, long amount,
               long expectedRevision, UUID previousBidderId, long previousBid,
               boolean reservationApplied, boolean auctionApplied, boolean notified,
               boolean settled, long createdAt) {
        Bid {
            Objects.requireNonNull(reference); Objects.requireNonNull(fingerprint); Objects.requireNonNull(auctionId); Objects.requireNonNull(bidderId);
            if (amount <= 0 || expectedRevision < 0 || previousBid < 0 || createdAt < 0)
                throw new IllegalArgumentException("Invalid auction bid journal entry");
            if (previousBidderId == null && previousBid != 0) throw new IllegalArgumentException("Previous bid has no bidder");
        }
        Bid progress(boolean reservation, boolean auction, boolean notice, boolean done) {
            return new Bid(reference, fingerprint, auctionId, bidderId, amount, expectedRevision, previousBidderId,
                    previousBid, reservation, auction, notice, done, createdAt);
        }
    }

    enum ActionType { ITEM_LISTING, PROPERTY_LISTING, BID }
    record CompletedAction(ActionType type, String fingerprint, UUID auctionId, UUID actorId, long amount) {
        CompletedAction {
            Objects.requireNonNull(type); Objects.requireNonNull(fingerprint); Objects.requireNonNull(auctionId); Objects.requireNonNull(actorId);
            if (fingerprint.isBlank() || amount < 0) throw new IllegalArgumentException("Invalid completed auction action");
        }
    }

    enum SettlementState { AWAITING_PAYMENT, PAYMENT_CAPTURED, COMPLETED, NO_BIDS, INVALID_ASSET, CANCELLED, TIMING_OUT, TIMED_OUT }
    record HistoryWindow(UUID auctionId, long endedAt, long expiresAt) {
        HistoryWindow {
            Objects.requireNonNull(auctionId);
            if (endedAt < 0 || expiresAt <= endedAt) throw new IllegalArgumentException("Invalid auction history window");
        }
    }
    record Settlement(UUID id, UUID auctionId, UUID sellerId, UUID winnerId, long amount,
                      int saleTaxBasisPoints, long saleTax, long sellerNet, UUID reservationReference,
                      SettlementState state, boolean chargeRegistered, boolean sellerCredited, boolean claimPrepared,
                      boolean sellerNotified, boolean winnerNotified, boolean awaitingSellerNotified,
                      boolean awaitingWinnerNotified, boolean returnNotified, boolean claimNotified, long createdAt) {
        Settlement {
            Objects.requireNonNull(id); Objects.requireNonNull(auctionId); Objects.requireNonNull(sellerId); Objects.requireNonNull(state);
            if (amount < 0 || saleTaxBasisPoints < 0 || saleTaxBasisPoints > 10_000 || saleTax < 0 || sellerNet < 0
                    || Math.addExact(saleTax, sellerNet) != amount || createdAt < 0)
                throw new IllegalArgumentException("Invalid auction settlement");
            if (winnerId == null && (amount != 0 || reservationReference != null)
                    || winnerId != null && (amount <= 0 || reservationReference == null))
                throw new IllegalArgumentException("Invalid auction settlement winner data");
        }
        Settlement(UUID id, UUID auctionId, UUID sellerId, UUID winnerId, long amount, UUID reservationReference,
                   SettlementState state, boolean chargeRegistered, boolean sellerCredited, boolean claimPrepared,
                   boolean sellerNotified, boolean winnerNotified, boolean awaitingSellerNotified,
                   boolean awaitingWinnerNotified, boolean returnNotified, boolean claimNotified, long createdAt) {
            this(id, auctionId, sellerId, winnerId, amount, 0, 0, amount, reservationReference, state,
                    chargeRegistered, sellerCredited, claimPrepared, sellerNotified, winnerNotified,
                    awaitingSellerNotified, awaitingWinnerNotified, returnNotified, claimNotified, createdAt);
        }
        Settlement update(SettlementState next, boolean registered, boolean credited, boolean claim,
                          boolean sellerNotice, boolean winnerNotice, boolean awaitingSellerNotice,
                          boolean awaitingWinnerNotice) {
            return update(next, registered, credited, claim, sellerNotice, winnerNotice,
                    awaitingSellerNotice, awaitingWinnerNotice, returnNotified, claimNotified);
        }
        Settlement update(SettlementState next, boolean registered, boolean credited, boolean claim,
                          boolean sellerNotice, boolean winnerNotice, boolean awaitingSellerNotice,
                          boolean awaitingWinnerNotice, boolean returnNotice, boolean claimNotice) {
            return new Settlement(id, auctionId, sellerId, winnerId, amount, saleTaxBasisPoints, saleTax, sellerNet,
                    reservationReference, next,
                    registered, credited, claim, sellerNotice, winnerNotice, awaitingSellerNotice,
                    awaitingWinnerNotice, returnNotice, claimNotice, createdAt);
        }
    }

    public static AuctionHouseStore get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Auction house accessed off server thread");
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(AuctionHouseStore::new, AuctionHouseStore::load), DATA_NAME);
    }

    public static void validate(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DATA_NAME + ".dat");
        if (!Files.exists(file)) return;
        try { load(NbtIo.readCompressed(file, NbtAccounter.create(64 * 1024 * 1024)).getCompound("data"), server.registryAccess()); }
        catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Auction data is unreadable; restore a backup before starting this world", error);
        }
    }

    Auction auction(UUID id) { return auctions.get(id); }
    Collection<Auction> auctions() {
        if (orderedAuctions == null) orderedAuctions = auctions.values().stream()
                .sorted(Comparator.comparingLong(Auction::deadline).thenComparing(Auction::id)).toList();
        return orderedAuctions;
    }
    Listing listing(UUID reference) { return listings.get(reference); }
    Collection<Listing> listings() { return List.copyOf(listings.values()); }
    PropertyListing propertyListing(UUID reference) { return propertyListings.get(reference); }
    Collection<PropertyListing> propertyListings() { return List.copyOf(propertyListings.values()); }
    Bid bid(UUID reference) { return bids.get(reference); }
    Collection<Bid> bids() { return List.copyOf(bids.values()); }
    CompletedAction completedAction(UUID reference) { historyRetention.requireUnretired(reference); return completedActions.get(reference); }
    Settlement settlement(UUID auctionId) { return settlements.get(auctionId); }
    Collection<Settlement> settlements() { return List.copyOf(settlements.values()); }
    HistoryWindow history(UUID auctionId) { return history.get(auctionId); }
    Collection<HistoryWindow> history() { return List.copyOf(history.values()); }
    long nextDeadline() {
        if (nextDeadline < 0) {
            nextDeadline = auctions.values().stream().filter(a -> a.state() == Auction.State.ACTIVE
                        || a.state() == Auction.State.AWAITING_PAYMENT)
                .mapToLong(a -> a.state() == Auction.State.ACTIVE ? a.deadline() : a.paymentDeadline()).min().orElse(Long.MAX_VALUE);
        }
        return nextDeadline;
    }
    long nextDeadlineWithoutProperties() {
        if (nextItemDeadline < 0) nextItemDeadline = auctions.values().stream().filter(a -> !(a.asset() instanceof AuctionAsset.PropertyAsset)
                        && (a.state() == Auction.State.ACTIVE || a.state() == Auction.State.AWAITING_PAYMENT))
                .mapToLong(a -> a.state() == Auction.State.ACTIVE ? a.deadline() : a.paymentDeadline()).min().orElse(Long.MAX_VALUE);
        return nextItemDeadline;
    }
    void prepare(Listing listing) {
        historyRetention.requireUnretired(listing.reference());
        Listing old = listings.get(listing.reference());
        if (old != null && !old.fingerprint().equals(listing.fingerprint()))
            throw new IllegalArgumentException("Listing reference was already used for another auction");
        if (old == null) { listings.put(listing.reference(), listing); setDirty(); }
    }
    void listing(Listing listing) { listings.put(listing.reference(), listing); setDirty(); }
    void prepare(PropertyListing listing) {
        historyRetention.requireUnretired(listing.reference());
        PropertyListing old = propertyListings.get(listing.reference());
        if (old != null && !old.fingerprint().equals(listing.fingerprint()))
            throw new IllegalArgumentException("Listing reference was already used for another auction");
        if (old == null) { propertyListings.put(listing.reference(), listing); setDirty(); }
    }
    void propertyListing(PropertyListing listing) { propertyListings.put(listing.reference(), listing); setDirty(); }
    void prepare(Bid bid) {
        historyRetention.requireUnretired(bid.reference());
        Bid old = bids.get(bid.reference());
        if (old != null && !old.fingerprint().equals(bid.fingerprint()))
            throw new IllegalArgumentException("Bid reference was already used for another auction operation");
        if (old == null) { bids.put(bid.reference(), bid); setDirty(); }
    }
    void bid(Bid bid) { bids.put(bid.reference(), bid); setDirty(); }
    void complete(Listing listing) {
        if (!listing.settled()) throw new IllegalArgumentException("Auction listing is not settled");
        complete(listing.reference(), new CompletedAction(ActionType.ITEM_LISTING, listing.fingerprint(), listing.auctionId(), listing.sellerId(), listing.fee()));
        listings.remove(listing.reference()); setDirty();
    }
    void complete(PropertyListing listing) {
        if (!listing.settled()) throw new IllegalArgumentException("Property auction listing is not settled");
        complete(listing.reference(), new CompletedAction(ActionType.PROPERTY_LISTING, listing.fingerprint(), listing.auctionId(), listing.sellerId(), listing.fee()));
        propertyListings.remove(listing.reference()); setDirty();
    }
    void complete(Bid bid) {
        if (!bid.settled()) throw new IllegalArgumentException("Auction bid is not settled");
        complete(bid.reference(), new CompletedAction(ActionType.BID, bid.fingerprint(), bid.auctionId(), bid.bidderId(), bid.amount()));
        bids.remove(bid.reference()); setDirty();
    }
    private void complete(UUID reference, CompletedAction value) {
        historyRetention.record(reference); CompletedAction old = completedActions.putIfAbsent(reference, value);
        if (old != null && !old.equals(value)) throw new IllegalStateException("Conflicting completed auction action");
    }
    void prepare(Settlement settlement) {
        Settlement old = settlements.putIfAbsent(settlement.auctionId(), settlement);
        if (old != null && !old.id().equals(settlement.id())) throw new IllegalArgumentException("Auction already has another settlement");
        if (old == null) setDirty();
    }
    void settlement(Settlement settlement) {
        if (!settlements.containsKey(settlement.auctionId())) throw new IllegalArgumentException("Unknown auction settlement");
        settlements.put(settlement.auctionId(), settlement); setDirty();
    }
    void finishSettlement(UUID auctionId) { if (settlements.remove(auctionId) != null) setDirty(); }
    void history(HistoryWindow window) {
        if (expiredHistory.contains(window.auctionId())) return;
        HistoryWindow old = history.putIfAbsent(window.auctionId(), window);
        if (old != null && !old.equals(window)) throw new IllegalArgumentException("Auction history already has another result window");
        if (old == null) { nextDeadline = -1; nextItemDeadline = -1; orderedAuctions = null; setDirty(); }
    }
    Set<UUID> expireHistory(long now) {
        return Set.of();
    }
    void trimHistory(int maximumPerPlayer) {
        if (maximumPerPlayer < 1 || history.isEmpty()) return;
        Map<UUID, List<HistoryWindow>> perPlayer = new HashMap<>();
        for (HistoryWindow window : history.values()) {
            Auction auction = auctions.get(window.auctionId());
            if (auction == null) continue;
            perPlayer.computeIfAbsent(auction.sellerId(), ignored -> new ArrayList<>()).add(window);
            for (UUID participant : auction.participants())
                perPlayer.computeIfAbsent(participant, ignored -> new ArrayList<>()).add(window);
        }
        Set<UUID> retained = new HashSet<>();
        Comparator<HistoryWindow> newest = Comparator.comparingLong(HistoryWindow::endedAt).reversed()
                .thenComparing(HistoryWindow::auctionId);
        for (List<HistoryWindow> windows : perPlayer.values()) {
            windows.sort(newest);
            windows.stream().limit(maximumPerPlayer).map(HistoryWindow::auctionId).forEach(retained::add);
        }
        Set<UUID> removed = new HashSet<>(history.keySet()); removed.removeAll(retained);
        removed.removeAll(settlements.keySet()); // Recovery still needs this auction to finish notifications or return escrow.
        if (removed.isEmpty()) return;
        removed.forEach(id -> { history.remove(id); auctions.remove(id); settlements.remove(id); });
        nextDeadline = -1; nextItemDeadline = -1; orderedAuctions = null; setDirty();
    }
    void create(Auction auction) {
        Auction old = auctions.putIfAbsent(auction.id(), auction);
        if (old != null && !old.equals(auction)) throw new IllegalArgumentException("Duplicate auction ID");
        nextDeadline = -1; nextItemDeadline = -1; orderedAuctions = null; setDirty();
    }
    void replace(Auction auction) {
        if (!auctions.containsKey(auction.id())) throw new IllegalArgumentException("Unknown auction");
        auctions.put(auction.id(), auction); nextDeadline = -1; nextItemDeadline = -1; orderedAuctions = null; setDirty();
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", 11); historyRetention.save(tag, "historyRetention"); ListTag values = new ListTag();
        for (Auction auction : auctions.values()) values.add(writeAuction(auction, registries));
        tag.put("auctions", values); ListTag journal = new ListTag();
        for (Listing listing : listings.values()) {
            CompoundTag value = new CompoundTag(); value.putUUID("reference", listing.reference()); value.putString("fingerprint", listing.fingerprint());
            value.putUUID("auction", listing.auctionId()); value.putUUID("seller", listing.sellerId()); value.put("item", listing.item().save(registries));
            value.putLong("startingBid", listing.startingBid()); value.putLong("minimumIncrease", listing.minimumIncrease()); value.putLong("fee", listing.fee());
            value.putInt("saleTaxBasisPoints", listing.saleTaxBasisPoints());
            value.putLong("createdAt", listing.createdAt()); value.putLong("deadline", listing.deadline()); value.putBoolean("feePaid", listing.feePaid());
            value.putBoolean("itemRemoved", listing.itemRemoved()); value.putBoolean("auctionCreated", listing.auctionCreated()); value.putBoolean("settled", listing.settled()); journal.add(value);
        }
        tag.put("listings", journal); ListTag propertyJournal = new ListTag();
        for (PropertyListing listing : propertyListings.values()) {
            CompoundTag value = new CompoundTag(); value.putUUID("reference", listing.reference()); value.putString("fingerprint", listing.fingerprint());
            value.putUUID("auction", listing.auctionId()); value.putUUID("seller", listing.sellerId()); value.putString("property", listing.propertyId());
            value.putString("dimension", listing.dimension()); value.putString("region", listing.region()); value.putLong("startingBid", listing.startingBid());
            value.putLong("minimumIncrease", listing.minimumIncrease()); value.putLong("fee", listing.fee()); value.putInt("saleTaxBasisPoints", listing.saleTaxBasisPoints()); value.putLong("createdAt", listing.createdAt());
            value.putLong("deadline", listing.deadline()); value.putBoolean("propertyLocked", listing.propertyLocked()); value.putBoolean("feePaid", listing.feePaid());
            value.putBoolean("auctionCreated", listing.auctionCreated()); value.putBoolean("settled", listing.settled()); propertyJournal.add(value);
        }
        tag.put("propertyListings", propertyJournal); ListTag bidTags = new ListTag();
        for (Bid bid : bids.values()) {
            CompoundTag value = new CompoundTag(); value.putUUID("reference", bid.reference()); value.putString("fingerprint", bid.fingerprint());
            value.putUUID("auction", bid.auctionId()); value.putUUID("bidder", bid.bidderId()); value.putLong("amount", bid.amount());
            value.putLong("expectedRevision", bid.expectedRevision()); if (bid.previousBidderId() != null) value.putUUID("previousBidder", bid.previousBidderId());
            value.putLong("previousBid", bid.previousBid()); value.putBoolean("reservationApplied", bid.reservationApplied());
            value.putBoolean("auctionApplied", bid.auctionApplied()); value.putBoolean("notified", bid.notified()); value.putBoolean("settled", bid.settled());
            value.putLong("createdAt", bid.createdAt()); bidTags.add(value);
        }
        tag.put("bids", bidTags); ListTag completedActionTags = new ListTag();
        completedActions.forEach((reference, action) -> {
            CompoundTag value = new CompoundTag(); value.putUUID("reference", reference); value.putString("type", action.type().name());
            value.putString("fingerprint", action.fingerprint()); value.putUUID("auction", action.auctionId()); value.putUUID("actor", action.actorId());
            value.putLong("amount", action.amount()); completedActionTags.add(value);
        });
        tag.put("completedActions", completedActionTags); ListTag settlementTags = new ListTag();
        for (Settlement settlement : settlements.values()) {
            CompoundTag value = new CompoundTag(); value.putUUID("id", settlement.id()); value.putUUID("auction", settlement.auctionId()); value.putUUID("seller", settlement.sellerId());
            if (settlement.winnerId() != null) value.putUUID("winner", settlement.winnerId()); value.putLong("amount", settlement.amount());
            value.putInt("saleTaxBasisPoints", settlement.saleTaxBasisPoints()); value.putLong("saleTax", settlement.saleTax()); value.putLong("sellerNet", settlement.sellerNet());
            if (settlement.reservationReference() != null) value.putUUID("reservation", settlement.reservationReference()); value.putString("state", settlement.state().name());
            value.putBoolean("chargeRegistered", settlement.chargeRegistered()); value.putBoolean("sellerCredited", settlement.sellerCredited());
            value.putBoolean("claimPrepared", settlement.claimPrepared()); value.putBoolean("sellerNotified", settlement.sellerNotified());
            value.putBoolean("winnerNotified", settlement.winnerNotified()); value.putBoolean("awaitingSellerNotified", settlement.awaitingSellerNotified());
            value.putBoolean("awaitingWinnerNotified", settlement.awaitingWinnerNotified()); value.putBoolean("returnNotified", settlement.returnNotified());
            value.putBoolean("claimNotified", settlement.claimNotified()); value.putLong("createdAt", settlement.createdAt()); settlementTags.add(value);
        }
        tag.put("settlements", settlementTags); ListTag historyTags = new ListTag();
        for (HistoryWindow window : history.values()) {
            CompoundTag value = new CompoundTag(); value.putUUID("auction", window.auctionId());
            value.putLong("endedAt", window.endedAt()); value.putLong("expiresAt", window.expiresAt()); historyTags.add(value);
        }
        tag.put("history", historyTags); ListTag expiredHistoryTags = new ListTag();
        expiredHistory.forEach(id -> expiredHistoryTags.add(NbtUtils.createUUID(id)));
        tag.put("expiredHistory", expiredHistoryTags); return tag;
    }

    private static CompoundTag writeAuction(Auction auction, HolderLookup.Provider registries) {
        CompoundTag value = new CompoundTag(); value.putUUID("id", auction.id()); value.putUUID("seller", auction.sellerId());
        value.putString("assetType", auction.asset().type().name());
        if (auction.asset() instanceof AuctionAsset.ItemAsset item) value.put("item", item.stack().save(registries));
        else if (auction.asset() instanceof AuctionAsset.PropertyAsset property) {
            value.putString("property", property.propertyId()); value.putString("dimension", property.dimension()); value.putString("region", property.regionKey());
        }
        value.putLong("startingBid", auction.startingBid()); value.putLong("minimumIncrease", auction.minimumIncrease());
        value.putInt("saleTaxBasisPoints", auction.saleTaxBasisPoints());
        if (auction.highestBidderId() != null) value.putUUID("highestBidder", auction.highestBidderId()); value.putLong("highestBid", auction.highestBid());
        value.putLong("createdAt", auction.createdAt()); value.putLong("deadline", auction.deadline()); value.putString("state", auction.state().name());
        ListTag participants = new ListTag(); for (UUID id : auction.participants()) participants.add(NbtUtils.createUUID(id)); value.put("participants", participants);
        value.putLong("revision", auction.revision()); value.putLong("paymentDeadline", auction.paymentDeadline()); return value;
    }

    public static AuctionHouseStore load(CompoundTag tag, HolderLookup.Provider registries) {
        int version = tag.getInt("version");
        if ((version < 1 || version > 11) || !tag.contains("auctions", Tag.TAG_LIST) || !tag.contains("listings", Tag.TAG_LIST)
                || version >= 2 && !tag.contains("bids", Tag.TAG_LIST) || version >= 3 && !tag.contains("settlements", Tag.TAG_LIST)
                || version >= 5 && !tag.contains("history", Tag.TAG_LIST)
                || version >= 6 && !tag.contains("expiredHistory", Tag.TAG_LIST)
                || version >= 7 && !tag.contains("propertyListings", Tag.TAG_LIST)
                || version >= 8 && !tag.contains("completedActions", Tag.TAG_LIST))
            throw new IllegalStateException("Unsupported auction data");
        AuctionHouseStore store = new AuctionHouseStore();
        for (Tag raw : tag.getList("auctions", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) raw;
            try {
                AuctionAsset asset = switch (AuctionAsset.Type.valueOf(value.getString("assetType"))) {
                    case ITEM -> new AuctionAsset.ItemAsset(ItemStack.parse(registries, value.get("item")).orElseThrow());
                    case PROPERTY -> new AuctionAsset.PropertyAsset(version >= 9 ? value.getString("property")
                            : value.getString("dimension") + "|" + value.getString("region"), value.getString("dimension"), value.getString("region"));
                };
                Set<UUID> participants = new LinkedHashSet<>();
                for (Tag id : value.getList("participants", Tag.TAG_INT_ARRAY)) participants.add(NbtUtils.loadUUID(id));
                Auction auction = new Auction(value.getUUID("id"), value.getUUID("seller"), asset,
                        value.getLong("startingBid"), value.getLong("minimumIncrease"), version >= 10 ? value.getInt("saleTaxBasisPoints") : 0,
                        value.hasUUID("highestBidder") ? value.getUUID("highestBidder") : null,
                        value.getLong("highestBid"), value.getLong("createdAt"), value.getLong("deadline"), Auction.State.valueOf(value.getString("state")),
                        participants, value.getLong("revision"), value.getLong("paymentDeadline"));
                if (store.auctions.putIfAbsent(auction.id(), auction) != null) throw new IllegalStateException("Duplicate auction");
            } catch (RuntimeException error) { throw new IllegalStateException("Invalid auction entry", error); }
        }
        for (Tag raw : tag.getList("listings", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) raw;
            try {
                Listing listing = new Listing(value.getUUID("reference"), value.getString("fingerprint"), value.getUUID("auction"), value.getUUID("seller"),
                        ItemStack.parse(registries, value.get("item")).orElseThrow(), value.getLong("startingBid"), value.getLong("minimumIncrease"), value.getLong("fee"),
                        version >= 10 ? value.getInt("saleTaxBasisPoints") : 0, value.getLong("createdAt"), value.getLong("deadline"), value.getBoolean("feePaid"), value.getBoolean("itemRemoved"),
                        value.getBoolean("auctionCreated"), value.getBoolean("settled"));
                if (listing.settled()) store.complete(listing); else if (store.listings.putIfAbsent(listing.reference(), listing) != null) throw new IllegalStateException("Duplicate listing transaction");
            } catch (RuntimeException error) { throw new IllegalStateException("Invalid auction listing journal", error); }
        }
        if (version >= 7) for (Tag raw : tag.getList("propertyListings", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) raw;
            try {
                PropertyListing listing = new PropertyListing(value.getUUID("reference"), value.getString("fingerprint"), value.getUUID("auction"),
                        value.getUUID("seller"), value.getString("property"), value.getString("dimension"), value.getString("region"),
                        value.getLong("startingBid"), value.getLong("minimumIncrease"), value.getLong("fee"),
                        version >= 10 ? value.getInt("saleTaxBasisPoints") : 0, value.getLong("createdAt"), value.getLong("deadline"),
                        value.getBoolean("propertyLocked"), value.getBoolean("feePaid"), value.getBoolean("auctionCreated"), value.getBoolean("settled"));
                if (listing.settled()) store.complete(listing); else if (store.propertyListings.putIfAbsent(listing.reference(), listing) != null) throw new IllegalStateException("Duplicate property listing transaction");
            } catch (RuntimeException error) { throw new IllegalStateException("Invalid property auction listing journal", error); }
        }
        if (version >= 2) for (Tag raw : tag.getList("bids", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) raw;
            try {
                Bid bid = new Bid(value.getUUID("reference"), value.getString("fingerprint"), value.getUUID("auction"), value.getUUID("bidder"),
                        value.getLong("amount"), value.getLong("expectedRevision"), value.hasUUID("previousBidder") ? value.getUUID("previousBidder") : null,
                        value.getLong("previousBid"), value.getBoolean("reservationApplied"), value.getBoolean("auctionApplied"),
                        value.getBoolean("notified"), value.getBoolean("settled"), value.getLong("createdAt"));
                if (bid.settled()) store.complete(bid); else if (store.bids.putIfAbsent(bid.reference(), bid) != null) throw new IllegalStateException("Duplicate auction bid transaction");
            } catch (RuntimeException error) { throw new IllegalStateException("Invalid auction bid journal", error); }
        }
        if (version >= 8) for (Tag raw : tag.getList("completedActions", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) raw;
            try {
                UUID reference = value.getUUID("reference");
                CompletedAction action = new CompletedAction(ActionType.valueOf(value.getString("type")), value.getString("fingerprint"),
                        value.getUUID("auction"), value.getUUID("actor"), value.getLong("amount"));
                CompletedAction old = store.completedActions.putIfAbsent(reference, action);
                if (old != null && !old.equals(action)) throw new IllegalStateException("Duplicate completed auction action");
            } catch (RuntimeException error) { throw new IllegalStateException("Invalid completed auction action", error); }
        }
        if (version >= 3) for (Tag raw : tag.getList("settlements", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) raw;
            try {
                long amount = value.getLong("amount");
                Settlement settlement = new Settlement(value.getUUID("id"), value.getUUID("auction"), value.getUUID("seller"),
                        value.hasUUID("winner") ? value.getUUID("winner") : null, amount,
                        version >= 10 ? value.getInt("saleTaxBasisPoints") : 0,
                        version >= 10 ? value.getLong("saleTax") : 0,
                        version >= 10 ? value.getLong("sellerNet") : amount,
                        value.hasUUID("reservation") ? value.getUUID("reservation") : null, SettlementState.valueOf(value.getString("state")),
                        value.getBoolean("chargeRegistered"), value.getBoolean("sellerCredited"), value.getBoolean("claimPrepared"),
                        value.getBoolean("sellerNotified"), value.getBoolean("winnerNotified"),
                        version >= 4 && value.getBoolean("awaitingSellerNotified"),
                        version >= 4 && value.getBoolean("awaitingWinnerNotified"),
                        version >= 5 && value.getBoolean("returnNotified"),
                        version >= 5 && value.getBoolean("claimNotified"), value.getLong("createdAt"));
                if (store.settlements.putIfAbsent(settlement.auctionId(), settlement) != null) throw new IllegalStateException("Duplicate auction settlement");
            } catch (RuntimeException error) { throw new IllegalStateException("Invalid auction settlement", error); }
        }
        if (version >= 5) for (Tag raw : tag.getList("history", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) raw;
            try {
                HistoryWindow window = new HistoryWindow(value.getUUID("auction"), value.getLong("endedAt"), value.getLong("expiresAt"));
                Auction auction = store.auctions.get(window.auctionId());
                if (auction == null || auction.state() == Auction.State.ACTIVE || auction.state() == Auction.State.AWAITING_PAYMENT)
                    throw new IllegalStateException("Auction history does not reference a completed auction");
                if (store.history.putIfAbsent(window.auctionId(), window) != null) throw new IllegalStateException("Duplicate auction history");
            } catch (RuntimeException error) { throw new IllegalStateException("Invalid auction history", error); }
        }
        if (version >= 6) for (Tag raw : tag.getList("expiredHistory", Tag.TAG_INT_ARRAY)) {
            UUID id = NbtUtils.loadUUID(raw);
            if (!store.expiredHistory.add(id) || store.history.containsKey(id)) throw new IllegalStateException("Duplicate expired auction history");
        }
        // Versions 6-7 kept finished auctions forever. Claims already contain the exact assets,
        // so it's safe to remove results whose display window has ended.
        for (UUID id : List.copyOf(store.expiredHistory)) {
            if (!store.settlements.containsKey(id)) store.auctions.remove(id);
        }
        store.expiredHistory.clear();
        store.historyRetention.load(tag, "historyRetention", store.completedActions.keySet(), tag.getInt("version") >= 11);
        for (UUID id : store.listings.keySet()) store.historyRetention.requireUnretired(id);
        for (UUID id : store.propertyListings.keySet()) store.historyRetention.requireUnretired(id);
        for (UUID id : store.bids.keySet()) store.historyRetention.requireUnretired(id);
        store.setDirty(tag.getInt("version") < 11); return store;
    }
}
