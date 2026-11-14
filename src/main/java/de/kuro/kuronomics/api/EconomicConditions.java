package de.kuro.kuronomics.api;

import de.kuro.kuronomics.company.Company;
import de.kuro.kuronomics.economy.EconomicOwner;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;

/**
 * Run these checks on the server thread. They only read state; combine them with and/or/negate.
 * They're eligibility hints, so the actual action still has to check everything again.
 */
public final class EconomicConditions {
    public static Predicate<ServerPlayer> accountExists(EconomicOwner owner) {
        Objects.requireNonNull(owner); return p -> KuronomyApi.economy(p.server).exists(owner);
    }
    public static Predicate<ServerPlayer> funds(long cents) { return funds(null, cents); }
    /** A null owner means the player's own account, not a company or the state. */
    public static Predicate<ServerPlayer> funds(EconomicOwner owner, long cents) {
        if (cents <= 0) throw new IllegalArgumentException("Condition amount must be positive cents");
        return p -> KuronomyApi.economy(p.server).canAfford(owner == null ? EconomyApi.player(p.getUUID()) : owner, cents);
    }
    public static Predicate<ServerPlayer> companyPermission(UUID company, CompanyApi.Permission permission) {
        Objects.requireNonNull(company); Objects.requireNonNull(permission);
        return p -> KuronomyApi.companies(p.server).hasPermission(p.getUUID(), company, permission);
    }
    public static Predicate<ServerPlayer> companyRank(UUID company, Company.Rank rank, boolean minimum) {
        Objects.requireNonNull(company); Objects.requireNonNull(rank);
        return p -> {
            var api = KuronomyApi.companies(p.server);
            if (!api.hasPermission(p.getUUID(), company, CompanyApi.Permission.MEMBER)) return false;
            var member = api.find(company).orElseThrow().members().get(p.getUUID());
            return minimum ? member.rank().compareTo(rank) >= 0 : member.rank() == rank;
        };
    }
    public static Predicate<ServerPlayer> job(String job) {
        Objects.requireNonNull(job); return p -> KuronomyApi.jobs(p.server).employment(p.getUUID()).filter(e -> e.job().id().equals(job)).isPresent();
    }
    public static Predicate<ServerPlayer> eligibleForJob(String job) {
        Objects.requireNonNull(job); return p -> KuronomyApi.jobs(p.server).canStart(p.getUUID(), job);
    }
    /** Java-only check using the job's target, zone and location rules. */
    public static Predicate<ServerPlayer> workCapability(net.minecraft.core.BlockPos position, JobApi.WorkCapability capability,
                                                        net.minecraft.world.entity.EntityType<?> target) {
        var at = Objects.requireNonNull(position).immutable(); Objects.requireNonNull(capability);
        return p -> KuronomyApi.jobs(p.server).canWork(p, at, capability, target);
    }
    /** A null owner means the player's own account. This doesn't check WorldGuard membership. */
    public static Predicate<ServerPlayer> ownsProperty(String property, EconomicOwner owner) {
        Objects.requireNonNull(property); return p -> KuronomyApi.estates(p.server).owns(owner == null ? EconomyApi.player(p.getUUID()) : owner, property);
    }
    public static Predicate<ServerPlayer> managesProperty(String property) {
        Objects.requireNonNull(property); return p -> {
            try { return KuronomyApi.estates(p.server).canManage(p, property); }
            catch (SecurityException unavailableActor) { return false; }
        };
    }
    private EconomicConditions() {}
}
