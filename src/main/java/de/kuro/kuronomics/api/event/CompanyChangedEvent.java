package de.kuro.kuronomics.api.event;

import de.kuro.kuronomics.company.Company;
import java.util.UUID;
import net.neoforged.bus.api.Event;

/**
 * Sent after a company change is saved to disk. The snapshots can't be changed or the event cancelled.
 * Duplicate requests and recovery don't send this again.
 */
public final class CompanyChangedEvent extends Event {
    private final UUID actor, reference;
    private final Company before, after;
    public CompanyChangedEvent(UUID actor, UUID reference, Company before, Company after) {
        this.actor = actor; this.reference = reference; this.before = before; this.after = after;
    }
    public UUID actor() { return actor; }
    public UUID reference() { return reference; }
    public Company before() { return before; }
    public Company after() { return after; }
}
