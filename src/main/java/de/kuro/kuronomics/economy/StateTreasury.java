package de.kuro.kuronomics.economy;

import de.kuro.kuronomics.economy.data.Account;
import de.kuro.kuronomics.economy.data.EconomyLedger;
import java.util.*;

/** The state uses a normal world ledger account in signed cents. It doesn't have a separate money store. */
public final class StateTreasury {
    private StateTreasury() {}
    public static long balance(EconomyLedger ledger) { return ledger.account(EconomicOwner.STATE).balance(); }

    /** Add the Treasury side to the caller's commit. This doesn't commit or save anything itself. */
    public static void stage(EconomyLedger ledger, Map<EconomicOwner, Account> updates, long delta) {
        if (delta == 0) return;
        Account current = updates.get(EconomicOwner.STATE);
        if (current == null) current = ledger.account(EconomicOwner.STATE);
        updates.put(EconomicOwner.STATE, current.balance(Math.addExact(current.balance(), delta)));
    }

    /** The caller must check OP permission. Normal SavedData saving handles this admin adjustment. */
    public static void set(EconomyLedger ledger, long value) {
        ledger.commitOwners(UUID.randomUUID(), "treasury-admin-set|" + value,
                Map.of(EconomicOwner.STATE, ledger.account(EconomicOwner.STATE).balance(value)), List.of(), ledger.companies(), Map.of());
    }
    public static void add(EconomyLedger ledger, long amount) {
        Money.positive(amount); set(ledger, Math.addExact(balance(ledger), amount));
    }
    public static void remove(EconomyLedger ledger, long amount) {
        Money.positive(amount);
        if (ledger.spendingCapacity(EconomicOwner.STATE) < amount) throw new IllegalArgumentException("Not enough Treasury funds.");
        set(ledger, Math.subtractExact(balance(ledger), amount));
    }
}
