package de.kuro.kuronomics.client;

import de.kuro.kuronomics.menu.WalletMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public final class WalletScreen extends AbstractContainerScreen<WalletMenu> {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath("kuronomics", "textures/screens/wallet_gui.png");
    public WalletScreen(WalletMenu menu, Inventory inventory, Component title) { super(menu, inventory, title); imageWidth = 184; imageHeight = 171; inventoryLabelY = 78; }
    @Override protected void renderBg(GuiGraphics g, float tick, int x, int y) { g.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight); }
    @Override protected void renderLabels(GuiGraphics g, int x, int y) { g.drawString(font, "Wallet", 75, 17, 0xFF3C3C3C, false); }
    @Override public void render(GuiGraphics g, int x, int y, float tick) { super.render(g, x, y, tick); renderTooltip(g, x, y); }
}
