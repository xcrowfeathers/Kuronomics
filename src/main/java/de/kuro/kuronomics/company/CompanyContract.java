package de.kuro.kuronomics.company;

import de.kuro.kuronomics.economy.EconomicOwner;
import java.util.*;

/** Company agreements are separate from personal /contract agreements. */
public record CompanyContract(UUID id, EconomicOwner payer, EconomicOwner recipient, String name, long amount,
                              Status status, UUID createdBy, long createdAt) {
    public enum Status { ACTIVE, CANCELLED }
    public CompanyContract {
        Objects.requireNonNull(id); Objects.requireNonNull(payer); Objects.requireNonNull(recipient);
        Objects.requireNonNull(status); Objects.requireNonNull(createdBy);
        name = displayName(name);
        if (amount <= 0 || payer.equals(recipient) || createdAt < 0
                || !(payer.isCompany() || (payer.isPlayer() || payer.equals(EconomicOwner.STATE)) && recipient.isCompany()))
            throw new IllegalArgumentException("Invalid Company contract");
    }
    public boolean stateContract() { return payer.equals(EconomicOwner.STATE) || recipient.equals(EconomicOwner.STATE); }
    public static String displayName(String name) {
        Objects.requireNonNull(name);
        name = name.trim();
        if (name.isBlank() || name.length() > 128 || name.chars().anyMatch(c -> Character.isISOControl(c) || c == '§'))
            throw new IllegalArgumentException("Choose a contract name with 1–128 characters.");
        if (name.equalsIgnoreCase("Shop Sale") || name.regionMatches(true, 0, "Rent:", 0, 5))
            throw new IllegalArgumentException("That name is reserved for shops or rent.");
        return name;
    }
    public static String legacyName(UUID id) { return "Contract " + id; }
}
