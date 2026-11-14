package de.kuro.kuronomics.economy.tax;

import de.kuro.kuronomics.event.EconomyEvents;
import de.kuro.kuronomics.economy.EconomyService;
import de.kuro.kuronomics.economy.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

public final class MarketplaceTaxService {
    public enum SourceType {
        AUCTION_SALE(true), REAL_ESTATE_SALE(false), GLOBAL_MARKET_SALE(true);
        private final boolean generallyTaxable;
        SourceType(boolean generallyTaxable) { this.generallyTaxable = generallyTaxable; }
        public boolean generallyTaxable() { return generallyTaxable; }
    }

    public record Breakdown(SourceType sourceType, long gross, int taxBasisPoints, long tax, long net) {
        public Breakdown {
            Objects.requireNonNull(sourceType); Money.positive(gross);
            if (taxBasisPoints < 0 || taxBasisPoints > 10_000 || tax < 0 || net < 0
                    || Math.addExact(tax, net) != gross)
                throw new IllegalArgumentException("Invalid marketplace tax breakdown");
        }
    }

    private MarketplaceTaxService() {}

    public static int configuredBasisPoints(SourceType sourceType) {
        Objects.requireNonNull(sourceType);
        double configured = switch (sourceType) {
            case AUCTION_SALE -> EconomyEvents.config().auctionSaleTaxPercent();
            case REAL_ESTATE_SALE -> EconomyEvents.config().realEstateSaleTaxPercent();
            case GLOBAL_MARKET_SALE -> de.kuro.kuronomics.market.MarketService.getCurrentTaxPercent();
        };
        if (!Double.isFinite(configured) || configured < 0 || configured > 100)
            throw new IllegalStateException("Invalid " + sourceType.name().toLowerCase() + " tax percentage");
        return BigDecimal.valueOf(configured).movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    public static Breakdown calculate(SourceType sourceType, long gross, int taxBasisPoints) {
        Money.positive(gross);
        if (taxBasisPoints < 0 || taxBasisPoints > 10_000)
            throw new IllegalArgumentException("Marketplace sale tax must be between 0 and 100 percent");
        long tax = Money.percent(gross, BigDecimal.valueOf(taxBasisPoints, 2));
        return new Breakdown(sourceType, gross, taxBasisPoints, tax, Math.subtractExact(gross, tax));
    }

    public static boolean queuePayout(EconomyService economy, UUID seller, UUID reference,
                                      Breakdown breakdown, String description) {
        Objects.requireNonNull(economy); Objects.requireNonNull(seller); Objects.requireNonNull(reference);
        return economy.pushMarketplaceIncome(seller, reference, description, breakdown);
    }
}
