package de.kuro.kuronomics.servershop;

import java.util.*;
import net.minecraft.world.item.ItemStack;

public record ServerShop(UUID id, String name, Mode mode, List<Offer> offers, Rotation rotation, long revision) {
    /** This cap protects serialization. The paged editor doesn't need a small fixed GUI limit. */
    public static final int MAX_OFFERS = 10_000;

    public ServerShop {
        id = Objects.requireNonNull(id, "id");
        name = validateName(name);
        mode = Objects.requireNonNull(mode, "mode");
        rotation = Objects.requireNonNull(rotation, "rotation");
        if (revision < 0) throw new IllegalArgumentException("Server shop revision cannot be negative");
        if (offers == null || offers.size() > MAX_OFFERS)
            throw new IllegalArgumentException("A server shop may contain at most " + MAX_OFFERS + " offers");
        Set<UUID> offerIds = new HashSet<>();
        for (Offer offer : offers) {
            Objects.requireNonNull(offer, "offer");
            if (!offerIds.add(offer.id())) throw new IllegalArgumentException("Duplicate server shop offer ID");
            if (mode != Mode.PLAYER_SELLS && offer.playerBuyPrice() <= 0)
                throw new IllegalArgumentException("Player buy prices must be greater than zero");
            if (mode != Mode.PLAYER_BUYS && offer.playerSellPrice() <= 0)
                throw new IllegalArgumentException("Player sell prices must be greater than zero");
        }
        offers = List.copyOf(offers);
    }

    public static String validateName(String value) {
        String name = java.text.Normalizer.normalize(Objects.requireNonNull(value, "name"), java.text.Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ");
        if (name.isEmpty() || name.length() > 48 || name.codePoints().anyMatch(c -> Character.isISOControl(c) || c == '\u00a7'))
            throw new IllegalArgumentException("Shop names must contain 1-48 printable characters.");
        return name;
    }

    public static String nameKey(String value) { return validateName(value).toLowerCase(Locale.ROOT); }

    public enum Mode { PLAYER_BUYS, PLAYER_SELLS, BOTH }
    public enum StockMode { INFINITE, SHARED }

    public record Stock(StockMode mode, long available) {
        public Stock {
            mode = Objects.requireNonNull(mode, "mode");
            if (available < 0) throw new IllegalArgumentException("Server shop stock cannot be negative");
            if (mode == StockMode.INFINITE && available != 0)
                throw new IllegalArgumentException("Infinite stock must not store a quantity");
        }
        public static Stock infinite() { return new Stock(StockMode.INFINITE, 0); }
        public static Stock shared(long available) { return new Stock(StockMode.SHARED, available); }
    }

    public record Offer(UUID id, ItemStack item, int quantity, long playerBuyPrice, long playerSellPrice, Stock stock,
                        boolean rotationPool) {
        public Offer(UUID id, ItemStack item, int quantity, long playerBuyPrice, long playerSellPrice, Stock stock) {
            this(id, item, quantity, playerBuyPrice, playerSellPrice, stock, false);
        }
        public Offer {
            id = Objects.requireNonNull(id, "id");
            if (item == null || item.isEmpty()) throw new IllegalArgumentException("A server shop offer requires an item");
            if (quantity <= 0 || quantity > 1_000_000)
                throw new IllegalArgumentException("Offer quantity must be between 1 and 1,000,000");
            if (playerBuyPrice < 0 || playerSellPrice < 0)
                throw new IllegalArgumentException("Server shop prices cannot be negative");
            stock = Objects.requireNonNull(stock, "stock");
            item = item.copyWithCount(1);
        }
        @Override public ItemStack item() { return item.copy(); }
    }

    public record Rotation(boolean enabled, long durationMillis, int visibleOffers, long nextRotationAt, long sequence) {
        public Rotation(boolean enabled, long durationMillis, int visibleOffers, long nextRotationAt) {
            this(enabled, durationMillis, visibleOffers, nextRotationAt, 0);
        }
        public Rotation {
            if (durationMillis < 0 || visibleOffers < 0 || nextRotationAt < 0 || sequence < 0)
                throw new IllegalArgumentException("Rotation settings cannot be negative");
            if (visibleOffers > MAX_OFFERS)
                throw new IllegalArgumentException("Rotation cannot expose more than " + MAX_OFFERS + " offers");
            if (enabled && (durationMillis < 1_000 || visibleOffers == 0))
                throw new IllegalArgumentException("Enabled rotation requires an interval and visible offer count");
        }
        public static Rotation disabled() { return new Rotation(false, 0, 0, 0, 0); }
    }
}
