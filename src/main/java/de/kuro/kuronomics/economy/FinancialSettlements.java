package de.kuro.kuronomics.economy;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;

/**
 * Payments share a snapshot and disk write. Update balances right away to prevent double spending,
 * but only report success once the batch is saved. Check queue space before changing balances.
 * Worker callbacks must not touch live Minecraft state.
 */
public final class FinancialSettlements {
    public static final int CAPACITY = 256;
    private static final long INTERVAL = 250_000_000L;
    private record Ticket(Runnable success, Consumer<Throwable> failure) {}
    private record Batch(ArrayDeque<Ticket> tickets, CompletableFuture<Void> durable) {}
    private static final class Queue {
        final ArrayDeque<Ticket> pending = new ArrayDeque<>();
        final ArrayDeque<Batch> batches = new ArrayDeque<>();
        long due;
        int size;
        RuntimeException failure;
    }
    private static final Map<MinecraftServer, Queue> QUEUES = new WeakHashMap<>();
    public static boolean busy(MinecraftServer server) {
        Queue queue = QUEUES.get(server); return queue != null && queue.size >= CAPACITY;
    }
    public static void requireCapacity(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Settlement off server thread");
        FinancialWriter.check(server);
        Queue queue = QUEUES.get(server);
        if (queue != null && queue.failure != null) throw new java.util.concurrent.CompletionException(queue.failure);
        if (queue != null && queue.size >= CAPACITY || FinancialWriter.busy(server))
            throw new IllegalStateException("Economy requests are still processing. Please try again shortly.");
    }
    public static void after(MinecraftServer server, Runnable success, Consumer<Throwable> failure) {
        if (!server.isSameThread()) throw new IllegalStateException("Settlement off server thread");
        Queue queue = QUEUES.computeIfAbsent(server, ignored -> new Queue());
        if (queue.size >= CAPACITY) throw new IllegalStateException("Settlement admission was not checked before mutation");
        if (queue.pending.isEmpty()) queue.due = System.nanoTime() + INTERVAL;
        queue.pending.addLast(new Ticket(success, failure)); queue.size++;
    }
    public static void tick(MinecraftServer server) {
        Queue queue = QUEUES.get(server); if (queue == null) return;
        if (!queue.pending.isEmpty() && System.nanoTime() >= queue.due && !FinancialWriter.busy(server)) flush(server, queue);
        long deadline = EconomyWorkCoordinator.deadline();
        while (!queue.batches.isEmpty() && System.nanoTime() < deadline) {
            Batch batch = queue.batches.peekFirst(); if (!batch.durable().isDone()) break;
            Throwable error = null;
            try { batch.durable().join(); } catch (RuntimeException failure) { error = failure; }
            Ticket ticket = batch.tickets().removeFirst(); queue.size--;
            if (batch.tickets().isEmpty()) queue.batches.removeFirst();
            try { if (error == null) ticket.success().run(); else ticket.failure().accept(error); }
            catch (RuntimeException failure) { com.mojang.logging.LogUtils.getLogger().error("Settlement notification failed", failure); }
        }
    }
    private static void flush(MinecraftServer server, Queue queue) {
        var tickets = new ArrayDeque<>(queue.pending); queue.pending.clear();
        CompletableFuture<Void> durable;
        try { durable = FinancialPersistence.submit(server); }
        catch (RuntimeException failure) { queue.failure = failure; durable = CompletableFuture.failedFuture(failure); }
        queue.batches.addLast(new Batch(tickets, durable));
    }
    /** Waiting is fine during shutdown, not during normal server ticks. */
    public static void stop(MinecraftServer server) {
        Queue queue = QUEUES.get(server); if (queue == null) return;
        try {
            if (!queue.pending.isEmpty()) flush(server, queue);
            for (Batch batch : queue.batches) batch.durable().join();
        } finally { QUEUES.remove(server); }
    }
    private FinancialSettlements() {}
}
