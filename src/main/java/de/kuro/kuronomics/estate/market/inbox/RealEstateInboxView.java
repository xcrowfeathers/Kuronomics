package de.kuro.kuronomics.estate.market.inbox;

import java.util.Objects;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Only display data goes to the client here, not UUID identities. */
public record RealEstateInboxView(RealEstateEventType type, long createdAt, String listingName,
                                  Long amount, Long taxAmount, Long netAmount, String otherPlayer, String message) {
    public RealEstateInboxView {
        Objects.requireNonNull(type); Objects.requireNonNull(listingName);
        Objects.requireNonNull(otherPlayer); Objects.requireNonNull(message);
    }
    public static void write(RegistryFriendlyByteBuf buffer, RealEstateInboxView view) {
        buffer.writeEnum(view.type); buffer.writeLong(view.createdAt); buffer.writeUtf(view.listingName, 128);
        buffer.writeBoolean(view.amount != null); if (view.amount != null) buffer.writeLong(view.amount);
        buffer.writeBoolean(view.taxAmount != null); if (view.taxAmount != null) buffer.writeLong(view.taxAmount);
        buffer.writeBoolean(view.netAmount != null); if (view.netAmount != null) buffer.writeLong(view.netAmount);
        buffer.writeUtf(view.otherPlayer, 64); buffer.writeUtf(view.message, 256);
    }
    public static RealEstateInboxView read(RegistryFriendlyByteBuf buffer) {
        RealEstateEventType type = buffer.readEnum(RealEstateEventType.class); long createdAt = buffer.readLong();
        String listing = buffer.readUtf(128); Long amount = buffer.readBoolean() ? buffer.readLong() : null;
        Long tax = buffer.readBoolean() ? buffer.readLong() : null; Long net = buffer.readBoolean() ? buffer.readLong() : null;
        return new RealEstateInboxView(type, createdAt, listing, amount, tax, net, buffer.readUtf(64), buffer.readUtf(256));
    }
}
