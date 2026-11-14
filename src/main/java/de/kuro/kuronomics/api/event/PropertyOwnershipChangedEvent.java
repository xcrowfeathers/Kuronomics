package de.kuro.kuronomics.api.event;

import de.kuro.kuronomics.economy.EconomicOwner;
import java.util.UUID;
import net.neoforged.bus.api.Event;

/**
 * Sent after a recoverable company property action finishes. This is economic ownership, not WorldGuard
 * membership.
 */
public final class PropertyOwnershipChangedEvent extends Event {
    private final UUID actor, reference;
    private final String property;
    private final EconomicOwner before, after;
    public PropertyOwnershipChangedEvent(UUID actor, UUID reference, String property, EconomicOwner before, EconomicOwner after) {
        this.actor = actor; this.reference = reference; this.property = property; this.before = before; this.after = after;
    }
    public UUID actor() { return actor; }
    public UUID reference() { return reference; }
    public String property() { return property; }
    public EconomicOwner before() { return before; }
    public EconomicOwner after() { return after; }
}
