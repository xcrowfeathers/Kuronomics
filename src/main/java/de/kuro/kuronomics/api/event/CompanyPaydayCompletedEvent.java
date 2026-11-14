package de.kuro.kuronomics.api.event;

import de.kuro.kuronomics.company.Company;
import java.util.*;
import net.neoforged.bus.api.Event;

/** One event for the whole frozen payday batch, after its checkpoint. Companies aren't saved separately. */
public final class CompanyPaydayCompletedEvent extends Event {
    private final UUID reference;
    private final long cycleDay;
    private final Map<UUID, Company.Statement> statements;
    private final boolean debug;
    public CompanyPaydayCompletedEvent(UUID reference, long cycleDay, Map<UUID, Company.Statement> statements) {
        this(reference, cycleDay, statements, false);
    }
    public CompanyPaydayCompletedEvent(UUID reference, long cycleDay, Map<UUID, Company.Statement> statements, boolean debug) {
        this.reference = reference; this.cycleDay = cycleDay; this.statements = Map.copyOf(statements);
        this.debug = debug;
    }
    public UUID reference() { return reference; }
    public long cycleDay() { return cycleDay; }
    public Map<UUID, Company.Statement> statements() { return statements; }
    public boolean debug() { return debug; }
}
