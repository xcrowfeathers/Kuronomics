package de.kuro.kuronomics.jobs;

import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.economy.data.EconomyLedger;
import de.kuro.kuronomics.integration.PropertyIntegrations;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import static net.minecraft.commands.Commands.*;
import static de.kuro.kuronomics.command.EconomyCommands.*;

@EventBusSubscriber(modid = Kuronomics.MODID)
public final class JobCommands {
    @SubscribeEvent public static void register(RegisterCommandsEvent e) {
        e.getDispatcher().register(literal("jobs").executes(safe(c -> {
            var p = c.getSource().getPlayerOrException();
            var terminal = de.kuro.kuronomics.terminal.JobLocator.nearest(p);
            if (terminal.isEmpty()) { c.getSource().sendFailure(net.minecraft.network.chat.Component.translatable("kuronomics.jobs.no_terminal")); return 0; }
            terminal.orElseThrow().terminal().open(p, false); return 1;
        })).then(literal("quit").executes(safe(c -> {
            var player = c.getSource().getPlayerOrException(); JobService.get(player.server).quit(player); return 1;
        }))).then(literal("reload").requires(s -> s.hasPermission(3)).executes(safe(c -> {
            JobService.get(c.getSource().getServer()).reload(); return message(c, "Jobs reloaded successfully.");
        }))).then(literal("list").executes(safe(c -> {
            var service = JobService.get(c.getSource().getServer());
            var account = c.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer p ? ledger(c).account(p.getUUID()) : de.kuro.kuronomics.economy.data.Account.empty();
            for (var job : service.registry().jobs().values()) c.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(job.id() + " — ").append(service.summary(account.job().equals(job.id()) ? account : de.kuro.kuronomics.economy.data.Account.empty().job(job.id()))), false);
            return 1;
        }))).then(literal("deliver").then(argument("count", IntegerArgumentType.integer(1, 64)).executes(safe(c -> {
            var p = c.getSource().getPlayerOrException(); var stack = p.getMainHandItem(); int count = IntegerArgumentType.getInteger(c, "count");
            long gained = JobDelivery.deliver(p, count);
            return message(c, "Delivered " + count + " items. Next PayDay: +" + de.kuro.kuronomics.economy.Money.format(gained));
        })))));
        e.getDispatcher().register(literal("editjobs").executes(safe(c -> {
            var p = c.getSource().getPlayerOrException();
            if (!JobPermissions.edit(p)) { c.getSource().sendFailure(net.minecraft.network.chat.Component.translatable("kuronomics.jobs.permission")); return 0; }
            var terminal = de.kuro.kuronomics.terminal.JobLocator.nearest(p);
            if (terminal.isEmpty()) { c.getSource().sendFailure(net.minecraft.network.chat.Component.translatable("kuronomics.jobs.no_terminal")); return 0; }
            terminal.orElseThrow().terminal().open(p, true); return 1;
        })));
        e.getDispatcher().register(literal("applications").executes(safe(c -> {
            var p = c.getSource().getPlayerOrException();
            if (!JobPermissions.manage(p)) { c.getSource().sendFailure(net.minecraft.network.chat.Component.translatable("kuronomics.jobs.permission")); return 0; }
            de.kuro.kuronomics.menu.JobMenu.openApplications(p); return 1;
        })));
        var root = literal("workzone").requires(s -> s.hasPermission(2));
        root.then(literal("create").then(argument("name", StringArgumentType.word()).executes(safe(c -> {
                    var player = c.getSource().getPlayerOrException(); var integration = PropertyIntegrations.get();
                    if (!integration.available()) throw new IllegalStateException(WorkZones.UNAVAILABLE);
                    var region = integration.at(player).orElseThrow(() -> new IllegalArgumentException("You are not standing inside a WorldGuard region."));
                    WorkZones.get(player.server).create(player.serverLevel(), StringArgumentType.getString(c, "name"), region.name());
                    de.kuro.kuronomics.economy.FinancialPersistence.save(player.server); return message(c, "WorkZone created for " + region.name() + ".");
                })).then(argument("worldguard-region", StringArgumentType.word())
                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(PropertyIntegrations.get().available() ? PropertyIntegrations.get().regionNames(c.getSource().getLevel()) : java.util.Set.of(), b))
                .executes(safe(c -> {
                    var server = c.getSource().getServer(); WorkZones.get(server).create(c.getSource().getLevel(), StringArgumentType.getString(c, "name"), StringArgumentType.getString(c, "worldguard-region"));
                    de.kuro.kuronomics.economy.FinancialPersistence.save(server); return message(c, "WorkZone created.");
                })))));
        for (String action : new String[]{"delete", "info"}) root.then(literal(action).then(argument("name", StringArgumentType.word())
                .suggests((c, b) -> net.minecraft.commands.SharedSuggestionProvider.suggest(WorkZones.get(c.getSource().getServer()).all().stream().map(WorkZones.Zone::name), b))
                .executes(safe(c -> {
                    var store = WorkZones.get(c.getSource().getServer()); String name = StringArgumentType.getString(c, "name"); var z = store.require(name);
                    if (action.equals("delete")) { store.delete(name); de.kuro.kuronomics.economy.FinancialPersistence.save(c.getSource().getServer()); return message(c, "WorkZone deleted. Jobs requiring it cannot earn there until it is recreated."); }
                    return message(c, z.name() + " | " + z.dimension() + " | WorldGuard region: " + z.region());
                }))));
        root.then(literal("list").executes(safe(c -> { for (var z : WorkZones.get(c.getSource().getServer()).all()) message(c, z.name() + " | " + z.dimension() + " | " + z.region()); return 1; })));
        root.then(literal("info").executes(safe(c -> {
            var player = c.getSource().getPlayerOrException(); var store = WorkZones.get(player.server);
            if (!PropertyIntegrations.get().available()) throw new IllegalStateException(WorkZones.UNAVAILABLE);
            int count = 0;
            for (var z : store.all()) if (store.contains(player.serverLevel(), player.blockPosition(), java.util.List.of(z.name()))) {
                message(c, z.name() + " | " + z.dimension() + " | WorldGuard region: " + z.region()); count++;
            }
            if (count == 0) throw new IllegalArgumentException("You are not standing inside a WorkZone."); return count;
        })));
        e.getDispatcher().register(root);
    }
}
