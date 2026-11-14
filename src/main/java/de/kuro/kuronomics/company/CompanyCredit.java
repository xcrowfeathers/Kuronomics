package de.kuro.kuronomics.company;

import de.kuro.kuronomics.economy.EconomicOwner;
import java.util.Objects;

/** Money already received, grouped by source, category and agreement instead of one entry per payday. */
public record CompanyCredit(EconomicOwner source, String category, String association, String label, long amount, boolean taxable) {
    public CompanyCredit(EconomicOwner source, String category, String association, long amount, boolean taxable) {
        this(source, category, association, CompanyChat.category(category), amount, taxable);
    }
    public CompanyCredit {
        Objects.requireNonNull(source); Objects.requireNonNull(category); Objects.requireNonNull(association); Objects.requireNonNull(label);
        if (amount <= 0) throw new IllegalArgumentException("Invalid Company credit");
    }
    public String key() { return source + "|" + category + "|" + association + "|" + taxable; }
    public CompanyCredit add(long value) { return new CompanyCredit(source, category, association, label, Math.addExact(amount, value), taxable); }
}
