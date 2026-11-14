package de.kuro.kuronomics.auction;

import de.kuro.kuronomics.economy.tax.MarketplaceTaxService;
import java.util.*;

public record Auction(UUID id, UUID sellerId, AuctionAsset asset, long startingBid, long minimumIncrease, int saleTaxBasisPoints,
                      UUID highestBidderId, long highestBid, long createdAt, long deadline, State state,
                      Set<UUID> participants, long revision, long paymentDeadline) {
    public Auction {
        Objects.requireNonNull(id); Objects.requireNonNull(sellerId); Objects.requireNonNull(asset); Objects.requireNonNull(state);
        if (startingBid <= 0 || minimumIncrease <= 0) throw new IllegalArgumentException("Auction prices must be positive");
        if (saleTaxBasisPoints < 0 || saleTaxBasisPoints > 10_000) throw new IllegalArgumentException("Invalid auction sale tax snapshot");
        if (createdAt < 0 || deadline <= createdAt || revision < 0 || paymentDeadline < 0)
            throw new IllegalArgumentException("Invalid auction timing or revision");
        if (highestBidderId == null && highestBid != 0) throw new IllegalArgumentException("Bid without bidder");
        if (highestBidderId != null && highestBid < startingBid) throw new IllegalArgumentException("Invalid highest bid");
        participants = Set.copyOf(participants == null ? Set.of() : participants);
    }

    /** Keeps older callers and saves working without tax fields. */
    public Auction(UUID id, UUID sellerId, AuctionAsset asset, long startingBid, long minimumIncrease,
                   UUID highestBidderId, long highestBid, long createdAt, long deadline, State state,
                   Set<UUID> participants, long revision, long paymentDeadline) {
        this(id, sellerId, asset, startingBid, minimumIncrease, 0, highestBidderId, highestBid,
                createdAt, deadline, state, participants, revision, paymentDeadline);
    }

    public enum State {
        ACTIVE, AWAITING_PAYMENT, COMPLETED, FAILED_NO_BIDS, FAILED_PAYMENT,
        FAILED_PAYMENT_TIMEOUT, FAILED_INVALID_ASSET, CANCELLED
    }

    public Auction state(State next, long paymentDeadline) {
        return new Auction(id, sellerId, asset, startingBid, minimumIncrease, saleTaxBasisPoints, highestBidderId, highestBid,
                createdAt, deadline, next, participants, Math.addExact(revision, 1), paymentDeadline);
    }

    public long minimumNextBid() {
        return highestBidderId == null ? startingBid : Math.addExact(highestBid, minimumIncrease);
    }

    public Auction bid(UUID bidder, long amount) {
        Objects.requireNonNull(bidder);
        Set<UUID> joined = new LinkedHashSet<>(participants); joined.add(bidder);
        return new Auction(id, sellerId, asset, startingBid, minimumIncrease, saleTaxBasisPoints, bidder, amount,
                createdAt, deadline, state, joined, Math.addExact(revision, 1), paymentDeadline);
    }

    public MarketplaceTaxService.Breakdown saleBreakdown(long gross) {
        return MarketplaceTaxService.calculate(MarketplaceTaxService.SourceType.AUCTION_SALE, gross, saleTaxBasisPoints);
    }
}
