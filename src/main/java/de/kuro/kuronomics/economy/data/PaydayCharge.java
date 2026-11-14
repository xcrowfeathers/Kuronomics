package de.kuro.kuronomics.economy.data;

import java.util.*;

/** Saved proof of a reserved debit. Auctions capture it at close; old payday records still need to load. */
public record PaydayCharge(UUID reference, UUID payer, long amount, String description,
                           UUID reservationReference, Status status, UUID paidByPayday) {
    public PaydayCharge {
        Objects.requireNonNull(reference); Objects.requireNonNull(payer); Objects.requireNonNull(reservationReference);
        Objects.requireNonNull(status); description = Objects.requireNonNull(description).strip();
        if (amount <= 0 || description.isEmpty() || description.length() > 128)
            throw new IllegalArgumentException("Invalid PayDay charge");
        if (status == Status.PAID && paidByPayday == null || status != Status.PAID && paidByPayday != null)
            throw new IllegalArgumentException("Invalid PayDay charge completion state");
    }
    public enum Status { ACTIVE, PAID, CANCELLED }
    public PaydayCharge paid(UUID paydayReference) {
        if (status == Status.PAID && paidByPayday.equals(paydayReference)) return this;
        if (status != Status.ACTIVE) throw new IllegalStateException("PayDay charge is not active");
        return new PaydayCharge(reference, payer, amount, description, reservationReference, Status.PAID, paydayReference);
    }
    public PaydayCharge cancelled() {
        return status == Status.CANCELLED ? this : new PaydayCharge(reference, payer, amount, description,
                reservationReference, Status.CANCELLED, null);
    }
}
