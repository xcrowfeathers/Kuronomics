package de.kuro.kuronomics.network;

import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.estate.market.inbox.RealEstateInboxView;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record RealEstateInboxUpdate(int container, int total, RealEstateInboxView entry) implements CustomPacketPayload {
    public static final Type<RealEstateInboxUpdate> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Kuronomics.MODID, "real_estate_inbox_update"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RealEstateInboxUpdate> CODEC = StreamCodec.of(
            (b, m) -> { b.writeVarInt(m.container); b.writeVarInt(m.total); RealEstateInboxView.write(b, m.entry); },
            b -> new RealEstateInboxUpdate(b.readVarInt(), b.readVarInt(), RealEstateInboxView.read(b)));
    @Override public Type<RealEstateInboxUpdate> type() { return TYPE; }
}
