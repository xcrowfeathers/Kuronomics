package de.kuro.kuronomics.api;

import de.kuro.kuronomics.company.CompanyContract;
import de.kuro.kuronomics.economy.EconomicOwner;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;

/** Personal and company contracts have their own IDs and payday schedules. */
public interface ContractApi {
    record PersonalContract(EconomicOwner payer, EconomicOwner recipient, long amount, String name) {}
    List<PersonalContract> personalOutgoing(UUID player);
    List<CompanyContract> companyContracts(EconomicOwner participant);
    OperationResult createCompanyContract(ServerPlayer actor, EconomicOwner payer, EconomicOwner recipient, String name, long cents, EconomyApi.Context context);
    OperationResult cancelCompanyContract(ServerPlayer actor, EconomicOwner participant, UUID contract, EconomyApi.Context context);
}
