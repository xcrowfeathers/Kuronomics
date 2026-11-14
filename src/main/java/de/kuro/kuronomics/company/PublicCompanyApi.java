package de.kuro.kuronomics.company;

import de.kuro.kuronomics.api.*;
import de.kuro.kuronomics.economy.*;
import de.kuro.kuronomics.economy.data.EconomyLedger;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class PublicCompanyApi implements CompanyApi {
    private final MinecraftServer server;
    public PublicCompanyApi(MinecraftServer server) { this.server = Objects.requireNonNull(server); ApiAccess.thread(server); }
    private CompanyBook book() { ApiAccess.thread(server); return EconomyLedger.get(server).companies(); }
    @Override public Optional<Company> find(UUID id) { return Optional.ofNullable(book().company(Objects.requireNonNull(id))); }
    @Override public Optional<Company> find(String name) { return Optional.ofNullable(book().named(name)); }
    @Override public Set<UUID> memberships(UUID player) { return Set.copyOf(book().memberships(player)); }
    @Override public boolean hasPermission(UUID player, UUID company, Permission permission) {
        var capability = CompanyPermissionService.Capability.valueOf(Objects.requireNonNull(permission).name());
        return find(company).filter(c -> CompanyPermissionService.allowed(c, player, capability)).isPresent();
    }
    private OperationResult change(ServerPlayer actor, UUID company, EconomyApi.Context context, String fingerprint,
                                   java.util.function.Consumer<ActionContext> work) {
        var result = ApiAccess.mutate(server, () -> {
            ApiAccess.actor(server, actor);
            // The service checks ranks and CEO rules. Retries still have to match the saved request.
            var ledger = EconomyLedger.get(server);
            if (ledger.transactionRecorded(context.reference(), fingerprint)) return false;
            work.accept(new ActionContext(actor.getUUID(), EconomicOwner.company(company), context.reference())); return true;
        });
        return result;
    }
    @Override public OperationResult rank(ServerPlayer actor, UUID company, UUID member, Company.Rank rank, EconomyApi.Context context) {
        ApiAccess.thread(server);
        return change(actor, company, context, "COMPANY_RANK|" + company + "|" + (actor == null ? null : actor.getUUID()) + "|" + member + "|" + rank,
                c -> CompanyService.get(server).rank(c, member, rank));
    }
    @Override public OperationResult salary(ServerPlayer actor, UUID company, UUID member, long cents, EconomyApi.Context context) {
        return change(actor, company, context, "COMPANY_SALARY|" + company + "|" + (actor == null ? null : actor.getUUID()) + "|" + member + "|" + cents,
                c -> CompanyService.get(server).salary(c, member, cents));
    }
    @Override public OperationResult remove(ServerPlayer actor, UUID company, UUID member, EconomyApi.Context context) {
        boolean leaving = actor != null && actor.getUUID().equals(member);
        return change(actor, company, context, "COMPANY_REMOVE|" + company + "|" + (actor == null ? null : actor.getUUID()) + "|" + member + "|" + leaving,
                c -> CompanyService.get(server).remove(c, member, leaving));
    }
}
