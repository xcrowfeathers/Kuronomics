package de.kuro.kuronomics.economy.bank;

import de.kuro.kuronomics.company.SnapshotMap;
import de.kuro.kuronomics.economy.EconomicOwner;
import java.util.*;
import net.minecraft.nbt.*;

/** Track all Treasury movements. Only manual staff actions count toward daily staff limits. */
public record BankActivity(long day, Map<UUID, Long> spent, Map<UUID, Long> debited, List<Entry> recent, BankHistory history) {
    public record Entry(UUID reference, long time, UUID actor, EconomicOwner payer, EconomicOwner recipient, long amount, String type) {
        public Entry(UUID reference,long time,UUID actor,EconomicOwner recipient,long amount,String type) {
            this(reference,time,actor,type.equals("DEBIT")?recipient:EconomicOwner.STATE,type.equals("DEBIT")?EconomicOwner.STATE:recipient,amount,type);
        }
        public boolean incoming() { return !payer.equals(EconomicOwner.STATE) || type.endsWith("_IN"); }
        public Entry(UUID reference,long time,UUID actor,EconomicOwner recipient,long amount){this(reference,time,actor,recipient,amount,"PAY");}
        public Entry { Objects.requireNonNull(reference); Objects.requireNonNull(actor); Objects.requireNonNull(payer); Objects.requireNonNull(recipient); Objects.requireNonNull(type);
            if (time < 0 || amount <= 0) throw new IllegalArgumentException("Invalid bank transfer history."); }
    }
    private static long dayStart,dayEnd,currentDay;
    public static long today(){
        long now=System.currentTimeMillis();if(now<dayStart||now>=dayEnd){var zone=java.time.ZoneId.systemDefault();var date=java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate();currentDay=date.toEpochDay();dayStart=date.atStartOfDay(zone).toInstant().toEpochMilli();dayEnd=date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();}return currentDay;
    }
    public static final BankActivity EMPTY = new BankActivity(-1, Map.of(), Map.of(), List.of(), BankHistory.EMPTY);
    public BankActivity { recent = List.copyOf(recent); if (recent.size() > 30) throw new IllegalArgumentException("Bank history exceeds its limit."); }
    public BankActivity(long day,Map<UUID,Long> spent,Map<UUID,Long> debited,List<Entry> recent){this(day,spent,debited,recent,BankHistory.EMPTY);}
    public BankActivity(long day,Map<UUID,Long> spent,List<Entry> recent){this(day,spent,Map.of(),recent);}
    public long used(UUID actor,long today,boolean debit){return day==today?(debit?debited:spent).getOrDefault(actor,0L):0;}
    public long used(UUID actor, long today) { return day == today ? spent.getOrDefault(actor, 0L) : 0; }
    public BankActivity transferred(Entry entry, long today, boolean limited, int maximum) {
        Map<UUID, Long> usage = day == today ? spent : Map.of();
        Map<UUID,Long> deductions=day==today?debited:Map.of();
        if (limited && entry.type().equals("DEBIT")) { var edit=new SnapshotMap<>(deductions);edit.put(entry.actor(),Math.addExact(deductions.getOrDefault(entry.actor(),0L),entry.amount()));deductions=edit.freeze(); }
        else if (limited) {
            var edit = new SnapshotMap<>(usage); edit.put(entry.actor(), Math.addExact(usage.getOrDefault(entry.actor(), 0L), entry.amount())); usage = edit.freeze();
        }
        var entries = new ArrayList<Entry>(maximum); entries.add(entry);
        entries.addAll(recent.subList(0, Math.min(recent.size(), maximum - 1)));
        return new BankActivity(today, usage, deductions, entries, history.add(entry, today));
    }
    public BankActivity trim(int maximum) { return recent.size() <= maximum ? this : new BankActivity(day, spent, debited, recent.subList(0, maximum),history); }
    public BankActivity retain(long today,int days,int maximum) {
        var retained=history.retained(today,days,maximum);return retained==history?this:new BankActivity(day,spent,debited,recent,retained);
    }
    public static final class Encoder {
        private BankActivity previous;private CompoundTag tag;
        private final BankHistory.Encoder history=new BankHistory.Encoder();
        public CompoundTag encode(BankActivity next) {
            if(previous==next)return tag;
            var result=next.saveBase();
            result.put("history",history.encode(next.history));previous=next;tag=result;return result;
        }
    }
    public CompoundTag save() {var tag=saveBase();tag.put("history",history.save());return tag;}
    private CompoundTag saveBase() {
        var tag = new CompoundTag(); tag.putLong("day", day);
        var usage = new CompoundTag(); spent.forEach((id, amount) -> usage.putLong(id.toString(), amount)); tag.put("spent", usage);
        var debitUsage=new CompoundTag();debited.forEach((id,amount)->debitUsage.putLong(id.toString(),amount));tag.put("debited",debitUsage);
        var entries = new ListTag();
        for (Entry e : recent) { var n = new CompoundTag(); n.putString("type", e.type()); n.putUUID("reference", e.reference()); n.putLong("time", e.time()); n.putUUID("actor", e.actor()); n.put("payer", e.payer().save()); n.put("recipient", e.recipient().save()); n.putLong("amount", e.amount()); entries.add(n); }
        tag.put("recent", entries); return tag;
    }
    public static BankActivity load(CompoundTag tag) {
        if (!tag.contains("day", Tag.TAG_LONG) || !tag.contains("spent", Tag.TAG_COMPOUND) || !tag.contains("recent", Tag.TAG_LIST)) throw new IllegalStateException("Invalid bank activity data.");
        var spent = new SnapshotMap<UUID, Long>(); var usage = tag.getCompound("spent");
        for (String id : usage.getAllKeys()) { if (!usage.contains(id, Tag.TAG_LONG) || usage.getLong(id) <= 0) throw new IllegalStateException("Invalid daily bank usage."); spent.put(UUID.fromString(id), usage.getLong(id)); }
        if(tag.contains("debited")&&!tag.contains("debited",Tag.TAG_COMPOUND))throw new IllegalStateException("Invalid daily debit usage data.");
        var debited=new SnapshotMap<UUID,Long>();var deductions=tag.getCompound("debited");
        for(String id:deductions.getAllKeys()){if(!deductions.contains(id,Tag.TAG_LONG)||deductions.getLong(id)<=0)throw new IllegalStateException("Invalid daily debit usage.");debited.put(UUID.fromString(id),deductions.getLong(id));}
        if (tag.getList("recent", Tag.TAG_COMPOUND).size()>30) throw new IllegalStateException("Bank history exceeds its limit.");
        var recent = new ArrayList<Entry>(); Set<UUID> references = new HashSet<>();
        for (Tag raw : tag.getList("recent", Tag.TAG_COMPOUND)) { var n = (CompoundTag) raw;
            if (n.getString("type").isBlank() || n.getString("type").length() > 128) throw new IllegalStateException("Invalid bank history transaction type.");
            String type = n.getString("type").equals("TRANSFER") ? "PAY" : n.getString("type");
            var entry = n.contains("payer", Tag.TAG_COMPOUND)
                    ? new Entry(n.getUUID("reference"), n.getLong("time"), n.getUUID("actor"), EconomicOwner.load(n.getCompound("payer")), EconomicOwner.load(n.getCompound("recipient")), n.getLong("amount"), type)
                    : new Entry(n.getUUID("reference"), n.getLong("time"), n.getUUID("actor"), EconomicOwner.load(n.getCompound("recipient")), n.getLong("amount"), type);
            if (!references.add(entry.reference())) throw new IllegalStateException("Duplicate bank history reference."); recent.add(entry); }
        return new BankActivity(tag.getLong("day"), spent.freeze(), debited.freeze(), recent,tag.contains("history",Tag.TAG_COMPOUND)?BankHistory.load(tag.getCompound("history")):BankHistory.legacy(recent));
    }
}
