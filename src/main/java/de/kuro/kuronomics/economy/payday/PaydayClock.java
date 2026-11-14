package de.kuro.kuronomics.economy.payday;

import de.kuro.kuronomics.economy.data.EconomyLedger;
import de.kuro.kuronomics.economy.EconomyService;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

/** The vanilla play-time score is the actual payday timer, not just a display of another timer. */
public final class PaydayClock {
    private static final String NAME = "payday";
    private static final ObjectiveCriteria CRITERIA = ObjectiveCriteria.byName("minecraft.custom:minecraft.play_time")
            .orElseThrow(() -> new IllegalStateException("Vanilla play-time scoreboard criterion is unavailable"));

    private PaydayClock() {}

    public static Objective ensure(MinecraftServer server) {
        var scoreboard = server.getScoreboard();
        Objective objective = scoreboard.getObjective(NAME);
        if (objective != null && objective.getCriteria() != CRITERIA) {
            com.mojang.logging.LogUtils.getLogger().warn("Repairing payday scoreboard criterion; online timer progress will be restored from the economy ledger");
            scoreboard.removeObjective(objective); objective = null;
        }
        if (objective == null) {
            objective = scoreboard.addObjective(NAME, CRITERIA, Component.literal(NAME), ObjectiveCriteria.RenderType.INTEGER, false, null);
            var ledger = EconomyLedger.get(server);
            if (ledger.paydayEnabled()) for (var player : server.getPlayerList().getPlayers()) restore(player, ledger, objective);
        }
        return objective;
    }

    public static void remove(MinecraftServer server) {
        Objective objective = server.getScoreboard().getObjective(NAME);
        if (objective != null) server.getScoreboard().removeObjective(objective);
    }

    /** Restore from the saved ledger so an old scoreboard can't trigger the same payday again after a crash. */
    public static void join(ServerPlayer player, EconomyLedger ledger) {
        if (!ledger.paydayEnabled()) return;
        Objective objective = ensure(player.server);
        restore(player, ledger, objective);
    }
    private static void restore(ServerPlayer player, EconomyLedger ledger, Objective objective) {
        player.server.getScoreboard().getOrCreatePlayerScore(ScoreHolder.forNameOnly(player.getScoreboardName()), objective)
                .set((int) Math.min(Integer.MAX_VALUE, new EconomyService(ledger).account(player.getUUID()).playTicks()));
    }
    public static void ready(ServerPlayer player, int interval) {
        var score = player.server.getScoreboard().getOrCreatePlayerScore(ScoreHolder.forNameOnly(player.getScoreboardName()), ensure(player.server));
        if (score.get() < interval) score.set(interval);
    }

    public static int score(ServerPlayer player) {
        var scoreboard = player.server.getScoreboard();
        Objective objective = ensure(player.server);
        return scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly(player.getScoreboardName()), objective).get();
    }

    public static void reset(ServerPlayer player) {
        var scoreboard = player.server.getScoreboard();
        scoreboard.getOrCreatePlayerScore(ScoreHolder.forNameOnly(player.getScoreboardName()), ensure(player.server)).set(0);
    }
}
