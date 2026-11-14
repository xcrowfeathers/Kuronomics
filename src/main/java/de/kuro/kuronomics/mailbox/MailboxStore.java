package de.kuro.kuronomics.mailbox;

import de.kuro.kuronomics.Kuronomics;
import java.util.*;
import java.io.IOException;
import java.nio.file.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

/** Save entries oldest first. Reverse them when building the inbox view. */
public final class MailboxStore extends de.kuro.kuronomics.economy.data.FinancialSavedData {
    public static final int MAX_ENTRIES = 16;
    private final Map<UUID, ArrayDeque<MailboxEntry>> mailboxes = new HashMap<>();
    private final Map<UUID, String> knownNames = new HashMap<>();
    private final Map<String, Set<UUID>> nameIndex = new HashMap<>();
    private final Set<UUID> pendingJobNotices = new HashSet<>();
    public boolean hasJobNotice(UUID player) { return pendingJobNotices.contains(player); }
    public void jobNotice(UUID player, boolean pending) {
        if (pending ? pendingJobNotices.add(player) : pendingJobNotices.remove(player)) setDirty();
    }

    public static MailboxStore get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Mailbox accessed off server thread");
        return server.overworld().getDataStorage().computeIfAbsent(
                new Factory<>(MailboxStore::new, MailboxStore::load), Kuronomics.MODID + "_mailbox");
    }

    /** Stop startup on corrupt financial messages instead of silently replacing them with an empty inbox. */
    public static void validate(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(Kuronomics.MODID + "_mailbox.dat");
        if (!Files.exists(file)) return;
        try { load(NbtIo.readCompressed(file, NbtAccounter.create(64 * 1024 * 1024)).getCompound("data"), server.registryAccess()); }
        catch (IOException | RuntimeException error) { throw new IllegalStateException("Mailbox data is unreadable; restore a backup before starting this world", error); }
    }

    public boolean add(UUID recipient, MailboxEntry entry) {
        Objects.requireNonNull(recipient); Objects.requireNonNull(entry);
        ArrayDeque<MailboxEntry> mailbox = mailboxes.computeIfAbsent(recipient, ignored -> new ArrayDeque<>());
        if (mailbox.stream().anyMatch(existing -> existing.id().equals(entry.id()))) return false;
        mailbox.addLast(entry);
        while (mailbox.size() > MAX_ENTRIES) mailbox.removeFirst();
        setDirty(); return true;
    }

    public List<MailboxEntry> newest(UUID recipient) {
        ArrayDeque<MailboxEntry> mailbox = mailboxes.get(recipient);
        if (mailbox == null) return List.of();
        List<MailboxEntry> result = new ArrayList<>(mailbox); Collections.reverse(result); return List.copyOf(result);
    }

    public int size(UUID recipient) { return mailboxes.getOrDefault(recipient, new ArrayDeque<>()).size(); }
    public void remember(UUID player, String name) {
        Objects.requireNonNull(player); name = Objects.requireNonNull(name).strip();
        if (name.isEmpty() || name.length() > 64) return;
        String previous = knownNames.put(player, name);
        if (!name.equals(previous)) {
            if (previous != null) { String key = previous.toLowerCase(Locale.ROOT); var ids = nameIndex.get(key); ids.remove(player); if (ids.isEmpty()) nameIndex.remove(key); }
            nameIndex.computeIfAbsent(name.toLowerCase(Locale.ROOT), ignored -> new HashSet<>()).add(player); setDirty();
        }
    }
    public UUID resolveKnown(String name) {
        var ids = nameIndex.get(name.toLowerCase(Locale.ROOT));
        if (ids == null || ids.size() != 1) throw new IllegalArgumentException("Player not found or name is ambiguous. Use their UUID.");
        return ids.iterator().next();
    }
    public String knownName(UUID player) { return knownNames.get(player); }
    public Map<UUID, String> knownPlayers() { return Map.copyOf(knownNames); }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag jobNotices = new ListTag(); pendingJobNotices.forEach(id -> jobNotices.add(net.minecraft.nbt.NbtUtils.createUUID(id))); tag.put("jobNotices", jobNotices);
        tag.putInt("version", 1); ListTag players = new ListTag();
        mailboxes.forEach((recipient, mailbox) -> {
            CompoundTag player = new CompoundTag(); player.putUUID("recipient", recipient); ListTag entries = new ListTag();
            mailbox.forEach(entry -> entries.add(saveEntry(entry))); player.put("entries", entries); players.add(player);
        });
        tag.put("mailboxes", players); ListTag names = new ListTag();
        knownNames.forEach((id, name) -> { CompoundTag item = new CompoundTag(); item.putUUID("id", id); item.putString("name", name); names.add(item); });
        tag.put("names", names); return tag;
    }

    private static CompoundTag saveEntry(MailboxEntry entry) {
        CompoundTag tag = new CompoundTag(); tag.putUUID("id", entry.id()); tag.putString("type", entry.type().name());
        tag.putLong("createdAt", entry.createdAt());
        if (entry.amount() != null) tag.putLong("amount", entry.amount());
        if (entry.balance() != null) tag.putLong("balance", entry.balance());
        if (entry.otherPlayer() != null) tag.putUUID("otherPlayer", entry.otherPlayer());
        tag.putString("subject", entry.subject()); ListTag details = new ListTag();
        for (MailboxDetail detail : entry.details()) {
            CompoundTag item = new CompoundTag(); item.putString("kind", detail.kind().name()); item.putString("label", detail.label());
            if (detail.amount() != null) item.putLong("amount", detail.amount()); details.add(item);
        }
        tag.put("details", details); return tag;
    }

    public static MailboxStore load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.getInt("version") != 1 || !tag.contains("mailboxes", Tag.TAG_LIST))
            throw new IllegalStateException("Unsupported mailbox data");
        MailboxStore store = new MailboxStore();
        for (Tag rawPlayer : tag.getList("mailboxes", Tag.TAG_COMPOUND)) {
            CompoundTag player = (CompoundTag) rawPlayer;
            if (!player.hasUUID("recipient") || !player.contains("entries", Tag.TAG_LIST)) throw new IllegalStateException("Invalid mailbox owner");
            UUID recipient = player.getUUID("recipient");
            if (store.mailboxes.containsKey(recipient)) throw new IllegalStateException("Duplicate mailbox owner");
            ArrayDeque<MailboxEntry> entries = new ArrayDeque<>(); Set<UUID> ids = new HashSet<>();
            for (Tag rawEntry : player.getList("entries", Tag.TAG_COMPOUND)) {
                MailboxEntry entry = loadEntry((CompoundTag) rawEntry);
                if (!ids.add(entry.id())) throw new IllegalStateException("Duplicate mailbox event");
                entries.addLast(entry);
            }
            if (entries.size() > MAX_ENTRIES) throw new IllegalStateException("Mailbox exceeds entry limit");
            if (!entries.isEmpty()) store.mailboxes.put(recipient, entries);
        }
        if (tag.contains("names", Tag.TAG_LIST)) for (Tag raw : tag.getList("names", Tag.TAG_COMPOUND)) {
            CompoundTag item = (CompoundTag) raw;
            if (!item.hasUUID("id") || item.getString("name").isBlank() || item.getString("name").length() > 64)
                throw new IllegalStateException("Invalid mailbox player name");
            if (store.knownNames.containsKey(item.getUUID("id"))) throw new IllegalStateException("Duplicate mailbox player name");
            store.remember(item.getUUID("id"), item.getString("name"));
        }
        if (tag.contains("jobNotices")) {
            if (!(tag.get("jobNotices") instanceof ListTag notices)) throw new IllegalStateException("Invalid pending job notices");
            for (Tag notice : notices) if (!store.pendingJobNotices.add(net.minecraft.nbt.NbtUtils.loadUUID(notice))) throw new IllegalStateException("Duplicate pending job notice");
        }
        store.setDirty(false); return store;
    }

    private static MailboxEntry loadEntry(CompoundTag tag) {
        if (!tag.hasUUID("id") || !tag.contains("createdAt", Tag.TAG_LONG) || !tag.contains("details", Tag.TAG_LIST))
            throw new IllegalStateException("Invalid mailbox event");
        MailboxEventType type;
        try { type = MailboxEventType.valueOf(tag.getString("type")); }
        catch (IllegalArgumentException invalid) { throw new IllegalStateException("Unknown mailbox event type", invalid); }
        List<MailboxDetail> details = new ArrayList<>();
        for (Tag raw : tag.getList("details", Tag.TAG_COMPOUND)) {
            CompoundTag item = (CompoundTag) raw;
            try { details.add(new MailboxDetail(MailboxDetail.Kind.valueOf(item.getString("kind")), item.getString("label"), item.contains("amount", Tag.TAG_LONG) ? item.getLong("amount") : null)); }
            catch (IllegalArgumentException invalid) { throw new IllegalStateException("Invalid mailbox detail", invalid); }
        }
        return new MailboxEntry(tag.getUUID("id"), type, tag.getLong("createdAt"),
                tag.contains("amount", Tag.TAG_LONG) ? tag.getLong("amount") : null,
                tag.contains("balance", Tag.TAG_LONG) ? tag.getLong("balance") : null,
                tag.hasUUID("otherPlayer") ? tag.getUUID("otherPlayer") : null, tag.getString("subject"), details);
    }
}
