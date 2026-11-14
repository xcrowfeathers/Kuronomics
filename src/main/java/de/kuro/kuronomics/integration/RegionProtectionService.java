package de.kuro.kuronomics.integration;

import de.kuro.kuronomics.economy.data.EconomyLedger;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class RegionProtectionService {
    public enum Capability { BREAK, PLACE, INTERACT, CONTAINER, ENTITY_INTERACT, ENTITY_DAMAGE }
    public enum Access { ALLOW, DENY, UNAVAILABLE }
    public record RegionId(String dimension, String region) {
        public RegionId { Objects.requireNonNull(dimension); Objects.requireNonNull(region); }
    }
    public boolean available() { return PropertyIntegrations.get().available(); }
    public Optional<PropertyIntegration.Region> resolve(ServerLevel level, RegionId id) {
        checkThread(level);
        if (!available() || !level.dimension().location().toString().equals(id.dimension())) return Optional.empty();
        return PropertyIntegrations.get().find(level, id.region());
    }
    public List<PropertyIntegration.Region> at(ServerLevel level, BlockPos pos) {
        checkThread(level);
        return available() ? PropertyIntegrations.get().at(level, pos) : List.of();
    }
    public boolean isRegistered(ServerLevel level, BlockPos pos) {
        var estates = EconomyLedger.get(level.getServer()).estates();
        return at(level, pos).stream().anyMatch(region -> estates.stream().anyMatch(estate ->
                estate.dimension().equals(region.dimension()) && estate.region().equals(region.name())
                        && estate.regionBinding().equals(region.binding())));
    }
    public Access access(ServerPlayer player, BlockPos pos, Capability capability) {
        checkThread(player.serverLevel());
        if (!available()) return Access.UNAVAILABLE;
        try { return PropertyIntegrations.get().access(player, pos, capability); }
        catch (IllegalStateException unavailable) { return Access.UNAVAILABLE; }
    }
    private static void checkThread(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Protection accessed off server thread");
    }
}
