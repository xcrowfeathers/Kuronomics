package de.kuro.kuronomics.economy.data;

import java.util.UUID;
import de.kuro.kuronomics.economy.Money;
import de.kuro.kuronomics.economy.tax.MarketplaceTaxService;

/** Income waiting for payday, grouped by description for the recipient's summary. */
public record Receipt(UUID recipient, String description, long amount, boolean taxable,
                      MarketplaceTaxService.SourceType sourceType, long grossAmount,
                      long marketplaceTax, long netAmount) {
    public static final String SHOP_SALES = "Shop Sales";

    public Receipt {
        if (recipient == null || description == null || description.isBlank()) throw new IllegalArgumentException("Invalid receipt");
        if (sourceType == null) {
            Money.positive(amount);
            if (grossAmount != amount || marketplaceTax != 0 || netAmount != amount)
                throw new IllegalArgumentException("Invalid ordinary receipt metadata");
        } else if (amount < 0 || grossAmount <= 0 || marketplaceTax < 0 || netAmount < 0
                || amount != netAmount || Math.addExact(marketplaceTax, netAmount) != grossAmount)
            throw new IllegalArgumentException("Invalid marketplace receipt metadata");
    }
    public Receipt(UUID recipient, String description, long amount, boolean taxable) {
        this(recipient, description, amount, taxable, null, amount, 0, amount);
    }
    public Receipt(UUID recipient, String description, long amount) { this(recipient, description, amount, true); }

    public static Receipt marketplace(UUID recipient, String description, boolean taxable,
                                      MarketplaceTaxService.Breakdown breakdown) {
        return new Receipt(recipient, description, breakdown.net(), taxable, breakdown.sourceType(),
                breakdown.gross(), breakdown.tax(), breakdown.net());
    }
    public boolean marketplace() { return sourceType != null; }

    /** Old saves made a separate shop receipt for every buyer. */
    public static String groupedDescription(String description) {
        if (description.equalsIgnoreCase("Shop Sale") || description.equalsIgnoreCase(SHOP_SALES)) return SHOP_SALES;
        String prefix = "Shop sale from ";
        if (description.regionMatches(true, 0, prefix, 0, prefix.length())) {
            String buyer = description.substring(prefix.length());
            try {
                if (UUID.fromString(buyer).toString().equalsIgnoreCase(buyer)) return SHOP_SALES;
            } catch (IllegalArgumentException ignored) { /* Custom descriptions aren't shop receipts. */ }
        }
        return description;
    }
}
