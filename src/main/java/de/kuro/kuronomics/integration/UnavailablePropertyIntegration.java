package de.kuro.kuronomics.integration;

import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

final class UnavailablePropertyIntegration implements PropertyIntegration {
    @Override public boolean available() { return false; }
    @Override public Optional<Region> find(ServerLevel level, String regionName) { return Optional.empty(); }
    @Override public Optional<Region> at(ServerPlayer player) { return Optional.empty(); }
    @Override public void replaceOwner(ServerLevel level, String regionName, UUID owner, UUID formerTenant) {
        throw new IllegalStateException(REQUIRED_MESSAGE);
    }
    @Override public void setResident(ServerLevel level, String regionName, UUID player, boolean present) {
        throw new IllegalStateException(REQUIRED_MESSAGE);
    }
}
