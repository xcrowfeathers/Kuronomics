package de.kuro.kuronomics.company;

import de.kuro.kuronomics.economy.EconomicOwner;
import de.kuro.kuronomics.economy.data.EconomyLedger;
import java.util.*;

/** For old saves, keep all CEO roles. If someone has multiple other jobs, keep their oldest one. */
public final class CompanyEmploymentPolicy {
    public static Set<UUID> reconcile(EconomyLedger ledger) {
        var companies = ledger.companies().companies().values().stream().filter(c -> c.status() != Company.Status.CLOSED)
                .sorted(Comparator.comparingLong(Company::createdAt).thenComparing(Company::id)).toList();
        Set<UUID> leaders = new HashSet<>(); companies.forEach(c -> leaders.add(c.ceo()));
        Map<UUID, UUID> employed = new HashMap<>(); Set<UUID> changed = new HashSet<>();
        var edit = ledger.companies().edit(); long now = System.currentTimeMillis();
        for (Company c : companies) {
            var members = new HashMap<>(c.members());
            for (var entry : c.members().entrySet()) {
                UUID player = entry.getKey(); if (entry.getValue().rank() == Company.Rank.CEO) continue;
                if (!leaders.contains(player) && employed.putIfAbsent(player, c.id()) == null) continue;
                members.remove(player); changed.add(c.id());
                var owner = EconomicOwner.company(c.id());
                edit.audit(new CompanyAudit(UUID.randomUUID(), now, null, owner, owner, EconomicOwner.player(player), "COMPANY_EMPLOYMENT_RECONCILED", 0, ""));
                com.mojang.logging.LogUtils.getLogger().warn("Company membership upgrade: removed conflicting employment player={} company={}; CEO roles or oldest employment retained", player, c.id());
            }
            if (members.size() != c.members().size()) edit.companies.put(c.id(), c.membership(members, c.ceo()));
        }
        if (!changed.isEmpty()) ledger.commitOwners(UUID.randomUUID(), "company-employment-upgrade", Map.of(), List.of(), edit.build(), Map.of());
        return Set.copyOf(changed);
    }
    private CompanyEmploymentPolicy() {}
}
