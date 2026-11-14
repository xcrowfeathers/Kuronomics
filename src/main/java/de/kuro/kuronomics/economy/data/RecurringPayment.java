package de.kuro.kuronomics.economy.data;

import java.util.UUID;

/** A null receiver means a signed state payment to the account in payer. */
public record RecurringPayment(UUID payer, UUID receiver, long amount, String name) {
    public RecurringPayment {
        if (payer == null || name == null || name.isBlank() || name.length() > 128)
            throw new IllegalArgumentException("Invalid contract identity");
        if (receiver != null && (receiver.equals(payer) || amount <= 0))
            throw new IllegalArgumentException("Invalid player contract");
        if (amount == 0 || amount == Long.MIN_VALUE) throw new IllegalArgumentException("Invalid contract amount");
    }
    public boolean bank() { return receiver == null; }
}
