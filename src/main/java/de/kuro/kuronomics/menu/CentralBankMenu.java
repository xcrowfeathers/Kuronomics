package de.kuro.kuronomics.menu;

import de.kuro.kuronomics.economy.*;
import de.kuro.kuronomics.economy.bank.*;
import de.kuro.kuronomics.economy.data.EconomyLedger;
import de.kuro.kuronomics.network.*;
import de.kuro.kuronomics.registry.ModContent;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

public final class CentralBankMenu extends AbstractContainerMenu {
    private static final Map<MinecraftServer, Map<UUID, CentralBankMenu>> OPEN = new WeakHashMap<>();
    public BankSnapshot state;
    private final Player viewer;
    private String status="";
    private int page;
    private BankActivity observed;
    private BankCategory category;
    private boolean window;
    private long observedDay=Long.MIN_VALUE;
    private int lastSyncTick;

    public CentralBankMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) { this(id, inventory); }
    public CentralBankMenu(int id, Inventory inventory) {
        super(ModContent.CENTRAL_BANK_MENU.get(),id); viewer=inventory.player; state=BankSnapshot.empty(id);
        if(viewer instanceof ServerPlayer p) { CentralBank.require(p,"gui",1); OPEN.computeIfAbsent(p.server, ignored->new HashMap<>()).put(p.getUUID(),this); }
    }
    public static void open(ServerPlayer player) {
        CentralBank.require(player,"gui",1);
        player.openMenu(new SimpleMenuProvider((id,inv,p)->new CentralBankMenu(id,inv),Component.literal("Central Bank")), buffer->{});
        if(player.containerMenu instanceof CentralBankMenu menu) menu.sync(player);
    }
    @Override public void broadcastChanges() {
        super.broadcastChanges();
        if (viewer instanceof ServerPlayer p && p.containerMenu == this && (EconomyLedger.get(p.server).bankActivity() != observed || observedDay!=BankActivity.today())
                && p.server.getTickCount()-lastSyncTick>=10) reauthorize(p);
    }
    public void apply(BankSnapshot snapshot) { state=snapshot; }
    @Override public boolean stillValid(Player player) { return viewer==player; }
    @Override public ItemStack quickMoveStack(Player p,int slot) { return ItemStack.EMPTY; }
    @Override public void removed(Player p) { super.removed(p); if(p instanceof ServerPlayer sp) { var open=OPEN.get(sp.server); if(open!=null) open.remove(p.getUUID(),this); } }
    public static void stop(MinecraftServer server) { OPEN.remove(server); }
    public static void left(ServerPlayer player) {
        var open=OPEN.get(player.server); if(open!=null) { open.remove(player.getUUID()); }
    }
    public static void employmentChanged(MinecraftServer server,UUID player) {
        var open=OPEN.get(server); if(open==null) return; var menu=open.get(player);
        if(menu!=null && menu.viewer instanceof ServerPlayer p) menu.reauthorize(p);
    }
    public static void refreshAll(MinecraftServer server) {
        var open=OPEN.get(server); if(open!=null) for(var menu:List.copyOf(open.values())) if(menu.viewer instanceof ServerPlayer p) menu.reauthorize(p);
    }
    private void reauthorize(ServerPlayer p) {
        try { sync(p); } catch(IllegalArgumentException denied) { p.closeContainer(); p.sendSystemMessage(Component.literal(denied.getMessage())); }
    }
    public void action(ServerPlayer p,BankAction action) {
        if(p!=viewer || p.containerMenu!=this || action.container()!=containerId) return;
        try { CentralBank.require(p,"gui",1); } catch(IllegalArgumentException denied) { p.closeContainer(); p.sendSystemMessage(Component.literal(denied.getMessage())); return; }
        status="";
        try {
            switch(action.action()) {
                case "main", "history" -> { category=null; page=0; }
                case "period" -> { window=!window;page=0; }
                case "previous" -> { page=Math.max(0,page-1); }
                case "next" -> { page++; }
                case "refresh" -> { }
                case "close" -> { p.closeContainer(); return; }
                default -> {
                    if(!action.action().startsWith("cat_"))throw new IllegalArgumentException("Unknown bank action.");
                    category=BankCategory.valueOf(action.action().substring(4).toUpperCase(Locale.ROOT));page=0;
                }
            }
        } catch(IllegalArgumentException | IllegalStateException | ArithmeticException failure) { status=Objects.toString(failure.getMessage(),"Bank action failed."); }
        sync(p);
    }
    private static final ThreadLocal<java.text.NumberFormat> MONEY=ThreadLocal.withInitial(()-> {
        var format=java.text.NumberFormat.getNumberInstance(Locale.GERMANY);format.setMinimumFractionDigits(2);format.setMaximumFractionDigits(2);return format;
    });
    private static String money(java.math.BigInteger value,boolean signed) {
        if(value.signum()==0)return "$0,00";
        var format=MONEY.get();
        return (value.signum()<0?"-":signed&&value.signum()>0?"+":"")+"$"+format.format(new java.math.BigDecimal(value.abs(),2));
    }
    public void sync(ServerPlayer p) {
        var access=CentralBank.require(p,"gui",1); var ledger=EconomyLedger.get(p.server); var config=CentralBank.config(p.server);
        observedDay=BankActivity.today();ledger.retainBankHistory(observedDay,config.historyDays(),config.maxHistoryAccountsPerCategory());
        observed=ledger.bankActivity();lastSyncTick=p.server.getTickCount();var history=observed.history();
        var metrics=new ArrayList<BankSnapshot.Metric>();boolean compacted=false;
        for(var type:BankCategory.values()) {
            var sum=history.summary(type,observedDay,window);
            metrics.add(new BankSnapshot.Metric(type.name().toLowerCase(Locale.ROOT),type.label,money(sum.incoming(),false),money(sum.outgoing(),false),money(sum.net(),true),sum.payments()));
            if(category==null||category==type)compacted|=history.compacted(type,observedDay,window);
        }
        var rows=new ArrayList<BankSnapshot.Row>();int total=0;
        if(category!=null) {
            var details=history.view(category,observedDay,window).rows();total=details.size();page=Math.max(0,Math.min(page,Math.max(0,(total-1)/5)));
            for(var row:details.subList(page*5,Math.min(total,page*5+5))) {
                var owner=row.owner();var sum=row.totals();String name=row.archived()?"Older accounts":owner.equals(EconomicOwner.STATE)?"Bank adjustments":CentralBank.name(p.server,owner);
                rows.add(new BankSnapshot.Row(owner.id()==null?new UUID(0,0):owner.id(),row.archived()?"ARCHIVE":owner.type().name(),name,
                        money(sum.incoming(),false),money(sum.outgoing(),false),money(sum.net(),true),sum.payments(),row.archived()));
            }
        } else page=0;
        String position=access.position();if(position.length()>128)position=position.substring(0,128);
        String net=Money.difference(StateTreasury.balance(ledger),ledger.treasuryDayOpening());
        String notice=status.isBlank()&&compacted?"Detail limit reached; earlier values are grouped in Older accounts.":status;
        state=new BankSnapshot(containerId,position,access.rank(),StateTreasury.balance(ledger),net,access.limited(),CentralBank.remaining(p,access,false),CentralBank.remaining(p,access,true),
                notice.length()>256?notice.substring(0,256):notice,category==null?"":category.name().toLowerCase(Locale.ROOT),window,config.historyDays(),config.maxHistoryAccountsPerCategory(),
                history.partial(observedDay,window),page,total,List.copyOf(metrics),List.copyOf(rows));
        PacketDistributor.sendToPlayer(p,state);
    }
}
