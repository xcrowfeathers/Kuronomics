package de.kuro.kuronomics.company;

import java.util.*;

/** Only copy changed partitions. Published buckets and entries must stay unchanged. */
public final class SnapshotMap<K, V> extends AbstractMap<K, V> {
    private static final int PARTITIONS = 256;
    private final Map<K, V>[] buckets;
    private final boolean[] writable = new boolean[PARTITIONS];
    private final Set<K> changed = new HashSet<>();
    private int size;
    private boolean copied;
    private Map<K, V> original;
    @SuppressWarnings("unchecked") public SnapshotMap() {
        buckets = (Map<K, V>[]) new Map<?, ?>[PARTITIONS]; Arrays.fill(buckets, Map.of()); copied = true;
    }
    public SnapshotMap(Map<K, V> base) {
        this(); copied = false; original = base;
        if (base instanceof Frozen<K, V> frozen) { System.arraycopy(frozen.buckets, 0, buckets, 0, PARTITIONS); size = frozen.size; }
        else { original = null; base.forEach(this::put); original = freeze(); changed.clear(); copied = false; }
    }
    private static int bucket(Object key) { int h = Objects.requireNonNull(key).hashCode(); return (h ^ (h >>> 16)) & (PARTITIONS - 1); }
    boolean unchanged() { return !copied; }
    Set<K> changedKeys() { return Collections.unmodifiableSet(changed); }
    public static <K, V> Set<K> differences(Map<K, V> before, Map<K, V> after) {
        Set<K> keys = new HashSet<>();
        if (before instanceof Frozen<K, V> a && after instanceof Frozen<K, V> b) {
            for (int i = 0; i < PARTITIONS; i++) if (a.buckets[i] != b.buckets[i]) {
                for (K key : a.buckets[i].keySet()) if (!Objects.equals(a.buckets[i].get(key), b.buckets[i].get(key))) keys.add(key);
                for (K key : b.buckets[i].keySet()) if (!a.buckets[i].containsKey(key)) keys.add(key);
            }
        } else {
            for (K key : before.keySet()) if (!Objects.equals(before.get(key), after.get(key))) keys.add(key);
            for (K key : after.keySet()) if (!before.containsKey(key)) keys.add(key);
        }
        return keys;
    }
    private Map<K, V> write(int bucket) {
        if (!writable[bucket]) { buckets[bucket] = new HashMap<>(buckets[bucket]); writable[bucket] = true; }
        copied = true; return buckets[bucket];
    }
    public Map<K, V> freeze() {
        if (!copied && original != null) return original;
        for (int i = 0; i < PARTITIONS; i++) if (writable[i]) { buckets[i] = Map.copyOf(buckets[i]); writable[i] = false; }
        return new Frozen<>(buckets.clone(), size);
    }
    @Override public int size() { return size; }
    @Override public V get(Object key) { return buckets[bucket(key)].get(key); }
    @Override public boolean containsKey(Object key) { return buckets[bucket(key)].containsKey(key); }
    @Override public V put(K key, V value) {
        Objects.requireNonNull(value); V old = write(bucket(key)).put(key, value); changed.add(key); if (old == null) size++; return old;
    }
    @SuppressWarnings("unchecked") @Override public V remove(Object key) {
        if (!containsKey(key)) return null; V old = write(bucket(key)).remove(key); changed.add((K) key); size--; return old;
    }
    @Override public void clear() { for (K key : new ArrayList<>(keySet())) remove(key); }
    @Override public Set<Entry<K, V>> entrySet() {
        return new AbstractSet<>() {
            public int size() { return SnapshotMap.this.size; }
            public Iterator<Entry<K, V>> iterator() {
                Map<K, V>[] snapshot = ((Frozen<K, V>) freeze()).buckets;
                return new Iterator<>() {
                    int nextBucket; Iterator<Entry<K, V>> entries = Collections.emptyIterator(); K last; boolean removable;
                    public boolean hasNext() {
                        while (!entries.hasNext() && nextBucket < PARTITIONS) entries = snapshot[nextBucket++].entrySet().iterator();
                        return entries.hasNext();
                    }
                    public Entry<K, V> next() {
                        if (!hasNext()) throw new NoSuchElementException(); Entry<K, V> entry = entries.next(); last = entry.getKey(); removable = true;
                        return new SimpleEntry<>(entry) {
                            public V setValue(V value) { V old = SnapshotMap.this.put(getKey(), value); super.setValue(value); return old; }
                        };
                    }
                    public void remove() {
                        if (!removable) throw new IllegalStateException(); SnapshotMap.this.remove(last); removable = false;
                    }
                };
            }
        };
    }
    private static final class Frozen<K, V> extends AbstractMap<K, V> {
        final Map<K, V>[] buckets; final int size;
        Frozen(Map<K, V>[] buckets, int size) { this.buckets = buckets; this.size = size; }
        public int size() { return size; }
        public V get(Object key) { return buckets[bucket(key)].get(key); }
        public boolean containsKey(Object key) { return buckets[bucket(key)].containsKey(key); }
        public Set<Entry<K, V>> entrySet() {
            return new AbstractSet<>() {
                public int size() { return Frozen.this.size; }
                public Iterator<Entry<K, V>> iterator() {
                    return Arrays.stream(buckets).flatMap(m -> m.entrySet().stream()).iterator();
                }
            };
        }
    }
}
