package de.kuro.kuronomics.integration;

import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public interface PropertyIntegration {
    String REQUIRED_MESSAGE = "WorldGuard is not installed or unavailable";

    record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public Bounds {
            if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("Invalid property bounds");
        }
        public long width() { return (long) maxX - minX + 1; }
        public long length() { return (long) maxZ - minZ + 1; }
    }

    record Region(String name, String dimension, Optional<Bounds> bounds, int priority,
                  Map<UUID, String> owners, Map<UUID, String> residents, String geometry, boolean uuidDomains) {
        public Region {
            if (name == null || name.isBlank() || dimension == null || dimension.isBlank())
                throw new IllegalArgumentException("Invalid property region");
            bounds = Objects.requireNonNull(bounds); owners = Map.copyOf(owners); residents = Map.copyOf(residents);
        }
        public long width() { return bounds.map(Bounds::width).orElse(0L); }
        public long length() { return bounds.map(Bounds::length).orElse(0L); }
        public String binding() { return UUID.nameUUIDFromBytes((dimension + "|" + name + "|" + geometry).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(); }
    }

    boolean available();
    default Set<String> regionNames(ServerLevel level) { return Set.of(); }
    default boolean containsRegion(ServerLevel level, String id, String binding, net.minecraft.core.BlockPos pos) { return false; }
    Optional<Region> find(ServerLevel level, String regionName);
    default boolean sameRegion(ServerLevel first, String firstId, ServerLevel second, String secondId) {
        return first.dimension().equals(second.dimension()) && firstId.equalsIgnoreCase(secondId);
    }
    Optional<Region> at(ServerPlayer player);
    default List<Region> at(ServerLevel level, net.minecraft.core.BlockPos position) { return List.of(); }
    default RegionProtectionService.Access access(ServerPlayer player, net.minecraft.core.BlockPos position, RegionProtectionService.Capability capability) {
        return RegionProtectionService.Access.UNAVAILABLE;
    }
    default Map<UUID, String> owners(ServerLevel level, String regionName) { return require(level, regionName).owners(); }
    default Map<UUID, String> residents(ServerLevel level, String regionName) { return require(level, regionName).residents(); }
    default boolean isOwner(ServerLevel level, String regionName, UUID owner) { return owners(level, regionName).containsKey(owner); }
    void replaceOwner(ServerLevel level, String regionName, UUID owner, UUID formerTenant);
    void setResident(ServerLevel level, String regionName, UUID player, boolean present);
    /** Only change the requested UUIDs, and make retries safe. Keep flags and unmanaged access entries intact. */
    default void updateDomains(ServerLevel level, String regionName, Set<UUID> ownersToAdd, Set<UUID> ownersToRemove,
                                Set<UUID> membersToAdd, Set<UUID> membersToRemove) {
        throw new IllegalStateException("Property provider does not support Company access deltas");
    }

    default Region require(ServerLevel level, String regionName) {
        if (!available()) throw new IllegalStateException(REQUIRED_MESSAGE);
        return find(level, regionName).orElseThrow(() -> new IllegalArgumentException("WorldGuard region not found: " + regionName));
    }
}
