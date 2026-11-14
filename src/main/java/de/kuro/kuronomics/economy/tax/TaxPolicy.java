package de.kuro.kuronomics.economy.tax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

public record TaxPolicy(boolean enabled, List<Bracket> brackets) {
    public record Bracket(long minimum, long maximum, BigDecimal percent) {
        public Bracket {
            if (minimum < 0 || maximum != -1 && maximum < minimum || percent.signum() < 0 || percent.compareTo(BigDecimal.valueOf(100)) > 0)
                throw new IllegalArgumentException("Invalid tax bracket");
        }
    }
    public TaxPolicy {
        brackets = brackets.stream().sorted(Comparator.comparingLong(Bracket::minimum)).toList();
        long previous = -1;
        for (Bracket b : brackets) {
            if (b.minimum() <= previous) throw new IllegalArgumentException("Overlapping tax brackets");
            previous = b.maximum() == -1 ? Long.MAX_VALUE : b.maximum();
        }
    }
    /** Keep the original tax rule: rounded income, a single bracket and whole-dollar tax. */
    public long calculate(long taxableCents) {
        if (!enabled || taxableCents <= 0) return 0;
        long dollars = BigDecimal.valueOf(taxableCents, 2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        for (Bracket b : brackets) {
            if (dollars >= b.minimum() && (b.maximum() == -1 || dollars <= b.maximum()))
                return BigDecimal.valueOf(dollars).multiply(b.percent()).divide(BigDecimal.valueOf(100), 0, RoundingMode.DOWN).movePointRight(2).longValueExact();
        }
        return 0;
    }
}
