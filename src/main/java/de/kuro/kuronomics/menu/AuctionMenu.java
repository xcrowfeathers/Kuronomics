package de.kuro.kuronomics.menu;

import de.kuro.kuronomics.auction.*;
import de.kuro.kuronomics.block.AuctionTerminalBlockEntity;
import de.kuro.kuronomics.event.EconomyEvents;
import de.kuro.kuronomics.economy.Money;
import de.kuro.kuronomics.economy.EconomyService;
import de.kuro.kuronomics.economy.data.EconomyLedger;
import de.kuro.kuronomics.economy.tax.MarketplaceTaxService;
import de.kuro.kuronomics.estate.PropertyService;
import de.kuro.kuronomics.network.*;
import de.kuro.kuronomics.registry.ModContent;
import de.kuro.kuronomics.util.PlayerNames;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

public final class AuctionMenu extends AbstractContainerMenu {
    private static final int PAGE_SIZE = 3;
    public final BlockPos pos;
    public AuctionSnapshot state;
    private final AuctionTerminalBlockEntity terminal;
    private final Player viewer;
    private final int openedAtTick;
    private boolean initialSyncPending = true;
    private boolean refreshPending;
    private final Set<UUID> changedAuctions = new LinkedHashSet<>();
    private int lastAuthoritativeSyncTick;
    private final LinkedHashSet<UUID> requests = new LinkedHashSet<>();
    private ItemStack selected = ItemStack.EMPTY;
    private int tab, page, inboxSection, sellAssetType, propertyIndex;
    private String status = "", search = "";

    public AuctionMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) { this(id, inventory, buffer.readBlockPos(), null); }
    private AuctionMenu(int id, Inventory inventory, BlockPos pos, AuctionTerminalBlockEntity terminal) {
        super(ModContent.AUCTION_MENU.get(), id); this.pos = pos; this.terminal = terminal;
        this.viewer = inventory.player;
        this.openedAtTick = viewer instanceof ServerPlayer serverPlayer ? serverPlayer.server.getTickCount() : -1;
        this.lastAuthoritativeSyncTick = openedAtTick;
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col + row * 9 + 9, 89 + col * 18, 161 + row * 18));
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 89 + col * 18, 219));
        state = empty(id);
    }
    public AuctionMenu(int id, Inventory inventory, AuctionTerminalBlockEntity terminal) { this(id, inventory, terminal.getBlockPos(), terminal); }
    public static void open(ServerPlayer player, AuctionTerminalBlockEntity terminal) {
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new AuctionMenu(id, inv, terminal), Component.literal("Auction House")), b -> b.writeBlockPos(terminal.getBlockPos()));
        if (player.containerMenu instanceof AuctionMenu menu) menu.sync(player);
    }
    public void apply(AuctionSnapshot snapshot) { state = snapshot; }
    public void apply(AuctionUpdate update) {
        List<AuctionSnapshot.AuctionView> rows = new ArrayList<>(state.auctions());
        int index = -1; for (int i = 0; i < rows.size(); i++) if (rows.get(i).id().equals(update.auctionId())) { index = i; break; }
        if (!update.visible()) { if (index >= 0) rows.remove(index); }
        else if (index >= 0) rows.set(index, update.view());
        else if (rows.size() < PAGE_SIZE) rows.add(update.view());
        state = new AuctionSnapshot(state.container(), state.tab(), state.page(), update.total(), update.status(), state.selected(),
                state.sellAssetType(), state.selectedPropertyName(), state.propertyCount(), state.propertyAvailable(), state.fee(), state.saleTaxBasisPoints(),
                state.minCycles(), state.maxCycles(), state.cycleMinutes(), rows, state.inboxSection(),
                state.claims(), state.results(), state.mailbox(), state.balance());
    }
    public void action(ServerPlayer player, AuctionAction action) {
        if (player.containerMenu != this || !stillValid(player) || !remember(action.requestId())) return;
        boolean liveUpdate = false;
        try {
            switch (action.action()) {
                case "tab" -> { if (action.value() < 0 || action.value() > 4) throw new IllegalArgumentException("Invalid auction tab."); tab = action.value(); page = 0; inboxSection = 0; status = ""; }
                case "inbox_section" -> { if (tab != 4 || action.value() < 0 || action.value() > 1) throw new IllegalArgumentException("Invalid Inbox section."); inboxSection = action.value(); page = 0; status = ""; }
                case "previous" -> page = Math.max(0, page - 1);
                case "next" -> page++;
                case "search" -> {
                    if (tab != 0 && tab != 2 && tab != 3) throw new IllegalArgumentException("Search is only available for auction listings.");
                    search = action.query().strip(); page = 0; status = "";
                }
                case "clear" -> selected = ItemStack.EMPTY;
                case "sell_type" -> {
                    if (tab != 1 || action.value() < 0 || action.value() > 1) throw new IllegalArgumentException("Invalid auction asset type.");
                    if (action.value() == 1) new PropertyService(player.server).requireIntegration();
                    sellAssetType = action.value(); status = "";
                }
                case "property_next" -> { if (tab != 1 || sellAssetType != 1) throw new IllegalArgumentException("Select property auctions first."); new PropertyService(player.server).requireIntegration(); propertyIndex++; status = ""; }
                case "list" -> {
                    if (tab != 1) throw new IllegalArgumentException("Open the Sell tab first.");
                    AuctionHouseService service = new AuctionHouseService(player.server); AuctionHouseService.ListingResult result;
                    String listed;
                    if (sellAssetType == 1) {
                        var properties = new PropertyService(player.server).getAuctionableProperties(player.getUUID());
                        if (properties.isEmpty()) throw new IllegalArgumentException("You have no eligible purchased properties.");
                        propertyIndex = Math.floorMod(propertyIndex, properties.size()); var property = properties.get(propertyIndex);
                        result = service.createPropertyAuction(player, property.id(), action.startingBid(), action.minimumIncrease(), action.value(), action.requestId());
                        listed = "property " + property.displayName();
                    } else {
                        result = service.createItemAuction(player, selected, action.startingBid(), action.minimumIncrease(), action.value(), action.requestId());
                        listed = selected.getCount() + "x " + selected.getHoverName().getString();
                    }
                    status = "Listed " + listed + " for " + Money.format(result.auction().startingBid());
                    selected = ItemStack.EMPTY; tab = 2; page = 0;
                }
                case "bid" -> {
                    if (tab != 0 && tab != 3) throw new IllegalArgumentException("Open Auctions or My Bids first.");
                    if (action.targetId().getMostSignificantBits() == 0 && action.targetId().getLeastSignificantBits() == 0)
                        throw new IllegalArgumentException("Select a valid auction.");
                    var result = new AuctionHouseService(player.server).bid(player, action.targetId(), action.amount(), action.revision(), action.requestId());
                    status = "Bid accepted: " + Money.format(result.auction().highestBid()); liveUpdate = true;
                    refreshAuction(player.server, result.auction().id());
                }
                case "cancel" -> {
                    if (tab != 2) throw new IllegalArgumentException("Open My Auctions first.");
                    AuctionHouseService service = new AuctionHouseService(player.server);
                    var result = service.cancelAuction(player, action.targetId(), action.revision(), action.requestId());
                    status = result.cancelled() ? "Auction removed. Its asset has been returned." : "This auction was already removed.";
                    liveUpdate = true; refreshAuction(player.server, result.auction().id());
                }
                case "claim" -> {
                    if (tab != 4 || inboxSection != 0) throw new IllegalArgumentException("Open Auction Claims first.");
                    if (action.targetId().getMostSignificantBits() == 0 && action.targetId().getLeastSignificantBits() == 0)
                        throw new IllegalArgumentException("Select a valid auction claim.");
                    AuctionHouseService service = new AuctionHouseService(player.server);
                    AuctionClaim claim = service.claims(player.getUUID()).stream().filter(c -> c.id().equals(action.targetId())).findFirst().orElse(null);
                    if (claim == null) status = "This auction asset was already claimed.";
                    else if (claim.asset() instanceof AuctionAsset.PropertyAsset) {
                        var result = service.claimProperty(player, action.targetId());
                        status = result.alreadyClaimed() ? "This property was already claimed." : "Claimed property " + result.propertyName();
                    } else {
                        var result = service.claimItem(player, action.targetId());
                        status = result.alreadyClaimed() ? "This auction item was already claimed."
                                : "Claimed " + result.item().getCount() + "x " + result.item().getHoverName().getString();
                    }
                }
                default -> throw new IllegalArgumentException("Invalid auction action.");
            }
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException error) { status = error.getMessage(); }
        if (!liveUpdate) requestSync(player);
    }
    @Override public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (player instanceof ServerPlayer serverPlayer && tab == 1 && slotId >= 0 && slotId < slots.size()) {
            ItemStack stack = slots.get(slotId).getItem(); selected = stack.isEmpty() ? ItemStack.EMPTY : stack.copy(); sellAssetType = 0;
            status = selected.isEmpty() ? "Selection cleared" : "Selected " + selected.getCount() + "x " + selected.getHoverName().getString(); sync(serverPlayer); return;
        }
        super.clicked(slotId, button, clickType, player);
    }
    public void sync(ServerPlayer player) {
        lastAuthoritativeSyncTick = player.server.getTickCount(); refreshPending = false; changedAuctions.clear();
        AuctionHouseService service = new AuctionHouseService(player.server);
        List<Auction> source = source(player, service);
        List<AuctionClaim> allClaims = tab == 4 ? service.claims(player.getUUID()) : List.of();
        List<AuctionHouseService.History> allHistory = tab == 4 ? service.history(player.getUUID()) : List.of();
        PropertyService propertyService = new PropertyService(player.server);
        boolean propertyAvailable = propertyService.integrationAvailable();
        if (!propertyAvailable && sellAssetType == 1) sellAssetType = 0;
        List<PropertyService.AuctionableProperty> properties = tab == 1 && propertyAvailable
                ? propertyService.getAuctionableProperties(player.getUUID()) : List.of();
        if (!properties.isEmpty()) propertyIndex = Math.floorMod(propertyIndex, properties.size()); else propertyIndex = 0;
        String propertyName = properties.isEmpty() ? "No eligible properties" : properties.get(propertyIndex).displayName();
        int inboxPageSize = inboxSection == 0 ? PAGE_SIZE : 2;
        int total = (tab == 0 || tab == 2 || tab == 3) ? source.size()
                : tab == 4 ? (inboxSection == 0 ? allClaims.size() : allHistory.size()) : 0;
        int effectivePageSize = tab == 4 ? inboxPageSize : PAGE_SIZE;
        int pages = Math.max(1, (total + effectivePageSize - 1) / effectivePageSize); page = Math.max(0, Math.min(page, pages - 1));
        List<AuctionSnapshot.AuctionView> views = new ArrayList<>();
        if (tab == 0 || tab == 2 || tab == 3) for (Auction auction : source.stream().skip((long) page * PAGE_SIZE).limit(PAGE_SIZE).toList()) {
            views.add(view(player, service, auction));
        }
        List<AuctionSnapshot.ClaimView> claimViews = tab == 4 && inboxSection == 0
                ? allClaims.stream().skip((long) page * PAGE_SIZE).limit(PAGE_SIZE).map(claim -> claimView(player, claim)).toList() : List.of();
        List<AuctionSnapshot.ResultView> resultViews = tab == 4 && inboxSection == 1
                ? allHistory.stream().skip((long) page * 2).limit(2).map(result -> resultView(player, result)).toList() : List.of();
        state = new AuctionSnapshot(containerId, tab, page, total, status, selected, sellAssetType, propertyName, properties.size(), propertyAvailable,
                Money.legacy(EconomyEvents.config().auctionListingFee()), MarketplaceTaxService.configuredBasisPoints(MarketplaceTaxService.SourceType.AUCTION_SALE),
                EconomyEvents.config().auctionMinDurationCycles(), EconomyEvents.config().auctionMaxDurationCycles(), EconomyEvents.config().auctionCycleMinutes(),
                views, inboxSection, claimViews, resultViews, List.of(),
                new EconomyService(EconomyLedger.get(player.server)).getBalance(player.getUUID()));
        PacketDistributor.sendToPlayer(player, state);
    }
    public static void refreshAll(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) if (player.containerMenu instanceof AuctionMenu menu) menu.refreshPending = true;
    }
    public static void refreshAuction(net.minecraft.server.MinecraftServer server, UUID auctionId) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) if (player.containerMenu instanceof AuctionMenu menu) {
            if (menu.changedAuctions.size() < 8) menu.changedAuctions.add(auctionId); else menu.refreshPending = true;
        }
    }
    private void requestSync(ServerPlayer player) {
        if (lastAuthoritativeSyncTick == player.server.getTickCount()) refreshPending = true; else sync(player);
    }
    @Override public void broadcastChanges() {
        super.broadcastChanges();
        if (viewer instanceof ServerPlayer player && player.containerMenu == this) {
            int now = player.server.getTickCount();
            if ((refreshPending || !changedAuctions.isEmpty()) && now != lastAuthoritativeSyncTick) {
                if (refreshPending || changedAuctions.size() > 1) sync(player);
                else {
                    var service = new AuctionHouseService(player.server);
                    var auction = service.find(changedAuctions.iterator().next()).orElse(null); changedAuctions.clear();
                    if (auction == null) sync(player); else { lastAuthoritativeSyncTick = now; pushUpdate(player, service, auction); }
                }
            }
            else if (initialSyncPending && now > openedAtTick) {
                initialSyncPending = false;
                sync(player);
            } else if (!initialSyncPending && now - lastAuthoritativeSyncTick >= 20 && Math.floorMod(now, 40) == Math.floorMod(player.getUUID().hashCode(), 40)) sync(player);
        }
    }
    private void pushUpdate(ServerPlayer player, AuctionHouseService service, Auction auction) {
        if (tab == 1) return;
        if (tab == 4) { sync(player); return; }
        List<Auction> source = source(player, service);
        int total = source.size(), pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE); page = Math.max(0, Math.min(page, pages - 1));
        boolean visible = source.stream().skip((long) page * PAGE_SIZE).limit(PAGE_SIZE).anyMatch(value -> value.id().equals(auction.id()));
        boolean wasVisible = state.auctions().stream().anyMatch(value -> value.id().equals(auction.id()));
        if (!visible && !wasVisible) return;
        AuctionSnapshot.AuctionView row = null;
        if (visible) row = view(player, service, auction);
        PacketDistributor.sendToPlayer(player, new AuctionUpdate(containerId, auction.id(), visible, total, status, row));
    }
    private List<Auction> source(ServerPlayer player, AuctionHouseService service) {
        List<Auction> values = switch (tab) {
            case 2 -> service.bySeller(player.getUUID()); case 3 -> service.byParticipant(player.getUUID()); default -> service.marketListings();
        };
        if (search.isBlank()) return values;
        String[] terms = search.toLowerCase(Locale.ROOT).split("\\s+");
        return values.stream().filter(auction -> {
            String itemId = auction.asset() instanceof AuctionAsset.ItemAsset item
                    ? net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.stack().getItem()).toString() : "property";
            String assetName = auction.asset() instanceof AuctionAsset.PropertyAsset property
                    ? new PropertyService(player.server).displayName(property.propertyId()) : ((AuctionAsset.ItemAsset) auction.asset()).stack().getHoverName().getString();
            String text = (assetName + " " + itemId + " " + PlayerNames.get(player.server, auction.sellerId())
                    + " " + auction.asset().type().name()).toLowerCase(Locale.ROOT);
            return Arrays.stream(terms).allMatch(text::contains);
        }).toList();
    }
    private static AuctionSnapshot.AuctionView view(ServerPlayer player, AuctionHouseService service, Auction auction) {
        String participation = auction.highestBidderId() != null && auction.highestBidderId().equals(player.getUUID()) ? "HIGHEST"
                : auction.participants().contains(player.getUUID()) ? "OUTBID" : "ACTIVE";
        long next; try { next = auction.minimumNextBid(); } catch (ArithmeticException overflow) { next = Long.MAX_VALUE; }
        ItemStack item = auction.asset() instanceof AuctionAsset.ItemAsset asset ? asset.stack() : ItemStack.EMPTY;
        String type = auction.asset().type().name(), name = auction.asset() instanceof AuctionAsset.PropertyAsset property
                ? new PropertyService(player.server).displayName(property.propertyId()) : item.getHoverName().getString();
        String bidReason = service.bidBlockReason(player, auction);
        return new AuctionSnapshot.AuctionView(auction.id(), item, type, name, PlayerNames.get(player.server, auction.sellerId()),
                auction.highestBidderId() == null ? "No bids" : PlayerNames.get(player.server, auction.highestBidderId()),
                auction.startingBid(), auction.highestBid(), auction.minimumIncrease(), next,
                auction.saleTaxBasisPoints(),
                auction.deadline(),
                auction.state().name(), participation, auction.revision(), bidReason.isEmpty(), bidReason);
    }
    private static AuctionSnapshot.ClaimView claimView(ServerPlayer player, AuctionClaim claim) {
        ItemStack item = claim.asset() instanceof AuctionAsset.ItemAsset asset ? asset.stack() : ItemStack.EMPTY;
        String reason = switch (claim.reason()) {
            case PURCHASE -> "Purchased item";
            case RETURN_NO_BIDS -> "Returned: no bids";
            case RETURN_PAYMENT_TIMEOUT -> "Returned: payment timeout";
            case RETURN_CANCELLED -> "Returned: auction removed";
        };
        String type = claim.asset().type().name(); String name = claim.asset() instanceof AuctionAsset.PropertyAsset property
                ? new PropertyService(player.server).displayName(property.propertyId()) : item.getHoverName().getString();
        if (claim.asset() instanceof AuctionAsset.PropertyAsset && claim.reason() == AuctionClaim.Reason.PURCHASE) reason = "Purchased property";
        return new AuctionSnapshot.ClaimView(claim.id(), item, type, name, reason, claim.readyAt());
    }
    private static AuctionSnapshot.ResultView resultView(ServerPlayer player, AuctionHouseService.History history) {
        Auction auction = history.auction(); ItemStack item = auction.asset() instanceof AuctionAsset.ItemAsset asset ? asset.stack() : ItemStack.EMPTY;
        String winner = auction.highestBidderId() == null ? "No winner" : PlayerNames.get(player.server, auction.highestBidderId());
        String payment = switch (auction.state()) {
            case COMPLETED -> "PAID";
            case FAILED_NO_BIDS -> "NO_PAYMENT";
            case FAILED_PAYMENT, FAILED_PAYMENT_TIMEOUT -> "FAILED";
            case FAILED_INVALID_ASSET -> {
                UUID settlement = AuctionHouseService.settlementReference(auction.id());
                UUID refund = de.kuro.kuronomics.economy.EconomyService.stableReference("auction-invalid-asset-refund", settlement.toString());
                yield new de.kuro.kuronomics.economy.EconomyService(de.kuro.kuronomics.economy.data.EconomyLedger.get(player.server))
                        .isTransactionProcessed(refund) ? "REFUNDED" : "NOT CHARGED";
            }
            case CANCELLED -> "NO_PAYMENT";
            default -> "PENDING";
        };
        String type = auction.asset().type().name(); String name = auction.asset() instanceof AuctionAsset.PropertyAsset property
                ? new PropertyService(player.server).displayName(property.propertyId()) : item.getHoverName().getString();
        return new AuctionSnapshot.ResultView(auction.id(), item, type, name, PlayerNames.get(player.server, auction.sellerId()),
                winner, auction.highestBid(), auction.state().name(), payment, history.endedAt());
    }
    private boolean remember(UUID request) { if (!requests.add(request)) return false; if (requests.size() > 128) requests.remove(requests.iterator().next()); return true; }
    @Override public boolean stillValid(Player player) {
        if (!(player instanceof ServerPlayer)) return true;
        double range = EconomyEvents.config().auctionTerminalRange() + 1.0;
        return player.level().getBlockState(pos).is(ModContent.AUCTION_TERMINAL_BLOCK.get())
                && (terminal == null || player.level().getBlockEntity(pos) == terminal)
                && player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) <= 3.0 * range * range;
    }
    @Override public ItemStack quickMoveStack(Player player, int slot) {
        if (tab == 1 && slot >= 0 && slot < slots.size()) { selected = slots.get(slot).getItem().copy(); sellAssetType = 0; if (player instanceof ServerPlayer sp) sync(sp); }
        return ItemStack.EMPTY;
    }
    private static AuctionSnapshot empty(int id) { return new AuctionSnapshot(id, 0, 0, 0, "", ItemStack.EMPTY,
            0, "", 0, false, 0, 0, 1, 1, 60, List.of(), 0, List.of(), List.of(), List.of(), 0); }
}
