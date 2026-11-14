package de.kuro.kuronomics.economy.data;

import de.kuro.kuronomics.Kuronomics;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** Keeps ATM transactions recoverable across economy and player-inventory saves. */
public final class CashTransactionStore extends de.kuro.kuronomics.economy.data.FinancialSavedData {
    private static final String DATA_NAME = Kuronomics.MODID + "_cash_transactions";
    public enum Type { DEPOSIT, WITHDRAWAL }
    public record Transaction(UUID reference, String fingerprint, UUID player, Type type, int denomination,
                              long amount, boolean physicalApplied, boolean moneyApplied) {
        public Transaction {
            Objects.requireNonNull(reference); Objects.requireNonNull(fingerprint); Objects.requireNonNull(player); Objects.requireNonNull(type);
            if (amount <= 0 || denomination < 0) throw new IllegalArgumentException("Invalid ATM transaction");
        }
        public Transaction progress(boolean physical, boolean money) {
            return new Transaction(reference, fingerprint, player, type, denomination, amount, physical, money);
        }
    }
    public record Completed(String fingerprint) {
        public Completed { if (fingerprint == null || fingerprint.isBlank()) throw new IllegalArgumentException("Invalid ATM transaction fingerprint"); }
    }

    private final Map<UUID, Transaction> pending = new LinkedHashMap<>();
    private final Map<UUID, Completed> completed = new LinkedHashMap<>();
    private final de.kuro.kuronomics.economy.data.FinancialHistory historyRetention = new de.kuro.kuronomics.economy.data.FinancialHistory();
    public int pruneHistory(int maximum, long ageMillis, long now) {
        int count = historyRetention.prune(completed, maximum, ageMillis, now, (id, value) -> true,
                (id, value) -> historyRetention.retire(id, value.fingerprint()));
        if (count > 0) setDirty(); return count;
    }

    public static CashTransactionStore get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("ATM transactions accessed off server thread");
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(CashTransactionStore::new, CashTransactionStore::load), DATA_NAME);
    }
    public static void validate(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DATA_NAME + ".dat");
        if (!Files.exists(file)) return;
        try { load(NbtIo.readCompressed(file, NbtAccounter.create(16 * 1024 * 1024)).getCompound("data"), server.registryAccess()); }
        catch (IOException | RuntimeException error) { throw new IllegalStateException("ATM transaction data is unreadable; restore a backup", error); }
    }
    public Transaction pending(UUID reference) { return pending.get(reference); }
    public Completed completed(UUID reference) { historyRetention.requireUnretired(reference); return completed.get(reference); }
    public Collection<Transaction> pendingFor(UUID player) {
        return pending.values().stream().filter(value -> value.player().equals(player)).toList();
    }
    public void prepare(Transaction value) {
        historyRetention.requireUnretired(value.reference());
        if (completed.containsKey(value.reference())) throw new IllegalArgumentException("ATM transaction was already completed");
        Transaction old = pending.putIfAbsent(value.reference(), value);
        if (old != null && !old.fingerprint().equals(value.fingerprint())) throw new IllegalArgumentException("ATM transaction reference was already used");
        if (old == null) setDirty();
    }
    public void update(Transaction value) {
        if (!pending.containsKey(value.reference())) throw new IllegalArgumentException("Unknown ATM transaction");
        pending.put(value.reference(), value); setDirty();
    }
    public void complete(UUID reference) {
        Transaction value = pending.get(reference);
        if (value == null) return;
        if (!value.physicalApplied() || !value.moneyApplied()) throw new IllegalStateException("ATM transaction is not settled");
        historyRetention.record(reference); Completed old = completed.putIfAbsent(reference, new Completed(value.fingerprint()));
        if (old != null && !old.fingerprint().equals(value.fingerprint())) throw new IllegalStateException("Conflicting completed ATM transaction");
        pending.remove(reference);
        setDirty();
    }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", 2); historyRetention.save(tag, "historyRetention"); ListTag active = new ListTag();
        for (Transaction value : pending.values()) {
            CompoundTag item = new CompoundTag(); item.putUUID("reference", value.reference()); item.putString("fingerprint", value.fingerprint());
            item.putUUID("player", value.player()); item.putString("type", value.type().name()); item.putInt("denomination", value.denomination());
            item.putLong("amount", value.amount()); item.putBoolean("physicalApplied", value.physicalApplied()); item.putBoolean("moneyApplied", value.moneyApplied());
            active.add(item);
        }
        tag.put("pending", active); ListTag done = new ListTag();
        completed.forEach((reference, value) -> { CompoundTag item = new CompoundTag(); item.putUUID("reference", reference); item.putString("fingerprint", value.fingerprint()); done.add(item); });
        tag.put("completed", done); return tag;
    }
    public static CashTransactionStore load(CompoundTag tag, HolderLookup.Provider registries) {
        if ((tag.getInt("version") < 1 || tag.getInt("version") > 2) || !tag.contains("pending", Tag.TAG_LIST) || !tag.contains("completed", Tag.TAG_LIST))
            throw new IllegalStateException("Unsupported ATM transaction data");
        CashTransactionStore store = new CashTransactionStore();
        for (Tag raw : tag.getList("pending", Tag.TAG_COMPOUND)) {
            CompoundTag item = (CompoundTag) raw;
            try {
                Transaction value = new Transaction(item.getUUID("reference"), item.getString("fingerprint"), item.getUUID("player"),
                        Type.valueOf(item.getString("type")), item.getInt("denomination"), item.getLong("amount"),
                        item.getBoolean("physicalApplied"), item.getBoolean("moneyApplied"));
                if (store.pending.putIfAbsent(value.reference(), value) != null) throw new IllegalStateException("Duplicate ATM transaction");
            } catch (RuntimeException error) { throw new IllegalStateException("Invalid ATM transaction", error); }
        }
        for (Tag raw : tag.getList("completed", Tag.TAG_COMPOUND)) {
            CompoundTag item = (CompoundTag) raw; UUID reference = item.getUUID("reference"); Completed value = new Completed(item.getString("fingerprint"));
            if (store.completed.putIfAbsent(reference, value) != null || store.pending.containsKey(reference)) throw new IllegalStateException("Duplicate completed ATM transaction");
        }
        store.historyRetention.load(tag, "historyRetention", store.completed.keySet(), tag.getInt("version") >= 2);
        for (UUID id : store.pending.keySet()) store.historyRetention.requireUnretired(id);
        store.setDirty(tag.getInt("version") < 2); return store;
    }
}
