package de.kuro.kuronomics.network;

import de.kuro.kuronomics.Kuronomics;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

public record ServerShopSnapshot(int container, boolean editor, boolean administrator, String status,
                                 String name, String mode, long revision, int page, int total,
                                 int selected, List<String> choices, List<OfferView> offers,
                                 boolean rotationEnabled, long rotationDuration, int rotationCount, long nextRotationAt,
                                 long balance, boolean overview)
        implements CustomPacketPayload {
    public ServerShopSnapshot(int container, boolean editor, boolean administrator, String status, String name, String mode,
                              long revision, int page, int total, int selected, List<String> choices, List<OfferView> offers) {
        this(container, editor, administrator, status, name, mode, revision, page, total, selected, choices, offers, false, 0, 0, 0, 0);
    }
    public ServerShopSnapshot(int container, boolean editor, boolean administrator, String status, String name, String mode,
                              long revision, int page, int total, int selected, List<String> choices, List<OfferView> offers,
                              boolean rotationEnabled, long rotationDuration, int rotationCount, long nextRotationAt, long balance) {
        this(container, editor, administrator, status, name, mode, revision, page, total, selected, choices, offers,
                rotationEnabled, rotationDuration, rotationCount, nextRotationAt, balance, false);
    }
    public record OfferView(UUID id, ItemStack item, int quantity, long buyPrice, long sellPrice, String stockMode, long stock,
                            boolean rotationPool, boolean canBuy, boolean canSell, String buyBlockReason, String sellBlockReason) {
        public OfferView(ItemStack item, int quantity, long buyPrice, long sellPrice, String stockMode, long stock) {
            this(new UUID(0, 0), item, quantity, buyPrice, sellPrice, stockMode, stock, false, false, false, "", "");
        }
        public OfferView { item = item.copy(); }
        @Override public ItemStack item() { return item.copy(); }
    }

    public static final Type<ServerShopSnapshot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Kuronomics.MODID, "server_shop_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerShopSnapshot> CODEC = StreamCodec.of(
            ServerShopSnapshot::write, ServerShopSnapshot::read);
    @Override public Type<ServerShopSnapshot> type() { return TYPE; }

    private static void write(RegistryFriendlyByteBuf b, ServerShopSnapshot s) {
        b.writeVarInt(s.container); b.writeBoolean(s.editor); b.writeBoolean(s.administrator);
        b.writeUtf(s.status, 512); b.writeUtf(s.name, 64); b.writeUtf(s.mode, 32); b.writeLong(s.revision);
        b.writeVarInt(s.page); b.writeVarInt(s.total); b.writeVarInt(s.selected);
        b.writeVarInt(s.choices.size()); for (String choice : s.choices) b.writeUtf(choice, 64);
        b.writeVarInt(s.offers.size());
        for (OfferView offer : s.offers) {
            b.writeUUID(offer.id);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(b, offer.item);
            b.writeVarInt(offer.quantity); b.writeLong(offer.buyPrice); b.writeLong(offer.sellPrice);
            b.writeUtf(offer.stockMode, 16); b.writeLong(offer.stock); b.writeBoolean(offer.rotationPool);
            b.writeBoolean(offer.canBuy); b.writeBoolean(offer.canSell);
            b.writeUtf(offer.buyBlockReason, 64); b.writeUtf(offer.sellBlockReason, 64);
        }
        b.writeBoolean(s.rotationEnabled); b.writeLong(s.rotationDuration); b.writeVarInt(s.rotationCount); b.writeLong(s.nextRotationAt);
        b.writeLong(s.balance); b.writeBoolean(s.overview);
    }

    private static ServerShopSnapshot read(RegistryFriendlyByteBuf b) {
        int container = b.readVarInt(); boolean editor = b.readBoolean(); boolean admin = b.readBoolean();
        String status = b.readUtf(512), name = b.readUtf(64), mode = b.readUtf(32); long revision = b.readLong();
        int page = b.readVarInt(), total = b.readVarInt(), selected = b.readVarInt();
        int choiceCount = Math.min(10_000, b.readVarInt()); List<String> choices = new ArrayList<>(choiceCount);
        for (int i = 0; i < choiceCount; i++) choices.add(b.readUtf(64));
        int offerCount = Math.min(16, b.readVarInt()); List<OfferView> offers = new ArrayList<>(offerCount);
        for (int i = 0; i < offerCount; i++) offers.add(new OfferView(b.readUUID(), ItemStack.OPTIONAL_STREAM_CODEC.decode(b), b.readVarInt(),
                b.readLong(), b.readLong(), b.readUtf(16), b.readLong(), b.readBoolean(), b.readBoolean(), b.readBoolean(),
                b.readUtf(64), b.readUtf(64)));
        return new ServerShopSnapshot(container, editor, admin, status, name, mode, revision, page, total, selected,
                List.copyOf(choices), List.copyOf(offers), b.readBoolean(), b.readLong(), b.readVarInt(), b.readLong(), b.readLong(), b.readBoolean());
    }
}
