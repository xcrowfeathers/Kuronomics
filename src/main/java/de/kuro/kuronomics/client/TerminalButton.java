package de.kuro.kuronomics.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;

final class TerminalButton extends Button {
    record Palette(int background, int hover, int disabled, int border, int hoverBorder,
                   int disabledBorder, int text, int disabledText) {}

    static final Palette REAL_ESTATE = new Palette(
            0xFF3E695C, 0xFF4E7D6D, 0xFF55665F, 0xFF85B39F, 0xFFAAD2C0, 0xFF71857B, 0xFFF2FFF7, 0xFFCBD8CF);
    static final Palette SERVER_SHOP = new Palette(
            0xFF8C7964, 0xFF998670, 0xFF827869, 0xFFAE9B84, 0xFFBBA98F, 0xFF978A77, 0xFFF4E0C5, 0xFFC4B5A2);
    static final Palette JOB = new Palette(
            0xFF384F6B, 0xFF4B688A, 0xFF526070, 0xFF7893B2, 0xFFA5BFDD, 0xFF708095, 0xFFF3F8FF, 0xFFC7D2E1);
    static final Palette BANK = new Palette(
            0xFFF0EFEC, 0xFFF8F7F4, 0xFFE4E2DE, 0xFFC8C5BF, 0xFFD5D2CB, 0xFFCECBC5, 0xFF514F4B, 0xFF938F88);

    private final Palette palette;

    TerminalButton(Builder builder, Palette palette) {
        super(builder);
        this.palette = palette;
    }

    @Override public void renderString(GuiGraphics graphics, net.minecraft.client.gui.Font font, int color) {
        if (palette != BANK) { super.renderString(graphics, font, color); return; }
        graphics.drawString(font, getMessage(), getX() + (getWidth() - font.width(getMessage())) / 2,
                getY() + (getHeight() - 8) / 2, color, false);
    }

    @Override protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean highlighted = active && isHoveredOrFocused();
        int border = !active ? palette.disabledBorder() : highlighted ? palette.hoverBorder() : palette.border();
        int background = !active ? palette.disabled() : highlighted ? palette.hover() : palette.background();
        graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), border);
        graphics.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1, background);
        renderString(graphics, Minecraft.getInstance().font, active ? palette.text() : palette.disabledText());
    }
}
