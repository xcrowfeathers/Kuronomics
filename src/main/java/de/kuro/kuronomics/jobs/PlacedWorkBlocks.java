package de.kuro.kuronomics.jobs;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Track placed blocks per dimension so natural-only tasks can tell them apart from generated blocks. */
public final class PlacedWorkBlocks extends SavedData {
    private final Set<Long> positions = new HashSet<>();
    public static PlacedWorkBlocks get(ServerLevel level) { return level.getDataStorage().computeIfAbsent(new Factory<>(PlacedWorkBlocks::new, PlacedWorkBlocks::load), "kuronomics_placed_work_blocks"); }
    public boolean contains(BlockPos pos) { return positions.contains(pos.asLong()); }
    public void placed(BlockPos pos) { if (positions.add(pos.asLong())) setDirty(); }
    public void removed(BlockPos pos) { if (positions.remove(pos.asLong())) setDirty(); }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider lookup) { tag.putLongArray("positions", positions.stream().mapToLong(Long::longValue).toArray()); return tag; }
    private static PlacedWorkBlocks load(CompoundTag tag, HolderLookup.Provider lookup) { var result = new PlacedWorkBlocks(); for (long pos : tag.getLongArray("positions")) result.positions.add(pos); return result; }
}
