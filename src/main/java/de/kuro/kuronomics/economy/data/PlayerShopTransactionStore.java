package de.kuro.kuronomics.economy.data;

import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.block.MachineKind;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** Keeps placed-shop trades recoverable across ledger, chunk and player saves. */
public final class PlayerShopTransactionStore extends de.kuro.kuronomics.economy.data.FinancialSavedData {
    private static final String DATA_NAME = Kuronomics.MODID + "_player_shop_transactions";
    public record Trade(UUID reference, String fingerprint, UUID buyer, UUID owner, String dimension, long position,
                        MachineKind kind, int slot, ItemStack stockItem, ItemStack boughtItem, long price,
                        boolean stockApplied, boolean delivered, boolean settled, UUID company) {
        public Trade(UUID reference, String fingerprint, UUID buyer, UUID owner, String dimension, long position,
                     MachineKind kind, int slot, ItemStack stockItem, ItemStack boughtItem, long price, boolean stockApplied, boolean delivered, boolean settled) {
            this(reference, fingerprint, buyer, owner, dimension, position, kind, slot, stockItem, boughtItem, price, stockApplied, delivered, settled, null);
        }
        public Trade {
            Objects.requireNonNull(reference); Objects.requireNonNull(fingerprint); Objects.requireNonNull(buyer);
            Objects.requireNonNull(dimension); Objects.requireNonNull(kind);
            if ((kind == MachineKind.COMPANY_SHOP) != (company != null)) throw new IllegalArgumentException("Invalid company shop owner");
            if (dimension.isBlank() || slot < 0 || stockItem == null || stockItem.isEmpty() || boughtItem == null || boughtItem.isEmpty() || price <= 0)
                throw new IllegalArgumentException("Invalid player-shop transaction");
            stockItem = stockItem.copyWithCount(1); boughtItem = boughtItem.copy();
        }
        @Override public ItemStack stockItem() { return stockItem.copy(); }
        @Override public ItemStack boughtItem() { return boughtItem.copy(); }
        public Trade progress(boolean stock, boolean item, boolean done) {
            return new Trade(reference, fingerprint, buyer, owner, dimension, position, kind, slot, stockItem, boughtItem, price, stock, item, done, company);
        }
    }
    private final Map<UUID, Trade> pending = new LinkedHashMap<>();
    private final Map<String, Integer> pendingShops = new HashMap<>();
    private static String shop(Trade trade) { return trade.dimension() + ":" + trade.position(); }
    public boolean pendingAt(String dimension, long position) { return pendingShops.getOrDefault(dimension + ":" + position, 0) > 0; }
    private final Map<UUID, String> completed = new LinkedHashMap<>();
    private final de.kuro.kuronomics.economy.data.FinancialHistory historyRetention = new de.kuro.kuronomics.economy.data.FinancialHistory();
    public int pruneHistory(int maximum, long ageMillis, long now) {
        int count = historyRetention.prune(completed, maximum, ageMillis, now, (id, value) -> true,
                (id, value) -> historyRetention.retire(id, value));
        if (count > 0) setDirty(); return count;
    }

    public static PlayerShopTransactionStore get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Player shops accessed off server thread");
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(PlayerShopTransactionStore::new, PlayerShopTransactionStore::load), DATA_NAME);
    }
    public static void validate(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DATA_NAME + ".dat");
        if (!Files.exists(file)) return;
        try { load(NbtIo.readCompressed(file, NbtAccounter.create(32 * 1024 * 1024)).getCompound("data"), server.registryAccess()); }
        catch (IOException | RuntimeException error) { throw new IllegalStateException("Player-shop transaction data is unreadable; restore a backup", error); }
    }
    public Trade trade(UUID reference) { return pending.get(reference); }
    public String completed(UUID reference) { historyRetention.requireUnretired(reference); return completed.get(reference); }
    public Collection<Trade> pendingFor(UUID buyer) { return pending.values().stream().filter(value -> value.buyer().equals(buyer)).toList(); }
    public void prepare(Trade trade) {
        historyRetention.requireUnretired(trade.reference());
        String done = completed.get(trade.reference());
        if (done != null) { if (!done.equals(trade.fingerprint())) throw new IllegalArgumentException("Shop reference was already used"); return; }
        Trade old = pending.putIfAbsent(trade.reference(), trade);
        if (old != null && !old.fingerprint().equals(trade.fingerprint())) throw new IllegalArgumentException("Shop reference was already used");
        if (old == null) { pendingShops.merge(shop(trade), 1, Integer::sum); setDirty(); }
    }
    public void update(Trade trade) { if (!pending.containsKey(trade.reference())) throw new IllegalStateException("Shop transaction is missing"); pending.put(trade.reference(), trade); setDirty(); }
    public void complete(Trade trade) {
        if (!trade.settled()) throw new IllegalStateException("Shop transaction is not settled");
        historyRetention.record(trade.reference()); String old = completed.putIfAbsent(trade.reference(), trade.fingerprint());
        if (old != null && !old.equals(trade.fingerprint())) throw new IllegalStateException("Conflicting shop transaction tombstone");
        if (pending.remove(trade.reference()) != null) pendingShops.computeIfPresent(shop(trade), (key, count) -> count <= 1 ? null : count - 1); setDirty();
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", 3); historyRetention.save(tag, "historyRetention"); ListTag active = new ListTag();
        for (Trade trade : pending.values()) {
            CompoundTag value = new CompoundTag(); value.putUUID("reference", trade.reference()); value.putString("fingerprint", trade.fingerprint());
            value.putUUID("buyer", trade.buyer()); if (trade.owner() != null) value.putUUID("owner", trade.owner()); value.putString("dimension", trade.dimension());
            if (trade.company() != null) value.putUUID("company", trade.company());
            value.putLong("position", trade.position()); value.putString("kind", trade.kind().name()); value.putInt("slot", trade.slot());
            value.put("stockItem", trade.stockItem().save(registries)); value.put("boughtItem", trade.boughtItem().save(registries)); value.putLong("price", trade.price());
            value.putBoolean("stockApplied", trade.stockApplied()); value.putBoolean("delivered", trade.delivered()); value.putBoolean("settled", trade.settled()); active.add(value);
        }
        tag.put("pending", active); ListTag done = new ListTag();
        completed.forEach((reference, fingerprint) -> { CompoundTag value = new CompoundTag(); value.putUUID("reference", reference); value.putString("fingerprint", fingerprint); done.add(value); });
        tag.put("completed", done); return tag;
    }
    public static PlayerShopTransactionStore load(CompoundTag tag, HolderLookup.Provider registries) {
        if ((tag.getInt("version") < 1 || tag.getInt("version") > 3) || !tag.contains("pending", Tag.TAG_LIST) || !tag.contains("completed", Tag.TAG_LIST))
            throw new IllegalStateException("Unsupported player-shop transaction data");
        PlayerShopTransactionStore store = new PlayerShopTransactionStore();
        for (Tag raw : tag.getList("pending", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) raw;
            try {
                Trade trade = new Trade(value.getUUID("reference"), value.getString("fingerprint"), value.getUUID("buyer"),
                        value.hasUUID("owner") ? value.getUUID("owner") : null, value.getString("dimension"), value.getLong("position"),
                        MachineKind.valueOf(value.getString("kind")), value.getInt("slot"), ItemStack.parse(registries, value.get("stockItem")).orElseThrow(),
                        ItemStack.parse(registries, value.get("boughtItem")).orElseThrow(), value.getLong("price"), value.getBoolean("stockApplied"),
                        value.getBoolean("delivered"), value.getBoolean("settled"), value.hasUUID("company") ? value.getUUID("company") : null);
                if (trade.settled()) store.completed.put(trade.reference(), trade.fingerprint());
                else if (store.pending.putIfAbsent(trade.reference(), trade) != null) throw new IllegalStateException("Duplicate player-shop transaction");
                if (!trade.settled()) store.pendingShops.merge(shop(trade), 1, Integer::sum);
            } catch (RuntimeException error) { throw new IllegalStateException("Invalid player-shop transaction", error); }
        }
        for (Tag raw : tag.getList("completed", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) raw; UUID reference = value.getUUID("reference"); String fingerprint = value.getString("fingerprint");
            String old = store.completed.putIfAbsent(reference, fingerprint);
            if (fingerprint.isBlank() || old != null && !old.equals(fingerprint) || store.pending.containsKey(reference))
                throw new IllegalStateException("Duplicate completed player-shop transaction");
        }
        store.historyRetention.load(tag, "historyRetention", store.completed.keySet(), tag.getInt("version") >= 2);
        for (UUID id : store.pending.keySet()) store.historyRetention.requireUnretired(id);
        store.setDirty(tag.getInt("version") < 3); return store;
    }
}
