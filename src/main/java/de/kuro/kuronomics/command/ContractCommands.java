package de.kuro.kuronomics.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.economy.Money;
import de.kuro.kuronomics.economy.data.RecurringPayment;
import de.kuro.kuronomics.util.ModText;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import static net.minecraft.commands.Commands.*;
import static de.kuro.kuronomics.command.EconomyCommands.*;

@EventBusSubscriber(modid = Kuronomics.MODID)
public final class ContractCommands {
    private ContractCommands() {}
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        for (boolean bank : new boolean[]{false, true}) {
            var root = literal(bank ? "bankcontract" : "contract").requires(s -> !bank || de.kuro.kuronomics.economy.bank.CentralBank.canContracts(s));
            if (bank) root.then(companyContracts());
            root.then(literal("create").then(argument("player", EntityArgument.player()).then(argument("amount", StringArgumentType.word()).then(argument("name", StringArgumentType.greedyString()).executes(safe(c -> {
                if(bank)de.kuro.kuronomics.economy.bank.CentralBank.contracts(c.getSource());
                var target = EntityArgument.getPlayer(c, "player");
                var payer = bank ? target : c.getSource().getPlayerOrException();
                String name = StringArgumentType.getString(c, "name");
                if (name.equalsIgnoreCase("Shop Sale") || name.regionMatches(true, 0, "Rent:", 0, 5)) throw new IllegalArgumentException("Please choose a different contract name.");
                long amount = amount(c, "amount"); if (!bank && amount < 100) throw new IllegalArgumentException("Minimum contract amount is $1");
                var payment = new RecurringPayment(payer.getUUID(), bank ? null : target.getUUID(), amount, name);
                ledger(c).addContract(payment);
                contractEvent(c, payment, de.kuro.kuronomics.api.event.ContractChangedEvent.Change.CREATED);
                target.sendSystemMessage(ModText.legacy("§aContract created: §6" + name + " §f(§a" + Money.format(amount) + "§f) per payday."));
                return message(c, "§a✔ Contract created.");
            }))))));
            root.then(literal("cancel").then(argument("player", EntityArgument.player()).then(argument("name", StringArgumentType.greedyString()).suggests((c, builder) -> {
                var target = EntityArgument.getPlayer(c, "player").getUUID();
                var self = bank ? target : c.getSource().getPlayerOrException().getUUID();
                return net.minecraft.commands.SharedSuggestionProvider.suggest(ledger(c).contracts().stream().filter(p -> matches(p, bank, self, target)).map(RecurringPayment::name), builder);
            }).executes(safe(c -> {
                if(bank)de.kuro.kuronomics.economy.bank.CentralBank.contracts(c.getSource());
                var target = EntityArgument.getPlayer(c, "player").getUUID();
                return cancel(c, bank, target);
            })))));
            root.then(literal("cancelid").then(argument("id", net.minecraft.commands.arguments.UuidArgument.uuid()).then(argument("name", StringArgumentType.greedyString()).executes(safe(c -> cancel(c, bank, net.minecraft.commands.arguments.UuidArgument.getUuid(c, "id")))))));
            root.then(literal("list").executes(safe(c -> {
                if(bank)de.kuro.kuronomics.economy.bank.CentralBank.contracts(c.getSource());
                var self = bank ? null : c.getSource().getPlayerOrException().getUUID();
                int count = 0;
                for (var payment : ledger(c).contracts()) {
                    if (payment.bank() != bank || !bank && !payment.payer().equals(self) && !payment.receiver().equals(self)) continue;
                    var target = bank || payment.receiver().equals(self) ? payment.payer() : payment.receiver();
                    String direction = bank ? "Bank contract for " : payment.payer().equals(self) ? "To " : "From ";
                    var line = ModText.legacy("§7- " + direction + "§b" + de.kuro.kuronomics.util.PlayerNames.get(c.getSource().getServer(), target)
                            + " §7| Name: §6" + payment.name() + " §7| Amount: " + (payment.amount() >= 0 ? "§a" : "§c") + Money.format(payment.amount()) + " ");
                    line.append(Component.literal(" [Cancel]").withStyle(style -> style.withColor(net.minecraft.ChatFormatting.RED)
                            .withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND, "/" + (bank ? "bankcontract" : "contract") + " cancelid " + target + " " + payment.name()))));
                    c.getSource().sendSuccess(() -> line, false); count++;
                }
                if (count == 0) message(c, "§7• No active contracts.");
                return 1;
            })));
            event.getDispatcher().register(root);
        }
    }
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<net.minecraft.commands.CommandSourceStack> companyContracts() {
        var root = literal("company");
        root.then(companyArgument().then(argument("contractName", StringArgumentType.string()).then(argument("amount", StringArgumentType.word()).executes(safe(c -> {
            de.kuro.kuronomics.economy.bank.CentralBank.contracts(c.getSource());
            var service = de.kuro.kuronomics.company.CompanyService.get(c.getSource().getServer());
            var company = service.named(StringArgumentType.getString(c, "company"));
            java.util.UUID admin = c.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer p ? p.getUUID() : null;
            service.stateContract(admin, company.id(), StringArgumentType.getString(c, "contractName"), amount(c, "amount"));
            de.kuro.kuronomics.economy.FinancialPersistence.save(c.getSource().getServer());
            return message(c, "§aCompany contract created.");
        })))));
        root.then(literal("cancel").then(companyArgument().then(argument("contractName", StringArgumentType.greedyString()).suggests((c, b) -> {
            try {
                de.kuro.kuronomics.economy.bank.CentralBank.contracts(c.getSource());
            var service = de.kuro.kuronomics.company.CompanyService.get(c.getSource().getServer());
                var owner = de.kuro.kuronomics.economy.EconomicOwner.company(service.named(StringArgumentType.getString(c, "company")).id());
                return net.minecraft.commands.SharedSuggestionProvider.suggest(ledger(c).companies().involving(owner).filter(de.kuro.kuronomics.company.CompanyContract::stateContract).map(de.kuro.kuronomics.company.CompanyContract::name), b);
            } catch (RuntimeException failure) { return b.buildFuture(); }
        }).executes(safe(c -> {
            de.kuro.kuronomics.economy.bank.CentralBank.contracts(c.getSource());
            var service = de.kuro.kuronomics.company.CompanyService.get(c.getSource().getServer());
            java.util.UUID admin = c.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer p ? p.getUUID() : null;
            service.cancelStateContract(admin, service.named(StringArgumentType.getString(c, "company")).id(), StringArgumentType.getString(c, "contractName"));
            de.kuro.kuronomics.economy.FinancialPersistence.save(c.getSource().getServer());
            return message(c, "§aCompany contract cancelled.");
        })))));
        return root;
    }
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<net.minecraft.commands.CommandSourceStack, String> companyArgument() {
        return argument("company", StringArgumentType.string()).suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                CompanyCommands.companyNames(c.getSource().getServer()).stream().map(StringArgumentType::escapeIfRequired), b));
    }
    private static boolean matches(RecurringPayment payment, boolean bank, java.util.UUID self, java.util.UUID target) {
        return payment.bank() == bank && (bank ? payment.payer().equals(target) : payment.payer().equals(self) && payment.receiver().equals(target) || payment.payer().equals(target) && payment.receiver().equals(self));
    }
    private static int cancel(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> c, boolean bank, java.util.UUID target) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if(bank)de.kuro.kuronomics.economy.bank.CentralBank.contracts(c.getSource());
        var self = bank ? target : c.getSource().getPlayerOrException().getUUID();
        String name = StringArgumentType.getString(c, "name"); boolean removed = false;
        for (var payment : ledger(c).contracts()) {
            if (!payment.name().equalsIgnoreCase(name) || !matches(payment, bank, self, target)) continue;
            if (payment.name().startsWith("Rent:")) throw new IllegalArgumentException("Use /unrent to end a rental");
            if (ledger(c).removeContract(payment)) { removed = true; contractEvent(c, payment, de.kuro.kuronomics.api.event.ContractChangedEvent.Change.CANCELLED); }
        }
        if (!removed) throw new IllegalArgumentException("No matching contract found.");
        var online = c.getSource().getServer().getPlayerList().getPlayer(target);
        if (online != null) online.sendSystemMessage(ModText.legacy("§cContract '§6" + name + "§c' was cancelled by §e" + c.getSource().getTextName()));
        return message(c, "§c✖ Contract cancelled.");
    }
    private static void contractEvent(com.mojang.brigadier.context.CommandContext<net.minecraft.commands.CommandSourceStack> context,
                                      RecurringPayment payment, de.kuro.kuronomics.api.event.ContractChangedEvent.Change change) {
        var personal = de.kuro.kuronomics.economy.EconomicOwner.player(payment.payer());
        var state = de.kuro.kuronomics.economy.EconomicOwner.STATE;
        var details = new de.kuro.kuronomics.api.ContractApi.PersonalContract(payment.bank() && payment.amount() > 0 ? state : personal,
                payment.bank() ? (payment.amount() > 0 ? personal : state) : de.kuro.kuronomics.economy.EconomicOwner.player(payment.receiver()), Math.abs(payment.amount()), payment.name());
        var actor = context.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer p ? p.getUUID() : null;
        ledger(context).notifyAfterCommit(new de.kuro.kuronomics.api.event.ContractChangedEvent(actor, null, change, null, details));
    }
}
