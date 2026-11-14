package de.kuro.kuronomics.api;

import de.kuro.kuronomics.economy.EconomicOwner;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;

/** Rewards come from an existing account. This doesn't create money or add job earnings. */
public record EconomicReward(Funding funding, EconomicOwner payer, long cents, EconomyApi.Timing timing) {
    public enum Funding { ACCOUNT, TREASURY }
    public EconomicReward {
        Objects.requireNonNull(funding); Objects.requireNonNull(payer); Objects.requireNonNull(timing);
        if (cents <= 0) throw new IllegalArgumentException("Reward must be positive integer cents");
        if (funding == Funding.TREASURY ? !payer.equals(EconomicOwner.STATE) || timing != EconomyApi.Timing.IMMEDIATE : payer.equals(EconomicOwner.STATE))
            throw new IllegalArgumentException("Treasury funding must be explicit and immediate");
    }
    public static EconomicReward funded(EconomicOwner payer, long cents, EconomyApi.Timing timing) {
        return new EconomicReward(Funding.ACCOUNT, payer, cents, timing);
    }
    public static EconomicReward treasury(long cents) { return new EconomicReward(Funding.TREASURY, EconomicOwner.STATE, cents, EconomyApi.Timing.IMMEDIATE); }
    /** The connected player must control the paying account or pass the bank's role and daily-limit checks. */
    public OperationResult apply(ServerPlayer actor, UUID recipient, EconomyApi.Context context) {
        var economy = KuronomyApi.economy(actor.server);
        return funding == Funding.TREASURY ? economy.bankPayment(actor, recipient, cents, context)
                : economy.transfer(actor, payer, EconomyApi.player(recipient), cents, timing, context);
    }
}
