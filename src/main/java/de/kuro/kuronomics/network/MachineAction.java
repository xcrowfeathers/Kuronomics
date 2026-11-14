package de.kuro.kuronomics.network;

import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.menu.MachineMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public record MachineAction(int container, String action, long value, java.util.UUID reference) implements CustomPacketPayload {
    public static final Type<MachineAction> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Kuronomics.MODID, "machine_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MachineAction> CODEC = StreamCodec.of(
            (buffer, message) -> { buffer.writeVarInt(message.container); buffer.writeUtf(message.action, 16); buffer.writeLong(message.value); buffer.writeUUID(message.reference); },
            buffer -> new MachineAction(buffer.readVarInt(), buffer.readUtf(16), buffer.readLong(), buffer.readUUID()));
    @Override public Type<MachineAction> type() { return TYPE; }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(MachineSnapshot.TYPE, MachineSnapshot.CODEC, (message, context) -> {
            if (context.player().containerMenu instanceof MachineMenu menu && menu.containerId == message.container()) message.apply(menu);
        });
        registrar.playToClient(MailboxUpdate.TYPE, MailboxUpdate.CODEC, (message, context) -> {
            if (context.player().containerMenu instanceof MachineMenu menu && menu.containerId == message.container()) message.apply(menu);
        });
        registrar.playToClient(MailboxSnapshot.TYPE, MailboxSnapshot.CODEC, (message, context) -> {
            if (context.player().containerMenu instanceof MachineMenu menu && menu.containerId == message.container()) message.apply(menu);
        });
        registrar.playToServer(TYPE, CODEC, (message, context) -> {
            if (context.player() instanceof ServerPlayer player && player.containerMenu instanceof MachineMenu menu && menu.containerId == message.container)
                de.kuro.kuronomics.economy.EconomyActions.submit(player, menu, message.reference(), () -> menu.action(player, message.action, message.value, message.reference));
        });
    }
}
