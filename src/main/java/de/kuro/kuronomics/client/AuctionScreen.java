package de.kuro.kuronomics.client;

import de.kuro.kuronomics.economy.Money;
import de.kuro.kuronomics.economy.tax.MarketplaceTaxService;
import de.kuro.kuronomics.menu.AuctionMenu;
import de.kuro.kuronomics.network.AuctionAction;
import java.util.*;
import java.math.BigDecimal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

public final class AuctionScreen extends AbstractContainerScreen<AuctionMenu> {
    private static final ScreenBackground CATALOG_BACKGROUND = new ScreenBackground("auction_catalog", 340, 250);
    private static final ScreenBackground SELL_BACKGROUND = new ScreenBackground("auction_sell", 340, 250);
    private static final ScreenBackground CLAIMS_BACKGROUND = new ScreenBackground("auction_claims", 340, 250);
    private static final ScreenBackground HISTORY_BACKGROUND = new ScreenBackground("auction_history", 340, 250);
    private final List<Button> sellControls = new ArrayList<>();
    private final List<Button> tabControls = new ArrayList<>();
    private final List<Button> pageControls = new ArrayList<>();
    private final List<Button> bidButtons = new ArrayList<>();
    private final List<Button> cancelButtons = new ArrayList<>();
    private final List<Button> inboxControls = new ArrayList<>();
    private final List<Button> claimButtons = new ArrayList<>();
    private EditBox starting, increment, cycles, bidAmount, search;
    private String pendingSearch = "", sentSearch = "";
    private int searchDelay;
    private String localError = "";
    public AuctionScreen(AuctionMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title); imageWidth = 340; imageHeight = 250; inventoryLabelY = 10_000;
    }
    private void send(String action, int value, long start, long increase) {
        PacketDistributor.sendToServer(new AuctionAction(menu.containerId, action, value, start, increase, UUID.randomUUID()));
    }
    private Button button(String label, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(Component.literal(label), b -> action.run())
                .bounds(leftPos + x, topPos + y, width, 20).build(AuctionButton::new));
    }
    private static final class AuctionButton extends Button {
        private AuctionButton(Builder builder) { super(builder); }
        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int border = active ? isHoveredOrFocused() ? 0xFFD19A63 : 0xFFAE754D : 0xFF76524B;
            int background = !active ? 0xFF603634 : isHoveredOrFocused() ? 0xFFA44D46 : 0xFF8B3C38;
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), border);
            g.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1, background);
            renderString(g, Minecraft.getInstance().font, active ? 0xFFF4E9DF : 0xFFC4AAAA);
        }
    }
    @Override protected void init() {
        super.init();
        sellControls.clear(); tabControls.clear(); pageControls.clear(); bidButtons.clear(); cancelButtons.clear();
        inboxControls.clear(); claimButtons.clear();
        String[] tabs = {"Auctions", "Sell", "My Auctions", "My Bids", "Inbox"}; int[] widths = {59, 42, 76, 56, 47};
        int x = (imageWidth - Arrays.stream(widths).sum() - (tabs.length - 1) * 3) / 2;
        for (int i = 0; i < tabs.length; i++) { int tab = i; tabControls.add(button(tabs[i], x, 6, widths[i], () -> send("tab", tab, 0, 0))); x += widths[i] + 3; }
        pageControls.add(button("<", 8, 130, 28, () -> send("previous", 0, 0, 0))); pageControls.add(button(">", 40, 130, 28, () -> send("next", 0, 0, 0)));
        for (int i = 0; i < 3; i++) { int row = i; bidButtons.add(button("Bid", 289, 38 + i * 30, 42, () -> bid(row))); }
        for (int i = 0; i < 3; i++) { int row = i; Button cancel = button("X", 310, 38 + i * 30, 21, () -> cancel(row));
            cancel.setTooltip(Tooltip.create(Component.literal("Remove this auction. The listing fee is not refunded."))); cancelButtons.add(cancel); }
        inboxControls.add(button("Claims", 8, 33, 62, () -> send("inbox_section", 0, 0, 0)));
        inboxControls.add(button("History", 74, 33, 66, () -> send("inbox_section", 1, 0, 0)));
        for (int i = 0; i < 3; i++) { int row = i; claimButtons.add(button("Claim", 279, 57 + i * 25, 52, () -> claim(row))); }
        search = field(8, 131, 92, "Search auctions"); search.setMaxLength(32); search.setValue(sentSearch);
        search.setResponder(value -> { pendingSearch = value; searchDelay = 6; });
        bidAmount = field(225, 131, 106, "Bid amount");
        starting = field(55, 65, 85, "Starting bid"); starting.setValue("1.00");
        increment = field(242, 65, 85, "Minimum increase"); increment.setValue("1.00");
        cycles = field(70, 90, 50, "Cycles"); cycles.setMaxLength(5); cycles.setValue(Integer.toString(menu.state.minCycles()));
        sellControls.add(button("Item", 8, 120, 42, () -> send("sell_type", 0, 0, 0)));
        sellControls.add(button("Property", 54, 120, 62, () -> send("sell_type", 1, 0, 0)));
        sellControls.add(button("Next", 120, 120, 42, () -> send("property_next", 0, 0, 0)));
        sellControls.add(button("Clear", 166, 120, 48, () -> send("clear", 0, 0, 0)));
        sellControls.add(button("Create listing", 218, 120, 114, this::list));
        updateVisibility();
    }
    private EditBox field(int x, int y, int width, String hint) {
        EditBox box = new EditBox(font, leftPos + x, topPos + y, width, 18, Component.literal(hint));
        box.setMaxLength(18); box.setHint(Component.literal(hint).withStyle(net.minecraft.ChatFormatting.DARK_GRAY)); addRenderableWidget(box); return box;
    }
    private void list() {
        try { send("list", Integer.parseInt(cycles.getValue()), Money.parseDisplay(starting.getValue()), Money.parseDisplay(increment.getValue()));
            starting.setTextColor(0xFFE0E0E0); increment.setTextColor(0xFFE0E0E0); cycles.setTextColor(0xFFE0E0E0); localError = "";
        } catch (RuntimeException error) { starting.setTextColor(0xFFFF5555); increment.setTextColor(0xFFFF5555); cycles.setTextColor(0xFFFF5555); localError = "Enter valid positive listing values."; }
    }
    private void bid(int row) {
        if (row < 0 || row >= menu.state.auctions().size()) return;
        try {
            var auction = menu.state.auctions().get(row); long amount = bidAmount.getValue().isBlank() ? auction.minimumNextBid() : Money.parseDisplay(bidAmount.getValue());
            PacketDistributor.sendToServer(new AuctionAction(menu.containerId, "bid", 0, 0, 0, auction.revision(), amount, auction.id(), UUID.randomUUID()));
            bidAmount.setTextColor(0xFFE0E0E0); localError = "";
        } catch (RuntimeException error) { bidAmount.setTextColor(0xFFFF5555); localError = "Enter a valid bid amount."; }
    }
    private void claim(int row) {
        if (row < 0 || row >= menu.state.claims().size()) return;
        var claim = menu.state.claims().get(row);
        PacketDistributor.sendToServer(new AuctionAction(menu.containerId, "claim", 0, 0, 0,
                0, 0, claim.id(), UUID.randomUUID()));
    }
    private void cancel(int row) {
        if (row < 0 || row >= menu.state.auctions().size()) return;
        var auction = menu.state.auctions().get(row);
        PacketDistributor.sendToServer(new AuctionAction(menu.containerId, "cancel", 0, 0, 0,
                auction.revision(), 0, auction.id(), UUID.randomUUID()));
    }
    @Override protected void containerTick() {
        super.containerTick();
        boolean searchable = menu.state.tab() == 0 || menu.state.tab() == 2 || menu.state.tab() == 3;
        if (searchable && searchDelay > 0 && --searchDelay == 0 && !pendingSearch.equals(sentSearch)) {
            sentSearch = pendingSearch;
            PacketDistributor.sendToServer(new AuctionAction(menu.containerId, "search", 0, 0, 0,
                    -1, 0, new UUID(0, 0), UUID.randomUUID(), sentSearch));
        }
        updateVisibility();
    }
    private void updateVisibility() {
        if (starting == null) return;
        boolean sell = menu.state.tab() == 1; starting.setVisible(sell); increment.setVisible(sell); cycles.setVisible(sell);
        for (int i = 0; i < tabControls.size(); i++) tabControls.get(i).active = menu.state.tab() != i;
        for (Button button : sellControls) button.visible = sell;
        if (sellControls.size() >= 2) {
            Button property = sellControls.get(1); property.active = sell && menu.state.propertyAvailable();
            property.setTooltip(menu.state.propertyAvailable() ? null : Tooltip.create(Component.translatable("kuronomics.property.unavailable")));
        }
        if (sellControls.size() >= 3) sellControls.get(2).active = sell && menu.state.sellAssetType() == 1 && menu.state.propertyCount() > 1;
        boolean searchable = menu.state.tab() == 0 || menu.state.tab() == 2 || menu.state.tab() == 3;
        search.setVisible(searchable);
        for (int i = 0; i < pageControls.size(); i++) {
            Button button = pageControls.get(i);
            button.visible = menu.state.tab() != 1;
            button.setY(topPos + (menu.state.tab() == 4 ? 136 : 130));
            button.setX(leftPos + (menu.state.tab() == 4 ? (i == 0 ? 8 : 40) : (i == 0 ? 104 : 136)));
        }
        boolean bidding = menu.state.tab() == 0 || menu.state.tab() == 3; bidAmount.setVisible(bidding);
        for (int i = 0; i < bidButtons.size(); i++) { Button button = bidButtons.get(i); button.visible = bidding && i < menu.state.auctions().size();
            boolean missingProvider = button.visible && menu.state.auctions().get(i).assetType().equals("PROPERTY") && !menu.state.propertyAvailable();
            button.active = button.visible && !missingProvider && menu.state.auctions().get(i).canBid();
            button.setTooltip(!button.visible || button.active ? null : Tooltip.create(Component.literal(switch (menu.state.auctions().get(i).bidBlockReason()) {
                case "OWN_AUCTION" -> "Unavailable ):";
                case "MISSING_FUNDS" -> "Missing funds";
                case "ACCOUNT_UNAVAILABLE" -> "Your account is unavailable";
                default -> "This auction is unavailable";
            })));
            if (missingProvider) button.setTooltip(Tooltip.create(Component.translatable("kuronomics.property.unavailable")));
        }
        for (int i = 0; i < cancelButtons.size(); i++) {
            Button button = cancelButtons.get(i);
            button.visible = menu.state.tab() == 2 && i < menu.state.auctions().size()
                    && menu.state.auctions().get(i).state().equals("ACTIVE");
            if (button.visible) {
                boolean missingProvider = menu.state.auctions().get(i).assetType().equals("PROPERTY") && !menu.state.propertyAvailable();
                boolean removable = !missingProvider && menu.state.auctions().get(i).currentBid() == 0;
                button.active = removable;
                button.setTooltip(Tooltip.create(Component.literal(removable
                        ? "Remove this auction. The listing fee is not refunded."
                        : "Auctions cannot be removed after the first bid.")));
                if (missingProvider) button.setTooltip(Tooltip.create(Component.translatable("kuronomics.property.unavailable")));
            }
        }
        boolean inbox = menu.state.tab() == 4;
        for (Button button : inboxControls) button.visible = inbox;
        for (int i = 0; i < claimButtons.size(); i++) {
            Button button = claimButtons.get(i); button.visible = inbox && menu.state.inboxSection() == 0 && i < menu.state.claims().size();
            boolean missingProvider = button.visible && menu.state.claims().get(i).assetType().equals("PROPERTY") && !menu.state.propertyAvailable();
            button.active = button.visible && !missingProvider;
            button.setTooltip(missingProvider ? Tooltip.create(Component.translatable("kuronomics.property.unavailable")) : null);
            if (button.visible) button.setMessage(Component.literal(menu.state.claims().get(i).assetType().equals("PROPERTY") ? "Claim Property" : "Claim"));
        }
    }
    @Override protected void renderBg(GuiGraphics g, float tick, int mouseX, int mouseY) {
        ScreenBackground background = switch (menu.state.tab()) {
            case 1 -> SELL_BACKGROUND;
            case 4 -> menu.state.inboxSection() == 0 ? CLAIMS_BACKGROUND : HISTORY_BACKGROUND;
            default -> CATALOG_BACKGROUND;
        };
        background.draw(g, leftPos, topPos);
    }
    @Override protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        var s = menu.state;
        if (s.tab() == 1) {
            if (s.sellAssetType() == 1) {
                g.drawString(font, "Property", 17, 39, 0xFFAA7700, false);
                g.drawString(font, font.plainSubstrByWidth(s.selectedPropertyName(), 245), 73, 39,
                        s.propertyCount() > 0 ? 0xFF333333 : 0xFFAA3333, false);
                g.drawString(font, s.propertyCount() + " eligible", 265, 39, 0xFF777777, false);
            } else if (!s.selected().isEmpty()) { g.renderItem(s.selected(), 17, 37); g.drawString(font, s.selected().getCount() + "x " + font.plainSubstrByWidth(s.selected().getHoverName().getString(), 270), 38, 42, 0xFF333333, false); }
            else g.drawString(font, "Click an inventory stack to select it", 17, 42, 0xFFFFD36B, true);
            g.drawString(font, "Start:", 17, 70, 0xFF777777, false); g.drawString(font, "Minimum increase:", 150, 70, 0xFF777777, false);
            g.drawString(font, "Duration:", 17, 95, 0xFF777777, false);
            g.drawString(font, "cycles (" + s.cycleMinutes() + " min each, " + s.minCycles() + "-" + s.maxCycles() + ")", 124, 95, 0xFF777777, false);
            String fee = "Listing fee: " + Money.format(s.fee());
            g.drawString(font, fee, 17, 109, 0xFFAA7700, false);
            g.drawString(font, "Sale tax: " + taxRate(s.saleTaxBasisPoints()) + " of final bid",
                    Math.min(326 - font.width("Sale tax: " + taxRate(s.saleTaxBasisPoints()) + " of final bid"), 32 + font.width(fee)),
                    109, 0xFFBB2222, false);
        } else if (s.tab() == 4) renderInbox(g, s);
        else {
            if (s.auctions().isEmpty()) g.drawCenteredString(font, search != null && !search.getValue().isBlank()
                    ? "No matching auctions." : s.tab() == 3 ? "No bids yet." : "No auctions.", imageWidth / 2, 76, 0xFF777777);
            for (int i = 0; i < s.auctions().size(); i++) {
                var a = s.auctions().get(i); int y = 36 + i * 30; int accent = a.participation().equals("HIGHEST") ? 0xFF229922 : a.participation().equals("OUTBID") ? 0xFFBB2222 : 0xFFCC9900;
                g.fill(8, y - 2, 11, y + 26, accent);
                boolean ended = !a.state().equals("ACTIVE");
                if ((s.tab() == 2 || s.tab() == 3) && ended && !a.participation().equals("ACTIVE")) {
                    int outcome = a.state().equals("COMPLETED") && a.participation().equals("HIGHEST") ? 0xFF229922
                            : a.state().equals("AWAITING_PAYMENT") && a.participation().equals("HIGHEST") ? 0xFFCC9900 : 0xFFBB2222;
                    g.renderOutline(8, y - 2, 324, 28, outcome);
                }
                if (a.assetType().equals("ITEM")) g.renderItem(a.item(), 14, y + 3);
                else g.drawString(font, "⌂", 18, y + 6, 0xFFAA7700, false);
                String assetLabel = a.assetType().equals("ITEM") ? a.item().getCount() + "x " + a.assetName() : a.assetName();
                g.drawString(font, ellipsize(assetLabel, 94), 35, y + 1, a.assetType().equals("PROPERTY") ? 0xFFAA7700 : 0xFF333333, false);
                String secondaryName;
                if (ended && s.tab() == 3) secondaryName = a.state().equals("COMPLETED") && a.participation().equals("HIGHEST")
                        ? "Won • paid" : a.participation().equals("OUTBID") ? "Lost • finished" : "Finished";
                else if (ended && s.tab() == 2) secondaryName = switch (a.state()) {
                    case "COMPLETED" -> "Sold • paid";
                    case "AWAITING_PAYMENT" -> "Finished • payment pending";
                    case "FAILED_NO_BIDS" -> "Finished • no bids";
                    case "FAILED_PAYMENT", "FAILED_PAYMENT_TIMEOUT" -> "Finished • payment failed";
                    case "FAILED_INVALID_ASSET" -> "Finished • invalid asset";
                    default -> "Finished";
                };
                else secondaryName = s.tab() == 2 && a.currentBid() > 0 ? "Bidder " + a.highestBidderName() : "by " + a.sellerName();
                g.drawString(font, ellipsize(secondaryName, 94), 35, y + 13, 0xFF777777, false);
                if (ended) g.drawString(font, "Expired", 235, y + 13, 0xFF777777, false);
                else {
                    long millis = Math.max(0, a.deadline() - System.currentTimeMillis()); long minutes = (millis + 59_999) / 60_000;
                    long cycleMillis = Math.max(60_000L, (long) s.cycleMinutes() * 60_000L); long remainingCycles = (millis + cycleMillis - 1) / cycleMillis;
                    g.drawString(font, ellipsize(minutes + "m/" + remainingCycles + "c", 49), 235, y + 13, 0xFF777777, false);
                }
                long shown = a.currentBid() > 0 ? a.currentBid() : a.startingBid();
                String grossLabel = (s.tab() == 2 ? "Gross " : "Current ") + Money.format(shown);
                g.drawString(font, font.plainSubstrByWidth(grossLabel, 90), 140, y + 1, 0xFFAA7700, false);
                if (s.tab() == 2 && a.currentBid() > 0) {
                    var breakdown = MarketplaceTaxService.calculate(MarketplaceTaxService.SourceType.AUCTION_SALE,
                            a.currentBid(), a.saleTaxBasisPoints());
                    g.drawString(font, font.plainSubstrByWidth("Net " + Money.format(breakdown.net()), 90), 140, y + 13, 0xFF229922, false);
                    g.drawString(font, "Tax " + taxRate(a.saleTaxBasisPoints()), 235, y + 1, 0xFFBB2222, false);
                } else {
                    g.drawString(font, font.plainSubstrByWidth("Next " + Money.format(a.minimumNextBid()), 90), 140, y + 13, 0xFFCC9900, false);
                    g.drawString(font, font.plainSubstrByWidth(a.highestBidderName(), 49), 235, y + 1, accent, false);
                }
            }
            int pages = Math.max(1, (s.total() + 2) / 3); g.drawString(font, (s.page() + 1) + " / " + pages, 172, 136, 0xFF777777, false);
        }
        if (s.tab() == 4) { int size = s.inboxSection() == 0 ? 3 : 2; int pages = Math.max(1, (s.total() + size - 1) / size); g.drawString(font, (s.page() + 1) + " / " + pages, 75, 142, 0xFF777777, false); }
        String status = localError.isBlank() ? s.status() : localError;
        boolean success = localError.isBlank() && (status.startsWith("Listed") || status.startsWith("Bid accepted")
                || status.startsWith("Claimed") || status.startsWith("Auction removed"));
        if (!status.isBlank()) g.drawCenteredString(font, font.plainSubstrByWidth(status, s.tab() == 4 ? 140 : 320), imageWidth / 2, s.tab() == 4 ? 142 : 151,
                success ? 0xFF229922 : 0xFFBB2222);
    }
    private void renderInbox(GuiGraphics g, de.kuro.kuronomics.network.AuctionSnapshot s) {
        if (s.inboxSection() == 0) {
            g.drawString(font, "Claimable auction assets", 148, 39, 0xFF229922, false);
            if (s.claims().isEmpty()) g.drawCenteredString(font, "No assets ready to claim.", imageWidth / 2, 82, 0xFF777777);
            for (int i = 0; i < s.claims().size(); i++) {
                var claim = s.claims().get(i); int y = 58 + i * 25;
                if (claim.assetType().equals("ITEM")) g.renderItem(claim.item(), 16, y); else g.drawString(font, "⌂", 20, y + 5, 0xFFAA7700, false);
                String claimName = claim.assetType().equals("ITEM") ? claim.item().getCount() + "x " + claim.assetName() : "Property: " + claim.assetName();
                g.drawString(font, font.plainSubstrByWidth(claimName, 190), 38, y, claim.assetType().equals("PROPERTY") ? 0xFFAA7700 : 0xFF333333, false);
                g.drawString(font, claim.reason(), 38, y + 11, claim.reason().startsWith("Purchased") ? 0xFF229922 : 0xFFBB8800, false);
            }
        } else {
            g.drawString(font, "Recent auction results", 148, 39, 0xFFAA7700, false);
            if (s.results().isEmpty()) g.drawString(font, "No recent results.", 16, 65, 0xFF777777, false);
            for (int i = 0; i < s.results().size(); i++) {
                var result = s.results().get(i); int y = 57 + i * 29;
                if (result.assetType().equals("ITEM")) g.renderItem(result.item(), 16, y); else g.drawString(font, "⌂", 20, y + 5, 0xFFAA7700, false);
                String resultName = result.assetType().equals("ITEM") ? result.item().getCount() + "x " + result.assetName() : result.assetName();
                g.drawString(font, ellipsize(resultName, 95), 38, y, result.assetType().equals("PROPERTY") ? 0xFFAA7700 : 0xFF333333, false);
                g.drawString(font, "Seller: " + font.plainSubstrByWidth(result.sellerName(), 55), 38, y + 11, 0xFF777777, false);
                int statusColor = result.status().equals("COMPLETED") ? 0xFF229922 : result.status().equals("CANCELLED") ? 0xFF777777 : 0xFFBB2222;
                String resultStatus = switch (result.status()) {
                    case "COMPLETED" -> "Sold";
                    case "FAILED_NO_BIDS" -> "No bids";
                    case "FAILED_PAYMENT", "FAILED_PAYMENT_TIMEOUT" -> "Payment failed";
                    case "FAILED_INVALID_ASSET" -> "Invalid asset";
                    case "CANCELLED" -> "Removed";
                    default -> result.status().replace('_', ' ');
                };
                g.drawString(font, resultStatus, 138, y, statusColor, false);
                String winner = result.winnerName().equals("No winner") ? "No winner" : "Winner: " + result.winnerName();
                g.drawString(font, font.plainSubstrByWidth(winner, 75), 138, y + 11, 0xFF777777, false);
                String bid = result.finalBid() > 0 ? Money.format(result.finalBid()) : "No bid";
                g.drawString(font, bid, 245, y, 0xFFCC9900, false);
                long ago = Math.max(0, (System.currentTimeMillis() - result.endedAt()) / 60_000L);
                String payment = switch (result.paymentStatus()) {
                    case "PAID" -> "Paid";
                    case "FAILED" -> "Payment failed";
                    case "REFUNDED" -> "Refunded";
                    case "NO_PAYMENT" -> "No payment";
                    case "NOT_CHARGED" -> "Not charged";
                    default -> result.paymentStatus().replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
                };
                int paymentColor = result.paymentStatus().equals("PAID") ? 0xFF229922
                        : result.paymentStatus().equals("FAILED") ? 0xFFBB2222 : 0xFF777777;
                g.drawString(font, font.plainSubstrByWidth(payment + " • " + ago + "m ago", 114), 218, y + 11, paymentColor, false);
            }
        }
    }
    private static String taxRate(int basisPoints) {
        return BigDecimal.valueOf(basisPoints, 2).stripTrailingZeros().toPlainString() + "%";
    }
    private String ellipsize(String text, int width) {
        if (font.width(text) <= width) return text;
        String dots = "...";
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width(dots))) + dots;
    }
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (getFocused() instanceof EditBox box && box.isFocused() && minecraft != null
                && minecraft.options.keyInventory.matches(keyCode, scanCode)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        super.render(g, mouseX, mouseY, partialTick);
        String balance = ellipsize("Balance: " + Money.format(menu.state.balance()), imageWidth - 16);
        int x = leftPos + 8, y = topPos + imageHeight + 4;
        g.fill(x - 4, y - 2, x + font.width(balance) + 4, y + font.lineHeight + 2, 0xB0000000);
        g.drawString(font, balance, x, y, menu.state.balance() >= 0 ? 0xFF96D9A4 : 0xFFFF9292, true);
        renderTooltip(g, mouseX, mouseY);
    }
}
