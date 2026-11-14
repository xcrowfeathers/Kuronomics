package de.kuro.kuronomics.economy.data;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.*;
import net.minecraft.nbt.*;

/**
 * Cap detailed history but keep compact retry records permanently.
 * Save the retry evidence and removed details in the same SavedData snapshot, not separate files.
 */
public final class FinancialHistory {
    public static final int BATCH = 256;
    private final LinkedHashMap<UUID, Long> candidates = new LinkedHashMap<>();
    private final TreeMap<Long, Integer> ages = new TreeMap<>();
    private Map<UUID, byte[]> retired = new HashMap<>();
    private GuardSnapshot frozenGuards;
    private static final class GuardSnapshot {
        final Map<UUID, byte[]> records;
        private long[] encoded;
        GuardSnapshot(Map<UUID, byte[]> records) { this.records = records; }
        synchronized long[] bytes() {
            if (encoded == null) {
                encoded = new long[Math.multiplyExact(records.size(), 6)]; int n = 0;
                for (var entry : records.entrySet()) {
                    encoded[n++] = entry.getKey().getMostSignificantBits(); encoded[n++] = entry.getKey().getLeastSignificantBits();
                    ByteBuffer bytes = ByteBuffer.wrap(entry.getValue()); for (int i = 0; i < 4; i++) encoded[n++] = bytes.getLong();
                }
            }
            return encoded.clone();
        }
    }
    private GuardSnapshot guards() {
        if (frozenGuards == null) frozenGuards = new GuardSnapshot(Map.copyOf(retired)); return frozenGuards;
    }
    /** Share unchanged retry evidence between checkpoints. Don't let callers edit the cached tags. */
    public void copyForSave(FinancialHistory target) {
        target.candidates.putAll(candidates); target.frozenGuards = guards(); target.retired = target.frozenGuards.records;
    }

    public void record(UUID id) { record(id, System.currentTimeMillis()); }
    public void record(UUID id, long now) { if (candidates.putIfAbsent(id, now) == null) ages.merge(now, 1, Integer::sum); }
    private void removeAge(long age) { ages.computeIfPresent(age, (key, count) -> count == 1 ? null : count - 1); }
    public boolean contains(UUID id) { return retired.containsKey(id); }
    public boolean matches(UUID id, String fingerprint) {
        byte[] previous = retired.get(id);
        if (previous == null) return false;
        if (!MessageDigest.isEqual(previous, digest(fingerprint)))
            throw new IllegalArgumentException("Transaction reference was already used for another operation");
        return true;
    }
    public void requireUnretired(UUID id) {
        if (contains(id)) throw new IllegalArgumentException("This request was already finalized; its history has expired.");
    }
    public void retire(UUID id, String fingerprint) {
        byte[] hash = digest(fingerprint);
        byte[] previous = retired.putIfAbsent(id, hash);
        if (previous == null) frozenGuards = null;
        if (previous != null && !MessageDigest.isEqual(previous, hash)) throw new IllegalStateException("Conflicting retired transaction");
    }
    /** Check at most BATCH entries. Move pinned work to the back without resetting its age. */
    public <T> int prune(Map<UUID, T> details, int maximum, long ageMillis, long now,
                         BiPredicate<UUID, T> safe, BiConsumer<UUID, T> removed) {
        if (candidates.isEmpty()) return 0;
        long oldest = ages.firstKey();
        if (candidates.size() <= maximum && (now < oldest || now - oldest < ageMillis)) return 0;
        int changed = 0, count = Math.min(BATCH, candidates.size());
        for (int i = 0; i < count; i++) {
            var iterator = candidates.entrySet().iterator(); var entry = iterator.next();
            UUID id = entry.getKey(); long created = entry.getValue(); iterator.remove();
            T value = details.get(id);
            if (value == null) { removeAge(created); continue; }
            boolean expired = now >= created && now - created >= ageMillis;
            if ((candidates.size() + 1 > maximum || expired) && safe.test(id, value)) {
                removed.accept(id, value); details.remove(id); removeAge(created); changed++;
            } else candidates.put(id, created);
        }
        return changed;
    }
    public void save(CompoundTag root, String key) {
        saveShared(root, key, null, null);
    }
    /** Only use for snapshots owned by the writer. Public saves still return separate arrays. */
    public void saveShared(CompoundTag root, String key, FinancialHistory previous, CompoundTag priorTag) {
        CompoundTag tag = new CompoundTag(); tag.putInt("version", 1);
        if (previous != null && candidates.equals(previous.candidates)) tag.put("ages", priorTag.get("ages")); else {
        long[] times = new long[Math.multiplyExact(candidates.size(), 3)]; int n = 0;
        for (var entry : candidates.entrySet()) {
            times[n++] = entry.getKey().getMostSignificantBits(); times[n++] = entry.getKey().getLeastSignificantBits(); times[n++] = entry.getValue();
        }
        tag.putLongArray("ages", times);
        }
        if (previous != null && guards() == previous.guards()) tag.put("guards", priorTag.get("guards"));
        else tag.putLongArray("guards", guards().bytes()); root.put(key, tag);
    }
    public void load(CompoundTag root, String key, Collection<UUID> existing, boolean required) {
        if (required && !root.contains(key, Tag.TAG_COMPOUND)) throw new IllegalStateException("Missing financial replay evidence: " + key);
        if (root.contains(key)) {
            CompoundTag tag = root.getCompound(key);
            if (tag.getInt("version") != 1 || !tag.contains("ages", Tag.TAG_LONG_ARRAY) || !tag.contains("guards", Tag.TAG_LONG_ARRAY))
                throw new IllegalStateException("Invalid financial history data");
            long[] times = tag.getLongArray("ages"), guards = tag.getLongArray("guards");
            if (times.length % 3 != 0 || guards.length % 6 != 0) throw new IllegalStateException("Truncated financial history data");
            for (int n = 0; n < times.length; n += 3)
                if (times[n + 2] < 0 || candidates.putIfAbsent(new UUID(times[n], times[n + 1]), times[n + 2]) != null)
                    throw new IllegalStateException("Invalid financial history age");
            for (int n = 0; n < guards.length; n += 6) {
                ByteBuffer bytes = ByteBuffer.allocate(32); for (int i = 2; i < 6; i++) bytes.putLong(guards[n + i]);
                if (retired.putIfAbsent(new UUID(guards[n], guards[n + 1]), bytes.array()) != null)
                    throw new IllegalStateException("Duplicate financial replay evidence");
            }
        }
        long now = System.currentTimeMillis();
        for (UUID id : existing) {
            if (retired.containsKey(id)) throw new IllegalStateException("Financial record is both retained and retired");
            record(id, now); // Old records don't have a reliable completion date. Start counting their age now.
        }
        candidates.keySet().retainAll(new HashSet<>(existing));
        ages.clear(); candidates.values().forEach(age -> ages.merge(age, 1, Integer::sum));
    }
    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
