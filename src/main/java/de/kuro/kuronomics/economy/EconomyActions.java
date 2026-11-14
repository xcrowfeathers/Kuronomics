package de.kuro.kuronomics.economy;

import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** UI requests stay in order in a capped queue. Check that they're still valid when they actually run. */
public final class EconomyActions {
    private record Request(ServerPlayer player, AbstractContainerMenu menu, UUID id, Runnable action) {}
    private static final class Queue {
        final LinkedHashMap<UUID, ArrayDeque<Request>> players = new LinkedHashMap<>();
        final Set<String> pending = new HashSet<>();
        final Map<UUID, Integer> warnings = new HashMap<>();
        int size;
    }
    private static final Map<MinecraftServer, Queue> queues = new WeakHashMap<>();
    public static boolean submit(ServerPlayer player, AbstractContainerMenu menu, UUID requestId, Runnable action) {
        if (!player.server.isSameThread()) throw new IllegalStateException("Economy request off server thread");
        Queue queue = queues.computeIfAbsent(player.server, ignored -> new Queue());
        String key = player.getUUID() + ":" + requestId;
        if (queue.pending.contains(key)) return false;
        var requests = queue.players.get(player.getUUID());
        if (queue.size >= 2048 || requests != null && requests.size() >= 32) {
            int tick = player.server.getTickCount(); Integer last = queue.warnings.get(player.getUUID());
            if (last == null || tick - last >= 20) {
                queue.warnings.put(player.getUUID(), tick);
                player.sendSystemMessage(Component.literal("Your economy requests are still processing. Please wait before trying again."));
            }
            return false;
        }
        queue.players.computeIfAbsent(player.getUUID(), ignored -> new ArrayDeque<>()).addLast(new Request(player, menu, requestId, action));
        queue.pending.add(key); queue.size++; return true;
    }
    public static void tick(MinecraftServer server) {
        if (FinancialWriter.busy(server) || FinancialSettlements.busy(server)) return;
        Queue queue = queues.get(server); if (queue == null || queue.size == 0) return;
        long stopAt = de.kuro.kuronomics.economy.EconomyWorkCoordinator.deadline(); Map<UUID, Integer> served = new HashMap<>();
        int attempts = Math.min(queue.size, 16);
        for (int i = 0; i < attempts; i++) {
            if (FinancialWriter.busy(server) || FinancialSettlements.busy(server) || i > 0 && System.nanoTime() >= stopAt) break;
            var iterator = queue.players.entrySet().iterator(); var entry = iterator.next(); iterator.remove();
            UUID id = entry.getKey(); var requests = entry.getValue();
            if (served.getOrDefault(id, 0) >= 2) { queue.players.put(id, requests); continue; }
            Request request = requests.removeFirst(); if (!requests.isEmpty()) queue.players.put(id, requests);
            queue.pending.remove(id + ":" + request.id()); queue.size--; served.merge(id, 1, Integer::sum);
            var player = request.player();
            if (server.getPlayerList().getPlayer(id) != player || player.containerMenu != request.menu() || !request.menu().stillValid(player)) continue;
            try { FinancialWriter.check(server); request.action().run(); }
            catch (RuntimeException failure) {
                player.sendSystemMessage(Component.literal(failure instanceof java.util.concurrent.CompletionException
                        ? "Economy storage is unavailable. Please contact an administrator."
                        : Objects.toString(failure.getMessage(), "The economy request could not be completed.")));
                if (failure instanceof java.util.concurrent.CompletionException)
                    com.mojang.logging.LogUtils.getLogger().error("Economy checkpoint failed; request stopped", failure);
            }
        }
    }
    public static void left(ServerPlayer player) {
        Queue queue = queues.get(player.server); if (queue == null) return;
        var removed = queue.players.remove(player.getUUID());
        if (removed != null) for (Request value : removed) { queue.pending.remove(player.getUUID() + ":" + value.id()); queue.size--; }
        queue.warnings.remove(player.getUUID());
    }
    public static void stop(MinecraftServer server) { queues.remove(server); }
    private EconomyActions() {}
}
