package de.kuro.kuronomics.network;

import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.menu.JobMenu;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public record JobAction(int container, String action, String jobId, UUID applicationId, UUID requestId, String text,
                        long terminalRevision, long registryRevision, long applicationRevision)
        implements CustomPacketPayload {
    public static final UUID NONE = new UUID(0, 0);
    public static final Type<JobAction> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Kuronomics.MODID, "job_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, JobAction> CODEC = StreamCodec.of(
            (b, m) -> { b.writeVarInt(m.container); b.writeUtf(m.action, 32); b.writeUtf(m.jobId, 512);
                b.writeUUID(m.applicationId); b.writeUUID(m.requestId); b.writeUtf(m.text, 2000); b.writeLong(m.terminalRevision);
                b.writeLong(m.registryRevision); b.writeLong(m.applicationRevision); },
            b -> new JobAction(b.readVarInt(), b.readUtf(32), b.readUtf(512), b.readUUID(), b.readUUID(), b.readUtf(2000),
                    b.readLong(), b.readLong(), b.readLong()));
    @Override public Type<JobAction> type() { return TYPE; }
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(JobSnapshot.TYPE, JobSnapshot.CODEC, (message, context) -> {
            if (context.player().containerMenu instanceof JobMenu menu && menu.containerId == message.container()) menu.apply(message);
        });
        registrar.playToServer(TYPE, CODEC, (message, context) -> {
            if (context.player() instanceof ServerPlayer player && player.containerMenu instanceof JobMenu menu && menu.containerId == message.container())
                de.kuro.kuronomics.economy.EconomyActions.submit(player, menu, message.requestId(), () -> menu.action(player, message));
        });
    }
}
