package de.kuro.kuronomics.economy.bank;

import de.kuro.kuronomics.economy.EconomicOwner;
import java.util.Objects;
import java.util.UUID;

/** Commit this Treasury movement together with the balance change. */
public record TreasuryMovement(EconomicOwner payer, EconomicOwner recipient, long amount, String label, UUID actor) {
    public TreasuryMovement {
        Objects.requireNonNull(payer); Objects.requireNonNull(recipient); Objects.requireNonNull(label);
        if (amount <= 0 || label.isBlank() || label.length() > 128
                || !payer.equals(EconomicOwner.STATE) && !recipient.equals(EconomicOwner.STATE))
            throw new IllegalArgumentException("Invalid Treasury movement");
    }
    public static TreasuryMovement payment(EconomicOwner owner, long stateDelta, String label) {
        return new TreasuryMovement(stateDelta > 0 ? owner : EconomicOwner.STATE,
                stateDelta > 0 ? EconomicOwner.STATE : owner, Math.abs(stateDelta), label, null);
    }
}
