package de.kuro.kuronomics.integration.kubejs;

import de.kuro.kuronomics.api.*;
import de.kuro.kuronomics.company.Company;
import de.kuro.kuronomics.economy.EconomicOwner;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/** Convert script IDs and cents through the public APIs. Don't expose the ledger or store script callbacks. */
public final class KuronomyScriptApi {
    private MinecraftServer server() {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) throw new IllegalStateException("Kuronomy script operations require the running server thread");
        return server;
    }
    public EconomicOwner player(String id) { return EconomyApi.player(UUID.fromString(id)); }
    public EconomicOwner company(String id) { return EconomyApi.company(UUID.fromString(id)); }
    public EconomicOwner state() { return EconomyApi.state(); }
    /** Use decimal strings for cents so JavaScript can't round large amounts. */
    public Map<String, Object> balance(EconomicOwner owner) {
        return KuronomyApi.economy(server()).balance(owner).<Map<String, Object>>map(b -> Map.of(
                "exists", true, "postedCents", Long.toString(b.posted()), "spendableCents", Long.toString(b.spendable()),
                "pendingCents", Long.toString(b.pending()), "reservedCents", Long.toString(b.reserved()), "suspended", b.suspended())).orElseGet(() -> Map.of("exists", false));
    }
    public Map<String, Object> playerBalance(String id) { return balance(player(id)); }
    public Map<String, Object> companyBalance(String id) { return balance(company(id)); }
    public Company companyByName(String name) { return KuronomyApi.companies(server()).find(name).orElse(null); }
    public boolean companyPermission(String player, String company, String permission) {
        return KuronomyApi.companies(server()).hasPermission(UUID.fromString(player), UUID.fromString(company), CompanyApi.Permission.valueOf(permission));
    }
    public String companyRank(String player, String company) {
        return KuronomyApi.companies(server()).find(UUID.fromString(company)).map(c -> c.members().get(UUID.fromString(player))).map(m -> m.rank().name()).orElse("");
    }
    public JobApi.Employment job(String player) { return KuronomyApi.jobs(server()).employment(UUID.fromString(player)).orElse(null); }
    public boolean canStartJob(String player, String job) { return KuronomyApi.jobs(server()).canStart(UUID.fromString(player), job); }
    public boolean hasFunds(EconomicOwner owner, String cents) { return KuronomyApi.economy(server()).canAfford(owner, Long.parseLong(cents)); }
    private EconomyApi.Context context(String reference, String reason) {
        return new EconomyApi.Context(UUID.fromString(reference), ResourceLocation.parse(reason), "KubeJS");
    }
    private OperationResult operation(Supplier<OperationResult> action) {
        server();
        try { return action.get(); }
        catch (IllegalArgumentException invalid) { return new OperationResult(OperationResult.Status.INVALID, Objects.toString(invalid.getMessage(), "Invalid argument")); }
    }
    public OperationResult transfer(ServerPlayer actor, EconomicOwner payer, EconomicOwner recipient, String cents, boolean deferred, String reference, String reason) {
        return operation(() -> KuronomyApi.economy(server()).transfer(actor, payer, recipient, Long.parseLong(cents), deferred ? EconomyApi.Timing.NEXT_PAYDAY : EconomyApi.Timing.IMMEDIATE, context(reference, reason)));
    }
    public OperationResult bankPayment(ServerPlayer actor, String player, String cents, String reference) {
        return operation(() -> KuronomyApi.economy(server()).bankPayment(actor, UUID.fromString(player), Long.parseLong(cents), context(reference, "kuronomics:kubejs_bank_payment")));
    }
    public OperationResult bankDebit(ServerPlayer actor, String player, String cents, String reference) {
        return operation(() -> KuronomyApi.economy(server()).bankDebit(actor, UUID.fromString(player), Long.parseLong(cents), context(reference, "kuronomics:kubejs_bank_debit")));
    }
    public OperationResult reserve(ServerPlayer actor, EconomicOwner payer, String cents, String reference, String reason) {
        return operation(() -> KuronomyApi.economy(server()).reserve(actor, payer, Long.parseLong(cents), context(reference, reason)));
    }
    public EconomyApi.Hold reservation(String reference) { return KuronomyApi.economy(server()).reservation(UUID.fromString(reference)).orElse(null); }
    public OperationResult capture(ServerPlayer actor, String hold, EconomicOwner recipient, boolean deferred, String reference, String reason) {
        return operation(() -> KuronomyApi.economy(server()).capture(actor, UUID.fromString(hold), recipient, deferred ? EconomyApi.Timing.NEXT_PAYDAY : EconomyApi.Timing.IMMEDIATE, context(reference, reason)));
    }
    public OperationResult release(ServerPlayer actor, String hold, String reference, String reason) {
        return operation(() -> KuronomyApi.economy(server()).release(actor, UUID.fromString(hold), context(reference, reason)));
    }
    public JobTaskApi.Completion submitTask(String type, String occurrence, ServerPlayer player, BlockPos position, Object validatedAction, int count) {
        server(); return KuronomyApi.jobTasks().submit(ResourceLocation.parse(type), UUID.fromString(occurrence), new JobTaskType.Trigger<>(player, position, validatedAction, count));
    }
    public void defer(Runnable action) { KuronomyApi.defer(server(), action); }
}
