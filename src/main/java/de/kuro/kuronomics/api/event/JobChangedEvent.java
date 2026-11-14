package de.kuro.kuronomics.api.event;

import java.util.UUID;
import net.neoforged.bus.api.Event;

/**
 * Also covers offline assignments, quitting and job removal (after == unemployed).
 * The player whose job changed isn't necessarily the person who changed it.
 */
public final class JobChangedEvent extends Event {
    private final UUID player, reference;
    private final String before, after;
    public JobChangedEvent(UUID player, UUID reference, String before, String after) {
        this.player = player; this.reference = reference; this.before = before; this.after = after;
    }
    public UUID player() { return player; }
    public UUID reference() { return reference; }
    public String before() { return before; }
    public String after() { return after; }
}
