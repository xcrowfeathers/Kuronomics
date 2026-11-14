package de.kuro.kuronomics.economy;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Keep Kuronomy autosaves and transaction writes in the same ordered queue.
 * If a write fails, stop later money work too; it can't skip the missing saved record.
 */
public final class FinancialWriter {
    private static final class Lane {
        final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "Kuronomy financial writer"); thread.setDaemon(true); return thread;
        });
        final ExecutorService checkpointExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "Kuronomy checkpoint preparation"); thread.setDaemon(true); return thread;
        });
        volatile CompletableFuture<Void> maintenance = CompletableFuture.completedFuture(null);
        final java.util.concurrent.atomic.AtomicInteger depth = new java.util.concurrent.atomic.AtomicInteger();
        CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
        final Map<Path, FinancialJournal> journals = new HashMap<>(); // Only accessed by the writer thread.
    }
    private static final Map<Path, Lane> lanes = new HashMap<>();
    public static synchronized void write(Path file, Supplier<CompoundTag> snapshot) {
        writeUpdate(file, () -> FinancialUpdate.between(null, snapshot.get()));
    }
    /** The supplier runs on the writer thread. Its returned tags belong to that writer and must stay unchanged. */
    public static synchronized void writeUpdate(Path file, Supplier<FinancialUpdate> update) {
        Path absolute = file.toAbsolutePath().normalize(), root = absolute.getParent();
        Lane lane = lanes.computeIfAbsent(root, ignored -> new Lane());
        CompletableFuture<Void> previous = lane.tail;
        if (previous.isCompletedExceptionally()) previous.join();
        checkMaintenance(lane);
        // Cap memory use for non-UI callers too. The schedulers should pause before getting this far.
        if (lane.depth.get() >= 32) throw new RejectedExecutionException("Financial writer is full; retry after pending writes settle");
        lane.depth.incrementAndGet();
        lane.tail = previous.thenRunAsync(() -> {
            try {
                checkMaintenance(lane);
                FinancialJournal journal = lane.journals.get(absolute);
                if (journal == null) { journal = new FinancialJournal(absolute); lane.journals.put(absolute, journal); }
                journal.appendOwned(update.get(), false);
                if (journal.needsCheckpoint() && lane.maintenance.isDone()) prepareCheckpoint(lane, journal);
            }
            catch (Exception failure) {
                com.mojang.logging.LogUtils.getLogger().error("Financial write failed; later checkpoints will not overtake it: {}", absolute, failure);
                throw new CompletionException("Could not save financial data " + absolute, failure);
            }
        }, lane.executor).whenComplete((ignored, failure) -> lane.depth.decrementAndGet());
    }
    private static void checkMaintenance(Lane lane) {
        if (lane.maintenance.isCompletedExceptionally()) lane.maintenance.join();
    }
    private static void prepareCheckpoint(Lane lane, FinancialJournal journal) {
        var captured = journal.captureCheckpoint();
        lane.maintenance = CompletableFuture.supplyAsync(() -> {
            try { return journal.prepareCheckpoint(captured); }
            catch (java.io.IOException failure) { throw new CompletionException("Financial checkpoint preparation failed", failure); }
        }, lane.checkpointExecutor).thenAcceptAsync(prepared -> {
            try { journal.publishCheckpoint(prepared); }
            catch (java.io.IOException failure) { throw new CompletionException("Financial checkpoint publication failed", failure); }
        }, lane.executor).whenComplete((ignored, failure) -> {
            if (failure != null) com.mojang.logging.LogUtils.getLogger().error("Financial checkpoint failed; journal evidence is retained and later financial work is blocked", failure);
        });
    }
    private static Path root(MinecraftServer server) { return server.getWorldPath(LevelResource.ROOT).resolve("data").toAbsolutePath().normalize(); }
    private static synchronized CompletableFuture<Void> pending(Path root) {
        Lane lane = lanes.get(root); return lane == null ? CompletableFuture.completedFuture(null) : lane.tail;
    }
    public static synchronized boolean busy(MinecraftServer server) {
        Lane lane = lanes.get(root(server)); return lane != null && lane.depth.get() >= 16;
    }
    public static void await(MinecraftServer server) { awaitDurable(root(server)); }
    /** Wait for journal records to reach disk; full checkpoint preparation can finish later. */
    public static void awaitDurable(Path directory) {
        Path root = directory.toAbsolutePath().normalize(); pending(root).join();
        synchronized (FinancialWriter.class) { Lane lane = lanes.get(root); if (lane != null) checkMaintenance(lane); }
    }
    public static void check(MinecraftServer server) {
        EconomicEventQueue.checkMutation();
        var pending = pending(root(server)); if (pending.isCompletedExceptionally()) pending.join();
        synchronized (FinancialWriter.class) { Lane lane = lanes.get(root(server)); if (lane != null) checkMaintenance(lane); }
    }
    /** Only waits for writes already submitted. This doesn't queue more I/O or another checkpoint. */
    public static CompletableFuture<Void> completion(Path directory) { return pending(directory.toAbsolutePath().normalize()); }
    public static void close(MinecraftServer server) {
        Path root = root(server);
        try {
            Lane lane; synchronized (FinancialWriter.class) { lane = lanes.get(root); }
            if (lane != null) {
                lane.tail.join(); lane.maintenance.join();
                lane.tail.thenRunAsync(() -> {
                try { for (FinancialJournal journal : lane.journals.values()) journal.checkpoint(); }
                catch (java.io.IOException failure) { throw new CompletionException(failure); }
                }, lane.executor).join();
            }
        }
        finally { synchronized (FinancialWriter.class) { Lane lane = lanes.remove(root); if (lane != null) { lane.executor.shutdown(); lane.checkpointExecutor.shutdown(); } } }
    }
    /** Also handles standalone verification files outside the world's data folder. */
    public static void awaitAll() {
        CompletableFuture<?>[] values; synchronized (FinancialWriter.class) { values = lanes.values().stream().map(lane -> lane.tail).toArray(CompletableFuture[]::new); }
        CompletableFuture.allOf(values).join();
        // Don't publish checkpoints while draining writes for verification, export or shutdown.
        synchronized (FinancialWriter.class) { values = lanes.values().stream().map(lane -> lane.maintenance).toArray(CompletableFuture[]::new); }
        CompletableFuture.allOf(values).join();
    }
    private FinancialWriter() {}
}
