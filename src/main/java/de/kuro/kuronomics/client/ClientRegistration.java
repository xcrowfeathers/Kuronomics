package de.kuro.kuronomics.client;

import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.registry.ModContent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

@EventBusSubscriber(modid = Kuronomics.MODID, value = Dist.CLIENT)
public final class ClientRegistration {
    private ClientRegistration() {}
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST)
    public static void tooltip(net.neoforged.neoforge.event.entity.player.ItemTooltipEvent event) {
        try {
            long price = de.kuro.kuronomics.item.Prices.get(event.getItemStack());
            if (price >= 0) {
                var stack = event.getItemStack();
                var lines=event.getToolTip();
                if (!lines.isEmpty() && de.kuro.kuronomics.item.Prices.hasGeneratedName(stack))
                    lines.set(0,de.kuro.kuronomics.item.Prices.unpricedName(stack));
                lines.removeIf(line->line.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                        && text.getKey().equals("kuronomics.tooltip.price"));
                lines.add(Math.min(1,lines.size()),net.minecraft.network.chat.Component.translatable("kuronomics.tooltip.price",
                        net.minecraft.network.chat.Component.literal(de.kuro.kuronomics.economy.Money.formatPrice(price)).withStyle(net.minecraft.ChatFormatting.GREEN))
                        .withStyle(net.minecraft.ChatFormatting.GRAY));
            }
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            event.getToolTip().add(net.minecraft.network.chat.Component.literal("Invalid price").withStyle(net.minecraft.ChatFormatting.RED));
        }
    }
    @SubscribeEvent public static void screens(RegisterMenuScreensEvent event) {
        event.register(ModContent.MACHINE_MENU.get(), MachineScreen::new);
        event.register(ModContent.WALLET_MENU.get(), WalletScreen::new);
        event.register(ModContent.DOCUMENT_MENU.get(), DocumentScreen::new);
        event.register(ModContent.SERVER_SHOP_TERMINAL_MENU.get(), ServerShopTerminalScreen::new);
        event.register(ModContent.SERVER_SHOP_EDITOR_MENU.get(), ServerShopEditorScreen::new);
        event.register(ModContent.AUCTION_MENU.get(), AuctionScreen::new);
        event.register(ModContent.REAL_ESTATE_MENU.get(), RealEstateScreen::new);
        event.register(ModContent.JOB_MENU.get(), JobScreen::new);
        event.register(ModContent.MARKET_MENU.get(), MarketScreen::new);
        event.register(ModContent.CENTRAL_BANK_MENU.get(), CentralBankScreen::new);
    }
}
