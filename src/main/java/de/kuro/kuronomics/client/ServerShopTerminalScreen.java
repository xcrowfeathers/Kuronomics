package de.kuro.kuronomics.client;

import de.kuro.kuronomics.economy.Money;
import de.kuro.kuronomics.menu.ServerShopTerminalMenu;
import de.kuro.kuronomics.network.ServerShopAction;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ServerShopTerminalScreen extends AbstractContainerScreen<ServerShopTerminalMenu> {
    private static final ScreenBackground BACKGROUND = new ScreenBackground("server_shop", 330, 250);
    private static final ScreenBackground BINDING_BACKGROUND = new ScreenBackground("server_shop_binding", 128, 119);
    private final List<Button> adminButtons = new ArrayList<>();
    private final List<Button> buyButtons = new ArrayList<>(), sellButtons = new ArrayList<>();
    private final List<Button> pageButtons = new ArrayList<>();
    private Button adminToggle;
    private boolean compactAdmin, compactAdminOpen;
    public ServerShopTerminalScreen(ServerShopTerminalMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title); imageWidth = 330; imageHeight = 250; inventoryLabelY = 10_000;
    }
    private void send(String action) { PacketDistributor.sendToServer(new ServerShopAction(menu.containerId, action, 1, 0, 0, 0,
            menu.state.revision(), 0, 0, new UUID(0, 0), UUID.randomUUID())); }
    private void trade(String action, int index) {
        if (index < 0 || index >= menu.state.offers().size()) return;
        var offer = menu.state.offers().get(index);
        PacketDistributor.sendToServer(new ServerShopAction(menu.containerId, action, index, 0, 0, 0,
                menu.state.revision(), 0, 0, offer.id(), UUID.randomUUID()));
    }
    private Button button(String text, int x, int y, int w, String action) {
        return addRenderableWidget(Button.builder(Component.literal(text), b -> send(action)).bounds(leftPos + x, topPos + y, w, 20)
                .build(builder -> new TerminalButton(builder, TerminalButton.SERVER_SHOP)));
    }
    @Override protected void init() {
        super.init();
        adminButtons.clear(); buyButtons.clear(); sellButtons.clear(); pageButtons.clear(); compactAdminOpen = false;
        compactAdmin = menu.state.administrator() && width < imageWidth + 272;
        for (int i = 0; i < 6; i++) {
            int index = i, cardX = 8 + i % 2 * 161, y = 69 + i / 2 * 64;
            buyButtons.add(addRenderableWidget(Button.builder(Component.literal("Buy"), b -> trade("buy", index)).bounds(leftPos + cardX + 5, topPos + y, 62, 18)
                    .build(builder -> new TerminalButton(builder, TerminalButton.SERVER_SHOP))));
            sellButtons.add(addRenderableWidget(Button.builder(Component.literal("Sell"), b -> trade("sell", index)).bounds(leftPos + cardX + 86, topPos + y, 62, 18)
                    .build(builder -> new TerminalButton(builder, TerminalButton.SERVER_SHOP))));
        }
        buyButtons.forEach(button -> button.visible = false); sellButtons.forEach(button -> button.visible = false);
        pageButtons.add(button("<", 209, 229, 30, "previous")); pageButtons.add(button(">", 291, 229, 30, "next"));
        int panelX = adminPanelX();
        adminButtons.add(button("<", panelX + 8, 65, 25, "choice_previous"));
        adminButtons.add(button(">", panelX + 95, 65, 25, "choice_next"));
        adminButtons.add(button("Bind", panelX + 8, 91, 53, "bind"));
        adminButtons.add(button("Unbind", panelX + 67, 91, 53, "unbind"));
        adminToggle = addRenderableWidget(Button.builder(Component.literal("Bind..."), b -> {
            compactAdminOpen = !compactAdminOpen; updateVisibility();
        }).bounds(leftPos + 270, topPos + 4, 53, 20).build(builder -> new TerminalButton(builder, TerminalButton.SERVER_SHOP)));
        updateVisibility();
    }
    @Override protected void containerTick() {
        super.containerTick(); updateVisibility();
    }
    private int adminPanelX() { return compactAdmin ? (imageWidth - 128) / 2 : 334; }
    private void updateVisibility() {
        boolean adminPanel = menu.state.administrator() && (!compactAdmin || compactAdminOpen);
        for (Button button : adminButtons) button.visible = adminPanel;
        if (adminToggle != null) {
            adminToggle.visible = menu.state.administrator() && compactAdmin;
            adminToggle.setMessage(Component.literal(compactAdminOpen ? "Close" : "Bind..."));
        }
        for (int i = 0; i < 6; i++) {
            boolean present = i < menu.state.offers().size();
            boolean catalogVisible = !compactAdmin || !compactAdminOpen;
            boolean buys = catalogVisible && present && !menu.state.mode().equals("PLAYER_SELLS");
            boolean sells = catalogVisible && present && !menu.state.mode().equals("PLAYER_BUYS");
            Button buy = buyButtons.get(i), sale = sellButtons.get(i);
            buy.visible = buys; buy.active = buys && menu.state.offers().get(i).canBuy();
            sale.visible = sells; sale.active = sells && menu.state.offers().get(i).canSell();
            buy.setTooltip(!buys || buy.active ? null : Tooltip.create(Component.literal(menu.state.offers().get(i).buyBlockReason())));
            sale.setTooltip(!sells || sale.active ? null : Tooltip.create(Component.literal(menu.state.offers().get(i).sellBlockReason())));
        }
        int pages = Math.max(1, (menu.state.total() + 5) / 6);
        if (pageButtons.size() == 2) {
            pageButtons.get(0).active = menu.state.page() > 0;
            pageButtons.get(1).active = menu.state.page() + 1 < pages;
        }
    }
    @Override protected void renderBg(GuiGraphics g, float tick, int mouseX, int mouseY) {
        BACKGROUND.draw(g, leftPos, topPos);
        if (menu.state.administrator() && (!compactAdmin || compactAdminOpen)) {
            BINDING_BACKGROUND.draw(g, leftPos + adminPanelX(), topPos + 31);
        }
    }
    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        var state = menu.state;
        g.drawCenteredString(font, state.name(), imageWidth / 2, 9, state.name().startsWith("No ") ? 0xFFFF5555 : 0xFFFFCC33);
        for (int i = 0; i < state.offers().size() && i < 6 && (!compactAdmin || !compactAdminOpen); i++) {
            var offer = state.offers().get(i); int x = 13 + i % 2 * 161, y = 35 + i / 2 * 64;
            g.renderItem(offer.item(), x, y); g.drawString(font, ellipsize(offer.item().getHoverName().getString(), 124), x + 21, y, 0xFF333333, false);
            String quantity = "x" + offer.quantity();
            String stock = offer.stockMode().equals("INFINITE") ? "Infinite" : "Stock " + offer.stock();
            g.drawString(font, quantity + " • " + stock, x + 21, y + 11, offer.stockMode().equals("INFINITE") ? 0xFF777777 : 0xFFAA7700, false);
            if (!state.mode().equals("PLAYER_SELLS")) g.drawString(font, Money.format(offer.buyPrice()), x, y + 22, 0xFF229922, false);
            if (!state.mode().equals("PLAYER_BUYS")) {
                String sell = Money.format(offer.sellPrice()); g.drawString(font, sell, x + 145 - font.width(sell), y + 22, 0xFF176B3A, false);
            }
        }
        int pages = Math.max(1, (state.total() + 5) / 6);
        g.drawCenteredString(font, (state.page() + 1) + " / " + pages, 265, 235, 0xFF777777);
        if (state.rotationEnabled()) {
            long seconds = Math.max(0, (state.nextRotationAt() - System.currentTimeMillis() + 999) / 1_000);
            g.drawCenteredString(font, "Rotates in " + seconds + "s", imageWidth / 2, 18, 0xFFAAAAAA);
        }
        if (state.administrator() && (!compactAdmin || compactAdminOpen)) {
            String selected = state.choices().isEmpty() ? "No shops" : state.choices().get(Math.floorMod(state.selected(), state.choices().size()));
            int center = adminPanelX() + 64;
            g.drawCenteredString(font, "Shop Edit", center, 39, 0xFFFFCC33);
            g.drawCenteredString(font, ellipsize(selected, 105), center, 65, 0xFFAA7700);
        }
        if (!state.status().isBlank()) g.drawCenteredString(font, ellipsize(state.status(), 310), imageWidth / 2, 218,
                state.status().toLowerCase(java.util.Locale.ROOT).contains("bound") ? 0xFF228822 : 0xFFCC3333);
        g.drawString(font, ellipsize("Balance: " + Money.format(state.balance()), 190), 9, 235,
                state.balance() >= 0 ? 0xFF176B3A : 0xFFCC3333, false);
    }
    private String ellipsize(String text, int width) {
        if (font.width(text) <= width) return text;
        String dots = "...";
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width(dots))) + dots;
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick); super.render(g, mouseX, mouseY, partialTick); renderTooltip(g, mouseX, mouseY);
    }
}
