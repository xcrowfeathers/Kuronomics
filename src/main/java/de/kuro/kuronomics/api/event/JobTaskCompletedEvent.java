package de.kuro.kuronomics.api.event;

import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.Event;

/**
 * Sent after a completed action adds job earnings in memory, not after a payday payout.
 * These earnings use the normal job save cycle, so a crash can lose anything not saved yet.
 * There is no extra save for each task.
 */
public final class JobTaskCompletedEvent extends Event {
    public record Reward(String task, long cents) {}
    private final UUID player;
    private final UUID reference;
    private final String job;
    private final ResourceLocation type;
    private final int count;
    private final List<Reward> rewards;
    public JobTaskCompletedEvent(UUID player, UUID reference, String job, ResourceLocation type, int count, List<Reward> rewards) {
        this.player = player; this.reference = reference; this.job = job; this.type = type; this.count = count; this.rewards = List.copyOf(rewards);
    }
    public UUID player() { return player; }
    /** Null for built-in rewards, which don't have a separately journaled occurrence ID. */
    public UUID reference() { return reference; }
    public String job() { return job; }
    public ResourceLocation type() { return type; }
    public int count() { return count; }
    public List<Reward> rewards() { return rewards; }
}
