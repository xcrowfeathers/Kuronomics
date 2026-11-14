package de.kuro.kuronomics.network;

import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.estate.market.inbox.RealEstateInboxView;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Send one server-checked page. Don't put raw player/property UUIDs into display text. */
public record RealEstateSnapshot(int container, int tab, int page, int total, String status,
                                 boolean available, int sort, String query, int taxBasisPoints,
                                 List<ListingView> listings, ListingView detail,
                                 PropertyView property, int propertyIndex, int propertyCount,
                                 List<RealEstateInboxView> inbox, long balance) implements CustomPacketPayload {
    public record ListingView(UUID id, String name, String seller, String location, String description,
                              String propertyName, String dimension, long price, long width, long length,
                              int taxBasisPoints, String state, long revision, boolean owner) {}
    public record PropertyView(String name, String dimension, long width, long length, String owner) {}
    public RealEstateSnapshot {
        status = Objects.requireNonNull(status); query = Objects.requireNonNull(query);
        listings = List.copyOf(listings); inbox = List.copyOf(inbox);
    }
    public static final Type<RealEstateSnapshot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Kuronomics.MODID, "real_estate_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, RealEstateSnapshot> CODEC = StreamCodec.of(RealEstateSnapshot::write, RealEstateSnapshot::read);
    @Override public Type<RealEstateSnapshot> type() { return TYPE; }
    private static void write(RegistryFriendlyByteBuf b, RealEstateSnapshot s) {
        b.writeVarInt(s.container); b.writeVarInt(s.tab); b.writeVarInt(s.page); b.writeVarInt(s.total);
        b.writeUtf(s.status, 512); b.writeBoolean(s.available); b.writeVarInt(s.sort); b.writeUtf(s.query, 64); b.writeVarInt(s.taxBasisPoints);
        b.writeVarInt(s.listings.size()); for (ListingView view : s.listings) writeListing(b, view);
        b.writeBoolean(s.detail != null); if (s.detail != null) writeListing(b, s.detail);
        b.writeBoolean(s.property != null); if (s.property != null) writeProperty(b, s.property);
        b.writeVarInt(s.propertyIndex); b.writeVarInt(s.propertyCount);
        b.writeVarInt(s.inbox.size()); for (RealEstateInboxView entry : s.inbox) RealEstateInboxView.write(b, entry);
        b.writeLong(s.balance);
    }
    private static RealEstateSnapshot read(RegistryFriendlyByteBuf b) {
        int container = b.readVarInt(), tab = b.readVarInt(), page = b.readVarInt(), total = b.readVarInt();
        String status = b.readUtf(512); boolean available = b.readBoolean(); int sort = b.readVarInt(); String query = b.readUtf(64); int tax = b.readVarInt();
        int count = bounded(b.readVarInt(), 0, 4, "listing page"); List<ListingView> listings = new ArrayList<>(count);
        for (int i = 0; i < count; i++) listings.add(readListing(b));
        ListingView detail = b.readBoolean() ? readListing(b) : null;
        PropertyView property = b.readBoolean() ? readProperty(b) : null;
        int propertyIndex = b.readVarInt(), propertyCount = b.readVarInt();
        int inboxCount = bounded(b.readVarInt(), 0, 6, "inbox page"); List<RealEstateInboxView> inbox = new ArrayList<>(inboxCount);
        for (int i = 0; i < inboxCount; i++) inbox.add(RealEstateInboxView.read(b));
        return new RealEstateSnapshot(container, tab, page, total, status, available, sort, query, tax,
                listings, detail, property, propertyIndex, propertyCount, inbox, b.readLong());
    }
    private static void writeListing(RegistryFriendlyByteBuf b, ListingView v) {
        b.writeUUID(v.id); b.writeUtf(v.name, 64); b.writeUtf(v.seller, 64); b.writeUtf(v.location, 128);
        b.writeUtf(v.description, 512); b.writeUtf(v.propertyName, 256); b.writeUtf(v.dimension, 256);
        b.writeLong(v.price); b.writeLong(v.width); b.writeLong(v.length); b.writeVarInt(v.taxBasisPoints);
        b.writeUtf(v.state, 24); b.writeLong(v.revision); b.writeBoolean(v.owner);
    }
    private static ListingView readListing(RegistryFriendlyByteBuf b) {
        return new ListingView(b.readUUID(), b.readUtf(64), b.readUtf(64), b.readUtf(128), b.readUtf(512),
                b.readUtf(256), b.readUtf(256), b.readLong(), b.readLong(), b.readLong(), b.readVarInt(),
                b.readUtf(24), b.readLong(), b.readBoolean());
    }
    private static void writeProperty(RegistryFriendlyByteBuf b, PropertyView v) {
        b.writeUtf(v.name, 256); b.writeUtf(v.dimension, 256); b.writeLong(v.width); b.writeLong(v.length); b.writeUtf(v.owner, 64);
    }
    private static PropertyView readProperty(RegistryFriendlyByteBuf b) {
        return new PropertyView(b.readUtf(256), b.readUtf(256), b.readLong(), b.readLong(), b.readUtf(64));
    }
    private static int bounded(int value, int min, int max, String label) {
        if (value < min || value > max) throw new IllegalArgumentException("Invalid Real Estate " + label + " size"); return value;
    }
}
