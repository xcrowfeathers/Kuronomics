package de.kuro.kuronomics.company;

import java.util.*;

/** Only remove access entries managed by the company. Leave other entries alone. */
public record CompanyAccess(UUID company, Set<UUID> owners, Set<UUID> members, long purchaseCost) {
    public CompanyAccess(UUID company, Set<UUID> owners, Set<UUID> members) { this(company, owners, members, 0); }
    public CompanyAccess {
        Objects.requireNonNull(company); owners = Set.copyOf(owners); members = Set.copyOf(members);
        if (purchaseCost < 0 || !Collections.disjoint(owners, members)) throw new IllegalArgumentException("Invalid Company domains");
    }
    public static CompanyAccess desired(Company company) {
        Set<UUID> owners = new HashSet<>(), members = new HashSet<>();
        company.members().forEach((id, value) -> {
            if (value.rank() == Company.Rank.CEO || value.rank() == Company.Rank.DIRECTOR) owners.add(id);
            else members.add(id);
        });
        return new CompanyAccess(company.id(), owners, members);
    }
    public static CompanyAccess empty(UUID company) { return new CompanyAccess(company, Set.of(), Set.of()); }
}

