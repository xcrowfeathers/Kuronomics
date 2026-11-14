package de.kuro.kuronomics.network;

import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.economy.EconomicOwner;
import java.util.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record MarketSnapshot(int container, String view, int page, int total, int filter, String query, String status,
                             EconomicOwner owner, String ownerName, int ownerChoices, int limit, long balance,
                             boolean enabled, int opening, int closing, String zone, long serverTime,
                             List<Offer> offers, Offer detail, ItemStack selected) implements CustomPacketPayload {
    public record Offer(UUID id, EconomicOwner seller, String sellerName, ItemStack item, long price, long stock, long revision,
                        boolean edit, boolean withdraw, boolean buy) {}
    public static final Type<MarketSnapshot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Kuronomics.MODID, "market_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MarketSnapshot> CODEC = StreamCodec.of(MarketSnapshot::write, MarketSnapshot::read);
    public static void owner(RegistryFriendlyByteBuf b, EconomicOwner o) { b.writeEnum(o.type()); if (o.id() != null) b.writeUUID(o.id()); }
    public static EconomicOwner owner(RegistryFriendlyByteBuf b) { var type = b.readEnum(EconomicOwner.Type.class); return new EconomicOwner(type, type == EconomicOwner.Type.STATE ? null : b.readUUID()); }
    private static void offer(RegistryFriendlyByteBuf b, Offer o) {
        b.writeUUID(o.id()); owner(b, o.seller()); b.writeUtf(o.sellerName(), 128); ItemStack.STREAM_CODEC.encode(b, o.item());
        b.writeLong(o.price()); b.writeLong(o.stock()); b.writeLong(o.revision()); b.writeBoolean(o.edit()); b.writeBoolean(o.withdraw()); b.writeBoolean(o.buy());
    }
    private static Offer offer(RegistryFriendlyByteBuf b) { return new Offer(b.readUUID(), owner(b), b.readUtf(128), ItemStack.STREAM_CODEC.decode(b), b.readLong(), b.readLong(), b.readLong(), b.readBoolean(), b.readBoolean(), b.readBoolean()); }
    private static void write(RegistryFriendlyByteBuf b, MarketSnapshot s) {
        b.writeVarInt(s.container); b.writeUtf(s.view, 16); b.writeVarInt(s.page); b.writeVarInt(s.total); b.writeVarInt(s.filter);
        b.writeUtf(s.query, 128); b.writeUtf(s.status, 256); owner(b, s.owner); b.writeUtf(s.ownerName, 128); b.writeVarInt(s.ownerChoices); b.writeVarInt(s.limit); b.writeLong(s.balance);
        b.writeBoolean(s.enabled); b.writeVarInt(s.opening); b.writeVarInt(s.closing); b.writeUtf(s.zone, 128); b.writeLong(s.serverTime);
        b.writeVarInt(s.offers.size()); for (Offer o : s.offers) offer(b, o); b.writeBoolean(s.detail != null); if (s.detail != null) offer(b, s.detail);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(b, s.selected);
    }
    private static MarketSnapshot read(RegistryFriendlyByteBuf b) {
        int container = b.readVarInt(); String view = b.readUtf(16); int page = b.readVarInt(), total = b.readVarInt(), filter = b.readVarInt();
        String query = b.readUtf(128), status = b.readUtf(256); EconomicOwner owner = owner(b); String ownerName = b.readUtf(128);
        int choices = b.readVarInt(), limit = b.readVarInt(); long balance = b.readLong(); boolean enabled = b.readBoolean();
        int opening = b.readVarInt(), closing = b.readVarInt(); String zone = b.readUtf(128); long now = b.readLong();
        int count = b.readVarInt(); if (count < 0 || count > 4) throw new IllegalArgumentException("Invalid market page");
        List<Offer> offers = new ArrayList<>(); for (int i = 0; i < count; i++) offers.add(offer(b)); Offer detail = b.readBoolean() ? offer(b) : null;
        return new MarketSnapshot(container, view, page, total, filter, query, status, owner, ownerName, choices, limit, balance, enabled, opening, closing, zone, now, offers, detail, ItemStack.OPTIONAL_STREAM_CODEC.decode(b));
    }
    public static MarketSnapshot empty(int container, String view) { return new MarketSnapshot(container, view, 0, 0, 0, "", "", EconomicOwner.player(new UUID(0,0)), "Personal", 1, 0, 0, false, 12, 21, "UTC", 0, List.of(), null, ItemStack.EMPTY); }
    @Override public Type<MarketSnapshot> type() { return TYPE; }
}
