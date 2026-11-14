package de.kuro.kuronomics.network;

import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.menu.MarketMenu;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public record MarketAction(int container, String action, UUID listing, int quantity, long revision, String text, UUID request) implements CustomPacketPayload {
    public static final Type<MarketAction> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Kuronomics.MODID, "market_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MarketAction> CODEC = StreamCodec.of((b, a) -> {
        b.writeVarInt(a.container); b.writeUtf(a.action, 16); b.writeUUID(a.listing); b.writeVarInt(a.quantity); b.writeLong(a.revision); b.writeUtf(a.text, 128); b.writeUUID(a.request);
    }, b -> new MarketAction(b.readVarInt(), b.readUtf(16), b.readUUID(), b.readVarInt(), b.readLong(), b.readUtf(128), b.readUUID()));
    public static void register(RegisterPayloadHandlersEvent event) {
        var r = event.registrar("1");
        r.playToClient(MarketSnapshot.TYPE, MarketSnapshot.CODEC, (m, c) -> { if (c.player().containerMenu instanceof MarketMenu menu && menu.containerId == m.container()) menu.apply(m); });
        r.playToServer(TYPE, CODEC, (m, c) -> { if (c.player() instanceof ServerPlayer p && p.containerMenu instanceof MarketMenu menu && menu.containerId == m.container())
            de.kuro.kuronomics.economy.EconomyActions.submit(p, menu, m.request(), () -> menu.action(p, m)); });
    }
    @Override public Type<MarketAction> type() { return TYPE; }
}
