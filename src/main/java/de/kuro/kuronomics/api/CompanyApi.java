package de.kuro.kuronomics.api;

import de.kuro.kuronomics.company.Company;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;

/** These snapshots can't be changed. They include ranks, salaries, company status and the last payday. */
public interface CompanyApi {
    enum Permission { MEMBER, STAFF, FINANCE, ASSET, LANDLORD, STATEMENT, SUCCESSION, CLOSE, COMMERCE }
    Optional<Company> find(UUID id);
    Optional<Company> find(String name);
    Set<UUID> memberships(UUID player);
    boolean hasPermission(UUID player, UUID company, Permission permission);
    OperationResult rank(ServerPlayer actor, UUID company, UUID member, Company.Rank rank, EconomyApi.Context context);
    OperationResult salary(ServerPlayer actor, UUID company, UUID member, long cents, EconomyApi.Context context);
    OperationResult remove(ServerPlayer actor, UUID company, UUID member, EconomyApi.Context context);
}
