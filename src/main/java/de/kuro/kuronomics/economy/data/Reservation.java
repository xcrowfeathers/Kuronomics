package de.kuro.kuronomics.economy.data;

import java.util.UUID;

/** Reserve spendable money. Keep released holds as records so retries can't apply them again. */
public record Reservation(UUID reference, de.kuro.kuronomics.economy.EconomicOwner economicOwner, long amount, boolean active) {
    public Reservation(UUID reference, UUID owner, long amount, boolean active) {
        this(reference, de.kuro.kuronomics.economy.EconomicOwner.player(owner), amount, active);
    }
    /** For personal-account callers. Don't pass off a company UUID as a player UUID. */
    public UUID owner() { return economicOwner.playerId(); }
    public Reservation {
        if (reference == null || economicOwner == null || amount <= 0) throw new IllegalArgumentException("Invalid reservation");
    }

    public Reservation released() { return active ? new Reservation(reference, economicOwner, amount, false) : this; }
}
