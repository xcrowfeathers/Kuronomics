package de.kuro.kuronomics.api.event;

import de.kuro.kuronomics.api.EconomyApi;
import de.kuro.kuronomics.economy.EconomicOwner;
import java.util.UUID;
import net.neoforged.bus.api.Event;

/**
 * Sent after the checkpoint for supported new transfers, hold captures and bank actions.
 * It can't be cancelled and isn't replayed during recovery. A crash can lose the notification itself.
 */
public final class EconomyTransactionEvent extends Event {
    private final UUID actor;
    private final EconomicOwner payer, recipient;
    private final long cents;
    private final EconomyApi.Timing timing;
    private final EconomyApi.Context context;
    public EconomyTransactionEvent(UUID actor, EconomicOwner payer, EconomicOwner recipient, long cents,
                                   EconomyApi.Timing timing, EconomyApi.Context context) {
        this.actor = actor; this.payer = payer; this.recipient = recipient; this.cents = cents; this.timing = timing; this.context = context;
    }
    public UUID actor() { return actor; }
    public EconomicOwner payer() { return payer; }
    public EconomicOwner recipient() { return recipient; }
    public long cents() { return cents; }
    public EconomyApi.Timing timing() { return timing; }
    public EconomyApi.Context context() { return context; }
}
