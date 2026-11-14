package de.kuro.kuronomics.api;

import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/** Types are registered once for the process. Task definitions and earnings belong to each server. */
public interface JobTaskApi {
    /**
     * Register in the mod constructor or common setup's queued work, before the first server data load.
     * Duplicate IDs and late registration throw. Built-in types can't be replaced.
     */
    <C, A> void register(ResourceLocation id, JobTaskType<C, A> type);
    Set<ResourceLocation> types();
    record Task(ResourceLocation id, ResourceLocation type, String job, long rewardCents, long capCents) {}
    Collection<Task> tasks(MinecraftServer server);
    enum Status { REWARDED, NO_REWARD, DUPLICATE, REJECTED }
    /** These are earned job rewards, not spendable money or confirmation that a payout was saved. */
    record Completion(Status status, long earnedCents) {}
    /**
     * Call on the server thread with a connected player. Only addon task types can be submitted here.
     * Use the actual server action's occurrence ID and reuse it on retries. Earnings and occurrence IDs
     * share the ledger's normal save cycle; this doesn't force a save for every action.
     * Validate client input first. This doesn't save inventory changes and payments together.
     */
    Completion submit(ResourceLocation type, UUID occurrence, JobTaskType.Trigger<?> trigger);
}
