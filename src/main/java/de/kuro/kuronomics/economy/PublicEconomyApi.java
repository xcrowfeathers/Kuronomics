package de.kuro.kuronomics.economy;

import de.kuro.kuronomics.api.*;
import de.kuro.kuronomics.economy.data.*;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** API calls use EconomyService and the same journal as normal gameplay. Each server has its own adapter. */
public final class PublicEconomyApi implements EconomyApi {
    private final MinecraftServer server;
    public PublicEconomyApi(MinecraftServer server) { this.server = Objects.requireNonNull(server); ApiAccess.thread(server); }
    private EconomyLedger ledger() { ApiAccess.thread(server); return EconomyLedger.get(server); }
    @Override public boolean exists(EconomicOwner owner) {
        var ledger = ledger(); Objects.requireNonNull(owner);
        return switch (owner.type()) {
            case PLAYER -> ledger.contains(owner.id());
            case COMPANY -> ledger.companies().company(owner.id()) != null;
            case STATE -> true;
        };
    }
    @Override public Optional<Balance> balance(EconomicOwner owner) {
        if (!exists(owner)) return Optional.empty();
        var ledger = ledger(); var a = ledger.account(owner);
        return Optional.of(new Balance(owner, a.balance(), ledger.spendingCapacity(owner), a.pendingIncome(), ledger.reserved(owner), a.suspended()));
    }
    @Override public boolean canAfford(EconomicOwner owner, long cents) {
        ApiAccess.thread(server);
        return cents > 0 && balance(owner).filter(b -> !b.suspended() && b.spendable() >= cents).isPresent()
                && (!owner.isCompany() || ledger().companies().company(owner.id()).status() == de.kuro.kuronomics.company.Company.Status.ACTIVE);
    }
    private void accounts(EconomicOwner payer, EconomicOwner recipient) {
        if (!exists(payer) || !exists(recipient)) throw new ApiAccess.Failure(OperationResult.Status.NOT_FOUND, "Account does not exist");
    }
    @Override public OperationResult transfer(ServerPlayer actor, EconomicOwner payer, EconomicOwner recipient, long cents, Timing timing, Context context) {
        return ApiAccess.mutate(server, () -> {
            ApiAccess.spending(server, actor, payer); accounts(payer, recipient); Money.positive(cents); Objects.requireNonNull(timing); Objects.requireNonNull(context);
            var economy = new EconomyService(ledger());
            if (timing == Timing.NEXT_PAYDAY && recipient.equals(EconomicOwner.STATE)) throw new IllegalArgumentException("State has no PayDay income buffer");
            // Let the service recognize an identical retry before checking how much money is available now.
            return economy.transfer(payer, recipient, cents, timing == Timing.NEXT_PAYDAY,
                    new ActionContext(actor.getUUID(), payer, context.reference()), context.reason().toString(), context.description(), true);
        });
    }
    private UUID holdId(UUID reference) { return EconomyService.stableReference("public-api-hold", Objects.requireNonNull(reference).toString()); }
    @Override public OperationResult reserve(ServerPlayer actor, EconomicOwner payer, long cents, Context context) {
        return ApiAccess.mutate(server, () -> {
            ApiAccess.spending(server, actor, payer); accounts(payer, payer); Money.positive(cents);
            return new EconomyService(ledger()).reserveAuthorized(payer, cents, holdId(context.reference()),
                    new ActionContext(actor.getUUID(), payer, context.reference()), context.reason().toString(), context.description());
        });
    }
    @Override public Optional<Hold> reservation(UUID reference) {
        return Optional.ofNullable(ledger().reservation(holdId(reference))).map(h -> new Hold(reference, h.economicOwner(), h.amount(), h.active()));
    }
    @Override public OperationResult capture(ServerPlayer actor, UUID holdReference, EconomicOwner recipient, Timing timing, Context context) {
        ApiAccess.thread(server);
        var hold = ledger().reservation(holdId(holdReference));
        if (hold == null) return new OperationResult(OperationResult.Status.NOT_FOUND, "Unknown API hold");
        return ApiAccess.mutate(server, () -> {
            ApiAccess.spending(server, actor, hold.economicOwner()); accounts(hold.economicOwner(), recipient); Objects.requireNonNull(timing);
            return new EconomyService(ledger()).captureReserved(hold.economicOwner(), hold.reference(), recipient,
                    timing == Timing.NEXT_PAYDAY, new ActionContext(actor.getUUID(), hold.economicOwner(), context.reference()),
                    context.reason().toString(), context.description());
        });
    }
    @Override public OperationResult release(ServerPlayer actor, UUID holdReference, Context context) {
        return ApiAccess.mutate(server, () -> {
            var hold = ledger().reservation(holdId(holdReference));
            if (hold == null) throw new ApiAccess.Failure(OperationResult.Status.NOT_FOUND, "Unknown API hold");
            ApiAccess.spending(server, actor, hold.economicOwner());
            return new EconomyService(ledger()).releaseAuthorized(hold.economicOwner(), hold.reference(),
                    new ActionContext(actor.getUUID(), hold.economicOwner(), context.reference()), context.reason().toString(), context.description());
        });
    }
    @Override public OperationResult bankPayment(ServerPlayer actor, UUID player, long cents, Context context) {
        return bank(actor, player, cents, context, de.kuro.kuronomics.economy.bank.CentralBank.Kind.PAY);
    }
    @Override public OperationResult bankDebit(ServerPlayer actor, UUID player, long cents, Context context) {
        return bank(actor, player, cents, context, de.kuro.kuronomics.economy.bank.CentralBank.Kind.DEBIT);
    }
    private OperationResult bank(ServerPlayer actor, UUID player, long cents, Context context, de.kuro.kuronomics.economy.bank.CentralBank.Kind kind) {
        EconomicOwner account = EconomicOwner.player(player);
        return ApiAccess.mutate(server, () -> {
                    ApiAccess.actor(server, actor);
                    return de.kuro.kuronomics.economy.bank.CentralBank.execute(actor, kind, account, cents, 0, null, context.reference());
                });
    }
}
