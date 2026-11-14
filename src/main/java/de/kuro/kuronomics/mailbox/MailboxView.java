package de.kuro.kuronomics.mailbox;

import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Resolve player UUIDs to names before sending mailbox data to the client. */
public record MailboxView(MailboxEventType type, long createdAt, Long amount, Long balance,
                          String otherPlayer, String subject, List<MailboxDetail> details) {
    public MailboxView {
        Objects.requireNonNull(type); otherPlayer = Objects.requireNonNull(otherPlayer);
        subject = Objects.requireNonNull(subject); details = List.copyOf(details);
    }

    public static void write(RegistryFriendlyByteBuf buffer, MailboxView view) {
        buffer.writeEnum(view.type); buffer.writeLong(view.createdAt);
        writeOptionalLong(buffer, view.amount); writeOptionalLong(buffer, view.balance);
        buffer.writeUtf(view.otherPlayer, 64); buffer.writeUtf(view.subject, 256);
        buffer.writeVarInt(view.details.size());
        for (MailboxDetail detail : view.details) {
            buffer.writeEnum(detail.kind()); buffer.writeUtf(detail.label(), 256);
            writeOptionalLong(buffer, detail.amount());
        }
    }

    public static MailboxView read(RegistryFriendlyByteBuf buffer) {
        MailboxEventType type = buffer.readEnum(MailboxEventType.class); long createdAt = buffer.readLong();
        Long amount = readOptionalLong(buffer), balance = readOptionalLong(buffer);
        String otherPlayer = buffer.readUtf(64), subject = buffer.readUtf(256);
        int count = buffer.readVarInt();
        if (count < 0 || count > 64) throw new IllegalArgumentException("Invalid mailbox detail count");
        List<MailboxDetail> details = new ArrayList<>(count);
        for (int i = 0; i < count; i++) details.add(new MailboxDetail(buffer.readEnum(MailboxDetail.Kind.class), buffer.readUtf(256), readOptionalLong(buffer)));
        return new MailboxView(type, createdAt, amount, balance, otherPlayer, subject, details);
    }

    public static void writeList(RegistryFriendlyByteBuf buffer, List<MailboxView> entries) {
        if (entries.size() > MailboxStore.MAX_ENTRIES) throw new IllegalArgumentException("Mailbox snapshot is too large");
        buffer.writeVarInt(entries.size()); entries.forEach(entry -> write(buffer, entry));
    }

    public static List<MailboxView> readList(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        if (count < 0 || count > MailboxStore.MAX_ENTRIES) throw new IllegalArgumentException("Invalid mailbox snapshot size");
        List<MailboxView> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) entries.add(read(buffer));
        return entries;
    }

    private static void writeOptionalLong(RegistryFriendlyByteBuf buffer, Long value) {
        buffer.writeBoolean(value != null); if (value != null) buffer.writeLong(value);
    }
    private static Long readOptionalLong(RegistryFriendlyByteBuf buffer) { return buffer.readBoolean() ? buffer.readLong() : null; }
}
