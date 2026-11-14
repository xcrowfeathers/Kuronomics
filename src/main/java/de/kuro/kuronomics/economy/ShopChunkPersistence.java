package de.kuro.kuronomics.economy;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkDataEvent;

/** Save stock and its recovery marker in the same chunk, with the usual mod chunk-save hooks. */
public final class ShopChunkPersistence {
    public static void save(ServerLevel level, BlockPos position) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Shop checkpoint off server thread");
        FinancialWriter.await(level.getServer()); // Save the prepared journal before removing physical stock.
        var chunk = level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4);
        if (chunk == null) throw new IllegalStateException("The shop chunk is no longer loaded");
        var snapshot = ChunkSerializer.write(level, chunk);
        NeoForge.EVENT_BUS.post(new ChunkDataEvent.Save(chunk, level, snapshot));
        level.getChunkSource().chunkMap.write(chunk.getPos(), snapshot).join();
        // Leave the chunk dirty flag alone. Vanilla autosave still needs it for the full chunk/POI save.
    }
    private ShopChunkPersistence() {}
}
