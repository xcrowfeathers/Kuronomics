package de.kuro.kuronomics.estate;

import de.kuro.kuronomics.Kuronomics;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** Save locks so two Kuronomy operations can't work on the same property at once. */
public final class PropertyLockStore extends de.kuro.kuronomics.economy.data.FinancialSavedData {
    private static final String DATA_NAME = Kuronomics.MODID + "_property_locks";
    public enum Purpose { AUCTION, MARKETPLACE }
    public record Lock(String propertyId, Purpose purpose, UUID operationId, UUID sellerId, UUID transferTarget, long lockedAt) {
        public Lock {
            if (propertyId == null || propertyId.isBlank()) throw new IllegalArgumentException("Property ID cannot be blank");
            Objects.requireNonNull(purpose); Objects.requireNonNull(operationId); Objects.requireNonNull(sellerId);
            if (lockedAt < 0) throw new IllegalArgumentException("Invalid property lock time");
        }
        public UUID auctionId() { return operationId; }
        Lock transferring(UUID target) { return new Lock(propertyId, purpose, operationId, sellerId, target, lockedAt); }
    }
    private final Map<String, Lock> locks = new LinkedHashMap<>();
    private Set<UUID> cachedOperations;
    @Override public void setDirty() { cachedOperations = null; super.setDirty(); }

    public static PropertyLockStore get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Property locks accessed off server thread");
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(PropertyLockStore::new, PropertyLockStore::load), DATA_NAME);
    }
    public static void validate(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DATA_NAME + ".dat");
        if (!Files.exists(file)) return;
        try { load(NbtIo.readCompressed(file, NbtAccounter.create(16 * 1024 * 1024)).getCompound("data"), server.registryAccess()); }
        catch (IOException | RuntimeException error) { throw new IllegalStateException("Property lock data is unreadable; restore a backup", error); }
    }
    Lock lock(String propertyId) { return locks.get(propertyId); }
    Collection<Lock> locks() { return List.copyOf(locks.values()); }
    public Set<UUID> operationReferences() {
        if (cachedOperations == null) {
            Set<UUID> result = new HashSet<>(); locks.values().forEach(value -> result.add(value.operationId())); cachedOperations = Set.copyOf(result);
        }
        return cachedOperations;
    }
    void add(Lock lock) {
        Lock old = locks.putIfAbsent(lock.propertyId(), lock);
        if (old != null && (!old.purpose().equals(lock.purpose()) || !old.operationId().equals(lock.operationId())
                || !old.sellerId().equals(lock.sellerId())))
            throw new IllegalArgumentException("This property is already locked by another operation.");
        if (old == null) setDirty();
    }
    void replace(Lock lock) {
        if (!locks.containsKey(lock.propertyId())) throw new IllegalStateException("Property auction lock is missing.");
        locks.put(lock.propertyId(), lock); setDirty();
    }
    void remove(String propertyId, Purpose purpose, UUID operationId) {
        Lock lock = locks.get(propertyId);
        if (lock != null && lock.purpose() == purpose && lock.operationId().equals(operationId)) {
            locks.remove(propertyId); setDirty();
        }
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", 2); ListTag values = new ListTag();
        for (Lock lock : locks.values()) {
            CompoundTag value = new CompoundTag(); value.putString("property", lock.propertyId());
            value.putString("purpose", lock.purpose().name()); value.putUUID("operation", lock.operationId());
            value.putUUID("seller", lock.sellerId()); if (lock.transferTarget() != null) value.putUUID("target", lock.transferTarget());
            value.putLong("lockedAt", lock.lockedAt()); values.add(value);
        }
        tag.put("locks", values); return tag;
    }
    public static PropertyLockStore load(CompoundTag tag, HolderLookup.Provider registries) {
        int version = tag.getInt("version");
        if ((version != 1 && version != 2) || !tag.contains("locks", Tag.TAG_LIST)) throw new IllegalStateException("Unsupported property lock data");
        PropertyLockStore store = new PropertyLockStore();
        for (Tag raw : tag.getList("locks", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) raw;
            Purpose purpose = version == 1 ? Purpose.AUCTION : Purpose.valueOf(value.getString("purpose"));
            UUID operation = version == 1 ? value.getUUID("auction") : value.getUUID("operation");
            Lock lock = new Lock(value.getString("property"), purpose, operation, value.getUUID("seller"),
                    value.hasUUID("target") ? value.getUUID("target") : null, value.getLong("lockedAt"));
            if (store.locks.putIfAbsent(lock.propertyId(), lock) != null) throw new IllegalStateException("Duplicate property lock");
        }
        store.setDirty(false); return store;
    }
}
