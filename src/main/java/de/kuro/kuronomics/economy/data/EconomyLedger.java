package de.kuro.kuronomics.economy.data;

import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.LegacyCompatibility;
import de.kuro.kuronomics.economy.EconomicOwner;
import de.kuro.kuronomics.company.CompanyBook;
import java.math.BigDecimal;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.saveddata.SavedData;

/** All ledger access has to run on the server thread. */
public final class EconomyLedger extends de.kuro.kuronomics.economy.data.FinancialSavedData {
    private de.kuro.kuronomics.economy.EconomicEventQueue events;
    private void checkMutation() { if (events != null) de.kuro.kuronomics.economy.EconomicEventQueue.checkMutation(); }
    private EconomyLedger attach(MinecraftServer server) {
        if (events == null) events = new de.kuro.kuronomics.economy.EconomicEventQueue(server); return this;
    }
    public void notifyAfterCommit(net.neoforged.bus.api.Event event) { if (events != null) events.add(event); }
    public void dispatchDurableEvents() { if (events != null) events.dispatch(); }
    private final SnapshotEncoder snapshotEncoder = new SnapshotEncoder();
    /** Only the ledger's writer thread should touch this, not gameplay code. */
    private static final class SnapshotEncoder {
        private EconomyLedger previous;
        private CompoundTag tag;
        private CompoundTag root;
        private final CompanyBook.SnapshotEncoder companies = new CompanyBook.SnapshotEncoder();
        private final de.kuro.kuronomics.economy.bank.BankLoans.Encoder loans = new de.kuro.kuronomics.economy.bank.BankLoans.Encoder();
        private final de.kuro.kuronomics.market.MarketBook.Encoder market = new de.kuro.kuronomics.market.MarketBook.Encoder();
        private final Map<UUID, CompoundTag> accounts = new HashMap<>();
        private final de.kuro.kuronomics.economy.bank.BankActivity.Encoder bank = new de.kuro.kuronomics.economy.bank.BankActivity.Encoder();
        synchronized de.kuro.kuronomics.economy.FinancialUpdate encode(EconomyLedger ledger, HolderLookup.Provider registries) {
            CompoundTag next = ledger.save(new CompoundTag(), registries, previous, tag, companies, accounts, loans);
            next.put("market", market.encode(ledger.market, registries));
            next.put("bankActivity",bank.encode(ledger.bankActivity));
            CompoundTag snapshot = new CompoundTag(); snapshot.put("data", next); NbtUtils.addCurrentDataVersion(snapshot);
            var update = de.kuro.kuronomics.economy.FinancialUpdate.between(root, snapshot);
            previous = ledger; tag = next; root = snapshot; return update;
        }
    }
    private final Map<UUID, Account> accounts = new HashMap<>();
    // Keep personal saves compatible. Other account types still use this same ledger and commit.
    private final Map<EconomicOwner, Account> ownedAccounts = new HashMap<>();
    private CompanyBook companies = CompanyBook.EMPTY;
    private de.kuro.kuronomics.market.MarketBook market = de.kuro.kuronomics.market.MarketBook.EMPTY;
    public de.kuro.kuronomics.market.MarketBook market() { return market; }
    public boolean commitMarket(UUID reference, String fingerprint, Map<EconomicOwner, Account> updates, Collection<Receipt> receipts,
                                CompanyBook companies, de.kuro.kuronomics.market.MarketBook next) {
        Objects.requireNonNull(next);
        if (!commitOwners(reference, fingerprint, updates, receipts, companies, Map.of())) return false;
        market = next; return true;
    }
    private de.kuro.kuronomics.economy.bank.BankActivity bankActivity = de.kuro.kuronomics.economy.bank.BankActivity.EMPTY;
    public de.kuro.kuronomics.economy.bank.BankActivity bankActivity() { return bankActivity; }
    private int bankHistoryLimit = 25;
    public void trimBankActivity(int maximum) {
        bankHistoryLimit = maximum;
        var trimmed = bankActivity.trim(maximum); if (trimmed != bankActivity) { bankActivity = trimmed; setDirty(); }
    }
    public void retainBankHistory(long today,int days,int maximum) {
        var next=bankActivity.retain(today,days,maximum);if(next!=bankActivity){bankActivity=next;setDirty();}
    }
    public boolean commitBankTransfer(UUID reference, String fingerprint, Map<EconomicOwner, Account> updates,
                                     de.kuro.kuronomics.economy.bank.BankActivity activity) {
        Objects.requireNonNull(activity);
        if (!commitOwners(reference, fingerprint, updates, List.of(), companies, Map.of())) return false;
        bankActivity = activity; return true;
    }
    private de.kuro.kuronomics.economy.bank.BankLoans bankLoans=de.kuro.kuronomics.economy.bank.BankLoans.EMPTY;
    public de.kuro.kuronomics.economy.bank.BankLoans bankLoans(){return bankLoans;}
    private long treasuryDay=-1,treasuryOpening;
    public long treasuryDayOpening(){return treasuryDay==de.kuro.kuronomics.economy.bank.BankActivity.today()?treasuryOpening:account(EconomicOwner.STATE).balance();}
    public boolean commitBankTransfer(UUID reference,String fingerprint,Map<EconomicOwner,Account> updates,de.kuro.kuronomics.economy.bank.BankActivity activity,de.kuro.kuronomics.economy.bank.BankLoans loans){
        Objects.requireNonNull(loans);if(!commitBankTransfer(reference,fingerprint,updates,activity))return false;bankLoans=loans;return true;
    }
    public boolean commitOwnersLoans(UUID reference,String fingerprint,Map<EconomicOwner,Account> updates,Collection<Receipt> receipts,CompanyBook next,de.kuro.kuronomics.economy.bank.BankLoans loans){
        return commitOwnersLoans(reference,fingerprint,updates,receipts,next,loans,null);
    }
    public boolean commitOwnersLoans(UUID reference,String fingerprint,Map<EconomicOwner,Account> updates,Collection<Receipt> receipts,CompanyBook next,de.kuro.kuronomics.economy.bank.BankLoans loans, List<de.kuro.kuronomics.economy.bank.TreasuryMovement> movements) {
        Objects.requireNonNull(loans);if(!commitOwners(reference,fingerprint,updates,receipts,next,Map.of(),movements))return false;bankLoans=loans;return true;
    }
    public boolean commitPaydayLoans(UUID reference,String fingerprint,Map<EconomicOwner,Account> updates,Collection<RecurringPayment> removed,Collection<Receipt> received,Set<UUID> settled,Collection<PaydayCharge> paidCharges,CompanyBook next,de.kuro.kuronomics.economy.bank.BankLoans loans){
        return commitPaydayLoans(reference,fingerprint,updates,removed,received,settled,paidCharges,next,loans,null);
    }
    public boolean commitPaydayLoans(UUID reference,String fingerprint,Map<EconomicOwner,Account> updates,Collection<RecurringPayment> removed,Collection<Receipt> received,Set<UUID> settled,Collection<PaydayCharge> paidCharges,CompanyBook next,de.kuro.kuronomics.economy.bank.BankLoans loans,List<de.kuro.kuronomics.economy.bank.TreasuryMovement> movements) {
        Objects.requireNonNull(loans);if(!commitPaydayOwners(reference,fingerprint,updates,removed,received,settled,paidCharges,next,movements))return false;bankLoans=loans;return true;
    }
    // Reloaded at startup or config reload. This is policy, not another saved balance.
    private long companyDebtLimit = 1_000_000;
    private boolean legacyCompanyAccess;
    public boolean legacyCompanyAccess() { return legacyCompanyAccess; }
    public void companyAccessUpgraded() { legacyCompanyAccess = false; }
    public void companyDebtLimit(long value) { if (value < 0) throw new IllegalArgumentException("Negative debt limit"); companyDebtLimit = value; }
    public long spendingCapacity(EconomicOwner owner) {
        long available = Math.subtractExact(account(owner).balance(), reserved(owner));
        if (!owner.isCompany()) return available;
        // Clamp only the positive headroom calculation. Actual money changes must still use exact arithmetic.
        return available > Long.MAX_VALUE - companyDebtLimit ? Long.MAX_VALUE : Math.addExact(available, companyDebtLimit);
    }
    private final Map<UUID, List<RecurringPayment>> contracts = new HashMap<>();
    private final Map<UUID, Long> checks = new HashMap<>();
    private final Map<UUID, Reservation> reservations = new HashMap<>();
    private final Map<EconomicOwner, Long> reservedTotals = new HashMap<>();
    private final Map<EconomicOwner, Map<UUID, Reservation>> activeReservations = new HashMap<>();
    private final Map<UUID, LinkedHashMap<UUID, PaydayCharge>> activeCharges = new HashMap<>();
    private final Map<UUID, String> transactions = new HashMap<>();
    private final FinancialHistory transactionHistory = new FinancialHistory();
    private final FinancialHistory retiredHolds = new FinancialHistory();
    private final FinancialHistory retiredCharges = new FinancialHistory();
    /**
     * Only call before startup recovery. Use references from saved source journals, not newer in-memory
     * state that might fail to save. Don't delete records across files during normal gameplay.
     */
    public int pruneClosedAtStartup(Set<UUID> durableReferences) {
        int removed = 0;
        var charges = paydayCharges.entrySet().iterator();
        while (charges.hasNext()) {
            var entry = charges.next(); var value = entry.getValue();
            if (value.status() == PaydayCharge.Status.ACTIVE || durableReferences.contains(entry.getKey())) continue;
            retiredCharges.retire(entry.getKey(), "closed-charge"); charges.remove(); removed++;
        }
        Set<UUID> pinned = new HashSet<>(durableReferences);
        paydayCharges.values().forEach(value -> pinned.add(value.reservationReference()));
        var holds = reservations.entrySet().iterator();
        while (holds.hasNext()) {
            var entry = holds.next();
            if (entry.getValue().active() || pinned.contains(entry.getKey())) continue;
            retiredHolds.retire(entry.getKey(), "closed-hold"); holds.remove(); removed++;
        }
        if (removed > 0) setDirty(); return removed;
    }
    private void recordTransaction(UUID id, String fingerprint) {
        transactions.put(id, fingerprint); transactionHistory.record(id);
    }
    /**
     * The saved payday sequence replaces individual payday receipts. Keep compact retry records for other
     * actions.
     */
    private boolean pastPayday(UUID reference, String fingerprint) {
        if (!fingerprint.startsWith("payday-settlement|payday:")) return false;
        try {
            String[] envelope = fingerprint.split("\\|", -1);
            if (envelope.length != 3 || !envelope[2].equals(reference.toString())) return false;
            String[] parts = envelope[1].split(":", -1);
            if (parts.length != 3) return false;
            UUID player = UUID.fromString(parts[1]); long sequence = Long.parseLong(parts[2]);
            return sequence >= 0 && account(player).paydaySequence() > sequence
                    && reference.equals(de.kuro.kuronomics.economy.EconomyService.stableReference("payday", player + ":" + sequence));
        } catch (IllegalArgumentException invalid) { return false; }
    }
    public int pruneHistory(int maximum, long ageMillis, long now) {
        int removed = transactionHistory.prune(transactions, maximum, ageMillis, now, (id, value) -> true,
                (id, value) -> { if (!pastPayday(id, value)) transactionHistory.retire(id, value); });
        if (removed > 0) setDirty(); return removed;
    }
    private final Map<UUID, PaydayCharge> paydayCharges = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Long>> receipts = new HashMap<>();
    private final Map<UUID, List<Receipt>> marketplaceReceipts = new HashMap<>();
    private final Map<UUID, Long> nonTaxablePending = new HashMap<>();
    private final Map<String, de.kuro.kuronomics.estate.Estate> estates = new HashMap<>();
    private final Map<UUID, List<de.kuro.kuronomics.estate.Estate>> tenantEstates = new HashMap<>();
    private final Map<EconomicOwner, Map<String, de.kuro.kuronomics.estate.Estate>> ownerEstates = new HashMap<>();
    private final Map<String, de.kuro.kuronomics.estate.Estate> regionEstates = new HashMap<>();
    public de.kuro.kuronomics.estate.Estate estateByRegion(String dimension, String region) { return regionEstates.get(dimension + "|" + region.toLowerCase(Locale.ROOT)); }
    private final Set<String> evictions = new HashSet<>();
    private final Map<UUID, Integer> observedTicks = new HashMap<>();
    private final Map<UUID, Long> accountVersions = new HashMap<>();
    private int sampledTick;
    private boolean tickSampled;
    private BigDecimal fee = new BigDecimal("2");
    private boolean paydayEnabled = true;
    private boolean imported;

    public static EconomyLedger get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Economy accessed off server thread");
        Factory<EconomyLedger> factory = new Factory<>(EconomyLedger::new, EconomyLedger::load);
        var storage = server.overworld().getDataStorage();
        String current = Kuronomics.MODID + "_economy";
        EconomyLedger ledger = storage.get(factory, current);
        if (ledger != null) return ledger.attach(server);
        var legacyFile = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(LegacyCompatibility.LEDGER + ".dat");
        if (Files.exists(legacyFile)) {
            try {
                CompoundTag root = NbtIo.readCompressed(legacyFile, NbtAccounter.create(256 * 1024 * 1024));
                EconomyLedger legacy = load(root.getCompound("data"), server.registryAccess());
                storage.set(current, legacy); legacy.setDirty(); return legacy.attach(server);
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("Cannot migrate legacy economy ledger", e);
            }
        }
        return storage.computeIfAbsent(factory, current).attach(server);
    }
    private de.kuro.kuronomics.jobs.JobRegistry jobs = de.kuro.kuronomics.jobs.JobRegistry.EMPTY;
    public de.kuro.kuronomics.jobs.JobRegistry jobs() { return jobs; }
    public void jobs(de.kuro.kuronomics.jobs.JobRegistry value) { jobs = value; }
    public Map<UUID, Account> employmentAccounts() { return Map.copyOf(accounts); }
    public Account employment(UUID id) { return accounts.get(id); }
    public void taskEarnings(UUID id, Account value) { checkMutation(); accounts.put(id, value); changed(id); setDirty(); }
    private static Map<String, Long> readTaskEarnings(CompoundTag tag) {
        Map<String, Long> result = new HashMap<>(); for (String key : tag.getAllKeys()) result.put(key, tag.getLong(key)); return result;
    }
    public boolean contains(UUID id) { return accounts.containsKey(id); }
    public void joined(UUID id, int tick) {
        if (!tickSampled) { sampledTick = tick; tickSampled = true; }
        if (paydayEnabled && accounts.putIfAbsent(id, Account.empty()) == null) setDirty();
        observedTicks.put(id, tick);
    }
    /** Sample time once per server. Only apply the elapsed time when an account is read or saved. */
    public void sampleTick(int tick) {
        sampledTick = tick; tickSampled = true;
        if (paydayEnabled && !observedTicks.isEmpty()) setDirty();
    }
    private long sampledElapsed(int previous) {
        if (!tickSampled) return 0;
        long elapsed = Integer.toUnsignedLong(sampledTick - previous);
        // A player may join after this sample. The next sample will catch their time up.
        return elapsed > Integer.MAX_VALUE ? 0 : elapsed;
    }
    public void observe(UUID id, int tick, boolean leaving) {
        Integer previous = leaving ? observedTicks.remove(id) : observedTicks.put(id, tick);
        if (previous != null && paydayEnabled) {
            long elapsed = Integer.toUnsignedLong(tick - previous);
            Account stored = accounts.getOrDefault(id, Account.empty());
            accounts.put(id, stored.ticks(Math.addExact(stored.playTicks(), elapsed)));
            setDirty();
        }
    }
    public Collection<de.kuro.kuronomics.estate.Estate> estates() { return List.copyOf(estates.values()); }
    public boolean hasEstates() { return !regionEstates.isEmpty(); }
    public de.kuro.kuronomics.estate.Estate estateByKey(String key) { return estates.get(key); }
    public List<de.kuro.kuronomics.estate.Estate> estatesForTenant(UUID tenant) { return tenantEstates.getOrDefault(tenant, List.of()); }
    private void indexTenants() {
        regionEstates.clear(); for (var estate : estates.values()) regionEstates.put(estate.dimension() + "|" + estate.region().toLowerCase(Locale.ROOT), estate);
        Map<UUID, List<de.kuro.kuronomics.estate.Estate>> grouped = new HashMap<>();
        for (var estate : estates.values()) if (estate.tenant() != null)
            grouped.computeIfAbsent(estate.tenant(), ignored -> new ArrayList<>()).add(estate);
        tenantEstates.clear();
        grouped.forEach((tenant, entries) -> tenantEstates.put(tenant, List.copyOf(entries)));
    }
    public List<de.kuro.kuronomics.estate.Estate> estatesOwned(EconomicOwner owner) { return List.copyOf(ownerEstates.getOrDefault(owner, Map.of()).values()); }
    public void estate(de.kuro.kuronomics.estate.Estate estate) {
        checkMutation();
        var previous = estates.put(estate.key(), estate);
        if (previous != null) unindexEstate(previous);
        ownerEstates.computeIfAbsent(estate.economicOwner(), ignored -> new HashMap<>()).put(estate.key(), estate);
        regionEstates.put(estate.dimension() + "|" + estate.region().toLowerCase(Locale.ROOT), estate);
        if (estate.tenant() != null) {
            var rented = new ArrayList<>(tenantEstates.getOrDefault(estate.tenant(), List.of())); rented.add(estate);
            tenantEstates.put(estate.tenant(), List.copyOf(rented));
        }
        setDirty();
    }
    private void unindexEstate(de.kuro.kuronomics.estate.Estate estate) {
        ownerEstates.getOrDefault(estate.economicOwner(), new HashMap<>()).remove(estate.key());
        regionEstates.remove(estate.dimension() + "|" + estate.region().toLowerCase(Locale.ROOT));
        if (estate.tenant() != null) {
            var remaining = tenantEstates.getOrDefault(estate.tenant(), List.of()).stream().filter(e -> !e.key().equals(estate.key())).toList();
            if (remaining.isEmpty()) tenantEstates.remove(estate.tenant()); else tenantEstates.put(estate.tenant(), remaining);
        }
    }
    public void removeEstate(String key) {
        var previous = estates.remove(key);
        if (previous != null) unindexEstate(previous);
        setDirty();
    }
    public Set<String> evictions() { return Set.copyOf(evictions); }
    public boolean evictionPending(String key) { return evictions.contains(key); }
    public void queueEviction(String key) { evictions.add(key); setDirty(); }
    public void clearEviction(String key) { evictions.remove(key); setDirty(); }
    public Account account(UUID id) {
        Account stored = accounts.getOrDefault(id, Account.empty());
        Integer previous = observedTicks.get(id);
        if (!paydayEnabled || previous == null) return stored;
        long elapsed = sampledElapsed(previous);
        return elapsed == 0 ? stored : stored.ticks(Math.addExact(stored.playTicks(), elapsed));
    }
    public long reserved(UUID id) {
        return reserved(EconomicOwner.player(id));
    }
    public long reserved(EconomicOwner owner) { return reservedTotals.getOrDefault(owner, 0L); }
    public Account account(EconomicOwner owner) { return owner.isPlayer() ? account(owner.id()) : ownedAccounts.getOrDefault(owner, Account.empty()); }
    public CompanyBook companies() { return companies; }
    private void putReservation(UUID reference, Reservation value) {
        Reservation previous = reservations.get(reference);
        if (previous != null && !previous.economicOwner().equals(value.economicOwner())) throw new IllegalStateException("Reservation owner changed");
        long total = reserved(value.economicOwner());
        if (previous != null && previous.active()) total = Math.subtractExact(total, previous.amount());
        if (value.active()) total = Math.addExact(total, value.amount());
        reservations.put(reference, value);
        var active = activeReservations.get(value.economicOwner());
        if (active != null) { active.remove(reference); if (active.isEmpty()) activeReservations.remove(value.economicOwner()); }
        if (value.active()) activeReservations.computeIfAbsent(value.economicOwner(), ignored -> new HashMap<>()).put(reference, value);
        if (total == 0) reservedTotals.remove(value.economicOwner()); else reservedTotals.put(value.economicOwner(), total);
    }
    private void putCharge(UUID reference, PaydayCharge value) {
        PaydayCharge previous = paydayCharges.put(reference, value);
        if (previous != null) {
            var values = activeCharges.get(previous.payer());
            if (values != null) { values.remove(reference); if (values.isEmpty()) activeCharges.remove(previous.payer()); }
        }
        if (value.status() == PaydayCharge.Status.ACTIVE)
            activeCharges.computeIfAbsent(value.payer(), id -> new LinkedHashMap<>()).put(reference, value);
    }
    public Reservation reservation(UUID reference) { return reservations.get(reference); }
    public Collection<Reservation> reservations() { return List.copyOf(reservations.values()); }
    public Collection<Reservation> activeReservations(EconomicOwner owner) { return List.copyOf(activeReservations.getOrDefault(owner, Map.of()).values()); }
    public PaydayCharge paydayCharge(UUID reference) { return paydayCharges.get(reference); }
    public List<PaydayCharge> paydayCharges(UUID payer) {
        var values = activeCharges.get(payer); return values == null ? List.of() : List.copyOf(values.values());
    }
    public Collection<PaydayCharge> paydayCharges() { return List.copyOf(paydayCharges.values()); }
    public boolean hasTransaction(UUID reference) { return transactions.containsKey(Objects.requireNonNull(reference)) || transactionHistory.contains(reference); }
    public boolean transactionRecorded(UUID reference, String fingerprint) {
        String recorded = transactions.get(Objects.requireNonNull(reference));
        if (recorded == null) return pastPayday(reference, fingerprint) || transactionHistory.matches(reference, fingerprint);
        if (!recorded.equals(fingerprint)) throw new IllegalArgumentException("Transaction reference was already used for another operation");
        return true;
    }
    public long accountVersion(UUID id) { return accountVersions.getOrDefault(id, 0L); }
    private void changed(UUID id) {
        accountVersions.put(id, accountVersions.getOrDefault(id, 0L) + 1);
        Integer previous = observedTicks.get(id);
        if (previous != null && sampledElapsed(previous) > 0) observedTicks.put(id, sampledTick);
    }
    public Map<String, Long> receipts(UUID id) { return Map.copyOf(receipts.getOrDefault(id, Map.of())); }
    public List<Receipt> marketplaceReceipts(UUID id) { return List.copyOf(marketplaceReceipts.getOrDefault(id, List.of())); }
    public long nonTaxablePending(UUID id) { return nonTaxablePending.getOrDefault(id, 0L); }
    private void commit(Map<UUID, Account> updates, Collection<RecurringPayment> removed, Collection<Receipt> received, Set<UUID> settled) {
        apply(updates, removed, received, settled);
    }
    private void apply(Map<UUID, Account> updates, Collection<RecurringPayment> removed, Collection<Receipt> received, Set<UUID> settled) {
        checkMutation();
        Map<UUID, Map<String, Long>> pending = new HashMap<>();
        Map<UUID, List<Receipt>> pendingMarketplace = new HashMap<>();
        Map<UUID, Long> pendingNonTaxable = new HashMap<>();
        for (Receipt receipt : received) {
            if (receipt.marketplace()) {
                List<Receipt> values = pendingMarketplace.computeIfAbsent(receipt.recipient(), id ->
                        new ArrayList<>(settled.contains(id) ? List.of() : marketplaceReceipts(id)));
                // Use one payday summary for global shop sales, not another row for every click.
                // Add the taxes already charged. Don't calculate tax again on the combined amount.
                int same = -1;
                if (receipt.sourceType() == de.kuro.kuronomics.economy.tax.MarketplaceTaxService.SourceType.GLOBAL_MARKET_SALE)
                    for (int i = 0; i < values.size(); i++) { Receipt old = values.get(i);
                        if (old.sourceType() == receipt.sourceType() && old.taxable() == receipt.taxable() && old.description().equals(receipt.description())) { same = i; break; }
                    }
                if (same < 0) values.add(receipt);
                else { Receipt old = values.get(same); values.set(same, new Receipt(receipt.recipient(), receipt.description(), Math.addExact(old.amount(), receipt.amount()), receipt.taxable(), receipt.sourceType(),
                        Math.addExact(old.grossAmount(), receipt.grossAmount()), Math.addExact(old.marketplaceTax(), receipt.marketplaceTax()), Math.addExact(old.netAmount(), receipt.netAmount()))); }
            } else {
                Map<String, Long> grouped = pending.computeIfAbsent(receipt.recipient(), id ->
                        new LinkedHashMap<>(settled.contains(id) ? Map.of() : receipts(id)));
                grouped.merge(Receipt.groupedDescription(receipt.description()), receipt.amount(), Math::addExact);
            }
            if (!receipt.taxable()) pendingNonTaxable.merge(receipt.recipient(), receipt.amount(), Math::addExact);
        }
        Set<UUID> affected = new HashSet<>(pending.keySet()); affected.addAll(pendingMarketplace.keySet());
        for (UUID id : affected) if (!settled.contains(id) && nonTaxablePending.containsKey(id))
            pendingNonTaxable.merge(id, nonTaxablePending.get(id), Math::addExact);
        // Calculate everything before changing accounts or receipt labels, so overflow can't leave half a
        // change.
        for (UUID id : affected) {
            long total = 0;
            for (long amount : pending.getOrDefault(id, receipts(id)).values()) total = Math.addExact(total, amount);
            for (Receipt receipt : pendingMarketplace.getOrDefault(id, marketplaceReceipts(id))) total = Math.addExact(total, receipt.amount());
            if (total > updates.getOrDefault(id, account(id)).pendingIncome()) throw new IllegalArgumentException("Receipt exceeds pending credit");
            if (pendingNonTaxable.getOrDefault(id, 0L) > total) throw new IllegalArgumentException("Non-taxable credit exceeds pending receipts");
        }
        settled.forEach(id -> { receipts.remove(id); marketplaceReceipts.remove(id); nonTaxablePending.remove(id); });
        pending.forEach((id, values) -> receipts.put(id, Map.copyOf(values))); pendingMarketplace.forEach((id, values) -> marketplaceReceipts.put(id, List.copyOf(values)));
        pendingNonTaxable.forEach((id, amount) -> { if (amount == 0) nonTaxablePending.remove(id); else nonTaxablePending.put(id, amount); });
        accounts.putAll(updates); updates.keySet().forEach(this::changed);
        removed.forEach(this::removeContract); setDirty();
    }
    public boolean commitTransaction(UUID reference, String fingerprint, Map<UUID, Account> updates,
                                     Collection<RecurringPayment> removed, Collection<Receipt> received,
                                     Set<UUID> settled) {
        Objects.requireNonNull(fingerprint);
        if (transactionRecorded(reference, fingerprint)) return false;
        apply(updates, removed, received, settled);
        recordTransaction(reference, fingerprint); setDirty(); return true;
    }
    public static Map<EconomicOwner, Account> personalUpdates(Map<UUID, Account> values) {
        Map<EconomicOwner, Account> result = new HashMap<>(); values.forEach((id, a) -> result.put(EconomicOwner.player(id), a)); return result;
    }
    private void validateOwners(Map<EconomicOwner, Account> updates, CompanyBook next) {
        updates.forEach((owner, a) -> {
            if (!owner.isPlayer() && (a.playTicks() != 0 || a.jobEarnings() != 0 || !a.taskEarnings().isEmpty()))
                throw new IllegalArgumentException("Invalid non-player financial account");
            if (owner.isCompany()) {
                if (next.company(owner.id()) == null) throw new IllegalArgumentException("Unknown Company account");
                Account existing = ownedAccounts.get(owner);
                // A balance-only change doesn't invalidate income buffers we've already checked.
                // New accounts and loaded accounts (existing == a) still need every credit checked.
                if (existing == null || existing == a || existing.pendingIncome() != a.pendingIncome()
                        || next.buffer(owner.id()) != companies.buffer(owner.id())) {
                    long pending = 0; for (var credit : next.buffer(owner.id()).values()) pending = Math.addExact(pending, credit.amount());
                    if (pending != a.pendingIncome()) throw new IllegalArgumentException("Company buffer does not match funded income");
                }
            }
        });
    }
    private void applyOwners(Map<EconomicOwner, Account> updates, Collection<RecurringPayment> removed,
                              Collection<Receipt> received, Set<UUID> settled, CompanyBook next, UUID reference, String fingerprint, List<de.kuro.kuronomics.economy.bank.TreasuryMovement> movements) {
        validateOwners(updates, next); Map<UUID, Account> personal = new HashMap<>();
        Account state=updates.get(EconomicOwner.STATE);long day=treasuryDay,opening=treasuryOpening;
        if(state!=null && state.balance()!=account(EconomicOwner.STATE).balance()){
            long today=de.kuro.kuronomics.economy.bank.BankActivity.today();if(day!=today){day=today;opening=account(EconomicOwner.STATE).balance();}
        }
        var nextActivity = treasuryActivity(reference, fingerprint, updates, next, received, movements);
        updates.forEach((owner, a) -> { if (owner.isPlayer()) personal.put(owner.id(), a); });
        apply(personal, removed, received, settled);
        updates.forEach((owner, a) -> { if (!owner.isPlayer()) ownedAccounts.put(owner, a); }); companies = next; treasuryDay=day;treasuryOpening=opening; bankActivity = nextActivity;
    }
    private de.kuro.kuronomics.economy.bank.BankActivity treasuryActivity(UUID reference, String fingerprint,
            Map<EconomicOwner, Account> updates, CompanyBook next, Collection<Receipt> received,
            List<de.kuro.kuronomics.economy.bank.TreasuryMovement> movements) {
        Account state = updates.get(EconomicOwner.STATE);
        if ((state == null && movements == null) || fingerprint.startsWith("bank-command|")) return bankActivity;
        java.math.BigInteger delta = java.math.BigInteger.valueOf(state == null ? account(EconomicOwner.STATE).balance() : state.balance()).subtract(java.math.BigInteger.valueOf(account(EconomicOwner.STATE).balance()));
        var legs = movements;
        if (legs == null) {
            if (delta.signum() == 0) return bankActivity;
            EconomicOwner other = EconomicOwner.STATE; UUID actor = null; String auditLabel=null;
            for (int i = next.audit()==companies.audit() ? -1 : next.audit().size()-1; i >= 0; i--) { var a = next.audit().get(i); if (a.operation().equals(reference) && (a.payer().equals(EconomicOwner.STATE) || a.recipient().equals(EconomicOwner.STATE))) {
                other = a.payer().equals(EconomicOwner.STATE) ? a.recipient() : a.payer(); actor = a.actor(); auditLabel=a.category().equals("COMPANY_CLOSING")?"Company closing tax":a.category(); break;
            } }
            if (fingerprint.startsWith("transfer|")) {var parts=fingerprint.split("\\|",3);other=EconomicOwner.player(UUID.fromString(parts[1]));}
            for (Receipt receipt:received) if(receipt.marketplace() && receipt.marketplaceTax()>0){other=EconomicOwner.player(receipt.recipient());break;}
            if (fingerprint.startsWith("funded-sale|") && received.isEmpty()) {
                for(int i=next.audit().size()-1;i>=0;i--){var audit=next.audit().get(i);if(audit.operation().equals(reference)&&audit.category().equals("COMPANY_SHOP_SALE")){other=audit.recipient();auditLabel="Sales tax";break;}}
            }
            if (other.equals(EconomicOwner.STATE)) {
                var owners = updates.keySet().stream().filter(o -> !o.equals(EconomicOwner.STATE)).sorted(Comparator.comparing(EconomicOwner::toString)).toList();
                if (!owners.isEmpty()) other = owners.getFirst();
            }
            String label = fingerprint.split("[|:]", 2)[0].replace('-', ' ');
            if (fingerprint.startsWith("funded-sale|") || fingerprint.startsWith("push-marketplace-income|")) label="Sales tax";
            else if(fingerprint.startsWith("job-change|"))label="Job salary";
            else if (fingerprint.startsWith("withdraw|") || fingerprint.startsWith("deposit|")) {var parts=fingerprint.split("\\|",4);if(parts.length==4)label=parts[3];}
            if(auditLabel!=null)label=auditLabel;
            if (label.length() > 110) label = label.substring(0,110);
            legs = new ArrayList<>();
            var remaining = delta.abs(); var maximum = java.math.BigInteger.valueOf(Long.MAX_VALUE);
            while (remaining.signum() > 0) {
                long amount = remaining.min(maximum).longValueExact();
                legs.add(new de.kuro.kuronomics.economy.bank.TreasuryMovement(delta.signum()>0?other:EconomicOwner.STATE,
                        delta.signum()>0?EconomicOwner.STATE:other, amount, label + (other.equals(EconomicOwner.STATE) ? delta.signum()>0?"_IN":"_OUT" : ""), actor));
                remaining = remaining.subtract(java.math.BigInteger.valueOf(amount));
            }
        }
        java.math.BigInteger recorded = java.math.BigInteger.ZERO;
        var result = bankActivity; int index = 0; long time = System.currentTimeMillis();
        for (var leg : legs) {
            boolean incoming = !leg.payer().equals(EconomicOwner.STATE) || leg.label().endsWith("_IN");
            recorded = recorded.add(java.math.BigInteger.valueOf(leg.amount()).multiply(java.math.BigInteger.valueOf(incoming?1:-1)));
            result = result.transferred(new de.kuro.kuronomics.economy.bank.BankActivity.Entry(
                    de.kuro.kuronomics.economy.EconomyService.stableReference("treasury-leg", reference + ":" + index++), time,
                    leg.actor()==null?new UUID(0,0):leg.actor(),leg.payer(),leg.recipient(),leg.amount(),leg.label()),
                    de.kuro.kuronomics.economy.bank.BankActivity.today(),false,bankHistoryLimit);
        }
        if (!recorded.equals(delta)) throw new IllegalStateException("Treasury history does not match the balance change");
        return result;
    }
    /**
     * Commit finances, company/property changes, pending asset work and retry records in the same saved
     * snapshot.
     */
    public boolean commitOwners(UUID reference, String fingerprint, Map<EconomicOwner, Account> updates,
                                Collection<Receipt> received, CompanyBook next,
                                Map<String, de.kuro.kuronomics.estate.Estate> properties) {
        return commitOwners(reference, fingerprint, updates, received, next, properties, null);
    }
    private boolean commitOwners(UUID reference, String fingerprint, Map<EconomicOwner, Account> updates,
                                Collection<Receipt> received, CompanyBook next,
                                Map<String, de.kuro.kuronomics.estate.Estate> properties, List<de.kuro.kuronomics.economy.bank.TreasuryMovement> movements) {
        if (transactionRecorded(reference, fingerprint)) return false;
        properties.forEach((id, e) -> { if (!id.equals(e.key())) throw new IllegalArgumentException("Property ID mismatch"); });
        applyOwners(updates, List.of(), received, Set.of(), next, reference, fingerprint, movements);
        properties.values().forEach(this::estate); recordTransaction(reference, fingerprint); setDirty(); return true;
    }
    public boolean reserve(Reservation reservation) {
        checkMutation();
        retiredHolds.requireUnretired(reservation.reference());
        Reservation existing = reservations.get(reservation.reference());
        if (existing != null) {
            if (!existing.economicOwner().equals(reservation.economicOwner()) || existing.amount() != reservation.amount())
                throw new IllegalArgumentException("Reservation reference was already used for another hold");
            return false;
        }
        putReservation(reservation.reference(), reservation); setDirty(); return true;
    }
    /** Record the asset operation and its hold together, with the same retry protection and checkpoint. */
    public boolean prepareOwned(UUID reference, String fingerprint, Reservation hold, CompanyBook next) {
        if (transactionRecorded(reference, fingerprint)) return false;
        if (hold != null) {
            retiredHolds.requireUnretired(hold.reference());
            if (reservations.containsKey(hold.reference()) || !hold.active()
                    || spendingCapacity(hold.economicOwner()) < hold.amount())
                throw new IllegalArgumentException("Cannot prepare the property hold");
            // Check the total before adding the asset operation; company credit can exceed a long's remaining
            // range.
            Math.addExact(reserved(hold.economicOwner()), hold.amount());
        }
        applyOwners(Map.of(), List.of(), List.of(), Set.of(), next, reference, fingerprint, null);
        if (hold != null) putReservation(hold.reference(), hold);
        recordTransaction(reference, fingerprint); setDirty(); return true;
    }
    public boolean release(UUID reference, UUID owner) {
        return release(reference, EconomicOwner.player(owner));
    }
    public boolean release(UUID reference, EconomicOwner owner) {
        checkMutation();
        Reservation existing = reservations.get(reference);
        if (existing == null) throw new IllegalArgumentException("Unknown reservation");
        if (!existing.economicOwner().equals(owner)) throw new IllegalArgumentException("Reservation belongs to another account");
        if (!existing.active()) return false;
        putReservation(reference, existing.released()); setDirty(); return true;
    }
    /** Release the hold and record its audit/retry details in the same snapshot. */
    public boolean releaseOwned(UUID reference, String fingerprint, UUID holdId, EconomicOwner owner, CompanyBook next) {
        if (transactionRecorded(reference, fingerprint)) return false;
        Reservation hold = reservation(holdId);
        if (hold == null || !hold.economicOwner().equals(owner)) throw new IllegalArgumentException("Unknown hold");
        if (!hold.active()) throw new IllegalArgumentException("Hold is no longer active");
        applyOwners(Map.of(), List.of(), List.of(), Set.of(), next, reference, fingerprint, null);
        putReservation(holdId, hold.released()); recordTransaction(reference, fingerprint); setDirty(); return true;
    }
    /** Replace the active hold and record the retry key in one commit. */
    public boolean transitionReservation(UUID transaction, String fingerprint,
                                         UUID previousReference, UUID previousOwner,
                                         UUID nextReference, UUID nextOwner, long nextAmount) {
        Objects.requireNonNull(fingerprint); Objects.requireNonNull(nextReference); Objects.requireNonNull(nextOwner);
        if (nextAmount <= 0) throw new IllegalArgumentException("Reservation amount must be positive");
        if (transactionRecorded(transaction, fingerprint)) return false;
        retiredHolds.requireUnretired(nextReference);
        Reservation previous = previousReference == null ? null : reservations.get(previousReference);
        if (previousReference != null) {
            if (previous == null || !previous.active()) throw new IllegalArgumentException("Previous auction reservation is no longer active");
            if (!previous.owner().equals(previousOwner)) throw new IllegalArgumentException("Previous auction reservation belongs to another account");
        }
        Reservation next = reservations.get(nextReference);
        if (next != null && !next.owner().equals(nextOwner))
            throw new IllegalArgumentException("Auction reservation reference belongs to another account");
        long capacity = Math.subtractExact(account(nextOwner).balance(), reserved(nextOwner));
        if (previous != null && previous.owner().equals(nextOwner)) capacity = Math.addExact(capacity, previous.amount());
        if (next != null && next.active() && (previous == null || !next.reference().equals(previous.reference())))
            capacity = Math.addExact(capacity, next.amount());
        if (capacity < nextAmount) throw new IllegalArgumentException("Your current available balance is insufficient.");
        if (previous != null && !previous.reference().equals(nextReference))
            putReservation(previous.reference(), previous.released());
        putReservation(nextReference, new Reservation(nextReference, nextOwner, nextAmount, true));
        recordTransaction(transaction, fingerprint); setDirty(); return true;
    }
    public boolean addPaydayCharge(PaydayCharge charge) {
        retiredCharges.requireUnretired(charge.reference());
        PaydayCharge old = paydayCharges.get(charge.reference());
        if (old != null) {
            if (!old.payer().equals(charge.payer()) || old.amount() != charge.amount()
                    || !old.reservationReference().equals(charge.reservationReference()) || !old.description().equals(charge.description()))
                throw new IllegalArgumentException("PayDay charge reference was already used");
            return false;
        }
        putCharge(charge.reference(), charge); setDirty(); return true;
    }

    public boolean cancelPaydayCharge(UUID chargeReference, UUID payer, UUID reservationReference,
                                      UUID transaction, String fingerprint) {
        if (transactionRecorded(transaction, fingerprint)) return false;
        PaydayCharge charge = paydayCharges.get(chargeReference);
        if (charge == null || !charge.payer().equals(payer) || !charge.reservationReference().equals(reservationReference))
            throw new IllegalArgumentException("Unknown auction PayDay charge");
        if (charge.status() == PaydayCharge.Status.PAID) throw new IllegalStateException("Auction payment has already completed");
        Reservation hold = reservations.get(reservationReference);
        if (hold != null && hold.active()) {
            if (!hold.owner().equals(payer)) throw new IllegalStateException("Auction reservation belongs to another account");
            putReservation(reservationReference, hold.released());
        }
        putCharge(chargeReference, charge.cancelled()); recordTransaction(transaction, fingerprint); setDirty(); return true;
    }

    public boolean commitPayday(UUID reference, String fingerprint, Map<UUID, Account> updates,
                                Collection<RecurringPayment> removed, Collection<Receipt> received, Set<UUID> settled,
                                Collection<PaydayCharge> paidCharges) {
        return commitPaydayOwners(reference, fingerprint, personalUpdates(updates), removed, received, settled, paidCharges, companies);
    }
    public boolean commitPaydayOwners(UUID reference, String fingerprint, Map<EconomicOwner, Account> updates,
                                Collection<RecurringPayment> removed, Collection<Receipt> received, Set<UUID> settled,
                                Collection<PaydayCharge> paidCharges, CompanyBook next) {
        return commitPaydayOwners(reference,fingerprint,updates,removed,received,settled,paidCharges,next,null);
    }
    private boolean commitPaydayOwners(UUID reference, String fingerprint, Map<EconomicOwner, Account> updates,
                                Collection<RecurringPayment> removed, Collection<Receipt> received, Set<UUID> settled,
                                Collection<PaydayCharge> paidCharges, CompanyBook next, List<de.kuro.kuronomics.economy.bank.TreasuryMovement> movements) {
        if (transactionRecorded(reference, fingerprint)) return false;
        for (PaydayCharge paid : paidCharges) {
            PaydayCharge current = paydayCharges.get(paid.reference());
            if (current == null || current.status() != PaydayCharge.Status.ACTIVE || !current.equals(new PaydayCharge(
                    paid.reference(), paid.payer(), paid.amount(), paid.description(), paid.reservationReference(), PaydayCharge.Status.ACTIVE, null)))
                throw new IllegalStateException("Auction PayDay charge changed during settlement");
            Reservation hold = reservations.get(paid.reservationReference());
            if (hold == null || !hold.active() || !hold.owner().equals(paid.payer()) || hold.amount() != paid.amount())
                throw new IllegalStateException("Auction PayDay reservation changed during settlement");
        }
        applyOwners(updates, removed, received, settled, next, reference, fingerprint, movements);
        for (PaydayCharge paid : paidCharges) {
            putCharge(paid.reference(), paid);
            Reservation hold = reservations.get(paid.reservationReference()); putReservation(hold.reference(), hold.released());
        }
        recordTransaction(reference, fingerprint); setDirty(); return true;
    }
    /** Capture part of the hold and reduce the remaining hold in the same commit. */
    public boolean captureOwned(UUID reference, String fingerprint, UUID holdId, EconomicOwner owner, long amount,
                                 Map<EconomicOwner, Account> updates, Collection<Receipt> received, CompanyBook next) {
        return captureOwned(reference, fingerprint, holdId, owner, amount, updates, received, next, Map.of());
    }
    public boolean captureOwned(UUID reference, String fingerprint, UUID holdId, EconomicOwner owner, long amount,
                                 Map<EconomicOwner, Account> updates, Collection<Receipt> received, CompanyBook next,
                                 Map<String, de.kuro.kuronomics.estate.Estate> properties) {
        if (transactionRecorded(reference, fingerprint)) return false;
        Reservation hold = reservation(holdId);
        if (hold == null || !hold.active() || !hold.economicOwner().equals(owner) || amount <= 0 || amount > hold.amount())
            throw new IllegalArgumentException("Invalid reservation capture");
        long remaining = Math.subtractExact(hold.amount(), amount);
        properties.forEach((id, e) -> { if (!id.equals(e.key())) throw new IllegalArgumentException("Property ID mismatch"); });
        applyOwners(updates, List.of(), received, Set.of(), next, reference, fingerprint, null);
        putReservation(holdId, remaining == 0 ? hold.released() : new Reservation(holdId, owner, remaining, true));
        properties.values().forEach(this::estate);
        recordTransaction(reference, fingerprint); setDirty(); return true;
    }
    public boolean capture(UUID transaction, String fingerprint, UUID reservationReference, UUID owner,
                           Map<UUID, Account> updates) {
        if (transactionRecorded(transaction, fingerprint)) return false;
        Reservation reservation = reservations.get(reservationReference);
        if (reservation == null || !reservation.active()) throw new IllegalArgumentException("Reservation is no longer active");
        if (!reservation.owner().equals(owner)) throw new IllegalArgumentException("Reservation belongs to another account");
        accounts.putAll(updates); updates.keySet().forEach(this::changed);
        putReservation(reservationReference, reservation.released());
        recordTransaction(transaction, fingerprint); setDirty(); return true;
    }
    public List<RecurringPayment> contracts() { return contracts.values().stream().flatMap(Collection::stream).toList(); }
    public List<RecurringPayment> contracts(UUID payer) { return List.copyOf(contracts.getOrDefault(payer, List.of())); }
    public void addContract(RecurringPayment payment) {
        checkMutation();
        var outgoing = contracts.computeIfAbsent(payment.payer(), id -> new ArrayList<>());
        if (outgoing.stream().anyMatch(c -> Objects.equals(c.receiver(), payment.receiver()) && c.name().equalsIgnoreCase(payment.name())))
            throw new IllegalArgumentException("A contract with that name already exists with this player");
        outgoing.add(payment); setDirty();
    }
    public boolean removeContract(RecurringPayment payment) {
        checkMutation();
        var outgoing = contracts.get(payment.payer()); if (outgoing == null) return false;
        boolean removed = outgoing.remove(payment);
        if (outgoing.isEmpty()) contracts.remove(payment.payer());
        if (removed) setDirty(); return removed;
    }
    public BigDecimal fee() { return fee; }
    public void fee(BigDecimal value) {
        if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(100)) > 0) throw new IllegalArgumentException("Fee must be between 0 and 100 percent");
        fee = value; setDirty();
    }
    public boolean paydayEnabled() { return paydayEnabled; }
    public void paydayEnabled(boolean value) {
        if (!paydayEnabled && value) observedTicks.keySet().forEach(id -> accounts.putIfAbsent(id, Account.empty()));
        if (paydayEnabled && !value) {
            for (var entry : observedTicks.entrySet()) {
                long elapsed = sampledElapsed(entry.getValue());
                if (elapsed == 0) continue;
                UUID id = entry.getKey(); Account stored = accounts.getOrDefault(id, Account.empty());
                accounts.put(id, stored.ticks(Math.addExact(stored.playTicks(), elapsed)));
                entry.setValue(sampledTick);
            }
        }
        paydayEnabled = value; setDirty();
    }
    public boolean imported() { return imported; }
    public void imported(boolean value) { imported = value; setDirty(); }
    public long checkValue(UUID id) { return checks.getOrDefault(id, 0L); }
    public boolean issueCheck(UUID claim, UUID player, long value, Account result, String fingerprint) {
        if (transactionRecorded(claim, fingerprint)) return false;
        de.kuro.kuronomics.economy.Money.positive(value);
        if (checks.containsKey(claim)) throw new IllegalArgumentException("Check reference already exists");
        checks.put(claim, value); accounts.put(player, result); changed(player);
        recordTransaction(claim, fingerprint); setDirty(); return true;
    }
    public boolean issueCheck(UUID claim, long value, String fingerprint) {
        if (transactionRecorded(claim, fingerprint)) return false;
        de.kuro.kuronomics.economy.Money.positive(value);
        if (checks.containsKey(claim)) throw new IllegalArgumentException("Check reference already exists");
        checks.put(claim, value); recordTransaction(claim, fingerprint); setDirty(); return true;
    }
    public boolean redeemCheck(UUID claim, UUID redemption, UUID player, Account result, String fingerprint) {
        if (transactionRecorded(redemption, fingerprint)) return false;
        if (checkValue(claim) <= 0) throw new IllegalArgumentException("This check has already been redeemed or is invalid.");
        checks.remove(claim); accounts.put(player, result); changed(player);
        recordTransaction(redemption, fingerprint); setDirty(); return true;
    }

    @Override public void save(java.io.File file, HolderLookup.Provider registries) {
        if (!isDirty()) return;
        // Copy the unchanged domain records on the server thread, then build and encode NBT off-thread.
        // The worker only reads this detached ledger, never live players, registries or maps.
        EconomyLedger frozen = new EconomyLedger();
        frozen.accounts.putAll(accounts);
        observedTicks.keySet().forEach(id -> { if (accounts.containsKey(id)) frozen.accounts.put(id, account(id)); });
        contracts.forEach((id, values) -> frozen.contracts.put(id, List.copyOf(values)));
        frozen.checks.putAll(checks); frozen.reservations.putAll(reservations); frozen.transactions.putAll(transactions);
        frozen.paydayCharges.putAll(paydayCharges);
        frozen.receipts.putAll(receipts);
        marketplaceReceipts.forEach((id, values) -> frozen.marketplaceReceipts.put(id, List.copyOf(values)));
        frozen.nonTaxablePending.putAll(nonTaxablePending); frozen.estates.putAll(estates); frozen.evictions.addAll(evictions);
        frozen.ownedAccounts.putAll(ownedAccounts); frozen.companies = companies; frozen.market = market; frozen.bankActivity = bankActivity; frozen.bankLoans=bankLoans;frozen.treasuryDay=treasuryDay;frozen.treasuryOpening=treasuryOpening;
        frozen.fee = fee; frozen.paydayEnabled = paydayEnabled; frozen.imported = imported;
        transactionHistory.copyForSave(frozen.transactionHistory); retiredHolds.copyForSave(frozen.retiredHolds); retiredCharges.copyForSave(frozen.retiredCharges);
        de.kuro.kuronomics.economy.FinancialWriter.writeUpdate(file.toPath(), () -> snapshotEncoder.encode(frozen, registries));
        if (events != null) events.saved(file.toPath().toAbsolutePath().getParent());
        setDirty(false);
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag saved = save(tag, registries, null, null, null, null, null); saved.put("market", market.save(registries)); return saved;
    }
    private CompoundTag save(CompoundTag tag, HolderLookup.Provider registries, EconomyLedger previous, CompoundTag priorTag,
                             CompanyBook.SnapshotEncoder companyEncoder, Map<UUID, CompoundTag> accountTags,de.kuro.kuronomics.economy.bank.BankLoans.Encoder loanEncoder) {
        tag.putInt("version", 11);
        tag.putLong("treasuryDay",treasuryDay);tag.putLong("treasuryOpening",treasuryOpening);
        tag.put("bankLoans",loanEncoder==null?bankLoans.save():loanEncoder.encode(bankLoans));
        tag.put("bankActivity", previous != null && previous.bankActivity == bankActivity ? priorTag.get("bankActivity") : (previous==null?bankActivity.save():new CompoundTag())); transactionHistory.saveShared(tag, "transactionHistory", previous == null ? null : previous.transactionHistory, previous == null ? null : priorTag.getCompound("transactionHistory"));
        tag.put("companies", companyEncoder == null ? companies.save() : companyEncoder.encode(companies)); ListTag ownerTags = new ListTag();
        if (previous != null && ownedAccounts.equals(previous.ownedAccounts)) tag.put("ownedAccounts", priorTag.get("ownedAccounts")); else {
        ownedAccounts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            CompoundTag n = new CompoundTag(); n.put("owner", e.getKey().save()); n.putLong("balance", e.getValue().balance());
            n.putLong("pending", e.getValue().pendingIncome()); n.putBoolean("suspended", e.getValue().suspended()); n.putString("reason", e.getValue().suspensionReason()); ownerTags.add(n);
        }); tag.put("ownedAccounts", ownerTags);
        }
        retiredHolds.saveShared(tag, "retiredHolds", previous == null ? null : previous.retiredHolds, previous == null ? null : priorTag.getCompound("retiredHolds"));
        retiredCharges.saveShared(tag, "retiredCharges", previous == null ? null : previous.retiredCharges, previous == null ? null : priorTag.getCompound("retiredCharges")); tag.putString("fee", fee.toPlainString());
        tag.putBoolean("paydayEnabled", paydayEnabled); tag.putBoolean("imported", imported);
        ListTag list = new ListTag();
        boolean sameAccounts = previous != null && accounts.equals(previous.accounts);
        if (!sameAccounts) {
        if (accountTags != null) accountTags.keySet().retainAll(accounts.keySet());
        accounts.forEach((id, stored) -> {
            Account a = account(id);
            if (accountTags != null && previous != null && a.equals(previous.accounts.get(id))) { list.add(accountTags.get(id)); return; }
            CompoundTag n = new CompoundTag(); n.putUUID("id", id);
            n.putLong("balance", a.balance()); n.putLong("pending", a.pendingIncome());
            n.putLong("ticks", a.playTicks()); n.putLong("sequence", a.paydaySequence());
            n.putLong("jobEarnings", a.jobEarnings());
            CompoundTag counters = new CompoundTag(); a.taskEarnings().forEach(counters::putLong); n.put("taskEarnings", counters);
            n.putString("job", a.job()); n.putString("entry", a.entry());
            n.putBoolean("suspended", a.suspended()); n.putString("reason", a.suspensionReason()); list.add(n); if (accountTags != null) accountTags.put(id, n);
        });
        } tag.put("accounts", sameAccounts ? priorTag.get("accounts") : list);
        if (previous != null && contracts.equals(previous.contracts)) tag.put("contracts", priorTag.get("contracts")); else {
        ListTag payments = new ListTag();
        for (RecurringPayment c : contracts()) {
            CompoundTag n = new CompoundTag(); n.putUUID("payer", c.payer());
            if (c.receiver() != null) n.putUUID("receiver", c.receiver());
            n.putLong("amount", c.amount()); n.putString("name", c.name()); payments.add(n);
        } tag.put("contracts", payments);
        }
        if (previous != null && checks.equals(previous.checks)) tag.put("checks", priorTag.get("checks")); else {
        ListTag claims = new ListTag();
        checks.forEach((id, value) -> { CompoundTag n = new CompoundTag(); n.putUUID("id", id); n.putLong("value", value); claims.add(n); });
        tag.put("checks", claims);
        }
        if (previous != null && reservations.equals(previous.reservations)) tag.put("reservations", priorTag.get("reservations")); else {
        ListTag holds = new ListTag();
        reservations.values().forEach(reservation -> {
            CompoundTag n = new CompoundTag(); n.putUUID("reference", reservation.reference()); n.put("economicOwner", reservation.economicOwner().save());
            n.putLong("amount", reservation.amount()); n.putBoolean("active", reservation.active()); holds.add(n);
        }); tag.put("reservations", holds);
        }
        if (previous != null && transactions.equals(previous.transactions)) tag.put("transactions", priorTag.get("transactions")); else {
        ListTag processed = new ListTag();
        transactions.forEach((reference, fingerprint) -> {
            CompoundTag n = new CompoundTag(); n.putUUID("reference", reference); n.putString("fingerprint", fingerprint); processed.add(n);
        }); tag.put("transactions", processed);
        }
        if (previous != null && paydayCharges.equals(previous.paydayCharges)) tag.put("paydayCharges", priorTag.get("paydayCharges")); else {
        ListTag chargeTags = new ListTag();
        paydayCharges.values().forEach(charge -> {
            CompoundTag n = new CompoundTag(); n.putUUID("reference", charge.reference()); n.putUUID("payer", charge.payer());
            n.putLong("amount", charge.amount()); n.putString("description", charge.description()); n.putUUID("reservation", charge.reservationReference());
            n.putString("status", charge.status().name()); if (charge.paidByPayday() != null) n.putUUID("paidByPayday", charge.paidByPayday()); chargeTags.add(n);
        }); tag.put("paydayCharges", chargeTags);
        }
        if (previous != null && receipts.equals(previous.receipts)) tag.put("receipts", priorTag.get("receipts")); else {
        ListTag receiptTags = new ListTag();
        receipts.forEach((id, grouped) -> grouped.forEach((description, amount) -> {
            CompoundTag n = new CompoundTag(); n.putUUID("recipient", id); n.putString("description", description); n.putLong("amount", amount); receiptTags.add(n);
        })); tag.put("receipts", receiptTags);
        }
        if (previous != null && marketplaceReceipts.equals(previous.marketplaceReceipts)) tag.put("marketplaceReceipts", priorTag.get("marketplaceReceipts")); else {
        ListTag marketplaceReceiptTags = new ListTag(); marketplaceReceipts.values().forEach(values -> values.forEach(receipt -> {
            CompoundTag n = new CompoundTag(); n.putUUID("recipient", receipt.recipient()); n.putString("description", receipt.description());
            n.putLong("amount", receipt.amount()); n.putBoolean("taxable", receipt.taxable()); n.putString("sourceType", receipt.sourceType().name());
            n.putLong("gross", receipt.grossAmount()); n.putLong("marketplaceTax", receipt.marketplaceTax()); n.putLong("net", receipt.netAmount());
            marketplaceReceiptTags.add(n);
        })); tag.put("marketplaceReceipts", marketplaceReceiptTags);
        }
        if (previous != null && nonTaxablePending.equals(previous.nonTaxablePending)) tag.put("nonTaxablePending", priorTag.get("nonTaxablePending")); else {
        ListTag nonTaxableTags = new ListTag(); nonTaxablePending.forEach((id, amount) -> {
            CompoundTag n = new CompoundTag(); n.putUUID("recipient", id); n.putLong("amount", amount); nonTaxableTags.add(n);
        }); tag.put("nonTaxablePending", nonTaxableTags);
        }
        if (previous != null && estates.equals(previous.estates)) tag.put("estates", priorTag.get("estates")); else {
        ListTag properties = new ListTag();
        for (var e : estates.values()) {
            CompoundTag n = new CompoundTag(); n.putString("id", e.key()); n.putString("dimension", e.dimension()); n.putString("region", e.region());
            n.putLong("price", e.price()); n.putLong("rent", e.rent()); n.putBoolean("locked", e.locked()); n.putBoolean("buildingAllowed", e.buildingAllowed()); n.putString("regionBinding", e.regionBinding());
            ListTag guests = new ListTag(); e.guests().forEach(id -> guests.add(NbtUtils.createUUID(id))); n.put("guests", guests);
            n.put("economicOwner", e.economicOwner().save()); if (e.tenant() != null) n.putUUID("tenant", e.tenant()); properties.add(n);
        }
        tag.put("estates", properties);
        }
        ListTag evictionTags = new ListTag(); evictions.forEach(key -> evictionTags.add(StringTag.valueOf(key))); tag.put("evictions", evictionTags); return tag;
    }
    public static EconomyLedger load(CompoundTag tag, HolderLookup.Provider registries) {
        int version = tag.getInt("version");
        if (version < 1 || version > 11) throw new IllegalStateException("Unsupported economy data version");
        for (String list : List.of("accounts", "contracts", "checks", "estates", "evictions"))
            if (!tag.contains(list, Tag.TAG_LIST)) throw new IllegalStateException("Missing economy data: " + list);
        if (version >= 2) for (String list : List.of("reservations", "transactions"))
            if (!tag.contains(list, Tag.TAG_LIST)) throw new IllegalStateException("Missing economy data: " + list);
        EconomyLedger ledger = new EconomyLedger(); ledger.fee(new BigDecimal(tag.getString("fee")));
        if(version>=11){
            if(!tag.contains("bankLoans",Tag.TAG_COMPOUND)||!tag.contains("treasuryDay",Tag.TAG_LONG)||!tag.contains("treasuryOpening",Tag.TAG_LONG))throw new IllegalStateException("Missing bank loan or daily summary data");
            ledger.bankLoans=de.kuro.kuronomics.economy.bank.BankLoans.load(tag.getCompound("bankLoans"));ledger.treasuryDay=tag.getLong("treasuryDay");ledger.treasuryOpening=tag.getLong("treasuryOpening");
        }
        if (version >= 10) {
            if (!tag.contains("bankActivity", Tag.TAG_COMPOUND)) throw new IllegalStateException("Missing bank activity data");
            ledger.bankActivity = de.kuro.kuronomics.economy.bank.BankActivity.load(tag.getCompound("bankActivity"));
        }
        if (version >= 9) {
            if (!tag.contains("market", Tag.TAG_COMPOUND)) throw new IllegalStateException("Missing marketplace data");
            ledger.market = de.kuro.kuronomics.market.MarketBook.load(tag.getCompound("market"), registries);
        }
        if (version >= 7) {
            if (!tag.contains("companies", Tag.TAG_COMPOUND) || !tag.contains("ownedAccounts", Tag.TAG_LIST)) throw new IllegalStateException("Missing owned economy data");
            ledger.companies = CompanyBook.load(tag.getCompound("companies"));
            ledger.legacyCompanyAccess = tag.getCompound("companies").getInt("version") == 1;
            for (Tag raw : tag.getList("ownedAccounts", Tag.TAG_COMPOUND)) {
                CompoundTag n = (CompoundTag) raw; EconomicOwner owner = EconomicOwner.load(n.getCompound("owner"));
                if (owner.isPlayer() || !n.contains("balance", Tag.TAG_LONG) || !n.contains("pending", Tag.TAG_LONG)) throw new IllegalStateException("Invalid owned account");
                Account a = Account.empty().balance(n.getLong("balance")).pending(n.getLong("pending")).suspension(n.getBoolean("suspended"), n.getString("reason"));
                if (ledger.ownedAccounts.putIfAbsent(owner, a) != null) throw new IllegalStateException("Duplicate economic owner");
            }
            ledger.validateOwners(ledger.ownedAccounts, ledger.companies);
            for (UUID id : ledger.companies.companies().keySet()) if (!ledger.ownedAccounts.containsKey(EconomicOwner.company(id))) throw new IllegalStateException("Missing Company account");
        }
        ledger.paydayEnabled = !tag.contains("paydayEnabled") || tag.getBoolean("paydayEnabled"); ledger.imported = tag.getBoolean("imported");
        for (Tag raw : tag.getList("accounts", Tag.TAG_COMPOUND)) {
            CompoundTag n = (CompoundTag) raw;
            for (String field : List.of("balance", "pending", "ticks", "sequence"))
                if (!n.contains(field, Tag.TAG_LONG)) throw new IllegalStateException("Missing account field: " + field);
            if (ledger.accounts.containsKey(n.getUUID("id"))) throw new IllegalStateException("Duplicate account UUID");
            if (n.getLong("pending") < 0 || n.getLong("ticks") < 0 || n.getLong("sequence") < 0) throw new IllegalStateException("Invalid economy account data");
            ledger.accounts.put(n.getUUID("id"), new Account(n.getLong("balance"), n.getLong("pending"), n.getLong("ticks"), n.getLong("sequence"), de.kuro.kuronomics.jobs.JobIds.legacy(n.getString("job")), n.getString("entry"), n.getBoolean("suspended"), n.getString("reason"), n.getLong("jobEarnings"), readTaskEarnings(n.getCompound("taskEarnings"))));
        }
        for (Tag raw : tag.getList("contracts", Tag.TAG_COMPOUND)) {
            CompoundTag n = (CompoundTag) raw;
            ledger.addContract(new RecurringPayment(n.getUUID("payer"), n.hasUUID("receiver") ? n.getUUID("receiver") : null, n.getLong("amount"), n.getString("name")));
        }
        for (Tag raw : tag.getList("checks", Tag.TAG_COMPOUND)) {
            CompoundTag n = (CompoundTag) raw; ledger.checks.put(n.getUUID("id"), de.kuro.kuronomics.economy.Money.positive(n.getLong("value")));
        }
        if (version >= 2) {
            for (Tag raw : tag.getList("reservations", Tag.TAG_COMPOUND)) {
                CompoundTag n = (CompoundTag) raw;
                Reservation reservation = new Reservation(n.getUUID("reference"), version >= 7 ? EconomicOwner.load(n.getCompound("economicOwner")) : EconomicOwner.player(n.getUUID("owner")), n.getLong("amount"), n.getBoolean("active"));
                if (ledger.reservations.putIfAbsent(reservation.reference(), reservation) != null) throw new IllegalStateException("Duplicate reservation UUID");
            }
            for (Tag raw : tag.getList("transactions", Tag.TAG_COMPOUND)) {
                CompoundTag n = (CompoundTag) raw;
                if (ledger.transactions.putIfAbsent(n.getUUID("reference"), n.getString("fingerprint")) != null)
                    throw new IllegalStateException("Duplicate transaction UUID");
            }
        }
        if (version >= 3) {
            if (!tag.contains("paydayCharges", Tag.TAG_LIST)) throw new IllegalStateException("Missing economy data: paydayCharges");
            for (Tag raw : tag.getList("paydayCharges", Tag.TAG_COMPOUND)) {
                CompoundTag n = (CompoundTag) raw;
                try {
                    PaydayCharge charge = new PaydayCharge(n.getUUID("reference"), n.getUUID("payer"), n.getLong("amount"), n.getString("description"),
                            n.getUUID("reservation"), PaydayCharge.Status.valueOf(n.getString("status")), n.hasUUID("paidByPayday") ? n.getUUID("paidByPayday") : null);
                    if (ledger.paydayCharges.putIfAbsent(charge.reference(), charge) != null) throw new IllegalStateException("Duplicate PayDay charge UUID");
                } catch (RuntimeException error) { throw new IllegalStateException("Invalid PayDay charge", error); }
            }
        }
        List<Receipt> pending = new ArrayList<>();
        for (Tag raw : tag.getList("receipts", Tag.TAG_COMPOUND)) {
            CompoundTag n = (CompoundTag) raw; pending.add(new Receipt(n.getUUID("recipient"), n.getString("description"), n.getLong("amount")));
        }
        ledger.commit(Map.of(), List.of(), pending, Set.of());
        if (version >= 5) {
            if (!tag.contains("marketplaceReceipts", Tag.TAG_LIST)) throw new IllegalStateException("Missing economy data: marketplaceReceipts");
            List<Receipt> marketplace = new ArrayList<>();
            for (Tag raw : tag.getList("marketplaceReceipts", Tag.TAG_COMPOUND)) {
                CompoundTag n = (CompoundTag) raw;
                try {
                    marketplace.add(new Receipt(n.getUUID("recipient"), n.getString("description"), n.getLong("amount"), n.getBoolean("taxable"),
                            de.kuro.kuronomics.economy.tax.MarketplaceTaxService.SourceType.valueOf(n.getString("sourceType")),
                            n.getLong("gross"), n.getLong("marketplaceTax"), n.getLong("net")));
                } catch (RuntimeException error) { throw new IllegalStateException("Invalid marketplace receipt", error); }
            }
            ledger.commit(Map.of(), List.of(), marketplace, Set.of());
        }
        if (version >= 4) {
            if (!tag.contains("nonTaxablePending", Tag.TAG_LIST)) throw new IllegalStateException("Missing economy data: nonTaxablePending");
            ledger.nonTaxablePending.clear();
            for (Tag raw : tag.getList("nonTaxablePending", Tag.TAG_COMPOUND)) {
                CompoundTag n = (CompoundTag) raw; UUID recipient = n.getUUID("recipient"); long amount = n.getLong("amount");
                long described = ledger.receipts(recipient).values().stream().reduce(0L, Math::addExact);
                for (Receipt receipt : ledger.marketplaceReceipts(recipient)) described = Math.addExact(described, receipt.amount());
                if (amount <= 0 || amount > described
                        || ledger.nonTaxablePending.putIfAbsent(recipient, amount) != null)
                    throw new IllegalStateException("Invalid non-taxable pending income");
            }
        }
        for (Tag raw : tag.getList("estates", Tag.TAG_COMPOUND)) {
            CompoundTag n = (CompoundTag) raw;
            String dimension = n.getString("dimension"), region = n.getString("region");
            String id = n.contains("id", Tag.TAG_STRING) && !n.getString("id").isBlank() ? n.getString("id") : dimension + "|" + region;
            var estate = new de.kuro.kuronomics.estate.Estate(id, dimension, region, n.getLong("price"), n.getLong("rent"), version >= 7 ? EconomicOwner.load(n.getCompound("economicOwner")) : EconomicOwner.personalOrState(n.hasUUID("owner") ? n.getUUID("owner") : null), n.hasUUID("tenant") ? n.getUUID("tenant") : null, n.getBoolean("locked"), n.getString("regionBinding"), !n.contains("buildingAllowed") || n.getBoolean("buildingAllowed"), Set.of());
            for (Tag guest : n.getList("guests", Tag.TAG_INT_ARRAY)) estate = estate.guest(NbtUtils.loadUUID(guest), true);
            ledger.estates.put(estate.key(), estate);
        }
        ledger.indexTenants();
        ledger.estates.values().forEach(e -> ledger.ownerEstates.computeIfAbsent(e.economicOwner(), ignored -> new HashMap<>()).put(e.key(), e));
        for (var estate : ledger.estates.values()) if (estate.economicOwner().isCompany()) {
            var access = ledger.companies.access().get(estate.key());
            if (ledger.companies.company(estate.economicOwner().id()) == null || access == null || !access.company().equals(estate.economicOwner().id()))
                throw new IllegalStateException("Company property has missing ownership/access evidence");
        }
        for (var operation : ledger.companies.operations().values()) if (!ledger.estates.containsKey(operation.property()))
            throw new IllegalStateException("Company operation references a missing property");
        for (Reservation value : ledger.reservations.values()) if (value.active()) {
            ledger.reservedTotals.merge(value.economicOwner(), value.amount(), Math::addExact);
            ledger.activeReservations.computeIfAbsent(value.economicOwner(), ignored -> new HashMap<>()).put(value.reference(), value);
        }
        for (PaydayCharge value : ledger.paydayCharges.values()) if (value.status() == PaydayCharge.Status.ACTIVE)
            ledger.activeCharges.computeIfAbsent(value.payer(), id -> new LinkedHashMap<>()).put(value.reference(), value);
        for (Tag raw : tag.getList("evictions", Tag.TAG_STRING)) ledger.evictions.add(raw.getAsString());
        ledger.transactionHistory.load(tag, "transactionHistory", ledger.transactions.keySet(), version >= 6);
        ledger.retiredHolds.load(tag, "retiredHolds", List.of(), version >= 6);
        ledger.retiredCharges.load(tag, "retiredCharges", List.of(), version >= 6);
        for (UUID id : ledger.reservations.keySet()) if (ledger.retiredHolds.contains(id)) throw new IllegalStateException("Retired hold still exists");
        for (UUID id : ledger.paydayCharges.keySet()) if (ledger.retiredCharges.contains(id)) throw new IllegalStateException("Retired charge still exists");
        for(var owner:ledger.bankLoans.borrowers().keySet()) {
            if(owner.isPlayer() ? !ledger.accounts.containsKey(owner.id()) : ledger.companies.company(owner.id())==null || ledger.companies.company(owner.id()).status()!=de.kuro.kuronomics.company.Company.Status.ACTIVE)
                throw new IllegalStateException("Bank loan has no active borrower account");
        }
        ledger.market.validateIntents(ledger::hasTransaction);
        ledger.setDirty(tag.getInt("version") < 11); return ledger;
    }
}
