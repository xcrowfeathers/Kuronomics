package de.kuro.kuronomics.client;

import de.kuro.kuronomics.economy.Money;
import de.kuro.kuronomics.menu.ServerShopEditorMenu;
import de.kuro.kuronomics.network.ServerShopAction;
import java.math.BigDecimal;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ServerShopEditorScreen extends AbstractContainerScreen<ServerShopEditorMenu> {
    private static final ScreenBackground BACKGROUND = new ScreenBackground("server_shop_editor", 300, 250);
    private static final ScreenBackground OVERVIEW = new ScreenBackground("server_shop", 330, 250);
    private final java.util.List<Button> editButtons = new java.util.ArrayList<>(), moveUp = new java.util.ArrayList<>(), moveDown = new java.util.ArrayList<>();
    private boolean overview;
    private Button previousPage, nextPage;
    private EditBox quantity, buy, sell, stock, rotationDuration, rotationCount;
    private int seenSelected = Integer.MIN_VALUE, seenTotal = Integer.MIN_VALUE;
    private String localError = "";
    public ServerShopEditorScreen(ServerShopEditorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title); imageWidth = 300; imageHeight = 250; inventoryLabelX = 240; inventoryLabelY = 159;
    }
    private Button button(String text, int x, int y, int width, String action) {
        Button button = addRenderableWidget(Button.builder(Component.literal(text), b -> send(action)).bounds(leftPos + x, topPos + y, width, 20)
                .build(builder -> new TerminalButton(builder, TerminalButton.SERVER_SHOP)));
        return button;
    }
    private EditBox field(int x, int y, int width, String hint) {
        EditBox box = new EditBox(font, leftPos + x, topPos + y, width, 18, Component.literal(hint));
        box.setMaxLength(20); box.setHint(Component.literal(hint).withStyle(net.minecraft.ChatFormatting.DARK_GRAY)); addRenderableWidget(box); return box;
    }
    @Override protected void init() {
        overview = menu.state.overview(); imageWidth = overview ? 330 : 300;
        super.init(); inventoryLabelY = overview ? 10000 : 159;
        editButtons.clear(); moveUp.clear(); moveDown.clear();
        if (overview) {
            for (int i = 0; i < 6; i++) {
                final int row = i; int x = 13 + i % 2 * 161, y = 69 + i / 2 * 64;
                editButtons.add(overviewButton("Edit", x, y, 70, () -> offerAction("edit", row)));
                moveUp.add(overviewButton("↑", x + 76, y, 32, () -> offerAction("move_up", row)));
                moveDown.add(overviewButton("↓", x + 112, y, 32, () -> offerAction("move_down", row)));
            }
            previousPage = button("<", 209, 229, 30, "page_previous"); nextPage = button(">", 291, 229, 30, "page_next");
            button("Done", 9, 229, 58, "cancel"); updateOverview(); return;
        }
        quantity = field(77, 43, 49, "Quantity"); buy = field(155, 43, 55, "Buy price"); sell = field(235, 43, 55, "Sell price"); stock = field(155, 69, 74, "Shared stock");
        rotationDuration = field(77, 108, 70, ""); rotationCount = field(155, 108, 45, "");
        button("Mode", 8, 5, 48, "mode").setTooltip(Tooltip.create(Component.literal("Cycle who may buy or sell.")));
        button("Stock", 236, 67, 54, "stock_mode").setTooltip(Tooltip.create(Component.literal("Switch between infinite and shared stock.")));
        button("Rotation", 10, 106, 60, "rotation").setTooltip(Tooltip.create(Component.literal("Enable or disable timed offer rotation.")));
        
        button("Pool", 220, 106, 70, "pool").setTooltip(Tooltip.create(Component.literal("Toggle every offer in this shop into or out of the rotation pool.")));
        button("<", 10, 137, 28, "previous"); button(">", 42, 137, 28, "next");
        button("Remove", 242, 198, 50, "remove").setTooltip(Tooltip.create(Component.literal("Remove this entry from the shop immediately.")));
        button("Back", 8, 222, 54, "back").setTooltip(Tooltip.create(Component.literal("Return to the overview and discard unsaved changes.")));
        button("Save", 242, 222, 50, "save").setTooltip(Tooltip.create(Component.literal("Save this item and shop settings, then return to the overview."))); refreshFields();
    }
    private Button overviewButton(String label, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(label), b -> action.run()).bounds(leftPos + x, topPos + y, width, 18)
                .build(builder -> new TerminalButton(builder, TerminalButton.SERVER_SHOP)));
    }
    private void offerAction(String action, int index) {
        if (index >= menu.state.offers().size()) return;
        PacketDistributor.sendToServer(new ServerShopAction(menu.containerId, action, 1, 0, 0, 0,
                menu.state.revision(), 0, 0, menu.state.offers().get(index).id(), UUID.randomUUID()));
    }
    private void updateOverview() {
        var s = menu.state;
        for (int i = 0; i < editButtons.size(); i++) {
            boolean present = i < s.offers().size();
            editButtons.get(i).visible = moveUp.get(i).visible = moveDown.get(i).visible = present;
            moveUp.get(i).active = s.page() * 6 + i > 0;
            moveDown.get(i).active = s.page() * 6 + i + 1 < s.total();
        }
        previousPage.active = s.page() > 0;
        nextPage.active = s.page() < Math.min(s.total(), de.kuro.kuronomics.servershop.ServerShop.MAX_OFFERS - 1) / 6;
    }
    private void renderOverview(GuiGraphics g) {
        var s = menu.state;
        g.drawCenteredString(font, "Edit " + s.name(), 165, 9, 0xFFFFCC33);
        g.drawCenteredString(font, "Click an empty slot to add an item", 165, 20, 0xFF777777);
        for (int i = 0; i < s.offers().size(); i++) {
            var o = s.offers().get(i); int x = 13 + i % 2 * 161, y = 35 + i / 2 * 64;
            g.renderItem(o.item(), x, y);
            g.drawString(font, font.plainSubstrByWidth(o.item().getHoverName().getString(), 122), x + 21, y, 0xFF333333, false);
            g.drawString(font, "x" + o.quantity() + " · " + (o.stockMode().equals("INFINITE") ? "Infinite" : "Stock " + o.stock()), x + 21, y + 11, 0xFF777777, false);
            if (!s.mode().equals("PLAYER_SELLS")) g.drawString(font, Money.format(o.buyPrice()), x, y + 22, 0xFF229922, false);
            if (!s.mode().equals("PLAYER_BUYS")) { String price = Money.format(o.sellPrice()); g.drawString(font, price, x + 145 - font.width(price), y + 22, 0xFF176B3A, false); }
        }
        int pages = Math.min(s.total(), de.kuro.kuronomics.servershop.ServerShop.MAX_OFFERS - 1) / 6 + 1;
        g.drawCenteredString(font, (s.page() + 1) + " / " + pages, 265, 235, 0xFF777777);
        if (!s.status().isBlank()) g.drawCenteredString(font, font.plainSubstrByWidth(s.status(), 310), 165, 218, 0xFF777777);
    }
    @Override public boolean mouseClicked(double x, double y, int button) {
        if (overview && button == 0) {
            for (int i = menu.state.offers().size(); i < 6; i++) {
                int cardX = leftPos + 8 + i % 2 * 161, cardY = topPos + 31 + i / 2 * 64;
                if (x >= cardX && x < cardX + 153 && y >= cardY && y < cardY + 58) { send("new"); return true; }
            }
        }
        return super.mouseClicked(x, y, button);
    }
    private long parse(EditBox box, String label) {
        try {
            long value = Money.parseDisplay(box.getValue());
            if (value < 0) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException ex) { box.setTextColor(0xFFFF5555); throw new IllegalArgumentException(label + " must be a valid non-negative amount."); }
    }
    private int parseQuantity() {
        try { int value = Integer.parseInt(quantity.getValue().strip()); if (value <= 0) throw new NumberFormatException(); return value; }
        catch (NumberFormatException ex) { quantity.setTextColor(0xFFFF5555); throw new IllegalArgumentException("Quantity must be a positive whole number."); }
    }
    private long parseStock() {
        try { long value = Long.parseLong(stock.getValue().strip()); if (value < 0) throw new NumberFormatException(); return value; }
        catch (NumberFormatException ex) { stock.setTextColor(0xFFFF5555); throw new IllegalArgumentException("Shared stock must be zero or more."); }
    }
    private long parseRotationDuration() {
        try { long value = Long.parseLong(rotationDuration.getValue().strip()); if (value <= 0) throw new NumberFormatException(); return Math.multiplyExact(value, 1_000L); }
        catch (NumberFormatException | ArithmeticException ex) { rotationDuration.setTextColor(0xFFFF5555); throw new IllegalArgumentException("Rotation seconds must be a positive whole number."); }
    }
    private int parseRotationCount() {
        try { int value = Integer.parseInt(rotationCount.getValue().strip()); if (value <= 0) throw new NumberFormatException(); return value; }
        catch (NumberFormatException ex) { rotationCount.setTextColor(0xFFFF5555); throw new IllegalArgumentException("Active offers must be a positive whole number."); }
    }
    private void send(String action) {
        try {
            boolean fields = !overview && !java.util.Set.of("cancel", "back", "remove").contains(action);
            if (fields) resetFieldColors();
            int parsedQuantity = !fields ? 1 : parseQuantity();
            long parsedBuy = !fields ? 0 : parse(buy, "Buy price");
            long parsedSell = !fields ? 0 : parse(sell, "Sell price");
            long parsedStock = !fields ? 0 : parseStock();
            long duration = !fields ? 0 : parseRotationDuration();
            int count = !fields ? 0 : parseRotationCount();
            PacketDistributor.sendToServer(new ServerShopAction(menu.containerId, action, parsedQuantity, parsedBuy, parsedSell, parsedStock,
                    menu.state.revision(), duration, count, new UUID(0, 0), UUID.randomUUID()));
            localError = "";
        } catch (IllegalArgumentException | ArithmeticException ex) {
            localError = ex.getMessage() == null ? "Check the highlighted values." : ex.getMessage();
        }
    }
    private void resetFieldColors() {
        quantity.setTextColor(0xFFE0E0E0); buy.setTextColor(0xFFE0E0E0); sell.setTextColor(0xFFE0E0E0); stock.setTextColor(0xFFE0E0E0);
        rotationDuration.setTextColor(0xFFE0E0E0); rotationCount.setTextColor(0xFFE0E0E0);
    }
    @Override protected void containerTick() {
        super.containerTick();
        if (overview != menu.state.overview()) { localError = ""; seenSelected = Integer.MIN_VALUE; rebuildWidgets(); }
        if (overview) { updateOverview(); return; }
        if (seenSelected != menu.state.selected() || seenTotal != menu.state.total()) refreshFields();
    }
    private void refreshFields() {
        if (quantity == null) return;
        if (menu.state.offers().isEmpty()) {
            quantity.setValue("1"); buy.setValue("0.00"); sell.setValue("0.00"); stock.setValue("0");
        } else {
            var offer = menu.state.offers().getFirst();
            quantity.setValue(Integer.toString(offer.quantity())); buy.setValue(BigDecimal.valueOf(offer.buyPrice(), 2).toPlainString());
            sell.setValue(BigDecimal.valueOf(offer.sellPrice(), 2).toPlainString()); stock.setValue(Long.toString(offer.stock()));
        }
        rotationDuration.setValue(Long.toString(Math.max(1, menu.state.rotationDuration() / 1_000)));
        rotationCount.setValue(Integer.toString(Math.max(1, menu.state.rotationCount())));
        seenSelected = menu.state.selected(); seenTotal = menu.state.total();
    }
    @Override protected void renderBg(GuiGraphics g, float tick, int mouseX, int mouseY) {
        if (overview) { OVERVIEW.draw(g, leftPos, topPos); return; }
        BACKGROUND.draw(g, leftPos, topPos);
        drawInventorySlots(g, leftPos + 70, topPos + 168);
    }
    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        if (overview) { renderOverview(g); return; }
        var state = menu.state; g.drawCenteredString(font, "Edit " + state.name(), imageWidth / 2, 5, 0xFFFFCC33);
        g.drawCenteredString(font, "Mode: " + state.mode().replace('_', ' '), imageWidth / 2, 17, 0xFF55DD55);
        g.drawString(font, "Item", 24, 79, 0xFF777777, false); g.drawString(font, "Quantity", 77, 31, 0xFF777777, false);
        g.drawString(font, "Buy price", 155, 31, 0xFF229922, false); g.drawString(font, "Sell price", 235, 31, 0xFFCC3333, false);
        String stockMode = state.offers().isEmpty() ? "INFINITE" : state.offers().getFirst().stockMode();
        g.drawString(font, "Stock: " + stockMode, 77, 73, stockMode.equals("INFINITE") ? 0xFF229922 : 0xFFAA7700, false);
        boolean pool = state.total() > 0 && state.offers().stream().allMatch(de.kuro.kuronomics.network.ServerShopSnapshot.OfferView::rotationPool);
        g.drawString(font, state.rotationEnabled() ? "Rotation on" : "Rotation off", 10, 96, state.rotationEnabled() ? 0xFF229922 : 0xFFCC3333, false);
        g.drawString(font, "Seconds", 77, 96, 0xFF777777, false);
        g.drawString(font, "Count", 155, 96, 0xFF777777, false);
        g.drawString(font, pool ? "All pooled" : "Pool off", 220, 96, pool ? 0xFFAA7700 : 0xFF777777, false);
        g.drawString(font, state.offers().isEmpty() || state.offers().getFirst().item().isEmpty() ? "New offer" : "Offer " + (state.selected() + 1) + " / " + state.total(), 10, 127, 0xFF777777, false);
        String warning = localError.isBlank() ? state.status() : localError;
        if (!warning.isBlank()) g.drawCenteredString(font, font.plainSubstrByWidth(warning, 280), imageWidth / 2, 159,
                localError.isBlank() && warning.toLowerCase(java.util.Locale.ROOT).contains("selected") ? 0xFF229922 : 0xFFCC3333);
    }
    private void drawInventorySlots(GuiGraphics g, int x, int y) {
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            g.renderOutline(x + col * 18 - 1, y + row * 18 - 1, 18, 18, 0xFF888888);
        for (int col = 0; col < 9; col++) g.renderOutline(x + col * 18 - 1, y + 58 - 1, 18, 18, 0xFF777777);
    }
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (getFocused() instanceof EditBox box && box.isFocused() && minecraft != null
                && minecraft.options.keyInventory.matches(keyCode, scanCode)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick); super.render(g, mouseX, mouseY, partialTick); renderTooltip(g, mouseX, mouseY);
    }
}
