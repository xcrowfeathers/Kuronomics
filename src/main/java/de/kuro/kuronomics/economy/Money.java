package de.kuro.kuronomics.economy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;

/** Money uses signed cents, so debt is valid. Conversions and arithmetic throw on overflow. */
public final class Money {
    private static final class Display {
        final NumberFormat format = NumberFormat.getNumberInstance(Locale.GERMANY);
        final java.util.Map<Long, String> amounts = cache(), prices = cache();
        private static java.util.Map<Long, String> cache() {
            return new java.util.LinkedHashMap<>() {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<Long, String> entry) { return size() > 256; }
            };
        }
        String render(long cents, boolean price) {
            var cache = price ? prices : amounts; String previous = cache.get(cents); if (previous != null) return previous;
            format.setMinimumFractionDigits(price || cents % 100 != 0 ? 2 : 0); format.setMaximumFractionDigits(2);
            String value = "$" + format.format(BigDecimal.valueOf(cents, 2)); cache.put(cents, value); return value;
        }
    }
    private static final ThreadLocal<Display> DISPLAY = ThreadLocal.withInitial(Display::new);
    private Money() {}

    public static long parse(String dollars) {
        return new BigDecimal(dollars).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }
    public static long parseDisplay(String text) {
        String value = text.trim().replaceFirst("^[$€]\\s*", "");
        if (value.isEmpty()) return 0;
        if (value.contains(",")) value = value.replace(".", "").replace(',', '.');
        return parse(value);
    }

    public static long legacy(double dollars) {
        if (!Double.isFinite(dollars)) throw new IllegalArgumentException("Non-finite monetary value");
        return parse(BigDecimal.valueOf(dollars).toPlainString());
    }

    public static long percent(long cents, BigDecimal rate) {
        if (rate.signum() < 0) throw new IllegalArgumentException("Negative percentage");
        return BigDecimal.valueOf(cents).multiply(rate).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).longValueExact();
    }

    public static long positive(long cents) {
        if (cents <= 0) throw new IllegalArgumentException("Amount must be positive");
        return cents;
    }

    public static String format(long cents) {
        return DISPLAY.get().render(cents, false);
    }

    public static String difference(long closing,long opening){
        BigDecimal delta=BigDecimal.valueOf(closing,2).subtract(BigDecimal.valueOf(opening,2));var formatter=DISPLAY.get().format;
        formatter.setMinimumFractionDigits(2);formatter.setMaximumFractionDigits(2);return (delta.signum()>0?"+":"")+"$"+formatter.format(delta);
    }
    public static String formatPrice(long cents) {
        return DISPLAY.get().render(cents, true);
    }
}
