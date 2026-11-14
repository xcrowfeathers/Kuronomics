package de.kuro.kuronomics.economy;

import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.economy.mixin.SavedDataAccess;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/** Financial checkpoints don't save unrelated mods' dirty data or block-placement tracking. */
public final class FinancialPersistence {
    public static void save(MinecraftServer server) {
        submit(server).join();
        FinancialWriter.check(server);
        de.kuro.kuronomics.economy.data.EconomyLedger.get(server).dispatchDurableEvents();
    }
    /** Take the snapshot on the server thread. Encode it and force it to disk on the ordered writer. */
    public static java.util.concurrent.CompletableFuture<Void> submit(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Financial save off server thread");
        FinancialWriter.check(server);
        var root = server.getWorldPath(LevelResource.ROOT).resolve("data");
        var data = ((SavedDataAccess) server.overworld().getDataStorage()).kuronomy$cachedData();
        // Save the money before completing journals that can drop their pending recovery state.
        var ledger = data.get(Kuronomics.MODID + "_economy");
        if (ledger != null && ledger.isDirty()) ledger.save(root.resolve(Kuronomics.MODID + "_economy.dat").toFile(), server.registryAccess());
        data.forEach((name, value) -> {
            if (value != null && value.isDirty() && name.startsWith(Kuronomics.MODID + "_")
                    && !(value instanceof de.kuro.kuronomics.jobs.PlacedWorkBlocks))
                value.save(root.resolve(name + ".dat").toFile(), server.registryAccess());
        });
        return FinancialWriter.completion(root);
    }
    private FinancialPersistence() {}
}
