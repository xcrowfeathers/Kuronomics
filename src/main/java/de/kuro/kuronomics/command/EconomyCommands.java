package de.kuro.kuronomics.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.economy.*;
import de.kuro.kuronomics.economy.data.*;
import de.kuro.kuronomics.event.EconomyEvents;
import de.kuro.kuronomics.economy.payday.PaydayClock;
import de.kuro.kuronomics.util.ModText;
import de.kuro.kuronomics.mailbox.MailboxService;
import java.math.BigDecimal;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import static net.minecraft.commands.Commands.*;

@EventBusSubscriber(modid = Kuronomics.MODID)
public final class EconomyCommands {
    private EconomyCommands() {}
    public static EconomyLedger ledger(CommandContext<CommandSourceStack> c) { return EconomyLedger.get(c.getSource().getServer()); }
    public static int message(CommandContext<CommandSourceStack> c, String text) { c.getSource().sendSuccess(() -> ModText.legacy(text), false); return 1; }
    public static Command<CommandSourceStack> safe(Command<CommandSourceStack> action) {
        return c -> { try { FinancialWriter.check(c.getSource().getServer()); return action.run(c); }
        catch (java.util.concurrent.CompletionException failure) {
            c.getSource().sendFailure(Component.literal("Economy storage is unavailable. Please contact an administrator.")); return 0;
        } catch (IllegalArgumentException | ArithmeticException | IllegalStateException e) {
            var message = de.kuro.kuronomics.integration.PropertyIntegration.REQUIRED_MESSAGE.equals(e.getMessage())
                    ? Component.translatable("kuronomics.property.unavailable")
                    : Component.literal(e.getMessage() == null ? "Invalid economy action" : e.getMessage());
            c.getSource().sendFailure(message.withStyle(net.minecraft.ChatFormatting.RED)); return 0;
        } };
    }
    public static long amount(CommandContext<CommandSourceStack> c, String name) { return Money.parse(StringArgumentType.getString(c, name)); }
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        var dispatcher = event.getDispatcher();
        Command<CommandSourceStack> ownBalance = safe(c -> message(c, "§e" + Money.format(
                new EconomyService(ledger(c)).getBalance(c.getSource().getPlayerOrException().getUUID()))));
        var balance = literal("bal").requires(s -> s.hasPermission(2)).executes(ownBalance);
        balance.then(literal("show").executes(ownBalance)
                .then(argument("player", EntityArgument.player()).executes(safe(c -> {
                    var player = EntityArgument.getPlayer(c, "player");
                    return message(c, "The balance of §a" + player.getName().getString() + " §ris §e"
                            + Money.format(new EconomyService(ledger(c)).getBalance(player.getUUID())));
                }))));
        for (String operation : new String[]{"set", "add", "sub"}) {
            balance.then(literal(operation).then(argument("player", EntityArgument.player())
                    .then(argument("amount", StringArgumentType.word()).executes(safe(c -> {
                        var p = EntityArgument.getPlayer(c, "player"); long value = amount(c, "amount"); var service = new EconomyService(ledger(c));
                        switch (operation) {
                            case "set" -> service.set(p.getUUID(), value);
                            case "add" -> { if (value <= 0) throw new IllegalArgumentException("Enter an amount greater than zero."); service.adjust(p.getUUID(), value); }
                            case "sub" -> { if (value <= 0) throw new IllegalArgumentException("Enter an amount greater than zero."); service.adjust(p.getUUID(), -value); }
                        }
                        FinancialPersistence.save(c.getSource().getServer());
                        if (operation.equals("add")) p.sendSystemMessage(ModText.legacy("§aYou received a payment of §e" + Money.format(value) + "§a from the §ebank§a."));
                        if (operation.equals("sub")) p.sendSystemMessage(ModText.legacy("§c-" + Money.format(value) + "§r have been debited from your account."));
                        return message(c, "The balance of §a" + p.getName().getString() + " §rhas been set to §e" + Money.format(service.getBalance(p.getUUID())));
                    })))));
        }
        var treasury = literal("statebank").requires(source -> source.hasPermission(3));
        treasury.then(literal("show").executes(safe(c -> message(c, "Treasury: " + Money.format(StateTreasury.balance(ledger(c)))))));
        for (String operation : new String[]{"set", "add", "sub"}) treasury.then(literal(operation).then(argument("amount", StringArgumentType.word()).executes(safe(c -> {
            long value = amount(c, "amount");
            switch (operation) {
                case "set" -> StateTreasury.set(ledger(c), value);
                case "add" -> StateTreasury.add(ledger(c), value);
                case "sub" -> StateTreasury.set(ledger(c), Math.subtractExact(StateTreasury.balance(ledger(c)), Money.positive(value)));
            }
            FinancialPersistence.save(c.getSource().getServer());
            return message(c, "Treasury: " + Money.format(StateTreasury.balance(ledger(c))));
        }))));
        balance.then(treasury); dispatcher.register(balance);
        dispatcher.register(literal("pay").then(argument("name", EntityArgument.player()).then(argument("amount", StringArgumentType.word()).executes(safe(c -> {
            var sender = c.getSource().getPlayerOrException(); var recipient = EntityArgument.getPlayer(c, "name"); long amount = amount(c, "amount");
            if (amount < 500) throw new IllegalArgumentException("Minimum payment is $5");
            FinancialSettlements.requireCapacity(sender.server);
            var economy = new EconomyService(ledger(c));
            long opening = economy.getBalance(sender.getUUID());
            long fee = Money.percent(amount, ledger(c).fee());
            java.util.UUID reference = java.util.UUID.randomUUID();
            if (!economy.transfer(sender.getUUID(), recipient.getUUID(), amount, true, reference)) return 0;
            new MailboxService(c.getSource().getServer()).payment(reference, sender.getUUID(), recipient.getUUID(), amount,
                    economy.getBalance(sender.getUUID()), economy.getBalance(recipient.getUUID()), "Direct payment");
            long closing = economy.getBalance(sender.getUUID());
            FinancialSettlements.after(sender.server, () -> {
            recipient.sendSystemMessage(ModText.legacy("§aYou received a payment of §e" + Money.format(amount) + "§a from §e" + sender.getName().getString() + "§a."));
            message(c, "§aPayment to §e" + recipient.getName().getString() + "§a succeeded.");
            message(c, "§7------ Bank ------");
            message(c, "Old balance: §a" + Money.format(opening));
            message(c, "§c-" + Money.format(amount));
            message(c, "§c-" + Money.format(fee) + "§r (fee)");
            message(c, "New balance: §a" + Money.format(closing));
            message(c, "§7------------------");
            }, failure -> c.getSource().sendFailure(Component.literal("Payment storage failed; completion is uncertain. Contact an administrator before retrying.")));
            return message(c, "§7Payment processing…");
        })))));
        dispatcher.register(literal("fee").executes(c -> message(c, "Current transfer fee: §a" + ledger(c).fee().toPlainString() + "%")));
        dispatcher.register(literal("setfee").requires(s -> s.hasPermission(3)).then(argument("percentage", StringArgumentType.word()).executes(safe(c -> {
            ledger(c).fee(new BigDecimal(StringArgumentType.getString(c, "percentage"))); return message(c, "§aTransaction fee updated.");
        }))));
        dispatcher.register(literal("giftmoney").requires(s -> s.hasPermission(2)).then(argument("name", EntityArgument.players()).then(argument("amount", StringArgumentType.word())
                .then(argument("message", StringArgumentType.greedyString()).executes(safe(c -> {
                    long amount = amount(c, "amount"); if (amount <= 0) throw new IllegalArgumentException("Enter a gift amount greater than zero.");
                    var ledger = ledger(c);
                    var players = EntityArgument.getPlayers(c, "name");
                    var economy = new EconomyService(ledger); java.util.UUID reference = java.util.UUID.randomUUID();
                    if (!economy.depositAll(players.stream().map(net.minecraft.server.level.ServerPlayer::getUUID).toList(), amount, reference, "giftmoney")) return 0;
                    var mailbox = new MailboxService(c.getSource().getServer());
                    for (var p : players) mailbox.deposit(p.getUUID(), amount, economy.getBalance(p.getUUID()), reference,
                            "Gift: " + StringArgumentType.getString(c, "message"));
                    FinancialPersistence.save(c.getSource().getServer());
                    for (var p : players) p.sendSystemMessage(ModText.legacy("You received §a§l" + Money.format(amount) + "§r: §a" + StringArgumentType.getString(c, "message")));
                    return message(c, "§aGift delivered.");
                }))))));
        dispatcher.register(literal("setjob").requires(s -> s.hasPermission(3)).then(argument("player", EntityArgument.players()).then(argument("name", StringArgumentType.word())
                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(de.kuro.kuronomics.jobs.JobService.get(c.getSource().getServer()).registry().jobs().keySet(), b)).executes(safe(c -> {
                    String job = StringArgumentType.getString(c, "name").toLowerCase(java.util.Locale.ROOT);
                    var economy = new EconomyService(ledger(c));
                    de.kuro.kuronomics.jobs.JobService.get(c.getSource().getServer()).assign(EntityArgument.getPlayers(c, "player"), job);
                    var assigned = de.kuro.kuronomics.jobs.JobService.get(c.getSource().getServer()).registry().jobs().get(job);
                    return message(c, "§aPlayer occupation set to §e" + (assigned == null ? "Unemployed" : assigned.name()) + "§a.");
                })))));
        dispatcher.register(literal("reloadec").requires(s -> s.hasPermission(3)).executes(safe(c -> { EconomyEvents.reload(c.getSource().getServer()); return message(c, "§aKuronomics config reloaded."); })));
        var payday = literal("payday").executes(safe(EconomyCommands::paydayStatus));
        payday.then(literal("now").requires(s -> s.hasPermission(2)).executes(safe(c -> {
            EconomyEvents.queuePayday(c.getSource().getPlayerOrException()); return 1;
        })).then(argument("players", EntityArgument.players()).executes(safe(c -> {
            var players = EntityArgument.getPlayers(c, "players"); for (var player : players) EconomyEvents.queuePayday(player);
            return 1;
        }))));
        for (boolean enabled : new boolean[]{true, false})
            payday.then(literal(enabled ? "enable" : "disable").requires(s -> s.hasPermission(2)).executes(safe(c -> payday(c, enabled))));
        dispatcher.register(payday);
        dispatcher.register(literal("entry").requires(s -> s.hasPermission(2)).then(argument("name", EntityArgument.player()).then(argument("entry", StringArgumentType.greedyString()).executes(safe(c -> {
            var player = EntityArgument.getPlayer(c, "name"); var id = player.getUUID(); new EconomyService(ledger(c)).updateAccount(id, account -> account.entry(StringArgumentType.getString(c, "entry"))); return message(c, "The ID record for §e" + player.getName().getString() + "§r has been changed.");
        })))));
        dispatcher.register(literal("suspend").requires(s -> s.hasPermission(3)).then(argument("name", EntityArgument.player()).then(argument("reason", StringArgumentType.greedyString()).executes(safe(c -> {
            var player = EntityArgument.getPlayer(c, "name"); var id = player.getUUID(); String reason = StringArgumentType.getString(c, "reason"); new EconomyService(ledger(c)).updateAccount(id, account -> account.suspension(true, reason)); return message(c, "The bank account of §a" + player.getName().getString() + "§r has been §c§lsuspended§r with reason \"§b" + reason + "§r\".");
        })))));
        dispatcher.register(literal("unsuspend").requires(s -> s.hasPermission(3)).then(argument("name", EntityArgument.player()).executes(safe(c -> {
            var player = EntityArgument.getPlayer(c, "name"); var id = player.getUUID(); new EconomyService(ledger(c)).updateAccount(id, account -> account.suspension(false, "")); return message(c, "The bank account ban of §a" + player.getName().getString() + "§r has been §b§lrevoked§r.");
        }))));
        for (String name : new String[]{"suspendreason", "suspendstatus"}) dispatcher.register(literal(name).requires(s -> s.hasPermission(3)).then(argument("name", EntityArgument.player()).executes(safe(c -> {
            var player = EntityArgument.getPlayer(c, "name"); var a = new EconomyService(ledger(c)).account(player.getUUID());
            return message(c, name.equals("suspendreason") ? "The last reason for the account suspension of §a" + player.getName().getString() + "§r was \"§e" + a.suspensionReason() + "§r\"."
                    : "The account of §a" + player.getName().getString() + "§r is currently " + (a.suspended() ? "§c§lsuspended§r." : "§a§lnot suspended§r."));
        }))));
        dispatcher.register(literal("remind").then(argument("name", EntityArgument.player()).then(argument("amount", StringArgumentType.word()).then(argument("comment", StringArgumentType.greedyString()).executes(safe(c -> {
            var sender = c.getSource().getPlayerOrException(); long amount = amount(c, "amount");
            if (amount <= 0) throw new IllegalArgumentException("Enter a reminder amount greater than zero.");
            var target = EntityArgument.getPlayer(c, "name");
            target.sendSystemMessage(ModText.legacy("§b" + sender.getName().getString() + " §ehas reminded you of a payment due of §a§l" + Money.format(amount) + "§e with comment: §f\"§b" + StringArgumentType.getString(c, "comment") + "§f\""));
            return message(c, "§eReminder successfully sent to §b" + target.getName().getString() + "§e.");
        }))))));
    }
    private static int payday(CommandContext<CommandSourceStack> c, boolean enabled) {
        var ledger = ledger(c);
        var server = c.getSource().getServer();
        if (ledger.paydayEnabled() == enabled) {
            if (enabled) PaydayClock.ensure(server); else PaydayClock.remove(server);
            return paydayStatus(c);
        }
        for (var player : server.getPlayerList().getPlayers()) ledger.observe(player.getUUID(), server.getTickCount(), false);
        if (enabled) {
            PaydayClock.ensure(server);
            ledger.paydayEnabled(true);
            for (var player : server.getPlayerList().getPlayers()) PaydayClock.join(player, ledger);
        } else {
            PaydayClock.remove(server);
            ledger.paydayEnabled(false);
        }
        FinancialPersistence.save(server);
        return message(c, enabled ? "§a✔ PayDay-System activated." : "§c✖ PayDay-System deactivated.");
    }
    private static int paydayStatus(CommandContext<CommandSourceStack> c) {
        if (!ledger(c).paydayEnabled()) return message(c, "§ePayDays are paused.");
        if (!(c.getSource().getEntity() instanceof ServerPlayer player)) return message(c, "PayDays are active.");
        int interval = EconomyEvents.config().paydayTicks();
        int elapsed = Math.max(0, Math.min(interval, PaydayClock.score(player)));
        long remaining = ((long) interval - elapsed + 1199) / 1200;
        String total = java.math.BigDecimal.valueOf(interval).divide(java.math.BigDecimal.valueOf(1200), 2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return message(c, "§6Next PayDay in §e" + remaining + (remaining == 1 ? " minute" : " minutes")
                + " §8(§7" + elapsed / 1200 + "/" + total + " minutes§8)");
    }
}
