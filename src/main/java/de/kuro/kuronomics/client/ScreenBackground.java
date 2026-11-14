package de.kuro.kuronomics.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

record ScreenBackground(ResourceLocation texture, int width, int height) {
    ScreenBackground(String name, int width, int height) {
        this(ResourceLocation.fromNamespaceAndPath("kuronomics", "textures/screens/" + name + ".png"), width, height);
    }

    void draw(GuiGraphics graphics, int x, int y) {
        graphics.blit(texture, x, y, 0, 0, width, height, width, height);
    }
}
