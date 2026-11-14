package de.kuro.kuronomics.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.economy.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import static net.minecraft.commands.Commands.*;
import static de.kuro.kuronomics.command.EconomyCommands.*;

@EventBusSubscriber(modid = Kuronomics.MODID)
public final class StateBankCommands {
    private StateBankCommands() {}
    @SubscribeEvent public static void register(RegisterCommandsEvent event) {
        var root = literal("statebank").requires(s -> s.hasPermission(3));
        root.then(literal("balance").executes(safe(c -> message(c, "Treasury: " + Money.format(StateTreasury.balance(ledger(c)))))));
        for (String action : new String[]{"set", "add", "remove"}) root.then(literal(action)
                .then(argument("amount", StringArgumentType.word()).executes(safe(c -> {
                    long value = amount(c, "amount");
                    switch (action) {
                        case "set" -> StateTreasury.set(ledger(c), value);
                        case "add" -> StateTreasury.add(ledger(c), value);
                        case "remove" -> StateTreasury.remove(ledger(c), value);
                    }
                    return message(c, "Treasury: " + Money.format(StateTreasury.balance(ledger(c))));
                }))));
        event.getDispatcher().register(root);
    }
}
