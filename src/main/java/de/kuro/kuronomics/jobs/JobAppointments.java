package de.kuro.kuronomics.jobs;

import com.mojang.brigadier.arguments.StringArgumentType;
import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.company.CompanyChat;
import de.kuro.kuronomics.command.CompanyCommands;
import de.kuro.kuronomics.economy.FinancialPersistence;
import de.kuro.kuronomics.mailbox.MailboxStore;
import java.util.*;
import net.minecraft.commands.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import static net.minecraft.commands.Commands.*;
import static de.kuro.kuronomics.command.EconomyCommands.*;

/** Permission to appoint jobs doesn't give OP or access to anyone's money. */
@EventBusSubscriber(modid = Kuronomics.MODID)
public final class JobAppointments {
    private record Pending(UUID token, UUID target, String before, String next, long registry, long expires) {}
    private static final Map<net.minecraft.server.MinecraftServer, Map<UUID, Pending>> pending = new WeakHashMap<>();
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        var root = literal("jobs");
        root.then(literal("appoint").then(argument("player", StringArgumentType.word()).suggests((c, b) ->
                SharedSuggestionProvider.suggest(CompanyCommands.playerNames(c.getSource().getServer()), b))
                .then(argument("job", StringArgumentType.word()).suggests((c, b) -> {
                    var service = JobService.get(c.getSource().getServer());
                    UUID actor = c.getSource().getPlayerOrException().getUUID(), target = target(c);
                    return SharedSuggestionProvider.suggest(service.registry().jobs().keySet().stream().filter(id -> {
                        try { service.requireAppointment(actor, target, id); return true; } catch (RuntimeException denied) { return false; }
                    }), b);
                }).executes(safe(c -> request(c, StringArgumentType.getString(c, "job")))))));
        root.then(literal("fire").then(argument("player", StringArgumentType.word()).suggests((c, b) ->
                SharedSuggestionProvider.suggest(CompanyCommands.playerNames(c.getSource().getServer()), b))
                .executes(safe(c -> request(c, "unemployed")))));
        root.then(literal("confirm").then(argument("token", StringArgumentType.word()).executes(safe(c -> {
            var server = c.getSource().getServer(); UUID actor = c.getSource().getPlayerOrException().getUUID();
            var requests = pending.computeIfAbsent(server, ignored -> new HashMap<>());
            Pending p = requests.get(actor); var jobs = JobService.get(server);
            if (p == null || !p.token().toString().equals(StringArgumentType.getString(c, "token")) || p.expires() < System.currentTimeMillis())
                throw new IllegalArgumentException("This appointment expired. Run the command again.");
            if (p.registry() != jobs.registryRevision() || !ledger(c).account(p.target()).job().equals(p.before()))
                throw new IllegalArgumentException("The job changed. Review the appointment again.");
            var online = server.getPlayerList().getPlayer(p.target()); if (online != null) JobDelivery.recover(online);
            jobs.appoint(actor, p.target(), p.next());
            FinancialPersistence.save(server); requests.remove(actor);
            com.mojang.logging.LogUtils.getLogger().info("Job appointment: actor={} target={} from={} to={}", actor, p.target(), p.before(), p.next());
            return message(c, "§aJob updated.");
        }))));
        event.getDispatcher().register(root);
    }
    private static UUID target(com.mojang.brigadier.context.CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "player"); var server = c.getSource().getServer();
        var online = server.getPlayerList().getPlayerByName(name); if (online != null) return online.getUUID();
        var matches = MailboxStore.get(server).knownPlayers().entrySet().stream().filter(e -> e.getValue().equalsIgnoreCase(name)).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("Can't find that player. They need to have joined this server.");
        return matches.getFirst().getKey();
    }
    private static int request(com.mojang.brigadier.context.CommandContext<CommandSourceStack> c, String next) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var server = c.getSource().getServer(); UUID actor = c.getSource().getPlayerOrException().getUUID(), target = target(c);
        var jobs = JobService.get(server); jobs.requireAppointment(actor, target, next);
        String before = ledger(c).account(target).job();
        if (before.equals(next)) throw new IllegalArgumentException("That player already holds this job.");
        var requests = pending.computeIfAbsent(server, ignored -> new HashMap<>());
        requests.values().removeIf(p -> p.expires() < System.currentTimeMillis());
        Pending p = new Pending(UUID.randomUUID(), target, before, next, jobs.registryRevision(), System.currentTimeMillis() + 60000);
        requests.put(actor, p);
        String job = next.equals("unemployed") ? "Unemployed" : jobs.registry().jobs().get(next).name();
        c.getSource().sendSuccess(() -> CompanyChat.confirm("Job Appointment", "§7Set §e" + StringArgumentType.getString(c, "player")
                + "§7's job to §e" + job + "§7?\n§eThis replaces their current job and its authority.", "Confirm", "/jobs confirm " + p.token(), 60), false);
        return 1;
    }
    private JobAppointments() {}
}
