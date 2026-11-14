package de.kuro.kuronomics.network;

import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.mailbox.MailboxView;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Resolve seller UUIDs to names before sending this auction view to the client. */
public record AuctionSnapshot(int container, int tab, int page, int total, String status, ItemStack selected,
                              int sellAssetType, String selectedPropertyName, int propertyCount,
                              boolean propertyAvailable,
                              long fee, int saleTaxBasisPoints, int minCycles, int maxCycles, int cycleMinutes,
                              List<AuctionView> auctions, int inboxSection, List<ClaimView> claims,
                              List<ResultView> results, List<MailboxView> mailbox, long balance) implements CustomPacketPayload {
    public record AuctionView(UUID id, ItemStack item, String assetType, String assetName, String sellerName, String highestBidderName,
                              long startingBid, long currentBid, long minimumIncrease, long minimumNextBid,
                              int saleTaxBasisPoints, long deadline, String state, String participation, long revision,
                              boolean canBid, String bidBlockReason) {
        public AuctionView { item = item.copy(); }
        public AuctionView(UUID id, ItemStack item, String sellerName, String highestBidderName,
                           long startingBid, long currentBid, long minimumIncrease, long minimumNextBid,
                           long deadline, String state, String participation, long revision, boolean canBid) {
            this(id, item, "ITEM", item.getHoverName().getString(), sellerName, highestBidderName, startingBid,
                    currentBid, minimumIncrease, minimumNextBid, 0, deadline, state, participation, revision, canBid,
                    canBid ? "" : "UNAVAILABLE");
        }
        @Override public ItemStack item() { return item.copy(); }
    }
    public record ClaimView(UUID id, ItemStack item, String assetType, String assetName, String reason, long readyAt) {
        public ClaimView { item = item.copy(); }
        @Override public ItemStack item() { return item.copy(); }
    }
    public record ResultView(UUID id, ItemStack item, String assetType, String assetName, String sellerName, String winnerName, long finalBid,
                             String status, String paymentStatus, long endedAt) {
        public ResultView { item = item.copy(); }
        @Override public ItemStack item() { return item.copy(); }
    }
    public AuctionSnapshot {
        selected = selected.copy(); auctions = List.copyOf(auctions); claims = List.copyOf(claims);
        results = List.copyOf(results); mailbox = List.copyOf(mailbox);
    }
    public AuctionSnapshot(int container, int tab, int page, int total, String status, ItemStack selected,
                           long fee, int minCycles, int maxCycles, int cycleMinutes,
                           List<AuctionView> auctions, List<MailboxView> mailbox) {
        this(container, tab, page, total, status, selected, 0, "", 0, false, fee, 0, minCycles, maxCycles, cycleMinutes,
                auctions, 0, List.of(), List.of(), mailbox, 0);
    }
    public AuctionSnapshot(int container, int tab, int page, int total, String status, ItemStack selected,
                           int sellAssetType, String selectedPropertyName, int propertyCount,
                           long fee, int minCycles, int maxCycles, int cycleMinutes, List<AuctionView> auctions,
                           int inboxSection, List<ClaimView> claims, List<ResultView> results, List<MailboxView> mailbox) {
        this(container, tab, page, total, status, selected, sellAssetType, selectedPropertyName, propertyCount, true,
                fee, 0, minCycles, maxCycles, cycleMinutes, auctions, inboxSection, claims, results, mailbox, 0);
    }
    @Override public ItemStack selected() { return selected.copy(); }
    public static final Type<AuctionSnapshot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Kuronomics.MODID, "auction_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AuctionSnapshot> CODEC = StreamCodec.of(AuctionSnapshot::write, AuctionSnapshot::read);
    @Override public Type<AuctionSnapshot> type() { return TYPE; }
    private static void write(RegistryFriendlyByteBuf b, AuctionSnapshot s) {
        b.writeVarInt(s.container); b.writeVarInt(s.tab); b.writeVarInt(s.page); b.writeVarInt(s.total); b.writeUtf(s.status, 512);
        ItemStack.OPTIONAL_STREAM_CODEC.encode(b, s.selected); b.writeVarInt(s.sellAssetType); b.writeUtf(s.selectedPropertyName, 128); b.writeVarInt(s.propertyCount); b.writeBoolean(s.propertyAvailable);
        b.writeLong(s.fee); b.writeVarInt(s.saleTaxBasisPoints); b.writeVarInt(s.minCycles); b.writeVarInt(s.maxCycles); b.writeVarInt(s.cycleMinutes);
        b.writeVarInt(s.auctions.size()); for (AuctionView a : s.auctions) {
            writeView(b, a);
        }
        b.writeVarInt(s.inboxSection); b.writeVarInt(s.claims.size());
        for (ClaimView claim : s.claims) {
            b.writeUUID(claim.id); ItemStack.OPTIONAL_STREAM_CODEC.encode(b, claim.item); b.writeUtf(claim.assetType, 16); b.writeUtf(claim.assetName, 128);
            b.writeUtf(claim.reason, 96); b.writeLong(claim.readyAt);
        }
        b.writeVarInt(s.results.size());
        for (ResultView result : s.results) {
            b.writeUUID(result.id); ItemStack.OPTIONAL_STREAM_CODEC.encode(b, result.item); b.writeUtf(result.assetType, 16); b.writeUtf(result.assetName, 128);
            b.writeUtf(result.sellerName, 64); b.writeUtf(result.winnerName, 64); b.writeLong(result.finalBid);
            b.writeUtf(result.status, 32); b.writeUtf(result.paymentStatus, 32); b.writeLong(result.endedAt);
        }
        MailboxView.writeList(b, s.mailbox);
        b.writeLong(s.balance);
    }
    private static AuctionSnapshot read(RegistryFriendlyByteBuf b) {
        int container = b.readVarInt(), tab = b.readVarInt(), page = b.readVarInt(), total = b.readVarInt(); String status = b.readUtf(512);
        ItemStack selected = ItemStack.OPTIONAL_STREAM_CODEC.decode(b); int sellType = b.readVarInt(); String propertyName = b.readUtf(128); int propertyCount = b.readVarInt(); boolean propertyAvailable = b.readBoolean();
        long fee = b.readLong(); int saleTax = b.readVarInt(), min = b.readVarInt(), max = b.readVarInt(), minutes = b.readVarInt();
        int count = b.readVarInt(); if (count < 0 || count > 8) throw new IllegalArgumentException("Invalid auction snapshot size");
        List<AuctionView> values = new ArrayList<>(count); for (int i = 0; i < count; i++) values.add(readView(b));
        int section = b.readVarInt(), claimCount = b.readVarInt();
        if (section < 0 || section > 1 || claimCount < 0 || claimCount > 8) throw new IllegalArgumentException("Invalid auction inbox snapshot");
        List<ClaimView> claims = new ArrayList<>(claimCount);
        for (int i = 0; i < claimCount; i++) claims.add(new ClaimView(b.readUUID(), ItemStack.OPTIONAL_STREAM_CODEC.decode(b), b.readUtf(16), b.readUtf(128), b.readUtf(96), b.readLong()));
        int resultCount = b.readVarInt(); if (resultCount < 0 || resultCount > 8) throw new IllegalArgumentException("Invalid auction result snapshot");
        List<ResultView> results = new ArrayList<>(resultCount);
        for (int i = 0; i < resultCount; i++) results.add(new ResultView(b.readUUID(), ItemStack.OPTIONAL_STREAM_CODEC.decode(b), b.readUtf(16), b.readUtf(128),
                b.readUtf(64), b.readUtf(64), b.readLong(), b.readUtf(32), b.readUtf(32), b.readLong()));
        List<MailboxView> mailbox = MailboxView.readList(b);
        return new AuctionSnapshot(container, tab, page, total, status, selected, sellType, propertyName, propertyCount, propertyAvailable, fee, saleTax, min, max, minutes,
                values, section, claims, results, mailbox, b.readLong());
    }
    static void writeView(RegistryFriendlyByteBuf b, AuctionView a) {
        b.writeUUID(a.id); ItemStack.OPTIONAL_STREAM_CODEC.encode(b, a.item); b.writeUtf(a.assetType, 16); b.writeUtf(a.assetName, 128);
        b.writeUtf(a.sellerName, 64); b.writeUtf(a.highestBidderName, 64);
        b.writeLong(a.startingBid); b.writeLong(a.currentBid); b.writeLong(a.minimumIncrease); b.writeLong(a.minimumNextBid);
        b.writeVarInt(a.saleTaxBasisPoints); b.writeLong(a.deadline); b.writeUtf(a.state, 32); b.writeUtf(a.participation, 16); b.writeLong(a.revision); b.writeBoolean(a.canBid);
        b.writeUtf(a.bidBlockReason, 32);
    }
    static AuctionView readView(RegistryFriendlyByteBuf b) {
        return new AuctionView(b.readUUID(), ItemStack.OPTIONAL_STREAM_CODEC.decode(b), b.readUtf(16), b.readUtf(128), b.readUtf(64), b.readUtf(64), b.readLong(), b.readLong(),
                b.readLong(), b.readLong(), b.readVarInt(), b.readLong(), b.readUtf(32), b.readUtf(16), b.readLong(), b.readBoolean(), b.readUtf(32));
    }
}
