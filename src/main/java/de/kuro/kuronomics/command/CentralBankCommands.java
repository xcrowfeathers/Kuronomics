package de.kuro.kuronomics.command;

import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.menu.CentralBankMenu;
import de.kuro.kuronomics.economy.*;
import de.kuro.kuronomics.economy.bank.*;
import de.kuro.kuronomics.jobs.JobService;
import de.kuro.kuronomics.util.ModText;
import java.math.BigDecimal;
import java.util.*;
import net.minecraft.commands.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import static net.minecraft.commands.Commands.*;
import static de.kuro.kuronomics.command.EconomyCommands.*;

@EventBusSubscriber(modid=Kuronomics.MODID)
public final class CentralBankCommands {
    private static RequiredArgumentBuilder<CommandSourceStack,String> target(boolean company){
        return argument("recipient",StringArgumentType.string()).suggests((c,b)->SharedSuggestionProvider.suggest(company?CompanyCommands.companyNames(c.getSource().getServer()).stream().map(StringArgumentType::escapeIfRequired).toList():c.getSource().getOnlinePlayerNames(),b));
    }
    @SubscribeEvent public static void register(RegisterCommandsEvent event){
        var root=literal("bank").executes(safe(c->{CentralBank.require(c.getSource().getPlayerOrException(),"gui",1);CentralBankMenu.open(c.getSource().getPlayerOrException());return 1;}));
        root.then(literal("reload").requires(s->s.hasPermission(3)&&CentralBank.can(s,"reload",1)).executes(safe(c->{CentralBank.reload(c.getSource().getServer());return message(c,"Bank settings reloaded.");})));
        for(var kind:List.of(CentralBank.Kind.PAY,CentralBank.Kind.DEBIT,CentralBank.Kind.INVEST)){
            String command=kind.name().toLowerCase(Locale.ROOT);int rank=kind==CentralBank.Kind.INVEST?3:1;
            root.then(literal(command).requires(s->CentralBank.can(s,command,rank)).then(target(kind==CentralBank.Kind.INVEST).then(argument("amount",StringArgumentType.word()).executes(safe(c->transfer(c,kind,0,BigDecimal.ZERO))))));
        }
        root.then(literal("balance").requires(s->CentralBank.can(s,"balance",1)).then(target(false).executes(safe(c->{
            var actor=c.getSource().getPlayerOrException();CentralBank.require(actor,"balance",1);var owner=CentralBank.resolve(actor.server,false,StringArgumentType.getString(c,"recipient"));var target=actor.server.getPlayerList().getPlayer(owner.id());
            if(target==null||target.serverLevel()!=actor.serverLevel()||target.distanceToSqr(actor)>100)throw new IllegalArgumentException("The player must be near you!");
            return message(c,"§b"+target.getGameProfile().getName()+" §8· §a"+Money.format(ledger(c).account(owner).balance()));
        }))));
        var loan=literal("loan").requires(s->CentralBank.can(s,"loan",2));
        for(boolean company:new boolean[]{false,true})loan.then(literal(company?"company":"player").then(target(company).then(argument("amount",StringArgumentType.word())
            .then(argument("paydays",IntegerArgumentType.integer(1)).then(argument("interest",StringArgumentType.word()).executes(safe(c->transfer(c,CentralBank.Kind.LOAN,IntegerArgumentType.getInteger(c,"paydays"),new BigDecimal(StringArgumentType.getString(c,"interest")),company))))))));
        root.then(loan);
        for(String command:List.of("promote","demote","fire")){
            var branch=target(false).executes(safe(c->staff(c,command,null)));
            if(!command.equals("fire"))branch.then(argument("job",StringArgumentType.word()).suggests((c,b)->SharedSuggestionProvider.suggest(List.of("banker","bank_chairman","central_bank_governor"),b))
                    .executes(safe(c->staff(c,command,StringArgumentType.getString(c,"job")))));
            root.then(literal(command).requires(s->CentralBank.can(s,command,3)).then(branch));
        }
        event.getDispatcher().register(root);
    }
    private static int transfer(CommandContext<CommandSourceStack> c,CentralBank.Kind kind,int periods,BigDecimal interest)throws com.mojang.brigadier.exceptions.CommandSyntaxException{return transfer(c,kind,periods,interest,kind==CentralBank.Kind.INVEST);}
    private static int transfer(CommandContext<CommandSourceStack> c,CentralBank.Kind kind,int periods,BigDecimal interest,boolean company)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        var actor=c.getSource().getPlayerOrException();CentralBank.require(actor,kind.name().toLowerCase(Locale.ROOT),kind==CentralBank.Kind.INVEST?3:kind==CentralBank.Kind.LOAN?2:1);
        var recipient=CentralBank.resolve(actor.server,company,StringArgumentType.getString(c,"recipient"));
        if(!CentralBank.executeAsync(actor,kind,recipient,Money.positive(amount(c,"amount")),periods,interest,UUID.randomUUID(),
                ()->message(c,"§a"+switch(kind){case PAY->"Payment sent.";case DEBIT->"Debit completed.";case INVEST->"Investment sent.";case LOAN->"Loan granted.";})))return 0;
        return message(c,"§7Bank transaction processing…");
    }
    private static int staff(CommandContext<CommandSourceStack> c,String command,String explicit)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        var actor=c.getSource().getPlayerOrException();CentralBank.require(actor,command,3);var target=CentralBank.resolve(actor.server,false,StringArgumentType.getString(c,"recipient"));
        var jobs=JobService.get(actor.server);String current=ledger(c).account(target).job();int before=CentralBank.rank(current);
        if(before==0&&!current.equals("unemployed"))throw new IllegalArgumentException("That player must quit their current job first.");
        if(before==0&&!command.equals("promote"))throw new IllegalArgumentException("That player does not work for the bank.");
        int after=command.equals("fire")?0:explicit==null?before+(command.equals("promote")?1:-1):CentralBank.rank(explicit);
        if(explicit!=null&&after==0||after<0||after>3||command.equals("promote")&&after<=before||command.equals("demote")&&after>=before)throw new IllegalArgumentException("Choose a valid "+(command.equals("promote")?"higher":"lower")+" bank position.");
        String next=CentralBank.job(after);var definition=jobs.registry().jobs().get(next);String name=definition==null?next:definition.name();
        String notice=after==0?"§cYou no longer work for the bank.":command.equals("promote")?"§aYou got promoted to §6"+name+"§a!":"§eYou got demoted to §6"+name+"§e.";
        jobs.assignNotified(target.id(),next,notice);return message(c,"§aBank position updated.");
    }
}
