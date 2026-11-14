package de.kuro.kuronomics.economy.bank;

import java.util.Locale;

public enum BankCategory {
    TAXES("Tax income"), TAX_REFUNDS("Tax refunds"), LOANS("Loans"), INTEREST("Interest paid"),
    SALARIES("Job salaries"), TRANSFERS("Bank payments"), TRADE("Public trade"), OTHERS("Others");

    public final String label;
    BankCategory(String label) { this.label = label; }

    public static BankCategory classify(BankActivity.Entry entry) {
        String type = entry.type().toLowerCase(Locale.ROOT);
        if (type.contains("loan")) return LOANS;
        if (type.contains("tax") || type.equals("funded sale") || type.equals("push marketplace income"))
            return entry.incoming() ? TAXES : TAX_REFUNDS;
        if (type.contains("interest")) return INTEREST;
        if (type.contains("salary") || type.equals("job change")) return SALARIES;
        if (type.equals("pay") || type.equals("debit") || type.equals("invest")) return TRANSFERS;
        if (type.contains("server shop") || type.contains("estate purchase") || type.contains("estate sale") || type.contains("company asset") || type.contains("company_property")) return TRADE;
        return OTHERS;
    }
}
