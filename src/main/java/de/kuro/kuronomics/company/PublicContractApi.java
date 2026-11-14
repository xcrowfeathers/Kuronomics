package de.kuro.kuronomics.company;

import de.kuro.kuronomics.api.*;
import de.kuro.kuronomics.economy.*;
import de.kuro.kuronomics.economy.data.EconomyLedger;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class PublicContractApi implements ContractApi {
    private final MinecraftServer server;
    public PublicContractApi(MinecraftServer server) { this.server = Objects.requireNonNull(server); ApiAccess.thread(server); }
    private EconomyLedger ledger() { ApiAccess.thread(server); return EconomyLedger.get(server); }
    @Override public List<PersonalContract> personalOutgoing(UUID player) {
        return ledger().contracts(player).stream().map(c -> {
            EconomicOwner owner = EconomicOwner.player(c.payer());
            if (!c.bank()) return new PersonalContract(owner, EconomicOwner.player(c.receiver()), c.amount(), c.name());
            return new PersonalContract(c.amount() > 0 ? EconomicOwner.STATE : owner,
                    c.amount() > 0 ? owner : EconomicOwner.STATE, Math.abs(c.amount()), c.name());
        }).toList();
    }
    @Override public List<CompanyContract> companyContracts(EconomicOwner participant) { return ledger().companies().involving(participant).toList(); }
    @Override public OperationResult createCompanyContract(ServerPlayer actor, EconomicOwner payer, EconomicOwner recipient, String name, long cents, EconomyApi.Context context) {
        return ApiAccess.mutate(server, () -> {
            ApiAccess.spending(server, actor, payer);
            // Contract IDs include creation time. First match the request's original fields so a retry finds
            // the same contract.
            var existing = ledger().companies().contracts().get(context.reference());
            if (existing != null) {
                if (!existing.payer().equals(payer) || !existing.recipient().equals(recipient) || existing.amount() != cents
                        || !existing.name().equals(CompanyContract.displayName(name)) || !existing.createdBy().equals(actor.getUUID()))
                    throw new IllegalArgumentException("Reference already used for a different contract");
                return false;
            }
            if (ledger().hasTransaction(context.reference())) throw new IllegalArgumentException("Reference already consumed");
            CompanyService.get(server).contract(new ActionContext(actor.getUUID(), payer, context.reference()), payer, recipient, name, cents); return true;
        });
    }
    @Override public OperationResult cancelCompanyContract(ServerPlayer actor, EconomicOwner participant, UUID contract, EconomyApi.Context context) {
        return ApiAccess.mutate(server, () -> {
            ApiAccess.spending(server, actor, participant);
            if (ledger().transactionRecorded(context.reference(), "company-contract-cancel|" + contract + "|" + actor.getUUID())) return false;
            CompanyService.get(server).cancel(new ActionContext(actor.getUUID(), participant, context.reference()), participant, contract); return true;
        });
    }
}
