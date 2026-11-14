package de.kuro.kuronomics.economy;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;

/** These notifications aren't saved. They're sent after checkpoints, not through a durable outbox. */
public final class EconomicEventQueue {
    private static final ThreadLocal<Boolean> DISPATCHING = ThreadLocal.withInitial(() -> false);
    private record Batch(List<Event> events, CompletableFuture<Void> durable) {}
    private final MinecraftServer server;
    private final List<Event> pending = new ArrayList<>();
    private final Deque<Batch> batches = new ArrayDeque<>();
    public EconomicEventQueue(MinecraftServer server) { this.server = server; }
    public static void checkMutation() {
        if (DISPATCHING.get()) throw new SecurityException("Economic event listeners must defer mutations with KuronomyApi.defer(server, action)");
    }
    public void add(Event event) { pending.add(Objects.requireNonNull(event)); }
    /** Call after the matching ledger snapshot has been handed to the writer. */
    public void saved(Path directory) {
        if (pending.isEmpty()) return;
        var batch = new Batch(List.copyOf(pending), FinancialWriter.completion(directory));
        batches.addLast(batch); pending.clear();
        batch.durable().thenRun(() -> server.tell(new net.minecraft.server.TickTask(0, this::dispatch)));
    }
    /** Job earnings are only recorded in memory here; they don't have a completed payment's save guarantee. */
    public static void accrual(Event event) {
        if (DISPATCHING.get()) throw new SecurityException("Recursive economic event dispatch");
        DISPATCHING.set(true);
        try { NeoForge.EVENT_BUS.post(event); }
        catch (RuntimeException failure) { com.mojang.logging.LogUtils.getLogger().error("Job accrual listener failed after recording", failure); }
        finally { DISPATCHING.remove(); }
    }
    public void dispatch() {
        if (!server.isSameThread()) throw new IllegalStateException("Economic events off server thread");
        if (DISPATCHING.get()) return;
        DISPATCHING.set(true);
        try {
            while (!batches.isEmpty()) {
                var batch = batches.peekFirst();
                if (!batch.durable().isDone() || batch.durable().isCompletedExceptionally()) return;
                batches.removeFirst();
                for (Event event : batch.events()) {
                    try { NeoForge.EVENT_BUS.post(event); }
                    catch (RuntimeException failure) { com.mojang.logging.LogUtils.getLogger().error("Economic listener failed after durable commit", failure); }
                }
            }
        } finally { DISPATCHING.remove(); }
    }
}
