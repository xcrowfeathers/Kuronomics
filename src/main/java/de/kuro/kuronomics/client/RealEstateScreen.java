package de.kuro.kuronomics.client;

import de.kuro.kuronomics.economy.Money;
import de.kuro.kuronomics.economy.tax.MarketplaceTaxService;
import de.kuro.kuronomics.menu.RealEstateMenu;
import de.kuro.kuronomics.network.*;
import java.math.BigDecimal;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

public final class RealEstateScreen extends AbstractContainerScreen<RealEstateMenu> {
    private static final ScreenBackground BACKGROUND = new ScreenBackground("real_estate", 360, 230);
    private static final ScreenBackground DETAIL_BACKGROUND = new ScreenBackground("real_estate_detail", 344, 178);
    private static final ScreenBackground UNAVAILABLE_BACKGROUND = new ScreenBackground("real_estate_unavailable", 344, 178);
    private static final ScreenBackground INBOX_ROW_BACKGROUND = new ScreenBackground("real_estate_inbox_row", 344, 28);
    private static final ScreenBackground CARD_BACKGROUND = new ScreenBackground("real_estate_card", 344, 30);
    private static final int CATALOG_ROWS = 4, CARD_TOP = 62, CARD_STEP = 33, CARD_HEIGHT = 30;
    private static final float CATALOG_PRICE_SCALE = 1.25F, DETAIL_PRICE_SCALE = 1.75F;
    private static final int DARK_BLUE = 0xFF1F4F70;
    private static final int TEXT = 0xFF333333, MUTED = 0xFF667784, PRICE_GREEN = 0xFF238A45, ERROR_RED = 0xFFBB2222;
    private final List<Button> tabs = new ArrayList<>(), pageButtons = new ArrayList<>(), rowButtons = new ArrayList<>(), sellButtons = new ArrayList<>(), detailButtons = new ArrayList<>();
    private EditBox search, listingName, location, price, description;
    private Button sortButton;
    private Button buyButton;
    private String pendingSearch = "", sentSearch = "", localError = "";
    private int searchDelay; private UUID loadedDetail;

    public RealEstateScreen(RealEstateMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title); imageWidth = 360; imageHeight = 230; inventoryLabelY = 10_000;
    }
    @Override protected void init() {
        super.init();
        // Resizing the window or changing GUI scale rebuilds the screen. Clear the old widgets first
        // so later visibility changes don't update buttons that aren't on screen anymore.
        tabs.clear(); pageButtons.clear(); rowButtons.clear(); sellButtons.clear(); detailButtons.clear();
        loadedDetail = null;
        int x = 8; String[] names = {"Catalog", "Sell Property", "My Listings", "Inbox"}; int[] widths = {58, 82, 75, 50};
        for (int i = 0; i < names.length; i++) { int tab = i; tabs.add(button(names[i], x, 6, widths[i], () -> send("tab", tab))); x += widths[i] + 4; }
        search = field(8, 36, 142, 24, "Search name, place, seller");
        search.setValue(menu.state.query()); pendingSearch = sentSearch = menu.state.query(); searchDelay = 0;
        search.setResponder(value -> { pendingSearch = value; searchDelay = 7; });
        sortButton = button("Newest", 154, 36, 100, this::cycleSort);
        pageButtons.add(button("<", 256, 200, 28, () -> send("previous", 0))); pageButtons.add(button(">", 324, 200, 28, () -> send("next", 0)));
        for (int i = 0; i < CATALOG_ROWS; i++) { int row = i; rowButtons.add(button("Details", 292, CARD_TOP + 5 + i * CARD_STEP, 59, () -> details(row))); }

        listingName = field(104, 92, 208, 28, "Listing name"); location = field(104, 115, 208, 28, "Location");
        price = field(104, 138, 208, 24, "Sale price"); description = field(104, 161, 208, 42, "Description (optional)");
        sellButtons.add(button("<", 8, 55, 28, () -> send("property_previous", 0)));
        sellButtons.add(button(">", 324, 55, 28, () -> send("property_next", 0)));
        sellButtons.add(button("Create Listing", (imageWidth - 130) / 2, 185, 130, this::create));

        detailButtons.add(button("Save changes", 189, 184, 92, this::update));
        Button cancel = button("Cancel listing", 285, 184, 67, this::cancel); cancel.setTooltip(Tooltip.create(Component.literal("Remove the listing and unlock the property."))); detailButtons.add(cancel);
        detailButtons.add(button("Close", 292, 36, 60, () -> send("close_details", 0)));
        buyButton = button("Buy", 280, 159, 72, this::buy);
        updateVisibility();
    }
    private Button button(String text, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(text), ignored -> action.run()).bounds(leftPos + x, topPos + y, width, 20)
                .build(builder -> new TerminalButton(builder, TerminalButton.REAL_ESTATE)));
    }
    private EditBox field(int x, int y, int width, int max, String hint) {
        EditBox box = new EditBox(font, leftPos + x, topPos + y, width, 18, Component.literal(hint)); box.setMaxLength(max);
        box.setHint(Component.literal(hint).withStyle(net.minecraft.ChatFormatting.DARK_GRAY)); return addRenderableWidget(box);
    }
    private void send(String action, int value) { send(action, value, 0, -1, RealEstateAction.NONE, "", "", ""); }
    private void send(String action, int value, long amount, long revision, UUID target, String first, String second, String third) {
        PacketDistributor.sendToServer(new RealEstateAction(menu.containerId, action, value, amount, revision, target, UUID.randomUUID(), first, second, third));
    }
    private void cycleSort() { send("sort", (menu.state.sort() + 1) % 5); }
    private void details(int row) { if (row < menu.state.listings().size()) send("details", 0, 0, -1, menu.state.listings().get(row).id(), "", "", ""); }
    private void create() {
        try { long amount = Money.parseDisplay(price.getValue()); send("create", 0, amount, -1, RealEstateAction.NONE,
                    listingName.getValue(), location.getValue(), description.getValue()); clearLocalError(); }
        catch (RuntimeException invalid) { fieldError("Enter a valid positive sale price."); }
    }
    private void update() {
        var detail = menu.state.detail(); if (detail == null) return;
        try { long amount = Money.parseDisplay(price.getValue()); send("update", 0, amount, detail.revision(), detail.id(),
                    listingName.getValue(), location.getValue(), description.getValue()); clearLocalError(); }
        catch (RuntimeException invalid) { fieldError("Enter a valid positive sale price."); }
    }
    private void cancel() { var detail = menu.state.detail(); if (detail != null) send("cancel", 0, 0, detail.revision(), detail.id(), "", "", ""); }
    private void buy() { var detail = menu.state.detail(); if (detail != null) send("buy", 0, 0, detail.revision(), detail.id(), "", "", ""); }
    private void fieldError(String message) { localError = message; price.setTextColor(0xFFFF5555); }
    private void clearLocalError() { localError = ""; price.setTextColor(0xFFE0E0E0); }

    @Override protected void containerTick() {
        super.containerTick();
        if (menu.state.tab() == 0 && searchDelay > 0 && --searchDelay == 0 && !pendingSearch.equals(sentSearch)) {
            sentSearch = pendingSearch; send("search", 0, 0, -1, RealEstateAction.NONE, sentSearch, "", "");
        }
        var detail = menu.state.detail();
        if (detail != null && !detail.id().equals(loadedDetail)) {
            loadedDetail = detail.id(); listingName.setValue(detail.name()); location.setValue(detail.location()); price.setValue(Money.formatPrice(detail.price())); description.setValue(detail.description());
        } else if (detail == null && loadedDetail != null) loadedDetail = null;
        updateVisibility();
    }
    private void updateVisibility() {
        if (search == null) return; int tab = menu.state.tab(); boolean detail = menu.state.detail() != null; boolean sell = tab == 1;
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).active = i != tab && (i == 3 || menu.state.available());
            tabs.get(i).setTooltip(i == 3 || menu.state.available() ? null
                    : Tooltip.create(Component.translatable("kuronomics.property.unavailable")));
        }
        search.setVisible(tab == 0 && !detail); sortButton.visible = tab == 0 && !detail;
        search.setEditable(menu.state.available()); sortButton.active = menu.state.available();
        sortButton.setMessage(Component.literal(switch (menu.state.sort()) { case 1 -> "Price: low-high"; case 2 -> "Price: high-low"; case 3 -> "Size: small-large"; case 4 -> "Size: large-small"; default -> "Newest"; }));
        boolean paged = (tab == 0 || tab == 2 || tab == 3) && !detail;
        int pageSize = tab == 3 ? 5 : CATALOG_ROWS;
        for (int i = 0; i < pageButtons.size(); i++) {
            Button button = pageButtons.get(i); button.visible = paged;
            button.active = paged && (i == 0 ? menu.state.page() > 0 : (menu.state.page() + 1) * pageSize < menu.state.total());
        }
        for (int i = 0; i < rowButtons.size(); i++) {
            boolean visible = (tab == 0 || tab == 2) && !detail && i < menu.state.listings().size();
            rowButtons.get(i).visible = visible; rowButtons.get(i).active = visible;
        }
        for (int i = 0; i < sellButtons.size(); i++) {
            Button button = sellButtons.get(i); button.visible = sell && !detail;
            button.active = button.visible && menu.state.available() && menu.state.property() != null
                    && (i == 2 || menu.state.propertyCount() > 1);
        }
        boolean edit = detail && tab == 2; for (Button button : detailButtons) button.visible = detail && (button == detailButtons.get(2) || edit);
        buyButton.visible = detail && tab == 0;
        buyButton.active = buyButton.visible && menu.state.available() && !menu.state.detail().owner();
        buyButton.setTooltip(!buyButton.visible || buyButton.active ? null : Tooltip.create(Component.literal(
                menu.state.detail().owner() ? "You cannot buy your own property." : "This listing is unavailable.")));
        boolean formVisible = edit || sell && menu.state.available() && menu.state.property() != null;
        listingName.setVisible(formVisible); location.setVisible(formVisible); price.setVisible(formVisible); description.setVisible(formVisible);
        boolean formEditable = menu.state.available() && (edit || sell && menu.state.property() != null);
        listingName.setEditable(formEditable); location.setEditable(formEditable); price.setEditable(formEditable); description.setEditable(formEditable);
    }

    @Override protected void renderBg(GuiGraphics g, float tick, int mouseX, int mouseY) {
        BACKGROUND.draw(g, leftPos, topPos);
        if (menu.state.detail() != null) DETAIL_BACKGROUND.draw(g, leftPos + 8, topPos + 34);
        else if (menu.state.tab() == 1) {
            (menu.state.available() ? DETAIL_BACKGROUND : UNAVAILABLE_BACKGROUND).draw(g, leftPos + 8, topPos + 34);
        } else if (menu.state.tab() == 3) {
            for (int i = 0; i < 5; i++) INBOX_ROW_BACKGROUND.draw(g, leftPos + 8, topPos + 39 + i * 30);
        } else {
            for (int i = 0; i < menu.state.listings().size(); i++) CARD_BACKGROUND.draw(g, leftPos + 8, topPos + CARD_TOP + i * CARD_STEP);
        }
    }
    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        var s = menu.state;
        if (!s.available()) {
            g.drawCenteredString(font, Component.translatable("kuronomics.property.unavailable"), imageWidth / 2, 64, 0xFFBB2222);
            if (s.tab() != 1) g.drawCenteredString(font, "Property market browsing is unavailable.", imageWidth / 2, 81, 0xFF777777);
        } else if (s.detail() != null) renderDetail(g, s.detail());
        else switch (s.tab()) { case 0, 2 -> renderListings(g); case 1 -> renderSell(g); case 3 -> renderInbox(g); default -> {} }
        if (s.detail() == null && (s.tab() == 0 || s.tab() == 2 || s.tab() == 3)) {
            int size = s.tab() == 3 ? 5 : CATALOG_ROWS, pages = Math.max(1, (s.total() + size - 1) / size);
            g.drawCenteredString(font, (s.page() + 1) + " / " + pages, 306, 206, MUTED);
            if (s.tab() == 0 || s.tab() == 3) g.drawString(font,
                    ellipsize("Balance: " + Money.format(s.balance()), 225), 8, 206,
                    s.balance() >= 0 ? PRICE_GREEN : ERROR_RED, false);
        }
        String message = localError.isBlank() ? s.status() : localError;
        boolean success = localError.isBlank() && message.contains("success");
        if (!message.isBlank()) g.drawCenteredString(font, font.plainSubstrByWidth(message, 320), imageWidth / 2, 220,
                success ? PRICE_GREEN : ERROR_RED);
    }
    private void renderListings(GuiGraphics g) {
        var s = menu.state; if (s.listings().isEmpty()) g.drawCenteredString(font,
                s.tab() == 2 ? "You have no active listings" : s.query().isBlank() ? "No properties currently listed" : "No matching properties", imageWidth / 2, 111, MUTED);
        for (int i = 0; i < s.listings().size(); i++) {
            var v = s.listings().get(i); int y = CARD_TOP + 4 + i * CARD_STEP;
            g.drawString(font, ellipsize(v.name(), 102), 44, y, DARK_BLUE, false);
            g.drawString(font, ellipsize(v.location(), 102), 44, y + 13, MUTED, false);
            String amount = Money.format(v.price());
            float priceScale = Math.min(CATALOG_PRICE_SCALE, 78.0F / Math.max(1, font.width(amount)));
            int amountX = 285 - Math.round(font.width(amount) * priceScale);
            g.drawString(font, ellipsize("by " + v.seller(), 52), 151, y, MUTED, false);
            g.drawString(font, ellipsize(v.width() + " × " + v.length(), 52), 151, y + 13, MUTED, false);
            float priceY = CARD_TOP + i * CARD_STEP + (CARD_HEIGHT - font.lineHeight * priceScale) / 2.0F;
            drawScaledString(g, amount, amountX, priceY, PRICE_GREEN, priceScale);
        }
    }
    private void renderSell(GuiGraphics g) {
        var s = menu.state; if (s.property() == null) { g.drawCenteredString(font, "You have no eligible properties", imageWidth / 2, 111, ERROR_RED); return; }
        var p = s.property(); g.drawCenteredString(font, font.plainSubstrByWidth(p.name(), 245), imageWidth / 2, 42, DARK_BLUE);
        g.drawCenteredString(font, (s.propertyIndex() + 1) + " / " + s.propertyCount() + "  •  " + p.width() + " × " + p.length(), imageWidth / 2, 57, MUTED);
        renderTaxPreview(g, s.taxBasisPoints(), 17, 78);
        g.drawString(font, "Name", 17, 97, MUTED, false); g.drawString(font, "Location", 17, 120, MUTED, false); g.drawString(font, "Sale price", 17, 143, MUTED, false);
        g.drawString(font, "Description", 17, 166, MUTED, false);
    }
    private void renderTaxPreview(GuiGraphics g, int taxBps, int x, int y) {
        String rate = BigDecimal.valueOf(taxBps, 2).stripTrailingZeros().toPlainString() + "%";
        String gross = "—", tax = "—", net = "—";
        try {
            long amount = Money.parseDisplay(price.getValue());
            var breakdown = MarketplaceTaxService.calculate(MarketplaceTaxService.SourceType.REAL_ESTATE_SALE, amount, taxBps);
            gross = Money.format(breakdown.gross()); tax = Money.format(breakdown.tax()); net = Money.format(breakdown.net());
        } catch (RuntimeException ignored) {}
        drawFinancials(g, x, y, gross, "Tax " + rate + " " + tax, net);
    }
    private void renderDetail(GuiGraphics g, RealEstateSnapshot.ListingView v) {
        g.drawString(font, ellipsize(v.name(), 255), 17, 43, DARK_BLUE, false);
        if (menu.state.tab() == 2) {
            g.drawString(font, "Name", 17, 97, MUTED, false); g.drawString(font, "Location", 17, 120, MUTED, false); g.drawString(font, "Price", 17, 143, MUTED, false); g.drawString(font, "Description", 17, 166, MUTED, false);
            String rate = BigDecimal.valueOf(v.taxBasisPoints(), 2).stripTrailingZeros().toPlainString() + "%";
            var breakdown = MarketplaceTaxService.calculate(MarketplaceTaxService.SourceType.REAL_ESTATE_SALE, v.price(), v.taxBasisPoints());
            drawFinancials(g, 17, 69, Money.format(breakdown.gross()), "Tax " + rate + " " + Money.format(breakdown.tax()), Money.format(breakdown.net()));
        } else {
            g.drawString(font, ellipsize("Seller: " + v.seller(), 200), 17, 65, MUTED, false);
            g.drawString(font, ellipsize("Location: " + v.location(), 325), 17, 80, MUTED, false);
            g.drawString(font, "Size: " + v.width() + " × " + v.length(), 17, 95, MUTED, false);
            g.drawString(font, ellipsize(v.description().isBlank() ? "No description" : v.description(), 325), 17, 119, TEXT, false);
            drawScaledString(g, Money.format(v.price()), 17, 151, PRICE_GREEN, DETAIL_PRICE_SCALE);
        }
    }
    private void drawScaledString(GuiGraphics g, String text, float x, float y, int color, float scale) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1.0F);
        g.drawString(font, text, 0, 0, color, false);
        g.pose().popPose();
    }
    private void drawFinancials(GuiGraphics g, int x, int y, String gross, String tax, String net) {
        String grossText = "Gross " + gross; g.drawString(font, grossText, x, y, PRICE_GREEN, false);
        int taxX = x + font.width(grossText) + 10; g.drawString(font, tax, taxX, y, ERROR_RED, false);
        int netX = taxX + font.width(tax) + 10; g.drawString(font, "Net " + net, netX, y, PRICE_GREEN, false);
    }
    private void renderInbox(GuiGraphics g) {
        if (menu.state.inbox().isEmpty()) { g.drawCenteredString(font, "No market notifications", imageWidth / 2, 105, 0xFF777777); return; }
        for (int i = 0; i < menu.state.inbox().size(); i++) {
            var entry = menu.state.inbox().get(i); int y = 44 + i * 30;
            int color = switch (entry.type()) { case PROPERTY_LISTED, PROPERTY_SOLD -> 0xFF228822; case PROPERTY_PURCHASED, LISTING_CANCELLED, LISTING_INVALIDATED, PROPERTY_TRANSFER_FAILED -> 0xFFBB2222; case PRICE_CHANGED -> 0xFFAA7700; };
            g.drawString(font, ellipsize(entry.listingName(), 155), 16, y, color, false);
            g.drawString(font, ellipsize(entry.message(), 210), 16, y + 12, 0xFF777777, false);
            if (entry.amount() != null) { String amount = Money.format(entry.amount()); g.drawString(font, amount, 344 - font.width(amount), y,
                    entry.type() == de.kuro.kuronomics.estate.market.inbox.RealEstateEventType.PROPERTY_PURCHASED ? ERROR_RED : PRICE_GREEN, false); }
            if (!entry.otherPlayer().isBlank()) g.drawString(font, ellipsize(entry.otherPlayer(), 90), 253, y + 12, 0xFF777777, false);
        }
    }
    private String ellipsize(String text, int width) {
        if (font.width(text) <= width) return text;
        String dots = "...";
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width(dots))) + dots;
    }
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (getFocused() instanceof EditBox box && box.isFocused() && minecraft != null && minecraft.options.keyInventory.matches(keyCode, scanCode)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) { renderBackground(g, mouseX, mouseY, partialTick); super.render(g, mouseX, mouseY, partialTick); renderTooltip(g, mouseX, mouseY); }
}
