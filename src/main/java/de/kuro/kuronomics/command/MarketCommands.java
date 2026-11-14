package de.kuro.kuronomics.command;

import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.market.MarketService;
import de.kuro.kuronomics.menu.MarketMenu;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import static net.minecraft.commands.Commands.*;
import static de.kuro.kuronomics.command.EconomyCommands.*;

@EventBusSubscriber(modid = Kuronomics.MODID)
public final class MarketCommands {
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(literal("market").executes(safe(c -> { MarketMenu.open(c.getSource().getPlayerOrException()); return 1; }))
                .then(literal("reload").requires(s -> s.hasPermission(2)).executes(safe(c -> { MarketService.get(c.getSource().getServer()).reload(); return message(c, "Marketplace settings reloaded."); })))
                .then(literal("recover").requires(s -> s.hasPermission(2)).then(argument("listing", net.minecraft.commands.arguments.UuidArgument.uuid()).suggests((c, b) -> {
                    var service = MarketService.get(c.getSource().getServer());
                    de.kuro.kuronomics.economy.data.EconomyLedger.get(c.getSource().getServer()).market().listings().values().stream()
                            .filter(l -> l.seller().isCompany() && !service.active(l.seller())).limit(100)
                            .forEach(l -> b.suggest(l.id().toString(), net.minecraft.network.chat.Component.literal(l.item().getHoverName().getString())));
                    return b.buildFuture();
                }).executes(safe(c -> {
                    var p = c.getSource().getPlayerOrException(); MarketService.get(p.server).reclaim(p, net.minecraft.commands.arguments.UuidArgument.getUuid(c, "listing"), java.util.UUID.randomUUID());
                    return message(c, "Closed-company stock recovered.");
                })))));
    }
    private MarketCommands() {}
}
