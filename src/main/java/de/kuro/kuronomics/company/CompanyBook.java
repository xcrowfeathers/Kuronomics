package de.kuro.kuronomics.company;

import de.kuro.kuronomics.economy.EconomicOwner;
import java.util.*;
import net.minecraft.nbt.*;

/** The company snapshot is part of EconomyLedger, not a second money store. */
public final class CompanyBook {
    private final Map<UUID, Company> companies;
    private final Map<UUID, CompanyContract> contracts;
    private final Map<UUID, Map<String, CompanyCredit>> buffers;
    private final Map<String, CompanyAccess> access;
    private final Map<String, CompanyAssetOperation> operations;
    private final List<CompanyAudit> audit;
    private final long cycleDay;
    private final Map<String, UUID> names;
    private final Map<UUID, Set<UUID>> membership;
    private final Map<EconomicOwner, List<CompanyContract>> outgoing;
    private final Map<EconomicOwner, List<CompanyContract>> incoming;
    public static final CompanyBook EMPTY = new Editor().build();

    private CompanyBook(Editor edit) {
        companies = freeze(edit.companies); contracts = freeze(edit.contracts);
        Map<UUID, Map<String, CompanyCredit>> frozen = new HashMap<>();
        edit.buffers.forEach((id, values) -> frozen.put(id, Map.copyOf(values))); buffers = Map.copyOf(frozen);
        access = freeze(edit.access); operations = freeze(edit.operations);
        audit = List.copyOf(edit.audit); cycleDay = edit.cycleDay;
        if (cycleDay < -1 || companies.values().stream().anyMatch(c -> c.lastPayday() != null && c.lastPayday().cycleDay() > cycleDay))
            throw new IllegalArgumentException("Invalid Company cycle/statement evidence");
        Map<String, UUID> nameIndex = new HashMap<>();
        companies.values().forEach(c -> {
            if (nameIndex.putIfAbsent(Company.normalizedName(c.name()), c.id()) != null) throw new IllegalArgumentException("Duplicate Company name");
        });
        names = Map.copyOf(nameIndex);
        var memberIndex = new SnapshotMap<UUID, Set<UUID>>(edit.base == null ? Map.of() : edit.base.membership);
        for (UUID id : changedKeys(edit.companies)) {
            Company old = edit.base == null ? null : edit.base.company(id), current = companies.get(id);
            Set<UUID> before = old == null ? Set.of() : old.members().keySet(), after = current == null ? Set.of() : current.members().keySet();
            if (before.equals(after)) continue;
            for (UUID player : before) if (!after.contains(player)) {
                var memberships = new HashSet<>(memberIndex.get(player)); memberships.remove(id);
                if (memberships.isEmpty()) memberIndex.remove(player); else memberIndex.put(player, Set.copyOf(memberships));
            }
            for (UUID player : after) if (!before.contains(player)) {
                var memberships = new HashSet<>(memberIndex.getOrDefault(player, Set.of())); memberships.add(id); memberIndex.put(player, Set.copyOf(memberships));
            }
        }
        membership = memberIndex.freeze();
        var outgoingIndex = new SnapshotMap<EconomicOwner, List<CompanyContract>>(edit.base == null ? Map.of() : edit.base.outgoing);
        var incomingIndex = new SnapshotMap<EconomicOwner, List<CompanyContract>>(edit.base == null ? Map.of() : edit.base.incoming);
        Set<UUID> changedContracts = changedKeys(edit.contracts);
        Map<EconomicOwner, List<CompanyContract>> additionsOut = new HashMap<>(), additionsIn = new HashMap<>();
        Set<EconomicOwner> affectedOut = new HashSet<>(), affectedIn = new HashSet<>();
        if (!contracts.isEmpty()) for (UUID id : changedContracts) {
            CompanyContract old = edit.base == null ? null : edit.base.contracts.get(id), current = contracts.get(id);
            if (old != null) { affectedOut.add(old.payer()); affectedIn.add(old.recipient()); }
            if (current != null) {
                for (EconomicOwner owner : List.of(current.payer(), current.recipient())) if (owner.isCompany()
                        && (companies.get(owner.id()) == null || companies.get(owner.id()).status() != Company.Status.ACTIVE))
                    throw new IllegalArgumentException("Contract references an inactive Company");
                affectedOut.add(current.payer()); affectedIn.add(current.recipient());
                additionsOut.computeIfAbsent(current.payer(), ignored -> new ArrayList<>()).add(current);
                additionsIn.computeIfAbsent(current.recipient(), ignored -> new ArrayList<>()).add(current);
            }
        }
        updateContracts(outgoingIndex, affectedOut, additionsOut, changedContracts, true);
        updateContracts(incomingIndex, affectedIn, additionsIn, changedContracts, false);
        if (!contracts.isEmpty()) for (UUID id : changedKeys(edit.companies)) {
            Company c = companies.get(id); EconomicOwner owner = EconomicOwner.company(id);
            if ((c == null || c.status() != Company.Status.ACTIVE)
                    && (!outgoingIndex.getOrDefault(owner, List.of()).isEmpty() || !incomingIndex.getOrDefault(owner, List.of()).isEmpty()))
                throw new IllegalArgumentException("Contract references an inactive Company");
        }
        outgoing = contracts.isEmpty() ? Map.of() : outgoingIndex.freeze(); incoming = contracts.isEmpty() ? Map.of() : incomingIndex.freeze();
        buffers.keySet().forEach(id -> { if (!companies.containsKey(id)) throw new IllegalArgumentException("Unknown Company buffer"); });
        for (String property : changedKeys(edit.access)) {
            CompanyAccess a = access.get(property); if (a == null) continue;
            if (property.isBlank() || !companies.containsKey(a.company()) || companies.get(a.company()).status() == Company.Status.CLOSED)
                throw new IllegalArgumentException("Invalid Company property access");
        }
        for (String property : changedKeys(edit.operations)) {
            CompanyAssetOperation o = operations.get(property); if (o == null) continue;
            if (!property.equals(o.property()) || !companies.containsKey(o.company()) || companies.get(o.company()).status() == Company.Status.CLOSED
                    || !o.company().equals(o.previous().company()) || !o.company().equals(o.desired().company())) throw new IllegalArgumentException("Invalid Company asset operation");
        }
        for (UUID id : changedKeys(edit.companies)) {
            Company c = companies.get(id);
            if (c == null || c.status() == Company.Status.CLOSED) {
                if (access.values().stream().anyMatch(a -> a.company().equals(id))) throw new IllegalArgumentException("Invalid Company property access");
                if (operations.values().stream().anyMatch(o -> o.company().equals(id))) throw new IllegalArgumentException("Invalid Company asset operation");
            }
        }
    }
    public Map<UUID, Company> companies() { return companies; }
    public Map<UUID, CompanyContract> contracts() { return contracts; }
    public Map<String, CompanyAccess> access() { return access; }
    public Map<String, CompanyAssetOperation> operations() { return operations; }
    public List<CompanyAudit> audit() { return audit; }
    public long cycleDay() { return cycleDay; }
    public Company company(UUID id) { return companies.get(id); }
    public Company named(String name) { UUID id = names.get(Company.normalizedName(name)); return id == null ? null : companies.get(id); }
    public Set<UUID> memberships(UUID player) { return membership.getOrDefault(player, Set.of()); }
    public List<CompanyContract> outgoing(EconomicOwner owner) { return outgoing.getOrDefault(owner, List.of()); }
    public List<CompanyContract> incoming(EconomicOwner owner) { return incoming.getOrDefault(owner, List.of()); }
    public java.util.stream.Stream<CompanyContract> involving(EconomicOwner owner) { return java.util.stream.Stream.concat(outgoing(owner).stream(), incoming(owner).stream()); }
    public Map<String, CompanyCredit> buffer(UUID id) { return buffers.getOrDefault(id, Map.of()); }
    public Editor edit() { return new Editor(this); }
    private static boolean unchanged(Map<?, ?> map) { return map instanceof SnapshotMap<?, ?> snapshot && snapshot.unchanged(); }
    private static <K, V> Set<K> changedKeys(Map<K, V> map) { return map instanceof SnapshotMap<K, V> snapshot ? snapshot.changedKeys() : map.keySet(); }
    private static void updateContracts(SnapshotMap<EconomicOwner, List<CompanyContract>> index, Set<EconomicOwner> affected,
                                        Map<EconomicOwner, List<CompanyContract>> additions, Set<UUID> changed, boolean validateNames) {
        for (EconomicOwner owner : affected) {
            var values = new ArrayList<CompanyContract>();
            for (CompanyContract c : index.getOrDefault(owner, List.of())) if (!changed.contains(c.id())) values.add(c);
            values.addAll(additions.getOrDefault(owner, List.of()));
            if (validateNames) {
                Map<EconomicOwner, Set<String>> names = new HashMap<>();
                for (CompanyContract c : values) if (!names.computeIfAbsent(c.recipient(), ignored -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)).add(c.name()))
                    throw new IllegalArgumentException("That contract name is already used with this recipient.");
            }
            values.sort(Comparator.comparing(CompanyContract::id));
            if (values.isEmpty()) index.remove(owner); else index.put(owner, List.copyOf(values));
        }
    }
    private static <K, V> Map<K, V> freeze(Map<K, V> map) { return map instanceof SnapshotMap<K, V> snapshot ? snapshot.freeze() : Map.copyOf(map); }

    public static final class Editor {
        public final Map<UUID, Company> companies;
        public final Map<UUID, CompanyContract> contracts;
        public final Map<UUID, Map<String, CompanyCredit>> buffers;
        public final Map<String, CompanyAccess> access;
        public final Map<String, CompanyAssetOperation> operations;
        private final CompanyBook base;
        public final Deque<CompanyAudit> audit = new ArrayDeque<>();
        private final Deque<java.util.function.Supplier<CompanyAudit>> pendingAudit = new ArrayDeque<>();
        private final Set<UUID> mutableBuffers = new HashSet<>();
        public long cycleDay = -1;
        public Editor() { base = null; companies = new SnapshotMap<>(); contracts = new SnapshotMap<>(); buffers = new SnapshotMap<>(); access = new SnapshotMap<>(); operations = new SnapshotMap<>(); }
        private Editor(CompanyBook book) {
            base = book; companies = new SnapshotMap<>(book.companies); contracts = new SnapshotMap<>(book.contracts); buffers = new SnapshotMap<>(book.buffers);
            access = new SnapshotMap<>(book.access); operations = new SnapshotMap<>(book.operations); audit.addAll(book.audit); cycleDay = book.cycleDay;
        }
        public void credit(UUID company, CompanyCredit credit) {
            Map<String, CompanyCredit> values;
            if (mutableBuffers.add(company)) { values = new HashMap<>(buffers.getOrDefault(company, Map.of())); buffers.put(company, values); }
            else values = buffers.get(company);
            CompanyCredit previous = values.get(credit.key()); values.put(credit.key(), previous == null ? credit : previous.add(credit.amount()));
            buffers.put(company, values);
        }
        public void audit(CompanyAudit entry) { flushAudit(); appendAudit(entry); }
        public void auditDeferred(java.util.function.Supplier<CompanyAudit> entry) {
            if (pendingAudit.size() >= 10000) pendingAudit.removeFirst(); pendingAudit.addLast(Objects.requireNonNull(entry));
        }
        private void appendAudit(CompanyAudit entry) { if (audit.size() >= 10000) audit.removeFirst(); audit.addLast(entry); }
        private void flushAudit() { while (!pendingAudit.isEmpty()) appendAudit(pendingAudit.removeFirst().get()); }
        public CompanyBook build() {
            flushAudit();
            // Ended agreements aren't needed to detect retries. Funded credits already keep their own labels.
            if (!unchanged(contracts)) for (UUID id : new ArrayList<>(changedKeys(contracts))) {
                CompanyContract c = contracts.get(id); if (c != null && c.status() != CompanyContract.Status.ACTIVE) contracts.remove(id);
            }
            // Cap the detailed audit history; the ledger keeps its replay records separately.
            while (audit.size() > 10000) audit.removeFirst();
            return new CompanyBook(this);
        }
    }

    private static void owner(CompoundTag n, String key, EconomicOwner value) { n.put(key, value.save()); }
    private static EconomicOwner owner(CompoundTag n, String key) { return EconomicOwner.load(n.getCompound(key)); }
    private static ListTag ids(Set<UUID> values) { ListTag list = new ListTag(); values.stream().sorted().forEach(id -> list.add(NbtUtils.createUUID(id))); return list; }
    private static Set<UUID> ids(ListTag list) { Set<UUID> result = new HashSet<>(); for (Tag value : list) if (!result.add(NbtUtils.loadUUID(value))) throw new IllegalStateException("Duplicate domain UUID"); return result; }
    private static CompoundTag access(CompanyAccess a) {
        CompoundTag n = new CompoundTag(); n.putUUID("company", a.company()); n.put("owners", ids(a.owners())); n.put("members", ids(a.members())); n.putLong("purchaseCost", a.purchaseCost()); return n;
    }
    private static CompanyAccess access(CompoundTag n) { return new CompanyAccess(n.getUUID("company"), ids(n.getList("owners", Tag.TAG_INT_ARRAY)), ids(n.getList("members", Tag.TAG_INT_ARRAY)), n.getLong("purchaseCost")); }
    public CompoundTag save() { return save(null, null, null); }
    /** Only use this on the writer thread. The returned tags belong to the writer; don't edit them afterward. */
    public static final class SnapshotEncoder {
        private CompanyBook previous;
        private CompoundTag tag;
        private Map<UUID, CompoundTag> contracts = Map.of();
        public synchronized CompoundTag encode(CompanyBook book) {
            var records = new SnapshotMap<UUID, CompoundTag>(contracts);
            for (UUID id : SnapshotMap.differences(previous == null ? Map.of() : previous.contracts, book.contracts)) {
                CompanyContract c = book.contracts.get(id); if (c == null) records.remove(id); else records.put(id, contractTag(c));
            }
            contracts = records.freeze(); tag = book.save(previous, tag, contracts); previous = book; return tag;
        }
    }
    private static CompoundTag contractTag(CompanyContract c) {
        CompoundTag n = new CompoundTag(); n.putUUID("id", c.id()); owner(n, "payer", c.payer()); owner(n, "recipient", c.recipient());
        n.putString("name", c.name()); n.putLong("amount", c.amount()); n.putString("status", c.status().name()); n.putUUID("actor", c.createdBy()); n.putLong("created", c.createdAt()); return n;
    }
    private CompoundTag save(CompanyBook previous, CompoundTag priorTag, Map<UUID, CompoundTag> contractRecords) {
        CompoundTag tag = new CompoundTag(); tag.putInt("version", 5); tag.putLong("cycleDay", cycleDay);
        Map<UUID, CompoundTag> priorCompanies = new HashMap<>();
        if (previous != null) for (Tag raw : priorTag.getList("companies", Tag.TAG_COMPOUND)) { var n = (CompoundTag) raw; priorCompanies.put(n.getUUID("id"), n); }
        Map<CompanyAudit, Tag> priorAudit = new HashMap<>();
        if (previous != null) { var entries = priorTag.getList("audit", Tag.TAG_COMPOUND); for (int i = 0; i < previous.audit.size(); i++) priorAudit.put(previous.audit.get(i), entries.get(i)); }
        ListTag companyTags = new ListTag(); companies.values().stream().sorted(Comparator.comparing(Company::id)).forEach(c -> {
            if (previous != null && c == previous.company(c.id())) { companyTags.add(priorCompanies.get(c.id())); return; }
            Company oldCompany = previous == null ? null : previous.company(c.id()); CompoundTag oldTag = priorCompanies.get(c.id());
            CompoundTag n = new CompoundTag(); n.putUUID("id", c.id()); n.putString("name", c.name()); n.putLong("created", c.createdAt());
            n.putUUID("ceo", c.ceo()); n.putString("status", c.status().name()); ListTag members = new ListTag();
            if (c.bankruptcy() != null) {
                CompoundTag b = new CompoundTag(); b.putLong("debt", c.bankruptcy().debt()); b.putLong("penalty", c.bankruptcy().penalty()); b.putLong("time", c.bankruptcy().time()); b.putLong("bankLoanRemainder",c.bankruptcy().bankLoanRemainder()); n.put("bankruptcy", b);
            }
            if (oldCompany != null && c.members() == oldCompany.members()) n.put("members", oldTag.get("members")); else {
            c.members().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
                CompoundTag m = new CompoundTag(); m.putUUID("player", e.getKey()); m.putString("rank", e.getValue().rank().name()); m.putLong("salary", e.getValue().salary()); members.add(m);
            }); n.put("members", members);
            }
            if (oldCompany != null && c.lastPayday() != null && c.lastPayday() == oldCompany.lastPayday()) n.put("statement", oldTag.get("statement"));
            else if (c.lastPayday() != null) {
                var s = c.lastPayday(); CompoundTag statement = new CompoundTag(); statement.putLong("day", s.cycleDay()); statement.putLong("cutoff", s.cutoff());
                statement.putLong("opening", s.opening()); statement.putLong("released", s.released()); statement.putLong("closing", s.closing()); ListTag lines = new ListTag();
                statement.putBoolean("debug", s.debug());
                if (oldCompany != null && oldCompany.lastPayday() != null && s.lines().equals(oldCompany.lastPayday().lines()))
                    statement.put("lines", oldTag.getCompound("statement").get("lines"));
                else {
                    Map<Company.Line, Tag> priorLines = new HashMap<>();
                    if (oldCompany != null && oldCompany.lastPayday() != null) {
                        var oldLines = oldCompany.lastPayday().lines(); var encoded = oldTag.getCompound("statement").getList("lines", Tag.TAG_COMPOUND);
                        for (int i = 0; i < oldLines.size(); i++) priorLines.put(oldLines.get(i), encoded.get(i));
                    }
                    for (var l : s.lines()) {
                        Tag oldLine = priorLines.get(l); if (oldLine != null) { lines.add(oldLine); continue; }
                        CompoundTag line = new CompoundTag(); line.putString("kind", l.kind()); line.putString("label", l.label()); line.putLong("amount", l.amount()); if (l.party() != null) owner(line, "party", l.party()); lines.add(line);
                    }
                    statement.put("lines", lines);
                }
                n.put("statement", statement);
            } companyTags.add(n);
        }); tag.put("companies", companyTags);
        if (previous != null && contracts == previous.contracts) tag.put("contracts", priorTag.get("contracts"));
        else {
            ListTag contractTags = new ListTag(); contracts.values().stream().sorted(Comparator.comparing(CompanyContract::id))
                    .forEach(c -> contractTags.add(contractRecords == null ? contractTag(c) : contractRecords.get(c.id()))); tag.put("contracts", contractTags);
        }
        if (previous != null && buffers.equals(previous.buffers)) tag.put("buffers", priorTag.get("buffers")); else {
        ListTag creditTags = new ListTag(); buffers.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
            e.getValue().values().stream().sorted(Comparator.comparing(CompanyCredit::key)).forEach(c -> {
                CompoundTag n = new CompoundTag(); n.putUUID("company", e.getKey()); owner(n, "source", c.source()); n.putString("category", c.category());
                n.putString("association", c.association()); n.putString("label", c.label()); n.putLong("amount", c.amount()); n.putBoolean("taxable", c.taxable()); creditTags.add(n);
            })); tag.put("buffers", creditTags);
        }
        if (previous != null && access.equals(previous.access)) tag.put("access", priorTag.get("access")); else {
        ListTag accessTags = new ListTag(); access.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> { CompoundTag n = access(e.getValue()); n.putString("property", e.getKey()); accessTags.add(n); }); tag.put("access", accessTags);
        }
        if (previous != null && operations.equals(previous.operations)) tag.put("operations", priorTag.get("operations")); else {
        ListTag operationTags = new ListTag(); operations.values().stream().sorted(Comparator.comparing(CompanyAssetOperation::id)).forEach(o -> {
            CompoundTag n = new CompoundTag(); n.putUUID("id", o.id()); if (o.actor() != null) n.putUUID("actor", o.actor()); n.putUUID("company", o.company());
            n.putString("property", o.property()); n.putString("kind", o.kind().name()); owner(n, "target", o.target()); n.putLong("amount", o.amount()); n.putLong("created", o.createdAt());
            n.put("previous", access(o.previous())); n.put("desired", access(o.desired())); operationTags.add(n);
        }); tag.put("operations", operationTags);
        }
        ListTag auditTags = new ListTag(); for (CompanyAudit a : audit) {
            Tag old = priorAudit.get(a); if (old != null) { auditTags.add(old); continue; }
            CompoundTag n = new CompoundTag(); n.putUUID("operation", a.operation()); n.putLong("time", a.time()); if (a.actor() != null) n.putUUID("actor", a.actor());
            owner(n, "owner", a.owner()); owner(n, "payer", a.payer()); owner(n, "recipient", a.recipient()); n.putString("category", a.category()); n.putLong("amount", a.amount()); n.putString("asset", a.asset()); auditTags.add(n);
        } tag.put("audit", auditTags); return tag;
    }
    public static CompanyBook load(CompoundTag tag) {
        if (tag.getInt("version") < 1 || tag.getInt("version") > 5 || !tag.contains("cycleDay", Tag.TAG_LONG)) throw new IllegalStateException("Unsupported Company data");
        for (String field : List.of("companies", "contracts", "buffers", "access", "operations", "audit")) if (!tag.contains(field, Tag.TAG_LIST)) throw new IllegalStateException("Missing Company data: " + field);
        Editor e = new Editor(); e.cycleDay = tag.getLong("cycleDay");
        for (Tag raw : tag.getList("companies", Tag.TAG_COMPOUND)) {
            CompoundTag n = (CompoundTag) raw; Map<UUID, Company.Member> members = new HashMap<>();
            for (Tag member : n.getList("members", Tag.TAG_COMPOUND)) { CompoundTag m = (CompoundTag) member; if (members.putIfAbsent(m.getUUID("player"), new Company.Member(Company.Rank.valueOf(m.getString("rank")), m.getLong("salary"))) != null) throw new IllegalStateException("Duplicate Company member"); }
            Company.Statement statement = null;
            if (n.contains("statement", Tag.TAG_COMPOUND)) {
                CompoundTag s = n.getCompound("statement"); List<Company.Line> lines = new ArrayList<>();
                for (Tag line : s.getList("lines", Tag.TAG_COMPOUND)) { CompoundTag l = (CompoundTag) line; lines.add(new Company.Line(l.getString("kind"), l.getString("label"), l.getLong("amount"), l.contains("party") ? owner(l, "party") : null)); }
                statement = new Company.Statement(s.getLong("day"), s.getLong("cutoff"), s.getLong("opening"), s.getLong("released"), s.getLong("closing"), lines, s.getBoolean("debug"));
            }
            Company.Bankruptcy bankruptcy = null;
            if (n.contains("bankruptcy", Tag.TAG_COMPOUND)) { var b = n.getCompound("bankruptcy"); bankruptcy = new Company.Bankruptcy(b.getLong("debt"), b.getLong("penalty"), b.getLong("time"), b.getLong("bankLoanRemainder")); }
            Company c = new Company(n.getUUID("id"), n.getString("name"), n.getLong("created"), n.getUUID("ceo"), members, Company.Status.valueOf(n.getString("status")), statement, bankruptcy);
            if (e.companies.putIfAbsent(c.id(), c) != null) throw new IllegalStateException("Duplicate Company ID");
        }
        for (Tag raw : tag.getList("contracts", Tag.TAG_COMPOUND)) {
            CompoundTag n = (CompoundTag) raw;
            String name = tag.getInt("version") < 3 ? CompanyContract.legacyName(n.getUUID("id")) : n.getString("name");
            CompanyContract c = new CompanyContract(n.getUUID("id"), owner(n, "payer"), owner(n, "recipient"), name, n.getLong("amount"), CompanyContract.Status.valueOf(n.getString("status")), n.getUUID("actor"), n.getLong("created"));
            if (e.contracts.putIfAbsent(c.id(), c) != null) throw new IllegalStateException("Duplicate Company contract");
        }
        for (Tag raw : tag.getList("buffers", Tag.TAG_COMPOUND)) {
            CompoundTag n = (CompoundTag) raw;
            String label = n.getString("label");
            if (tag.getInt("version") < 4) {
                label = CompanyChat.category(n.getString("category"));
                if (n.getString("category").equals("Contract")) {
                    CompanyContract contract = e.contracts.get(UUID.fromString(n.getString("association")));
                    if (contract == null) throw new IllegalStateException("Missing buffered Company contract evidence");
                    label = "Contract '" + contract.name() + "'";
                }
            } else if (!n.contains("label", Tag.TAG_STRING)) throw new IllegalStateException("Missing Company credit label");
            CompanyCredit c = new CompanyCredit(owner(n, "source"), n.getString("category"), n.getString("association"), label, n.getLong("amount"), n.getBoolean("taxable"));
            UUID id = n.getUUID("company"); if (e.buffers.getOrDefault(id, Map.of()).containsKey(c.key())) throw new IllegalStateException("Duplicate Company credit"); e.credit(id, c);
        }
        for (Tag raw : tag.getList("access", Tag.TAG_COMPOUND)) { CompoundTag n = (CompoundTag) raw; if (e.access.putIfAbsent(n.getString("property"), access(n)) != null) throw new IllegalStateException("Duplicate Company property access"); }
        for (Tag raw : tag.getList("operations", Tag.TAG_COMPOUND)) {
            CompoundTag n = (CompoundTag) raw; CompanyAssetOperation o = new CompanyAssetOperation(n.getUUID("id"), n.hasUUID("actor") ? n.getUUID("actor") : null, n.getUUID("company"), n.getString("property"), CompanyAssetOperation.Kind.valueOf(n.getString("kind")), owner(n, "target"), n.getLong("amount"), n.getLong("created"), access(n.getCompound("previous")), access(n.getCompound("desired")));
            if (e.operations.putIfAbsent(o.property(), o) != null) throw new IllegalStateException("Duplicate Company property operation");
        }
        for (Tag raw : tag.getList("audit", Tag.TAG_COMPOUND)) {
            CompoundTag n = (CompoundTag) raw; e.audit.add(new CompanyAudit(n.getUUID("operation"), n.getLong("time"), n.hasUUID("actor") ? n.getUUID("actor") : null, owner(n, "owner"), owner(n, "payer"), owner(n, "recipient"), n.getString("category"), n.getLong("amount"), n.getString("asset")));
        } return e.build();
    }
}


