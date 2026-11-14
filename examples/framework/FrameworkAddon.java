package example.framework;

import de.kuro.kuronomics.api.*;
import de.kuro.kuronomics.api.event.CompanyPaydayCompletedEvent;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

// This is an example addon.

@Mod("framework_example")
public final class FrameworkAddon {
    public FrameworkAddon() { NeoForge.EVENT_BUS.addListener(FrameworkAddon::companyPayday); }

    // pays $ 2.50 from a company account to a player’s next payday
    public static OperationResult questCompleted(ServerPlayer authorizedTreasurer, UUID fundingCompany, UUID winner, UUID completion) {

        var reward = EconomicReward.funded(EconomyApi.company(fundingCompany), 250, EconomyApi.Timing.NEXT_PAYDAY);
        return reward.apply(authorizedTreasurer, winner, new EconomyApi.Context(completion,
                ResourceLocation.parse("framework_example:quest_reward"), "Quest complete"));
    }
    // charges a company $1 and immediately pays it to a player, after checking financial permission
    public static OperationResult deviceUsed(ServerPlayer operator, UUID company, UUID utilityProvider, UUID operation) {

        var permission = EconomicConditions.companyPermission(company, CompanyApi.Permission.FINANCE);

        if (!permission.test(operator)) return new OperationResult(OperationResult.Status.DENIED, "Company financial permission required");
        // This only checks eligibility. The transfer checks permissions, funds and holds again before paying
        return KuronomyApi.economy(operator.server).transfer(operator, EconomyApi.company(company), EconomyApi.player(utilityProvider),
                100, EconomyApi.Timing.IMMEDIATE, new EconomyApi.Context(operation, ResourceLocation.parse("framework_example:device"), "Device operation"));
    }
    private static void companyPayday(CompanyPaydayCompletedEvent event) {
        // logs when company payday finishes and how many companies were included
        // Just read the batch here, don't run money operations inside the listener.
        com.mojang.logging.LogUtils.getLogger().info("Company PayDay {} completed for {} companies", event.reference(), event.statements().size());
    }
}
