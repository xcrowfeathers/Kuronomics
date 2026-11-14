package de.kuro.kuronomics.company;

import de.kuro.kuronomics.economy.*;
import de.kuro.kuronomics.economy.data.*;
import java.util.*;
import java.util.function.LongSupplier;
import net.minecraft.server.MinecraftServer;
import static de.kuro.kuronomics.company.CompanyPermissionService.Capability.*;

/** Commit company changes and the affected balances together. */
public final class CompanyService {
    public record FoundingQuote(UUID request, UUID player, String name, long cost, long expires) {}
    public record Invite(UUID request, ActionContext inviter, UUID player, long expires) {}
    public record Handover(UUID request, ActionContext context, UUID player, Company expected, List<CompanyContract> contracts, long expires) {}
    public enum Decision { CLOSE, CEO, LEAVE, KICK, PROMOTE, BUY, SELL, PAY, CONTRACT, SALARY, TRANSFER }
    public record Confirmation(UUID request, ActionContext context, Decision decision, String target, long amount,
                               Company expected, de.kuro.kuronomics.estate.Estate estate, String contractName, long expires) {}
    private static final Map<MinecraftServer, CompanyService> SERVICES = new WeakHashMap<>();
    private final EconomyLedger ledger;
    private final LongSupplier clock;
    private final Map<UUID, FoundingQuote> quotes = new HashMap<>();
    private final Map<UUID, Invite> invites = new HashMap<>();
    private final Map<UUID, Handover> handovers = new HashMap<>();
    private final Map<UUID, Confirmation> confirmations = new HashMap<>();
    public CompanyService(EconomyLedger ledger, LongSupplier clock) { this.ledger = ledger; this.clock = clock; }
    public static CompanyService get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Company service off server thread");
        return SERVICES.computeIfAbsent(server, s -> new CompanyService(EconomyLedger.get(s), System::currentTimeMillis));
    }
    public static void stop(MinecraftServer server) { SERVICES.remove(server); }
    public Company require(UUID id) {
        Company company = ledger.companies().company(id);
        if (company == null || company.status() != Company.Status.ACTIVE) throw new IllegalArgumentException("This company is closed or closing."); return company;
    }
    public Company named(String name) {
        Company company = ledger.companies().named(name);
        if (company == null) throw new IllegalArgumentException("No company has that name."); return company;
    }
    public Company acting(UUID actor, String name) {
        Company company;
        if (name != null) company = named(name);
        else {
            var ids = ledger.companies().memberships(actor);
            if (ids.size() != 1) throw new IllegalArgumentException("Choose a company with /company for <name> <command>.");
            company = require(ids.iterator().next());
        }
        CompanyPermissionService.require(company, actor, MEMBER); return company;
    }
    public FoundingQuote quote(UUID player, String name, CompanyConfig config) {
        requireCanFound(player);
        name = Company.displayName(name);
        if (ledger.companies().named(name) != null) throw new IllegalArgumentException("That company name is taken.");
        long now = clock.getAsLong(); quotes.values().removeIf(q -> q.expires() < now || q.player().equals(player));
        FoundingQuote quote = new FoundingQuote(UUID.randomUUID(), player, name, config.foundingCost(), Math.addExact(now, config.confirmationSeconds() * 1000L));
        quotes.put(quote.request(), quote); return quote;
    }
    public Company confirm(UUID player, UUID request) {
        requireCanFound(player);
        FoundingQuote q = quotes.get(request);
        if (q == null || !q.player().equals(player) || q.expires() < clock.getAsLong()) throw new IllegalArgumentException("This founding offer has expired. Run /company found again.");
        if (ledger.hasTransaction(request)) throw new IllegalArgumentException("You already used this confirmation.");
        if (ledger.companies().named(q.name()) != null) throw new IllegalArgumentException("That company name is taken.");
        var economy = new EconomyService(ledger); EconomicOwner founder = EconomicOwner.player(player); economy.requireActive(founder);
        if (economy.getAvailableBalance(founder) < q.cost()) throw new IllegalArgumentException("You need more money to start this company.");
        UUID id = EconomyService.stableReference("company", request.toString());
        Company c = new Company(id, q.name(), clock.getAsLong(), player, Map.of(player, new Company.Member(Company.Rank.CEO, 0)), Company.Status.ACTIVE, null);
        var edit = ledger.companies().edit(); edit.companies.put(id, c);
        edit.audit(CompanyAudit.of(new ActionContext(player, EconomicOwner.company(id), request), founder, EconomicOwner.STATE, "COMPANY_FOUND", q.cost(), ""));
        Map<EconomicOwner, Account> updates = new HashMap<>();
        updates.put(founder, economy.account(founder).balance(Math.subtractExact(economy.getBalance(founder), q.cost())));
        updates.put(EconomicOwner.STATE, economy.account(EconomicOwner.STATE).balance(Math.addExact(economy.getBalance(EconomicOwner.STATE), q.cost())));
        updates.put(EconomicOwner.company(id), Account.empty());
        if (ledger.commitOwners(request, "company-found|" + player + "|" + q.name() + "|" + q.cost(), updates, List.of(), edit.build(), Map.of()))
            ledger.notifyAfterCommit(new de.kuro.kuronomics.api.event.CompanyChangedEvent(player, request, null, c));
        quotes.remove(request); return c;
    }
    private void metadata(Company company, ActionContext context, String reason, String detail, CompanyBook.Editor edit) {
        edit.audit(CompanyAudit.of(context, context.owner(), context.owner(), reason, 0, ""));
        // The retry key describes the action. Don't put a whole staff list or payday report in it.
        var next = edit.build();
        if (ledger.commitOwners(context.operation(), reason + "|" + company.id() + "|" + context.actor() + "|" + detail, Map.of(), List.of(), next, Map.of()))
            ledger.notifyAfterCommit(new de.kuro.kuronomics.api.event.CompanyChangedEvent(context.actor(), context.operation(), company, next.company(company.id())));
    }
    public Invite invite(ActionContext context, UUID player, CompanyConfig config) {
        requireCanEmploy(player);
        Company c = require(context.owner().id()); CompanyPermissionService.manageRank(c, context.actor(), player, Company.Rank.EMPLOYEE);
        if (c.members().containsKey(player)) throw new IllegalArgumentException("That player already works here.");
        long now = clock.getAsLong();
        invites.values().removeIf(i -> i.expires() < now || i.player().equals(player) && i.inviter().owner().equals(context.owner()));
        Invite invitation = new Invite(UUID.randomUUID(), context, player, Math.addExact(now, config.confirmationSeconds() * 1000L));
        invites.put(invitation.request(), invitation); return invitation;
    }
    public Company acceptInvite(UUID player, UUID request) {
        Invite invite = invites.get(request);
        if (invite == null || !invite.player().equals(player) || invite.expires() < clock.getAsLong())
            throw new IllegalArgumentException("This job invite has expired. Ask for a new one.");
        // Check permissions again; an old invite shouldn't keep working after its sender loses authority.
        hire(new ActionContext(invite.inviter().actor(), invite.inviter().owner(), request), player);
        invites.remove(request); return require(invite.inviter().owner().id());
    }
    private void hire(ActionContext context, UUID player) {
        requireCanEmploy(player);
        Company c = require(context.owner().id()); CompanyPermissionService.manageRank(c, context.actor(), player, Company.Rank.EMPLOYEE);
        if (c.members().containsKey(player)) throw new IllegalArgumentException("That player already works here.");
        var members = new HashMap<>(c.members()); members.put(player, new Company.Member(Company.Rank.EMPLOYEE, 0));
        var edit = ledger.companies().edit(); edit.companies.put(c.id(), c.membership(members, c.ceo())); metadata(c, context, "COMPANY_HIRE", player.toString(), edit);
    }
    public void rank(ActionContext context, UUID player, Company.Rank rank) {
        Company c = require(context.owner().id()); CompanyPermissionService.manageRank(c, context.actor(), player, rank);
        var old = c.members().get(player); if (old == null) throw new IllegalArgumentException("That player does not work here.");
        var members = new HashMap<>(c.members()); members.put(player, new Company.Member(rank, old.salary()));
        var edit = ledger.companies().edit(); edit.companies.put(c.id(), c.membership(members, c.ceo())); metadata(c, context, "COMPANY_RANK", player + "|" + rank, edit);
    }
    public void remove(ActionContext context, UUID player, boolean leaving) {
        Company c = require(context.owner().id());
        if (!leaving) CompanyPermissionService.fire(c, context.actor(), player);
        else if (!player.equals(context.actor())) throw new IllegalArgumentException("Invalid departure");
        if (player.equals(c.ceo())) throw new IllegalArgumentException("Choose a new CEO or close the company before leaving.");
        var members = new HashMap<>(c.members()); if (members.remove(player) == null) throw new IllegalArgumentException("That player does not work here.");
        var edit = ledger.companies().edit(); edit.companies.put(c.id(), c.membership(members, c.ceo())); metadata(c, context, "COMPANY_REMOVE", player + "|" + leaving, edit);
    }
    public void salary(ActionContext context, UUID player, long amount) {
        Company c = require(context.owner().id()); CompanyPermissionService.salary(c, context.actor(), player);
        var old = c.members().get(player); if (old == null || amount < 0) throw new IllegalArgumentException("Choose an employee and a salary of $0 or more.");
        var members = new HashMap<>(c.members()); members.put(player, new Company.Member(old.rank(), amount));
        var edit = ledger.companies().edit(); edit.companies.put(c.id(), c.membership(members, c.ceo())); metadata(c, context, "COMPANY_SALARY", player + "|" + amount, edit);
    }
    public Handover offerHandover(ActionContext context, UUID player, CompanyConfig config) {
        Company c = require(context.owner().id()); CompanyPermissionService.require(c, context.actor(), SUCCESSION); requireDebtFree(context.owner());
        if (!c.members().containsKey(player) || c.ceo().equals(player)) throw new IllegalArgumentException("Choose another company member.");
        long now = clock.getAsLong(); handovers.values().removeIf(h -> h.expires() < now || h.context().owner().equals(context.owner()));
        Handover h = new Handover(UUID.randomUUID(), context, player, c, ledger.companies().outgoing(context.owner()), Math.addExact(now, config.confirmationSeconds() * 1000L));
        handovers.put(h.request(), h); return h;
    }
    public Company acceptHandover(UUID player, UUID request) {
        Handover h = handovers.get(request);
        if (h == null || !h.player().equals(player) || h.expires() < clock.getAsLong()) throw new IllegalArgumentException("This CEO offer has expired. Ask for a new one.");
        if (!require(h.context().owner().id()).equals(h.expected()) || !ledger.companies().outgoing(h.context().owner()).equals(h.contracts())) throw new IllegalArgumentException("The company changed. Ask for a new CEO offer.");
        successor(new ActionContext(h.context().actor(), h.context().owner(), request), player);
        handovers.remove(request); return require(h.context().owner().id());
    }
    private void successor(ActionContext context, UUID player) {
        Company c = require(context.owner().id()); CompanyPermissionService.require(c, context.actor(), SUCCESSION);
        requireDebtFree(context.owner());
        if (ledger.companies().operations().values().stream().anyMatch(o -> o.company().equals(c.id()))) throw new IllegalArgumentException("Finish the pending property changes before changing CEO.");
        var old = c.members().get(player); if (old == null || player.equals(c.ceo())) throw new IllegalArgumentException("Choose another company member.");
        var members = new HashMap<>(c.members());
        // A CEO who still runs another company can't switch to being an employee here.
        boolean leadsOthers = ledger.companies().memberships(c.ceo()).stream().anyMatch(id -> !id.equals(c.id())
                && ledger.companies().company(id).ceo().equals(c.ceo()));
        if (leadsOthers) members.remove(c.ceo());
        else members.put(c.ceo(), new Company.Member(Company.Rank.DIRECTOR, members.get(c.ceo()).salary()));
        members.put(player, new Company.Member(Company.Rank.CEO, old.salary()));
        var edit = ledger.companies().edit(); edit.companies.put(c.id(), c.membership(members, player));
        for (var estate : ledger.estatesOwned(context.owner())) {
            if (edit.operations.containsKey(estate.key())) throw new IllegalArgumentException("Finish the pending property changes before changing CEO.");
            CompanyAccess previous = edit.access.get(estate.key());
            if (previous == null) throw new IllegalStateException("Company access evidence missing");
            var owners = new HashSet<>(previous.owners()); var residents = new HashSet<>(previous.members());
            // Both the new CEO and the former CEO (now Director) keep senior property access.
            residents.remove(c.ceo());
            if (leadsOthers) owners.remove(c.ceo()); else owners.add(c.ceo());
            residents.remove(player); owners.add(player);
            var desired = new CompanyAccess(c.id(), owners, residents, previous.purchaseCost());
            UUID operation = EconomyService.stableReference("company-ceo-property", context.operation() + ":" + estate.key());
            edit.operations.put(estate.key(), new CompanyAssetOperation(operation, context.actor(), c.id(), estate.key(), CompanyAssetOperation.Kind.SYNC,
                    context.owner(), 0, clock.getAsLong(), previous, desired));
        }
        metadata(c, context, "COMPANY_CEO", player.toString(), edit);
    }
    public String contractName(EconomicOwner payer, EconomicOwner recipient, String name) {
        String result = CompanyContract.displayName(name);
        if (ledger.companies().outgoing(payer).stream().anyMatch(c -> c.recipient().equals(recipient) && c.name().equalsIgnoreCase(result)))
            throw new IllegalArgumentException("You already have a contract called '" + result + "' with that recipient.");
        return result;
    }
    public CompanyContract contract(ActionContext context, EconomicOwner payer, EconomicOwner recipient, String name, long amount) {
        if (payer.equals(EconomicOwner.STATE) || recipient.equals(EconomicOwner.STATE)) throw new IllegalArgumentException("Only admins can set Bank contracts.");
        if (!context.owner().equals(payer)) throw new IllegalArgumentException("Invalid Company contract owner");
        if (payer.isCompany()) { Company c = require(payer.id()); CompanyPermissionService.require(c, context.actor(), FINANCE); }
        else if (!payer.isPlayer() || !payer.id().equals(context.actor()) || !recipient.isCompany()) throw new IllegalArgumentException("Invalid Company contract authority");
        if (recipient.isCompany()) require(recipient.id());
        CompanyContract c = new CompanyContract(context.operation(), payer, recipient, contractName(payer, recipient, name), Money.positive(amount), CompanyContract.Status.ACTIVE, context.actor(), clock.getAsLong());
        var edit = ledger.companies().edit(); edit.contracts.put(c.id(), c); edit.audit(CompanyAudit.of(context, payer, recipient, "COMPANY_CONTRACT_CREATE", amount, c.id().toString()));
        if (ledger.commitOwners(context.operation(), "company-contract-create|" + c, Map.of(), List.of(), edit.build(), Map.of()))
            ledger.notifyAfterCommit(new de.kuro.kuronomics.api.event.ContractChangedEvent(context.actor(), context.operation(), de.kuro.kuronomics.api.event.ContractChangedEvent.Change.CREATED, c, null));
        return c;
    }
    public void cancel(ActionContext context, EconomicOwner payer, EconomicOwner recipient) {
        cancel(context, payer, recipient, null);
    }
    public void cancel(ActionContext context, EconomicOwner owner, EconomicOwner counterpart, String name) {
        cancel(context, owner, counterpart, name, null);
    }
    public void cancel(ActionContext context, EconomicOwner owner, EconomicOwner counterpart, String name, Boolean outgoing) {
        contractAuthority(context, owner);
        var matches = ledger.companies().involving(owner).filter(c -> !c.stateContract() && c.status() == CompanyContract.Status.ACTIVE
                && (c.payer().equals(owner) && c.recipient().equals(counterpart) || c.recipient().equals(owner) && c.payer().equals(counterpart))
                && (outgoing == null || c.payer().equals(owner) == outgoing)
                && (name == null || c.name().equalsIgnoreCase(name.trim()))).toList();
        if (matches.size() != 1) throw new IllegalArgumentException(matches.isEmpty() ? "No matching contract." : name == null ? "Choose a contract name." : "Choose cancel incoming or cancel outgoing.");
        cancel(context, owner, matches.getFirst().id());
    }
    private void contractAuthority(ActionContext context, EconomicOwner owner) {
        if (!context.owner().equals(owner)) throw new IllegalArgumentException("Invalid contract owner");
        if (owner.isCompany()) CompanyPermissionService.require(require(owner.id()), context.actor(), FINANCE);
        else if (!owner.isPlayer() || !owner.id().equals(context.actor())) throw new IllegalArgumentException("You cannot cancel this contract.");
    }
    public void cancel(ActionContext context, EconomicOwner owner, UUID id) {
        contractAuthority(context, owner);
        CompanyContract contract = ledger.companies().contracts().get(id);
        if (contract == null || !(contract.payer().equals(owner) || contract.recipient().equals(owner)) || contract.status() != CompanyContract.Status.ACTIVE) throw new IllegalArgumentException("No active contract with that ID.");
        if (contract.stateContract()) throw new IllegalArgumentException("Only admins can cancel Bank contracts.");
        var edit = ledger.companies().edit(); edit.contracts.remove(contract.id()); edit.audit(CompanyAudit.of(context, contract.payer(), contract.recipient(), "COMPANY_CONTRACT_CANCEL", 0, contract.id().toString()));
        if (ledger.commitOwners(context.operation(), "company-contract-cancel|" + contract.id() + "|" + context.actor(), Map.of(), List.of(), edit.build(), Map.of()))
            ledger.notifyAfterCommit(new de.kuro.kuronomics.api.event.ContractChangedEvent(context.actor(), context.operation(), de.kuro.kuronomics.api.event.ContractChangedEvent.Change.CANCELLED, contract, null));
    }
    /** Called by the permission-level-2 bankcontract command. State grants are recorded as newly created money. */
    public CompanyContract stateContract(UUID admin, UUID company, String name, long signedAmount) {
        require(company); if (signedAmount == 0 || signedAmount == Long.MIN_VALUE) throw new IllegalArgumentException("Choose a non-zero amount.");
        EconomicOwner owner = EconomicOwner.company(company); String title = CompanyContract.displayName(name);
        if (ledger.companies().involving(owner).anyMatch(c -> c.stateContract() && c.name().equalsIgnoreCase(title)))
            throw new IllegalArgumentException("A Bank contract already has that name.");
        UUID operation = UUID.randomUUID(); EconomicOwner payer = signedAmount > 0 ? EconomicOwner.STATE : owner, recipient = signedAmount > 0 ? owner : EconomicOwner.STATE;
        CompanyContract contract = new CompanyContract(operation, payer, recipient, title, Math.abs(signedAmount), CompanyContract.Status.ACTIVE,
                admin == null ? new UUID(0, 0) : admin, clock.getAsLong());
        var edit = ledger.companies().edit(); edit.contracts.put(operation, contract);
        edit.audit(CompanyAudit.of(new ActionContext(admin, owner, operation), payer, recipient, "COMPANY_STATE_CONTRACT_CREATE", contract.amount(), operation.toString()));
        ledger.commitOwners(operation, "company-state-contract-create|" + contract, Map.of(), List.of(), edit.build(), Map.of()); return contract;
    }
    public void cancelStateContract(UUID admin, UUID company, String name) {
        require(company); EconomicOwner owner = EconomicOwner.company(company);
        var matches = ledger.companies().involving(owner).filter(c -> c.stateContract() && c.name().equalsIgnoreCase(name.trim())).toList();
        if (matches.isEmpty()) throw new IllegalArgumentException("No matching Bank contract.");
        var edit = ledger.companies().edit(); UUID operation = UUID.randomUUID();
        for (var c : matches) { edit.contracts.remove(c.id()); edit.audit(CompanyAudit.of(new ActionContext(admin, owner, operation), c.payer(), c.recipient(), "COMPANY_STATE_CONTRACT_CANCEL", 0, c.id().toString())); }
        ledger.commitOwners(operation, "company-state-contract-cancel|" + company + "|" + name, Map.of(), List.of(), edit.build(), Map.of());
    }
    public void requireDebtFree(EconomicOwner owner) {
        if (ledger.account(owner).balance() < 0) throw new IllegalArgumentException("Pay off the company's debt first.");
    }
    private void requireCanFound(UUID player) {
        if (ledger.companies().memberships(player).stream().anyMatch(id -> !ledger.companies().company(id).ceo().equals(player)))
            throw new IllegalArgumentException("Leave your current company job before founding a company.");
    }
    private void requireCanEmploy(UUID player) {
        if (!ledger.companies().memberships(player).isEmpty())
            throw new IllegalArgumentException("That player already works for or leads a company.");
    }
    public Confirmation request(ActionContext context, Decision decision, String target, long amount,
                                de.kuro.kuronomics.estate.Estate estate, CompanyConfig config) {
        return request(context, decision, target, amount, estate, null, config);
    }
    public Confirmation request(ActionContext context, Decision decision, String target, long amount,
                                de.kuro.kuronomics.estate.Estate estate, String contractName, CompanyConfig config) {
        if (decision == Decision.CONTRACT) contractName = CompanyContract.displayName(contractName);
        Company c = require(context.owner().id());
        long now = clock.getAsLong(); confirmations.values().removeIf(q -> q.expires() < now || q.context().actor().equals(context.actor()));
        Confirmation q = new Confirmation(UUID.randomUUID(), context, decision, target, amount, c, estate, contractName, Math.addExact(now, config.confirmationSeconds() * 1000L));
        confirmations.put(q.request(), q); return q;
    }
    public Confirmation confirmation(UUID actor, UUID request) {
        Confirmation q = confirmations.get(request);
        if (q == null || !q.context().actor().equals(actor) || q.expires() < clock.getAsLong()) throw new IllegalArgumentException("This confirmation has expired. Run the command again.");
        if (!require(q.context().owner().id()).equals(q.expected())) throw new IllegalArgumentException("The company changed. Run the command again.");
        if (q.estate() != null && !q.estate().equals(ledger.estateByKey(q.estate().key()))) throw new IllegalArgumentException("The property changed. Run the command again.");
        return q;
    }
    public void consume(UUID request) { confirmations.remove(request); }
}
