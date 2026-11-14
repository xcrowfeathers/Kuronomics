package de.kuro.kuronomics.economy.bank;

import de.kuro.kuronomics.config.EconomyConfig;
import de.kuro.kuronomics.economy.Money;
import java.nio.file.*;
import java.util.*;
import com.electronwill.nightconfig.toml.TomlParser;

public record BankConfig(boolean enabled,long bankerDailyLimit,long chairmanDailyLimit,int historyLimit,Set<String> commands,int historyDays,int maxHistoryAccountsPerCategory) {
    public static final Set<String> COMMANDS=Set.of("gui","pay","debit","balance","bankcontract","loan","invest","promote","demote","fire","reload");
    public static final String DEFAULTS = """
            # Reload with /bank reload or /reloadec.
            [bank]

            enabled = true
            bankerDailyLimit = 10000
            chairmanDailyLimit = 50000
            recentTransferHistoryLimit = 25

            # How many days of history are available to view
            historyDays = 7

            # a max. grouped history cap. default recommended
            maxHistoryAccountsPerCategory = 2048

            [commands]
            gui = true
            pay = true
            debit = true
            balance = true
            bankcontract = true
            loan = true
            invest = true
            promote = true
            demote = true
            fire = true
            reload = true
            """;
    public BankConfig { commands=Set.copyOf(commands); if(bankerDailyLimit<0 || chairmanDailyLimit<0 || historyLimit<1 || historyLimit>30 || historyDays<1 || historyDays>BankHistory.MAX_DAYS || maxHistoryAccountsPerCategory<1 || maxHistoryAccountsPerCategory>BankHistory.MAX_ACCOUNTS) throw new IllegalArgumentException("Invalid bank limits."); }
    public BankConfig(boolean enabled,long bankerDailyLimit,long chairmanDailyLimit,int historyLimit,Set<String> commands) {
        this(enabled,bankerDailyLimit,chairmanDailyLimit,historyLimit,commands,BankHistory.DEFAULT_DAYS,BankHistory.DEFAULT_ACCOUNTS);
    }
    public BankConfig(boolean enabled,long chairmanDailyLimit,int historyLimit) { this(enabled,1000000,chairmanDailyLimit,historyLimit,COMMANDS); }
    private static int integer(Object value,int fallback){return value==null?fallback:new java.math.BigDecimal(value.toString()).intValueExact();}
    public static Path path(){return EconomyConfig.directory().resolve("economy_bank.toml");}
    public static BankConfig load(){
        try{
            if(!Files.exists(path())){Files.createDirectories(path().getParent());Files.writeString(path(),DEFAULTS,StandardOpenOption.CREATE_NEW);}
            String text=Files.readString(path());var root=new TomlParser().parse(text); Object enabled=root.get("bank.enabled");
            if(!(enabled instanceof Boolean on))throw new IllegalArgumentException("bank.enabled must be true or false.");
            Set<String> commands=new HashSet<>();
            for(String key:COMMANDS){Object value=root.get("commands."+key); if(value!=null && !(value instanceof Boolean))throw new IllegalArgumentException("commands."+key+" must be true or false."); if(value==null || Boolean.TRUE.equals(value))commands.add(key);}
            Object banker=root.get("bank.bankerDailyLimit"), chairman=root.get("bank.chairmanDailyLimit");
            // Still accept the old Chairman limit key and keep its configured value.
            if(chairman==null)chairman=root.get("bank.chairmanDailyTransferLimit");
            var result=new BankConfig(on,banker==null?1000000:Money.parse(banker.toString()),chairman==null?5000000:Money.parse(chairman.toString()),
                integer(root.get("bank.recentTransferHistoryLimit"),25),commands,
                integer(root.get("bank.historyDays"),BankHistory.DEFAULT_DAYS),integer(root.get("bank.maxHistoryAccountsPerCategory"),BankHistory.DEFAULT_ACCOUNTS));
            String additions="";
            if(root.get("bank.historyDays")==null)additions+="\n# How many days of history are available to view\nhistoryDays = 7\n";
            if(root.get("bank.maxHistoryAccountsPerCategory")==null)additions+="\n# a max. grouped history cap. default recommended\nmaxHistoryAccountsPerCategory = 2048\n";
            String cleaned=text.replaceAll("(?m)^# Pay and debit have SEPARATE counters, per employee and local server day\\.\\r?\\n?", "");
            if(!additions.isEmpty()) {
                var section=java.util.regex.Pattern.compile("(?m)^\\[bank][ \\t]*(?:#.*)?\\r?$").matcher(cleaned);
                if(section.find()) {
                    var next=java.util.regex.Pattern.compile("(?m)^\\[").matcher(cleaned);
                    int end=next.find(section.end())?next.start():cleaned.length();
                    Files.writeString(path(),cleaned.substring(0,end).stripTrailing()+"\n"+additions+"\n"+cleaned.substring(end));
                }
            } else if(!cleaned.equals(text))Files.writeString(path(),cleaned);
            return result;
        }catch(java.io.IOException|RuntimeException failure){throw new IllegalArgumentException("Cannot load "+path()+"; previous bank settings retained.",failure);}
    }
}
