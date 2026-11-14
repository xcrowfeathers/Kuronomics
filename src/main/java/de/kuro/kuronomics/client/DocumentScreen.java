package de.kuro.kuronomics.client;

import de.kuro.kuronomics.item.Prices;
import de.kuro.kuronomics.item.DocumentItem;
import de.kuro.kuronomics.menu.DocumentMenu;
import de.kuro.kuronomics.registry.ModContent;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

public final class DocumentScreen extends AbstractContainerScreen<DocumentMenu> {
    private final ResourceLocation texture;
    public DocumentScreen(DocumentMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title); imageWidth = 188; imageHeight = 116;
        texture = ResourceLocation.fromNamespaceAndPath("kuronomics", "textures/screens/" + (menu.document.is(ModContent.DOCUMENTS.get(4).get()) ? "drivers_license_screen" : "id_card_screen") + ".png");
    }
    @Override protected void renderBg(GuiGraphics g, float tick, int x, int y) { g.blit(texture, leftPos, topPos, 0, 0, imageWidth, imageHeight, imageWidth, imageHeight); }
    @Override protected void renderLabels(GuiGraphics g, int x, int y) {
        var data = Prices.data(menu.document);
        boolean license = menu.document.is(ModContent.DOCUMENTS.get(4).get());
        String[] keys = license ? new String[]{"name", "date", "status"} : new String[]{"name", "ship", "status", "date"};
        String[] labels = license ? new String[]{"Name:", "Date:", "Occ.:"} : new String[]{"Name:", "City:", "Occ.:", "Date Created:"};
        for (int i = 0; i < keys.length; i++) {
            int line = i == 3 ? 80 : 17 + 18 * i;
            g.drawString(font, labels[i], i == 3 ? 16 : 61, line, 0xFF3C3C3C, false);
            String value = keys[i].equals("status") ? DocumentItem.jobDisplayName(data.getString(keys[i])) : data.getString(keys[i]);
            if (keys[i].equals("status")) {
                float scale=Math.min(1f,78f/Math.max(1,font.width(value)));
                g.pose().pushPose();g.pose().translate(106,line+(font.lineHeight-font.lineHeight*scale)/2f,0);g.pose().scale(scale,scale,1);
                g.drawString(font,value,0,0,0xFF6666FF,false);g.pose().popPose();
            } else g.drawString(font, font.plainSubstrByWidth(value, 78), 106, line, 0xFF6666FF, false);
        }
    }
}
