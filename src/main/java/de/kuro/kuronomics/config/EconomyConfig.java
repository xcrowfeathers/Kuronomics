package de.kuro.kuronomics.config;

import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.google.gson.*;
import de.kuro.kuronomics.economy.Money;
import de.kuro.kuronomics.economy.tax.TaxPolicy;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.*;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.loading.FMLPaths;

public final class EconomyConfig {
    private static final String FILE_NAME = "economy_config.toml";
    public static Path directory() { return FMLPaths.CONFIGDIR.get().resolve("economy"); }
    public static Path path() { return directory().resolve(FILE_NAME); }
    private static final String DEFAULTS = """
            {
              "paydayTicks": 72000,
              "interest": {"enabled": true, "percent": 2.0, "minimumBalance": 500.0, "maximumPayment": 500000.0},
              "cities": {"blue": "Blue city", "purple": "Purple city", "red": "Red city", "green": "Green city"},
              "terminals": {"shopRange": 5, "auctionRange": 25, "realEstateRange": 5, "jobRange": 5},
              "auction": {
                "cycleMinutes": 60, "listingFee": 25.0, "saleTaxPercent": 5.0,
                "minDurationCycles": 1, "maxDurationCycles": 168, "paymentGraceCycles": 1, "maxActivePerPlayer": 10
              },
              "realEstate": {"saleTaxPercent": 5.0},
              "serverShop": {"dailySellLimit": 16000.0},
              "jobs": [],
              "taxes": {
                "fixed": [
                  {"name": "Health Insurance", "amount": -50},
                  {"name": "Universal Bonus", "amount": 100},
                  {"name": "Guild Tax", "amount": -30}
                ],
                "useTaxBrackets": true,
                "brackets": [
                  {"min": 0, "max": 4999, "rate": 0},
                  {"min": 5000, "max": 10000, "rate": 10},
                  {"min": 10001, "max": -1, "rate": 20}
                ]
              }
            }
            """;

    private final de.kuro.kuronomics.market.MarketConfig marketplace;
    private final int[] paydayDetails;
    public de.kuro.kuronomics.market.MarketConfig marketplace() { return marketplace; }
    public int[] paydayDetails() { return paydayDetails.clone(); }

    private final int paydayTicks, shopRange, auctionTerminalRange, realEstateTerminalRange, jobTerminalRange;
    private final int auctionCycleMinutes, auctionMinDurationCycles, auctionMaxDurationCycles;
    private final int auctionPaymentGraceCycles, auctionMaxActivePerPlayer;
    private final boolean interestEnabled;
    private final double interestPercent, interestMinimumBalance, interestMaximumPayment;
    private final double auctionListingFee, auctionSaleTaxPercent, realEstateSaleTaxPercent, serverShopDailySellLimit;
    private final List<String> cities;
    private final Map<String, Long> salaries;
    private final List<FixedPayment> fixedPayments;
    private final TaxPolicy tax;
    private final int historyMaximum, historyDays;
    public int historyMaximum() { return historyMaximum; }
    public long historyAgeMillis() { return historyDays * 86_400_000L; }

    public record FixedPayment(String name, long amount) {}

    private EconomyConfig(JsonObject root) {
        this(root, de.kuro.kuronomics.market.MarketConfig.parse(de.kuro.kuronomics.market.MarketConfig.DEFAULTS),
                de.kuro.kuronomics.economy.payday.PaydayReport.parse(de.kuro.kuronomics.economy.payday.PaydayReport.defaultConfig()));
    }
    private EconomyConfig(JsonObject root, de.kuro.kuronomics.market.MarketConfig marketplace, int[] paydayDetails) {
        this.marketplace = marketplace;
        this.paydayDetails = paydayDetails.clone();
        JsonObject history = root.has("financialHistory") ? object(root, "financialHistory") : historyDefaults();
        historyMaximum = integer(history, "maxCompletedRecords", 32, 1_000_000);
        historyDays = integer(history, "retentionDays", 1, 3650);
        paydayTicks = integer(root, "paydayTicks", 20, Integer.MAX_VALUE);
        JsonObject interest = object(root, "interest");
        interestEnabled = interest.get("enabled").getAsBoolean();
        interestPercent = decimal(interest, "percent", 0, 100);
        interestMinimumBalance = decimal(interest, "minimumBalance", 0, 1e12);
        interestMaximumPayment = decimal(interest, "maximumPayment", 0, 1e12);

        JsonObject cityNames = object(root, "cities");
        cities = List.of(cityNames.get("blue").getAsString(), cityNames.get("purple").getAsString(),
                cityNames.get("red").getAsString(), cityNames.get("green").getAsString());
        JsonObject terminals = object(root, "terminals");
        shopRange = integer(terminals, "shopRange", 1, 8);
        auctionTerminalRange = integer(terminals, "auctionRange", 1, 64);
        realEstateTerminalRange = integer(terminals, "realEstateRange", 1, 64);
        jobTerminalRange = terminals.has("jobRange") ? integer(terminals, "jobRange", 1, 64) : 5;

        JsonObject auction = object(root, "auction");
        auctionCycleMinutes = integer(auction, "cycleMinutes", 1, 10_080);
        auctionListingFee = decimal(auction, "listingFee", 0, 1e12);
        auctionSaleTaxPercent = decimal(auction, "saleTaxPercent", 0, 100);
        auctionMinDurationCycles = integer(auction, "minDurationCycles", 1, 10_000);
        auctionMaxDurationCycles = integer(auction, "maxDurationCycles", 1, 10_000);
        if (auctionMaxDurationCycles < auctionMinDurationCycles)
            throw new IllegalArgumentException("auction.maxDurationCycles must be at least minDurationCycles");
        auctionPaymentGraceCycles = integer(auction, "paymentGraceCycles", 1, 10_000);
        auctionMaxActivePerPlayer = integer(auction, "maxActivePerPlayer", 1, 10_000);
        realEstateSaleTaxPercent = decimal(object(root, "realEstate"), "saleTaxPercent", 0, 100);
        serverShopDailySellLimit = decimal(object(root, "serverShop"), "dailySellLimit", 0, 1e12);

        Map<String, Long> parsedSalaries = new LinkedHashMap<>();
        for (JsonElement raw : root.getAsJsonArray("jobs")) {
            JsonObject job = raw.getAsJsonObject();
            String name = job.get("name").getAsString().toLowerCase(Locale.ROOT);
            long salary = Money.parse(job.get("salary").getAsString());
            if (name.isBlank() || salary < 0 || parsedSalaries.putIfAbsent(name, salary) != null)
                throw new IllegalArgumentException("Invalid or duplicate job: " + name);
        }
        salaries = Map.copyOf(parsedSalaries);

        JsonObject taxes = object(root, "taxes");
        List<FixedPayment> parsedPayments = new ArrayList<>();
        for (JsonElement raw : taxes.getAsJsonArray("fixed")) {
            JsonObject payment = raw.getAsJsonObject();
            String name = payment.get("name").getAsString();
            if (name.isBlank()) throw new IllegalArgumentException("Fixed payment name cannot be blank");
            parsedPayments.add(new FixedPayment(name, Money.parse(payment.get("amount").getAsString())));
        }
        fixedPayments = List.copyOf(parsedPayments);
        List<TaxPolicy.Bracket> brackets = new ArrayList<>();
        for (JsonElement raw : taxes.getAsJsonArray("brackets")) {
            JsonObject bracket = raw.getAsJsonObject();
            brackets.add(new TaxPolicy.Bracket(bracket.get("min").getAsLong(), bracket.get("max").getAsLong(),
                    bracket.get("rate").getAsBigDecimal()));
        }
        tax = new TaxPolicy(taxes.get("useTaxBrackets").getAsBoolean(), brackets);
    }

    public int paydayTicks() { return paydayTicks; }
    public int shopRange() { return shopRange; }
    public int auctionTerminalRange() { return auctionTerminalRange; }
    public int realEstateTerminalRange() { return realEstateTerminalRange; }
    public int jobTerminalRange() { return jobTerminalRange; }
    public int auctionCycleMinutes() { return auctionCycleMinutes; }
    public int auctionMinDurationCycles() { return auctionMinDurationCycles; }
    public int auctionMaxDurationCycles() { return auctionMaxDurationCycles; }
    public int auctionPaymentGraceCycles() { return auctionPaymentGraceCycles; }
    public int auctionMaxActivePerPlayer() { return auctionMaxActivePerPlayer; }
    public boolean interestEnabled() { return interestEnabled; }
    public double interestPercent() { return interestPercent; }
    public double interestMinimumBalance() { return interestMinimumBalance; }
    public double interestMaximumPayment() { return interestMaximumPayment; }
    public double auctionListingFee() { return auctionListingFee; }
    public double auctionSaleTaxPercent() { return auctionSaleTaxPercent; }
    public double realEstateSaleTaxPercent() { return realEstateSaleTaxPercent; }
    public double serverShopDailySellLimit() { return serverShopDailySellLimit; }
    public String city(int color) { return cities.get(color); }
    public long salary(String job) { return salaries.getOrDefault(job.toLowerCase(Locale.ROOT), 0L); }
    public Set<String> jobs() { return salaries.keySet(); }
    public List<FixedPayment> fixedPayments() { return fixedPayments; }
    public TaxPolicy tax() { return tax; }

    public static EconomyConfig load(MinecraftServer server) {
        return load();
    }
    public static EconomyConfig load() {
        Path file = path();
        try {
            boolean fresh = !Files.exists(file);
            String text = fresh ? formatToml(JsonParser.parseString(DEFAULTS).getAsJsonObject()) : Files.readString(file);
            EconomyConfig config = new EconomyConfig(readToml(text), de.kuro.kuronomics.market.MarketConfig.parse(text),
                    de.kuro.kuronomics.economy.payday.PaydayReport.parse(text));
            if (fresh) {
                Files.createDirectories(directory());
                Files.writeString(file, text, StandardOpenOption.CREATE_NEW);
            }
            return config;
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot load " + file + "; previous configuration retained on reload", e);
        }
    }

    private static JsonObject readToml(Path file) throws IOException {
        return readToml(Files.readString(file));
    }
    private static JsonObject readToml(String text) {
        var source = new TomlParser().parse(text);
        JsonObject root = new JsonObject();
        JsonObject payday = new JsonObject();
        copy(payday, table(source, "payday"), "ticks");
        root.add("paydayTicks", payday.get("ticks"));
        for (String section : List.of("interest", "cities", "terminals", "auction", "realEstate", "serverShop")) {
            JsonObject values = new JsonObject();
            JsonObject expected = JsonParser.parseString(DEFAULTS).getAsJsonObject().getAsJsonObject(section);
            UnmodifiableConfig old = table(source, section);
            for (String key : expected.keySet()) {
                if (section.equals("terminals") && key.equals("jobRange") && old.get(key) == null)
                    values.add(key, expected.get(key).deepCopy());
                else copy(values, old, key);
            }
            root.add(section, values);
        }
        // New configs keep jobs in economy_jobs.toml. Still read the old salary table if it's present
        // so existing employment can be migrated.
        root.add("jobs", source.get("jobs") == null ? new JsonArray()
                : readTables(source.get("jobs"), "jobs", List.of("name", "salary")));
        UnmodifiableConfig taxes = table(source, "taxes");
        JsonObject tax = new JsonObject();
        copy(tax, taxes, "useTaxBrackets");
        tax.add("fixed", readTables(taxes.get("fixed"), "taxes.fixed", List.of("name", "amount")));
        tax.add("brackets", readTables(taxes.get("brackets"), "taxes.brackets", List.of("min", "max", "rate")));
        root.add("taxes", tax);
        JsonObject history = historyDefaults();
        if (source.get("financialHistory") != null) {
            var section = table(source, "financialHistory");
            for (String key : history.keySet()) if (section.get(key) != null) copy(history, section, key);
        }
        root.add("financialHistory", history);
        return root;
    }

    private static UnmodifiableConfig table(UnmodifiableConfig source, String name) {
        Object value = source.get(name);
        if (value instanceof UnmodifiableConfig table) return table;
        throw new IllegalArgumentException("Missing or invalid config section: " + name);
    }

    private static JsonArray readTables(Object value, String name, List<String> keys) {
        if (!(value instanceof List<?> tables)) throw new IllegalArgumentException("Missing or invalid config list: " + name);
        JsonArray result = new JsonArray();
        for (Object entry : tables) {
            if (!(entry instanceof UnmodifiableConfig table)) throw new IllegalArgumentException("Invalid entry in " + name);
            JsonObject object = new JsonObject();
            for (String key : keys) copy(object, table, key);
            result.add(object);
        }
        return result;
    }

    private static void copy(JsonObject target, UnmodifiableConfig source, String key) {
        Object value = source.get(key);
        if (value == null) throw new IllegalArgumentException("Missing config value: " + key);
        put(target, key, value);
    }

    private static String formatToml(JsonObject root) {
        StringBuilder out = new StringBuilder("# Economy configuration\n");
        out.append("# Money values are in dollars. Reload with /reloadec.\n\n");
        section(out, "financialHistory", "No touchie unless you know what you are doing.");
        fields(out, root.has("financialHistory") ? object(root, "financialHistory") : historyDefaults(), "maxCompletedRecords", "retentionDays");
        section(out, "payday", "Payday interval in ticks. 20 ticks = 1 second");
        value(out, "ticks", root.get("paydayTicks"));
        out.append('\n');
        section(out, "interest", "Interest received on payday.");
        fields(out, object(root, "interest"), "enabled", "percent", "minimumBalance", "maximumPayment");
        section(out, "cities", "City names on ID cards.");
        fields(out, object(root, "cities"), "blue", "purple", "red", "green");
        section(out, "terminals", "Maximum range in blocks for each shop/terminal.");
        fields(out, object(root, "terminals"), "shopRange", "auctionRange", "realEstateRange", "jobRange");
        section(out, "auction", "Auction related stuff.");
        fields(out, object(root, "auction"), "cycleMinutes", "listingFee", "saleTaxPercent",
                "minDurationCycles", "maxDurationCycles", "paymentGraceCycles", "maxActivePerPlayer");
        section(out, "realEstate", "Tax for real estate sales.");
        fields(out, object(root, "realEstate"), "saleTaxPercent");
        section(out, "serverShop", "Daily sale limit for the global Server Shop.");
        fields(out, object(root, "serverShop"), "dailySellLimit");

        tables(out, "jobs", root.getAsJsonArray("jobs"), "name", "salary");
        section(out, "taxes", "Whether tax brackets are used");
        value(out, "useTaxBrackets", object(root, "taxes").get("useTaxBrackets"));
        out.append("\n# Fixed payments for everyone at each payday. May be negative or positive.\n");
        tables(out, "taxes.fixed", object(root, "taxes").getAsJsonArray("fixed"), "name", "amount");
        out.append("# Tax brackets; whole dollar values; max/infinite = -1\n");
        tables(out, "taxes.brackets", object(root, "taxes").getAsJsonArray("brackets"), "min", "max", "rate");
        out.append("\n").append(de.kuro.kuronomics.market.MarketConfig.DEFAULTS);
        out.append("\n").append(de.kuro.kuronomics.economy.payday.PaydayReport.defaultConfig());
        return out.toString();
    }

    private static void section(StringBuilder out, String name, String comment) {
        out.append("# ").append(comment).append("\n[").append(name).append("]\n");
    }

    private static void fields(StringBuilder out, JsonObject values, String... keys) {
        for (String key : keys) value(out, key, values.get(key));
        out.append('\n');
    }

    private static void tables(StringBuilder out, String name, JsonArray entries, String... keys) {
        for (JsonElement entry : entries) {
            out.append("[[").append(name).append("]]\n");
            JsonObject values = entry.getAsJsonObject();
            for (String key : keys) value(out, key, values.get(key));
            out.append('\n');
        }
    }

    private static void value(StringBuilder out, String name, JsonElement value) {
        out.append(name).append(" = ").append(value).append('\n');
    }

    private static JsonObject object(JsonObject parent, String name) { return parent.getAsJsonObject(name); }
    private static JsonObject historyDefaults() {
        JsonObject value = new JsonObject(); value.addProperty("maxCompletedRecords", 10000); value.addProperty("retentionDays", 30); return value;
    }

    private static int integer(JsonObject parent, String name, int minimum, int maximum) {
        int value = parent.get(name).getAsBigDecimal().intValueExact();
        if (value < minimum || value > maximum) throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        return value;
    }

    private static double decimal(JsonObject parent, String name, double minimum, double maximum) {
        double value = parent.get(name).getAsDouble();
        if (!Double.isFinite(value) || value < minimum || value > maximum)
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        return value;
    }

    private static void put(JsonObject target, String key, Object value) {
        if (value instanceof Number number) target.addProperty(key, number);
        else if (value instanceof Boolean bool) target.addProperty(key, bool);
        else if (value instanceof String string) target.addProperty(key, string);
        else if (value != null) throw new IllegalArgumentException("Unsupported config value for " + key);
    }
}
