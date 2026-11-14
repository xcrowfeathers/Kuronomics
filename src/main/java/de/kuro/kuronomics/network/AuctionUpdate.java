package de.kuro.kuronomics.network;

import de.kuro.kuronomics.Kuronomics;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record AuctionUpdate(int container, UUID auctionId, boolean visible, int total, String status,
                            AuctionSnapshot.AuctionView view) implements CustomPacketPayload {
    public static final Type<AuctionUpdate> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Kuronomics.MODID, "auction_update"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AuctionUpdate> CODEC = StreamCodec.of(
            (b, m) -> { b.writeVarInt(m.container); b.writeUUID(m.auctionId); b.writeBoolean(m.visible); b.writeVarInt(m.total); b.writeUtf(m.status, 512);
                if (m.visible) AuctionSnapshot.writeView(b, m.view); },
            b -> { int container = b.readVarInt(); UUID id = b.readUUID(); boolean visible = b.readBoolean(); int total = b.readVarInt(); String status = b.readUtf(512);
                return new AuctionUpdate(container, id, visible, total, status, visible ? AuctionSnapshot.readView(b) : null); });
    @Override public Type<AuctionUpdate> type() { return TYPE; }
}
