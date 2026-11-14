package de.kuro.kuronomics.economy.bank;

import de.kuro.kuronomics.company.SnapshotMap;
import de.kuro.kuronomics.economy.EconomicOwner;
import java.math.BigInteger;
import java.util.*;
import net.minecraft.nbt.*;

/** Store capped totals per account and day. The GUI doesn't need a list of every payment. */
public final class BankHistory {
    public static final int DEFAULT_DAYS = 7, DEFAULT_ACCOUNTS = 2048, MAX_DAYS = 30, MAX_ACCOUNTS = 10000;
    public record Totals(BigInteger incoming, BigInteger outgoing, long payments) {
        public static final Totals ZERO = new Totals(BigInteger.ZERO, BigInteger.ZERO, 0);
        public Totals {
            Objects.requireNonNull(incoming); Objects.requireNonNull(outgoing);
            if (incoming.signum()<0 || outgoing.signum()<0 || payments<0) throw new IllegalArgumentException("Invalid bank history totals");
        }
        public BigInteger net() { return incoming.subtract(outgoing); }
        public Totals plus(Totals other) { return new Totals(incoming.add(other.incoming),outgoing.add(other.outgoing),Math.addExact(payments,other.payments)); }
        static Totals entry(BankActivity.Entry e) {
            var amount=BigInteger.valueOf(e.amount()); return new Totals(e.incoming()?amount:BigInteger.ZERO,e.incoming()?BigInteger.ZERO:amount,1);
        }
    }
    public record Key(long day, EconomicOwner owner) { public Key { Objects.requireNonNull(owner); } }
    public record Account(Totals totals,long lastAt) { public Account { Objects.requireNonNull(totals); if(lastAt<0)throw new IllegalArgumentException("Invalid bank history time"); } }
    public record Row(EconomicOwner owner,Totals totals,long lastAt,boolean archived) { }
    public record View(Totals totals,List<Row> rows) { }
    private static final class Bucket {
        static final Bucket EMPTY=new Bucket(Map.of(),List.of(),Map.of(),Map.of());
        final Map<Key,Account> accounts;
        final List<Key> order;
        final Map<Long,Totals> totals,omitted;
        private long cachedDay=Long.MIN_VALUE;
        private View todayView,windowView;
        Bucket(Map<Key,Account> accounts,List<Key> order,Map<Long,Totals> totals,Map<Long,Totals> omitted) {
            this.accounts=accounts;this.order=order;this.totals=totals;this.omitted=omitted;
        }
        Bucket add(BankActivity.Entry e,long day,int maximum) {
            EconomicOwner owner=e.incoming()?e.payer():e.recipient();
            var key=new Key(day,owner);var amount=Totals.entry(e);var prior=accounts.get(key);
            var rows=new SnapshotMap<>(accounts);var days=new HashMap<>(totals);var overflow=omitted;
            days.merge(day,amount,Totals::plus);List<Key> nextOrder=order;
            if(prior==null) {
                var keys=new ArrayList<>(order);
                if(keys.size()>=maximum) {
                    Key oldest=keys.removeFirst();Account removed=rows.remove(oldest);
                    var edit=new HashMap<>(omitted);edit.merge(oldest.day(),removed.totals(),Totals::plus);overflow=Map.copyOf(edit);
                }
                keys.add(key);nextOrder=List.copyOf(keys);
            }
            rows.put(key,new Account(prior==null?amount:prior.totals().plus(amount),prior==null?e.time():Math.max(e.time(),prior.lastAt())));
            return new Bucket(rows.freeze(),nextOrder,Map.copyOf(days),overflow);
        }
        Bucket retain(long minimum,long today,int maximum) {
            if(accounts.size()<=maximum && totals.keySet().stream().allMatch(day->day>=minimum && day<=today))return this;
            var rows=new SnapshotMap<Key,Account>();var keys=new ArrayList<Key>();var days=new HashMap<Long,Totals>();var overflow=new HashMap<Long,Totals>();
            totals.forEach((day,t)->{if(day>=minimum && day<=today)days.put(day,t);});
            omitted.forEach((day,t)->{if(day>=minimum && day<=today)overflow.put(day,t);});
            for(Key key:order)if(key.day()>=minimum && key.day()<=today){rows.put(key,accounts.get(key));keys.add(key);}
            while(keys.size()>maximum){Key key=keys.removeFirst();overflow.merge(key.day(),rows.remove(key).totals(),Totals::plus);}
            return new Bucket(rows.freeze(),List.copyOf(keys),Map.copyOf(days),Map.copyOf(overflow));
        }
        View view(long today,boolean window) {
            if(cachedDay!=today){cachedDay=today;todayView=null;windowView=null;}
            View cached=window?windowView:todayView;if(cached!=null)return cached;
            var grouped=new HashMap<EconomicOwner,Account>();Totals sum=Totals.ZERO,archive=Totals.ZERO;
            for(var day:totals.entrySet())if(window||day.getKey()==today)sum=sum.plus(day.getValue());
            for(var day:omitted.entrySet())if(window||day.getKey()==today)archive=archive.plus(day.getValue());
            accounts.forEach((key,a)->{if(window||key.day()==today)grouped.merge(key.owner(),a,(one,two)->new Account(one.totals().plus(two.totals()),Math.max(one.lastAt(),two.lastAt())));});
            var rows=new ArrayList<Row>();grouped.forEach((owner,a)->rows.add(new Row(owner,a.totals(),a.lastAt(),false)));
            // Sort by total activity, then account for stable ties. A loan's net alone can hide a lot of
            // activity.
            rows.sort(Comparator.<Row,BigInteger>comparing(r->r.totals().incoming().add(r.totals().outgoing())).reversed().thenComparing(r->r.owner().toString()));
            if(archive.payments()>0)rows.add(new Row(EconomicOwner.STATE,archive,0,true));
            View result=new View(sum,List.copyOf(rows));if(window)windowView=result;else todayView=result;return result;
        }
    }
    public static final BankHistory EMPTY=new BankHistory(Map.of(),Long.MIN_VALUE,DEFAULT_DAYS,DEFAULT_ACCOUNTS,0);
    private final Map<BankCategory,Bucket> categories;
    private final long throughDay,partialBefore;
    private final int days,maximum;
    private BankHistory(Map<BankCategory,Bucket> categories,long throughDay,int days,int maximum,long partialBefore) {
        this.categories=categories;this.throughDay=throughDay;this.days=days;this.maximum=maximum;this.partialBefore=partialBefore;
    }
    public int days(){return days;}
    public int maximum(){return maximum;}
    public boolean partial(long today,boolean window){return partialBefore>0 && day(partialBefore)>=(window?today-days+1:today);}
    public static long day(long time){return java.time.Instant.ofEpochMilli(time).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay();}
    public BankHistory retained(long today,int keepDays,int keepAccounts) {
        if(keepDays<1||keepDays>MAX_DAYS||keepAccounts<1||keepAccounts>MAX_ACCOUNTS)throw new IllegalArgumentException("Invalid bank history retention");
        if(throughDay==today && days==keepDays && maximum==keepAccounts)return this;
        var result=new EnumMap<BankCategory,Bucket>(BankCategory.class);
        categories.forEach((category,bucket)->{var retained=bucket.retain(today-keepDays+1,today,keepAccounts);if(!retained.totals.isEmpty())result.put(category,retained);});
        return new BankHistory(Collections.unmodifiableMap(result),today,keepDays,keepAccounts,partialBefore);
    }
    public BankHistory add(BankActivity.Entry entry,long today) {
        var current=retained(today,days,maximum);long entryDay=day(entry.time());
        if(entryDay<today-days+1 || entryDay>today)return current;
        var category=BankCategory.classify(entry);var result=new EnumMap<BankCategory,Bucket>(BankCategory.class);result.putAll(current.categories);
        result.put(category,current.categories.getOrDefault(category,Bucket.EMPTY).add(entry,entryDay,maximum));
        return new BankHistory(Collections.unmodifiableMap(result),today,days,maximum,partialBefore);
    }
    public View view(BankCategory category,long today,boolean window) {
        var current=retained(today,days,maximum);return current.categories.getOrDefault(category,Bucket.EMPTY).view(today,window);
    }
    public int groups(BankCategory category){return categories.getOrDefault(category,Bucket.EMPTY).accounts.size();}
    public boolean compacted(BankCategory category,long today,boolean window) {
        return retained(today,days,maximum).categories.getOrDefault(category,Bucket.EMPTY).omitted.entrySet().stream()
                .anyMatch(entry->(window||entry.getKey()==today)&&entry.getValue().payments()>0);
    }
    public Totals summary(BankCategory category,long today,boolean window) {
        Totals result=Totals.ZERO;var current=retained(today,days,maximum);
        for(var entry:current.categories.getOrDefault(category,Bucket.EMPTY).totals.entrySet())if(window||entry.getKey()==today)result=result.plus(entry.getValue());
        return result;
    }
    /** Reuse encoded data for unchanged categories, day totals, account partitions and account records. */
    public static final class Encoder {
        private BankHistory previous;private CompoundTag tag;
        public CompoundTag encode(BankHistory next) {
            if(previous==next)return tag;
            var root=new CompoundTag();root.putInt("version",1);root.putInt("days",next.days);root.putInt("maximum",next.maximum);root.putLong("partialBefore",next.partialBefore);
            var cats=new CompoundTag();
            for(var entry:next.categories.entrySet()) {
                String name=entry.getKey().name();var bucket=entry.getValue();var old=previous==null?null:previous.categories.get(entry.getKey());
                CompoundTag prior=previous==null?new CompoundTag():tag.getCompound("categories").getCompound(name);
                if(bucket==old){cats.put(name,prior);continue;}
                var n=new CompoundTag();n.put("totals",saveDays(bucket.totals));n.put("omitted",saveDays(bucket.omitted));
                CompoundTag rows=old==null?new CompoundTag():shallow(prior.getCompound("accounts"));
                Set<Key> changed=old==null?bucket.accounts.keySet():SnapshotMap.differences(old.accounts,bucket.accounts);
                Map<String,CompoundTag> partitions=new HashMap<>();
                for(Key key:changed) {
                    String partition=Integer.toHexString((key.hashCode()^(key.hashCode()>>>16))&255);
                    CompoundTag group=partitions.computeIfAbsent(partition,k->shallow(rows.getCompound(k)));
                    String id=key.day()+":"+key.owner();Account a=bucket.accounts.get(key);
                    if(a==null)group.remove(id);else {var value=saveTotals(a.totals());value.putLong("day",key.day());value.put("owner",key.owner().save());value.putLong("lastAt",a.lastAt());group.put(id,value);}
                }
                partitions.forEach((id,value)->{if(value.isEmpty())rows.remove(id);else rows.put(id,value);});n.put("accounts",rows);
                if(old!=null && old.order==bucket.order)n.put("order",prior.get("order"));else {
                    var order=new ListTag();for(Key key:bucket.order){var k=new CompoundTag();k.putLong("day",key.day());k.put("owner",key.owner().save());order.add(k);}n.put("order",order);
                }
                cats.put(name,n);
            }
            root.put("categories",cats);previous=next;tag=root;return root;
        }
    }
    private static CompoundTag shallow(CompoundTag tag){var result=new CompoundTag();tag.getAllKeys().forEach(key->result.put(key,tag.get(key)));return result;}
    private static CompoundTag saveTotals(Totals totals){var n=new CompoundTag();n.putString("in",totals.incoming().toString());n.putString("out",totals.outgoing().toString());n.putLong("count",totals.payments());return n;}
    private static Totals loadTotals(CompoundTag n){String in=n.getString("in"),out=n.getString("out");if(!in.matches("[0-9]{1,64}")||!out.matches("[0-9]{1,64}")||!n.contains("count",Tag.TAG_LONG))throw new IllegalStateException("Invalid bank history totals");return new Totals(new BigInteger(in),new BigInteger(out),n.getLong("count"));}
    private static CompoundTag saveDays(Map<Long,Totals> days){var result=new CompoundTag();days.forEach((day,t)->result.put(day.toString(),saveTotals(t)));return result;}
    private static Map<Long,Totals> loadDays(CompoundTag n){if(n.size()>MAX_DAYS)throw new IllegalStateException("Bank history day limit exceeded");var result=new HashMap<Long,Totals>();for(String day:n.getAllKeys())result.put(Long.parseLong(day),loadTotals(n.getCompound(day)));return Map.copyOf(result);}
    public CompoundTag save(){return new Encoder().encode(this).copy();}
    public static BankHistory load(CompoundTag tag) {
        if(tag.getInt("version")!=1)throw new IllegalStateException("Unsupported bank history version");
        int days=tag.getInt("days"),maximum=tag.getInt("maximum");if(days<1||days>MAX_DAYS||maximum<1||maximum>MAX_ACCOUNTS)throw new IllegalStateException("Invalid bank history bounds");
        var result=new EnumMap<BankCategory,Bucket>(BankCategory.class);var cats=tag.getCompound("categories");
        for(String name:cats.getAllKeys()) {
            var category=BankCategory.valueOf(name);var n=cats.getCompound(name);var rows=new SnapshotMap<Key,Account>();
            var partitions=n.getCompound("accounts");
            if(partitions.size()>256)throw new IllegalStateException("Bank history partition limit exceeded");
            for(String partition:partitions.getAllKeys())for(String id:partitions.getCompound(partition).getAllKeys()) {
                var a=partitions.getCompound(partition).getCompound(id);var key=new Key(a.getLong("day"),EconomicOwner.load(a.getCompound("owner")));
                if(!id.equals(key.day()+":"+key.owner()) || rows.put(key,new Account(loadTotals(a),a.getLong("lastAt")))!=null || rows.size()>maximum)throw new IllegalStateException("Invalid bank history account");
            }
            var order=new ArrayList<Key>();var seen=new HashSet<Key>();
            for(Tag raw:n.getList("order",Tag.TAG_COMPOUND)){var k=(CompoundTag)raw;var key=new Key(k.getLong("day"),EconomicOwner.load(k.getCompound("owner")));if(!rows.containsKey(key)||!seen.add(key))throw new IllegalStateException("Invalid bank history order");order.add(key);}
            if(order.size()!=rows.size())throw new IllegalStateException("Missing bank history order");
            var totals=loadDays(n.getCompound("totals"));var omitted=loadDays(n.getCompound("omitted"));
            var checked=new HashMap<Long,Totals>(omitted);rows.forEach((key,a)->checked.merge(key.day(),a.totals(),Totals::plus));
            if(!checked.equals(totals))throw new IllegalStateException("Bank history account totals do not reconcile");
            result.put(category,new Bucket(rows.freeze(),List.copyOf(order),totals,omitted));
        }
        return new BankHistory(Collections.unmodifiableMap(result),Long.MIN_VALUE,days,maximum,tag.getLong("partialBefore")).retained(BankActivity.today(),days,maximum);
    }
    static BankHistory legacy(List<BankActivity.Entry> recent) {
        var result=new BankHistory(Map.of(),Long.MIN_VALUE,DEFAULT_DAYS,DEFAULT_ACCOUNTS,System.currentTimeMillis());
        for(int i=recent.size()-1;i>=0;i--)result=result.add(recent.get(i),BankActivity.today());return result;
    }
}
