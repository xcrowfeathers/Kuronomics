package de.kuro.kuronomics.network;

import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.menu.RealEstateMenu;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public record RealEstateAction(int container, String action, int value, long amount, long revision,
                               UUID targetId, UUID requestId, String primary, String secondary, String tertiary)
        implements CustomPacketPayload {
    public static final UUID NONE = new UUID(0, 0);
    public static final Type<RealEstateAction> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Kuronomics.MODID, "real_estate_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RealEstateAction> CODEC = StreamCodec.of(
            (b, m) -> { b.writeVarInt(m.container); b.writeUtf(m.action, 24); b.writeVarInt(m.value); b.writeLong(m.amount); b.writeLong(m.revision);
                b.writeUUID(m.targetId); b.writeUUID(m.requestId); b.writeUtf(m.primary, 512); b.writeUtf(m.secondary, 512); b.writeUtf(m.tertiary, 512); },
            b -> new RealEstateAction(b.readVarInt(), b.readUtf(24), b.readVarInt(), b.readLong(), b.readLong(),
                    b.readUUID(), b.readUUID(), b.readUtf(512), b.readUtf(512), b.readUtf(512)));
    @Override public Type<RealEstateAction> type() { return TYPE; }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(RealEstateSnapshot.TYPE, RealEstateSnapshot.CODEC, (message, context) -> {
            if (context.player().containerMenu instanceof RealEstateMenu menu && menu.containerId == message.container()) menu.apply(message);
        });
        registrar.playToClient(RealEstateInboxUpdate.TYPE, RealEstateInboxUpdate.CODEC, (message, context) -> {
            if (context.player().containerMenu instanceof RealEstateMenu menu && menu.containerId == message.container()) menu.apply(message);
        });
        registrar.playToServer(TYPE, CODEC, (message, context) -> {
            if (context.player() instanceof ServerPlayer player && player.containerMenu instanceof RealEstateMenu menu
                    && menu.containerId == message.container()) de.kuro.kuronomics.economy.EconomyActions.submit(player, menu, message.requestId(), () -> menu.action(player, message));
        });
    }
}
