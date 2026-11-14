package de.kuro.kuronomics.network;

import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.menu.ServerShopEditorMenu;
import de.kuro.kuronomics.menu.ServerShopTerminalMenu;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public record ServerShopAction(int container, String action, int quantity, long buyPrice,
                               long sellPrice, long stock, long revision, long rotationDuration,
                               int rotationCount, UUID targetId, UUID requestId) implements CustomPacketPayload {
    public ServerShopAction(int container, String action, int quantity, long buyPrice, long sellPrice, long stock, UUID requestId) {
        this(container, action, quantity, buyPrice, sellPrice, stock, -1, 0, 0, new UUID(0, 0), requestId);
    }
    public static final Type<ServerShopAction> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Kuronomics.MODID, "server_shop_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ServerShopAction> CODEC = StreamCodec.of(
            (b, m) -> { b.writeVarInt(m.container); b.writeUtf(m.action, 24); b.writeVarInt(m.quantity);
                b.writeLong(m.buyPrice); b.writeLong(m.sellPrice); b.writeLong(m.stock); b.writeLong(m.revision);
                b.writeLong(m.rotationDuration); b.writeVarInt(m.rotationCount); b.writeUUID(m.targetId); b.writeUUID(m.requestId); },
            b -> new ServerShopAction(b.readVarInt(), b.readUtf(24), b.readVarInt(), b.readLong(), b.readLong(), b.readLong(),
                    b.readLong(), b.readLong(), b.readVarInt(), b.readUUID(), b.readUUID()));
    @Override public Type<ServerShopAction> type() { return TYPE; }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2");
        registrar.playToClient(ServerShopSnapshot.TYPE, ServerShopSnapshot.CODEC, (message, context) -> {
            if (context.player().containerMenu instanceof ServerShopTerminalMenu menu && menu.containerId == message.container()) menu.apply(message);
            else if (context.player().containerMenu instanceof ServerShopEditorMenu menu && menu.containerId == message.container()) menu.apply(message);
        });
        registrar.playToServer(TYPE, CODEC, (message, context) -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            if (player.containerMenu instanceof ServerShopTerminalMenu menu && menu.containerId == message.container)
                de.kuro.kuronomics.economy.EconomyActions.submit(player, menu, message.requestId(), () -> menu.action(player, message));
            else if (player.containerMenu instanceof ServerShopEditorMenu menu && menu.containerId == message.container)
                de.kuro.kuronomics.economy.EconomyActions.submit(player, menu, message.requestId(), () -> menu.action(player, message));
        });
    }
}
