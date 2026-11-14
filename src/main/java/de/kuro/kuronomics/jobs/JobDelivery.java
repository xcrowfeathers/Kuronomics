package de.kuro.kuronomics.jobs;

import java.util.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import de.kuro.kuronomics.economy.data.EconomyLedger;

/** Save removed items with the recovery marker, and earnings with their receipt. */
public final class JobDelivery {
    private static final String MARKER = "kuronomics_job_delivery";
    public static long deliver(ServerPlayer p, int count) {
        recover(p);
        var stack = p.getMainHandItem(); if (count < 1 || count > stack.getCount()) throw new IllegalArgumentException("Not enough items in your hand.");
        var jobs = JobService.get(p.server); var ledger = EconomyLedger.get(p.server);
        var before = ledger.account(p.getUUID());
        var after = jobs.preview(p, "deliver_item", stack.getItem(), p.blockPosition(), stack, count, true);
        if (after == null) throw new IllegalArgumentException("No applicable delivery task, or the PayDay cap has been reached.");
        UUID reference = UUID.randomUUID(); CompoundTag pending = new CompoundTag();
        pending.putUUID("reference", reference); pending.putString("job", before.job()); pending.putLong("sequence", before.paydaySequence());
        pending.putLong("earnings", after.jobEarnings()); CompoundTag counters = new CompoundTag(); after.taskEarnings().forEach(counters::putLong); pending.put("counters", counters);
        // Save the removed items before adding earnings. The reward amount never comes from a client packet.
        stack.shrink(count); p.getInventory().setChanged(); p.getPersistentData().put(MARKER, pending);
        recover(p); p.containerMenu.broadcastChanges();
        return after.jobEarnings() - before.jobEarnings();
    }
    public static void recover(ServerPlayer p) {
        if (!p.getPersistentData().contains(MARKER)) return;
        CompoundTag pending = p.getPersistentData().getCompound(MARKER); UUID reference = pending.getUUID("reference");
        var ledger = EconomyLedger.get(p.server);
        de.kuro.kuronomics.economy.data.Account rewardedBefore=null,rewardedAfter=null;
        if (!ledger.hasTransaction(reference)) {
            var account = ledger.account(p.getUUID());
            if (!account.job().equals(pending.getString("job")) || account.paydaySequence() != pending.getLong("sequence"))
                throw new IllegalStateException("Pending job delivery must be recovered before changing jobs or processing PayDay.");
            Map<String, Long> counters = new HashMap<>(); CompoundTag saved = pending.getCompound("counters"); for (String key : saved.getAllKeys()) counters.put(key, saved.getLong(key));
            long earnings = pending.getLong("earnings");
            if (earnings < account.jobEarnings()) throw new IllegalStateException("Delivery earnings changed before recovery.");
            de.kuro.kuronomics.economy.PlayerPersistence.save(p);
            rewardedBefore=account;rewardedAfter=account.earnings(earnings,counters);
            ledger.commitTransaction(reference, "job-delivery:" + p.getUUID(), Map.of(p.getUUID(), rewardedAfter), List.of(), List.of(), Set.of());
        }
        de.kuro.kuronomics.economy.FinancialPersistence.save(p.server);
        de.kuro.kuronomics.economy.FinancialWriter.await(p.server);
        if(rewardedAfter!=null)JobTaskProgress.record(p,rewardedBefore,rewardedAfter);
        p.getPersistentData().remove(MARKER); // The next normal autosave clears the old disk marker. It's harmless now that the transaction is
        // saved.
    }
    private JobDelivery() {}
}
