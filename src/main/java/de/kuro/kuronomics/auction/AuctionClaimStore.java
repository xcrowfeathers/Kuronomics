package de.kuro.kuronomics.auction;

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

public final class AuctionClaimStore extends de.kuro.kuronomics.economy.data.FinancialSavedData {
    private static final String DATA_NAME = Kuronomics.MODID + "_auction_claims";
    private final Map<UUID, AuctionClaim> claims = new LinkedHashMap<>();
    private final Set<UUID> completed = new LinkedHashSet<>();

    public static AuctionClaimStore get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Auction claims accessed off server thread");
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(AuctionClaimStore::new, AuctionClaimStore::load), DATA_NAME);
    }
    public static void validate(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(DATA_NAME + ".dat");
        if (!Files.exists(file)) return;
        try { load(NbtIo.readCompressed(file, NbtAccounter.create(64 * 1024 * 1024)).getCompound("data"), server.registryAccess()); }
        catch (IOException | RuntimeException error) { throw new IllegalStateException("Auction claim data is unreadable; restore a backup", error); }
    }
    AuctionClaim claim(UUID id) { return claims.get(id); }
    boolean completed(UUID id) { return completed.contains(id); }
    public List<AuctionClaim> claims(UUID owner) { return claims.values().stream().filter(c -> c.ownerId().equals(owner)).toList(); }
    boolean add(AuctionClaim claim) {
        if (completed.contains(claim.id())) return false;
        AuctionClaim old = claims.putIfAbsent(claim.id(), claim);
        if (old != null && !old.equals(claim)) throw new IllegalArgumentException("Auction claim ID was already used");
        if (old == null) setDirty(); return old == null;
    }
    boolean complete(UUID id, UUID owner) {
        AuctionClaim claim = claims.get(Objects.requireNonNull(id));
        if (claim == null) return completed.contains(id);
        if (!claim.ownerId().equals(owner)) throw new IllegalArgumentException("Auction claim belongs to another player");
        claims.remove(id); completed.add(id); setDirty(); return true;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", 3); ListTag values = new ListTag();
        for (AuctionClaim claim : claims.values()) {
            CompoundTag value = new CompoundTag(); value.putUUID("id", claim.id()); value.putUUID("auction", claim.auctionId()); value.putUUID("owner", claim.ownerId());
            value.putString("reason", claim.reason().name()); value.putLong("readyAt", claim.readyAt()); value.putString("assetType", claim.asset().type().name());
            if (claim.asset() instanceof AuctionAsset.ItemAsset item) value.put("item", item.stack().save(registries));
            else if (claim.asset() instanceof AuctionAsset.PropertyAsset property) { value.putString("property", property.propertyId()); value.putString("dimension", property.dimension()); value.putString("region", property.regionKey()); }
            values.add(value);
        }
        tag.put("claims", values); ListTag completedTags = new ListTag();
        completed.forEach(id -> completedTags.add(NbtUtils.createUUID(id))); tag.put("completed", completedTags); return tag;
    }
    public static AuctionClaimStore load(CompoundTag tag, HolderLookup.Provider registries) {
        int version = tag.getInt("version");
        if ((version < 1 || version > 3) || !tag.contains("claims", Tag.TAG_LIST)
                || version >= 2 && !tag.contains("completed", Tag.TAG_LIST)) throw new IllegalStateException("Unsupported auction claim data");
        AuctionClaimStore store = new AuctionClaimStore();
        for (Tag raw : tag.getList("claims", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) raw;
            try {
                AuctionAsset asset = switch (AuctionAsset.Type.valueOf(value.getString("assetType"))) {
                    case ITEM -> new AuctionAsset.ItemAsset(ItemStack.parse(registries, value.get("item")).orElseThrow());
                    case PROPERTY -> new AuctionAsset.PropertyAsset(version >= 3 ? value.getString("property")
                            : value.getString("dimension") + "|" + value.getString("region"), value.getString("dimension"), value.getString("region"));
                };
                AuctionClaim claim = new AuctionClaim(value.getUUID("id"), value.getUUID("auction"), value.getUUID("owner"), asset,
                        AuctionClaim.Reason.valueOf(value.getString("reason")), value.getLong("readyAt"));
                if (store.claims.putIfAbsent(claim.id(), claim) != null) throw new IllegalStateException("Duplicate auction claim");
            } catch (RuntimeException error) { throw new IllegalStateException("Invalid auction claim", error); }
        }
        if (version >= 2) for (Tag raw : tag.getList("completed", Tag.TAG_INT_ARRAY)) {
            UUID id = NbtUtils.loadUUID(raw);
            if (!store.completed.add(id) || store.claims.containsKey(id)) throw new IllegalStateException("Duplicate completed auction claim");
        }
        store.setDirty(false); return store;
    }
}
