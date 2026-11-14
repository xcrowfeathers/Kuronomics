package de.kuro.kuronomics.jobs;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import de.kuro.kuronomics.integration.PropertyIntegrations;

public final class WorkZones extends de.kuro.kuronomics.economy.data.FinancialSavedData {
    public static final String UNAVAILABLE = "WorldGuard is not installed; WorkZone constraints are unavailable.";
    public record Zone(String name, String dimension, String region, String binding) {}
    private final Map<String, Zone> zones = new TreeMap<>();
    public static WorkZones get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(WorkZones::new, WorkZones::load), "kuronomics_workzones");
    }
    public Collection<Zone> all() { return List.copyOf(zones.values()); }
    public Zone require(String name) {
        Zone zone = zones.get(name); if (zone == null) throw new IllegalArgumentException("Unknown WorkZone '" + name + "'"); return zone;
    }
    public void validate(MinecraftServer server, String name) {
        Zone zone = require(name);
        var level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.ResourceLocation.parse(zone.dimension())));
        if (level == null) throw new IllegalArgumentException("WorkZone '" + name + "': dimension unavailable");
        var region = PropertyIntegrations.get().require(level, zone.region());
        if (!region.binding().equals(zone.binding())) throw new IllegalArgumentException("WorkZone '" + name + "': region geometry changed; recreate its mapping");
    }
    public void create(ServerLevel level, String name, String region) {
        if (!name.matches("[a-z0-9_][a-z0-9_.-]*")) throw new IllegalArgumentException("Use a lowercase WorkZone name without spaces.");
        var integration = PropertyIntegrations.get(); if (!integration.available()) throw new IllegalStateException(UNAVAILABLE);
        if (zones.containsKey(name)) throw new IllegalArgumentException("WorkZone already exists: " + name);
        var found = integration.require(level, region);
        zones.put(name, new Zone(name, found.dimension(), found.name(), found.binding())); setDirty();
    }
    public void delete(String name) { require(name); zones.remove(name); setDirty(); }
    public boolean contains(ServerLevel level, BlockPos pos, List<String> names) {
        return matches(level, names, zone -> PropertyIntegrations.get().containsRegion(level, zone.region(), zone.binding(), pos));
    }
    public boolean matches(ServerLevel level, List<String> names, java.util.function.Predicate<Zone> contains) {
        if (!PropertyIntegrations.get().available()) return false;
        String dimension = level.dimension().location().toString();
        for (String name : names) {
            Zone zone = zones.get(name);
            if (zone != null && zone.dimension().equals(dimension)
                    && contains.test(zone)) return true;
        }
        return false;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) {
        ListTag list = new ListTag(); for (Zone z : zones.values()) {
            CompoundTag n = new CompoundTag(); n.putString("name", z.name()); n.putString("dimension", z.dimension()); n.putString("region", z.region()); n.putString("binding", z.binding()); list.add(n);
        } tag.put("zones", list); return tag;
    }
    private static WorkZones load(CompoundTag tag, HolderLookup.Provider lookup) {
        WorkZones store = new WorkZones(); for (Tag raw : tag.getList("zones", Tag.TAG_COMPOUND)) {
            CompoundTag n = (CompoundTag) raw; var z = new Zone(n.getString("name"), n.getString("dimension"), n.getString("region"), n.getString("binding")); store.zones.put(z.name(), z);
        } return store;
    }
}
