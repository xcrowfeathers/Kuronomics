package de.kuro.kuronomics.economy;

import java.util.Objects;
import java.util.UUID;

/** Only scheduled system work can have a null actor. Always specify whose account is being used. */
public record ActionContext(UUID actor, EconomicOwner owner, UUID operation) {
    public ActionContext { Objects.requireNonNull(owner); Objects.requireNonNull(operation); }
    public static ActionContext player(UUID actor, EconomicOwner owner) {
        return new ActionContext(Objects.requireNonNull(actor), owner, UUID.randomUUID());
    }
}
