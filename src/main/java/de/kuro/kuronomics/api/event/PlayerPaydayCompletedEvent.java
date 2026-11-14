package de.kuro.kuronomics.api.event;

import java.util.UUID;
import net.neoforged.bus.api.Event;

/** Sent after the payday checkpoint. It can't be cancelled and isn't sent again when loading a save. */
public final class PlayerPaydayCompletedEvent extends Event {
    private final UUID player, reference;
    private final long sequence, opening, closing;
    public PlayerPaydayCompletedEvent(UUID player, UUID reference, long sequence, long opening, long closing) {
        this.player = player; this.reference = reference; this.sequence = sequence; this.opening = opening; this.closing = closing;
    }
    public UUID player() { return player; }
    public UUID reference() { return reference; }
    public long sequence() { return sequence; }
    public long opening() { return opening; }
    public long closing() { return closing; }
}
