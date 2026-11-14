package de.kuro.kuronomics.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.servershop.ServerShopService;
import de.kuro.kuronomics.servershop.ServerShop;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import static de.kuro.kuronomics.command.EconomyCommands.*;
import static net.minecraft.commands.Commands.*;

@EventBusSubscriber(modid = Kuronomics.MODID)
public final class ServerShopCommands {
    private ServerShopCommands() {}

    private static ServerShopService service(CommandSourceStack source) { return new ServerShopService(source.getServer()); }
    private static String name(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        return StringArgumentType.getString(context, "name");
    }
    private static RequiredArgumentBuilder<CommandSourceStack, String> existingName() {
        return argument("name", StringArgumentType.string()).suggests((context, builder) ->
                SharedSuggestionProvider.suggest(service(context.getSource()).names().stream()
                        .map(StringArgumentType::escapeIfRequired), builder));
    }

    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        var root = literal("svshop").requires(source -> source.hasPermission(2));
        root.then(literal("create").then(argument("name", StringArgumentType.string()).executes(safe(context -> {
            var shop = service(context.getSource()).create(name(context));
            de.kuro.kuronomics.menu.ServerShopTerminalMenu.refreshAll(context.getSource().getServer());
            return message(context, "§aServer shop '§e" + shop.name() + "§a' created.");
        }))));
        root.then(literal("list").executes(safe(context -> {
            var shops = service(context.getSource()).shops().stream()
                    .sorted(java.util.Comparator.comparing(ServerShop::name, String.CASE_INSENSITIVE_ORDER)).toList();
            if (shops.isEmpty()) return message(context, "§7No server shops have been created.");
            message(context, "§6Server shops (§e" + shops.size() + "§6):");
            for (ServerShop shop : shops) message(context, "§e" + shop.name() + " §7- §f" + mode(shop.mode())
                    + "§7, §f" + shop.offers().size() + " offer" + (shop.offers().size() == 1 ? "" : "s"));
            return 1;
        })));
        root.then(literal("info").then(existingName().executes(safe(context -> {
            ServerShop shop = service(context.getSource()).find(name(context))
                    .orElseThrow(() -> new IllegalArgumentException("That server shop does not exist."));
            long permanent = shop.offers().stream().filter(offer -> !offer.rotationPool()).count();
            long pool = shop.offers().size() - permanent;
            long shared = shop.offers().stream().filter(offer -> offer.stock().mode() == ServerShop.StockMode.SHARED).count();
            message(context, "§6Server shop: §e" + shop.name());
            message(context, "§7Mode: §f" + mode(shop.mode()) + " §8| §7Offers: §f" + shop.offers().size()
                    + " §8(§f" + permanent + " permanent§8, §f" + pool + " rotating§8)");
            message(context, "§7Stock: §f" + shared + " shared§8, §f" + (shop.offers().size() - shared) + " infinite");
            if (shop.rotation().enabled()) message(context, "§7Rotation: §aEnabled §8| §f" + shop.rotation().visibleOffers()
                    + " active every " + Math.max(1, shop.rotation().durationMillis() / 60_000L) + " min");
            else message(context, "§7Rotation: §cDisabled");
            return 1;
        }))));
        root.then(literal("edit").then(existingName().executes(safe(context -> {
            var shopService = service(context.getSource());
            var session = shopService.beginEditing(context.getSource().getPlayerOrException(), name(context));
            var shop = shopService.find(session.shopId())
                    .orElseThrow(() -> new IllegalStateException("This server shop no longer exists."));
            de.kuro.kuronomics.menu.ServerShopEditorMenu.open(context.getSource().getPlayerOrException(), shop);
            return 1;
        }))));
        root.then(literal("delete").then(existingName().executes(safe(context -> {
            var shop = service(context.getSource()).delete(name(context));
            de.kuro.kuronomics.menu.ServerShopTerminalMenu.refreshAll(context.getSource().getServer());
            return message(context, "§cServer shop '§e" + shop.name() + "§c' deleted.");
        }))));
        event.getDispatcher().register(root);
    }
    private static String mode(ServerShop.Mode mode) {
        return switch (mode) {
            case PLAYER_BUYS -> "Player buys";
            case PLAYER_SELLS -> "Player sells";
            case BOTH -> "Buy and sell";
        };
    }
}
