package de.kuro.kuronomics.market;

import de.kuro.kuronomics.company.Company;
import de.kuro.kuronomics.economy.EconomicOwner;
import de.kuro.kuronomics.economy.data.EconomyLedger;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;

public final class MarketPermissions {
    public static boolean canCreate(Company c, UUID actor) {
        return c != null && de.kuro.kuronomics.company.CompanyPermissionService.allowed(c, actor,
                de.kuro.kuronomics.company.CompanyPermissionService.Capability.COMMERCE);
    }
    public static boolean allowed(EconomyLedger ledger, EconomicOwner owner, UUID actor, boolean senior) {
        if (owner.isPlayer()) return owner.id().equals(actor);
        if (!owner.isCompany()) return false;
        Company c = ledger.companies().company(owner.id());
        return senior ? canCreate(c, actor) : c != null && de.kuro.kuronomics.company.CompanyPermissionService.allowed(c, actor,
                de.kuro.kuronomics.company.CompanyPermissionService.Capability.MEMBER);
    }
    public static List<EconomicOwner> companies(ServerPlayer player, boolean senior) {
        var ledger = EconomyLedger.get(player.server);
        return ledger.companies().memberships(player.getUUID()).stream().map(id -> EconomicOwner.company(id))
                .filter(owner -> allowed(ledger, owner, player.getUUID(), senior))
                .sorted(Comparator.comparing(o -> ledger.companies().company(o.id()).name(), String.CASE_INSENSITIVE_ORDER)).toList();
    }
    public static boolean isEmployee(ServerPlayer player) {
        var book = EconomyLedger.get(player.server).companies();
        return book.memberships(player.getUUID()).stream().anyMatch(id -> book.company(id).members().get(player.getUUID()).rank() == Company.Rank.EMPLOYEE);
    }
    private MarketPermissions() {}
}
