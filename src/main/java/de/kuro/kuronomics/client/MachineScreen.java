package de.kuro.kuronomics.client;

import de.kuro.kuronomics.block.MachineKind;
import de.kuro.kuronomics.economy.Money;
import de.kuro.kuronomics.item.Prices;
import de.kuro.kuronomics.menu.MachineMenu;
import de.kuro.kuronomics.mailbox.*;
import de.kuro.kuronomics.network.MachineAction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

public final class MachineScreen extends AbstractContainerScreen<MachineMenu> {
    private record ButtonPalette(int background, int hover, int disabled, int border, int hoverBorder,
                                 int disabledBorder, int text, int disabledText) {}
    private static final ButtonPalette SHOP_BUTTONS = new ButtonPalette(
            0xFF9A775A, 0xFFB18C6B, 0xFF6E5B4B, 0xFFD1B494, 0xFFE4C9A7, 0xFF8B7664, 0xFF2E2119, 0xFFB8A899);
    private static final ButtonPalette ATM_BUTTONS = new ButtonPalette(
            0xFF5369AF, 0xFF6B80C3, 0xFF4A527C, 0xFF9FAEE0, 0xFFC1CFFF, 0xFF6D769E, 0xFFF5F7FF, 0xFFBBC4DF);
    private static final ButtonPalette VENDING_BUTTONS = new ButtonPalette(
            0xFF89524B, 0xFFA46358, 0xFF67413D, 0xFFC18A76, 0xFFD8A18B, 0xFF86625A, 0xFFF8EDE2, 0xFFC7ABA3);
    private static final ButtonPalette PRINTER_BUTTONS = new ButtonPalette(
            0xFF755FA5, 0xFF8B75B9, 0xFF5C5278, 0xFFB9A5D7, 0xFFD2BEEB, 0xFF817493, 0xFFF8F2FF, 0xFFC8BBD6);
    private static final class MachineButton extends Button {
        private final ButtonPalette palette;
        private MachineButton(Builder builder, ButtonPalette palette) { super(builder); this.palette = palette; }
        @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            boolean highlighted = active && isHoveredOrFocused();
            int border = !active ? palette.disabledBorder() : highlighted ? palette.hoverBorder() : palette.border();
            int background = !active ? palette.disabled() : highlighted ? palette.hover() : palette.background();
            g.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), border);
            g.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1, background);
            renderString(g, Minecraft.getInstance().font, active ? palette.text() : palette.disabledText());
        }
    }
    private static int restoreMouseX = Integer.MIN_VALUE, restoreMouseY = Integer.MIN_VALUE;
    private EditBox amount;
    private int mailboxPage;
    private int lastMouseX, lastMouseY;
    private final ResourceLocation texture;
    public MachineScreen(MachineMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title); imageWidth = menu.layout.width(); imageHeight = menu.layout.height();
        inventoryLabelY = menu.layout.inventoryY() - 12;
        texture = ResourceLocation.fromNamespaceAndPath("kuronomics", "textures/screens/" + menu.layout.texture() + ".png");
    }
    private void send(String action, long value) {
        if (action.equals("page")) { restoreMouseX = lastMouseX; restoreMouseY = lastMouseY; }
        PacketDistributor.sendToServer(new MachineAction(menu.containerId, action, value, java.util.UUID.randomUUID()));
    }
    private Button button(String label, int x, int y, int width, Runnable action) {
        ButtonPalette palette = switch (menu.kind) {
            case ATM -> ATM_BUTTONS;
            case SHOP, ADMIN_SHOP, SERVER_SHOP, COMPANY_SHOP -> SHOP_BUTTONS;
            case VENDING, ADMIN_VENDING, STACK_VENDING -> VENDING_BUTTONS;
            case PRINTER -> PRINTER_BUTTONS;
            default -> throw new IllegalStateException("No buttons for " + menu.kind);
        };
        return addRenderableWidget(Button.builder(Component.literal(label), b -> action.run())
                .bounds(leftPos + x, topPos + y, width, 20).build(builder -> new MachineButton(builder, palette)));
    }
    @Override protected void init() {
        super.init();
        if (menu.kind == MachineKind.ATM) {
            switch (menu.page) {
                case 0 -> {
                    if (!menu.suspended) {
                        button("Deposit", 14, 72, 103, () -> send("page", 1));
                        button("Withdraw", 14, 99, 103, () -> send("page", 2));
                        button("Check Management", 14, 126, 103, () -> send("page", 3));
                    }
                    button("Inbox", 144, 126, 130, () -> send("page", 4));
                }
                case 1, 2 -> {
                    long[] values = menu.page == 1 ? new long[]{1,5,10,50,100,500} : new long[]{1,5,10,50,100,500,1000,5000,10000,50000,100000};
                    for (int i = 0; i < values.length; i++) {
                        long value = values[i];
                        button(java.text.NumberFormat.getIntegerInstance(java.util.Locale.GERMANY).format(value) + "$",
                                10 + i / 5 * 63, 30 + i % 5 * 27, 51, () -> send(menu.page == 1 ? "deposit" : "withdraw", value));
                    }
                    if (menu.page == 1) button("Dp. All", 73, 138, 51, () -> send("deposit", 0));
                    button("Back", 176, 138, 51, () -> send("page", 0));
                }
                case 3 -> {
                    button("Redeem", 34, 55, 40, () -> send("redeem", 0));
                    button("Confirm", 250, 55, 40, () -> send("validate", 0));
                    button("Print", 97, 55, 51, () -> send("blank", 0));
                    button("<", 259, 154, 30, () -> send("page", 0));
                }
                case 4 -> {
                    button("Previous", 14, 154, 64, () -> mailboxPage = Math.max(0, mailboxPage - 1));
                    button("Next", 84, 154, 50, () -> mailboxPage = Math.min(mailboxPages() - 1, mailboxPage + 1));
                    button("Back", 239, 154, 45, () -> send("page", 0));
                }
            }
        } else if (menu.kind == MachineKind.PRINTER && menu.manager) {
            amount = new EditBox(font, leftPos + 34, topPos + 10, 110, 20, Component.literal("Price")); amount.setMaxLength(18); addRenderableWidget(amount);
            amount.setValue(menu.price < 0 ? "0" : java.math.BigDecimal.valueOf(menu.price, 2).toPlainString());
            amount.setTooltip(Tooltip.create(Component.literal("Price in dollars. Enter 0 to disable printing.")));
            button("✔", 151, 10, 17, () -> { try { send("price", Money.parseDisplay(amount.getValue())); amount.setTextColor(0xFFE0E0E0); } catch (IllegalArgumentException | ArithmeticException ignored) { amount.setTextColor(0xFFFF5555); } })
                    .setTooltip(Tooltip.create(Component.literal("Save printer price")));
        } else if ((menu.kind.shop() || menu.kind.vending()) && menu.manager) {
            if (menu.removalAllowed) button("X", imageWidth + 3, imageHeight - 30, 35, () -> send("remove", 0));
            if (menu.kind.vending()) button("$", imageWidth + 3, imageHeight - 52, 35, () -> send("collect", 0));
        }
        restoreMouseAfterPageChange();
    }
    private void restoreMouseAfterPageChange() {
        if (minecraft == null || restoreMouseX == Integer.MIN_VALUE) return;
        int x = restoreMouseX, y = restoreMouseY; restoreMouseX = restoreMouseY = Integer.MIN_VALUE;
        minecraft.execute(() -> org.lwjgl.glfw.GLFW.glfwSetCursorPos(minecraft.getWindow().getWindow(),
                x * minecraft.getWindow().getGuiScale(), y * minecraft.getWindow().getGuiScale()));
    }
    @Override protected void renderBg(GuiGraphics g, float tick, int mouseX, int mouseY) {
        g.blit(texture, leftPos, topPos, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight);
        if (menu.kind == MachineKind.ATM && menu.page == 4) {
            g.fill(leftPos + 8, topPos + 7, leftPos + imageWidth - 8, topPos + 149, 0xEEEBEBEB);
            g.fill(leftPos + 8, topPos + 7, leftPos + imageWidth - 8, topPos + 8, 0xFF777777);
        }
        if (menu.layout.playerInventory()) drawInventorySlots(g, leftPos + menu.layout.inventoryX(), topPos + menu.layout.inventoryY());
    }
    @Override protected void renderLabels(GuiGraphics g, int x, int y) {
        if (menu.kind == MachineKind.ATM) {
            int balanceColor = menu.balance < 0 ? 0xFF990033 : 0xFF339900;
            if (menu.page == 0) {
                g.drawString(font, "Balance:", 14, 9, 0xFF3C3C3C, false);
                g.drawString(font, Money.format(menu.balance), 59, 9, menu.balance < 0 ? 0xFF990000 : 0xFF009900, false);
                g.drawString(font, "Welcome,", 14, 27, 0xFF3C3C3C, false);
                g.drawString(font, minecraft.player == null ? "" : minecraft.player.getName().getString(), 59, 27, 0xFF009999, false);
                String date = java.time.LocalDate.now().toString();
                g.drawString(font, date, imageWidth - 10 - font.width(date), 9, 0xFF3C3C3C, false);
                if (menu.balance < 0) g.drawString(font, "You are currently in debt.", 14, 45, 0xFF660000, false);
                if (menu.suspended) {
                    g.drawString(font, "Your account has been suspended.", 59, 72, 0xFFFF0033, false);
                    g.drawString(font, "Reason:", 14, 162, 0xFF3C3C3C, false);
                    g.drawString(font, font.plainSubstrByWidth(menu.suspensionReason, 270), 14, 171, 0xFF996600, false);
                }
            } else if (menu.page == 1 || menu.page == 2) {
                g.drawString(font, menu.page == 1 ? "Deposit" : "Withdraw", 10, 12, 0xFF3C3C3C, false);
                g.drawString(font, "Bal:", 73, 12, 0xFF3C3C3C, false);
                g.drawString(font, Money.format(menu.balance), 100, 12, balanceColor, false);
            } else if (menu.page == 3) {
                g.drawString(font, "Check Management", 7, 10, 0xFF333333, false);
                g.drawString(font, "Redeem Check:", 7, 37, 0xFF3C3C3C, false);
                g.drawString(font, "Validate Check:", 214, 37, 0xFF3C3C3C, false);
                g.drawString(font, "Balance:", 133, 10, 0xFF3C3C3C, false);
                g.drawString(font, Money.format(menu.balance), 178, 10, balanceColor, false);
                g.drawString(font, "Print Empty Check:", 97, 37, 0xFF3C3C3C, false);
            } else if (menu.page == 4) {
                renderMailbox(g);
            }
        } else if (menu.kind == MachineKind.PASS_READER && menu.slots.getFirst().hasItem()) {
            var data = Prices.data(menu.slots.getFirst().getItem());
            String[] keys = {"name", "ship", "status", "date", "entry"};
            String[] labels = {"Name:", "City:", "Occup:", "Created:", "Record:"};
            int[] lines = {17, 26, 35, 44, 62};
            for (int i = 0; i < keys.length; i++) {
                g.drawString(font, labels[i], i == 4 ? 19 : 64, lines[i], 0xFF3C3C3C, false);
                g.drawString(font, font.plainSubstrByWidth(data.getString(keys[i]), i == 4 ? 170 : 130), i == 4 ? 64 : 109, lines[i], 0xFF6666FF, false);
            }
        } else if (menu.kind.shop() || menu.kind.vending()) {
            String heading = menu.kind.shop() ? "Shop" : "Vending Machine";
            if (menu.kind.shop()) {
                g.drawString(font, heading, (imageWidth - font.width(heading)) / 2, 8, 0xFF444444, false);
                g.drawString(font, Money.format(menu.balance), 6, 70, menu.balance < 0 ? 0xFF990033 : 0xFF00A000, false);
                g.drawString(font, menu.kind.wholeStack() ? "stk" : "x1", 150, 70, 0xFF999999, false);
            } else {
                g.drawString(font, heading, (imageWidth - font.width(heading)) / 2, 5, 0xFF999999, false);
                if (menu.manager) g.drawString(font, font.plainSubstrByWidth("Profit: " + Money.format(menu.profit), 140), 8, 111, 0xFF999999, false);
                g.drawString(font, menu.kind.wholeStack() ? "stk" : "x1", 159, 111, 0xFF999999, false);
            }
        }
    }
    private int mailboxPages() { return Math.max(1, (menu.mailbox.size() + 2) / 3); }
    private void renderMailbox(GuiGraphics g) {
        mailboxPage = Math.min(mailboxPage, mailboxPages() - 1);
        String heading = "Inbox";
        g.drawString(font, heading, (imageWidth - font.width(heading)) / 2, 12, 0xFF444444, false);
        if (menu.mailbox.isEmpty()) {
            g.drawCenteredString(font, "No messages yet.", imageWidth / 2, 79, 0xFF777777); return;
        }
        int first = mailboxPage * 3;
        for (int row = 0; row < 3 && first + row < menu.mailbox.size(); row++) {
            MailboxView entry = menu.mailbox.get(first + row); int y = 29 + row * 39;
            g.drawString(font, title(entry.type()), 14, y, titleColor(entry.type()), false);
            String time = java.time.format.DateTimeFormatter.ofPattern("dd.MM HH:mm")
                    .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(entry.createdAt()));
            g.drawString(font, time, imageWidth - 14 - font.width(time), y, 0xFF888888, false);
            renderMailboxSummary(g, entry, 14, y + 10, imageWidth - 28);
            if (entry.balance() != null)
                g.drawString(font, "Balance: " + Money.format(entry.balance()), 14, y + 19, 0xFFAA7700, false);
            else if (!entry.subject().isBlank())
                g.drawString(font, font.plainSubstrByWidth(entry.subject(), imageWidth - 28), 14, y + 19, 0xFF888888, false);
            g.fill(14, y + 31, imageWidth - 14, y + 32, 0xFFD0D0D0);
        }
        String page = (mailboxPage + 1) + " / " + mailboxPages();
        g.drawString(font, page, 145, 160, 0xFF777777, false);
    }
    private static String title(MailboxEventType type) {
        return switch (type) {
            case PAYDAY_REPORT -> "PayDay Report";
            case DEPOSIT -> "Deposit received";
            case WITHDRAWAL -> "Withdrawal completed";
            case PAYMENT_SENT -> "Payment sent";
            case PAYMENT_RECEIVED -> "Payment received";
            case RENT_WARNING -> "Rent warning";
            case AUCTION_OUTBID -> "Auction outbid";
            case AUCTION_NO_BIDS -> "Auction ended";
            case AUCTION_WON -> "Auction won";
            case AUCTION_PAYMENT_COMPLETED -> "Auction paid";
            case AUCTION_PAYMENT_TIMEOUT -> "Auction payment failed";
            case AUCTION_SOLD -> "Auction sold";
            case AUCTION_RETURNED -> "Auction returned";
            case AUCTION_CLAIM_READY -> "Claim ready";
            case SYSTEM -> "System message";
        };
    }
    private static int titleColor(MailboxEventType type) {
        return switch (type) {
            case DEPOSIT, PAYMENT_RECEIVED -> 0xFF229922;
            case WITHDRAWAL, PAYMENT_SENT, RENT_WARNING, AUCTION_OUTBID, AUCTION_NO_BIDS, AUCTION_PAYMENT_TIMEOUT, AUCTION_RETURNED -> 0xFFBB2222;
            case AUCTION_PAYMENT_COMPLETED, AUCTION_SOLD, AUCTION_CLAIM_READY -> 0xFF229922;
            case AUCTION_WON -> 0xFFBB8800;
            case PAYDAY_REPORT -> 0xFFBB8800;
            case SYSTEM -> 0xFF666666;
        };
    }
    private void renderMailboxSummary(GuiGraphics g, MailboxView entry, int x, int y, int width) {
        String prefix = switch (entry.type()) {
            case PAYDAY_REPORT -> "Net change: ";
            case DEPOSIT -> entry.subject().isBlank() ? "" : entry.subject() + " • ";
            case WITHDRAWAL -> entry.subject().isBlank() ? "" : entry.subject() + " • ";
            case PAYMENT_SENT -> "To " + entry.otherPlayer() + " • ";
            case PAYMENT_RECEIVED -> "From " + entry.otherPlayer() + " • ";
            case RENT_WARNING -> entry.subject() + " • ";
            case AUCTION_OUTBID, AUCTION_NO_BIDS, AUCTION_WON, AUCTION_PAYMENT_COMPLETED, AUCTION_PAYMENT_TIMEOUT, AUCTION_SOLD, AUCTION_RETURNED, AUCTION_CLAIM_READY -> entry.subject() + " • ";
            case SYSTEM -> entry.subject();
        };
        String clipped = font.plainSubstrByWidth(prefix, Math.max(0, width - 70));
        g.drawString(font, clipped, x, y, entry.type() == MailboxEventType.RENT_WARNING
                || entry.type() == MailboxEventType.AUCTION_OUTBID || entry.type() == MailboxEventType.AUCTION_NO_BIDS
                || entry.type() == MailboxEventType.AUCTION_PAYMENT_TIMEOUT || entry.type() == MailboxEventType.AUCTION_RETURNED ? 0xFFAA2222 : 0xFF666666, false);
        if (entry.amount() == null) return;
        String sign = switch (entry.type()) {
            case DEPOSIT, PAYMENT_RECEIVED -> "+";
            case WITHDRAWAL, PAYMENT_SENT -> "-";
            case RENT_WARNING, AUCTION_OUTBID, AUCTION_NO_BIDS, AUCTION_WON, AUCTION_PAYMENT_COMPLETED, AUCTION_PAYMENT_TIMEOUT, AUCTION_SOLD, AUCTION_RETURNED, AUCTION_CLAIM_READY -> "";
            case PAYDAY_REPORT -> entry.amount() >= 0 ? "+" : "-";
            case SYSTEM -> "";
        };
        long magnitude = entry.amount() < 0 && entry.amount() != Long.MIN_VALUE ? -entry.amount() : entry.amount();
        g.drawString(font, sign + Money.format(magnitude), x + font.width(clipped), y, 0xFFCC9900, false);
    }
    @Override public void render(GuiGraphics g, int x, int y, float tick) {
        lastMouseX = x; lastMouseY = y;
        super.render(g, x, y, tick); renderTooltip(g, x, y);
        if (menu.kind == MachineKind.ATM && menu.page == 4 && x >= leftPos + 8 && x < leftPos + imageWidth - 8
                && y >= topPos + 27 && y < topPos + 145) {
            int row = (y - topPos - 29) / 39, index = mailboxPage * 3 + row;
            if (row >= 0 && row < 3 && index < menu.mailbox.size())
                g.renderComponentTooltip(font, mailboxTooltip(menu.mailbox.get(index)), x, y);
        }
    }
    private void drawInventorySlots(GuiGraphics g, int x, int y) {
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            g.renderOutline(x + col * 18 - 1, y + row * 18 - 1, 18, 18, 0x88777777);
        for (int col = 0; col < 9; col++) g.renderOutline(x + col * 18 - 1, y + 58 - 1, 18, 18, 0xAA666666);
    }
    @Override public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (getFocused() instanceof EditBox box && box.isFocused() && minecraft != null
                && minecraft.options.keyInventory.matches(keyCode, scanCode)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    private java.util.List<Component> mailboxTooltip(MailboxView entry) {
        java.util.List<Component> lines = new java.util.ArrayList<>();
        lines.add(Component.literal(title(entry.type())).withStyle(switch (entry.type()) {
            case DEPOSIT, PAYMENT_RECEIVED -> net.minecraft.ChatFormatting.GREEN;
            case WITHDRAWAL, PAYMENT_SENT, RENT_WARNING, AUCTION_OUTBID, AUCTION_NO_BIDS, AUCTION_PAYMENT_TIMEOUT, AUCTION_RETURNED -> net.minecraft.ChatFormatting.RED;
            case AUCTION_PAYMENT_COMPLETED, AUCTION_SOLD, AUCTION_CLAIM_READY -> net.minecraft.ChatFormatting.GREEN;
            case AUCTION_WON -> net.minecraft.ChatFormatting.GOLD;
            case PAYDAY_REPORT -> net.minecraft.ChatFormatting.GOLD;
            case SYSTEM -> net.minecraft.ChatFormatting.GRAY;
        }));
        if (!entry.subject().isBlank()) lines.add(Component.literal(entry.subject()).withStyle(net.minecraft.ChatFormatting.GRAY));
        for (MailboxDetail detail : entry.details().stream().limit(10).toList()) {
            var color = switch (detail.kind()) {
                case INCOMING -> net.minecraft.ChatFormatting.GREEN;
                case OUTGOING, WARNING -> net.minecraft.ChatFormatting.RED;
                case INFO -> net.minecraft.ChatFormatting.GRAY;
            };
            var line = Component.literal(detail.label()).withStyle(color);
            if (detail.amount() != null) line.append(Component.literal(": " + Money.format(detail.amount())).withStyle(net.minecraft.ChatFormatting.GOLD));
            lines.add(line);
        }
        if (entry.details().size() > 10) lines.add(Component.literal("+ " + (entry.details().size() - 10) + " more entries").withStyle(net.minecraft.ChatFormatting.GRAY));
        if (entry.balance() != null) lines.add(Component.literal("Balance: ").withStyle(net.minecraft.ChatFormatting.GRAY)
                .append(Component.literal(Money.format(entry.balance())).withStyle(net.minecraft.ChatFormatting.GOLD)));
        return lines;
    }
}
