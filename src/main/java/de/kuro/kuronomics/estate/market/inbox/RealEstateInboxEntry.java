package de.kuro.kuronomics.estate.market.inbox;

import java.util.Objects;
import java.util.UUID;

/** Keep player IDs as UUIDs in the save, then resolve names when building the GUI data. */
public record RealEstateInboxEntry(UUID id, RealEstateEventType type, long createdAt, UUID listingId,
                                   String listingName, Long amount, Long taxAmount, Long netAmount,
                                   UUID otherPlayer, String message) {
    public RealEstateInboxEntry {
        Objects.requireNonNull(id); Objects.requireNonNull(type); Objects.requireNonNull(listingId);
        listingName = Objects.requireNonNull(listingName).strip(); message = Objects.requireNonNull(message).strip();
        if (createdAt < 0 || listingName.isBlank() || listingName.length() > 128 || message.length() > 256)
            throw new IllegalArgumentException("Invalid Real Estate inbox event");
        if (taxAmount != null && taxAmount < 0 || netAmount != null && netAmount < 0)
            throw new IllegalArgumentException("Invalid Real Estate inbox amounts");
    }
}
