package de.kuro.kuronomics.market;

import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import de.kuro.kuronomics.company.CompanyConfig;
import java.time.*;
import java.math.BigDecimal;

public record MarketConfig(boolean enabled, int openingHour, int closingHour, ZoneId zone, int maxPrivateListings,
                           int companyBaseListings, int companyListingsPerEmployee, int maxCompanyListings, BigDecimal salesTaxPercent,
                           int maxItemDataKiB) {
    public static final String DEFAULTS = """
            [marketplace]
            enabled = true
            openingHour = 12
            closingHour = 21
            # LOCAL follows system timezone.
            timeZone = "LOCAL"
            maxPrivateListings = 10
            companyBaseListings = 10
            companyListingsPerEmployee = 5
            maxCompanyListings = 50
            salesTaxPercent = 3.0
            # Maximum data per item, in KiB (1 KiB = 1024 bytes)
            # 0 disables the limit.
            maxItemDataKiB = 128
            """;
    public MarketConfig {
        if (openingHour < 0 || openingHour > 23 || closingHour < 0 || closingHour > 23 || zone == null
                || maxPrivateListings < 0 || companyBaseListings < 0 || companyListingsPerEmployee < 0 || maxCompanyListings < 0 || maxItemDataKiB < 0
                || salesTaxPercent == null || salesTaxPercent.signum() < 0 || salesTaxPercent.compareTo(BigDecimal.valueOf(100)) > 0)
            throw new IllegalArgumentException("Invalid marketplace configuration");
    }
    public boolean open(Instant instant) {
        int hour = instant.atZone(zone).getHour();
        return enabled && (openingHour == closingHour || (openingHour < closingHour ? hour >= openingHour && hour < closingHour : hour >= openingHour || hour < closingHour));
    }
    public String hours() { return String.format(java.util.Locale.ROOT, "%02d:00–%02d:00 %s", openingHour, closingHour, zone); }
    public static MarketConfig parse(String text) {
        UnmodifiableConfig root = new TomlParser().parse(text); Object raw = root.get("marketplace");
        if (!(raw instanceof UnmodifiableConfig c)) throw new IllegalArgumentException("Missing marketplace settings");
        Object enabled = c.get("enabled"); if (!(enabled instanceof Boolean on)) throw new IllegalArgumentException("enabled must be true or false");
        return new MarketConfig(on, integer(c, "openingHour"), integer(c, "closingHour"), CompanyConfig.resolveZone(c.get("timeZone").toString()),
                integer(c, "maxPrivateListings"), integer(c, "companyBaseListings"), integer(c, "companyListingsPerEmployee"), integer(c, "maxCompanyListings"), new BigDecimal(c.get("salesTaxPercent").toString()),
                c.contains("maxItemDataKiB") ? integer(c, "maxItemDataKiB") : 128);
    }
    private static int integer(UnmodifiableConfig c, String key) {
        try { return new BigDecimal(c.get(key).toString()).intValueExact(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException(key + " must be a whole number within the integer range.", invalid); }
    }
}
