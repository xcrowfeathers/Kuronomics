package de.kuro.kuronomics.company;

import de.kuro.kuronomics.economy.*;
import de.kuro.kuronomics.economy.data.*;
import de.kuro.kuronomics.estate.*;
import de.kuro.kuronomics.integration.*;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import static de.kuro.kuronomics.company.CompanyPermissionService.Capability.*;
import static de.kuro.kuronomics.company.CompanyAssetOperation.Kind.*;

/** Save asset operations before doing external work. Queue size is capped and retries must be safe. */
public final class CompanyAssetService {
    private static final Map<MinecraftServer, CompanyAssetService> SERVICES = new WeakHashMap<>();
    private final MinecraftServer server;
    private final EconomyLedger ledger;
    private final PropertyService properties;
    private final ArrayDeque<String> work = new ArrayDeque<>();
    private final Set<String> queued = new HashSet<>();
    private final Set<String> failed = new HashSet<>();
    private CompanyAssetService(MinecraftServer server) { this.server = server; ledger = EconomyLedger.get(server); properties = new PropertyService(server); }
    public static CompanyAssetService get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Company property service off server thread");
        return SERVICES.computeIfAbsent(server, CompanyAssetService::new);
    }
    public static void stop(MinecraftServer server) { SERVICES.remove(server); }
    private Company company(ActionContext context, CompanyPermissionService.Capability capability) {
        if (!context.owner().isCompany()) throw new IllegalArgumentException("Company owner required");
        Company c = CompanyService.get(server).require(context.owner().id()); CompanyPermissionService.require(c, context.actor(), capability); return c;
    }
    public void buy(ActionContext context, Estate estate) {
        Company c = company(context, ASSET); properties.requireUnlocked(estate.key()); properties.requireOwnershipState(estate);
        if (!estate.economicOwner().equals(EconomicOwner.STATE) || estate.tenant() != null || !properties.residents(estate).isEmpty())
            throw new IllegalArgumentException("This property is not available to buy.");
        EconomyService economy = new EconomyService(ledger); economy.requireActive(context.owner());
        if (ledger.spendingCapacity(context.owner()) < estate.price()) throw new IllegalArgumentException("The company cannot afford this within its debt limit.");
        var desired = CompanyAccess.desired(c);
        desired = new CompanyAccess(c.id(), desired.owners(), desired.members(), estate.price());
        prepare(new CompanyAssetOperation(context.operation(), context.actor(), c.id(), estate.key(), BUY, context.owner(), estate.price(), System.currentTimeMillis(), CompanyAccess.empty(c.id()), desired));
        resumeNow(estate.key());
    }
    public void sell(ActionContext context, Estate estate) {
        company(context, ASSET); requireTitle(context.owner(), estate); properties.requireUnlocked(estate.key());
        if (estate.tenant() != null) throw new IllegalArgumentException("End the rental before selling this property.");
        var previous = requireAccess(estate); long refund = previous.purchaseCost() / 2;
        prepare(new CompanyAssetOperation(context.operation(), context.actor(), context.owner().id(), estate.key(), SELL, EconomicOwner.STATE,
                refund, System.currentTimeMillis(), previous, CompanyAccess.empty(context.owner().id()))); resumeNow(estate.key());
    }
    /** Players can rent from a company. Companies can't be tenants themselves. */
    public void rentToPlayer(UUID tenant, Estate estate) {
        properties.requireUnlocked(estate.key()); properties.requireOwnershipState(estate);
        CompanyService.get(server).require(estate.economicOwner().id());
        if (estate.locked() || estate.tenant() != null || estate.rent() <= 0) throw new IllegalArgumentException("Property is not available for rent");
        var access = requireAccess(estate); UUID operation = UUID.randomUUID();
        prepare(new CompanyAssetOperation(operation, tenant, estate.economicOwner().id(), estate.key(), RENT,
                estate.economicOwner(), estate.rent(), System.currentTimeMillis(), access, access)); resumeNow(estate.key());
    }
    public void transfer(ActionContext context, Estate estate, UUID player) {
        company(context, LANDLORD); requireTitle(context.owner(), estate); properties.requireUnlocked(estate.key());
        CompanyService.get(server).requireDebtFree(context.owner());
        if (estate.tenant() != null) throw new IllegalArgumentException("End the rental before giving this property away.");
        prepare(new CompanyAssetOperation(context.operation(), context.actor(), context.owner().id(), estate.key(), TRANSFER, EconomicOwner.player(player), 0,
                System.currentTimeMillis(), requireAccess(estate), new CompanyAccess(context.owner().id(), Set.of(player), Set.of()))); resumeNow(estate.key());
    }
    private void requireTitle(EconomicOwner owner, Estate estate) {
        if (!estate.economicOwner().equals(owner)) throw new IllegalArgumentException("This property does not belong to the company.");
        properties.requireOwnershipState(estate);
    }
    private CompanyAccess requireAccess(Estate estate) {
        CompanyAccess access = ledger.companies().access().get(estate.key());
        if (access == null || !access.company().equals(estate.economicOwner().id())) throw new IllegalStateException("Company property access evidence is missing"); return access;
    }
    private void prepare(CompanyAssetOperation operation) {
        var edit = ledger.companies().edit();
        if (edit.operations.putIfAbsent(operation.property(), operation) != null) throw new IllegalArgumentException("Property has an unfinished operation");
        var next = edit.build();
        EconomicOwner payer = operation.kind() == RENT ? EconomicOwner.player(operation.actor()) : EconomicOwner.company(operation.company());
        new EconomyService(ledger).requireActive(payer);
        Reservation hold = (operation.kind() == BUY || operation.kind() == RENT) && operation.amount() > 0
                ? new Reservation(operation.id(), payer, operation.amount(), true) : null;
        ledger.prepareOwned(EconomyService.stableReference("company-asset-prepare", operation.id().toString()), "company-asset-prepare|" + operation.fingerprint(), hold, next);
        FinancialPersistence.save(server); enqueue(operation.property());
    }
    public void refreshMembershipAccess(UUID company) {
        for (var estate : ledger.estatesOwned(EconomicOwner.company(company))) refreshAccess(estate.key());
    }
    private void refreshAccess(String property) {
        Estate estate = ledger.estateByKey(property);
        if (estate == null || !estate.economicOwner().isCompany() || ledger.companies().operations().containsKey(property)) return;
        Company c = ledger.companies().company(estate.economicOwner().id());
        if (c == null || c.status() != Company.Status.ACTIVE) return;
        var previous = requireAccess(estate); var mapping = CompanyAccess.desired(c);
        var desired = new CompanyAccess(c.id(), mapping.owners(), mapping.members(), previous.purchaseCost());
        if (previous.equals(desired)) return;
        var edit = ledger.companies().edit(); UUID id = UUID.randomUUID();
        edit.operations.put(property, new CompanyAssetOperation(id, null, c.id(), property, SYNC, estate.economicOwner(), 0,
                System.currentTimeMillis(), previous, desired));
        ledger.commitOwners(id, "company-membership-access|" + property, Map.of(), List.of(), edit.build(), Map.of());
        FinancialPersistence.save(server); enqueue(property);
    }
    public void recover() {
        upgradeLegacyAccess();
        boolean prepared = false;
        for (Company c : List.copyOf(ledger.companies().companies().values())) if (c.status() == Company.Status.CLOSING) prepared |= prepareClosureAssets(c, false);
        // Save every liquidation request before the queued provider work can start.
        if (prepared) FinancialPersistence.save(server);
        ledger.companies().operations().keySet().forEach(this::enqueue);
        // A company with no properties can close right away, including after a restart.
        for (Company c : ledger.companies().companies().values()) if (c.status() == Company.Status.CLOSING) finishClosure(c.id());
    }
    /** Fix the old Manager/Director mapping on upgrade instead of waiting for payday. */
    private void upgradeLegacyAccess() {
        if (!ledger.legacyCompanyAccess()) return;
        var edit = ledger.companies().edit(); boolean changed = false;
        for (var entry : List.copyOf(edit.operations.entrySet())) {
            var op = entry.getValue(); Company c = edit.companies.get(op.company());
            if (c.status() != Company.Status.ACTIVE || op.kind() != BUY && op.kind() != SYNC) continue;
            var mapping = CompanyAccess.desired(c);
            var desired = new CompanyAccess(c.id(), mapping.owners(), mapping.members(), op.desired().purchaseCost());
            if (desired.equals(op.desired())) continue;
            var owners = new HashSet<>(op.previous().owners()); owners.addAll(op.desired().owners());
            var members = new HashSet<>(op.previous().members()); members.addAll(op.desired().members()); members.removeAll(owners);
            var previous = new CompanyAccess(c.id(), owners, members, op.previous().purchaseCost());
            edit.operations.put(entry.getKey(), new CompanyAssetOperation(op.id(), op.actor(), op.company(), op.property(), op.kind(), op.target(), op.amount(), op.createdAt(), previous, desired)); changed = true;
        }
        for (Company c : ledger.companies().companies().values()) if (c.status() == Company.Status.ACTIVE) {
            var mapping = CompanyAccess.desired(c);
            for (Estate e : ledger.estatesOwned(EconomicOwner.company(c.id()))) {
                var previous = edit.access.get(e.key());
                if (previous == null || edit.operations.containsKey(e.key())) continue;
                var desired = new CompanyAccess(c.id(), mapping.owners(), mapping.members(), previous.purchaseCost());
                if (previous.equals(desired)) continue;
                UUID operation = EconomyService.stableReference("company-rank-upgrade", e.key());
                edit.operations.put(e.key(), new CompanyAssetOperation(operation, null, c.id(), e.key(), SYNC, e.economicOwner(), 0,
                        System.currentTimeMillis(), previous, desired)); changed = true;
            }
        }
        if (changed) {
            ledger.commitOwners(UUID.randomUUID(), "company-rank-upgrade", Map.of(), List.of(), edit.build(), Map.of()); FinancialPersistence.save(server);
        }
        ledger.companyAccessUpgraded();
    }
    /** Freeze finances first. Let already-funded purchases finish before handing their properties back. */
    private void prepareClosureAssets(Company c) {
        prepareClosureAssets(c, true);
    }
    private boolean prepareClosureAssets(Company c, boolean checkpoint) {
        var owner = EconomicOwner.company(c.id()); var edit = ledger.companies().edit(); boolean changed = false;
        for (Estate e : ledger.estatesOwned(owner)) {
            var pending = edit.operations.get(e.key());
            if (pending != null && (pending.kind() == RENT || pending.kind() == SELL || pending.kind() == RETURN_TO_STATE)) continue;
            CompanyAccess previous = requireAccess(e);
            if (pending != null) {
                // Include both sides of an unfinished transfer/access change so no company-managed UUID gets
                // left behind.
                var owners = new HashSet<>(previous.owners()); owners.addAll(pending.desired().owners());
                var members = new HashSet<>(previous.members()); members.addAll(pending.desired().members()); members.removeAll(owners);
                previous = new CompanyAccess(c.id(), owners, members, previous.purchaseCost());
            }
            UUID id = EconomyService.stableReference("company-liquidate-property", c.id() + ":" + e.key());
            edit.operations.put(e.key(), new CompanyAssetOperation(id, null, c.id(), e.key(), RETURN_TO_STATE, EconomicOwner.STATE, 0,
                    System.currentTimeMillis(), previous, CompanyAccess.empty(c.id()))); changed = true;
        }
        // Keep holds for funded purchases until they're captured. Release other holds once operations are
        // frozen.
        Set<UUID> secured = new HashSet<>(); edit.operations.values().forEach(o -> secured.add(o.id()));
        boolean released = false;
        for (Reservation hold : ledger.activeReservations(owner)) if (!secured.contains(hold.reference())) {
            ledger.release(hold.reference(), owner); released = true;
        }
        if (changed) ledger.commitOwners(UUID.randomUUID(), "company-liquidate-assets|" + c.id(), Map.of(), List.of(), edit.build(), Map.of());
        if (checkpoint && (changed || released)) FinancialPersistence.save(server);
        return changed || released;
    }
    private void enqueue(String property) { failed.remove(property); if (queued.add(property)) work.addLast(property); }
    private void resumeNow(String property) {
        try { process(property); FinancialPersistence.save(server); }
        catch (RuntimeException error) { throw new IllegalStateException("The property change is saved but could not finish. Ask an admin to use /companyadmin recover. Details: " + error.getMessage(), error); }
    }
    public void tick() {
        if (work.isEmpty() || FinancialWriter.busy(server)) return;
        long deadline = de.kuro.kuronomics.economy.EconomyWorkCoordinator.deadline(); int processed = 0;
        while (!work.isEmpty() && processed < 4 && (processed == 0 || System.nanoTime() < deadline)) {
            String property = work.removeFirst(); queued.remove(property); processed++;
            try { process(property); }
            catch (RuntimeException error) {
                failed.add(property);
                com.mojang.logging.LogUtils.getLogger().error("Company property maintenance is pending for {}: {}", property, error.getMessage());
            }
        }
        FinancialPersistence.save(server); // One financial checkpoint for this work slice.
    }
    public int pendingCount() { return ledger.companies().operations().size(); }
    private static Set<UUID> difference(Set<UUID> first, Set<UUID> second) { var result = new HashSet<>(first); result.removeAll(second); return result; }
    private void process(String property) {
        CompanyAssetOperation op = ledger.companies().operations().get(property); if (op == null) return;
        Estate e = ledger.estateByKey(property); if (e == null) throw new IllegalStateException("Company property disappeared");
        EconomicOwner company = EconomicOwner.company(op.company());
        if (op.kind() == BUY ? !e.economicOwner().equals(EconomicOwner.STATE) : !e.economicOwner().equals(company))
            throw new IllegalStateException("Property title changed outside the pending operation");
        var region = properties.region(e);
        Set<UUID> expectedOwners = new HashSet<>(op.previous().owners()); expectedOwners.addAll(op.desired().owners());
        if (op.kind() != SYNC && !expectedOwners.containsAll(region.owners().keySet()))
            throw new IllegalStateException("Unmanaged WorldGuard owners require administrator review before ownership transfer");
        // Check all arithmetic before touching WorldGuard. The saved hold keeps the purchase funded.
        Map<EconomicOwner, Account> updates = new HashMap<>();
        if (op.kind() == RENT) {
            EconomicOwner tenant = EconomicOwner.player(op.actor()); var hold = ledger.reservation(op.id());
            if (e.tenant() != null || hold == null || !hold.active() || !hold.economicOwner().equals(tenant) || hold.amount() != op.amount())
                throw new IllegalStateException("Rental hold or vacancy changed");
            updates.put(tenant, ledger.account(tenant).balance(Math.subtractExact(ledger.account(tenant).balance(), op.amount())));
            updates.put(company, ledger.account(company).pending(Math.addExact(ledger.account(company).pendingIncome(), op.amount())));
        }
        if (op.kind() == BUY && op.amount() > 0) {
            var hold = ledger.reservation(op.id());
            if (hold == null || !hold.active() || !hold.economicOwner().equals(company) || hold.amount() != op.amount()) throw new IllegalStateException("Company purchase hold is missing");
            updates.put(company, ledger.account(company).balance(Math.subtractExact(ledger.account(company).balance(), op.amount())));
            updates.put(EconomicOwner.STATE, ledger.account(EconomicOwner.STATE).balance(Math.addExact(ledger.account(EconomicOwner.STATE).balance(), op.amount())));
        } else if (op.kind() == SELL && op.amount() > 0) {
            updates.put(company, ledger.account(company).balance(Math.addExact(ledger.account(company).balance(), op.amount())));
            StateTreasury.stage(ledger, updates, Math.negateExact(op.amount()));
        }
        var ownerAdds = difference(op.desired().owners(), op.previous().owners());
        var ownerRemoves = difference(op.previous().owners(), op.desired().owners());
        var memberAdds = difference(op.desired().members(), op.previous().members());
        var memberRemoves = difference(op.previous().members(), op.desired().members());
        // A player who is also a tenant keeps their lease access even after losing company access.
        if (e.tenant() != null) { memberRemoves.remove(e.tenant()); memberAdds.add(e.tenant()); }
        if (op.kind() == RENT) memberAdds.add(op.actor());
        var level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(e.dimension())));
        if (level == null) throw new IllegalStateException("Property dimension unavailable");
        PropertyIntegrations.get().updateDomains(level, e.region(), ownerAdds, ownerRemoves, memberAdds, memberRemoves);
        var verified = properties.region(e);
        if (!verified.owners().keySet().containsAll(op.desired().owners()) || !verified.residents().keySet().containsAll(op.desired().members())
                || !Collections.disjoint(verified.owners().keySet(), ownerRemoves) || !Collections.disjoint(verified.residents().keySet(), memberRemoves))
            throw new IllegalStateException("WorldGuard did not apply the requested Company access deltas");
        var edit = ledger.companies().edit(); edit.operations.remove(property);
        Map<String, Estate> propertyUpdates = new HashMap<>();
        if (op.kind() == BUY || op.kind() == SYNC || op.kind() == RENT) edit.access.put(property, op.desired()); else edit.access.remove(property);
        if (op.kind() == RENT) {
            propertyUpdates.put(property, e.tenant(op.actor()));
            edit.credit(op.company(), new CompanyCredit(EconomicOwner.player(op.actor()), "Rent", property, op.amount(), true));
        } else if (op.kind() != SYNC) {
            Estate result = e.ownership(op.target());
            if (op.kind() == BUY) result = result.locked(true);
            if (op.kind() == SELL || op.kind() == RETURN_TO_STATE) result = result.locked(false).building(true);
            propertyUpdates.put(property, result);
        }
        EconomicOwner payer = op.kind() == SELL ? EconomicOwner.STATE : op.kind() == RENT ? EconomicOwner.player(op.actor()) : company;
        EconomicOwner recipient = op.kind() == BUY ? EconomicOwner.STATE : op.kind() == SELL || op.kind() == RENT ? company : op.target();
        edit.audit(new CompanyAudit(op.id(), System.currentTimeMillis(), op.actor(), company, payer, recipient, "COMPANY_PROPERTY_" + op.kind(), op.amount(), property));
        UUID commit = EconomyService.stableReference("company-asset-commit", op.id().toString());
        boolean committed;
        if ((op.kind() == BUY || op.kind() == RENT) && op.amount() > 0) committed = ledger.captureOwned(commit, "company-asset-commit|" + op.fingerprint(), op.id(), payer, op.amount(), updates, List.of(), edit.build(), propertyUpdates);
        else committed = ledger.commitOwners(commit, "company-asset-commit|" + op.fingerprint(), updates, List.of(), edit.build(), propertyUpdates);
        if (committed && propertyUpdates.containsKey(property) && !e.economicOwner().equals(propertyUpdates.get(property).economicOwner()))
            ledger.notifyAfterCommit(new de.kuro.kuronomics.api.event.PropertyOwnershipChangedEvent(op.actor(), commit, property, e.economicOwner(), propertyUpdates.get(property).economicOwner()));
        Company c = ledger.companies().company(op.company());
        if (c.status() == Company.Status.ACTIVE) refreshAccess(property);
        if (c.status() == Company.Status.CLOSING) { prepareClosureAssets(c); ledger.companies().operations().keySet().forEach(this::enqueue); finishClosure(op.company()); }
    }
    public void close(ActionContext context, CompanyConfig config) {
        Company c = company(context, CLOSE); EconomicOwner owner = context.owner();
        if (!ledger.market().owned(owner).isEmpty()) throw new IllegalArgumentException("Remove the company's marketplace listings before closing.");
        CompanyService.get(server).requireDebtFree(owner);
        if(!ledger.bankLoans().forBorrower(owner).isEmpty())throw new IllegalArgumentException("Repay the company bank loans before closing.");
        if (ledger.reserved(owner) != 0 || ledger.companies().operations().values().stream().anyMatch(o -> o.company().equals(c.id())))
            throw new IllegalArgumentException("Finish pending purchases and property changes before closing.");
        var edit = ledger.companies().edit();
        edit.contracts.values().removeIf(contract -> contract.payer().equals(owner) || contract.recipient().equals(owner));
        edit.companies.put(c.id(), c.status(Company.Status.CLOSING));
        for (Estate e : ledger.estatesOwned(owner)) {
            properties.requireUnlocked(e.key()); var previous = requireAccess(e); UUID id = EconomyService.stableReference("company-close-property", context.operation() + ":" + e.key());
            edit.operations.put(e.key(), new CompanyAssetOperation(id, context.actor(), c.id(), e.key(), RETURN_TO_STATE, EconomicOwner.STATE, 0, System.currentTimeMillis(), previous, CompanyAccess.empty(c.id())));
        }
        long taxable = 0; for (CompanyCredit credit : ledger.companies().buffer(c.id()).values()) if (credit.taxable()) taxable = Math.addExact(taxable, credit.amount());
        Account account = ledger.account(owner); long tax = config.incomeTax(taxable);
        Account closing = account.pending(0).balance(Math.subtractExact(Math.addExact(account.balance(), account.pendingIncome()), tax));
        edit.buffers.remove(c.id()); edit.audit(CompanyAudit.of(context, owner, EconomicOwner.STATE, "COMPANY_CLOSING", tax, ""));
        Map<EconomicOwner, Account> updates = new HashMap<>(); updates.put(owner, closing);
        if (tax > 0) updates.put(EconomicOwner.STATE, ledger.account(EconomicOwner.STATE).balance(Math.addExact(ledger.account(EconomicOwner.STATE).balance(), tax)));
        ledger.commitOwners(context.operation(), "company-close|" + c.id() + "|" + context.actor(), updates, List.of(), edit.build(), Map.of());
        FinancialPersistence.save(server); recover(); FinancialPersistence.save(server);
    }
    private void finishClosure(UUID id) {
        Company c = ledger.companies().company(id); EconomicOwner owner = EconomicOwner.company(id);
        if (c == null || c.status() != Company.Status.CLOSING || !ledger.estatesOwned(owner).isEmpty()
                || ledger.companies().operations().values().stream().anyMatch(o -> o.company().equals(id))) return;
        Account account = ledger.account(owner);
        if (ledger.reserved(owner) != 0) throw new IllegalStateException("Company closure has unresolved funds");
        // An already-funded rental can finish during liquidation. Its incoming money goes to the state then.
        EconomicOwner recipient = c.bankruptcy() == null ? EconomicOwner.player(c.ceo()) : EconomicOwner.STATE;
        Account player = ledger.account(recipient);
        long net = Math.addExact(account.balance(), account.pendingIncome()), payout = Math.max(0, net);
        var edit = ledger.companies().edit(); edit.companies.put(id, c.status(Company.Status.CLOSED));
        edit.buffers.remove(id);
        UUID reference = EconomyService.stableReference("company-close-final", id.toString());
        edit.audit(new CompanyAudit(reference, System.currentTimeMillis(), c.ceo(), owner, owner, recipient, "COMPANY_CLOSED", payout, ""));
        Map<EconomicOwner, Account> updates = new HashMap<>();
        updates.put(owner, account.pending(0).balance(0)); updates.put(recipient, player.balance(Math.addExact(player.balance(), payout)));
        if (c.bankruptcy() != null && net < 0) {
            edit.audit(new CompanyAudit(reference, System.currentTimeMillis(), null, owner, owner, EconomicOwner.STATE, "COMPANY_DEBT_WRITEOFF", Math.negateExact(net), ""));
            StateTreasury.stage(ledger, updates, net);
        }
        if (ledger.commitOwners(reference, "company-close-final|" + id, updates, List.of(), edit.build(), Map.of()))
            ledger.notifyAfterCommit(new de.kuro.kuronomics.api.event.CompanyChangedEvent(null, reference, c, c.status(Company.Status.CLOSED)));
    }
}

