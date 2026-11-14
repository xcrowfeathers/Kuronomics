package de.kuro.kuronomics.economy;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import net.minecraft.nbt.*;

/**
 * Only the writer thread uses this redo journal. Business transaction IDs still decide whether work ran.
 * Replay restores saved state; it must never run payments, deliver items or repeat gameplay actions.
 */
public final class FinancialJournal {
    private static final int MAGIC = 0x4b57414c, COMMIT = 0x454e4421, MAX_RECORD = 256 * 1024 * 1024;
    private static final String MARKER = "KuronomyJournalSequence";
    private static final int CHECKPOINT_RECORDS = 256;
    private static final long CHECKPOINT_BYTES = 16 * 1024 * 1024;
    private final Path file;
    private CompoundTag state;
    private byte[] stateHash;
    private FinancialDigest.Node authentication;
    private long sequence;
    private int records;
    private long journalBytes;

    public FinancialJournal(Path file) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        Recovery recovered = read(this.file);
        if (recovered.torn) throw new IOException("Journal requires startup recovery: " + file);
        state = recovered.state; sequence = recovered.sequence;
        authentication = FinancialDigest.tree3(state, null); stateHash = authentication.hash();
        journalBytes = Files.exists(journal(this.file)) ? Files.size(journal(this.file)) : 0;
    }
    public static Path journal(Path file) { return file.resolveSibling(file.getFileName() + ".journal"); }

    public void append(CompoundTag next) throws IOException {
        appendOwned(next.copy());
    }
    /** This snapshot belongs to the writer. Don't change it or any shared sections after handing it over. */
    void appendOwned(CompoundTag next) throws IOException {
        appendOwned(FinancialUpdate.between(null, next), true);
    }
    void appendOwned(FinancialUpdate update, boolean synchronousCheckpoint) throws IOException {
        CompoundTag next = update.snapshot();
        if (next.contains(MARKER)) throw new IOException("Reserved financial journal metadata");
        CompoundTag patch = update.base() == state ? update.patch() : diff(state, next);
        if (patch == null) return;
        long nextSequence = Math.incrementExact(sequence);
        CompoundTag record = new CompoundTag(); record.putInt("version", 3); record.putLong("sequence", nextSequence);
        var nextAuthentication = FinancialDigest.tree3(next, authentication); byte[] nextHash = nextAuthentication.hash();
        record.putByteArray("before", stateHash); record.putByteArray("after", nextHash); record.put("patch", patch);
        Files.createDirectories(file.getParent());
        ByteBuffer frame = frame(record);
        int frameBytes = frame.remaining();
        boolean created = !Files.exists(journal(file));
        try (FileChannel channel = FileChannel.open(journal(file), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            while (frame.hasRemaining()) channel.write(frame);
            channel.force(true);
        }
        if (created) forceDirectory(file.getParent());
        state = next; authentication = nextAuthentication; stateHash = nextHash; sequence = nextSequence; records++;
        journalBytes += frameBytes;
        if (synchronousCheckpoint && needsCheckpoint()) checkpoint();
    }
    boolean needsCheckpoint() { return records >= CHECKPOINT_RECORDS || journalBytes >= CHECKPOINT_BYTES; }
    private static final class FastGzip extends java.util.zip.GZIPOutputStream {
        FastGzip(OutputStream output) throws IOException { super(output); def.setLevel(java.util.zip.Deflater.BEST_SPEED); }
    }
    private static ByteBuffer frame(CompoundTag record) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        // Use fast compression here. The output is still normal gzip/NBT that older readers can read.
        try (var gzip = new FastGzip(bytes);
             var output = new DataOutputStream(new BufferedOutputStream(gzip))) { NbtIo.write(record, output); }
        byte[] payload = bytes.toByteArray();
        if (payload.length > MAX_RECORD) throw new IOException("Financial journal record too large");
        ByteBuffer frame = ByteBuffer.allocate(Math.addExact(payload.length, 48));
        // Store the length's complement too, so a corrupt length can't look like an unfinished payload.
        return frame.putInt(MAGIC).putInt(payload.length).putInt(~payload.length).put(payload).put(digest(payload)).putInt(COMMIT).flip();
    }

    public void checkpoint() throws IOException {
        if (sequence == 0) return;
        checkpoint(file, state, sequence, stateHash);
        records = 0; journalBytes = Files.size(journal(file));
    }
    /** Capture this on the writer thread. Don't change its private NBT tree afterward. */
    record Checkpoint(CompoundTag state, long sequence, byte[] hash, long boundary, int records) {}
    record PreparedCheckpoint(Checkpoint captured, Path temporary) {}
    Checkpoint captureCheckpoint() { return new Checkpoint(state, sequence, stateHash, journalBytes, records); }
    PreparedCheckpoint prepareCheckpoint(Checkpoint captured) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".journal-checkpoint.tmp");
        writeCheckpoint(temporary, captured.state(), captured.sequence());
        return new PreparedCheckpoint(captured, temporary);
    }
    /** Publish on the writer thread. Keep every saved append after the checkpoint's captured position. */
    void publishCheckpoint(PreparedCheckpoint prepared) throws IOException {
        Checkpoint captured = prepared.captured();
        if (captured.sequence() > sequence || captured.boundary() > journalBytes)
            throw new IOException("Financial checkpoint overtook its writer");
        Path temporaryJournal = journal(file).resolveSibling(journal(file).getFileName() + ".tmp");
        ByteBuffer base = baseFrame(captured.sequence(), captured.hash());
        long baseBytes = base.remaining(), tailBytes = journalBytes - captured.boundary();
        try (FileChannel output = FileChannel.open(temporaryJournal, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
             FileChannel input = FileChannel.open(journal(file), StandardOpenOption.READ)) {
            if (input.size() != journalBytes) throw new IOException("Financial journal changed outside its writer");
            while (base.hasRemaining()) output.write(base);
            input.position(captured.boundary()); ByteBuffer chunk = ByteBuffer.allocate(64 * 1024);
            long left = tailBytes;
            while (left > 0) {
                chunk.clear(); chunk.limit((int) Math.min(chunk.capacity(), left));
                int read = input.read(chunk); if (read <= 0) throw new EOFException("Incomplete checkpoint journal tail");
                chunk.flip(); while (chunk.hasRemaining()) output.write(chunk); left -= read;
            }
            output.force(true);
        }
        // Recovery handles either crash point: the new checkpoint with the old journal, or both new files.
        Files.move(prepared.temporary(), file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        forceDirectory(file.getParent());
        Files.move(temporaryJournal, journal(file), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        forceDirectory(file.getParent());
        records -= captured.records(); journalBytes = baseBytes + tailBytes;
    }
    private static void writeCheckpoint(Path temporary, CompoundTag state, long sequence) throws IOException {
        CompoundTag root = new CompoundTag(); state.getAllKeys().forEach(key -> root.put(key, state.get(key))); root.putLong(MARKER, sequence);
        Files.createDirectories(temporary.getParent());
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); NbtIo.writeCompressed(root, bytes);
            ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray()); while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
    private static ByteBuffer baseFrame(long sequence, byte[] hash) throws IOException {
        CompoundTag base = new CompoundTag(); base.putInt("version", 3); base.putBoolean("base", true);
        base.putLong("sequence", sequence); base.putByteArray("after", hash); return frame(base);
    }
    private static void checkpoint(Path file, CompoundTag state, long sequence) throws IOException {
        checkpoint(file, state, sequence, FinancialDigest.tree3(state, null).hash());
    }
    private static void checkpoint(Path file, CompoundTag state, long sequence, byte[] stateHash) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".journal-checkpoint.tmp");
        writeCheckpoint(temporary, state, sequence);
        // Never delete the old checkpoint first. Don't fall back to a replacement that isn't atomic.
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        forceDirectory(file.getParent());
        // Replace the journal atomically too. Its base record catches a missing or wrong checkpoint.
        // Don't truncate the live journal until its replacement is safely on disk.
        Path journalTemporary = journal(file).resolveSibling(journal(file).getFileName() + ".tmp");
        try (FileChannel channel = FileChannel.open(journalTemporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = baseFrame(sequence, stateHash); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
        Files.move(journalTemporary, journal(file), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        forceDirectory(file.getParent());
    }
    private static void forceDirectory(Path directory) throws IOException {
        // Windows can't open directory handles through FileChannel. File forcing and atomic replacement still
        // work,
        // but surviving a power cut also depends on the OS and storage.
        if (System.getProperty("os.name", "").startsWith("Windows")) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }

    private record Recovery(CompoundTag state, long sequence, boolean torn) {}
    private static Recovery read(Path file) throws IOException {
        CompoundTag state = Files.exists(file) ? NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()) : new CompoundTag();
        long checkpointSequence = state.getLong(MARKER), sequence = checkpointSequence;
        if (checkpointSequence < 0) throw new IOException("Invalid financial checkpoint sequence: " + file);
        state.remove(MARKER);
        Path journal = journal(file);
        if (!Files.exists(journal)) {
            if (checkpointSequence > 0) throw new IOException("Missing financial journal sidecar: " + journal);
            return new Recovery(state, sequence, false);
        }
        if (checkpointSequence > 0 && Files.size(journal) == 0) throw new IOException("Empty financial journal sidecar: " + journal);
        boolean torn = false, checkpointMatched = checkpointSequence == 0; long previous = -1;
        FinancialDigest.Node authentication = null;
        try (RandomAccessFile input = new RandomAccessFile(journal.toFile(), "r")) {
            while (input.getFilePointer() < input.length()) {
                if (input.length() - input.getFilePointer() < 12) { torn = true; break; }
                if (input.readInt() != MAGIC) throw new IOException("Invalid financial journal header: " + journal);
                int length = input.readInt();
                if (input.readInt() != ~length || length < 1 || length > MAX_RECORD) throw new IOException("Invalid financial journal length: " + journal);
                if (input.length() - input.getFilePointer() < (long) length + 36) { torn = true; break; }
                byte[] payload = new byte[length], checksum = new byte[32]; input.readFully(payload); input.readFully(checksum);
                if (input.readInt() != COMMIT || !MessageDigest.isEqual(checksum, digest(payload)))
                    throw new IOException("Corrupt financial journal record: " + journal);
                // Check the payload checksum first. Use SavedData's heap limits so a valid decoded save isn't
                // rejected
                // just because the compressed frame has a different size limit.
                CompoundTag record = NbtIo.readCompressed(new ByteArrayInputStream(payload), NbtAccounter.unlimitedHeap());
                long current = record.getLong("sequence");
                int version = record.getInt("version");
                if ((version < 1 || version > 3) || current <= 0 || previous >= 0 && current != previous + 1)
                    throw new IOException("Out-of-order financial journal: " + journal);
                previous = current;
                if (record.getBoolean("base")) {
                    if (input.getFilePointer() != length + 48L || current > checkpointSequence)
                        throw new IOException("Missing financial checkpoint for journal base: " + journal);
                    if (version >= 2 && current == checkpointSequence) authentication = authenticate(state, authentication, version);
                    if (current == checkpointSequence && !MessageDigest.isEqual(record.getByteArray("after"), version == 1 ? hash(state) : authentication.hash()))
                        throw new IOException("Financial journal base checksum mismatch: " + journal);
                    if (current == checkpointSequence) checkpointMatched = true;
                    continue;
                }
                if (current <= checkpointSequence) {
                    if (version >= 2 && current == checkpointSequence) authentication = authenticate(state, authentication, version);
                    if (current == checkpointSequence && !MessageDigest.isEqual(record.getByteArray("after"), version == 1 ? hash(state) : authentication.hash()))
                        throw new IOException("Financial checkpoint/journal mismatch: " + journal);
                    if (current == checkpointSequence) checkpointMatched = true;
                    continue;
                }
                if (version >= 2) authentication = authenticate(state, authentication, version);
                if (current != sequence + 1 || !MessageDigest.isEqual(record.getByteArray("before"), version == 1 ? hash(state) : authentication.hash()))
                    throw new IOException("Missing or mismatched financial journal base: " + journal);
                Tag restored;
                try { restored = apply(state, record.getCompound("patch")); }
                catch (RuntimeException invalid) { throw new IOException("Invalid financial journal patch: " + journal, invalid); }
                if (!(restored instanceof CompoundTag next)) throw new IOException("Invalid financial journal root: " + journal);
                if (version >= 2) authentication = authenticate(next, authentication, version);
                if (!MessageDigest.isEqual(record.getByteArray("after"), version == 1 ? hash(next) : authentication.hash()))
                    throw new IOException("Financial journal result mismatch: " + journal);
                state = next; sequence = current;
            }
        }
        if (!checkpointMatched) throw new IOException("Financial journal does not authenticate its checkpoint: " + journal);
        return new Recovery(state, sequence, torn);
    }
    private static FinancialDigest.Node authenticate(Tag state, FinancialDigest.Node previous, int version) throws IOException {
        return version == 2 ? FinancialDigest.tree(state, previous) : FinancialDigest.tree3(state, previous);
    }

    /** For verification/export only. Don't read this while the writer is changing it. */
    public static CompoundTag readLatest(Path file) throws IOException { return read(file).state; }

    /**
     * Run before loading SavedData. Check all journals before changing any files.
     * Corrupt finished records stop startup; only an unfinished final append can be dropped.
     */
    public static void recoverDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return;
        Map<Path, Recovery> recovered = new LinkedHashMap<>();
        try (var files = Files.list(directory)) {
            for (Path candidate : files.filter(p -> p.getFileName().toString().startsWith("kuronomics_")
                    && (p.getFileName().toString().endsWith(".dat.journal") || p.getFileName().toString().endsWith(".dat"))).sorted().toList()) {
                String name = candidate.getFileName().toString();
                Path file = name.endsWith(".journal") ? candidate.resolveSibling(name.substring(0, name.length() - 8)) : candidate;
                if (!recovered.containsKey(file)) recovered.put(file, read(file));
            }
        }
        for (var entry : recovered.entrySet()) {
            Recovery value = entry.getValue();
            if (value.torn) com.mojang.logging.LogUtils.getLogger().warn("Discarding incomplete final financial journal append for {}", entry.getKey());
            if (value.sequence > 0) checkpoint(entry.getKey(), value.state, value.sequence);
            else if (value.torn) {
                try (FileChannel channel = FileChannel.open(journal(entry.getKey()), StandardOpenOption.WRITE)) { channel.truncate(0); channel.force(true); }
            }
        }
    }

    // Patches keep list order and unknown mod/component fields.
    // They store changed records, not payment commands, so replay can't charge or pay twice.
    static CompoundTag diff(Tag before, Tag after) {
        if (before == after) return null;
        CompoundTag patch = new CompoundTag();
        if (before instanceof CompoundTag a && after instanceof CompoundTag b) {
            patch.putByte("op", (byte) 1); CompoundTag changes = new CompoundTag(); ListTag removed = new ListTag();
            for (String key : a.getAllKeys()) if (!b.contains(key)) removed.add(StringTag.valueOf(key));
            for (String key : b.getAllKeys()) { var change = diff(a.get(key), b.get(key)); if (change != null) changes.put(key, change); }
            if (changes.isEmpty() && removed.isEmpty()) return null;
            patch.put("changes", changes); patch.put("removed", removed);
        } else if (before instanceof ListTag a && after instanceof ListTag b && a.getElementType() == b.getElementType()) {
            CompoundTag retained = retainedRange(a, b); if (retained != null) return retained;
            int prefix = 0, suffix = 0;
            // Skip unchanged cached records by identity. Don't walk them recursively just to check equality.
            while (prefix < Math.min(a.size(), b.size()) && a.get(prefix) == b.get(prefix)) prefix++;
            while (suffix < Math.min(a.size(), b.size()) - prefix && a.get(a.size() - 1 - suffix) == b.get(b.size() - 1 - suffix)) suffix++;
            // Find insertions and removals before allocating edits for every shifted record.
            if (a.size() != b.size()) {
                // API snapshots may not share cached nodes. Keep splices compact in that case too.
                while (prefix < Math.min(a.size(), b.size()) - suffix && a.get(prefix).equals(b.get(prefix))) prefix++;
                while (suffix < Math.min(a.size(), b.size()) - prefix && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) suffix++;
                patch.putByte("op", (byte) 3); patch.putInt("prefix", prefix); patch.putInt("suffix", suffix);
                ListTag middle = new ListTag(); for (int i = prefix; i < b.size() - suffix; i++) middle.add(b.get(i)); patch.put("middle", middle);
            } else {
                patch.putByte("op", (byte) 2); patch.putInt("size", b.size()); CompoundTag changes = new CompoundTag();
                for (int i = prefix; i < b.size() - suffix; i++) { var change = diff(a.get(i), b.get(i)); if (change != null) changes.put(Integer.toString(i), change); }
                if (changes.isEmpty()) return null;
                patch.put("changes", changes);
            }
        } else if (before instanceof LongArrayTag a && after instanceof LongArrayTag b) {
            if (a.equals(b)) return null;
            long[] old = a.getAsLongArray(), next = b.getAsLongArray(); int prefix = 0, suffix = 0;
            while (prefix < Math.min(old.length, next.length) && old[prefix] == next[prefix]) prefix++;
            while (suffix < Math.min(old.length, next.length) - prefix && old[old.length - 1 - suffix] == next[next.length - 1 - suffix]) suffix++;
            patch.putByte("op", (byte) 4); patch.putInt("prefix", prefix); patch.putInt("suffix", suffix);
            patch.putLongArray("middle", Arrays.copyOfRange(next, prefix, next.length - suffix));
        } else { if (Objects.equals(before, after)) return null; patch.putByte("op", (byte) 0); patch.put("value", after); }
        return patch;
    }
    /** Keep the unchanged interval when rotating a queue, instead of editing every shifted position. */
    private static CompoundTag retainedRange(ListTag old, ListTag next) {
        if (old.isEmpty() || next.isEmpty() || old.get(0) == next.get(0)) return null;
        int start = -1, nextStart = 0;
        for (int i = 1; i < old.size(); i++) if (old.get(i) == next.get(0)) { start = i; break; }
        if (start < 0) for (int i = 1; i < next.size(); i++) if (next.get(i) == old.get(0)) { start = 0; nextStart = i; break; }
        if (start < 0) return null;
        int length = 0;
        while (start + length < old.size() && nextStart + length < next.size() && old.get(start + length) == next.get(nextStart + length)) length++;
        if (length <= Math.max(old.size(), next.size()) / 2) return null;
        ListTag prefix = new ListTag(), suffix = new ListTag();
        for (int i = 0; i < nextStart; i++) prefix.add(next.get(i));
        for (int i = nextStart + length; i < next.size(); i++) suffix.add(next.get(i));
        CompoundTag patch = new CompoundTag(); patch.putByte("op", (byte) 5); patch.putInt("start", start); patch.putInt("length", length);
        patch.put("prefix", prefix); patch.put("suffix", suffix); return patch;
    }
    private static Tag apply(Tag before, CompoundTag patch) throws IOException {
        switch (patch.getByte("op")) {
            case 0: { Tag value = patch.get("value"); if (value == null) throw new IOException("Missing replacement"); return value; }
            case 1: {
                if (!(before instanceof CompoundTag original)) throw new IOException("Invalid compound patch base");
                CompoundTag value = new CompoundTag(), changes = patch.getCompound("changes");
                original.getAllKeys().forEach(key -> value.put(key, original.get(key)));
                for (Tag key : patch.getList("removed", Tag.TAG_STRING)) value.remove(key.getAsString());
                for (String key : changes.getAllKeys()) value.put(key, apply(value.get(key), changes.getCompound(key)));
                return value;
            }
            case 2: {
                if (!(before instanceof ListTag original)) throw new IOException("Invalid list patch base");
                int size = patch.getInt("size"); if (size < 0 || size > MAX_RECORD) throw new IOException("Invalid list size");
                ListTag value = new ListTag(); CompoundTag changes = patch.getCompound("changes");
                for (int i = 0; i < size; i++) {
                    Tag old = i < original.size() ? original.get(i) : null; String key = Integer.toString(i);
                    Tag item = changes.contains(key) ? apply(old, changes.getCompound(key)) : old;
                    if (item == null || !value.add(item)) throw new IOException("Invalid list patch item");
                }
                return value;
            }
            case 3: {
                if (!(before instanceof ListTag original)) throw new IOException("Invalid list splice base");
                int prefix = patch.getInt("prefix"), suffix = patch.getInt("suffix"); validateSplice(prefix, suffix, original.size());
                if (!(patch.get("middle") instanceof ListTag middle)) throw new IOException("Invalid list splice");
                ListTag value = new ListTag();
                for (int i = 0; i < prefix; i++) value.add(original.get(i));
                for (Tag item : middle) if (!value.add(item)) throw new IOException("Invalid list splice type");
                for (int i = original.size() - suffix; i < original.size(); i++) if (!value.add(original.get(i))) throw new IOException("Invalid list splice type");
                return value;
            }
            case 4: {
                if (!(before instanceof LongArrayTag original)) throw new IOException("Invalid array splice base");
                long[] old = original.getAsLongArray(), middle = patch.getLongArray("middle");
                int prefix = patch.getInt("prefix"), suffix = patch.getInt("suffix"); validateSplice(prefix, suffix, old.length);
                long[] next = new long[Math.addExact(Math.addExact(prefix, suffix), middle.length)];
                System.arraycopy(old, 0, next, 0, prefix); System.arraycopy(middle, 0, next, prefix, middle.length);
                System.arraycopy(old, old.length - suffix, next, prefix + middle.length, suffix); return new LongArrayTag(next);
            }
            case 5: {
                if (!(before instanceof ListTag original)) throw new IOException("Invalid retained list base");
                int start = patch.getInt("start"), length = patch.getInt("length");
                if (start < 0 || length < 0 || (long) start + length > original.size()
                        || !(patch.get("prefix") instanceof ListTag prefix) || !(patch.get("suffix") instanceof ListTag suffix))
                    throw new IOException("Invalid retained list interval");
                ListTag value = new ListTag();
                for (Tag item : prefix) if (!value.add(item)) throw new IOException("Invalid retained list type");
                for (int i = start; i < start + length; i++) if (!value.add(original.get(i))) throw new IOException("Invalid retained list type");
                for (Tag item : suffix) if (!value.add(item)) throw new IOException("Invalid retained list type");
                return value;
            }
            default: throw new IOException("Unknown financial patch operation");
        }
    }
    private static void validateSplice(int prefix, int suffix, int size) throws IOException {
        if (prefix < 0 || suffix < 0 || (long) prefix + suffix > size) throw new IOException("Invalid financial splice bounds");
    }
    private static byte[] digest(byte[] bytes) { return sha256().digest(bytes); }
    private static final class CanonicalHasher {
        final MessageDigest digest = sha256();
        // Hash blocks of bytes instead of making thousands of tiny writes for each record.
        // Flush before digest/reset so the bytes and existing journal hashes stay exactly the same.
        final DataOutputStream output = new DataOutputStream(new BufferedOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest)));
        byte[] hash(Tag tag) throws IOException {
            digest.reset();
            try { canonical(tag, output); } finally { output.flush(); }
            return digest.digest();
        }
    }
    private static final ThreadLocal<CanonicalHasher> CANONICAL_HASHER = ThreadLocal.withInitial(CanonicalHasher::new);
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    static byte[] hash(Tag tag) throws IOException {
        return CANONICAL_HASHER.get().hash(tag);
    }
    private static void canonical(Tag tag, DataOutput output) throws IOException {
        output.writeByte(tag.getId());
        if (tag instanceof CompoundTag compound) {
            String[] keys = compound.getAllKeys().toArray(String[]::new); Arrays.sort(keys); output.writeInt(keys.length);
            for (String key : keys) { output.writeUTF(key); canonical(compound.get(key), output); }
        } else if (tag instanceof ListTag list) {
            output.writeInt(list.size()); for (Tag item : list) canonical(item, output);
        } else tag.write(output);
    }
}
