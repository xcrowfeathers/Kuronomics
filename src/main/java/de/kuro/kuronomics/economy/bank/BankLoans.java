package de.kuro.kuronomics.economy.bank;

import de.kuro.kuronomics.company.SnapshotMap;
import de.kuro.kuronomics.economy.EconomicOwner;
import java.util.*;
import net.minecraft.nbt.*;

/** Index active loans by borrower so payday only visits that borrower's loans. */
public record BankLoans(Map<EconomicOwner,Map<UUID,Loan>> borrowers) {
    public record Loan(UUID id,long principal,long total,long remaining,int paymentsLeft) {
        public Loan { Objects.requireNonNull(id); if(principal<=0||total<principal||remaining<=0||remaining>total||paymentsLeft<=0||paymentsLeft>remaining)throw new IllegalArgumentException("Invalid bank loan."); }
        public long instalment(){return remaining/paymentsLeft+(remaining%paymentsLeft==0?0:1);}
    }
    public static final BankLoans EMPTY=new BankLoans(Map.of());
    public Map<UUID,Loan> forBorrower(EconomicOwner owner){return borrowers.getOrDefault(owner,Map.of());}
    public Editor edit(){return new Editor(this);}
    public static final class Editor {
        private final SnapshotMap<EconomicOwner,Map<UUID,Loan>> borrowers;
        public Editor(BankLoans book){borrowers=new SnapshotMap<>(book.borrowers);}
        public Collection<Loan> loans(EconomicOwner owner){return borrowers.getOrDefault(owner,Map.of()).values();}
        public void add(EconomicOwner owner,Loan loan){var map=new HashMap<>(borrowers.getOrDefault(owner,Map.of()));if(map.putIfAbsent(loan.id(),loan)!=null)throw new IllegalArgumentException("Loan already exists.");borrowers.put(owner,Map.copyOf(map));}
        public void repay(EconomicOwner owner){
            Map<UUID,Loan> remaining=new HashMap<>();
            for(Loan loan:loans(owner))if(loan.paymentsLeft()>1)remaining.put(loan.id(),new Loan(loan.id(),loan.principal(),loan.total(),loan.remaining()-loan.instalment(),loan.paymentsLeft()-1));
            if(remaining.isEmpty())borrowers.remove(owner);else borrowers.put(owner,Map.copyOf(remaining));
        }
        public long discharge(EconomicOwner owner){long total=0;for(var loan:loans(owner))total=Math.addExact(total,loan.remaining());borrowers.remove(owner);return total;}
        public BankLoans build(){return new BankLoans(borrowers.freeze());}
    }
    private static String key(EconomicOwner owner){return owner.type().name()+":"+owner.id();}
    private static String bucket(EconomicOwner owner){int h=owner.id().hashCode()^(owner.isCompany()?1:0);return Integer.toHexString((h^(h>>>16))&255);}
    private static CompoundTag shallow(CompoundTag source){var copy=new CompoundTag();source.getAllKeys().forEach(k->copy.put(k,source.get(k)));return copy;}
    public CompoundTag save(){return new Encoder().encode(this).copy();}
    public static final class Encoder {
        private BankLoans previous;private CompoundTag tag;
        public CompoundTag encode(BankLoans next){
            if(previous==next)return tag;
            var root=previous==null?new CompoundTag():shallow(tag);
            Set<EconomicOwner> changed=previous==null?next.borrowers.keySet():SnapshotMap.differences(previous.borrowers,next.borrowers);
            Map<String,CompoundTag> groups=new HashMap<>();
            for(var owner:changed){String bucket=bucket(owner);var group=groups.computeIfAbsent(bucket,k->shallow(root.getCompound(k)));
                if(next.forBorrower(owner).isEmpty()){group.remove(key(owner));continue;}
                var entry=new CompoundTag();entry.put("owner",owner.save());var loans=new ListTag();
                for(Loan loan:next.forBorrower(owner).values()){var n=new CompoundTag();n.putUUID("id",loan.id());n.putLong("principal",loan.principal());n.putLong("total",loan.total());n.putLong("remaining",loan.remaining());n.putInt("payments",loan.paymentsLeft());loans.add(n);}
                entry.put("loans",loans);group.put(key(owner),entry);
            }
            groups.forEach((key,group)->{if(group.isEmpty())root.remove(key);else root.put(key,group);});previous=next;tag=root;return root;
        }
    }
    public static BankLoans load(CompoundTag root){
        var entries=new SnapshotMap<EconomicOwner,Map<UUID,Loan>>();
        for(String bucket:root.getAllKeys()) {
            if(!root.contains(bucket,Tag.TAG_COMPOUND))throw new IllegalStateException("Invalid bank loan bucket.");
            var group=root.getCompound(bucket);
            for(String key:group.getAllKeys()){
                if(!group.contains(key,Tag.TAG_COMPOUND))throw new IllegalStateException("Invalid bank loan entry.");
                var entry=group.getCompound(key);
                if(!entry.contains("owner",Tag.TAG_COMPOUND)||!entry.contains("loans",Tag.TAG_LIST))throw new IllegalStateException("Missing bank loan data.");
                var owner=EconomicOwner.load(entry.getCompound("owner"));
                if(owner.equals(EconomicOwner.STATE)||!bucket(owner).equals(bucket)||!key(owner).equals(key)||entries.containsKey(owner))throw new IllegalStateException("Invalid loan borrower.");
                Map<UUID,Loan> loans=new HashMap<>();
                for(Tag raw:entry.getList("loans",Tag.TAG_COMPOUND)){
                    var n=(CompoundTag)raw;
                    if(!n.hasUUID("id")||!n.contains("principal",Tag.TAG_LONG)||!n.contains("total",Tag.TAG_LONG)||!n.contains("remaining",Tag.TAG_LONG)||!n.contains("payments",Tag.TAG_INT))throw new IllegalStateException("Invalid bank loan fields.");
                    var loan=new Loan(n.getUUID("id"),n.getLong("principal"),n.getLong("total"),n.getLong("remaining"),n.getInt("payments"));
                    if(loans.putIfAbsent(loan.id(),loan)!=null)throw new IllegalStateException("Duplicate bank loan.");
                }
                if(loans.isEmpty())throw new IllegalStateException("Empty loan borrower index.");entries.put(owner,Map.copyOf(loans));
            }
        }
        return new BankLoans(entries.freeze());
    }
}
