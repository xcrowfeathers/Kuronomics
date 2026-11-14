package de.kuro.kuronomics.estate.market.inbox;

import de.kuro.kuronomics.estate.market.RealEstateListing;
import de.kuro.kuronomics.menu.RealEstateMenu;
import de.kuro.kuronomics.network.RealEstateInboxUpdate;
import de.kuro.kuronomics.util.PlayerNames;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.network.PacketDistributor;

public final class RealEstateInboxService {
    private final MinecraftServer server;
    private final RealEstateInboxStore store;
    public RealEstateInboxService(MinecraftServer server) {
        this.server = Objects.requireNonNull(server); store = RealEstateInboxStore.get(server);
    }
    public List<RealEstateInboxView> entries(UUID recipient) { return store.newest(recipient).stream().map(this::view).toList(); }
    public int size(UUID recipient) { return store.size(recipient); }

    public boolean listed(RealEstateListing listing) {
        return push(listing.sellerId(), listing, RealEstateEventType.PROPERTY_LISTED, listing.price(), null, null, null, "Property listed");
    }
    public boolean cancelled(RealEstateListing listing) {
        return push(listing.sellerId(), listing, RealEstateEventType.LISTING_CANCELLED, null, null, null, null, "Listing cancelled");
    }
    public boolean invalidated(RealEstateListing listing) {
        return push(listing.sellerId(), listing, RealEstateEventType.LISTING_INVALIDATED, null, null, null, null,
                listing.invalidReason().isBlank() ? "Listing invalidated" : listing.invalidReason());
    }
    public boolean priceChanged(RealEstateListing listing, long oldPrice) {
        return push(listing.sellerId(), listing, RealEstateEventType.PRICE_CHANGED, listing.price(), null, null, null,
                "Price changed from " + de.kuro.kuronomics.economy.Money.format(oldPrice));
    }
    public boolean sold(RealEstateListing listing, UUID buyer) {
        return push(listing.sellerId(), listing, RealEstateEventType.PROPERTY_SOLD, listing.price(), listing.taxAmount(),
                listing.sellerNetProceeds(), buyer, "Tax " + de.kuro.kuronomics.economy.Money.format(listing.taxAmount())
                        + " • PayDay net " + de.kuro.kuronomics.economy.Money.format(listing.sellerNetProceeds()));
    }
    public boolean purchased(RealEstateListing listing, UUID buyer) {
        return push(buyer, listing, RealEstateEventType.PROPERTY_PURCHASED, listing.price(), null, null, listing.sellerId(), "Property purchased");
    }
    public boolean transferFailed(RealEstateListing listing, UUID recipient, String reason) {
        return push(recipient, listing, RealEstateEventType.PROPERTY_TRANSFER_FAILED, listing.price(), null, null, null, reason);
    }
    private boolean push(UUID recipient, RealEstateListing listing, RealEstateEventType type, Long amount,
                         Long tax, Long net, UUID other, String message) {
        message = message == null ? "" : message.chars().map(character -> Character.isISOControl(character) ? ' ' : character)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString().strip();
        if (message.length() > 256) message = message.substring(0, 256);
        String discriminator = type + ":" + listing.revision() + ":" + message;
        UUID id = UUID.nameUUIDFromBytes(("kuronomics:real-estate-inbox:" + listing.id() + ":" + recipient + ":" + discriminator)
                .getBytes(StandardCharsets.UTF_8));
        RealEstateInboxEntry entry = new RealEstateInboxEntry(id, type, System.currentTimeMillis(), listing.id(),
                listing.listingName(), amount, tax, net, other, message);
        if (!store.add(recipient, entry)) return false;
        de.kuro.kuronomics.economy.FinancialPersistence.save(server);
        var player = server.getPlayerList().getPlayer(recipient);
        if (player != null && player.containerMenu instanceof RealEstateMenu menu && menu.inboxOpen())
            PacketDistributor.sendToPlayer(player, new RealEstateInboxUpdate(menu.containerId, store.size(recipient), view(entry)));
        return true;
    }
    private RealEstateInboxView view(RealEstateInboxEntry entry) {
        String other = entry.otherPlayer() == null ? "" : PlayerNames.get(server, entry.otherPlayer());
        return new RealEstateInboxView(entry.type(), entry.createdAt(), entry.listingName(), entry.amount(),
                entry.taxAmount(), entry.netAmount(), other, entry.message());
    }
}
