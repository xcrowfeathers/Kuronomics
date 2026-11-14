package de.kuro.kuronomics.company;

import de.kuro.kuronomics.economy.*;
import java.util.*;

public record CompanyAudit(UUID operation, long time, UUID actor, EconomicOwner owner,
                           EconomicOwner payer, EconomicOwner recipient, String category, long amount, String asset) {
    public CompanyAudit {
        Objects.requireNonNull(operation); Objects.requireNonNull(owner); Objects.requireNonNull(payer);
        Objects.requireNonNull(recipient); Objects.requireNonNull(category); Objects.requireNonNull(asset);
        if (time < 0 || amount < 0) throw new IllegalArgumentException("Invalid audit entry");
    }
    public static CompanyAudit of(ActionContext context, EconomicOwner payer, EconomicOwner recipient,
                                  String category, long amount, String asset) {
        return new CompanyAudit(context.operation(), System.currentTimeMillis(), context.actor(), context.owner(), payer, recipient, category, amount, asset);
    }
}
