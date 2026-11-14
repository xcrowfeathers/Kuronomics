package de.kuro.kuronomics.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.*;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.company.*;
import de.kuro.kuronomics.economy.*;
import de.kuro.kuronomics.estate.*;
import de.kuro.kuronomics.mailbox.MailboxStore;
import de.kuro.kuronomics.util.PlayerNames;
import java.util.*;
import net.minecraft.commands.*;
import net.minecraft.network.chat.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import static net.minecraft.commands.Commands.*;
import static de.kuro.kuronomics.command.EconomyCommands.*;
import static de.kuro.kuronomics.company.CompanyPermissionService.Capability.*;
import static de.kuro.kuronomics.company.CompanyService.Decision.*;

/** The selected company only applies to this command. */
@EventBusSubscriber(modid = Kuronomics.MODID)
public final class CompanyCommands {
    private static CompanyService service(CommandContext<CommandSourceStack> c) { return CompanyService.get(c.getSource().getServer()); }
    private static CompanyConfig config(CommandContext<CommandSourceStack> c) { return CompanyRuntime.get(c.getSource().getServer()).config(); }
    private static ServerPlayer actor(CommandContext<CommandSourceStack> c) throws CommandSyntaxException { return c.getSource().getPlayerOrException(); }
    private static Company selected(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        String name = null; try { name = StringArgumentType.getString(c, "actingCompany"); } catch (IllegalArgumentException absent) { }
        return service(c).acting(actor(c).getUUID(), name);
    }
    private static ActionContext context(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        return new ActionContext(actor(c).getUUID(), EconomicOwner.company(selected(c).id()), UUID.randomUUID());
    }
    public static List<String> companyNames(MinecraftServer server) {
        return de.kuro.kuronomics.economy.data.EconomyLedger.get(server).companies().companies().values().stream()
                .filter(c -> c.status() != Company.Status.CLOSED).map(Company::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }
    public static List<String> playerNames(MinecraftServer server) {
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER); names.addAll(MailboxStore.get(server).knownPlayers().values());
        server.getPlayerList().getPlayers().forEach(p -> names.add(p.getGameProfile().getName())); return List.copyOf(names);
    }
    private static RequiredArgumentBuilder<CommandSourceStack, String> companies(String name) {
        return argument(name, StringArgumentType.string()).suggests((c, b) -> SharedSuggestionProvider.suggest(
                companyNames(c.getSource().getServer()).stream().map(StringArgumentType::escapeIfRequired), b));
    }
    private static RequiredArgumentBuilder<CommandSourceStack, String> players(boolean membersOnly) {
        return argument("player", StringArgumentType.word()).suggests((c, b) -> {
            try {
                var names = membersOnly ? selected(c).members().keySet().stream().map(id -> PlayerNames.get(c.getSource().getServer(), id)).sorted().toList() : playerNames(c.getSource().getServer());
                return SharedSuggestionProvider.suggest(names, b);
            } catch (CommandSyntaxException | RuntimeException failure) { return b.buildFuture(); }
        });
    }
    private static UUID player(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "player"); var server = c.getSource().getServer();
        var online = server.getPlayerList().getPlayerByName(name); if (online != null) return online.getUUID();
        var known = MailboxStore.get(server).knownPlayers().entrySet().stream().filter(e -> e.getValue().equalsIgnoreCase(name)).map(Map.Entry::getKey).toList();
        if (known.size() == 1) return known.getFirst();
        throw new IllegalArgumentException("Can't find that player. They need to have joined this server.");
    }
    private static EconomicOwner targetCompany(CommandContext<CommandSourceStack> c) { return EconomicOwner.company(service(c).named(StringArgumentType.getString(c, "company")).id()); }
    @FunctionalInterface private interface Mutation { void run(CommandContext<CommandSourceStack> c) throws CommandSyntaxException; }
    private static Command<CommandSourceStack> change(Mutation operation, String success) {
        return safe(c -> { operation.run(c); FinancialPersistence.save(c.getSource().getServer()); return message(c, "§a" + success); });
    }
    private static int send(CommandContext<CommandSourceStack> c, Component component) { c.getSource().sendSuccess(() -> component, false); return 1; }
    private static int ask(CommandContext<CommandSourceStack> c, ActionContext ctx, CompanyService.Decision decision, String target, long amount, Estate estate, String warning) {
        return ask(c, ctx, decision, target, amount, estate, null, warning);
    }
    private static int ask(CommandContext<CommandSourceStack> c, ActionContext ctx, CompanyService.Decision decision, String target, long amount, Estate estate, String contractName, String warning) {
        var quote = service(c).request(ctx, decision, target, amount, estate, contractName, config(c));
        if (c.getNodes().getLast().getNode().getName().equals("confirm")) {
            try { return confirm(c, quote.request()); } catch (CommandSyntaxException failure) { throw new IllegalArgumentException(failure.getMessage()); }
        }
        return send(c, CompanyChat.confirm(selectedName(c, ctx), warning, "Confirm", "/company confirm " + quote.request(), config(c).confirmationSeconds()));
    }
    private static String selectedName(CommandContext<CommandSourceStack> c, ActionContext ctx) { return ledger(c).companies().company(ctx.owner().id()).name(); }
    private static String name(CommandContext<CommandSourceStack> c, UUID id) { return PlayerNames.get(c.getSource().getServer(), id); }
    private static String debtWarning(CommandContext<CommandSourceStack> c, ActionContext ctx, long amount) {
        long after = Math.subtractExact(ledger(c).account(ctx.owner()).balance(), amount);
        return "§eThis uses the company's credit.\n§7Debt after payment: §c" + Money.format(Math.max(0, Math.negateExact(Math.min(0, after))))
                + "\n§7Debt grows by §c" + config(c).debtFeePercent().toPlainString() + "%§7 each payday. At §c" + Money.format(config(c).debtLimit())
                + "§7, the company closes and the CEO pays §c" + config(c).ceoPenaltyPercent().toPlainString() + "%§7 of its debt.";
    }
    private static Runnable finance(CommandContext<CommandSourceStack> c, ActionContext ctx, EconomicOwner target, long amount, String contractName) {
        CompanyPermissionService.require(service(c).require(ctx.owner().id()), ctx.actor(), FINANCE);
        if (contractName != null) service(c).contract(ctx, ctx.owner(), target, contractName, amount);
        else if (new EconomyService(ledger(c)).transfer(ctx.owner(), target, amount, !target.equals(EconomicOwner.STATE), ctx, "COMPANY_PAYMENT", "", true)) {
            if (!target.equals(EconomicOwner.STATE)) {
                try {
                var server = c.getSource().getServer(); var book = ledger(c).companies();
                UUID recipient = target.isPlayer() ? target.id() : book.company(target.id()).ceo();
                String source = book.company(ctx.owner().id()).name();
                String subject = "Payment from " + source + (target.isCompany() ? " to " + book.company(target.id()).name() : "");
                boolean added = new de.kuro.kuronomics.mailbox.MailboxService(server).system(recipient, ctx.operation(), subject,
                        List.of(new de.kuro.kuronomics.mailbox.MailboxDetail(de.kuro.kuronomics.mailbox.MailboxDetail.Kind.INCOMING, "Available at next payday", amount)));
                var online = server.getPlayerList().getPlayer(recipient);
                if (added) return () -> {
                    var recipientPlayer = server.getPlayerList().getPlayer(recipient);
                    if (recipientPlayer != null) recipientPlayer.sendSystemMessage(de.kuro.kuronomics.util.ModText.legacy("§a" + subject + ": " + Money.format(amount) + " §7(available at next payday)."));
                };
                } catch (RuntimeException failure) {
                    com.mojang.logging.LogUtils.getLogger().error("Company payment committed but recipient notification failed", failure);
                }
            }
        }
        return () -> {};
    }
    private static LiteralArgumentBuilder<CommandSourceStack> moneyTarget(String type, boolean cancel, boolean contract) { return moneyTarget(type, cancel, contract, null); }
    private static LiteralArgumentBuilder<CommandSourceStack> moneyTarget(String type, boolean cancel, boolean contract, Boolean outgoing) {
        var result = literal(type); Command<CommandSourceStack> action = safe(c -> {
            var ctx = context(c); var target = type.equals("company") ? targetCompany(c) : type.equals("player") ? EconomicOwner.player(player(c)) : EconomicOwner.STATE;
            CompanyPermissionService.require(selected(c), ctx.actor(), FINANCE);
            if (cancel) { service(c).cancel(ctx, ctx.owner(), target, optional(c, "contractName"), outgoing); FinancialPersistence.save(c.getSource().getServer()); return message(c, "§aCompany contract cancelled."); }
            long value = Money.positive(amount(c, "amount")); String recipient = PlayerNames.owner(c.getSource().getServer(), target);
            if (contract) {
                String name = service(c).contractName(ctx.owner(), target, StringArgumentType.getString(c, "contractName"));
                return ask(c, ctx, CONTRACT, target.toString(), value, null, name, "§7Start §e" + name + "§7?\n§7Pay §e" + recipient + " " + Money.format(value)
                        + "§7 every company payday.\n§ePayments continue while everyone is offline and can put the company in debt.");
            }
            if (new EconomyService(ledger(c)).getAvailableBalance(ctx.owner()) < value) return ask(c, ctx, PAY, target.toString(), value, null,
                    "§7Pay §e" + recipient + " " + Money.format(value) + "§7?\n" + debtWarning(c, ctx, value));
            FinancialSettlements.requireCapacity(c.getSource().getServer());
            var notification = finance(c, ctx, target, value, null);
            FinancialSettlements.after(c.getSource().getServer(), () -> { notification.run(); message(c, "§aPaid §e" + recipient + " " + Money.format(value) + "§a."); },
                    failure -> c.getSource().sendFailure(Component.literal("Payment storage failed; completion is uncertain. Contact an administrator before retrying.")));
            return message(c, "§7Payment processing…");
        });
        if (type.equals("state") || type.equals("bank")) return result.then(argument("amount", StringArgumentType.word()).executes(action));
        var target = type.equals("company") ? companies("company") : players(false);
        if (cancel) target.executes(action).then(contractNameArgument(true, type, outgoing).executes(action));
        else if (contract) target.then(argument("contractName", StringArgumentType.string()).then(argument("amount", StringArgumentType.word()).executes(action)));
        else target.then(argument("amount", StringArgumentType.word()).executes(action));
        return result.then(target);
    }
    private static EconomicOwner parseOwner(String text) {
        // This comes from our confirmation token, not an account picked by the client.
        for (EconomicOwner.Type type : EconomicOwner.Type.values()) {
            if (text.equals(EconomicOwner.STATE.toString())) return EconomicOwner.STATE;
            String prefix = type.name() + ":"; if (text.startsWith(prefix)) return new EconomicOwner(type, UUID.fromString(text.substring(prefix.length())));
        }
        throw new IllegalArgumentException("That payment confirmation is invalid.");
    }
    private static int confirm(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        return confirm(c, UUID.fromString(StringArgumentType.getString(c, "confirmation")));
    }
    private static int confirm(CommandContext<CommandSourceStack> c, UUID request) throws CommandSyntaxException {
        var q = service(c).confirmation(actor(c).getUUID(), request);
        var ctx = q.context(); var assets = CompanyAssetService.get(c.getSource().getServer()); String response;
        if (q.decision() == PAY) {
            FinancialSettlements.requireCapacity(c.getSource().getServer());
            var notification = finance(c, ctx, parseOwner(q.target()), q.amount(), null);
            service(c).consume(request);
            FinancialSettlements.after(c.getSource().getServer(), () -> { notification.run(); message(c, "§aPayment sent."); },
                    failure -> c.getSource().sendFailure(Component.literal("Payment storage failed; completion is uncertain. Contact an administrator before retrying.")));
            return message(c, "§7Payment processing…");
        }
        switch (q.decision()) {
            case CLOSE -> { assets.close(ctx, config(c)); response = "Company closing."; }
            case CEO -> {
                UUID target = UUID.fromString(q.target()); var candidate = c.getSource().getServer().getPlayerList().getPlayer(target);
                if (candidate == null) throw new IllegalArgumentException("That player is offline.");
                var offer = service(c).offerHandover(ctx, target, config(c));
                candidate.sendSystemMessage(CompanyChat.confirm("Become CEO · " + q.expected().name(), "§7" + name(c, ctx.actor()) + " wants you to lead the company.\n§eYou'll control it and be personally responsible for its debt.\n§7At " + Money.format(config(c).debtLimit()) + " debt, it closes and you're charged " + config(c).ceoPenaltyPercent().toPlainString() + "% of the debt.", "Become CEO", "/company acceptceo " + offer.request(), config(c).confirmationSeconds()));
                response = "CEO offer sent.";
            }
            case LEAVE -> { service(c).remove(ctx, ctx.actor(), true); response = "You left " + q.expected().name() + "."; }
            case KICK -> { service(c).remove(ctx, UUID.fromString(q.target()), false); response = name(c, UUID.fromString(q.target())) + " no longer works here."; }
            case PROMOTE -> { var parts = q.target().split(":", 2); service(c).rank(ctx, UUID.fromString(parts[0]), Company.Rank.valueOf(parts[1])); response = "Rank updated."; }
            case BUY -> { assets.buy(ctx, q.estate()); response = "Bought " + q.estate().region() + " for the company."; }
            case SELL -> { assets.sell(ctx, q.estate()); response = "Sold " + q.estate().region() + "."; }
            case TRANSFER -> { assets.transfer(ctx, q.estate(), UUID.fromString(q.target())); response = "Property given to " + name(c, UUID.fromString(q.target())) + "."; }
            case PAY, CONTRACT -> { finance(c, ctx, parseOwner(q.target()), q.amount(), q.contractName()); response = q.decision() == CONTRACT ? "Company contract created." : "Payment sent."; }
            case SALARY -> { service(c).salary(ctx, UUID.fromString(q.target()), q.amount()); response = "Salary set to " + Money.format(q.amount()) + "."; }
            default -> throw new IllegalArgumentException("Unknown confirmation.");
        }
        FinancialPersistence.save(c.getSource().getServer()); service(c).consume(request); return message(c, "§a" + response);
    }
    public static int requestTransfer(CommandContext<CommandSourceStack> c, ServerPlayer player, Estate estate, UUID recipient) {
        var ctx = new ActionContext(player.getUUID(), estate.economicOwner(), UUID.randomUUID());
        CompanyPermissionService.require(service(c).require(ctx.owner().id()), ctx.actor(), LANDLORD); service(c).requireDebtFree(ctx.owner());
        return ask(c, ctx, TRANSFER, recipient.toString(), 0, estate, "§7Give §e" + estate.region() + "§7 to §e" + name(c, recipient) + "§7?\n§cThe company loses this property. It receives no money.");
    }
    private static LiteralArgumentBuilder<CommandSourceStack> actions(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(literal("balance").executes(safe(c -> insight(c, selected(c), false))));
        root.then(literal("deposit").then(argument("amount", StringArgumentType.word()).executes(change(c -> {
            var ctx = context(c); new EconomyService(ledger(c)).transfer(EconomicOwner.player(ctx.actor()), ctx.owner(), amount(c, "amount"), false, ctx, "COMPANY_CAPITAL", "", false);
        }, "Deposit made."))));
        root.then(literal("hire").then(players(false).executes(safe(c -> {
            var ctx = context(c); UUID target = player(c); var invited = c.getSource().getServer().getPlayerList().getPlayer(target);
            if (invited == null) throw new IllegalArgumentException("That player is offline.");
            var invite = service(c).invite(ctx, target, config(c));
            invited.sendSystemMessage(CompanyChat.confirm("Job Invite · " + selected(c).name(), "§e" + actor(c).getGameProfile().getName() + "§7 invited you to join as an §fEmployee§7.\n§7Starting salary: §f$0§7.", "Join Company", "/company join " + invite.request(), config(c).confirmationSeconds()));
            return message(c, "§aJob invite sent to §e" + name(c, target) + "§a.");
        }))));
        root.then(literal("kick").then(players(true).executes(safe(c -> {
            var ctx = context(c); UUID target = player(c); CompanyPermissionService.fire(selected(c), ctx.actor(), target);
            if (!selected(c).members().containsKey(target)) throw new IllegalArgumentException("That player doesn't work here.");
            return ask(c, ctx, KICK, target.toString(), 0, null, "§7Remove §e" + name(c, target) + "§7 from the company?\n§eTheir salary stops. Company property access is removed at payday.");
        }))));
        root.then(literal("leave").executes(safe(c -> {
            var ctx = context(c); if (selected(c).ceo().equals(ctx.actor())) { service(c).requireDebtFree(ctx.owner()); throw new IllegalArgumentException("Choose a new CEO or close the company before leaving."); }
            return ask(c, ctx, LEAVE, "", 0, null, "§7Leave §e" + selected(c).name() + "§7?\n§eYour salary stops. Company property access is removed at payday.");
        })));
        root.then(literal("promote").then(players(true).then(argument("rank", StringArgumentType.word()).suggests((c, b) -> {
            try {
                var company = selected(c); var current = company.members().get(player(c)); var authority = company.members().get(actor(c).getUUID());
                return SharedSuggestionProvider.suggest(Arrays.stream(Company.Rank.values()).filter(r -> current != null && r.ordinal() > current.rank().ordinal() && r.ordinal() < authority.rank().ordinal()).map(Enum::name), b);
            } catch (CommandSyntaxException | RuntimeException failure) { return b.buildFuture(); }
        }).executes(safe(c -> {
            Company company = selected(c); UUID target = player(c); Company.Rank rank;
            try { rank = Company.Rank.valueOf(StringArgumentType.getString(c, "rank").toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Choose Employee, Manager or Director."); }
            var member = company.members().get(target); if (member == null || rank.ordinal() <= member.rank().ordinal()) throw new IllegalArgumentException("Choose a higher rank. Use demote to lower it.");
            var ctx = context(c); CompanyPermissionService.manageRank(company, ctx.actor(), target, rank);
            if (rank == Company.Rank.DIRECTOR) return ask(c, ctx, PROMOTE, target + ":" + rank.name(), 0, null,
                    "§7Make §e" + name(c, target) + "§7 a §6Director§7?\n§eThey can spend company money, manage staff and handle properties.");
            service(c).rank(ctx, target, rank); FinancialPersistence.save(c.getSource().getServer()); return message(c, "§a" + name(c, target) + " is now a " + CompanyChat.rank(rank) + ".");
        })))));
        root.then(literal("demote").then(players(true).executes(change(c -> {
            var member = selected(c).members().get(player(c));
            if (member == null || member.rank() == Company.Rank.EMPLOYEE || member.rank() == Company.Rank.CEO) throw new IllegalArgumentException("That rank can't be demoted. Use /company ceo to change the CEO.");
            service(c).rank(context(c), player(c), Company.Rank.values()[member.rank().ordinal() - 1]);
        }, "Rank lowered."))));
        root.then(literal("salary").then(players(true).then(argument("amount", StringArgumentType.word()).executes(safe(c -> {
            var ctx = context(c); UUID target = player(c); long value = amount(c, "amount"); CompanyPermissionService.salary(selected(c), ctx.actor(), target);
            if (value < 0 || !selected(c).members().containsKey(target)) throw new IllegalArgumentException("Choose an employee and a salary of $0 or more.");
            return ask(c, ctx, SALARY, target.toString(), value, null, "§7Set §e" + name(c, target) + "§7's salary to §e" + Money.format(value) + "§7 per company payday?\n"
                    + (value == 0 ? "§eThis stops their salary." : "§eSalary is paid while they're offline too. It can put the company in debt."));
        })))));
        root.then(literal("ceo").then(players(true).executes(safe(c -> {
            var ctx = context(c); UUID target = player(c); var company = selected(c);
            CompanyPermissionService.require(company, ctx.actor(), SUCCESSION); service(c).requireDebtFree(ctx.owner());
            if (!company.members().containsKey(target) || target.equals(company.ceo())) throw new IllegalArgumentException("Choose another company member.");
            if (c.getSource().getServer().getPlayerList().getPlayer(target) == null) throw new IllegalArgumentException("That player is offline.");
            boolean leadsOthers = ledger(c).companies().memberships(ctx.actor()).stream().anyMatch(id -> !id.equals(company.id()) && ledger(c).companies().company(id).ceo().equals(ctx.actor()));
            return ask(c, ctx, CEO, target.toString(), 0, null, "§7Hand the company to §e" + name(c, target) + "§7?\n§c"
                    + (leadsOthers ? "You'll leave this company because you still lead another one." : "You'll lose CEO control and become a Director.")
                    + "\n§eThe new CEO takes responsibility for company debt and closure.");
        }))));
        root.then(literal("lastpayday").executes(safe(c -> {
            Company company = selected(c); CompanyPermissionService.require(company, actor(c).getUUID(), STATEMENT);
            if (company.lastPayday() == null) return message(c, "§7This company hasn't had a payday yet.");
            return send(c, CompanyRuntime.statement(c.getSource().getServer(), company, company.lastPayday()));
        })));
        root.then(literal("close").executes(safe(c -> {
            var ctx = context(c); CompanyPermissionService.require(selected(c), ctx.actor(), CompanyPermissionService.Capability.CLOSE); service(c).requireDebtFree(ctx.owner());
            return ask(c, ctx, CompanyService.Decision.CLOSE, "", 0, null, "§cClose " + selected(c).name() + " for good?\n§7Everyone loses their job here and all company contracts end.\n§7Properties return to the Bank. Remaining money goes to you.\n§eThe founding fee isn't refunded. This can't be undone.");
        })));
        var pay = literal("pay"); for (String type : List.of("player", "company", "bank", "state")) pay.then(moneyTarget(type, false, false)); root.then(pay);
        var contracts = literal("contract"); var create = literal("create"); var cancel = literal("cancel");
        for (String type : List.of("company", "player")) { create.then(moneyTarget(type, false, true)); cancel.then(moneyTarget(type, true, true)); }
        for (boolean out : List.of(false, true)) { var direction = literal(out ? "outgoing" : "incoming"); for (String type : List.of("company", "player")) direction.then(moneyTarget(type, true, true, out)); cancel.then(direction); }
        contracts.then(create).then(cancel).then(literal("list").executes(safe(c -> contractList(c, EconomicOwner.company(selected(c).id()))))); root.then(contracts);
        var estates = literal("estate");
        for (String operation : List.of("buy", "sell", "info")) {
            var estate = argument("estate", StringArgumentType.string()).suggests((c, b) -> {
                try {
                    var owner = EconomicOwner.company(selected(c).id());
                    return SharedSuggestionProvider.suggest(ledger(c).estates().stream().filter(e -> operation.equals("buy") ? e.economicOwner().equals(EconomicOwner.STATE) && e.tenant() == null : e.economicOwner().equals(owner)).map(e -> StringArgumentType.escapeIfRequired(e.key())), b);
                } catch (CommandSyntaxException | RuntimeException failure) { return b.buildFuture(); }
            });
            Command<CommandSourceStack> action = safe(c -> {
                var ctx = context(c); var e = new EstateService(c.getSource().getServer()).find(actor(c), optionalEstate(c));
                if (operation.equals("info")) {
                    if (!e.economicOwner().equals(ctx.owner())) throw new IllegalArgumentException("This property doesn't belong to the company.");
                    return send(c, CompanyChat.finish(CompanyChat.card(e.region(), "§7Owner: §e" + selected(c).name() + "\n§7Price: §a" + Money.format(e.price()) + "\n§7Rent: §a" + Money.format(e.rent()) + "§7 per tenant payday")));
                }
                CompanyPermissionService.require(selected(c), ctx.actor(), ASSET);
                if (operation.equals("buy")) return ask(c, ctx, BUY, "", e.price(), e, "§7Buy §e" + e.region() + "§7 for §e" + Money.format(e.price()) + "§7?"
                        + (new EconomyService(ledger(c)).getAvailableBalance(ctx.owner()) < e.price() ? "\n" + debtWarning(c, ctx, e.price()) : ""));
                if (!e.economicOwner().equals(ctx.owner())) throw new IllegalArgumentException("This property doesn't belong to the company.");
                var access = ledger(c).companies().access().get(e.key());
                return ask(c, ctx, SELL, "", 0, e, "§7Sell §e" + e.region() + "§7 back to the Bank?\n§7Company refund: §a" + Money.format(access.purchaseCost() / 2) + "");
            });
            estates.then(literal(operation).executes(action).then(estate.executes(action)));
        }
        estates.then(literal("list").executes(safe(c -> {
            var owned = ledger(c).estatesOwned(EconomicOwner.company(selected(c).id()));
            return send(c, CompanyChat.finish(CompanyChat.card("Properties · " + selected(c).name(), "§7" + (owned.isEmpty() ? "No properties yet." : owned.stream().map(Estate::region).sorted().collect(java.util.stream.Collectors.joining("\n§e"))))));
        }))); root.then(estates);
        root.then(literal("history").executes(safe(c -> {
            Company company = selected(c); CompanyPermissionService.require(company, actor(c).getUUID(), FINANCE);
            var owner = EconomicOwner.company(company.id()); var entries = ledger(c).companies().audit().stream().filter(a -> a.owner().equals(owner) || a.payer().equals(owner) || a.recipient().equals(owner)).toList();
            var card = CompanyChat.card("Recent Activity · " + company.name(), entries.isEmpty() ? "§7Nothing here yet." : "");
            for (var a : entries.subList(Math.max(0, entries.size() - 15), entries.size())) card.append(de.kuro.kuronomics.util.ModText.legacy("§e" + auditLabel(ledger(c).companies(), a) + " §8· §7"
                    + (a.actor() == null ? "Server" : name(c, a.actor())) + "\n§7" + PlayerNames.owner(c.getSource().getServer(), a.payer()) + " → " + PlayerNames.owner(c.getSource().getServer(), a.recipient()) + " §f" + Money.format(a.amount()) + "\n"));
            return send(c, CompanyChat.finish(card));
        })));
        return root;
    }
    private static String optionalEstate(CommandContext<CommandSourceStack> c) { try { return StringArgumentType.getString(c, "estate"); } catch (IllegalArgumentException absent) { return null; } }
    private static String optional(CommandContext<CommandSourceStack> c, String key) { try { return StringArgumentType.getString(c, key); } catch (IllegalArgumentException absent) { return null; } }
    private static String auditLabel(CompanyBook book, CompanyAudit entry) {
        String label = CompanyChat.category(entry.category());
        if (entry.category().contains("CONTRACT") || entry.category().equals("Contract")) {
            try {
                var contract = book.contracts().get(UUID.fromString(entry.asset()));
                if (contract != null) return label + " · " + contract.name();
            } catch (IllegalArgumentException legacyAssociation) { }
        }
        return label;
    }
    private static RequiredArgumentBuilder<CommandSourceStack, String> contractNameArgument(boolean corporate, String type) { return contractNameArgument(corporate, type, null); }
    private static RequiredArgumentBuilder<CommandSourceStack, String> contractNameArgument(boolean corporate, String type, Boolean outgoing) {
        return argument("contractName", StringArgumentType.greedyString()).suggests((c, b) -> {
            try {
                EconomicOwner owner = corporate ? EconomicOwner.company(selected(c).id()) : EconomicOwner.player(actor(c).getUUID());
                EconomicOwner counterpart = type.equals("company") ? targetCompany(c) : EconomicOwner.player(player(c));
                return SharedSuggestionProvider.suggest(ledger(c).companies().involving(owner)
                        .filter(t -> !t.stateContract() && (outgoing == null || t.payer().equals(owner) == outgoing) && t.status() == CompanyContract.Status.ACTIVE && (t.payer().equals(owner) && t.recipient().equals(counterpart)
                                || t.recipient().equals(owner) && t.payer().equals(counterpart)))
                        .map(CompanyContract::name).distinct().sorted(String.CASE_INSENSITIVE_ORDER), b);
            } catch (CommandSyntaxException | RuntimeException failure) { return b.buildFuture(); }
        });
    }
    private static int contractList(CommandContext<CommandSourceStack> c, EconomicOwner payer) throws CommandSyntaxException {
        if (payer.isCompany()) CompanyPermissionService.require(selected(c), actor(c).getUUID(), FINANCE);
        var values = ledger(c).companies().involving(payer).sorted(Comparator.comparing(CompanyContract::name, String.CASE_INSENSITIVE_ORDER).thenComparing(CompanyContract::id)).toList();
        var card = CompanyChat.card("Contracts", values.isEmpty() ? "§7No active contracts." : "");
        for (boolean outgoing : List.of(false, true)) {
            var section = values.stream().filter(t -> t.payer().equals(payer) == outgoing).toList();
            if (values.isEmpty()) break;
            card.append(de.kuro.kuronomics.util.ModText.legacy("\n§b§l" + (outgoing ? "Outgoing" : "Incoming") + "§r\n"));
            if (section.isEmpty()) card.append(de.kuro.kuronomics.util.ModText.legacy("§7None.\n"));
            for (var t : section) {
            var counterpart = outgoing ? t.recipient() : t.payer();
            card.append(de.kuro.kuronomics.util.ModText.legacy("§6" + t.name() + " §8· §7" + (outgoing ? "To " : "From ") + "§e" + PlayerNames.owner(c.getSource().getServer(), counterpart) + " §8· §a" + Money.format(t.amount()) + "§7 / " + (t.stateContract() || t.payer().isCompany() ? "company" : "personal") + " payday "));
            if (!t.stateContract()) {
                String direction = outgoing ? "outgoing " : "incoming ";
                String target = counterpart.isCompany() ? StringArgumentType.escapeIfRequired(ledger(c).companies().company(counterpart.id()).name()) : PlayerNames.get(c.getSource().getServer(), counterpart.id());
                String command = payer.isCompany() ? "/company for " + StringArgumentType.escapeIfRequired(ledger(c).companies().company(payer.id()).name()) + " contract cancel " + direction + (counterpart.isCompany() ? "company " : "player ") + target : "/company contract personal cancel " + direction + target;
                card.append(CompanyChat.button("Cancel", command + " " + t.name(), net.minecraft.ChatFormatting.RED));
            }
            card.append(Component.literal("\n"));
            }
        }
        return send(c, CompanyChat.finish(card));
    }
    private static int insight(CommandContext<CommandSourceStack> c, Company company, boolean full) {
        var owner = EconomicOwner.company(company.id()); var account = ledger(c).account(owner); long reserved = ledger(c).reserved(owner);
        var card = CompanyChat.card("Company Insight · " + company.name(), "§7Available Capital: §a" + Money.format(Math.max(0, Math.subtractExact(account.balance(), reserved)))
                + "\n§7Open Payments: §b" + Money.format(account.pendingIncome())
                + "\n§7Reserved Balance: §e" + Money.format(reserved)
                + "\n§7Account Balance: " + (account.balance() < 0 ? "§c" : "§a") + Money.format(account.balance()));
        if (company.status() == Company.Status.ACTIVE) card.append(de.kuro.kuronomics.util.ModText.legacy("\n§7Credit Left: §e" + Money.format(Math.min(config(c).debtLimit(), Math.max(0, ledger(c).spendingCapacity(owner))))));
        if (account.balance() < 0) card.append(de.kuro.kuronomics.util.ModText.legacy("\n§7Debt: §c" + Money.format(Math.negateExact(account.balance())) + " §8(+" + config(c).debtFeePercent().toPlainString() + "% each payday)"));
        if (full) {
            card.append(de.kuro.kuronomics.util.ModText.legacy("\n\n§7CEO: §6" + name(c, company.ceo()) + "\n§7Status: §f" + (company.status() == Company.Status.ACTIVE ? "Open" : company.status() == Company.Status.CLOSING ? "Closing" : "Closed") + "\n§7Employees: §f" + company.members().size() + "\n\n§6Team"));
            for (var member : company.members().entrySet().stream().sorted(Comparator.<Map.Entry<UUID, Company.Member>>comparingInt(e -> e.getValue().rank().ordinal()).reversed().thenComparing(Map.Entry::getKey)).toList()) card.append(de.kuro.kuronomics.util.ModText.legacy("\n§e" + name(c, member.getKey()) + " §8· §f" + CompanyChat.rank(member.getValue().rank()) + " §8· §a" + Money.format(member.getValue().salary()) + "§7 / company payday"));
            var owned = ledger(c).estatesOwned(owner);
            long payroll = company.members().values().stream().mapToLong(Company.Member::salary).reduce(0, Math::addExact);
            long contracts = ledger(c).companies().outgoing(owner).stream().mapToLong(CompanyContract::amount).reduce(0, Math::addExact);
            card.append(de.kuro.kuronomics.util.ModText.legacy("\n\n§7Daily Salaries: §e" + Money.format(payroll) + "\n§7Daily Contracts: §e" + Money.format(contracts)));
            card.append(de.kuro.kuronomics.util.ModText.legacy("\n\n§7Properties: §f" + (owned.isEmpty() ? "None" : owned.stream().map(Estate::region).sorted().collect(java.util.stream.Collectors.joining(", ")))
                    + "\n§7Active Contracts: §f" + ledger(c).companies().outgoing(owner).size() + "\n§7Last Payday: §f" + (company.lastPayday() == null ? "None yet" : java.time.LocalDate.ofEpochDay(company.lastPayday().cycleDay()))));
            if (company.bankruptcy() != null) card.append(de.kuro.kuronomics.util.ModText.legacy("\n§cBankrupt · CEO penalty: " + Money.format(company.bankruptcy().penalty())));
        }
        return send(c, CompanyChat.finish(card));
    }
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        var root = actions(literal("company")); var explicit = actions(literal("selection")); var company = companies("actingCompany");
        withConfirmations(explicit.build()).getChildren().forEach(company::then); root.then(literal("for").then(company));
        root.then(literal("found").then(argument("name", StringArgumentType.greedyString()).executes(safe(c -> {
            String input = StringArgumentType.getString(c, "name").strip(); String name; boolean confirmed;
            if (input.startsWith("\"")) {
                var reader = new com.mojang.brigadier.StringReader(input); name = reader.readString(); reader.skipWhitespace();
                String tail = reader.getRemaining();
                if (!tail.isEmpty() && !tail.equals("confirm")) throw new IllegalArgumentException("Use /company found \"Company name\" [confirm].");
                confirmed = tail.equals("confirm");
            } else { confirmed = input.endsWith(" confirm"); name = confirmed ? input.substring(0,input.length()-8).strip() : input; }
            var quote = service(c).quote(actor(c).getUUID(), name, config(c));
            if (confirmed) {
                var created = service(c).confirm(actor(c).getUUID(), quote.request()); FinancialPersistence.save(c.getSource().getServer());
                return message(c, "§aFounded §e" + created.name() + "§a.");
            }
            return send(c, CompanyChat.confirm("Start a Company", "§7Found §e" + quote.name() + "§7 for §e" + Money.format(quote.cost()) + "§7?\n§7You'll be CEO. The company starts with $0.\n§eThe founding fee isn't refunded when you close it.", "Found and Pay", "/company accept " + quote.request(), config(c).confirmationSeconds()));
        }))));
        root.then(literal("accept").then(argument("confirmation", StringArgumentType.word()).executes(safe(c -> {
            var created = service(c).confirm(actor(c).getUUID(), UUID.fromString(StringArgumentType.getString(c, "confirmation"))); FinancialPersistence.save(c.getSource().getServer());
            return message(c, "§aFounded §e" + created.name() + "§a.");
        }))));
        root.then(literal("join").then(argument("invitation", StringArgumentType.word()).executes(safe(c -> {
            var joined = service(c).acceptInvite(actor(c).getUUID(), UUID.fromString(StringArgumentType.getString(c, "invitation"))); FinancialPersistence.save(c.getSource().getServer());
            actor(c).playNotifySound(SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.8F, 1.0F);
            return send(c, CompanyChat.finish(CompanyChat.card("Welcome to " + joined.name(), "§aYou now work at §e" + joined.name() + "§a!")));
        }))));
        root.then(literal("confirm").then(argument("confirmation", StringArgumentType.word()).executes(safe(CompanyCommands::confirm))));
        root.then(literal("acceptceo").then(argument("confirmation", StringArgumentType.word()).executes(safe(c -> {
            var joined = service(c).acceptHandover(actor(c).getUUID(), UUID.fromString(StringArgumentType.getString(c, "confirmation")));
            FinancialPersistence.save(c.getSource().getServer()); var assets = CompanyAssetService.get(c.getSource().getServer()); assets.recover(); assets.tick();
            return message(c, "§aYou're now CEO of §e" + joined.name() + "§a!");
        }))));
        root.then(literal("info").then(companies("company").executes(safe(c -> insight(c, service(c).named(StringArgumentType.getString(c, "company")), true)))));
        event.getDispatcher().getRoot().addChild(withConfirmations(root.build()));
        var personal = literal("personal");
        personal.then(literal("create").then(companies("company").then(argument("contractName", StringArgumentType.string()).then(argument("amount", StringArgumentType.word()).executes(change(c -> {
            var payer = EconomicOwner.player(actor(c).getUUID()); service(c).contract(new ActionContext(payer.id(), payer, UUID.randomUUID()), payer, targetCompany(c), StringArgumentType.getString(c, "contractName"), amount(c, "amount"));
        }, "Company contract created."))))));
        var personalCancel = literal("cancel");
        Command<CommandSourceStack> endPersonal = change(c -> {
            var payer = EconomicOwner.player(actor(c).getUUID()); service(c).cancel(new ActionContext(payer.id(), payer, UUID.randomUUID()), payer, targetCompany(c), optional(c, "contractName"));
        }, "Company contract cancelled.");
        personalCancel.then(companies("company").executes(endPersonal).then(contractNameArgument(false, "company").executes(endPersonal)));
        for (boolean out : List.of(false, true)) personalCancel.then(literal(out ? "outgoing" : "incoming").then(companies("company").then(contractNameArgument(false, "company", out).executes(change(c -> {
            var payer = EconomicOwner.player(actor(c).getUUID()); service(c).cancel(new ActionContext(payer.id(), payer, UUID.randomUUID()), payer, targetCompany(c), optional(c, "contractName"), out);
        }, "Company contract cancelled.")))));
        personal.then(personalCancel);
        personal.then(literal("list").executes(safe(c -> contractList(c, EconomicOwner.player(actor(c).getUUID()))))); event.getDispatcher().register(literal("company").then(literal("contract").then(personal)));
        event.getDispatcher().register(literal("companyadmin").requires(s -> s.hasPermission(2))
                .then(literal("reload").executes(safe(c -> { CompanyRuntime.get(c.getSource().getServer()).reload(); return message(c, "§aCompany settings reloaded."); })))
                .then(literal("recover").executes(safe(c -> { var assets = CompanyAssetService.get(c.getSource().getServer()); assets.recover(); assets.tick(); return message(c, "§aRetried unfinished property changes. §7Still pending: §e" + assets.pendingCount()); })))
                .then(literal("dopayday").executes(safe(c -> {
                    var runtime = CompanyRuntime.get(c.getSource().getServer()); UUID admin = c.getSource().getEntity() instanceof ServerPlayer p ? p.getUUID() : null;
                    var request = runtime.requestDebug(admin);
                    if (admin == null) message(c, "Confirm with: /companyadmin dopayday confirm " + request);
                    return send(c, CompanyChat.confirm("Run Company Payday", "§cThis is a real extra payday for every company.\n§7Salaries, contracts, debt fees and bankruptcies all apply.\n§eThe normal daily payday will still run.", "Run Payday", "/companyadmin dopayday confirm " + request, config(c).confirmationSeconds()));
                })).then(literal("confirm").executes(safe(c -> {
                    UUID admin = c.getSource().getEntity() instanceof ServerPlayer p ? p.getUUID() : null;
                    var runtime = CompanyRuntime.get(c.getSource().getServer()); runtime.debug(admin, runtime.requestDebug(admin));
                    return message(c, "§aCompany payday completed.");
                })).then(argument("confirmation", StringArgumentType.word()).executes(safe(c -> {
                    UUID admin = c.getSource().getEntity() instanceof ServerPlayer p ? p.getUUID() : null;
                    CompanyRuntime.get(c.getSource().getServer()).debug(admin, UUID.fromString(StringArgumentType.getString(c, "confirmation")));
                    return message(c, "§aCompany payday completed.");
                })))))
                .then(literal("grant").then(companies("company").then(argument("amount", StringArgumentType.word()).executes(change(c -> {
                    var owner = targetCompany(c); service(c).require(owner.id()); long value = Money.positive(amount(c, "amount"));
                    UUID operation = UUID.randomUUID(); UUID admin = c.getSource().getEntity() instanceof ServerPlayer p ? p.getUUID() : null;
                    var ledger = ledger(c); var current = ledger.account(owner); var edit = ledger.companies().edit();
                    edit.audit(CompanyAudit.of(new ActionContext(admin, owner, operation), owner, owner, "ADMIN_GRANT", value, ""));
                    ledger.commitOwners(operation, "company-debug-grant|" + owner + "|" + value,
                            Map.of(owner, current.balance(Math.addExact(current.balance(), value))), List.of(), edit.build(), Map.of());
                }, "Company grant added."))))));
    }
    /** The confirm suffix still uses the same parser, permissions and confirmation checks. */
    private static com.mojang.brigadier.tree.CommandNode<CommandSourceStack> withConfirmations(com.mojang.brigadier.tree.CommandNode<CommandSourceStack> root) {
        addConfirmations(root, false); return root;
    }
    private static void addConfirmations(com.mojang.brigadier.tree.CommandNode<CommandSourceStack> node, boolean enabled) {
        if (node.getName().equals("confirm")) return;
        enabled |= Set.of("ceo", "salary", "kick", "leave", "promote", "close", "pay", "create", "buy", "sell").contains(node.getName());
        for (var child : List.copyOf(node.getChildren())) addConfirmations(child, enabled);
        if (enabled && node.getCommand() != null && node.getChild("confirm") == null)
            node.addChild(literal("confirm").requires(node.getRequirement()).executes(node.getCommand()).build());
    }
    private CompanyCommands() {}
}
