package de.kuro.kuronomics.economy.bank;

import de.kuro.kuronomics.economy.*;
import de.kuro.kuronomics.economy.data.*;
import de.kuro.kuronomics.jobs.JobService;
import de.kuro.kuronomics.mailbox.*;
import de.kuro.kuronomics.menu.CentralBankMenu;
import de.kuro.kuronomics.util.ModText;
import java.util.*;
import java.math.BigDecimal;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class CentralBank {
    private static final Map<MinecraftServer,BankConfig> SETTINGS=new WeakHashMap<>();
    public enum Kind {PAY,DEBIT,INVEST,LOAN}
    public record Access(String position,int rank){public boolean limited(){return rank<3;}}
    private CentralBank(){}
    public static BankConfig config(MinecraftServer server){var value=SETTINGS.get(server);if(value==null)throw new IllegalStateException("Bank settings not loaded.");return value;}
    public static void configure(MinecraftServer server,BankConfig config){SETTINGS.put(server,config);if(server.overworld()!=null){EconomyLedger.get(server).trimBankActivity(config.historyLimit());EconomyLedger.get(server).retainBankHistory(BankActivity.today(),config.historyDays(),config.maxHistoryAccountsPerCategory());}CentralBankMenu.refreshAll(server);}
    public static void reload(MinecraftServer server){configure(server,BankConfig.load());}
    public static void stop(MinecraftServer server){SETTINGS.remove(server);CentralBankMenu.stop(server);}
    public static int rank(String id){return switch(id){case "banker"->1;case "bank_chairman"->2;case "central_bank_governor"->3;default->0;};}
    public static String job(int rank){return switch(rank){case 1->"banker";case 2->"bank_chairman";case 3->"central_bank_governor";default->"unemployed";};}
    public static Access access(ServerPlayer player){
        if(!config(player.server).enabled())throw new IllegalArgumentException("Bank features are disabled.");
        if(player.hasPermissions(3))return new Access("Administrator",4);
        var account=EconomyLedger.get(player.server).employment(player.getUUID());var job=account==null||account.suspended()?null:JobService.get(player.server).registry().jobs().get(account.job());
        int rank=job==null?0:rank(job.id());if(rank==0)throw new IllegalArgumentException("You need a bank job to do that.");return new Access(job.name(),rank);
    }
    public static Access require(ServerPlayer player,String command,int minimum){
        var access=access(player);if(!config(player.server).commands().contains(command))throw new IllegalArgumentException("This bank command is disabled.");
        if(access.rank()<minimum)throw new IllegalArgumentException("Your bank position cannot do that.");return access;
    }
    public static boolean can(CommandSourceStack source,String command,int minimum){
        try{if(source.getEntity() instanceof ServerPlayer p){require(p,command,minimum);return true;}
            return source.hasPermission(3)&&config(source.getServer()).enabled()&&config(source.getServer()).commands().contains(command);
        }catch(IllegalArgumentException|IllegalStateException denied){return false;}
    }
    public static void contracts(CommandSourceStack source){
        if(!config(source.getServer()).enabled()||!config(source.getServer()).commands().contains("bankcontract"))throw new IllegalArgumentException("Bank contracts are disabled.");
        if(source.hasPermission(2))return;
        if(!(source.getEntity() instanceof ServerPlayer p))throw new IllegalArgumentException("Bank Chairman permission required.");require(p,"bankcontract",2);
    }
    public static boolean canContracts(CommandSourceStack source){try{contracts(source);return true;}catch(RuntimeException denied){return false;}}
    public static long today(){return BankActivity.today();}
    public static long limit(MinecraftServer server,Access access){return access.rank()==1?config(server).bankerDailyLimit():access.rank()==2?config(server).chairmanDailyLimit():Long.MAX_VALUE;}
    public static long remaining(ServerPlayer player,Access access){return remaining(player,access,false);}
    public static long remaining(ServerPlayer player,Access access,boolean debit){return access.limited()?Math.max(0,limit(player.server,access)-EconomyLedger.get(player.server).bankActivity().used(player.getUUID(),today(),debit)):Long.MAX_VALUE;}
    public static String name(MinecraftServer server,EconomicOwner owner){
        if(owner.equals(EconomicOwner.STATE))return "Bank";
        if(owner.isCompany()){var c=EconomyLedger.get(server).companies().company(owner.id());return c==null?owner.id().toString():c.name();}
        var online=server.getPlayerList().getPlayer(owner.id());if(online!=null)return online.getGameProfile().getName();
        String name=MailboxStore.get(server).knownName(owner.id());return name==null?owner.id().toString():name;
    }
    public static EconomicOwner resolve(MinecraftServer server,boolean company,String input){
        String text=input.strip();if(text.isEmpty()||text.length()>128)throw new IllegalArgumentException("Enter a recipient name or UUID.");UUID id=null;try{id=UUID.fromString(text);}catch(IllegalArgumentException ignored){}
        var ledger=EconomyLedger.get(server);
        if(company){var c=id==null?ledger.companies().named(text):ledger.companies().company(id);if(c==null||c.status()!=de.kuro.kuronomics.company.Company.Status.ACTIVE)throw new IllegalArgumentException("Company not found or closed.");return EconomicOwner.company(c.id());}
        if(id==null){var online=server.getPlayerList().getPlayerByName(text);id=online==null?MailboxStore.get(server).resolveKnown(text):online.getUUID();}
        if(id==null||ledger.employment(id)==null)throw new IllegalArgumentException("Player not found. They need an existing account here.");return EconomicOwner.player(id);
    }
    public static boolean execute(ServerPlayer actor,Kind kind,EconomicOwner recipient,long amount,int periods,BigDecimal interest,UUID reference){
        return executeInternal(actor,kind,recipient,amount,periods,interest,reference,true);
    }
    public static boolean executeAsync(ServerPlayer actor,Kind kind,EconomicOwner recipient,long amount,int periods,BigDecimal interest,UUID reference,Runnable success){
        FinancialSettlements.requireCapacity(actor.server);
        if(!executeInternal(actor,kind,recipient,amount,periods,interest,reference,false))return false;
        var loan=kind==Kind.LOAN?EconomyLedger.get(actor.server).bankLoans().forBorrower(recipient).get(reference):null;
        var notification=notification(actor,recipient,amount,kind,reference,loan);
        FinancialSettlements.after(actor.server,()->{notification.run();success.run();},failure->actor.sendSystemMessage(net.minecraft.network.chat.Component.literal("Bank storage failed; completion is uncertain. Contact an administrator before retrying.")));
        return true;
    }
    private static boolean executeInternal(ServerPlayer actor,Kind kind,EconomicOwner recipient,long amount,int periods,BigDecimal interest,UUID reference,boolean synchronous){
        if(!actor.server.isSameThread())throw new IllegalStateException("Bank transaction off server thread.");FinancialWriter.check(actor.server);
        String command=kind.name().toLowerCase(Locale.ROOT);var access=require(actor,command,kind==Kind.INVEST?3:kind==Kind.LOAN?2:1);var ledger=EconomyLedger.get(actor.server);
        Money.positive(amount);String fingerprint="bank-command|"+actor.getUUID()+"|"+kind+"|"+recipient+"|"+amount+"|"+periods+"|"+interest;
        if(ledger.transactionRecorded(reference,fingerprint))return false;
        if(recipient.equals(EconomicOwner.STATE)||(kind==Kind.INVEST&&!recipient.isCompany())||((kind==Kind.PAY||kind==Kind.DEBIT)&&!recipient.isPlayer()))throw new IllegalArgumentException("Invalid bank recipient.");
        var economy=new EconomyService(ledger);if(recipient.isCompany())economy.requireActive(recipient);else if(ledger.employment(recipient.id())==null)throw new IllegalArgumentException("Player account not found.");
        boolean debit=kind==Kind.DEBIT;long day=today();boolean limited=access.limited()&&(kind==Kind.PAY||debit);
        if(limited&&amount>Math.max(0,limit(actor.server,access)-ledger.bankActivity().used(actor.getUUID(),day,debit)))throw new IllegalArgumentException("That exceeds your remaining daily "+(debit?"debit":"payment")+" limit.");
        if(debit){economy.requireActive(recipient);if(ledger.spendingCapacity(recipient)<amount)throw new EconomyService.InsufficientFundsException("The player has insufficient available funds.");}
        else if(ledger.spendingCapacity(EconomicOwner.STATE)<amount)throw new EconomyService.InsufficientFundsException("Not enough available Treasury funds.");
        var loans=ledger.bankLoans();BankLoans.Loan loan=null;
        if(kind==Kind.LOAN){
            if(interest!=null)interest=interest.stripTrailingZeros();
            if(interest==null || interest.signum()<0 || interest.scale()>6
                    || interest.compareTo(BigDecimal.valueOf(Long.MAX_VALUE).multiply(BigDecimal.valueOf(100)))>0 || periods<=0)
                throw new IllegalArgumentException("Use positive loan periods and a nonnegative interest rate with up to six decimals.");
            long total=Math.addExact(amount,Money.percent(amount,interest));
            if(periods>total)throw new IllegalArgumentException("Each loan instalment must be at least one cent.");
            loan=new BankLoans.Loan(reference,amount,total,total,periods);
            var edit=loans.edit();edit.add(recipient,loan);loans=edit.build();
        }
        Map<EconomicOwner,Account> updates=new HashMap<>();var account=ledger.account(recipient);updates.put(recipient,account.balance(debit?Math.subtractExact(account.balance(),amount):Math.addExact(account.balance(),amount)));
        StateTreasury.stage(ledger,updates,debit?amount:Math.negateExact(amount));
        var activity=ledger.bankActivity().transferred(new BankActivity.Entry(reference,System.currentTimeMillis(),actor.getUUID(),recipient,amount,kind.name()),day,limited,config(actor.server).historyLimit());
        if(!ledger.commitBankTransfer(reference,fingerprint,updates,activity,loans))return false;
        economy.transferEvent(actor.getUUID(),debit?recipient:EconomicOwner.STATE,debit?EconomicOwner.STATE:recipient,amount,false,reference,"kuronomics:bank_"+command,"");
        if(synchronous){var notification=notification(actor,recipient,amount,kind,reference,loan);FinancialPersistence.save(actor.server);notification.run();}return true;
    }
    private static Runnable notification(ServerPlayer actor,EconomicOwner recipient,long amount,Kind kind,UUID reference,BankLoans.Loan loan){
        var alerts=new ArrayList<Runnable>();
        String employee=actor.getGameProfile().getName();String subject=switch(kind){case PAY->"Bank payment";case DEBIT->"Bank debit";case INVEST->"Bank investment";case LOAN->"Bank loan";};
        String message=(kind==Kind.DEBIT?"§c":"§a")+subject+": "+(kind==Kind.DEBIT?"-":"+")+Money.format(amount)+" §7(by "+employee+").";
        if(loan!=null)message+=" §7Repayment: "+Money.format(loan.total())+" over "+loan.paymentsLeft()+" paydays (~ "+Money.format(loan.instalment())+" per payday).";
        final String notice=message;
        try{var mailbox=new MailboxService(actor.server);var ledger=EconomyLedger.get(actor.server);if(recipient.isPlayer()){
            long balance=ledger.account(recipient).balance();
            if(loan!=null)mailbox.system(recipient.id(),reference,subject+" by "+employee,loanDetails(loan,balance));
            else mailbox.payment(reference,kind==Kind.DEBIT?recipient.id():null,kind==Kind.DEBIT?null:recipient.id(),amount,kind==Kind.DEBIT?balance:0,kind==Kind.DEBIT?null:balance,subject+" by "+employee);
            var player=actor.server.getPlayerList().getPlayer(recipient.id());if(player!=null)alerts.add(()->player.sendSystemMessage(ModText.legacy(notice)));
        }else{
            var company=ledger.companies().company(recipient.id());long balance=ledger.account(recipient).balance();
            var details=loan==null?List.of(new MailboxDetail(MailboxDetail.Kind.INCOMING,subject+" by "+employee,amount),
                    new MailboxDetail(MailboxDetail.Kind.INFO,"Company balance",balance)):loanDetails(loan,balance);
            mailbox.system(company.ceo(),reference,subject+": "+company.name(),details);
            var ceo=actor.server.getPlayerList().getPlayer(company.ceo());
            if(ceo!=null)alerts.add(()->ceo.sendSystemMessage(ModText.legacy("§6"+company.name()+" §8· "+notice)));
        }
        }catch(RuntimeException failure){com.mojang.logging.LogUtils.getLogger().error("Bank transaction committed but its notification could not be staged",failure);}
        return ()->alerts.forEach(Runnable::run);
    }
    private static List<MailboxDetail> loanDetails(BankLoans.Loan loan,long balance){
        return List.of(new MailboxDetail(MailboxDetail.Kind.INCOMING,"Loan received",loan.principal()),
                new MailboxDetail(MailboxDetail.Kind.INFO,"Total to repay",loan.total()),
                new MailboxDetail(MailboxDetail.Kind.INFO,"Repay over "+loan.paymentsLeft()+" paydays",null),
                new MailboxDetail(MailboxDetail.Kind.OUTGOING,"~ Per payday",loan.instalment()),
                new MailboxDetail(MailboxDetail.Kind.INFO,"Current balance",balance));
    }

}
