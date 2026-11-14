package de.kuro.kuronomics.economy;

import net.minecraft.server.MinecraftServer;

/**
 * Share one time budget across the queues and rotate who goes first so none gets stuck waiting.
 * A single operation can't be interrupted, so the budget can be exceeded; the metrics track that.
 */
public final class EconomyWorkCoordinator {
    public record Metrics(long slices, long totalNanos, long maximumNanos, long overBudget) {}
    private static final java.util.Map<MinecraftServer, Metrics> METRICS = new java.util.WeakHashMap<>();
    public static Metrics metrics(MinecraftServer server) { return METRICS.getOrDefault(server, new Metrics(0, 0, 0, 0)); }
    public static void resetMetrics(MinecraftServer server) { METRICS.remove(server); }
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    public static long deadline() {
        Long active = DEADLINE.get(); return active == null ? System.nanoTime() + 1_000_000L : active;
    }
    public static void run(MinecraftServer server, Runnable... queues) {
        if (!server.isSameThread()) throw new IllegalStateException("Economy scheduling off server thread");
        if (queues.length == 0) return;
        long started = System.nanoTime(), deadline = started + 1_000_000L;
        int first = Math.floorMod(server.getTickCount(), queues.length);
        DEADLINE.set(deadline);
        try {
            for (int i = 0; i < queues.length && (i == 0 || System.nanoTime() < deadline); i++) queues[(first + i) % queues.length].run();
        } finally {
            DEADLINE.remove();
            long elapsed = System.nanoTime() - started; Metrics previous = metrics(server);
            METRICS.put(server, new Metrics(previous.slices() + 1, previous.totalNanos() + elapsed,
                    Math.max(previous.maximumNanos(), elapsed), previous.overBudget() + (elapsed > 1_000_000L ? 1 : 0)));
        }
    }
    private EconomyWorkCoordinator() {}
}
