package de.kuro.kuronomics.terminal;

import java.util.Optional;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/** Find terminals only when requested. Don't load chunks or keep a ticking index. */
public final class TerminalLocator {
    private TerminalLocator() {}

    public record Located<T>(BlockPos position, T terminal, double distanceSquared) {}

    /**
     * Search loaded chunks in the axis-aligned range around {@code center}.
     * Return null from the mapper for block entities that aren't the requested terminal type.
     */
    public static <T> Optional<Located<T>> nearest(ServerLevel level, Vec3 origin, BlockPos center, int range,
                                                    Function<BlockEntity, T> mapper) {
        if (range < 0) throw new IllegalArgumentException("Terminal range cannot be negative");
        int minX = Math.subtractExact(center.getX(), range);
        int maxX = Math.addExact(center.getX(), range);
        int minY = Math.max(level.getMinBuildHeight(), Math.subtractExact(center.getY(), range));
        int maxY = Math.min(level.getMaxBuildHeight() - 1, Math.addExact(center.getY(), range));
        int minZ = Math.subtractExact(center.getZ(), range);
        int maxZ = Math.addExact(center.getZ(), range);
        Located<T> nearest = null;

        for (int chunkX = Math.floorDiv(minX, 16); chunkX <= Math.floorDiv(maxX, 16); chunkX++) {
            for (int chunkZ = Math.floorDiv(minZ, 16); chunkZ <= Math.floorDiv(maxZ, 16); chunkZ++) {
                var chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) continue;
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    BlockPos pos = blockEntity.getBlockPos();
                    if (pos.getX() < minX || pos.getX() > maxX || pos.getY() < minY || pos.getY() > maxY
                            || pos.getZ() < minZ || pos.getZ() > maxZ || blockEntity.isRemoved()
                            || blockEntity.getLevel() != level || level.getBlockEntity(pos) != blockEntity) continue;
                    T terminal = mapper.apply(blockEntity);
                    if (terminal == null) continue;
                    double distance = origin.distanceToSqr(Vec3.atCenterOf(pos));
                    int distanceOrder = nearest == null ? -1 : Double.compare(distance, nearest.distanceSquared());
                    if (nearest == null || distanceOrder < 0 || distanceOrder == 0 && compare(pos, nearest.position()) < 0)
                        nearest = new Located<>(pos.immutable(), terminal, distance);
                }
            }
        }
        return Optional.ofNullable(nearest);
    }

    private static int compare(BlockPos first, BlockPos second) {
        int x = Integer.compare(first.getX(), second.getX());
        if (x != 0) return x;
        int y = Integer.compare(first.getY(), second.getY());
        return y != 0 ? y : Integer.compare(first.getZ(), second.getZ());
    }
}
