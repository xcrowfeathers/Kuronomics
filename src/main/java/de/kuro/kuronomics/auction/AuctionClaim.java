package de.kuro.kuronomics.auction;

import java.util.*;

/** Claims live outside the capped mailbox, so clearing old notifications can't lose someone's items. */
public record AuctionClaim(UUID id, UUID auctionId, UUID ownerId, AuctionAsset asset, Reason reason, long readyAt) {
    public AuctionClaim {
        Objects.requireNonNull(id); Objects.requireNonNull(auctionId); Objects.requireNonNull(ownerId); Objects.requireNonNull(asset); Objects.requireNonNull(reason);
        if (readyAt < 0) throw new IllegalArgumentException("Invalid auction claim timestamp");
    }
    public enum Reason { PURCHASE, RETURN_NO_BIDS, RETURN_PAYMENT_TIMEOUT, RETURN_CANCELLED }
}
