package de.kuro.kuronomics.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.estate.*;
import de.kuro.kuronomics.estate.EstateService.SuggestionUse;
import de.kuro.kuronomics.economy.Money;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import static net.minecraft.commands.Commands.*;
import static de.kuro.kuronomics.command.EconomyCommands.*;

@EventBusSubscriber(modid = Kuronomics.MODID)
public final class EstateCommands {
    private EstateCommands() {}
    private static EstateService service(CommandContext<CommandSourceStack> c) { return new EstateService(c.getSource().getServer()); }
    private static String optionalName(CommandContext<CommandSourceStack> c) { try { return StringArgumentType.getString(c, "estate"); } catch (IllegalArgumentException absent) { return null; } }
    @FunctionalInterface private interface Action { void run(EstateService service, ServerPlayer player, Estate estate, CommandContext<CommandSourceStack> context) throws CommandSyntaxException; }
    private static Command<CommandSourceStack> execute(Action action) {
        return execute(action, true);
    }
    private static Command<CommandSourceStack> execute(Action action, boolean report) {
        return safe(c -> { var s = service(c); var p = c.getSource().getPlayerOrException(); var e = s.find(p, optionalName(c)); action.run(s, p, e, c); return report ? message(c, "§6◆ §e§lESTATE §6◆ §7Estate updated: §b§l" + e.region()) : 1; });
    }
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> estateArgument() {
        return estateArgument(SuggestionUse.OWNER);
    }
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> estateArgument(SuggestionUse use) {
        return argument("estate", StringArgumentType.string()).suggests((c, builder) -> {
            if (!(c.getSource().getEntity() instanceof ServerPlayer player)) return builder.buildFuture();
            var service = service(c);
            return net.minecraft.commands.SharedSuggestionProvider.suggest(ledger(c).estates().stream()
                    .filter(e -> service.canSuggest(player, e, use))
                    .map(e -> StringArgumentType.escapeIfRequired(e.dimension().equals(player.level().dimension().location().toString()) ? e.region() : e.key())), builder);
        });
    }
    private static LiteralArgumentBuilder<CommandSourceStack> optional(String name, Command<CommandSourceStack> action) {
        return optional(name, action, SuggestionUse.OWNER);
    }
    private static LiteralArgumentBuilder<CommandSourceStack> optional(String name, Command<CommandSourceStack> action, SuggestionUse use) {
        return literal(name).executes(action).then(estateArgument(use).executes(action));
    }
    private static void info(CommandContext<CommandSourceStack> c, Estate e) {
        if (e.regionBinding().isEmpty()) message(c, "§eThis estate is waiting for administrator review with /estate setprice.");
        message(c, "§6◆ §e§lESTATE §6◆ §b§l" + e.region() + " §7| " + e.dimension());
        message(c, "§7Price: §a§l" + Money.format(e.price()) + " §7| Rent: §a§l" + Money.format(e.rent()));
        message(c, "§7Owner: §e" + de.kuro.kuronomics.util.PlayerNames.owner(c.getSource().getServer(), e.economicOwner()) + " §7| Tenant: §e" + (e.tenant() == null ? "None" : de.kuro.kuronomics.util.PlayerNames.get(c.getSource().getServer(), e.tenant())) + " §7| Rent locked: " + (e.locked() ? "§c§lYes" : "§a§lNo"));
        message(c, "§7Building: " + (e.buildingAllowed() ? "§aAllowed" : "§cDisallowed"));
        message(c, "§7Interaction guests: " + (e.guests().isEmpty() ? "None" : e.guests().stream()
                .map(id -> de.kuro.kuronomics.util.PlayerNames.get(c.getSource().getServer(), id)).sorted().collect(java.util.stream.Collectors.joining(", "))));
    }
    private static int landlordList(CommandContext<CommandSourceStack> c) throws CommandSyntaxException {
        var player = c.getSource().getPlayerOrException();
        var owned = ledger(c).estates().stream().filter(e -> service(c).isLandlord(player, e))
                .sorted(java.util.Comparator.comparing(Estate::region, String.CASE_INSENSITIVE_ORDER).thenComparing(Estate::dimension)).toList();
        message(c, "§6◆ §e§lLANDLORD §6◆ §7Owned estates: §b§l" + owned.size());
        if (owned.isEmpty()) return message(c, "§7You do not currently own any estates.");
        for (Estate e : owned) {
            String tenant = e.tenant() == null ? "§aAvailable" : "§eRented to "
                    + de.kuro.kuronomics.util.PlayerNames.get(c.getSource().getServer(), e.tenant());
            String lock = e.locked() ? "§cLocked" : "§aUnlocked";
            message(c, "§8• §b§l" + e.region() + " §8[" + lock + "§8] §7| Rent: §a" + Money.format(e.rent())
                    + " §7| " + tenant + " §7| Owner: " + de.kuro.kuronomics.util.PlayerNames.owner(c.getSource().getServer(), e.economicOwner()) + " §8| " + e.dimension());
        }
        return owned.size();
    }
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        var estate = literal("estate");
        estate.then(literal("setprice").requires(s -> s.hasPermission(2)).then(argument("price", StringArgumentType.word()).executes(safe(c -> {
            service(c).price(c.getSource().getPlayerOrException(), amount(c, "price"), null); return message(c, "§6◆ §e§lESTATE §6◆ §7Estate price set.");
        })).then(argument("rent", StringArgumentType.word()).executes(safe(c -> {
            service(c).price(c.getSource().getPlayerOrException(), amount(c, "price"), amount(c, "rent")); return message(c, "§6◆ §e§lESTATE §6◆ §7Estate price and rent set.");
        })))));
        estate.then(optional("remove", execute((s, p, e, c) -> s.remove(p, e)), SuggestionUse.REMOVE).requires(s -> s.hasPermission(2)));
        estate.then(optional("info", execute((s, p, e, c) -> info(c, e), false), SuggestionUse.INFO));
        estate.then(optional("buy", execute((s, p, e, c) -> {
            s.buy(p, e); message(c, "§6◆ §e§lESTATE §6◆ §7You bought §b§l" + e.region() + " §7for §a§l" + Money.format(e.price()) + "§7.");
        }, false), SuggestionUse.BUY));
        estate.then(optional("sell", execute((s, p, e, c) -> {
            s.sell(p, e); message(c, "§6◆ §e§lESTATE §6◆ §7You sold §b§l" + e.region() + " §7for §a§l" + Money.format(e.price() / 2) + "§7.");
        }, false)));
        event.getDispatcher().register(estate);
        event.getDispatcher().register(optional("rent", execute((s, p, e, c) -> {
            s.rent(p, e); message(c, "§6◆ §e§lRENT §6◆ §7You are now renting §b§l" + e.region() + " §7for §a§l" + Money.format(e.rent()) + "§7.");
        }, false), SuggestionUse.RENT));
        event.getDispatcher().register(optional("unrent", safe(c -> {
            var s = service(c); var p = c.getSource().getPlayerOrException(); var e = s.rented(p, optionalName(c)); s.unrent(p, e); return message(c, "§6◆ §e§lRENT §6◆ §7You are no longer renting §b§l" + e.region() + "§7.");
        }), SuggestionUse.UNRENT));
        var landlord = literal("landlord"); var properties = literal("estate");
        var building = literal("building");
        for (boolean allow : new boolean[]{true, false}) building.then(optional(allow ? "allow" : "disallow", execute((s, p, e, c) -> s.building(p, e, allow)), SuggestionUse.BUILDING));
        properties.then(building);
        properties.then(literal("list").executes(safe(EstateCommands::landlordList)));
        properties.then(optional("lock", execute((s, p, e, c) -> s.lock(p, e, true))));
        properties.then(optional("unlock", execute((s, p, e, c) -> s.lock(p, e, false))));
        properties.then(optional("info", execute((s, p, e, c) -> { s.requireOwner(p, e); info(c, e); }, false), SuggestionUse.OWNER_INFO));
        Command<CommandSourceStack> rent = execute((s, p, e, c) -> s.rentPrice(p, e, amount(c, "amount")));
        properties.then(literal("rent").then(argument("amount", StringArgumentType.word()).executes(rent).then(estateArgument().executes(rent))));
        Command<CommandSourceStack> transfer = safe(c -> {
            var s = service(c); var p = c.getSource().getPlayerOrException(); var e = s.find(p, optionalName(c)); var target = EntityArgument.getPlayer(c, "player");
            if (e.economicOwner().isCompany()) return CompanyCommands.requestTransfer(c, p, e, target.getUUID());
            s.transfer(p, e, target); return message(c, "§aProperty given to " + target.getGameProfile().getName() + ".");
        });
        properties.then(literal("transfer").then(estateArgument().then(argument("player", EntityArgument.player()).executes(transfer).then(literal("confirm").executes(transfer)))));
        landlord.then(properties); var members = literal("permit");
        for (boolean add : new boolean[]{true, false}) {
            Command<CommandSourceStack> member = execute((s, p, e, c) -> s.member(p, e, EntityArgument.getPlayer(c, "player"), add));
            members.then(literal(add ? "add" : "remove").then(argument("player", EntityArgument.player()).executes(member).then(estateArgument().executes(member))));
        }
        landlord.then(members); event.getDispatcher().register(landlord);
    }
}

