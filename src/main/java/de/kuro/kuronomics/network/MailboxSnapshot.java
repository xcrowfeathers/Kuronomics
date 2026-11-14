package de.kuro.kuronomics.network;

import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.mailbox.MailboxView;
import de.kuro.kuronomics.menu.MachineMenu;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Resync the open ATM inbox periodically in case it missed a live update. */
public record MailboxSnapshot(int container, List<MailboxView> entries) implements CustomPacketPayload {
    public MailboxSnapshot { entries = List.copyOf(entries); }
    public static final Type<MailboxSnapshot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Kuronomics.MODID, "mailbox_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MailboxSnapshot> CODEC = StreamCodec.of(
            (buffer, message) -> { buffer.writeVarInt(message.container); MailboxView.writeList(buffer, message.entries); },
            buffer -> new MailboxSnapshot(buffer.readVarInt(), MailboxView.readList(buffer)));
    @Override public Type<MailboxSnapshot> type() { return TYPE; }
    public void apply(MachineMenu menu) { menu.replaceMailbox(entries); }
}
