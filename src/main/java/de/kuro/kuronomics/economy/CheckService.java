package de.kuro.kuronomics.economy;

import de.kuro.kuronomics.economy.data.EconomyLedger;
import de.kuro.kuronomics.item.Prices;
import de.kuro.kuronomics.registry.ModContent;
import de.kuro.kuronomics.util.ModText;
import de.kuro.kuronomics.mailbox.MailboxService;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

public final class CheckService {
    private CheckService() {}
    public static void draft(ItemStack stack, long amount) {
        if (!stack.is(ModContent.CHECK.get()) || Prices.data(stack).hasUUID("claim") || Prices.data(stack).getDouble("worth") > 0)
            throw new IllegalArgumentException("You need an empty check! You can get one at the ATM.");
        if (amount < 50000) throw new IllegalArgumentException("Minimum check is $500");
        CustomData.update(DataComponents.CUSTOM_DATA, stack, n -> n.putLong("draftCents", amount));
        stack.set(DataComponents.CUSTOM_NAME, ModText.checkName("Written Check", amount));
    }
    public static ItemStack issue(EconomyLedger ledger, long amount) {
        UUID claim = UUID.randomUUID(); new EconomyService(ledger).issueCheck(amount, claim);
        return issue(claim, amount);
    }
    private static ItemStack issue(UUID claim, long amount) {
        ItemStack stack = new ItemStack(ModContent.CHECK.get());
        CustomData.update(DataComponents.CUSTOM_DATA, stack, n -> { n.putUUID("claim", claim); n.putLong("valueCents", amount); });
        stack.set(DataComponents.CUSTOM_NAME, ModText.checkName(amount)); return stack;
    }
    public static ItemStack validate(ServerPlayer player, ItemStack draft) {
        if (!draft.is(ModContent.CHECK.get()) || Prices.data(draft).hasUUID("claim") || Prices.data(draft).getDouble("worth") > 0)
            throw new IllegalArgumentException("Insert a check that has not been validated yet.");
        var data = Prices.data(draft);
        long amount = data.contains("draftCents") ? data.getLong("draftCents") : Money.legacy(data.getDouble("preworth"));
        if (amount < 50000) throw new IllegalArgumentException("Define the check amount first with /checkdefine");
        var dataTag = Prices.data(draft);
        UUID claim = dataTag.hasUUID("validationId") ? dataTag.getUUID("validationId") : UUID.randomUUID();
        if (!dataTag.hasUUID("validationId")) CustomData.update(DataComponents.CUSTOM_DATA, draft, n -> n.putUUID("validationId", claim));
        var economy = new EconomyService(EconomyLedger.get(player.server));
        if (economy.issueCheck(player.getUUID(), amount, claim))
            new MailboxService(player.server).withdrawal(player.getUUID(), amount,
                    economy.getBalance(player.getUUID()), claim, "Validated check");
        return issue(claim, amount);
    }
    public static void redeem(ServerPlayer player, ItemStack stack) {
        if (!stack.is(ModContent.CHECK.get())) throw new IllegalArgumentException("Insert a check to redeem.");
        var ledger = EconomyLedger.get(player.server); new EconomyService(ledger).requireActive(player.getUUID());
        var data = Prices.data(stack);
        if (!data.hasUUID("claim")) {
            // Only accept old bearer checks from the actual server-side check slot.
            // Consume the old item once. Newly issued checks always have a ledger-backed claim.
            long legacy = Money.legacy(data.getDouble("worth"));
            if (legacy <= 0) throw new IllegalArgumentException("This check has no value.");
            UUID reference = UUID.randomUUID(); var economy = new EconomyService(ledger);
            if (economy.adjust(player.getUUID(), legacy, reference, "legacy check redemption")) {
                stack.shrink(1);
                new MailboxService(player.server).deposit(player.getUUID(), legacy,
                        economy.getBalance(player.getUUID()), reference, "Check redeemed");
            }
            return;
        }
        UUID claim = data.getUUID("claim"); long value = ledger.checkValue(claim); var economy = new EconomyService(ledger);
        if (!economy.redeemCheck(player.getUUID(), claim))
            throw new IllegalArgumentException("This check has already been redeemed or is invalid.");
        stack.shrink(1);
        UUID reference = EconomyService.stableReference("check-redemption", claim.toString());
        new MailboxService(player.server).deposit(player.getUUID(), value,
                economy.getBalance(player.getUUID()), reference, "Check redeemed");
    }
}
