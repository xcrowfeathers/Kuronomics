package de.kuro.kuronomics.estate.market;

import de.kuro.kuronomics.economy.tax.MarketplaceTaxService;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** This is a saved listing snapshot. PropertyService/WorldGuard still decides who owns the property. */
public record RealEstateListing(UUID id, UUID creationReference, String creationFingerprint,
                                String propertyId, UUID sellerId, String listingName,
                                String location, String description, long price,
                                String propertyName, String dimension, long width, long length,
                                int taxBasisPoints, State state, String invalidReason,
                                long revision, long createdAt, long updatedAt) {
    public enum State { ACTIVE, SETTLEMENT_PENDING, SOLD, CANCELLED, INVALIDATED }
    static final int NAME_LIMIT = 64, LOCATION_LIMIT = 128, DESCRIPTION_LIMIT = 512, REASON_LIMIT = 160;

    public RealEstateListing {
        Objects.requireNonNull(id); Objects.requireNonNull(creationReference); Objects.requireNonNull(sellerId);
        Objects.requireNonNull(state); Objects.requireNonNull(creationFingerprint); Objects.requireNonNull(propertyId);
        Objects.requireNonNull(listingName); Objects.requireNonNull(location); Objects.requireNonNull(description);
        Objects.requireNonNull(propertyName); Objects.requireNonNull(dimension); Objects.requireNonNull(invalidReason);
        if (creationFingerprint.isBlank() || propertyId.isBlank() || listingName.isBlank() || location.isBlank()
                || propertyName.isBlank() || dimension.isBlank() || price <= 0 || width <= 0 || length <= 0
                || taxBasisPoints < 0 || taxBasisPoints > 10_000 || revision < 0 || createdAt < 0 || updatedAt < createdAt)
            throw new IllegalArgumentException("Invalid real-estate listing");
        if (listingName.length() > NAME_LIMIT || location.length() > LOCATION_LIMIT
                || description.length() > DESCRIPTION_LIMIT || invalidReason.length() > REASON_LIMIT
                || propertyId.length() > 512 || propertyName.length() > 256 || dimension.length() > 256
                || creationFingerprint.length() > 2_048)
            throw new IllegalArgumentException("Real-estate listing text exceeds its storage limit");
        if ((listingName + location + description + invalidReason).chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Real-estate listing contains unsupported control characters");
        Math.multiplyExact(width, length);
        if (state == State.INVALIDATED && invalidReason.isBlank())
            throw new IllegalArgumentException("Invalidated listing requires a reason");
    }

    public long grossPrice() { return price; }
    public BigDecimal taxRatePercent() { return BigDecimal.valueOf(taxBasisPoints, 2); }
    public long taxAmount() { return taxBreakdown().tax(); }
    public long sellerNetProceeds() { return taxBreakdown().net(); }
    public MarketplaceTaxService.Breakdown taxBreakdown() {
        return MarketplaceTaxService.calculate(MarketplaceTaxService.SourceType.REAL_ESTATE_SALE, price, taxBasisPoints);
    }
    public long area() { return Math.multiplyExact(width, length); }

    RealEstateListing metadata(String name, String place, String details, long newPrice, long now) {
        return new RealEstateListing(id, creationReference, creationFingerprint, propertyId, sellerId,
                name, place, details, newPrice, propertyName, dimension, width, length, taxBasisPoints,
                state, invalidReason, Math.addExact(revision, 1), createdAt, now);
    }

    RealEstateListing transition(State next, String reason, long now) {
        return new RealEstateListing(id, creationReference, creationFingerprint, propertyId, sellerId,
                listingName, location, description, price, propertyName, dimension, width, length,
                taxBasisPoints, next, reason, Math.addExact(revision, 1), createdAt, now);
    }
}
