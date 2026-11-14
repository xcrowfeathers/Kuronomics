package de.kuro.kuronomics.servershop;

import de.kuro.kuronomics.Kuronomics;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** Only {@link ServerShopService} should change this store. Mutators stay package-private. */
public final class ServerShopStore extends de.kuro.kuronomics.economy.data.FinancialSavedData {
    private static final String DATA_NAME = Kuronomics.MODID + "_server_shops";
    private final Map<UUID, ServerShop> shops = new LinkedHashMap<>();
    private final Map<String, UUID> names = new HashMap<>();
    private final Map<UUID, EditorState> editors = new HashMap<>();
    private final Map<UUID, Trade> trades = new LinkedHashMap<>();
    private final Map<UUID, CompletedTrade> completedTrades = new LinkedHashMap<>();
    private final de.kuro.kuronomics.economy.data.FinancialHistory historyRetention = new de.kuro.kuronomics.economy.data.FinancialHistory();
    public int pruneHistory(int maximum, long ageMillis, long now) {
        int count = historyRetention.prune(completedTrades, maximum, ageMillis, now, (id, value) -> true,
                (id, value) -> historyRetention.retire(id, value.fingerprint()));
        if (count > 0) setDirty(); return count;
    }
    private final Map<UUID, DailySales> dailySales = new HashMap<>();
    private long nextRotationDeadline = -1;

    record EditorState(UUID shopId, long revision) {}
    enum TradeType { BUY, SELL }
    record DailySales(long epochDay, long earned) {
        DailySales { if (epochDay < 0 || earned < 0) throw new IllegalArgumentException("Invalid daily server-shop sales"); }
    }
    record Trade(UUID reference, String fingerprint, TradeType type, UUID playerId, UUID shopId, UUID offerId,
                 ItemStack item, int quantity, long amount, boolean sharedStock, boolean stockApplied,
                 boolean dailyLimitApplied, boolean settled, long createdAt) {
        Trade {
            Objects.requireNonNull(reference); Objects.requireNonNull(fingerprint); Objects.requireNonNull(type);
            Objects.requireNonNull(playerId); Objects.requireNonNull(shopId); Objects.requireNonNull(offerId);
            if (item == null || item.isEmpty() || quantity <= 0 || amount <= 0 || createdAt < 0)
                throw new IllegalArgumentException("Invalid server shop transaction journal entry");
            item = item.copyWithCount(1);
        }
        @Override public ItemStack item() { return item.copy(); }
        Trade withStockApplied() { return new Trade(reference, fingerprint, type, playerId, shopId, offerId, item,
                quantity, amount, sharedStock, true, dailyLimitApplied, settled, createdAt); }
        Trade withDailyLimitApplied() { return new Trade(reference, fingerprint, type, playerId, shopId, offerId, item,
                quantity, amount, sharedStock, stockApplied, true, settled, createdAt); }
        Trade withSettled() { return new Trade(reference, fingerprint, type, playerId, shopId, offerId, item,
                quantity, amount, sharedStock, stockApplied, dailyLimitApplied, true, createdAt); }
    }
    record CompletedTrade(UUID reference, String fingerprint, TradeType type, UUID playerId, UUID shopId,
                          UUID offerId, String itemName, int quantity, long amount) {
        CompletedTrade {
            Objects.requireNonNull(reference); Objects.requireNonNull(fingerprint); Objects.requireNonNull(type);
            Objects.requireNonNull(playerId); Objects.requireNonNull(shopId); Objects.requireNonNull(offerId); Objects.requireNonNull(itemName);
            if (itemName.isBlank() || quantity <= 0 || amount <= 0) throw new IllegalArgumentException("Invalid completed server shop transaction");
        }
        static CompletedTrade of(Trade trade) {
            return new CompletedTrade(trade.reference(), trade.fingerprint(), trade.type(), trade.playerId(), trade.shopId(),
                    trade.offerId(), trade.item().getHoverName().getString(), trade.quantity(), trade.amount());
        }
    }

    public static ServerShopStore get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Server shops accessed off server thread");
        return server.overworld().getDataStorage().computeIfAbsent(
                new Factory<>(ServerShopStore::new, ServerShopStore::load), DATA_NAME);
    }

    public static void validate(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DATA_NAME + ".dat");
        if (!Files.exists(file)) return;
        try { load(NbtIo.readCompressed(file, NbtAccounter.create(64 * 1024 * 1024)).getCompound("data"), server.registryAccess()); }
        catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Server shop data is unreadable; restore a backup before starting this world", error);
        }
    }

    ServerShop byId(UUID id) { return shops.get(id); }
    ServerShop byName(String name) { UUID id = names.get(ServerShop.nameKey(name)); return id == null ? null : shops.get(id); }
    Collection<ServerShop> all() { return List.copyOf(shops.values()); }
    long nextRotationDeadline() {
        if (nextRotationDeadline < 0) nextRotationDeadline = shops.values().stream().map(ServerShop::rotation)
                .filter(ServerShop.Rotation::enabled).mapToLong(ServerShop.Rotation::nextRotationAt).min().orElse(Long.MAX_VALUE);
        return nextRotationDeadline;
    }
    Trade trade(UUID reference) { return trades.get(reference); }
    CompletedTrade completedTrade(UUID reference) { historyRetention.requireUnretired(reference); return completedTrades.get(reference); }
    Collection<Trade> trades() { return List.copyOf(trades.values()); }
    long dailySales(UUID player, long epochDay) {
        DailySales value = dailySales.get(player);
        return value != null && value.epochDay() == epochDay ? value.earned() : 0;
    }
    void recordDailySale(UUID player, long epochDay, long amount) {
        if (amount <= 0) throw new IllegalArgumentException("Daily sale amount must be positive");
        long current = dailySales(player, epochDay);
        dailySales.put(player, new DailySales(epochDay, Math.addExact(current, amount))); setDirty();
    }

    void create(ServerShop shop) {
        if (shops.containsKey(shop.id())) throw new IllegalArgumentException("A server shop with that ID already exists");
        String key = ServerShop.nameKey(shop.name());
        if (names.containsKey(key)) throw new IllegalArgumentException("A server shop named '" + shop.name() + "' already exists.");
        shops.put(shop.id(), shop); names.put(key, shop.id()); nextRotationDeadline = -1; setDirty();
    }

    void replace(ServerShop shop) {
        ServerShop previous = shops.get(shop.id());
        if (previous == null) throw new IllegalArgumentException("This server shop no longer exists.");
        String key = ServerShop.nameKey(shop.name()); UUID named = names.get(key);
        if (named != null && !named.equals(shop.id()))
            throw new IllegalArgumentException("A server shop named '" + shop.name() + "' already exists.");
        names.remove(ServerShop.nameKey(previous.name())); names.put(key, shop.id()); shops.put(shop.id(), shop); nextRotationDeadline = -1; setDirty();
    }

    ServerShop delete(UUID id) {
        ServerShop removed = shops.remove(id);
        if (removed == null) return null;
        names.remove(ServerShop.nameKey(removed.name()));
        nextRotationDeadline = -1;
        editors.entrySet().removeIf(entry -> entry.getValue().shopId().equals(id));
        setDirty(); return removed;
    }

    void edit(UUID administrator, ServerShop shop) { editors.put(administrator, new EditorState(shop.id(), shop.revision())); }
    EditorState editor(UUID administrator) { return editors.get(administrator); }
    void advanceEditors(UUID shopId, long previousRevision, long revision) {
        editors.replaceAll((administrator, editor) -> editor.shopId().equals(shopId) && editor.revision() == previousRevision
                ? new EditorState(shopId, revision) : editor);
    }
    void clearEditor(UUID administrator) { editors.remove(administrator); }
    void prepare(Trade trade) {
        historyRetention.requireUnretired(trade.reference());
        Trade existing = trades.get(trade.reference());
        if (existing != null) {
            if (!existing.fingerprint().equals(trade.fingerprint()))
                throw new IllegalArgumentException("Transaction reference was already used for another server shop operation");
            return;
        }
        trades.put(trade.reference(), trade); setDirty();
    }
    void trade(Trade trade) {
        if (!trades.containsKey(trade.reference())) throw new IllegalArgumentException("Unknown server shop transaction");
        trades.put(trade.reference(), trade); setDirty();
    }
    void completeTrade(UUID reference) {
        Trade trade = trades.get(reference);
        if (trade == null) return;
        if (!trade.settled()) throw new IllegalStateException("Cannot remove an unsettled server shop transaction");
        CompletedTrade completed = CompletedTrade.of(trade);
        historyRetention.record(reference); CompletedTrade old = completedTrades.putIfAbsent(reference, completed);
        if (old != null && !old.equals(completed)) throw new IllegalStateException("Completed server shop transaction conflicts with its tombstone");
        trades.remove(reference); setDirty();
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", 5); historyRetention.save(tag, "historyRetention"); ListTag list = new ListTag();
        for (ServerShop shop : shops.values()) {
            CompoundTag value = new CompoundTag(); value.putUUID("id", shop.id()); value.putString("name", shop.name());
            value.putString("mode", shop.mode().name()); value.putLong("revision", shop.revision());
            ListTag offers = new ListTag();
            for (ServerShop.Offer offer : shop.offers()) {
                CompoundTag entry = new CompoundTag(); entry.putUUID("id", offer.id());
                entry.put("item", offer.item().save(registries)); entry.putInt("quantity", offer.quantity());
                entry.putLong("playerBuyPrice", offer.playerBuyPrice()); entry.putLong("playerSellPrice", offer.playerSellPrice());
                entry.putString("stockMode", offer.stock().mode().name()); entry.putLong("stock", offer.stock().available());
                entry.putBoolean("rotationPool", offer.rotationPool());
                offers.add(entry);
            }
            value.put("offers", offers); CompoundTag rotation = new CompoundTag();
            rotation.putBoolean("enabled", shop.rotation().enabled()); rotation.putLong("durationMillis", shop.rotation().durationMillis());
            rotation.putInt("visibleOffers", shop.rotation().visibleOffers()); rotation.putLong("nextRotationAt", shop.rotation().nextRotationAt());
            rotation.putLong("sequence", shop.rotation().sequence());
            value.put("rotation", rotation); list.add(value);
        }
        tag.put("shops", list);
        ListTag transactionTags = new ListTag();
        for (Trade trade : trades.values()) {
            CompoundTag value = new CompoundTag(); value.putUUID("reference", trade.reference()); value.putString("fingerprint", trade.fingerprint());
            value.putString("type", trade.type().name()); value.putUUID("player", trade.playerId()); value.putUUID("shop", trade.shopId()); value.putUUID("offer", trade.offerId());
            value.put("item", trade.item().save(registries)); value.putInt("quantity", trade.quantity()); value.putLong("amount", trade.amount());
            value.putBoolean("sharedStock", trade.sharedStock()); value.putBoolean("stockApplied", trade.stockApplied());
            value.putBoolean("dailyLimitApplied", trade.dailyLimitApplied());
            value.putBoolean("settled", trade.settled()); value.putLong("createdAt", trade.createdAt()); transactionTags.add(value);
        }
        tag.put("transactions", transactionTags); ListTag completedTags = new ListTag();
        for (CompletedTrade trade : completedTrades.values()) {
            CompoundTag value = new CompoundTag(); value.putUUID("reference", trade.reference()); value.putString("fingerprint", trade.fingerprint());
            value.putString("type", trade.type().name()); value.putUUID("player", trade.playerId()); value.putUUID("shop", trade.shopId());
            value.putUUID("offer", trade.offerId()); value.putString("itemName", trade.itemName()); value.putInt("quantity", trade.quantity());
            value.putLong("amount", trade.amount()); completedTags.add(value);
        }
        tag.put("completedTransactions", completedTags); ListTag dailyTags = new ListTag();
        dailySales.forEach((player, sales) -> {
            CompoundTag value = new CompoundTag(); value.putUUID("player", player); value.putLong("epochDay", sales.epochDay());
            value.putLong("earned", sales.earned()); dailyTags.add(value);
        });
        tag.put("dailySales", dailyTags); return tag;
    }

    public static ServerShopStore load(CompoundTag tag, HolderLookup.Provider registries) {
        int version = tag.getInt("version");
        if ((version < 1 || version > 5) || !tag.contains("shops", Tag.TAG_LIST)
                || version >= 4 && !tag.contains("dailySales", Tag.TAG_LIST))
            throw new IllegalStateException("Unsupported server shop data");
        ServerShopStore store = new ServerShopStore();
        for (Tag raw : tag.getList("shops", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) raw;
            if (!value.hasUUID("id") || !value.contains("offers", Tag.TAG_LIST)
                    || !value.contains("rotation", Tag.TAG_COMPOUND) || !value.contains("revision", Tag.TAG_LONG))
                throw new IllegalStateException("Invalid server shop entry");
            ServerShop.Mode mode;
            try { mode = ServerShop.Mode.valueOf(value.getString("mode")); }
            catch (IllegalArgumentException error) { throw new IllegalStateException("Unknown server shop mode", error); }
            List<ServerShop.Offer> offers = new ArrayList<>();
            for (Tag rawOffer : value.getList("offers", Tag.TAG_COMPOUND)) {
                CompoundTag offer = (CompoundTag) rawOffer;
                if (!offer.hasUUID("id") || !offer.contains("item") || !offer.contains("quantity", Tag.TAG_INT)
                        || !offer.contains("playerBuyPrice", Tag.TAG_LONG) || !offer.contains("playerSellPrice", Tag.TAG_LONG)
                        || !offer.contains("stock", Tag.TAG_LONG)) throw new IllegalStateException("Invalid server shop offer");
                ServerShop.StockMode stockMode;
                try { stockMode = ServerShop.StockMode.valueOf(offer.getString("stockMode")); }
                catch (IllegalArgumentException error) { throw new IllegalStateException("Unknown server shop stock mode", error); }
                ItemStack item = ItemStack.parse(registries, offer.get("item"))
                        .orElseThrow(() -> new IllegalStateException("Invalid server shop offer item"));
                offers.add(new ServerShop.Offer(offer.getUUID("id"), item, offer.getInt("quantity"),
                        offer.getLong("playerBuyPrice"), offer.getLong("playerSellPrice"),
                        new ServerShop.Stock(stockMode, offer.getLong("stock")), version >= 2 && offer.getBoolean("rotationPool")));
            }
            CompoundTag rotation = value.getCompound("rotation");
            String durationKey = version >= 2 ? "durationMillis" : "intervalTicks";
            if (!rotation.contains("enabled", Tag.TAG_BYTE) || !rotation.contains(durationKey, Tag.TAG_LONG)
                    || !rotation.contains("visibleOffers", Tag.TAG_INT) || !rotation.contains("nextRotationAt", Tag.TAG_LONG))
                throw new IllegalStateException("Invalid server shop rotation settings");
            long duration = rotation.getLong(durationKey);
            if (version == 1) duration = Math.multiplyExact(duration, 50L);
            long deadline = version == 1 && rotation.getBoolean("enabled") ? Math.addExact(System.currentTimeMillis(), duration) : rotation.getLong("nextRotationAt");
            ServerShop shop = new ServerShop(value.getUUID("id"), value.getString("name"), mode, offers,
                    new ServerShop.Rotation(rotation.getBoolean("enabled"), duration,
                            rotation.getInt("visibleOffers"), deadline, version >= 2 ? rotation.getLong("sequence") : 0), value.getLong("revision"));
            try { store.create(shop); }
            catch (IllegalArgumentException error) { throw new IllegalStateException("Duplicate or invalid server shop", error); }
        }
        if (version >= 2) {
            if (!tag.contains("transactions", Tag.TAG_LIST)) throw new IllegalStateException("Missing server shop transactions");
            for (Tag raw : tag.getList("transactions", Tag.TAG_COMPOUND)) {
                CompoundTag value = (CompoundTag) raw;
                try {
                    Trade trade = new Trade(value.getUUID("reference"), value.getString("fingerprint"), TradeType.valueOf(value.getString("type")),
                            value.getUUID("player"), value.getUUID("shop"), value.getUUID("offer"),
                            ItemStack.parse(registries, value.get("item")).orElseThrow(), value.getInt("quantity"), value.getLong("amount"),
                            value.getBoolean("sharedStock"), value.getBoolean("stockApplied"),
                            version >= 4 && value.getBoolean("dailyLimitApplied"), value.getBoolean("settled"), value.getLong("createdAt"));
                    if (store.trades.putIfAbsent(trade.reference(), trade) != null) throw new IllegalStateException("Duplicate server shop transaction");
                } catch (IllegalArgumentException | NoSuchElementException error) { throw new IllegalStateException("Invalid server shop transaction", error); }
            }
        }
        if (version >= 3) {
            if (!tag.contains("completedTransactions", Tag.TAG_LIST)) throw new IllegalStateException("Missing completed server shop transactions");
            for (Tag raw : tag.getList("completedTransactions", Tag.TAG_COMPOUND)) {
                CompoundTag value = (CompoundTag) raw;
                try {
                    CompletedTrade trade = new CompletedTrade(value.getUUID("reference"), value.getString("fingerprint"),
                            TradeType.valueOf(value.getString("type")), value.getUUID("player"), value.getUUID("shop"), value.getUUID("offer"),
                            value.getString("itemName"), value.getInt("quantity"), value.getLong("amount"));
                    if (store.completedTrades.putIfAbsent(trade.reference(), trade) != null || store.trades.containsKey(trade.reference()))
                        throw new IllegalStateException("Duplicate completed server shop transaction");
                } catch (IllegalArgumentException error) { throw new IllegalStateException("Invalid completed server shop transaction", error); }
            }
        }
        if (version >= 4) for (Tag raw : tag.getList("dailySales", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) raw;
            try {
                UUID player = value.getUUID("player");
                DailySales sales = new DailySales(value.getLong("epochDay"), value.getLong("earned"));
                if (store.dailySales.putIfAbsent(player, sales) != null) throw new IllegalStateException("Duplicate daily server-shop sales");
            } catch (RuntimeException error) { throw new IllegalStateException("Invalid daily server-shop sales", error); }
        }
        store.historyRetention.load(tag, "historyRetention", store.completedTrades.keySet(), tag.getInt("version") >= 5);
        for (UUID id : store.trades.keySet()) store.historyRetention.requireUnretired(id);
        store.setDirty(tag.getInt("version") < 5); return store;
    }
}
