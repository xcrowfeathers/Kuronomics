package de.kuro.kuronomics.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.block.*;
import de.kuro.kuronomics.auction.AuctionHouseService;
import de.kuro.kuronomics.menu.AuctionMenu;
import de.kuro.kuronomics.economy.CheckService;
import de.kuro.kuronomics.economy.Money;
import de.kuro.kuronomics.item.Prices;
import de.kuro.kuronomics.terminal.ShopLocator;
import de.kuro.kuronomics.terminal.AuctionLocator;
import de.kuro.kuronomics.terminal.RealEstateLocator;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.UuidArgument;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import static net.minecraft.commands.Commands.*;
import static de.kuro.kuronomics.command.EconomyCommands.*;

@EventBusSubscriber(modid = Kuronomics.MODID)
public final class ContentCommands {
    private ContentCommands() {}
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(literal("price").then(argument("price", StringArgumentType.word()).executes(safe(c -> {
            long value = amount(c, "price"); Prices.set(c.getSource().getPlayerOrException().getMainHandItem(), value);
            return message(c, "§aPrice set to " + Money.formatPrice(value));
        }))));
        event.getDispatcher().register(literal("checkdefine").then(argument("value", StringArgumentType.word()).executes(safe(c -> {
            long value = amount(c, "value"); CheckService.draft(c.getSource().getPlayerOrException().getMainHandItem(), value);
            return message(c, "Check amount defined: §e" + Money.format(value) + "§r. Complete validation at an ATM.");
        }))));
        event.getDispatcher().register(literal("val").requires(s -> s.hasPermission(3)).executes(safe(c -> {
            var data = Prices.data(c.getSource().getPlayerOrException().getMainHandItem());
            long value = data.hasUUID("claim") ? ledger(c).checkValue(data.getUUID("claim")) : Money.legacy(data.getDouble("worth"));
            return message(c, "§e" + Money.format(value));
        })));
        event.getDispatcher().register(literal("shop").executes(safe(c -> {
            var player = c.getSource().getPlayerOrException();
            var nearest = ShopLocator.nearest(player).orElseThrow(() -> new IllegalArgumentException("No shop nearby."));
            nearest.terminal().openShop(player);
            return 1;
        })));
        event.getDispatcher().register(literal("auction").executes(safe(c -> {
            var player = c.getSource().getPlayerOrException();
            var nearest = AuctionLocator.nearest(player).orElseThrow(() -> new IllegalArgumentException("No auction terminal nearby."));
            nearest.terminal().open(player); return 1;
        })));
        event.getDispatcher().register(literal("finishauction").requires(s -> s.hasPermission(2)).executes(safe(c -> {
                    var service = new AuctionHouseService(c.getSource().getServer());
                    var active = service.active();
                    for (var auction : active) service.finishNow(auction.id());
                    AuctionMenu.refreshAll(c.getSource().getServer());
                    return message(c, active.isEmpty() ? "§eThere are no active auctions."
                            : "§aFinished §e" + active.size() + "§a active auction" + (active.size() == 1 ? "." : "s."));
                }))
                .then(argument("auction", UuidArgument.uuid()).suggests((context, builder) ->
                        SharedSuggestionProvider.suggest(new AuctionHouseService(context.getSource().getServer()).active().stream()
                                .map(auction -> auction.id().toString()), builder)).executes(safe(c -> {
                    var service = new AuctionHouseService(c.getSource().getServer());
                    var auction = service.finishNow(UuidArgument.getUuid(c, "auction"));
                    AuctionMenu.refreshAuction(c.getSource().getServer(), auction.id());
                    return message(c, "§aFinished auction §e" + auction.id() + "§a. Status: §f" + auction.state());
                }))));
        event.getDispatcher().register(literal("realestate").executes(safe(c -> {
            var player = c.getSource().getPlayerOrException();
            var nearest = RealEstateLocator.nearest(player).orElseThrow(() -> new IllegalArgumentException("No Real Estate terminal nearby."));
            nearest.terminal().open(player); return 1;
        })));
    }
}
