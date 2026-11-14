package de.kuro.kuronomics.economy.payday;

import de.kuro.kuronomics.company.Company;
import de.kuro.kuronomics.economy.EconomicOwner;
import java.util.*;
import java.math.BigInteger;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.core.UnmodifiableConfig;

/** Only build the report here. Keep buckets and details capped; don't change the economy. */
public final class PaydayReport {
    public enum Group {
        SHOP_SALES("shopSales", "Shop Sales", 0), MARKET_SALES("marketSales", "Marketplace sales", 0),
        AUCTION_SALES("auctionSales", "Auction sales", 10), PROPERTY_SALES("propertySales", "Property sales", 10),
        SALES_TAX("salesTax", "Sales tax", 10), CONTRACTS("contracts", "Contracts", 10),
        BANK_CONTRACTS("bankContracts", "Bank contracts", 10), RENTS("rents", "Rent", 10),
        SALARIES("salaries", "Salaries", 10), FIXED("fixedPayments", "Fees and bonuses", 10),
        TAXES("taxes", "Taxes", 10), INTEREST("interest", "Interest", 10),
        PAYMENTS("payments", "Payments", 10), AUCTION_PAYMENTS("auctionPayments", "Auction payments", 10), OTHER("other", "Other", 10);
        public final String key, label; final int defaultLimit;
        Group(String key, String label, int limit) { this.key = key; this.label = label; defaultLimit = limit; }
    }
    public record Entry(PaydayService.Item.Kind kind, String label, Long amount, EconomicOwner party) {}
    private static final int KINDS = PaydayService.Item.Kind.values().length;
    private static volatile int[] settings = defaults();
    private final int[] limits = settings;
    private final Bucket[] buckets = new Bucket[Group.values().length * KINDS];
    private final List<Bucket> ordered = new ArrayList<>();
    private static final class Bucket {
        final Group group; final PaydayService.Item.Kind kind;
        Bucket(Group group, PaydayService.Item.Kind kind) { this.group = group; this.kind = kind; }
        final List<Entry> details = new ArrayList<>(); long count, total; BigInteger wideTotal; boolean monetary;
        void amount(long value) {
            monetary = true;
            if (wideTotal != null) wideTotal = wideTotal.add(BigInteger.valueOf(value));
            else try { total = Math.addExact(total, value); }
            catch (ArithmeticException overflow) { wideTotal = BigInteger.valueOf(total).add(BigInteger.valueOf(value)); }
        }
    }
    private static int[] defaults() { return Arrays.stream(Group.values()).mapToInt(g -> g.defaultLimit).toArray(); }
    public static String defaultConfig() {
        StringBuilder text = new StringBuilder("# Display limits of payday categories.\n# Limits are for income, expenses, information..\n# 0 always summarizes\n[paydayDisplay]\n");
        for (Group g : Group.values()) if (g != Group.SHOP_SALES && g != Group.MARKET_SALES) text.append(g.key).append(" = ").append(g.defaultLimit).append('\n');
        return text.toString();
    }
    public static int[] parse(String text) {
        UnmodifiableConfig root = new TomlParser().parse(text); Object raw = root.get("paydayDisplay");
        if (!(raw instanceof UnmodifiableConfig details)) throw new IllegalArgumentException("Missing payday display details");
        int[] result = defaults();
        for (Group g : Group.values()) if (details.contains(g.key)) {
            int value;
            try { value = new java.math.BigDecimal(details.get(g.key).toString()).intValueExact(); }
            catch (ArithmeticException | NumberFormatException e) { throw new IllegalArgumentException("Invalid payday detail limit: " + g.key, e); }
            if (value < 0 || value > 50) throw new IllegalArgumentException("Payday detail limits must be between 0 and 50: " + g.key);
            result[g.ordinal()] = value;
        }
        result[Group.SHOP_SALES.ordinal()] = 0;
        result[Group.MARKET_SALES.ordinal()] = 0;
        return result;
    }
    public static void reload() {
        configure(de.kuro.kuronomics.config.EconomyConfig.load().paydayDetails());
    }
    public static void configure(int[] limits) { settings = limits.clone(); }
    public void add(Group group, Entry entry) {
        add(group, entry.kind(), entry.label(), entry.amount(), entry.party());
    }
    public void add(Group group, PaydayService.Item.Kind kind, String label, Long amount, EconomicOwner party) {
        add(group, kind, label, amount == null ? 0 : amount.longValue(), amount != null, party);
    }
    public void add(Group group, PaydayService.Item.Kind kind, String label, long amount, EconomicOwner party) {
        add(group, kind, label, amount, true, party);
    }
    private void add(Group group, PaydayService.Item.Kind kind, String label, long amount, boolean monetary, EconomicOwner party) {
        int key = group.ordinal() * KINDS + kind.ordinal(); Bucket bucket = buckets[key];
        if (bucket == null) { bucket = new Bucket(group, kind); buckets[key] = bucket; ordered.add(bucket); }
        int limit = limits[group.ordinal()];
        if (++bucket.count <= limit) bucket.details.add(new Entry(kind, label, monetary ? amount : null, party));
        else if (bucket.count == limit + 1L) bucket.details.clear();
        if (monetary) bucket.amount(amount);
    }
    public void add(Group group, Company.Line line) {
        add(group, PaydayService.Item.Kind.valueOf(line.kind()), line.label(), line.amount(), line.party());
    }
    public void add(Company.Line line) { add(category(line.label()), line); }
    public List<Entry> entries() {
        List<Entry> result = new ArrayList<>();
        ordered.forEach(bucket -> {
            if (bucket.count <= limits[bucket.group.ordinal()]) { result.addAll(bucket.details); return; }
            String label = bucket.group.label + (bucket.kind == PaydayService.Item.Kind.INFO && bucket.group != Group.SALES_TAX ? " (gross)" : "")
                    + (bucket.kind == PaydayService.Item.Kind.WARNING ? " notices" : "")
                    + (bucket.group == Group.SHOP_SALES || bucket.group == Group.MARKET_SALES ? "" : " (" + bucket.count + ")");
            if (!bucket.monetary) result.add(new Entry(bucket.kind, label, null, null));
            else if (bucket.wideTotal == null) result.add(new Entry(bucket.kind, label, bucket.total, null));
            else {
                // Report totals can overflow a long even when each balance and payment fits.
                // Split that rare case so formatting can't stop an otherwise valid payday.
                BigInteger remaining = bucket.wideTotal, maximum = BigInteger.valueOf(Long.MAX_VALUE);
                while (remaining.signum() > 0) { long part = remaining.min(maximum).longValueExact(); result.add(new Entry(bucket.kind, label, part, null)); remaining = remaining.subtract(BigInteger.valueOf(part)); }
            }
        });
        return List.copyOf(result);
    }
    public List<Company.Line> companyLines() {
        return entries().stream().map(e -> new Company.Line(e.kind().name(), e.label(), e.amount() == null ? 0 : e.amount(), e.party())).toList();
    }
    /** Classify old untyped labels once while already looping over that account's entries. */
    public static Group category(String label) {
        String text = label.toLowerCase(Locale.ROOT);
        if (text.startsWith("shop sale")) return Group.SHOP_SALES;
        if (text.startsWith("marketplace sale")) return Group.MARKET_SALES;
        if (text.startsWith("bank loan") || text.startsWith("bank contract") || text.startsWith("state contract")) return Group.BANK_CONTRACTS;
        if (text.startsWith("contract") || text.startsWith("company contract") || text.startsWith("incoming contract")) return Group.CONTRACTS;
        if (text.startsWith("rent ") || text.equals("rent")) return Group.RENTS;
        if (text.startsWith("company salary") || text.startsWith("salary") || text.startsWith("salaries") || text.startsWith("unpaid salary")) return Group.SALARIES;
        if (text.equals("interest")) return Group.INTEREST;
        if (text.startsWith("income tax") || text.equals("tax") || text.equals("debt fee")) return Group.TAXES;
        int from = label.lastIndexOf(" from ");
        if (from >= 0 && label.length() - from == 42) try { UUID.fromString(label.substring(from + 6)); return Group.CONTRACTS; } catch (IllegalArgumentException ignored) {}
        return Group.PAYMENTS;
    }
    public static List<Company.Line> shopSales(List<Company.Line> lines) {
        var sales = new PaydayReport(); List<Company.Line> result = new ArrayList<>();
        for (var line : lines) if (category(line.label()) == Group.SHOP_SALES) sales.add(Group.SHOP_SALES, line); else result.add(line);
        result.addAll(sales.companyLines()); return result;
    }
}
