package de.kuro.kuronomics.api.event;

import de.kuro.kuronomics.api.ContractApi;
import de.kuro.kuronomics.company.CompanyContract;
import java.util.*;
import net.neoforged.bus.api.Event;

/** Sent after a contract change commits. Personal and company contracts keep separate IDs. */
public final class ContractChangedEvent extends Event {
    public enum Change { CREATED, CANCELLED, UNFUNDED }
    private final UUID actor, reference;
    private final Change change;
    private final CompanyContract company;
    private final ContractApi.PersonalContract personal;
    public ContractChangedEvent(UUID actor, UUID reference, Change change, CompanyContract company, ContractApi.PersonalContract personal) {
        if ((company == null) == (personal == null)) throw new IllegalArgumentException("Choose exactly one contract model");
        this.actor = actor; this.reference = reference; this.change = change; this.company = company; this.personal = personal;
    }
    public UUID actor() { return actor; }
    public UUID reference() { return reference; }
    public Change change() { return change; }
    public Optional<CompanyContract> company() { return Optional.ofNullable(company); }
    public Optional<ContractApi.PersonalContract> personal() { return Optional.ofNullable(personal); }
}
