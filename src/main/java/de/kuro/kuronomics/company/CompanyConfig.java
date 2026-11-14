package de.kuro.kuronomics.company;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import de.kuro.kuronomics.economy.Money;
import de.kuro.kuronomics.economy.tax.TaxPolicy;
import java.io.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Company policy is separate. Don't silently borrow personal tax, interest or fixed-payment settings. */
public record CompanyConfig(long foundingCost, int confirmationSeconds, LocalTime paydayTime, ZoneId zone,
                            BigDecimal incomeTaxPercent, boolean interestEnabled, BigDecimal interestPercent,
                            long interestMinimum, long interestMaximum, List<FixedPayment> fixedPayments,
                            long debtLimit, BigDecimal debtFeePercent, BigDecimal ceoPenaltyPercent, TaxPolicy taxBrackets) {
    public CompanyConfig(long foundingCost, int confirmationSeconds, LocalTime paydayTime, ZoneId zone,
                         BigDecimal incomeTaxPercent, boolean interestEnabled, BigDecimal interestPercent,
                         long interestMinimum, long interestMaximum, List<FixedPayment> fixedPayments,
                         long debtLimit, BigDecimal debtFeePercent, BigDecimal ceoPenaltyPercent) {
        this(foundingCost, confirmationSeconds, paydayTime, zone, incomeTaxPercent, interestEnabled, interestPercent,
                interestMinimum, interestMaximum, fixedPayments, debtLimit, debtFeePercent, ceoPenaltyPercent,
                new TaxPolicy(false, List.of()));
    }
    public CompanyConfig(long foundingCost, int confirmationSeconds, LocalTime paydayTime, ZoneId zone,
                         BigDecimal incomeTaxPercent, boolean interestEnabled, BigDecimal interestPercent,
                         long interestMinimum, long interestMaximum, List<FixedPayment> fixedPayments) {
        this(foundingCost, confirmationSeconds, paydayTime, zone, incomeTaxPercent, interestEnabled, interestPercent,
                interestMinimum, interestMaximum, fixedPayments, 1_000_000, BigDecimal.valueOf(20), BigDecimal.valueOf(200));
    }
    public record FixedPayment(String name, long amount) {}
    private static final String MANAGEMENT_DEFAULTS = """
            # Company-related configuration. Reload with /companyadmin reload.
            [company]
            foundingCost = 10000
            confirmationSeconds = 60

            [debt]
            maximum = 10000
            paydayFeePercent = 20
            ceoPenaltyPercent = 200

            [payday]
            time = "18:00"
            # LOCAL uses the operating-system timezone. Zone IDs work too.
            timezone = "LOCAL"

            """;
    public static final String PAYDAY_DEFAULTS = """
            # Same payday-related rules as personal paydays. If unsure how to use, take a look at them first in the wiki.
            [tax]
            incomePercent = 0
            useTaxBrackets = true
            [[tax.brackets]]
            min = 0
            max = 4999
            rate = 0
            [[tax.brackets]]
            min = 5000
            max = 10000
            rate = 10
            [[tax.brackets]]
            min = 10001
            max = -1
            rate = 20

            [interest]
            enabled = false
            percent = 2
            minimumBalance = 500
            maximumPayment = 1000

            [[fixedPayments]]
            name = "Commercial Bonus"
            amount = 200
            [[fixedPayments]]
            name = "Enterprise Fee"
            amount = -200
            """;
    public static final String DEFAULTS = MANAGEMENT_DEFAULTS + "\n" + PAYDAY_DEFAULTS;
    public CompanyConfig {
        fixedPayments = List.copyOf(fixedPayments); Objects.requireNonNull(paydayTime); Objects.requireNonNull(zone); Objects.requireNonNull(taxBrackets);
        if (foundingCost < 0 || confirmationSeconds < 5 || confirmationSeconds > 600 || interestMinimum < 0 || interestMaximum < 0)
            throw new IllegalArgumentException("Invalid Company configuration");
        if (debtLimit < 0 || debtFeePercent.signum() < 0 || debtFeePercent.compareTo(BigDecimal.valueOf(100)) > 0
                || ceoPenaltyPercent.signum() < 0 || ceoPenaltyPercent.compareTo(BigDecimal.valueOf(1000)) > 0)
            throw new IllegalArgumentException("Invalid company debt settings");
        for (BigDecimal value : List.of(incomeTaxPercent, interestPercent)) if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0)
            throw new IllegalArgumentException("Company percentages must be between 0 and 100");
        Set<String> names = new HashSet<>(); for (FixedPayment value : fixedPayments)
            if (value.name() == null || value.name().isBlank() || value.name().length() > 80 || value.amount() == Long.MIN_VALUE || !names.add(value.name()))
                throw new IllegalArgumentException("Invalid Company fixed payment");
    }
    public long incomeTax(long taxable) { return taxBrackets.enabled() ? taxBrackets.calculate(taxable) : Money.percent(taxable, incomeTaxPercent); }
    public static CompanyConfig defaults() { return parse(DEFAULTS); }
    public static CompanyConfig load() { return load(de.kuro.kuronomics.config.EconomyConfig.directory()); }
    public static CompanyConfig load(Path directory) {
        Path path = directory.resolve("economy_companies.toml");
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(directory);
                Files.writeString(path, DEFAULTS, StandardOpenOption.CREATE_NEW);
            }
            return parse(Files.readString(path));
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Cannot load " + path + "; previous configuration retained", failure);
        }
    }
    private static UnmodifiableConfig table(UnmodifiableConfig config, String name) {
        if (config.get(name) instanceof UnmodifiableConfig value) return value;
        throw new IllegalArgumentException("Missing Company config table: " + name);
    }
    private static Object value(UnmodifiableConfig config, String name) {
        Object value = config.get(name); if (value == null) throw new IllegalArgumentException("Missing Company config value: " + name); return value;
    }
    private static long money(UnmodifiableConfig config, String name) { return Money.parse(value(config, name).toString()); }
    public static CompanyConfig parse(String text) {
        return parse(text, null);
    }
    public static CompanyConfig parse(String text, String policy) {
        var root = new TomlParser().parse(new StringReader(text)); var company = table(root, "company");
        if (root.get("debt") != null && !(root.get("debt") instanceof UnmodifiableConfig)) throw new IllegalArgumentException("Invalid company debt settings");
        var settings = policy == null ? root : new TomlParser().parse(new StringReader(policy));
        var payday = table(root, "payday"); var tax = table(settings, "tax"); var interest = table(settings, "interest");
        List<TaxPolicy.Bracket> brackets = new ArrayList<>();
        boolean useBrackets = Boolean.TRUE.equals(tax.get("useTaxBrackets"));
        if (tax.get("useTaxBrackets") != null && !(tax.get("useTaxBrackets") instanceof Boolean)) throw new IllegalArgumentException("Invalid Company tax switch");
        if (tax.get("brackets") instanceof List<?> list) for (Object raw : list) {
            if (!(raw instanceof UnmodifiableConfig c)) throw new IllegalArgumentException("Invalid Company tax bracket");
            brackets.add(new TaxPolicy.Bracket(Long.parseLong(value(c, "min").toString()), Long.parseLong(value(c, "max").toString()), new BigDecimal(value(c, "rate").toString())));
        }
        else if (useBrackets || tax.get("brackets") != null) throw new IllegalArgumentException("Missing Company tax brackets");
        List<FixedPayment> fixed = new ArrayList<>(); Object entries = settings.get("fixedPayments");
        if (entries != null) {
            if (!(entries instanceof List<?> list)) throw new IllegalArgumentException("Invalid Company fixedPayments");
            for (Object entry : list) { if (!(entry instanceof UnmodifiableConfig c)) throw new IllegalArgumentException("Invalid Company fixed payment"); fixed.add(new FixedPayment(value(c, "name").toString(), money(c, "amount"))); }
        }
        Object enabled = value(interest, "enabled"); if (!(enabled instanceof Boolean)) throw new IllegalArgumentException("Invalid Company interest enabled");
        try { return new CompanyConfig(money(company, "foundingCost"), Integer.parseInt(value(company, "confirmationSeconds").toString()),
                LocalTime.parse(value(payday, "time").toString()), resolveZone(value(payday, "timezone").toString()), new BigDecimal(value(tax, "incomePercent").toString()),
                (Boolean) enabled, new BigDecimal(value(interest, "percent").toString()), money(interest, "minimumBalance"), money(interest, "maximumPayment"), fixed,
                root.get("debt") instanceof UnmodifiableConfig debt ? money(debt, "maximum") : 1_000_000,
                root.get("debt") instanceof UnmodifiableConfig debt ? new BigDecimal(value(debt, "paydayFeePercent").toString()) : BigDecimal.valueOf(20),
                root.get("debt") instanceof UnmodifiableConfig debt ? new BigDecimal(value(debt, "ceoPenaltyPercent").toString()) : BigDecimal.valueOf(200), new TaxPolicy(useBrackets, brackets));
        } catch (DateTimeException invalid) { throw new IllegalArgumentException("Invalid Company PayDay time/timezone", invalid); }
    }
    public static ZoneId resolveZone(String value) {
        return value.equalsIgnoreCase("LOCAL") || value.equalsIgnoreCase("SYSTEM") ? ZoneId.systemDefault() : ZoneId.of(value);
    }
}
