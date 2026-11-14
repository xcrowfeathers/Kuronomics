package de.kuro.kuronomics.auction;

import de.kuro.kuronomics.event.EconomyEvents;
import de.kuro.kuronomics.economy.*;
import de.kuro.kuronomics.economy.data.*;
import de.kuro.kuronomics.economy.payday.PaydayService;
import de.kuro.kuronomics.economy.tax.MarketplaceTaxService;
import de.kuro.kuronomics.estate.*;
import de.kuro.kuronomics.mailbox.MailboxService;
import de.kuro.kuronomics.mailbox.MailboxDetail;
import java.util.*;
import java.util.function.LongSupplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Change auctions and escrow through this service, on the server. */
public final class AuctionHouseService {
    public static final int MAX_HISTORY_ENTRIES = 32;
    private static final String PLAYER_LISTINGS = "kuronomicsAuctionListings";
    private static final String PLAYER_CLAIMS = "kuronomicsAuctionClaims";
    private final MinecraftServer server;
    private final AuctionHouseStore store;
    private final LongSupplier clock;

    public record ListingResult(Auction auction, long fee, long balance, boolean created) {}
    public record BidResult(Auction auction, long availableBalance, boolean accepted) {}
    public record CancellationResult(Auction auction, boolean cancelled) {}
    public record ClaimResult(ItemStack item, boolean delivered, boolean alreadyClaimed) {
        public ClaimResult { item = item.copy(); }
        @Override public ItemStack item() { return item.copy(); }
    }
    public record PropertyClaimResult(String propertyName, boolean delivered, boolean alreadyClaimed) {}
    public record History(Auction auction, long endedAt, long expiresAt) {}

    public AuctionHouseService(MinecraftServer server) {
        this(server, System::currentTimeMillis);
    }
    public AuctionHouseService(MinecraftServer server, LongSupplier clock) {
        this.server = Objects.requireNonNull(server); this.store = AuctionHouseStore.get(server); this.clock = Objects.requireNonNull(clock);
    }
    public AuctionHouseService(AuctionHouseStore store) { this.server = null; this.store = Objects.requireNonNull(store); this.clock = System::currentTimeMillis; }

    public Optional<Auction> find(UUID id) { checkThread(); return Optional.ofNullable(store.auction(Objects.requireNonNull(id))); }
    public List<Auction> auctions() { checkThread(); return ordered(store.auctions().stream().toList()); }
    public List<Auction> active() { checkThread(); return ordered(store.auctions().stream().filter(a -> a.state() == Auction.State.ACTIVE).toList()); }
    /** Keep the listing visible while its payment is still settling, even though bidding has ended. */
    public List<Auction> marketListings() { checkThread(); return ordered(store.auctions().stream()
            .filter(a -> a.state() == Auction.State.ACTIVE || a.state() == Auction.State.AWAITING_PAYMENT).toList()); }
    public List<Auction> bySeller(UUID seller) { checkThread(); return ordered(store.auctions().stream()
            .filter(a -> a.sellerId().equals(seller) && a.state() != Auction.State.CANCELLED).toList()); }
    public List<Auction> byParticipant(UUID player) { checkThread(); return ordered(store.auctions().stream()
            .filter(a -> a.participants().contains(player) && visibleListing(a)).toList()); }
    public List<AuctionClaim> claims(UUID player) { checkThread(); return AuctionClaimStore.get(server).claims(player).stream()
            .sorted(Comparator.comparingLong(AuctionClaim::readyAt).reversed().thenComparing(AuctionClaim::id)).toList(); }
    public List<History> history(UUID player) {
        checkThread();
        return store.history().stream().map(window -> {
            Auction auction = store.auction(window.auctionId());
            return auction == null ? null : new History(auction, window.endedAt(), window.expiresAt());
        }).filter(Objects::nonNull).filter(result -> result.auction().sellerId().equals(player)
                || result.auction().participants().contains(player))
                .sorted(Comparator.comparingLong(History::endedAt).reversed().thenComparing(h -> h.auction().id()))
                .limit(MAX_HISTORY_ENTRIES).toList();
    }

    /** Save the items and delivery marker together first, so a retry can't give the items out twice. */
    public ClaimResult claimItem(ServerPlayer player, UUID claimId) {
        checkThread(); Objects.requireNonNull(player); Objects.requireNonNull(claimId);
        if (player.server != server) throw new IllegalArgumentException("Player is not connected to this server.");
        AuctionClaimStore claims = AuctionClaimStore.get(server); AuctionClaim claim = claims.claim(claimId);
        if (claim == null) { clearPlayerClaimMarker(player, claimId); return new ClaimResult(ItemStack.EMPTY, false, true); }
        if (!claim.ownerId().equals(player.getUUID())) throw new IllegalArgumentException("This auction claim belongs to another player.");
        if (!(claim.asset() instanceof AuctionAsset.ItemAsset item))
            throw new IllegalArgumentException("Property claims are not available yet.");
        ItemStack stack = item.stack();
        if (!hasPlayerClaimMarker(player, claim.id())) {
            if (!hasCapacity(player.getInventory(), stack)) throw new IllegalArgumentException("Not enough inventory space for this claim.");
            addItems(player.getInventory(), stack); markPlayerClaim(player, claim.id());
        }
        de.kuro.kuronomics.economy.PlayerPersistence.save(player);
        claims.complete(claim.id(), player.getUUID()); save(); clearPlayerClaimMarker(player, claim.id());
        return new ClaimResult(stack, true, false);
    }

    /** Transfer ownership first. If WorldGuard fails, leave the saved claim available for retry. */
    public PropertyClaimResult claimProperty(ServerPlayer player, UUID claimId) {
        checkThread(); Objects.requireNonNull(player); Objects.requireNonNull(claimId);
        if (player.server != server) throw new IllegalArgumentException("Player is not connected to this server.");
        AuctionClaimStore claims = AuctionClaimStore.get(server); AuctionClaim claim = claims.claim(claimId);
        if (claim == null) return new PropertyClaimResult("Property", false, true);
        if (!claim.ownerId().equals(player.getUUID())) throw new IllegalArgumentException("This auction claim belongs to another player.");
        if (!(claim.asset() instanceof AuctionAsset.PropertyAsset property)) throw new IllegalArgumentException("This is an item claim.");
        if (!new PropertyService(server).transferToBuyer(property.propertyId(), claim.auctionId(), player.getUUID()))
            throw new IllegalStateException("Property ownership transfer could not be completed. The claim remains available.");
        claims.complete(claim.id(), player.getUUID()); save();
        return new PropertyClaimResult(new PropertyService(server).displayName(property.propertyId()), true, false);
    }

    public boolean canBid(ServerPlayer player, Auction auction) {
        return bidBlockReason(player, auction).isEmpty();
    }

    public String bidBlockReason(ServerPlayer player, Auction auction) {
        if (auction.sellerId().equals(player.getUUID())) return "OWN_AUCTION";
        try {
            if (auction.state() != Auction.State.ACTIVE || auction.deadline() <= clock.getAsLong() || !validEscrow(auction))
                return "UNAVAILABLE";
            long required = auction.minimumNextBid(); EconomyService economy = economy(); economy.requireActive(player.getUUID());
            long capacity = economy.getAvailableBalance(player.getUUID());
            if (player.getUUID().equals(auction.highestBidderId())) capacity = Math.addExact(capacity, auction.highestBid());
            return capacity >= required ? "" : "MISSING_FUNDS";
        } catch (IllegalStateException error) { return "ACCOUNT_UNAVAILABLE"; }
        catch (RuntimeException error) { return "UNAVAILABLE"; }
    }

    public BidResult bid(ServerPlayer bidder, UUID auctionId, long amount, long expectedRevision, UUID reference) {
        checkThread(); Objects.requireNonNull(bidder); Objects.requireNonNull(auctionId); Objects.requireNonNull(reference);
        if (bidder.server != server) throw new IllegalArgumentException("Player is not connected to this server.");
        String fingerprint = bidFingerprint(auctionId, bidder.getUUID(), amount, expectedRevision);
        AuctionHouseStore.CompletedAction completed = store.completedAction(reference);
        if (completed != null) {
            requireCompleted(completed, AuctionHouseStore.ActionType.BID, fingerprint);
            Auction current = Optional.ofNullable(store.auction(completed.auctionId())).orElseThrow(() -> new IllegalStateException("Completed bid auction is missing."));
            return new BidResult(current, economy().getAvailableBalance(bidder.getUUID()), false);
        }
        AuctionHouseStore.Bid existing = store.bid(reference);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) throw new IllegalArgumentException("Bid reference was already used for another auction operation");
            executeBid(existing); Auction current = Objects.requireNonNull(store.auction(existing.auctionId()));
            return new BidResult(current, economy().getAvailableBalance(bidder.getUUID()), false);
        }
        if (store.listing(reference) != null || store.propertyListing(reference) != null || store.completedAction(reference) != null
                || economy().isTransactionProcessed(reference))
            throw new IllegalArgumentException("Transaction reference was already used for another operation");
        Auction auction = Optional.ofNullable(store.auction(auctionId)).orElseThrow(() -> new IllegalArgumentException("This auction no longer exists."));
        long now = clock.getAsLong();
        if (auction.deadline() <= now) {
            processExpired(); throw new IllegalArgumentException("This auction has already ended.");
        }
        if (auction.state() != Auction.State.ACTIVE) throw new IllegalArgumentException("This auction is no longer active.");
        if (auction.asset() instanceof AuctionAsset.PropertyAsset && !propertyIntegrationAvailable())
            throw new IllegalStateException(de.kuro.kuronomics.integration.PropertyIntegration.REQUIRED_MESSAGE);
        if (!validEscrow(auction)) throw new IllegalArgumentException("This auction's property is no longer valid or escrowed.");
        if (auction.revision() != expectedRevision) throw new IllegalStateException("This auction changed. Review the latest bid and try again.");
        if (auction.sellerId().equals(bidder.getUUID())) throw new IllegalArgumentException("You cannot bid on your own auction.");
        Money.positive(amount); long minimum = auction.minimumNextBid();
        if (amount < minimum) throw new IllegalArgumentException("The minimum next bid is " + Money.format(minimum) + ".");
        EconomyService economy = economy(); economy.requireActive(bidder.getUUID());
        UUID previousReservation = auction.highestBidderId() == null ? null : reservationReference(auction.id(), auction.highestBidderId());
        if (previousReservation != null) {
            var hold = economy.getReservation(previousReservation).orElseThrow(() -> new IllegalStateException("The current bid reservation is missing."));
            if (!hold.active() || !hold.owner().equals(auction.highestBidderId()) || hold.amount() != auction.highestBid())
                throw new IllegalStateException("The current bid reservation is inconsistent.");
        }
        long capacity = economy.getAvailableBalance(bidder.getUUID());
        if (bidder.getUUID().equals(auction.highestBidderId())) capacity = Math.addExact(capacity, auction.highestBid());
        if (capacity < amount) throw new IllegalArgumentException("Missing funds.");
        AuctionHouseStore.Bid bid = new AuctionHouseStore.Bid(reference, fingerprint, auction.id(), bidder.getUUID(), amount,
                expectedRevision, auction.highestBidderId(), auction.highestBid(), false, false, false, false, now);
        store.prepare(bid); save(); executeBid(bid);
        Auction updated = Objects.requireNonNull(store.auction(auction.id()));
        return new BidResult(updated, economy.getAvailableBalance(bidder.getUUID()), true);
    }

    /** Only the seller can cancel an active listing. Return escrow safely, but don't refund the listing fee. */
    public CancellationResult cancelAuction(ServerPlayer seller, UUID auctionId, long expectedRevision, UUID requestId) {
        checkThread(); Objects.requireNonNull(seller); Objects.requireNonNull(auctionId); Objects.requireNonNull(requestId);
        if (seller.server != server) throw new IllegalArgumentException("Player is not connected to this server.");
        Auction auction = Optional.ofNullable(store.auction(auctionId))
                .orElseThrow(() -> new IllegalArgumentException("This auction no longer exists."));
        if (!auction.sellerId().equals(seller.getUUID())) throw new IllegalArgumentException("Only the seller may remove this auction.");
        if (auction.state() == Auction.State.CANCELLED) return new CancellationResult(auction, false);
        long now = clock.getAsLong();
        if (auction.deadline() <= now) { processExpired(); throw new IllegalArgumentException("This auction has already ended."); }
        if (auction.state() != Auction.State.ACTIVE) throw new IllegalArgumentException("Only active auctions can be removed.");
        if (auction.highestBidderId() != null || auction.highestBid() > 0 || !auction.participants().isEmpty())
            throw new IllegalArgumentException("Auctions with bids cannot be removed.");
        if (auction.revision() != expectedRevision) throw new IllegalStateException("This auction changed. Review it and try again.");
        Auction cancelled = auction.state(Auction.State.CANCELLED, 0);
        AuctionHouseStore.Settlement settlement = settlementFor(cancelled, AuctionHouseStore.SettlementState.CANCELLED);
        store.prepare(settlement); store.replace(cancelled); recordHistory(cancelled, now); save();
        finishCancelled(settlement);
        return new CancellationResult(cancelled, true);
    }

    private void executeBid(AuctionHouseStore.Bid original) {
        AuctionHouseStore.Bid bid = Objects.requireNonNull(store.bid(original.reference())); EconomyService economy = economy();
        UUID previousReference = bid.previousBidderId() == null ? null : reservationReference(bid.auctionId(), bid.previousBidderId());
        UUID nextReference = reservationReference(bid.auctionId(), bid.bidderId());
        if (!bid.reservationApplied()) {
            economy.transitionReservation(previousReference, bid.previousBidderId(), nextReference, bid.bidderId(), bid.amount(),
                    bid.reference(), "auction bid " + bid.auctionId());
            save(); bid = bid.progress(true, false, false, false); store.bid(bid); save();
        }
        if (!bid.auctionApplied()) {
            Auction current = Optional.ofNullable(store.auction(bid.auctionId())).orElseThrow(() -> new IllegalStateException("Bid auction is missing."));
            if (current.revision() == bid.expectedRevision()) {
                if (!Objects.equals(current.highestBidderId(), bid.previousBidderId()) || current.highestBid() != bid.previousBid())
                    throw new IllegalStateException("Auction changed while applying a durable bid.");
                store.replace(current.bid(bid.bidderId(), bid.amount())); save();
            } else if (current.revision() != Math.addExact(bid.expectedRevision(), 1)
                    || !bid.bidderId().equals(current.highestBidderId()) || current.highestBid() != bid.amount())
                throw new IllegalStateException("Auction revision conflicts with a durable bid.");
            bid = bid.progress(true, true, false, false); store.bid(bid); save();
        }
        if (!bid.notified()) {
            if (bid.previousBidderId() != null && !bid.previousBidderId().equals(bid.bidderId())) {
                Auction current = Objects.requireNonNull(store.auction(bid.auctionId()));
                new MailboxService(server).auctionOutbid(bid.reference(), bid.previousBidderId(), bid.bidderId(),
                        assetName(current.asset()), bid.previousBid(), bid.amount());
            }
            bid = bid.progress(true, true, true, false); store.bid(bid); save();
        }
        if (!bid.settled()) { bid = bid.progress(true, true, true, true); store.bid(bid); save(); }
        store.complete(bid); save();
    }

    public void recoverBids() {
        checkThread(); for (AuctionHouseStore.Bid bid : store.bids()) if (!bid.settled()) executeBid(bid);
    }

    public static UUID reservationReference(UUID auctionId, UUID bidderId) {
        return EconomyService.stableReference("auction-reservation", auctionId + ":" + bidderId);
    }

    private static List<Auction> ordered(List<Auction> values) {
        return List.copyOf(values); // Filtering keeps the store's existing order.
    }
    private boolean visibleListing(Auction auction) {
        return auction.state() == Auction.State.ACTIVE || auction.state() == Auction.State.AWAITING_PAYMENT
                || store.history(auction.id()) != null;
    }

    public ListingResult createItemAuction(ServerPlayer seller, ItemStack exactStack, long startingBid,
                                           long minimumIncrease, int durationCycles, UUID reference) {
        checkThread(); Objects.requireNonNull(seller); Objects.requireNonNull(reference);
        if (seller.server != server) throw new IllegalArgumentException("Player is not connected to this server.");
        String fingerprint = fingerprint(seller.getUUID(), startingBid, minimumIncrease, durationCycles);
        AuctionHouseStore.CompletedAction completed = store.completedAction(reference);
        if (completed != null) {
            requireCompleted(completed, AuctionHouseStore.ActionType.ITEM_LISTING, fingerprint);
            Auction auction = Optional.ofNullable(store.auction(completed.auctionId())).orElseThrow(() -> new IllegalStateException("Completed listing auction is missing."));
            if (!(auction.asset() instanceof AuctionAsset.ItemAsset item) || exactStack != null && !exactStack.isEmpty()
                    && (exactStack.getCount() != item.stack().getCount() || !ItemStack.isSameItemSameComponents(exactStack, item.stack())))
                throw new IllegalArgumentException("Listing reference was already used for another item stack");
            return new ListingResult(auction, completed.amount(), economy().getBalance(seller.getUUID()), false);
        }
        AuctionHouseStore.Listing existing = store.listing(reference);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) throw new IllegalArgumentException("Listing reference was already used for another auction");
            if (exactStack != null && !exactStack.isEmpty() && (exactStack.getCount() != existing.item().getCount()
                    || !ItemStack.isSameItemSameComponents(exactStack, existing.item())))
                throw new IllegalArgumentException("Listing reference was already used for another item stack");
            execute(seller, existing); Auction auction = Objects.requireNonNull(store.auction(existing.auctionId()));
            return new ListingResult(auction, existing.fee(), economy().getBalance(seller.getUUID()), false);
        }
        if (store.propertyListing(reference) != null || store.completedAction(reference) != null)
            throw new IllegalArgumentException("Listing reference was already used for another auction");
        requireListingCapacity(seller.getUUID());
        Money.positive(startingBid); Money.positive(minimumIncrease);
        int minimum = EconomyEvents.config().auctionMinDurationCycles();
        int maximum = EconomyEvents.config().auctionMaxDurationCycles();
        if (minimum > maximum) throw new IllegalStateException("Auction minimum duration exceeds its maximum duration in the config.");
        if (durationCycles < minimum || durationCycles > maximum)
            throw new IllegalArgumentException("Auction duration must be between " + minimum + " and " + maximum + " cycles.");
        if (exactStack == null || exactStack.isEmpty()) throw new IllegalArgumentException("Select an item stack to sell.");
        ItemStack escrow = exactStack.copy();
        if (!hasExactItems(seller.getInventory(), escrow)) throw new IllegalArgumentException("You no longer have the selected items.");
        long fee = Money.legacy(EconomyEvents.config().auctionListingFee());
        if (fee < 0) throw new IllegalStateException("Auction fee cannot be negative.");
        int saleTaxBasisPoints = MarketplaceTaxService.configuredBasisPoints(MarketplaceTaxService.SourceType.AUCTION_SALE);
        EconomyService economy = economy(); economy.requireActive(seller.getUUID());
        if (economy.getAvailableBalance(seller.getUUID()) < fee) throw new IllegalArgumentException("Your current balance is insufficient for the auction fee.");
        if (fee > 0 && economy.isTransactionProcessed(reference))
            throw new IllegalArgumentException("Transaction reference was already used for another financial operation");
        long now = clock.getAsLong();
        long cycleMillis = Math.multiplyExact(EconomyEvents.config().auctionCycleMinutes(), 60_000L);
        long deadline = Math.addExact(now, Math.multiplyExact(durationCycles, cycleMillis));
        AuctionHouseStore.Listing listing = new AuctionHouseStore.Listing(reference, fingerprint, UUID.randomUUID(), seller.getUUID(), escrow,
                startingBid, minimumIncrease, fee, saleTaxBasisPoints, now, deadline, fee == 0, false, false, false);
        store.prepare(listing); save(); execute(seller, listing);
        return new ListingResult(Objects.requireNonNull(store.auction(listing.auctionId())), fee, economy.getBalance(seller.getUUID()), true);
    }

    public ListingResult createPropertyAuction(ServerPlayer seller, String propertyId, long startingBid,
                                               long minimumIncrease, int durationCycles, UUID reference) {
        checkThread(); Objects.requireNonNull(seller); Objects.requireNonNull(propertyId); Objects.requireNonNull(reference);
        if (seller.server != server) throw new IllegalArgumentException("Player is not connected to this server.");
        String fingerprint = propertyFingerprint(seller.getUUID(), propertyId, startingBid, minimumIncrease, durationCycles);
        AuctionHouseStore.CompletedAction completed = store.completedAction(reference);
        if (completed != null) {
            requireCompleted(completed, AuctionHouseStore.ActionType.PROPERTY_LISTING, fingerprint);
            Auction auction = Optional.ofNullable(store.auction(completed.auctionId())).orElseThrow(() -> new IllegalStateException("Completed listing auction is missing."));
            if (!(auction.asset() instanceof AuctionAsset.PropertyAsset property) || !property.propertyId().equals(propertyId))
                throw new IllegalArgumentException("Listing reference was already used for another property");
            return new ListingResult(auction, completed.amount(), economy().getBalance(seller.getUUID()), false);
        }
        AuctionHouseStore.PropertyListing existing = store.propertyListing(reference);
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint)) throw new IllegalArgumentException("Listing reference was already used for another auction");
            executeProperty(existing); Auction auction = Objects.requireNonNull(store.auction(existing.auctionId()));
            return new ListingResult(auction, existing.fee(), economy().getBalance(seller.getUUID()), false);
        }
        if (store.listing(reference) != null || store.completedAction(reference) != null || economy().isTransactionProcessed(reference))
            throw new IllegalArgumentException("Transaction reference was already used for another operation");
        requireListingCapacity(seller.getUUID());
        Money.positive(startingBid); Money.positive(minimumIncrease);
        int minimum = EconomyEvents.config().auctionMinDurationCycles(), maximum = EconomyEvents.config().auctionMaxDurationCycles();
        if (minimum > maximum) throw new IllegalStateException("Auction minimum duration exceeds its maximum duration in the config.");
        if (durationCycles < minimum || durationCycles > maximum)
            throw new IllegalArgumentException("Auction duration must be between " + minimum + " and " + maximum + " cycles.");
        PropertyService properties = new PropertyService(server); Estate estate = properties.requireAuctionable(seller.getUUID(), propertyId);
        long fee = Money.legacy(EconomyEvents.config().auctionListingFee()); if (fee < 0) throw new IllegalStateException("Auction fee cannot be negative.");
        int saleTaxBasisPoints = MarketplaceTaxService.configuredBasisPoints(MarketplaceTaxService.SourceType.AUCTION_SALE);
        EconomyService economy = economy(); economy.requireActive(seller.getUUID());
        if (economy.getAvailableBalance(seller.getUUID()) < fee) throw new IllegalArgumentException("Your current balance is insufficient for the auction fee.");
        long now = clock.getAsLong(), cycleMillis = Math.multiplyExact(EconomyEvents.config().auctionCycleMinutes(), 60_000L);
        long deadline = Math.addExact(now, Math.multiplyExact(durationCycles, cycleMillis)); UUID auctionId = UUID.randomUUID();
        AuctionHouseStore.PropertyListing listing = new AuctionHouseStore.PropertyListing(reference, fingerprint, auctionId, seller.getUUID(),
                estate.key(), estate.dimension(), estate.region(), startingBid, minimumIncrease, fee, saleTaxBasisPoints,
                now, deadline, false, fee == 0, false, false);
        store.prepare(listing); save(); executeProperty(listing);
        return new ListingResult(Objects.requireNonNull(store.auction(auctionId)), fee, economy.getBalance(seller.getUUID()), true);
    }

    private void requireListingCapacity(UUID sellerId) {
        int maximum = EconomyEvents.config().auctionMaxActivePerPlayer();
        long active = store.auctions().stream()
                .filter(auction -> auction.sellerId().equals(sellerId) && auction.state() == Auction.State.ACTIVE)
                .count();
        if (active >= maximum)
            throw new IllegalStateException("You may have at most " + maximum + " active auctions at once.");
    }

    private void executeProperty(AuctionHouseStore.PropertyListing original) {
        AuctionHouseStore.PropertyListing listing = Objects.requireNonNull(store.propertyListing(original.reference()));
        PropertyService properties = new PropertyService(server); EconomyService economy = economy();
        if (!properties.integrationAvailable() || properties.awaitingRegistration(listing.propertyId())) return;
        if (!listing.propertyLocked()) {
            properties.lockForAuction(listing.sellerId(), listing.propertyId(), listing.auctionId());
            listing = listing.progress(true, listing.feePaid(), listing.auctionCreated(), false); store.propertyListing(listing); save();
        }
        if (!listing.feePaid()) {
            if (!properties.isEscrowedForAuction(listing.propertyId(), listing.auctionId(), listing.sellerId()))
                throw new IllegalStateException("Property escrow could not be validated before the listing fee.");
            if (!economy.collectState(listing.sellerId(), listing.fee(), listing.reference(), "Auction listing fee")
                    && !economy.isTransactionProcessed(listing.reference()))
                throw new IllegalStateException("The auction listing fee could not be charged.");
            listing = listing.progress(true, true, listing.auctionCreated(), false); store.propertyListing(listing); save();
        }
        if (!listing.auctionCreated()) {
            store.create(new Auction(listing.auctionId(), listing.sellerId(), new AuctionAsset.PropertyAsset(listing.propertyId(), listing.dimension(), listing.region()),
                    listing.startingBid(), listing.minimumIncrease(), listing.saleTaxBasisPoints(), null, 0,
                    listing.createdAt(), listing.deadline(), Auction.State.ACTIVE, Set.of(), 0, 0));
            listing = listing.progress(true, true, true, false); store.propertyListing(listing); save();
        }
        if (!listing.settled()) {
            MailboxService mailbox = new MailboxService(server);
            if (listing.fee() > 0) mailbox.withdrawal(listing.sellerId(), listing.fee(), economy.getBalance(listing.sellerId()), listing.reference(), "Auction listing fee");
            mailbox.system(listing.sellerId(), listing.reference(), "Property auction listed: " + listing.region(),
                    List.of(new MailboxDetail(MailboxDetail.Kind.INFO, "Starting bid", listing.startingBid())));
            listing = listing.progress(true, true, true, true); store.propertyListing(listing); save();
        }
        store.complete(listing); save();
    }

    public void recoverPropertyListings() {
        checkThread();
        if (!propertyIntegrationAvailable()) return;
        for (AuctionHouseStore.PropertyListing listing : store.propertyListings()) if (!listing.settled()) {
            try { executeProperty(listing); }
            catch (RuntimeException error) { com.mojang.logging.LogUtils.getLogger().error("Property auction listing {} recovery is pending", listing.reference(), error); }
        }
    }

    private void execute(ServerPlayer seller, AuctionHouseStore.Listing original) {
        AuctionHouseStore.Listing listing = store.listing(original.reference());
        EconomyService economy = economy();
        if (!listing.feePaid()) {
            economy.collectState(listing.sellerId(), listing.fee(), listing.reference(), "Auction listing fee");
            save(); listing = listing.progress(true, listing.itemRemoved(), listing.auctionCreated(), false); store.listing(listing); save();
        }
        if (!listing.itemRemoved()) {
            CompoundTag markers = seller.getPersistentData().getCompound(PLAYER_LISTINGS);
            if (!markers.getBoolean(listing.reference().toString())) {
                if (!removeExactItems(seller.getInventory(), listing.item()))
                    throw new IllegalStateException("The selected auction items are unavailable; the listing will recover when they are restored.");
                markers.putBoolean(listing.reference().toString(), true); seller.getPersistentData().put(PLAYER_LISTINGS, markers);
                // Save the items and retry marker in the same player file.
            }
            de.kuro.kuronomics.economy.PlayerPersistence.save(seller);
            listing = listing.progress(true, true, listing.auctionCreated(), false); store.listing(listing); save();
        }
        if (!listing.auctionCreated()) {
            store.create(new Auction(listing.auctionId(), listing.sellerId(), new AuctionAsset.ItemAsset(listing.item()),
                    listing.startingBid(), listing.minimumIncrease(), listing.saleTaxBasisPoints(), null, 0, listing.createdAt(), listing.deadline(),
                    Auction.State.ACTIVE, Set.of(), 0, 0));
            listing = listing.progress(true, true, true, false); store.listing(listing); save();
        }
        if (!listing.settled()) {
            MailboxService mailbox = new MailboxService(server);
            if (listing.fee() > 0) mailbox.withdrawal(listing.sellerId(), listing.fee(),
                    economy.getBalance(listing.sellerId()), listing.reference(), "Auction listing fee");
            mailbox.system(listing.sellerId(), listing.reference(), "Auction listed: " + listing.item().getHoverName().getString(),
                    List.of(new MailboxDetail(MailboxDetail.Kind.INFO, "Starting bid", listing.startingBid())));
            listing = listing.progress(true, true, true, true); store.listing(listing); save();
            clearPlayerListingMarker(seller, listing.reference());
        }
        store.complete(listing); save();
    }

    /** Finish this owner's saved, incomplete listings when they reconnect. */
    public void recover(ServerPlayer player) {
        checkThread(); for (AuctionHouseStore.Listing listing : store.listings())
            if (!listing.settled() && listing.sellerId().equals(player.getUUID())) {
                try { execute(player, listing); }
                catch (RuntimeException error) { com.mojang.logging.LogUtils.getLogger().error("Auction listing {} recovery is pending", listing.reference(), error); }
            }
    }

    /** Resume saved settlements on startup. Reuse the same reference for each external change. */
    public Set<UUID> recoverSettlements() {
        checkThread();
        Set<UUID> changed = new LinkedHashSet<>();
        for (Auction auction : store.auctions()) {
            if (propertyPaused(auction)) continue;
            AuctionHouseStore.Settlement settlement = store.settlement(auction.id());
            if (settlement == null && (auction.state() == Auction.State.AWAITING_PAYMENT
                    || auction.state() == Auction.State.FAILED_NO_BIDS
                    || auction.state() == Auction.State.FAILED_INVALID_ASSET
                    || auction.state() == Auction.State.FAILED_PAYMENT_TIMEOUT
                    || auction.state() == Auction.State.CANCELLED)) {
                settlement = settlementFor(auction, switch (auction.state()) {
                    case AWAITING_PAYMENT -> AuctionHouseStore.SettlementState.AWAITING_PAYMENT;
                    case FAILED_NO_BIDS -> AuctionHouseStore.SettlementState.NO_BIDS;
                    case FAILED_INVALID_ASSET -> AuctionHouseStore.SettlementState.INVALID_ASSET;
                    case CANCELLED -> AuctionHouseStore.SettlementState.CANCELLED;
                    default -> AuctionHouseStore.SettlementState.TIMING_OUT;
                });
                store.prepare(settlement); save();
            }
            if (settlement != null && recoverSettlement(settlement)) changed.add(auction.id());
        }
        return Set.copyOf(changed);
    }

    /** Called after the payday transaction commits. It's safe to call again on retry. */
    public Set<UUID> onPayday(PaydayService.Result result) {
        checkThread(); Objects.requireNonNull(result);
        Set<UUID> changed = new LinkedHashSet<>();
        for (PaydayCharge paid : result.paidCharges()) {
            for (AuctionHouseStore.Settlement settlement : store.settlements()) {
                if (settlement.id().equals(paid.reference()) && completeSuccessfulSettlement(settlement))
                    changed.add(settlement.auctionId());
            }
        }
        return Set.copyOf(changed);
    }

    /** Only check auctions once the cached next deadline is due, not on every tick for every auction. */
    public Set<UUID> processExpired() {
        checkThread(); long now = clock.getAsLong(); boolean propertiesAvailable = propertyIntegrationAvailable();
        if ((propertiesAvailable ? store.nextDeadline() : store.nextDeadlineWithoutProperties()) > now) return Set.of();
        Set<UUID> changed = new LinkedHashSet<>(store.expireHistory(now));
        if (!changed.isEmpty()) save();
        long stopAt = de.kuro.kuronomics.economy.EconomyWorkCoordinator.deadline();
        for (Auction auction : store.auctions()) {
            if (changed.size() >= 4 || !changed.isEmpty() && System.nanoTime() >= stopAt) break;
            if (propertyPaused(auction)) continue;
            if (auction.state() == Auction.State.ACTIVE && auction.deadline() <= now) {
                changed.add(finishActive(auction, now).id());
            } else if (auction.state() == Auction.State.AWAITING_PAYMENT && auction.paymentDeadline() <= now) {
                AuctionHouseStore.Settlement settlement = store.settlement(auction.id());
                if (settlement == null) {
                    settlement = settlementFor(auction, AuctionHouseStore.SettlementState.AWAITING_PAYMENT);
                    store.prepare(settlement); save();
                }
                recoverSettlement(settlement);
                changed.add(auction.id());
            }
        }
        return Set.copyOf(changed);
    }

    public Auction finishNow(UUID auctionId) {
        checkThread(); Objects.requireNonNull(auctionId);
        Auction auction = Optional.ofNullable(store.auction(auctionId))
                .orElseThrow(() -> new IllegalArgumentException("This auction no longer exists."));
        if (auction.state() != Auction.State.ACTIVE) throw new IllegalArgumentException("Only active auctions can be finished.");
        if (auction.asset() instanceof AuctionAsset.PropertyAsset && !propertyIntegrationAvailable())
            throw new IllegalStateException(de.kuro.kuronomics.integration.PropertyIntegration.REQUIRED_MESSAGE);
        return finishActive(auction, clock.getAsLong());
    }

    private Auction finishActive(Auction auction, long now) {
        AuctionHouseStore.Settlement settlement;
        Auction next;
        if (!validEscrow(auction)) {
            settlement = settlementFor(auction, AuctionHouseStore.SettlementState.INVALID_ASSET);
            next = auction.state(Auction.State.FAILED_INVALID_ASSET, 0);
        } else if (!hasValidHighestBid(auction)) {
            settlement = settlementFor(auction, AuctionHouseStore.SettlementState.NO_BIDS);
            next = auction.state(Auction.State.FAILED_NO_BIDS, 0);
        } else {
            settlement = settlementFor(auction, AuctionHouseStore.SettlementState.AWAITING_PAYMENT);
            // Only the pending state is saved here; try to capture the money in this same server action.
            next = auction.state(Auction.State.AWAITING_PAYMENT, now);
        }
        store.prepare(settlement); store.replace(next);
        if (next.state() == Auction.State.FAILED_NO_BIDS || next.state() == Auction.State.FAILED_INVALID_ASSET)
            recordHistory(next, now);
        save(); recoverSettlement(settlement);
        return Objects.requireNonNull(store.auction(next.id()));
    }

    private AuctionHouseStore.Settlement settlementFor(Auction auction, AuctionHouseStore.SettlementState state) {
        UUID winner = state == AuctionHouseStore.SettlementState.NO_BIDS || state == AuctionHouseStore.SettlementState.INVALID_ASSET
                ? null : auction.highestBidderId();
        MarketplaceTaxService.Breakdown tax = winner == null ? null : auction.saleBreakdown(auction.highestBid());
        return new AuctionHouseStore.Settlement(settlementReference(auction.id()), auction.id(), auction.sellerId(), winner,
                winner == null ? 0 : auction.highestBid(), winner == null ? 0 : tax.taxBasisPoints(),
                winner == null ? 0 : tax.tax(), winner == null ? 0 : tax.net(),
                winner == null ? null : reservationReference(auction.id(), winner),
                state, false, false, false, false, false, false, false, false, false, clock.getAsLong());
    }

    private boolean hasValidHighestBid(Auction auction) {
        if (auction.highestBidderId() == null || auction.highestBid() <= 0) return false;
        Optional<Reservation> hold = economy().getReservation(reservationReference(auction.id(), auction.highestBidderId()));
        return hold.isPresent() && hold.get().active() && hold.get().owner().equals(auction.highestBidderId())
                && hold.get().amount() == auction.highestBid();
    }

    private boolean recoverSettlement(AuctionHouseStore.Settlement settlement) {
        return switch (settlement.state()) {
            case AWAITING_PAYMENT -> {
                Optional<PaydayCharge> charge = economy().getPaydayCharge(settlement.id());
                if (charge.isPresent() && charge.get().status() == PaydayCharge.Status.PAID)
                    yield completeSuccessfulSettlement(settlement);
                yield settleReservedPayment(settlement);
            }
            case PAYMENT_CAPTURED, COMPLETED -> completeSuccessfulSettlement(settlement);
            case NO_BIDS -> finishNoBid(settlement);
            case INVALID_ASSET -> finishInvalidAsset(settlement);
            case CANCELLED -> finishCancelled(settlement);
            case TIMING_OUT, TIMED_OUT -> finishPaymentTimeout(settlement);
        };
    }

    private boolean settleReservedPayment(AuctionHouseStore.Settlement original) {
        AuctionHouseStore.Settlement settlement = current(original); EconomyService economy = economy();
        Auction auction = requireAuction(settlement);
        if (propertyPaused(auction)) return false;
        if (!validEscrow(auction)) {
            settlement = settlement.update(AuctionHouseStore.SettlementState.INVALID_ASSET, settlement.chargeRegistered(), false,
                    settlement.claimPrepared(), settlement.sellerNotified(), settlement.winnerNotified(), settlement.awaitingSellerNotified(), settlement.awaitingWinnerNotified());
            store.settlement(settlement); store.replace(auction.state(Auction.State.FAILED_INVALID_ASSET, 0)); save(); return finishInvalidAsset(settlement);
        }
        Optional<PaydayCharge> existing = economy.getPaydayCharge(settlement.id());
        if (existing.isEmpty()) {
            economy.registerPaydayDebit(settlement.id(), settlement.winnerId(), settlement.amount(),
                    settlement.reservationReference(), "Auction purchase: " + assetName(requireAuction(settlement).asset()));
        } else {
            PaydayCharge charge = existing.get();
            if (!charge.payer().equals(settlement.winnerId()) || charge.amount() != settlement.amount()
                    || !charge.reservationReference().equals(settlement.reservationReference()))
                throw new IllegalStateException("Auction settlement PayDay charge is inconsistent");
            if (charge.status() == PaydayCharge.Status.PAID) return completeSuccessfulSettlement(settlement);
            if (charge.status() == PaydayCharge.Status.CANCELLED) throw new IllegalStateException("Active auction payment was cancelled");
        }
        if (!settlement.chargeRegistered()) {
            settlement = settlement.update(settlement.state(), true, settlement.sellerCredited(), settlement.claimPrepared(),
                    settlement.sellerNotified(), settlement.winnerNotified(), settlement.awaitingSellerNotified(),
                    settlement.awaitingWinnerNotified()); store.settlement(settlement); save();
        }
        if (economy.account(settlement.winnerId()).suspended() || economy.getAvailableBalance(settlement.winnerId()) < 0) {
            settlement = settlement.update(AuctionHouseStore.SettlementState.TIMING_OUT, true, false,
                    settlement.claimPrepared(), settlement.sellerNotified(), settlement.winnerNotified(), settlement.awaitingSellerNotified(), settlement.awaitingWinnerNotified());
            store.settlement(settlement); save(); return finishPaymentTimeout(settlement);
        }
        economy.payReservedDebit(settlement.id()); save();
        return completeSuccessfulSettlement(settlement);
    }

    private boolean completeSuccessfulSettlement(AuctionHouseStore.Settlement original) {
        AuctionHouseStore.Settlement settlement = current(original); EconomyService economy = economy();
        PaydayCharge charge = economy.getPaydayCharge(settlement.id()).orElse(null);
        if (charge == null || charge.status() != PaydayCharge.Status.PAID) return false;
        Auction auction = requireAuction(settlement);
        if (propertyPaused(auction)) return false;
        if (!validEscrow(auction)) {
            if (economy.isTransactionProcessed(sellerCreditReference(settlement.id())))
                throw new IllegalStateException("Auction property changed after seller payout; administrator recovery required.");
            UUID refund = EconomyService.stableReference("auction-invalid-asset-refund", settlement.id().toString());
            if (!economy.isTransactionProcessed(refund)) economy.deposit(settlement.winnerId(), settlement.amount(), refund,
                    "Auction refund: property unavailable");
            if (auction.state() != Auction.State.FAILED_INVALID_ASSET) {
                auction = auction.state(Auction.State.FAILED_INVALID_ASSET, 0); store.replace(auction); recordHistory(auction, clock.getAsLong());
            }
            settlement = settlement.update(AuctionHouseStore.SettlementState.INVALID_ASSET, true, false,
                    settlement.claimPrepared(), settlement.sellerNotified(), settlement.winnerNotified(),
                    settlement.awaitingSellerNotified(), settlement.awaitingWinnerNotified());
            store.settlement(settlement); save(); return finishInvalidAsset(settlement);
        }
        if (settlement.state() != AuctionHouseStore.SettlementState.PAYMENT_CAPTURED
                && settlement.state() != AuctionHouseStore.SettlementState.COMPLETED) {
            settlement = settlement.update(AuctionHouseStore.SettlementState.PAYMENT_CAPTURED, true,
                    settlement.sellerCredited(), settlement.claimPrepared(), settlement.sellerNotified(),
                    settlement.winnerNotified(), settlement.awaitingSellerNotified(), settlement.awaitingWinnerNotified());
            store.settlement(settlement); save();
        }
        UUID creditReference = sellerCreditReference(settlement.id());
        if (!economy.isTransactionProcessed(creditReference))
            MarketplaceTaxService.queuePayout(economy, settlement.sellerId(), creditReference,
                    new MarketplaceTaxService.Breakdown(MarketplaceTaxService.SourceType.AUCTION_SALE,
                            settlement.amount(), settlement.saleTaxBasisPoints(), settlement.saleTax(), settlement.sellerNet()),
                    "Auction Sale: " + assetName(auction.asset()));
        if (!settlement.sellerCredited()) {
            settlement = settlement.update(settlement.state(), settlement.chargeRegistered(), true, settlement.claimPrepared(),
                    settlement.sellerNotified(), settlement.winnerNotified(), settlement.awaitingSellerNotified(),
                    settlement.awaitingWinnerNotified()); store.settlement(settlement); save();
        }
        AuctionClaimStore claims = AuctionClaimStore.get(server);
        UUID claimId = claimReference(auction.id(), settlement.winnerId(), AuctionClaim.Reason.PURCHASE);
        if (claims.claim(claimId) == null) claims.add(new AuctionClaim(claimId, auction.id(), settlement.winnerId(),
                auction.asset(), AuctionClaim.Reason.PURCHASE, clock.getAsLong()));
        if (!settlement.claimPrepared()) {
            settlement = settlement.update(settlement.state(), settlement.chargeRegistered(), settlement.sellerCredited(), true,
                    settlement.sellerNotified(), settlement.winnerNotified(), settlement.awaitingSellerNotified(),
                    settlement.awaitingWinnerNotified()); store.settlement(settlement); save();
        }
        if (!settlement.claimNotified()) {
            new MailboxService(server).auctionClaimReady(claimId, settlement.winnerId(), assetName(auction.asset()),
                    auction.asset() instanceof AuctionAsset.PropertyAsset ? "Purchased property" : "Purchased item");
            settlement = settlement.update(settlement.state(), settlement.chargeRegistered(), settlement.sellerCredited(), settlement.claimPrepared(),
                    settlement.sellerNotified(), settlement.winnerNotified(), settlement.awaitingSellerNotified(),
                    settlement.awaitingWinnerNotified(), settlement.returnNotified(), true);
            store.settlement(settlement); save();
        }
        if (auction.state() != Auction.State.COMPLETED) {
            auction = auction.state(Auction.State.COMPLETED, 0); store.replace(auction); recordHistory(auction, clock.getAsLong()); save();
        } else if (store.history(auction.id()) == null) { recordHistory(auction, clock.getAsLong()); save(); }
        MailboxService mailbox = new MailboxService(server); String asset = assetName(auction.asset());
        if (!settlement.winnerNotified()) {
            mailbox.auctionPaymentCompleted(settlement.id(), settlement.winnerId(), settlement.sellerId(), asset, settlement.amount());
            settlement = settlement.update(settlement.state(), settlement.chargeRegistered(), settlement.sellerCredited(), settlement.claimPrepared(),
                    settlement.sellerNotified(), true, settlement.awaitingSellerNotified(), settlement.awaitingWinnerNotified());
            store.settlement(settlement); save();
        }
        if (!settlement.sellerNotified()) {
            mailbox.auctionSold(settlement.id(), settlement.sellerId(), settlement.winnerId(), asset,
                    settlement.amount(), settlement.saleTax(), settlement.sellerNet());
            settlement = settlement.update(settlement.state(), settlement.chargeRegistered(), settlement.sellerCredited(), settlement.claimPrepared(),
                    true, settlement.winnerNotified(), settlement.awaitingSellerNotified(), settlement.awaitingWinnerNotified());
            store.settlement(settlement); save();
        }
        if (settlement.state() != AuctionHouseStore.SettlementState.COMPLETED) {
            settlement = settlement.update(AuctionHouseStore.SettlementState.COMPLETED, settlement.chargeRegistered(), true, true,
                    settlement.sellerNotified(), settlement.winnerNotified(), settlement.awaitingSellerNotified(),
                    settlement.awaitingWinnerNotified()); store.settlement(settlement); save();
        }
        store.finishSettlement(auction.id()); save();
        return true;
    }

    private boolean finishNoBid(AuctionHouseStore.Settlement original) {
        AuctionHouseStore.Settlement settlement = current(original); Auction auction = requireAuction(settlement);
        if (store.history(auction.id()) == null) { recordHistory(auction, auction.deadline()); save(); }
        releaseAuctionReservations(auction);
        UUID claimId = null;
        if (auction.asset() instanceof AuctionAsset.PropertyAsset property) {
            new PropertyService(server).unlockAfterFailedAuction(settlement.sellerId(), property.propertyId(), auction.id());
        } else {
            AuctionClaimStore claims = AuctionClaimStore.get(server);
            claimId = claimReference(auction.id(), settlement.sellerId(), AuctionClaim.Reason.RETURN_NO_BIDS);
            if (claims.claim(claimId) == null) claims.add(new AuctionClaim(claimId, auction.id(), settlement.sellerId(),
                    auction.asset(), AuctionClaim.Reason.RETURN_NO_BIDS, clock.getAsLong()));
        }
        if (!settlement.claimPrepared()) {
            settlement = settlement.update(settlement.state(), settlement.chargeRegistered(), settlement.sellerCredited(), true,
                    settlement.sellerNotified(), settlement.winnerNotified(), settlement.awaitingSellerNotified(),
                    settlement.awaitingWinnerNotified()); store.settlement(settlement); save();
        }
        MailboxService mailbox = new MailboxService(server); String asset = assetName(auction.asset());
        if (!settlement.sellerNotified()) {
            mailbox.auctionNoBids(settlement.id(), settlement.sellerId(), asset);
            settlement = settlement.update(settlement.state(), settlement.chargeRegistered(), settlement.sellerCredited(), settlement.claimPrepared(),
                    true, settlement.winnerNotified(), settlement.awaitingSellerNotified(), settlement.awaitingWinnerNotified());
            store.settlement(settlement); save();
        }
        if (!settlement.returnNotified()) {
            mailbox.auctionReturned(settlement.id(), settlement.sellerId(), asset, "Returned because the auction received no bids");
            settlement = settlement.update(settlement.state(), settlement.chargeRegistered(), settlement.sellerCredited(), settlement.claimPrepared(),
                    settlement.sellerNotified(), settlement.winnerNotified(), settlement.awaitingSellerNotified(),
                    settlement.awaitingWinnerNotified(), true, settlement.claimNotified());
            store.settlement(settlement); save();
        }
        if (!settlement.claimNotified() && claimId != null) {
            mailbox.auctionClaimReady(claimId, settlement.sellerId(), asset, "Returned auction item");
            settlement = settlement.update(settlement.state(), settlement.chargeRegistered(), settlement.sellerCredited(), settlement.claimPrepared(),
                    settlement.sellerNotified(), settlement.winnerNotified(), settlement.awaitingSellerNotified(),
                    settlement.awaitingWinnerNotified(), settlement.returnNotified(), true);
            store.settlement(settlement); save();
        } else if (!settlement.claimNotified()) {
            settlement = settlement.update(settlement.state(), settlement.chargeRegistered(), settlement.sellerCredited(), settlement.claimPrepared(),
                    settlement.sellerNotified(), settlement.winnerNotified(), settlement.awaitingSellerNotified(),
                    settlement.awaitingWinnerNotified(), settlement.returnNotified(), true);
            store.settlement(settlement); save();
        }
        store.finishSettlement(auction.id()); save(); return true;
    }

    private boolean finishInvalidAsset(AuctionHouseStore.Settlement original) {
        AuctionHouseStore.Settlement settlement = current(original); Auction auction = requireAuction(settlement);
        var charge = economy().getPaydayCharge(settlement.id()).orElse(null);
        if (charge != null && charge.status() == PaydayCharge.Status.ACTIVE)
            economy().cancelPaydayDebit(settlement.id(), settlement.winnerId(), settlement.reservationReference(),
                    EconomyService.stableReference("auction-invalid-charge", settlement.id().toString()));
        releaseAuctionReservations(auction);
        if (auction.asset() instanceof AuctionAsset.PropertyAsset property)
            new PropertyService(server).unlockAfterFailedAuction(settlement.sellerId(), property.propertyId(), auction.id());
        if (!settlement.claimPrepared()) {
            settlement = settlement.update(settlement.state(), settlement.chargeRegistered(), settlement.sellerCredited(), true,
                    settlement.sellerNotified(), settlement.winnerNotified(), settlement.awaitingSellerNotified(), settlement.awaitingWinnerNotified());
            store.settlement(settlement); save();
        }
        if (!settlement.sellerNotified()) {
            new MailboxService(server).auctionReturned(settlement.id(), settlement.sellerId(), assetName(auction.asset()),
                    "Auction cancelled because the property is no longer valid");
            settlement = settlement.update(settlement.state(), settlement.chargeRegistered(), settlement.sellerCredited(), settlement.claimPrepared(),
                    true, settlement.winnerNotified(), settlement.awaitingSellerNotified(), settlement.awaitingWinnerNotified(), true, true);
            store.settlement(settlement); save();
        }
        if (settlement.winnerId() != null && !settlement.winnerNotified()) {
            new MailboxService(server).system(settlement.winnerId(), settlement.id(), "Auction cancelled: property unavailable",
                    List.of(new MailboxDetail(MailboxDetail.Kind.INCOMING, "Captured payment was refunded", settlement.amount())));
            settlement = settlement.update(settlement.state(), settlement.chargeRegistered(), settlement.sellerCredited(), settlement.claimPrepared(),
                    settlement.sellerNotified(), true, settlement.awaitingSellerNotified(), settlement.awaitingWinnerNotified(), true, true);
            store.settlement(settlement); save();
        }
        store.finishSettlement(auction.id()); save(); return true;
    }

    private boolean finishCancelled(AuctionHouseStore.Settlement original) {
        AuctionHouseStore.Settlement settlement = current(original); Auction auction = requireAuction(settlement);
        if (store.history(auction.id()) == null) { recordHistory(auction, clock.getAsLong()); save(); }
        releaseAuctionReservations(auction);
        UUID claimId = null;
        if (auction.asset() instanceof AuctionAsset.PropertyAsset property) {
            new PropertyService(server).unlockAfterFailedAuction(settlement.sellerId(), property.propertyId(), auction.id());
        } else {
            AuctionClaimStore claims = AuctionClaimStore.get(server);
            claimId = claimReference(auction.id(), settlement.sellerId(), AuctionClaim.Reason.RETURN_CANCELLED);
            if (claims.claim(claimId) == null) claims.add(new AuctionClaim(claimId, auction.id(), settlement.sellerId(),
                    auction.asset(), AuctionClaim.Reason.RETURN_CANCELLED, clock.getAsLong()));
        }
        if (!settlement.claimPrepared()) {
            settlement = settlement.update(settlement.state(), settlement.chargeRegistered(), settlement.sellerCredited(), true,
                    settlement.sellerNotified(), settlement.winnerNotified(), settlement.awaitingSellerNotified(), settlement.awaitingWinnerNotified());
            store.settlement(settlement); save();
        }
        MailboxService mailbox = new MailboxService(server); String asset = assetName(auction.asset());
        if (!settlement.sellerNotified()) {
            mailbox.auctionReturned(settlement.id(), settlement.sellerId(), asset, "Returned after you removed the auction");
            settlement = settlement.update(settlement.state(), settlement.chargeRegistered(), settlement.sellerCredited(), settlement.claimPrepared(),
                    true, settlement.winnerNotified(), settlement.awaitingSellerNotified(), settlement.awaitingWinnerNotified(), true, settlement.claimNotified());
            store.settlement(settlement); save();
        }
        if (!settlement.winnerNotified()) {
            if (settlement.winnerId() != null) mailbox.system(settlement.winnerId(), settlement.id(), "Auction removed: " + asset,
                    List.of(new MailboxDetail(MailboxDetail.Kind.INCOMING, "Bid reservation released", settlement.amount())));
            settlement = settlement.update(settlement.state(), settlement.chargeRegistered(), settlement.sellerCredited(), settlement.claimPrepared(),
                    settlement.sellerNotified(), true, settlement.awaitingSellerNotified(), settlement.awaitingWinnerNotified(),
                    settlement.returnNotified(), settlement.claimNotified());
            store.settlement(settlement); save();
        }
        if (!settlement.claimNotified() && claimId != null) {
            mailbox.auctionClaimReady(claimId, settlement.sellerId(), asset, "Removed auction item");
            settlement = settlement.update(settlement.state(), settlement.chargeRegistered(), settlement.sellerCredited(), settlement.claimPrepared(),
                    settlement.sellerNotified(), settlement.winnerNotified(), settlement.awaitingSellerNotified(), settlement.awaitingWinnerNotified(),
                    settlement.returnNotified(), true);
            store.settlement(settlement); save();
        } else if (!settlement.claimNotified()) {
            settlement = settlement.update(settlement.state(), settlement.chargeRegistered(), settlement.sellerCredited(), settlement.claimPrepared(),
                    settlement.sellerNotified(), settlement.winnerNotified(), settlement.awaitingSellerNotified(), settlement.awaitingWinnerNotified(),
                    settlement.returnNotified(), true);
            store.settlement(settlement); save();
        }
        store.finishSettlement(auction.id()); save(); return true;
    }

    private boolean finishPaymentTimeout(AuctionHouseStore.Settlement original) {
        AuctionHouseStore.Settlement settlement = current(original); EconomyService economy = economy();
        Optional<PaydayCharge> charge = economy.getPaydayCharge(settlement.id());
        if (charge.isPresent() && charge.get().status() == PaydayCharge.Status.PAID)
            return completeSuccessfulSettlement(settlement);
        if (charge.isPresent() && charge.get().status() == PaydayCharge.Status.ACTIVE)
            economy.cancelPaydayDebit(settlement.id(), settlement.winnerId(), settlement.reservationReference(),
                    EconomyService.stableReference("auction-payment-timeout", settlement.id().toString()));
        else {
            Optional<Reservation> hold = economy.getReservation(settlement.reservationReference());
            if (hold.isPresent() && hold.get().active()) economy.releaseReservation(settlement.winnerId(), settlement.reservationReference());
        }
        Auction auction = requireAuction(settlement);
        if (auction.state() != Auction.State.FAILED_PAYMENT_TIMEOUT) {
            long endedAt = auction.paymentDeadline() > 0 ? auction.paymentDeadline() : clock.getAsLong();
            auction = auction.state(Auction.State.FAILED_PAYMENT_TIMEOUT, auction.paymentDeadline());
            store.replace(auction); recordHistory(auction, endedAt); save();
        } else if (store.history(auction.id()) == null) { recordHistory(auction, clock.getAsLong()); save(); }
        UUID claimId = null;
        if (auction.asset() instanceof AuctionAsset.PropertyAsset property) {
            new PropertyService(server).unlockAfterFailedAuction(settlement.sellerId(), property.propertyId(), auction.id());
        } else {
            AuctionClaimStore claims = AuctionClaimStore.get(server);
            claimId = claimReference(auction.id(), settlement.sellerId(), AuctionClaim.Reason.RETURN_PAYMENT_TIMEOUT);
            if (claims.claim(claimId) == null) claims.add(new AuctionClaim(claimId, auction.id(), settlement.sellerId(),
                    auction.asset(), AuctionClaim.Reason.RETURN_PAYMENT_TIMEOUT, clock.getAsLong()));
        }
        if (!settlement.claimPrepared()) {
            settlement = settlement.update(settlement.state(), settlement.chargeRegistered(), settlement.sellerCredited(), true,
                    settlement.sellerNotified(), settlement.winnerNotified(), settlement.awaitingSellerNotified(),
                    settlement.awaitingWinnerNotified()); store.settlement(settlement); save();
        }
        MailboxService mailbox = new MailboxService(server); String asset = assetName(auction.asset());
        if (!settlement.winnerNotified()) {
            mailbox.auctionPaymentTimeout(settlement.id(), settlement.winnerId(), settlement.sellerId(), asset, settlement.amount());
            settlement = settlement.update(settlement.state(), settlement.chargeRegistered(), settlement.sellerCredited(), settlement.claimPrepared(),
                    settlement.sellerNotified(), true, settlement.awaitingSellerNotified(), settlement.awaitingWinnerNotified());
            store.settlement(settlement); save();
        }
        if (!settlement.returnNotified()) {
            mailbox.auctionReturned(settlement.id(), settlement.sellerId(), asset, "Returned because the winner could not pay");
            settlement = settlement.update(settlement.state(), settlement.chargeRegistered(), settlement.sellerCredited(), settlement.claimPrepared(),
                    settlement.sellerNotified(), settlement.winnerNotified(), settlement.awaitingSellerNotified(),
                    settlement.awaitingWinnerNotified(), true, settlement.claimNotified());
            store.settlement(settlement); save();
        }
        if (!settlement.claimNotified() && claimId != null) {
            mailbox.auctionClaimReady(claimId, settlement.sellerId(), asset, "Returned auction item");
            settlement = settlement.update(settlement.state(), settlement.chargeRegistered(), settlement.sellerCredited(), settlement.claimPrepared(),
                    settlement.sellerNotified(), settlement.winnerNotified(), settlement.awaitingSellerNotified(),
                    settlement.awaitingWinnerNotified(), settlement.returnNotified(), true);
            store.settlement(settlement); save();
        } else if (!settlement.claimNotified()) {
            settlement = settlement.update(settlement.state(), settlement.chargeRegistered(), settlement.sellerCredited(), settlement.claimPrepared(),
                    settlement.sellerNotified(), settlement.winnerNotified(), settlement.awaitingSellerNotified(),
                    settlement.awaitingWinnerNotified(), settlement.returnNotified(), true);
            store.settlement(settlement); save();
        }
        if (!settlement.sellerNotified()) {
            mailbox.auctionPaymentTimeout(settlement.id(), settlement.sellerId(), settlement.winnerId(), asset, settlement.amount());
            settlement = settlement.update(settlement.state(), settlement.chargeRegistered(), settlement.sellerCredited(), settlement.claimPrepared(),
                    true, settlement.winnerNotified(), settlement.awaitingSellerNotified(), settlement.awaitingWinnerNotified());
            store.settlement(settlement); save();
        }
        if (settlement.state() != AuctionHouseStore.SettlementState.TIMED_OUT) {
            settlement = settlement.update(AuctionHouseStore.SettlementState.TIMED_OUT, settlement.chargeRegistered(), false, true,
                    settlement.sellerNotified(), settlement.winnerNotified(), settlement.awaitingSellerNotified(),
                    settlement.awaitingWinnerNotified()); store.settlement(settlement); save();
        }
        store.finishSettlement(auction.id()); save(); return true;
    }

    private void releaseAuctionReservations(Auction auction) {
        EconomyService economy = economy();
        for (UUID participant : auction.participants()) {
            UUID reference = reservationReference(auction.id(), participant);
            Optional<Reservation> hold = economy.getReservation(reference);
            if (hold.isPresent() && hold.get().active()) economy.releaseReservation(participant, reference);
        }
    }

    private AuctionHouseStore.Settlement current(AuctionHouseStore.Settlement settlement) {
        AuctionHouseStore.Settlement current = store.settlement(settlement.auctionId());
        if (current == null || !current.id().equals(settlement.id())) throw new IllegalStateException("Auction settlement is missing");
        return current;
    }
    private void recordHistory(Auction auction, long endedAt) {
        if (store.history(auction.id()) != null) return;
        store.history(new AuctionHouseStore.HistoryWindow(auction.id(), endedAt, Long.MAX_VALUE));
        store.trimHistory(MAX_HISTORY_ENTRIES);
    }
    private Auction requireAuction(AuctionHouseStore.Settlement settlement) {
        Auction auction = store.auction(settlement.auctionId());
        if (auction == null) throw new IllegalStateException("Auction settlement has no auction");
        return auction;
    }
    public static UUID settlementReference(UUID auctionId) {
        return EconomyService.stableReference("auction-settlement", auctionId.toString());
    }
    private static UUID sellerCreditReference(UUID settlementId) {
        return EconomyService.stableReference("auction-seller-credit", settlementId.toString());
    }
    private static UUID claimReference(UUID auctionId, UUID owner, AuctionClaim.Reason reason) {
        return EconomyService.stableReference("auction-claim", auctionId + ":" + owner + ":" + reason);
    }

    private EconomyService economy() { return new EconomyService(EconomyLedger.get(server)); }
    private void save() { de.kuro.kuronomics.economy.FinancialPersistence.save(server); }
    private void checkThread() { if (server != null && !server.isSameThread()) throw new IllegalStateException("Auction house accessed off server thread"); }
    private static String fingerprint(UUID seller, long starting, long increment, int cycles) {
        return String.join("|", seller.toString(), Long.toString(starting), Long.toString(increment), Integer.toString(cycles));
    }
    private static String propertyFingerprint(UUID seller, String property, long starting, long increment, int cycles) {
        return String.join("|", seller.toString(), property, Long.toString(starting), Long.toString(increment), Integer.toString(cycles));
    }
    private static String bidFingerprint(UUID auction, UUID bidder, long amount, long revision) {
        return String.join("|", auction.toString(), bidder.toString(), Long.toString(amount), Long.toString(revision));
    }
    private static void requireCompleted(AuctionHouseStore.CompletedAction completed, AuctionHouseStore.ActionType type, String fingerprint) {
        if (completed.type() != type || !completed.fingerprint().equals(fingerprint))
            throw new IllegalArgumentException("Transaction reference was already used for another auction operation");
    }
    private String assetName(AuctionAsset asset) {
        if (asset instanceof AuctionAsset.ItemAsset item) return item.stack().getHoverName().getString();
        if (asset instanceof AuctionAsset.PropertyAsset property) return new PropertyService(server).displayName(property.propertyId());
        return "Auction";
    }
    private boolean validEscrow(Auction auction) {
        if (!(auction.asset() instanceof AuctionAsset.PropertyAsset property)) return true;
        return new PropertyService(server).isEscrowedForAuction(property.propertyId(), auction.id(), auction.sellerId());
    }
    private boolean propertyIntegrationAvailable() {
        return server != null && new PropertyService(server).integrationAvailable();
    }
    public boolean chargeUsable(PaydayCharge charge) {
        checkThread();
        return store.settlements().stream().filter(s -> s.id().equals(charge.reference()))
                .anyMatch(s -> { Auction auction = store.auction(s.auctionId()); return auction != null && validEscrow(auction); });
    }
    private boolean propertyPaused(Auction auction) {
        return auction.asset() instanceof AuctionAsset.PropertyAsset property
                && (!propertyIntegrationAvailable() || new PropertyService(server).awaitingRegistration(property.propertyId()));
    }
    static boolean hasExactItems(Inventory inventory, ItemStack wanted) {
        int remaining = wanted.getCount();
        for (ItemStack stack : inventory.items) if (ItemStack.isSameItemSameComponents(stack, wanted)) {
            remaining -= stack.getCount(); if (remaining <= 0) return true;
        }
        return false;
    }
    static boolean removeExactItems(Inventory inventory, ItemStack wanted) {
        if (!hasExactItems(inventory, wanted)) return false;
        int remaining = wanted.getCount();
        for (int i = 0; i < inventory.items.size() && remaining > 0; i++) {
            ItemStack stack = inventory.items.get(i);
            if (!ItemStack.isSameItemSameComponents(stack, wanted)) continue;
            int remove = Math.min(remaining, stack.getCount()); stack.shrink(remove); remaining -= remove;
        }
        inventory.setChanged(); return true;
    }
    static boolean hasCapacity(Inventory inventory, ItemStack wanted) {
        int remaining = wanted.getCount();
        int max = Math.min(inventory.getMaxStackSize(wanted), wanted.getMaxStackSize());
        for (ItemStack stack : inventory.items) {
            if (stack.isEmpty()) remaining -= max;
            else if (ItemStack.isSameItemSameComponents(stack, wanted)) remaining -= Math.max(0, max - stack.getCount());
            if (remaining <= 0) return true;
        }
        return false;
    }
    static void addItems(Inventory inventory, ItemStack wanted) {
        int remaining = wanted.getCount();
        int max = Math.min(inventory.getMaxStackSize(wanted), wanted.getMaxStackSize());
        for (ItemStack stack : inventory.items) if (!stack.isEmpty()
                && ItemStack.isSameItemSameComponents(stack, wanted) && remaining > 0) {
            int moved = Math.min(remaining, Math.max(0, max - stack.getCount())); stack.grow(moved); remaining -= moved;
        }
        for (int i = 0; i < inventory.items.size() && remaining > 0; i++) if (inventory.items.get(i).isEmpty()) {
            int moved = Math.min(remaining, max); inventory.items.set(i, wanted.copyWithCount(moved)); remaining -= moved;
        }
        if (remaining != 0) throw new IllegalStateException("Inventory capacity changed while claiming the auction item");
        inventory.setChanged();
    }
    private static boolean hasPlayerClaimMarker(ServerPlayer player, UUID claimId) {
        for (Tag raw : player.getPersistentData().getList(PLAYER_CLAIMS, Tag.TAG_INT_ARRAY))
            if (claimId.equals(NbtUtils.loadUUID(raw))) return true;
        return false;
    }
    private static void markPlayerClaim(ServerPlayer player, UUID claimId) {
        if (hasPlayerClaimMarker(player, claimId)) return;
        ListTag delivered = player.getPersistentData().getList(PLAYER_CLAIMS, Tag.TAG_INT_ARRAY);
        delivered.add(NbtUtils.createUUID(claimId)); player.getPersistentData().put(PLAYER_CLAIMS, delivered);
    }
    private void clearPlayerClaimMarker(ServerPlayer player, UUID claimId) {
        de.kuro.kuronomics.economy.FinancialWriter.await(player.server); // Wait for the completed transaction to reach disk before clearing its recovery marker.

        ListTag delivered = player.getPersistentData().getList(PLAYER_CLAIMS, Tag.TAG_INT_ARRAY);
        boolean removed = delivered.removeIf(raw -> claimId.equals(NbtUtils.loadUUID(raw)));
        if (!removed) return;
        if (delivered.isEmpty()) player.getPersistentData().remove(PLAYER_CLAIMS); else player.getPersistentData().put(PLAYER_CLAIMS, delivered);
        
    }
    private void clearPlayerListingMarker(ServerPlayer player, UUID reference) {
        de.kuro.kuronomics.economy.FinancialWriter.await(player.server); // Wait for the completed transaction to reach disk before clearing its recovery marker.

        CompoundTag markers = player.getPersistentData().getCompound(PLAYER_LISTINGS);
        if (!markers.contains(reference.toString())) return;
        markers.remove(reference.toString());
        if (markers.isEmpty()) player.getPersistentData().remove(PLAYER_LISTINGS); else player.getPersistentData().put(PLAYER_LISTINGS, markers);
        
    }
}
