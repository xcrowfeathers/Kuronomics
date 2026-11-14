package de.kuro.kuronomics.jobs;

import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.economy.data.*;
import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Keep only the latest reward notice per active player. Don't scan idle players or all jobs. */
@EventBusSubscriber(modid=Kuronomics.MODID)
public final class JobTaskProgress {
    private record Notice(ServerPlayer player,String job,String name,long sequence,long gain,long total) { }
    private static final Map<MinecraftServer,Map<UUID,Notice>> PENDING=new WeakHashMap<>();
    public static void record(ServerPlayer player,Account before,Account after) {
        long gain=after.jobEarnings()-before.jobEarnings();if(gain<=0)return;
        var job=JobService.get(player.server).registry().jobs().get(after.job());
        if(job==null||job.mode()!=JobRegistry.Mode.TASK_BASED)return;
        PENDING.computeIfAbsent(player.server,unused->new HashMap<>()).put(player.getUUID(),
                new Notice(player,after.job(),job.name(),after.paydaySequence(),gain,after.jobEarnings()));
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if(event.getServer().getTickCount()%4==0)flush(event.getServer());
    }
    /** Send at most five updates a second per player. Show the latest gain, not a sum of rapid gains. */
    public static void flush(MinecraftServer server) {
        var pending=PENDING.remove(server);if(pending==null)return;
        var ledger=EconomyLedger.get(server);
        for(var notice:pending.values()) {
            var player=notice.player();var current=ledger.account(player.getUUID());
            if(player.hasDisconnected()||!current.job().equals(notice.job())||current.paydaySequence()!=notice.sequence())continue;
            player.displayClientMessage(Component.literal(notice.name()+": +"+amount(notice.gain())+" ("+amount(notice.total())+")")
                    .withStyle(net.minecraft.ChatFormatting.GREEN),true);
        }
    }
    private static String amount(long cents){return java.math.BigDecimal.valueOf(cents,2).toPlainString()+"$";}
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if(event.getEntity() instanceof ServerPlayer player){var notices=PENDING.get(player.server);if(notices!=null)notices.remove(player.getUUID());}
    }
    public static void stop(MinecraftServer server){PENDING.remove(server);}
    private JobTaskProgress() { }
}
