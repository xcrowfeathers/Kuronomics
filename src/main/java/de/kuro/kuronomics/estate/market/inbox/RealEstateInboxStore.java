package de.kuro.kuronomics.estate.market.inbox;

import de.kuro.kuronomics.Kuronomics;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.LevelResource;

public final class RealEstateInboxStore extends de.kuro.kuronomics.economy.data.FinancialSavedData {
    public static final int MAX_ENTRIES = 32;
    private static final String DATA_NAME = Kuronomics.MODID + "_real_estate_inbox";
    private final Map<UUID, ArrayDeque<RealEstateInboxEntry>> entries = new HashMap<>();

    public static RealEstateInboxStore get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Real Estate inbox accessed off server thread");
        return server.overworld().getDataStorage().computeIfAbsent(
                new Factory<>(RealEstateInboxStore::new, RealEstateInboxStore::load), DATA_NAME);
    }
    public static void validate(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DATA_NAME + ".dat");
        if (!Files.exists(file)) return;
        try { load(NbtIo.readCompressed(file, NbtAccounter.create(32 * 1024 * 1024)).getCompound("data"), server.registryAccess()); }
        catch (IOException | RuntimeException error) {
            throw new IllegalStateException("Real Estate inbox data is unreadable; restore a backup before starting this world", error);
        }
    }
    boolean add(UUID recipient, RealEstateInboxEntry entry) {
        ArrayDeque<RealEstateInboxEntry> inbox = entries.computeIfAbsent(Objects.requireNonNull(recipient), ignored -> new ArrayDeque<>());
        if (inbox.stream().anyMatch(existing -> existing.id().equals(entry.id()))) return false;
        inbox.addLast(entry); while (inbox.size() > MAX_ENTRIES) inbox.removeFirst(); setDirty(); return true;
    }
    List<RealEstateInboxEntry> newest(UUID recipient) {
        ArrayDeque<RealEstateInboxEntry> inbox = entries.get(recipient); if (inbox == null) return List.of();
        List<RealEstateInboxEntry> result = new ArrayList<>(inbox); Collections.reverse(result); return List.copyOf(result);
    }
    int size(UUID recipient) { return entries.getOrDefault(recipient, new ArrayDeque<>()).size(); }

    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", 1); ListTag players = new ListTag();
        entries.forEach((recipient, inbox) -> {
            CompoundTag player = new CompoundTag(); player.putUUID("recipient", recipient); ListTag values = new ListTag();
            for (RealEstateInboxEntry entry : inbox) {
                CompoundTag value = new CompoundTag(); value.putUUID("id", entry.id()); value.putString("type", entry.type().name());
                value.putLong("createdAt", entry.createdAt()); value.putUUID("listing", entry.listingId());
                value.putString("listingName", entry.listingName()); if (entry.amount() != null) value.putLong("amount", entry.amount());
                if (entry.taxAmount() != null) value.putLong("taxAmount", entry.taxAmount());
                if (entry.netAmount() != null) value.putLong("netAmount", entry.netAmount());
                if (entry.otherPlayer() != null) value.putUUID("otherPlayer", entry.otherPlayer()); value.putString("message", entry.message());
                values.add(value);
            }
            player.put("entries", values); players.add(player);
        });
        tag.put("mailboxes", players); return tag;
    }
    public static RealEstateInboxStore load(CompoundTag tag, HolderLookup.Provider registries) {
        if (tag.getInt("version") != 1 || !tag.contains("mailboxes", Tag.TAG_LIST))
            throw new IllegalStateException("Unsupported Real Estate inbox data");
        RealEstateInboxStore store = new RealEstateInboxStore();
        for (Tag rawPlayer : tag.getList("mailboxes", Tag.TAG_COMPOUND)) {
            CompoundTag player = (CompoundTag) rawPlayer;
            if (!player.hasUUID("recipient") || !player.contains("entries", Tag.TAG_LIST))
                throw new IllegalStateException("Invalid Real Estate inbox owner");
            UUID recipient = player.getUUID("recipient"); ArrayDeque<RealEstateInboxEntry> inbox = new ArrayDeque<>();
            Set<UUID> ids = new HashSet<>();
            for (Tag raw : player.getList("entries", Tag.TAG_COMPOUND)) {
                CompoundTag value = (CompoundTag) raw;
                if (!value.hasUUID("id") || !value.hasUUID("listing"))
                    throw new IllegalStateException("Invalid Real Estate inbox event identity");
                RealEstateInboxEntry entry;
                try { entry = new RealEstateInboxEntry(value.getUUID("id"), RealEstateEventType.valueOf(value.getString("type")),
                        value.getLong("createdAt"), value.getUUID("listing"), value.getString("listingName"),
                        value.contains("amount", Tag.TAG_LONG) ? value.getLong("amount") : null,
                        value.contains("taxAmount", Tag.TAG_LONG) ? value.getLong("taxAmount") : null,
                        value.contains("netAmount", Tag.TAG_LONG) ? value.getLong("netAmount") : null,
                        value.hasUUID("otherPlayer") ? value.getUUID("otherPlayer") : null, value.getString("message")); }
                catch (RuntimeException error) { throw new IllegalStateException("Invalid Real Estate inbox event", error); }
                if (!ids.add(entry.id())) throw new IllegalStateException("Duplicate Real Estate inbox event"); inbox.addLast(entry);
            }
            if (inbox.size() > MAX_ENTRIES || store.entries.putIfAbsent(recipient, inbox) != null)
                throw new IllegalStateException("Invalid Real Estate inbox size or duplicate owner");
        }
        store.setDirty(false); return store;
    }
}
