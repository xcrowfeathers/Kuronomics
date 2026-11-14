package de.kuro.kuronomics.client;

import de.kuro.kuronomics.menu.MarketMenu;
import de.kuro.kuronomics.network.*;
import de.kuro.kuronomics.economy.Money;
import java.time.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/** Inventory clicks move real stacks. The preview is only a display, not item storage. */
public final class MarketScreen extends AbstractContainerScreen<MarketMenu> {
    private static final ScreenBackground CATALOG = new ScreenBackground("market_catalog", 360, 226);
    private static final ScreenBackground CREATE = new ScreenBackground("market_create", 360, 226);
    private static final ScreenBackground PANEL = new ScreenBackground("market_panel", 360, 226);
    private static final ScreenBackground ASSIGN = new ScreenBackground("company_shop_selection", 220, 100);
    private final List<Button> rows = new ArrayList<>(), tabs = new ArrayList<>(), filters = new ArrayList<>();
    private Button previous, next, owner, create, savePrice, restock, withdraw, remove, back, confirm, assign;
    private EditBox search, price, quantity;
    private String lastView = "", lastDetail = "", pendingSearch = "", sentSearch = "", localError = "";
    private int searchDelay;
    private long serverOffset, lastServerTime = Long.MIN_VALUE;
    public MarketScreen(MarketMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title); boolean assigning = menu.state.view().equals("assign");
        imageWidth = assigning ? 220 : 360; imageHeight = assigning ? 100 : 226; inventoryLabelY = 10000;
    }
    private Button button(String label, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(label), ignored -> action.run()).bounds(leftPos + x, topPos + y, width, 20).build(FlatButton::new));
    }
    private EditBox field(int x, int y, int width, String label, int max) {
        var f = new EditBox(font, leftPos + x, topPos + y, width, 18, Component.literal(label)); f.setTextShadow(false); f.setMaxLength(max); f.setHint(Component.literal(label)); return addRenderableWidget(f);
    }
    @Override protected void init() {
        super.init(); rows.clear(); tabs.clear(); filters.clear(); lastView = ""; lastDetail = "";
        topPos = Math.max(0, (height - imageHeight - 28) / 2);
        tabs.add(button("Catalog", 8, 6, 65, () -> send("catalog")));
        tabs.add(button("My Listings", 78, 6, 80, () -> send("mine")));
        tabs.add(button("Create", 163, 6, 65, () -> send("create_view")));
        search = field(8, 32, 170, "Search items or sellers", 128); search.setValue(menu.state.query()); pendingSearch = sentSearch = menu.state.query();
        search.setResponder(value -> { pendingSearch = value; searchDelay = 6; });
        String[] names = {"All", "Players", "Companies"}; int[] x = {184, 236, 294}, widths = {48, 54, 58};
        for (int i = 0; i < 3; i++) { int filter = i; filters.add(button(names[i], x[i], 32, widths[i], () -> send("filter", null, filter, 0, ""))); }
        for (int i = 0; i < 4; i++) {
            int row = i;
            Button.Builder builder = Button.builder(Component.literal("Buy"), ignored -> rowAction(row, false)).bounds(leftPos + 288, topPos + 61 + i * 34, 56, 20);
            rows.add(addRenderableWidget(builder.build(b -> new BuyButton(b, () -> rowAction(row, true)))));
        }
        previous = button("<", 8, 194, 28, () -> send("previous")); next = button(">", 324, 194, 28, () -> send("next"));
        owner = button("Personal Account", 8, 32, 344, () -> send("owner"));
        price = field(200, 56, 100, "Unit price", 20);
        price.setHint(Component.literal("0").withStyle(style -> style.withColor(0x707070)));
        quantity = field(95, 82, 88, "Quantity", 10); quantity.setValue("1");
        quantity.setResponder(ignored -> localError = "");
        create = button("Create Listing", 254, 96, 98, () -> send("create", null, 0, 0, price.getValue()));
        savePrice = button("Change Price", 200, 106, 145, () -> detailAction("price", 0, price.getValue()));
        restock = button("Restock", 14, 106, 100, () -> detailAction("restock", parseQuantity(), ""));
        withdraw = button("Withdraw", 14, 134, 100, () -> detailAction("withdraw", parseQuantity(), ""));
        remove = button("Remove Listing", 242, 134, 103, () -> detailAction("remove_view", 0, ""));
        back = button("Back", 123, 134, 100, () -> {
            if (menu.state.view().equals("remove")) detailAction("edit", 0, ""); else send("mine");
        });
        confirm = button("Confirm Purchase", 112, 140, 140, () -> detailAction(menu.state.view().equals("remove") ? "remove" : "buy", menu.state.view().equals("remove") ? 0 : parseQuantity(), ""));
        assign = button("Assign Shop", 12, 66, 196, () -> send("assign")); update();
    }
    private void send(String action) { send(action, null, 0, 0, ""); }
    private void send(String action, UUID id, int amount, long revision, String text) {
        localError = ""; PacketDistributor.sendToServer(new MarketAction(menu.containerId, action, id == null ? new UUID(0,0) : id, amount, revision, text, UUID.randomUUID()));
    }
    private void detailAction(String action, int amount, String text) { var o = menu.state.detail(); if (o != null && amount != -1) send(action, o.id(), amount, o.revision(), text); }
    private int parseQuantity() {
        try { int count = Integer.parseInt(quantity.getValue().strip()); if (count <= 0) throw new NumberFormatException(); return count; }
        catch (NumberFormatException e) { localError = "Enter a positive whole-number quantity."; return -1; }
    }
    private void rowAction(int index, boolean right) {
        if (index >= menu.state.offers().size()) return; var o = menu.state.offers().get(index);
        if (menu.state.view().equals("owned")) { if (!right) send("edit", o.id(), 0, o.revision(), ""); }
        else send(right ? "buy_view" : "buy", o.id(), right ? 0 : 1, o.revision(), "");
    }
    @Override protected void containerTick() {
        super.containerTick();
        if (menu.state.view().equals("catalog") && searchDelay > 0 && --searchDelay == 0 && !pendingSearch.equals(sentSearch)) { sentSearch = pendingSearch; send("search", null, 0, 0, sentSearch); }
        update();
    }
    private boolean open() {
        var s = menu.state; int hour = Instant.ofEpochMilli(System.currentTimeMillis() + serverOffset).atZone(ZoneId.of(s.zone())).getHour();
        return s.enabled() && (s.opening() == s.closing() || (s.opening() < s.closing() ? hour >= s.opening() && hour < s.closing() : hour >= s.opening() || hour < s.closing()));
    }
    private void update() {
        if (search == null) return; var s = menu.state; String view = s.view(); var detail = s.detail();
        if (lastServerTime != s.serverTime()) { lastServerTime = s.serverTime(); serverOffset = s.serverTime() - System.currentTimeMillis(); }
        String detailId = detail == null ? "" : detail.id().toString();
        if (!lastView.equals(view) || !lastDetail.equals(detailId)) {
            lastView = view; lastDetail = detailId; localError = "";
            price.setValue(view.equals("create") || detail == null ? "" : Money.formatPrice(detail.price())); quantity.setValue("1");
        }
        boolean catalog = view.equals("catalog"), owned = view.equals("owned"), listing = catalog || owned, edit = view.equals("edit"), buying = view.equals("buy"), removing = view.equals("remove");
        tabs.forEach(b -> b.visible = !view.equals("assign")); search.setVisible(catalog);
        for (int i = 0; i < filters.size(); i++) { filters.get(i).visible = catalog; filters.get(i).active = i != s.filter(); }
        owner.visible = owned || view.equals("create") || view.equals("assign"); owner.active = s.ownerChoices() > 1;
        owner.setMessage(Component.literal(clip((view.equals("assign") ? "" : "Seller: ") + s.ownerName(), view.equals("assign") ? 180 : view.equals("create") ? 224 : 320)));
        if (owner.visible) owner.setTooltip(Tooltip.create(Component.literal(s.ownerName())));
        owner.setPosition(leftPos + (view.equals("assign") ? 12 : 8), topPos + (view.equals("create") ? 96 : view.equals("assign") ? 36 : 32)); owner.setWidth(view.equals("assign") ? 196 : view.equals("create") ? 240 : 344);
        price.setVisible(view.equals("create") || edit); price.setY(topPos + (edit ? 82 : 56)); price.setWidth(edit ? 145 : 100);
        quantity.setVisible(edit || buying); quantity.setPosition(leftPos + (buying ? 128 : 95), topPos + (buying ? 88 : 82));
        create.visible = view.equals("create"); create.active = !s.selected().isEmpty();
        savePrice.visible = restock.visible = withdraw.visible = remove.visible = edit;
        savePrice.active = restock.active = detail != null && detail.edit(); withdraw.active = remove.active = detail != null && detail.withdraw(); quantity.setEditable(true);
        previous.visible = next.visible = listing; previous.active = s.page() > 0; next.active = (long)(s.page() + 1) * 4 < s.total();
        for (int i = 0; i < rows.size(); i++) {
            var b = rows.get(i); b.visible = listing && i < s.offers().size(); if (!b.visible) continue;
            var o = s.offers().get(i); b.setMessage(Component.literal(owned ? "Edit" : "Buy")); b.active = owned ? o.edit() : o.buy() && open();
            b.setTooltip(Tooltip.create(Component.literal(owned ? "Manage listing" : "Left-click: buy 1\nRight-click: choose quantity")));
        }
        back.visible = edit || buying || removing; back.setPosition(leftPos + (buying || removing ? 14 : 123), topPos + (buying || removing ? 140 : 134));
        confirm.visible = buying || removing; confirm.active = removing ? detail != null && detail.withdraw() : detail != null && detail.buy() && open(); confirm.setMessage(Component.literal(removing ? "Remove Listing" : "Confirm Purchase"));
        assign.visible = view.equals("assign"); assign.active = s.ownerChoices() > 0;
    }
    @Override protected void renderBg(GuiGraphics g, float tick, int mouseX, int mouseY) {
        ScreenBackground background = switch (menu.state.view()) {
            case "assign" -> ASSIGN;
            case "create" -> CREATE;
            case "edit", "buy", "remove" -> PANEL;
            default -> CATALOG;
        };
        background.draw(g, leftPos, topPos);
    }
    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        var s = menu.state; String view = s.view();
        if (view.equals("catalog") || view.equals("owned")) {
            for (int i = 0; i < s.offers().size(); i++) {
                var o = s.offers().get(i); int y = 56 + i * 34;
                g.renderItem(o.item(), 12, y + 6);
                g.drawString(font, clip(o.item().getHoverName().getString(), 246), 34, y + 2, 0xFF252525, false);
                g.drawString(font, clip(Money.format(o.price()) + " · " + (o.stock() == 0 ? "Out of Stock" : "Stock: " + o.stock()), 246), 34, y + 12, 0xFF36543D, false);
                g.drawString(font, clip((o.seller().isCompany() ? "[C] " : "[P] ") + o.sellerName(), 245), 34, y + 22, 0xFF555555, false);
            }
            if (s.offers().isEmpty()) centered(g, "No matching listings", imageWidth / 2, 100, 0xFF555555);
            centered(g, (s.page() + 1) + " / " + Math.max(1, (s.total() + 3) / 4), imageWidth / 2, 200, 0xFF333333);
            if (view.equals("owned")) g.drawString(font, "Listings: " + s.total() + " / " + s.limit(), 44, 200, 0xFF555555, false);
        } else if (view.equals("assign")) {
            centered(g, "Company Shop", imageWidth / 2, 14, 0xFF333333);
        } else if (view.equals("create")) {
            g.drawString(font, "Unit price", 204, 44, 0xFF333333, false);
            g.drawString(font, clip(s.selected().isEmpty() ? "Select an inventory stack" : s.selected().getHoverName().getString(), 150), 43, 58, 0xFF333333, false);
        } else if (s.detail() != null) {
            var o = s.detail(); g.renderItem(o.item(), 20, 40);
            g.drawString(font, clip(o.item().getHoverName().getString(), 298), 44, 41, 0xFF333333, false);
            g.drawString(font, "Stock: " + o.stock() + " · " + clip(o.sellerName(), 190), 44, 54, 0xFF555555, false);
            if (view.equals("buy")) {
                g.drawString(font, "Unit price: " + Money.format(o.price()), 20, 70, 0xFF333333, false);
                g.drawString(font, "Quantity", 20, 92, 0xFF333333, false);
                try { int count = Integer.parseInt(quantity.getValue().strip()); if (count <= 0) throw new NumberFormatException();
                    g.drawString(font, count + " × " + Money.format(o.price()) + " = " + Money.format(Math.multiplyExact(o.price(), (long)count)), 20, 118, 0xFF36543D, false);
                } catch (RuntimeException invalid) { /* Show validation errors below the panel when the player confirms. */ }
            } else if (view.equals("edit")) { g.drawString(font, "Quantity", 14, 86, 0xFF333333, false); g.drawString(font, "Unit price", 204, 70, 0xFF333333, false); }
            else if (view.equals("remove")) { centered(g, "Remove this listing?", 180, 82, 0xFF993333); centered(g, "All remaining stock must fit in your inventory.", 180, 100, 0xFF555555); }
        }
        if (view.equals("catalog")) g.drawString(font, Money.format(s.balance()), 237, 12, 0xFF36543D, false);
    }
    @Override public void render(GuiGraphics g, int x, int y, float tick) {
        update(); super.render(g, x, y, tick); renderTooltip(g, x, y);
        var s = menu.state;
        String hours = "";
        if (!s.view().equals("assign")) {
            hours = (open() ? "Open · " : "Marketplace Closed · ")
                    + String.format(Locale.ROOT, "%02d:00–%02d:00 %s", s.opening(), s.closing(), s.zone());
        }
        String status = localError.isEmpty() ? s.status() : localError;
        int footerY = Math.max(0, Math.min(topPos + imageHeight + 4, height - font.lineHeight - 2));
        int messageY = footerY + (hours.isEmpty() ? 0 : font.lineHeight + 3), available = Math.max(40, width - 16);
        if (!status.isEmpty() && messageY + font.lineHeight > height - 2) {
            // At high GUI scales, there may only be room for one line below the panel.
            // Keep the message and hours on that line, with the full text on hover.
            status = status + (hours.isEmpty() ? "" : " · " + hours); hours = ""; messageY = footerY;
        }
        if (!hours.isEmpty()) {
            centered(g, clip(hours, available), leftPos + imageWidth / 2, footerY, open() ? 0xFFAAC6A9 : 0xFFFFAAAA);
            if (font.width(hours) > available && y >= footerY && y < footerY + font.lineHeight) g.renderTooltip(font, Component.literal(hours), x, y);
        }
        if (!status.isEmpty()) {
            centered(g, clip(status, available), leftPos + imageWidth / 2, messageY, 0xFFFFAAAA);
            if (font.width(status) > available && y >= messageY && y < messageY + font.lineHeight) g.renderTooltip(font, Component.literal(status), x, y);
        }
        if (s.view().equals("catalog") || s.view().equals("owned")) for (int i = 0; i < s.offers().size(); i++) {
            int iy = topPos + 62 + i * 34; if (x >= leftPos + 12 && x < leftPos + 28 && y >= iy && y < iy + 16) g.renderTooltip(font, s.offers().get(i).item(), x, y);
        } else if (s.detail() != null && x >= leftPos + 20 && x < leftPos + 36 && y >= topPos + 40 && y < topPos + 56) g.renderTooltip(font, s.detail().item(), x, y);
    }
    private String clip(String text, int width) { return font.width(text) <= width ? text : font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…"; }
    private void centered(GuiGraphics g, String text, int center, int y, int color) { g.drawString(font, text, center - font.width(text) / 2, y, color, false); }
    private class FlatButton extends Button {
        FlatButton(Builder builder) { super(builder); }
        @Override public void renderString(GuiGraphics g, net.minecraft.client.gui.Font f, int color) {
            String text = clip(getMessage().getString(), getWidth() - 8);
            g.drawString(f, text, getX() + (getWidth() - f.width(text)) / 2, getY() + (getHeight() - 8) / 2, color, false);
        }
    }
    private final class BuyButton extends FlatButton {
        private final Runnable quantity;
        BuyButton(Builder builder, Runnable quantity) { super(builder); this.quantity = quantity; }
        @Override public boolean mouseClicked(double x, double y, int button) {
            if (button == 1 && active && visible && clicked(x, y)) { playDownSound(minecraft.getSoundManager()); quantity.run(); return true; }
            return super.mouseClicked(x, y, button);
        }
    }
}
