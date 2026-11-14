package de.kuro.kuronomics.network;

import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.menu.AuctionMenu;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public record AuctionAction(int container, String action, int value, long startingBid,
                            long minimumIncrease, long revision, long amount, UUID targetId,
                            UUID requestId, String query) implements CustomPacketPayload {
    public AuctionAction(int container, String action, int value, long startingBid, long minimumIncrease, UUID requestId) {
        this(container, action, value, startingBid, minimumIncrease, -1, 0, new UUID(0, 0), requestId, "");
    }
    public AuctionAction(int container, String action, int value, long startingBid, long minimumIncrease,
                         long revision, long amount, UUID targetId, UUID requestId) {
        this(container, action, value, startingBid, minimumIncrease, revision, amount, targetId, requestId, "");
    }
    public static final Type<AuctionAction> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Kuronomics.MODID, "auction_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AuctionAction> CODEC = StreamCodec.of(
            (b, m) -> { b.writeVarInt(m.container); b.writeUtf(m.action, 24); b.writeVarInt(m.value); b.writeLong(m.startingBid); b.writeLong(m.minimumIncrease);
                b.writeLong(m.revision); b.writeLong(m.amount); b.writeUUID(m.targetId); b.writeUUID(m.requestId); b.writeUtf(m.query, 64); },
            b -> new AuctionAction(b.readVarInt(), b.readUtf(24), b.readVarInt(), b.readLong(), b.readLong(), b.readLong(), b.readLong(), b.readUUID(), b.readUUID(), b.readUtf(64)));
    @Override public Type<AuctionAction> type() { return TYPE; }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(AuctionSnapshot.TYPE, AuctionSnapshot.CODEC, (message, context) -> {
            if (context.player().containerMenu instanceof AuctionMenu menu && menu.containerId == message.container()) menu.apply(message);
        });
        registrar.playToClient(AuctionUpdate.TYPE, AuctionUpdate.CODEC, (message, context) -> {
            if (context.player().containerMenu instanceof AuctionMenu menu && menu.containerId == message.container()) menu.apply(message);
        });
        registrar.playToServer(TYPE, CODEC, (message, context) -> {
            if (context.player() instanceof ServerPlayer player && player.containerMenu instanceof AuctionMenu menu && menu.containerId == message.container()) de.kuro.kuronomics.economy.EconomyActions.submit(player, menu, message.requestId(), () -> menu.action(player, message));
        });
    }
}
