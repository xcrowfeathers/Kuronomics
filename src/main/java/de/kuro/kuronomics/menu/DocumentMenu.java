package de.kuro.kuronomics.menu;

import de.kuro.kuronomics.registry.ModContent;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Documents are read-only server snapshots. Identity fields never come from the client. */
public final class DocumentMenu extends AbstractContainerMenu {
    public final ItemStack document;
    public DocumentMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) { this(id, ItemStack.STREAM_CODEC.decode(buffer)); }
    private DocumentMenu(int id, ItemStack document) { super(ModContent.DOCUMENT_MENU.get(), id); this.document = document.copy(); }
    public static void open(ServerPlayer player, ItemStack document) {
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new DocumentMenu(id, document), document.getHoverName()), buffer -> ItemStack.STREAM_CODEC.encode(buffer, document));
    }
    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
    @Override public boolean stillValid(Player player) { return true; }
}
