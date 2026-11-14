package de.kuro.kuronomics.network;

import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.mailbox.MailboxView;
import de.kuro.kuronomics.menu.MachineMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record MailboxUpdate(int container, MailboxView entry) implements CustomPacketPayload {
    public static final Type<MailboxUpdate> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Kuronomics.MODID, "mailbox_update"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MailboxUpdate> CODEC = StreamCodec.of(
            (buffer, message) -> { buffer.writeVarInt(message.container); MailboxView.write(buffer, message.entry); },
            buffer -> new MailboxUpdate(buffer.readVarInt(), MailboxView.read(buffer)));
    @Override public Type<MailboxUpdate> type() { return TYPE; }
    public void apply(MachineMenu menu) { menu.addMailboxEntry(entry); }
}
