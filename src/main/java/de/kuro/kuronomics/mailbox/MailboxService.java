package de.kuro.kuronomics.mailbox;

import de.kuro.kuronomics.menu.MachineMenu;
import de.kuro.kuronomics.network.MailboxUpdate;
import de.kuro.kuronomics.util.PlayerNames;
import de.kuro.kuronomics.economy.EconomyService;
import de.kuro.kuronomics.economy.payday.PaydayService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Only call money-related notifications after their server transaction commits. */
public final class MailboxService {
    private final MinecraftServer server;
    private final MailboxStore store;

    public MailboxService(MinecraftServer server) { this.server = Objects.requireNonNull(server); store = MailboxStore.get(server); }

    public void remember(UUID player, String name) { store.remember(player, name); }

    public List<MailboxEntry> entries(UUID recipient) { return store.newest(recipient); }
    public List<MailboxView> snapshot(UUID recipient) { return entries(recipient).stream().map(this::view).toList(); }
    /** Keep auction messages in the Auction Inbox, not the ATM inbox. */
    public List<MailboxView> atmSnapshot(UUID recipient) {
        return entries(recipient).stream().filter(entry -> !isAuctionEntry(entry)).map(this::view).toList();
    }

    public boolean push(UUID recipient, MailboxEntry entry) {
        boolean added = store.add(recipient, entry);
        if (!added) return false;
        var player = server.getPlayerList().getPlayer(recipient);
        if (player != null && !isAuctionEntry(entry) && player.containerMenu instanceof MachineMenu menu
                && menu.kind == de.kuro.kuronomics.block.MachineKind.ATM && menu.page == 4)
            PacketDistributor.sendToPlayer(player, new MailboxUpdate(menu.containerId, view(entry)));
        return true;
    }

    public boolean deposit(UUID recipient, long amount, long balance, UUID transaction, String subject) {
        return push(recipient, entry(transaction, recipient, MailboxEventType.DEPOSIT, "deposit", amount, balance, null, subject, List.of()));
    }

    public boolean withdrawal(UUID recipient, long amount, long balance, UUID transaction, String subject) {
        Long shownBalance = (subject.startsWith("Server shop purchase:") || subject.startsWith("Bank Shop Purchase:")) ? null : balance;
        return push(recipient, entry(transaction, recipient, MailboxEventType.WITHDRAWAL, "withdrawal", amount, shownBalance, null, subject, List.of()));
    }

    public void payment(UUID transaction, UUID payer, UUID receiver, long amount,
                        long payerBalance, Long receiverBalance, String subject) {
        rememberOnline(payer); rememberOnline(receiver);
        if (payer != null) push(payer, entry(transaction, payer, MailboxEventType.PAYMENT_SENT, "sent:" + subject, amount, payerBalance,
                receiver, subject, List.of()));
        if (receiver != null) push(receiver, entry(transaction, receiver, MailboxEventType.PAYMENT_RECEIVED, "received:" + subject, amount,
                receiverBalance, payer, subject, List.of()));
    }

    public boolean paymentReceived(UUID transaction, UUID payer, UUID receiver, long amount, Long receiverBalance, String subject) {
        rememberOnline(payer); rememberOnline(receiver);
        return push(receiver, entry(transaction, receiver, MailboxEventType.PAYMENT_RECEIVED, "received:" + subject,
                amount, receiverBalance, payer, subject, List.of()));
    }

    public boolean payday(UUID recipient, UUID transaction, long opening, long closing, List<MailboxDetail> details) {
        Long change;
        try { change = Math.subtractExact(closing, opening); if (change == Long.MIN_VALUE) change = null; }
        catch (ArithmeticException overflow) { change = null; }
        List<MailboxDetail> report = new ArrayList<>(details.size() + 1);
        report.add(new MailboxDetail(MailboxDetail.Kind.INFO, "Old balance", opening));
        report.addAll(details);
        return push(recipient, entry(transaction, recipient, MailboxEventType.PAYDAY_REPORT, "payday",
                change, closing, null, "PayDay report", report));
    }

    public void payday(UUID recipient, PaydayService.Result result) {
        List<MailboxDetail> details = result.items().stream().map(item -> new MailboxDetail(
                MailboxDetail.Kind.valueOf(item.kind().name()), item.label(), item.amount())).toList();
        payday(recipient, result.reference(), result.opening(), result.closing(), details);
        for (var warning : result.rentWarnings()) rentWarning(recipient, result.reference(), warning.estate(), warning.amount());
        for (var received : result.received()) paymentReceived(result.reference(), recipient, received.recipient(),
                received.amount(), null, received.description());
        for (var cancelled : result.cancelled()) if (cancelled.receiver() != null)
            system(cancelled.receiver(), EconomyService.stableReference("contract-cancelled",
                            result.reference() + ":" + cancelled.receiver() + ":" + cancelled.name()),
                    "Contract cancelled: " + cancelled.name(), List.of(new MailboxDetail(
                            MailboxDetail.Kind.WARNING, "Payment was not received", cancelled.amount())));
    }

    public boolean rentWarning(UUID recipient, UUID transaction, String estate, long amount) {
        return push(recipient, entry(transaction, recipient, MailboxEventType.RENT_WARNING, estate,
                amount, null, null, estate, List.of(new MailboxDetail(MailboxDetail.Kind.WARNING, "Rent could not be paid", amount))));
    }

    public boolean auctionOutbid(UUID transition, UUID previousBidder, UUID newBidder,
                                 String itemName, long previousBid, long newBid) {
        rememberOnline(previousBidder); rememberOnline(newBidder);
        return push(previousBidder, entry(transition, previousBidder, MailboxEventType.AUCTION_OUTBID,
                "outbid", newBid, null, newBidder, itemName, List.of(
                        new MailboxDetail(MailboxDetail.Kind.INFO, "Your previous bid", previousBid),
                        new MailboxDetail(MailboxDetail.Kind.WARNING, "New highest bid", newBid))));
    }

    public boolean auctionNoBids(UUID settlement, UUID seller, String asset) {
        return push(seller, entry(settlement, seller, MailboxEventType.AUCTION_NO_BIDS, "no-bids",
                null, null, null, asset, List.of(new MailboxDetail(
                        MailboxDetail.Kind.WARNING, "Auction ended without bids", null))));
    }

    public boolean auctionWon(UUID settlement, UUID winner, UUID seller, String asset, long amount) {
        rememberOnline(winner); rememberOnline(seller);
        return push(winner, entry(settlement, winner, MailboxEventType.AUCTION_WON, "awaiting-payment",
                amount, null, seller, asset, List.of(new MailboxDetail(
                        MailboxDetail.Kind.WARNING, "Payment is due at PayDay", amount))));
    }

    public boolean auctionAwaitingSeller(UUID settlement, UUID seller, UUID winner, String asset, long amount) {
        rememberOnline(seller); rememberOnline(winner);
        return push(seller, entry(settlement, seller, MailboxEventType.AUCTION_WON, "awaiting-buyer-payment",
                amount, null, winner, asset, List.of(new MailboxDetail(
                        MailboxDetail.Kind.INFO, "Waiting for the winner's PayDay", amount))));
    }

    public boolean auctionPaymentCompleted(UUID settlement, UUID winner, UUID seller, String asset, long amount) {
        rememberOnline(winner); rememberOnline(seller);
        return push(winner, entry(settlement, winner, MailboxEventType.AUCTION_PAYMENT_COMPLETED, "payment-completed",
                amount, null, seller, asset, List.of(new MailboxDetail(
                        MailboxDetail.Kind.INCOMING, "Purchase ready to claim", amount))));
    }

    public boolean auctionPaymentTimeout(UUID settlement, UUID recipient, UUID otherPlayer, String asset, long amount) {
        rememberOnline(recipient); rememberOnline(otherPlayer);
        return push(recipient, entry(settlement, recipient, MailboxEventType.AUCTION_PAYMENT_TIMEOUT, "payment-timeout",
                amount, null, otherPlayer, asset, List.of(new MailboxDetail(
                        MailboxDetail.Kind.WARNING, "Auction cancelled: payment unavailable", amount))));
    }

    public boolean auctionSold(UUID settlement, UUID seller, UUID winner, String asset,
                               long gross, long saleTax, long net) {
        rememberOnline(seller); rememberOnline(winner);
        return push(seller, entry(settlement, seller, MailboxEventType.AUCTION_SOLD, "sold",
                net, null, winner, asset, List.of(
                        new MailboxDetail(MailboxDetail.Kind.INFO, "Gross winning bid", gross),
                        new MailboxDetail(MailboxDetail.Kind.OUTGOING, "Auction sale tax", saleTax),
                        new MailboxDetail(MailboxDetail.Kind.INCOMING, "Net added to PayDay", net))));
    }

    public boolean auctionReturned(UUID settlement, UUID seller, String asset, String reason) {
        return push(seller, entry(settlement, seller, MailboxEventType.AUCTION_RETURNED, "returned:" + reason,
                null, null, null, asset, List.of(new MailboxDetail(
                        MailboxDetail.Kind.WARNING, reason, null))));
    }

    public boolean auctionClaimReady(UUID claim, UUID owner, String asset, String reason) {
        return push(owner, entry(claim, owner, MailboxEventType.AUCTION_CLAIM_READY, "claim-ready:" + reason,
                null, null, null, asset, List.of(new MailboxDetail(
                        MailboxDetail.Kind.INCOMING, "Ready in Auction Inbox", null))));
    }

    public boolean system(UUID recipient, UUID reference, String subject, List<MailboxDetail> details) {
        return push(recipient, entry(reference, recipient, MailboxEventType.SYSTEM, subject, null, null, null, subject, details));
    }

    private MailboxEntry entry(UUID transaction, UUID recipient, MailboxEventType type, String discriminator,
                               Long amount, Long balance, UUID otherPlayer, String subject, List<MailboxDetail> details) {
        String key = transaction + ":" + recipient + ":" + type + ":" + discriminator;
        UUID id = UUID.nameUUIDFromBytes(("kuronomics:mailbox:" + key).getBytes(StandardCharsets.UTF_8));
        return new MailboxEntry(id, type, System.currentTimeMillis(), amount, balance, otherPlayer, subject, details);
    }

    private static boolean isAuctionEntry(MailboxEntry entry) {
        if (entry.type().name().startsWith("AUCTION_")) return true;
        String subject = entry.subject().toLowerCase(Locale.ROOT);
        return subject.contains("auction");
    }

    private MailboxView view(MailboxEntry entry) {
        String other = entry.otherPlayer() == null ? "Bank" : name(entry.otherPlayer());
        String subject = PlayerNames.resolve(entry.subject(), this::name);
        if(subject.startsWith("Server shop purchase:"))subject="Bank Shop Purchase:"+subject.substring("Server shop purchase:".length());
        else if(subject.startsWith("Server shop sale:"))subject="Bank Shop Sale:"+subject.substring("Server shop sale:".length());
        List<MailboxDetail> details = entry.details().stream().map(detail ->
                new MailboxDetail(detail.kind(), PlayerNames.resolve(detail.label(), this::name), detail.amount())).toList();
        return new MailboxView(entry.type(), entry.createdAt(), entry.amount(), entry.balance(), other, subject, details);
    }
    private void rememberOnline(UUID id) {
        if (id == null) return;
        var player = server.getPlayerList().getPlayer(id);
        if (player != null) store.remember(id, player.getGameProfile().getName());
    }
    private String name(UUID id) {
        var online = server.getPlayerList().getPlayer(id);
        if (online != null) { store.remember(id, online.getGameProfile().getName()); return online.getGameProfile().getName(); }
        String known = store.knownName(id);
        if (known != null) return known;
        String cached = PlayerNames.get(server, id);
        if (!cached.equals("Unknown player")) store.remember(id, cached);
        return cached;
    }
}
