package de.kuro.kuronomics.estate;

import java.util.UUID;
import de.kuro.kuronomics.economy.EconomicOwner;

public record Estate(String id, String dimension, String region, long price, long rent, EconomicOwner economicOwner, UUID tenant, boolean locked, String regionBinding, boolean buildingAllowed, java.util.Set<UUID> guests) {
    public Estate(String id, String dimension, String region, long price, long rent, UUID owner, UUID tenant, boolean locked, String regionBinding, boolean buildingAllowed, java.util.Set<UUID> guests) {
        this(id, dimension, region, price, rent, EconomicOwner.personalOrState(owner), tenant, locked, regionBinding, buildingAllowed, guests);
    }
    /** For personal-account callers. Don't return a company UUID as if it were a player. */
    public UUID owner() { return economicOwner.isPlayer() ? economicOwner.id() : null; }
    public Estate(String id, String dimension, String region, long price, long rent, UUID owner, UUID tenant, boolean locked, String regionBinding, boolean buildingAllowed) {
        this(id, dimension, region, price, rent, EconomicOwner.personalOrState(owner), tenant, locked, regionBinding, buildingAllowed, java.util.Set.of());
    }
    public Estate(String id, String dimension, String region, long price, long rent, UUID owner, UUID tenant, boolean locked, String regionBinding) {
        this(id, dimension, region, price, rent, owner, tenant, locked, regionBinding, true);
    }
    public Estate(String dimension, String region, long price, long rent, UUID owner, UUID tenant, boolean locked) {
        this(dimension + "|" + region, dimension, region, price, rent, owner, tenant, locked, "");
    }
    public Estate {
        guests = java.util.Set.copyOf(guests);
        java.util.Objects.requireNonNull(regionBinding, "regionBinding");
        java.util.Objects.requireNonNull(economicOwner, "economicOwner");
        if (id == null || id.isBlank() || dimension == null || region == null || region.isBlank() || price < 0 || rent < 0) throw new IllegalArgumentException("Invalid estate");
        if (economicOwner.isPlayer() && economicOwner.id().equals(tenant)) throw new IllegalArgumentException("Owner cannot rent their own estate");
    }
    public Estate bound(String name, String binding) { return new Estate(id, dimension, name, price, rent, economicOwner, tenant, locked, binding, buildingAllowed, binding.equals(regionBinding) ? guests : java.util.Set.of()); }
    public Estate building(boolean value) { return new Estate(id, dimension, region, price, rent, economicOwner, tenant, locked, regionBinding, value, guests); }
    public String key() { return id; }
    public Estate prices(long price, long rent) { return new Estate(id, dimension, region, price, rent, economicOwner, tenant, locked, regionBinding, buildingAllowed, guests); }
    public Estate owner(UUID value) { return ownership(EconomicOwner.personalOrState(value)); }
    public Estate ownership(EconomicOwner value) { return new Estate(id, dimension, region, price, rent, value, value.isPlayer() && value.id().equals(tenant) ? null : tenant, locked, regionBinding, buildingAllowed, economicOwner.equals(value) ? guests : java.util.Set.of()); }
    public Estate tenant(UUID value) {
        var remaining = new java.util.HashSet<>(guests); remaining.remove(value);
        return new Estate(id, dimension, region, price, rent, economicOwner, value, locked, regionBinding, buildingAllowed, remaining);
    }
    public Estate locked(boolean value) { return new Estate(id, dimension, region, price, rent, economicOwner, tenant, value, regionBinding, buildingAllowed, guests); }
    public Estate guest(UUID player, boolean add) {
        var updated = new java.util.HashSet<>(guests);
        if (add) updated.add(player); else updated.remove(player);
        return new Estate(id, dimension, region, price, rent, economicOwner, tenant, locked, regionBinding, buildingAllowed, updated);
    }
}
