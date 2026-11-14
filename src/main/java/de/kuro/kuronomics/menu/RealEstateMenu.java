package de.kuro.kuronomics.menu;

import de.kuro.kuronomics.economy.Money;
import de.kuro.kuronomics.economy.EconomyService;
import de.kuro.kuronomics.economy.data.EconomyLedger;
import de.kuro.kuronomics.block.RealEstateTerminalBlockEntity;
import de.kuro.kuronomics.event.EconomyEvents;
import de.kuro.kuronomics.estate.PropertyService;
import de.kuro.kuronomics.estate.market.*;
import de.kuro.kuronomics.estate.market.inbox.RealEstateInboxService;
import de.kuro.kuronomics.network.*;
import de.kuro.kuronomics.registry.ModContent;
import de.kuro.kuronomics.util.PlayerNames;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

public final class RealEstateMenu extends AbstractContainerMenu {
    private static final int LISTING_PAGE_SIZE = 4, INBOX_PAGE_SIZE = 5;
    public final BlockPos pos;
    public RealEstateSnapshot state;
    private final RealEstateTerminalBlockEntity terminal;
    private final Player viewer;
    private final int openedAtTick;
    private boolean initialSyncPending = true;
    private boolean refreshPending;
    private int lastAuthoritativeSyncTick;
    private final LinkedHashSet<UUID> requests = new LinkedHashSet<>();
    private int tab, page, sort, propertyIndex;
    private String query = "", status = "";
    private UUID detailId;

    public RealEstateMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) { this(id, inventory, buffer.readBlockPos(), null); }
    private RealEstateMenu(int id, Inventory inventory, BlockPos pos, RealEstateTerminalBlockEntity terminal) {
        super(ModContent.REAL_ESTATE_MENU.get(), id); this.pos = pos.immutable(); this.terminal = terminal;
        this.viewer = inventory.player;
        this.openedAtTick = viewer instanceof ServerPlayer serverPlayer ? serverPlayer.server.getTickCount() : -1;
        this.lastAuthoritativeSyncTick = openedAtTick;
        state = empty(id);
    }
    public RealEstateMenu(int id, Inventory inventory, RealEstateTerminalBlockEntity terminal) { this(id, inventory, terminal.getBlockPos(), terminal); }
    public static void open(ServerPlayer player, RealEstateTerminalBlockEntity terminal) {
        player.openMenu(new SimpleMenuProvider((id, inventory, ignored) -> new RealEstateMenu(id, inventory, terminal),
                Component.literal("Real Estate Market")), buffer -> buffer.writeBlockPos(terminal.getBlockPos()));
        if (player.containerMenu instanceof RealEstateMenu menu) menu.sync(player);
    }
    public void apply(RealEstateSnapshot snapshot) { state = snapshot; }
    public void apply(RealEstateInboxUpdate update) {
        if (!inboxOpen() || state.page() != 0) return;
        List<de.kuro.kuronomics.estate.market.inbox.RealEstateInboxView> entries = new ArrayList<>(state.inbox());
        entries.add(0, update.entry()); if (entries.size() > INBOX_PAGE_SIZE) entries.remove(entries.size() - 1);
        state = new RealEstateSnapshot(state.container(), state.tab(), 0, update.total(), state.status(), state.available(),
                state.sort(), state.query(), state.taxBasisPoints(), state.listings(), state.detail(), state.property(),
                state.propertyIndex(), state.propertyCount(), entries, state.balance());
    }
    public boolean inboxOpen() { return tab == 3; }

    public void action(ServerPlayer player, RealEstateAction action) {
        if (player.containerMenu != this || !stillValid(player) || !remember(action.requestId())) return;
        try {
            RealEstateMarketService service = new RealEstateMarketService(player.server);
            switch (action.action()) {
                case "tab" -> { if (action.value() < 0 || action.value() > 3) throw new IllegalArgumentException("Invalid market tab."); tab = action.value(); page = 0; detailId = null; status = ""; }
                case "previous" -> page = Math.max(0, page - 1);
                case "next" -> page++;
                case "search" -> { if (tab != 0) throw new IllegalArgumentException("Search is available in the catalog."); query = clean(action.primary(), 64); page = 0; detailId = null; }
                case "sort" -> { if (tab != 0 || action.value() < 0 || action.value() > 4) throw new IllegalArgumentException("Invalid catalog sort."); sort = action.value(); page = 0; }
                case "property_previous" -> { requireSell(); propertyIndex--; status = ""; }
                case "property_next" -> { requireSell(); propertyIndex++; status = ""; }
                case "details" -> {
                    if (tab != 0 && tab != 2) throw new IllegalArgumentException("Open the Catalog or My Listings first.");
                    RealEstateListing listing = service.getListing(target(action)).orElseThrow(() -> new IllegalArgumentException("This listing no longer exists."));
                    if (tab == 2 && !listing.sellerId().equals(player.getUUID())) throw new IllegalArgumentException("You do not own this listing.");
                    if (tab == 0 && listing.state() != RealEstateListing.State.ACTIVE) throw new IllegalArgumentException("This listing is no longer active.");
                    detailId = listing.id(); status = "";
                }
                case "close_details" -> detailId = null;
                case "create" -> {
                    requireSell(); List<PropertyService.PropertyDetails> properties = new PropertyService(player.server).getMarketableProperties(player.getUUID());
                    if (properties.isEmpty()) throw new IllegalArgumentException("You have no eligible purchased properties.");
                    propertyIndex = Math.floorMod(propertyIndex, properties.size());
                    var result = service.createListing(action.requestId(), player.getUUID(), properties.get(propertyIndex).id(),
                            action.primary(), action.secondary(), action.tertiary(), action.amount());
                    status = result.created() ? "Property listed successfully." : "This listing request was already completed.";
                    tab = 2; page = 0; detailId = result.listing().id();
                }
                case "update" -> {
                    if (tab != 2 || detailId == null || !detailId.equals(target(action))) throw new IllegalArgumentException("Open one of your listings first.");
                    RealEstateListing updated = service.updateListing(player.getUUID(), detailId, action.revision(), action.primary(), action.secondary(), action.tertiary(), action.amount());
                    status = "Listing updated successfully."; detailId = updated.id();
                }
                case "cancel" -> {
                    if (tab != 2 || detailId == null || !detailId.equals(target(action))) throw new IllegalArgumentException("Open one of your listings first.");
                    service.cancelListing(player.getUUID(), detailId, action.revision()); status = "Listing cancelled."; detailId = null; page = 0;
                }
                case "buy" -> {
                    if (tab != 0 || detailId == null || !detailId.equals(target(action))) throw new IllegalArgumentException("Open an active listing first.");
                    var result = service.purchase(action.requestId(), player.getUUID(), detailId, action.revision());
                    status = result.completed() ? "Property purchased successfully." : "Payment secured; property transfer is pending recovery.";
                    if (result.completed()) detailId = null; page = 0;
                }
                default -> throw new IllegalArgumentException("Invalid Real Estate action.");
            }
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException error) {
            status = error.getMessage() == null ? "The market action failed." : error.getMessage();
        }
        if (lastAuthoritativeSyncTick == player.server.getTickCount()) refreshPending = true; else sync(player);
    }

    public void sync(ServerPlayer player) {
        lastAuthoritativeSyncTick = player.server.getTickCount(); refreshPending = false;
        new de.kuro.kuronomics.mailbox.MailboxService(player.server).remember(player.getUUID(), player.getGameProfile().getName());
        RealEstateMarketService market = new RealEstateMarketService(player.server); boolean available = market.available();
        if (!available) { detailId = null; if (tab == 1) status = "WorldGuard is not installed or unavailable"; }
        List<RealEstateListing> source = List.of(); List<de.kuro.kuronomics.estate.market.inbox.RealEstateInboxView> inbox = List.of();
        List<PropertyService.PropertyDetails> properties = List.of(); int total;
        RealEstateMarketService.CatalogPage catalog = null;
        if ((tab == 0 || tab == 2) && available) {
            catalog = market.catalogPage(tab == 0 ? query : "", tab == 0 ? sort : 0, tab == 2 ? player.getUUID() : null, page, LISTING_PAGE_SIZE);
            source = catalog.listings(); page = catalog.page();
        }
        if (tab == 1 && available) properties = new PropertyService(player.server).getMarketableProperties(player.getUUID());
        if (tab == 3) inbox = new RealEstateInboxService(player.server).entries(player.getUUID());
        total = tab == 3 ? inbox.size() : catalog != null ? catalog.total() : 0;
        int pageSize = tab == 3 ? INBOX_PAGE_SIZE : LISTING_PAGE_SIZE;
        int pages = Math.max(1, (total + pageSize - 1) / pageSize); page = Math.max(0, Math.min(page, pages - 1));
        List<RealEstateSnapshot.ListingView> views = (tab == 0 || tab == 2)
                ? source.stream().map(value -> view(player, value)).toList() : List.of();
        List<de.kuro.kuronomics.estate.market.inbox.RealEstateInboxView> inboxPage = tab == 3
                ? inbox.stream().skip((long) page * INBOX_PAGE_SIZE).limit(INBOX_PAGE_SIZE).toList() : List.of();
        if (!properties.isEmpty()) propertyIndex = Math.floorMod(propertyIndex, properties.size()); else propertyIndex = 0;
        RealEstateSnapshot.PropertyView property = properties.isEmpty() ? null : property(player, properties.get(propertyIndex));
        RealEstateSnapshot.ListingView detail = null;
        if (detailId != null) {
            RealEstateListing listing = market.getListing(detailId).orElse(null);
            boolean visible = listing != null && ((tab == 0 && listing.state() == RealEstateListing.State.ACTIVE)
                    || (tab == 2 && listing.sellerId().equals(player.getUUID()) && listing.state() == RealEstateListing.State.ACTIVE));
            if (visible) detail = view(player, listing); else detailId = null;
        }
        state = new RealEstateSnapshot(containerId, tab, page, total, status, available, sort, query,
                available ? RealEstateMarketService.configuredTaxBasisPoints() : 0, views, detail, property,
                propertyIndex, properties.size(), inboxPage,
                new EconomyService(EconomyLedger.get(player.server)).getBalance(player.getUUID()));
        PacketDistributor.sendToPlayer(player, state);
    }
    public static void refreshListing(MinecraftServer server, UUID listingId) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) if (player.containerMenu instanceof RealEstateMenu menu) menu.refreshPending = true;
    }
    @Override public void broadcastChanges() {
        super.broadcastChanges();
        if (viewer instanceof ServerPlayer player && player.containerMenu == this) {
            int now = player.server.getTickCount();
            if (refreshPending && now != lastAuthoritativeSyncTick) { sync(player); }
            else if (initialSyncPending && now > openedAtTick) {
                initialSyncPending = false;
                sync(player);
            } else if (!initialSyncPending && now - lastAuthoritativeSyncTick >= 20 && Math.floorMod(now, 40) == Math.floorMod(player.getUUID().hashCode(), 40)) sync(player);
        }
    }
    private List<RealEstateListing> sorted(List<RealEstateListing> input) {
        if (sort == 0) return input;
        Comparator<RealEstateListing> newest = Comparator.comparingLong(RealEstateListing::createdAt).reversed().thenComparing(RealEstateListing::id);
        Comparator<RealEstateListing> comparator = switch (sort) {
            case 1 -> Comparator.comparingLong(RealEstateListing::price).thenComparing(newest);
            case 2 -> Comparator.comparingLong(RealEstateListing::price).reversed().thenComparing(newest);
            case 3 -> Comparator.comparingLong(RealEstateListing::area).thenComparing(newest);
            case 4 -> Comparator.comparingLong(RealEstateListing::area).reversed().thenComparing(newest);
            default -> newest;
        };
        return input.stream().sorted(comparator).toList();
    }
    private static RealEstateSnapshot.ListingView view(ServerPlayer player, RealEstateListing listing) {
        return new RealEstateSnapshot.ListingView(listing.id(), listing.listingName(), PlayerNames.get(player.server, listing.sellerId()),
                listing.location(), listing.description(), listing.propertyName(), listing.dimension(), listing.price(), listing.width(), listing.length(),
                listing.taxBasisPoints(), listing.state().name(), listing.revision(), listing.sellerId().equals(player.getUUID()));
    }
    private static RealEstateSnapshot.PropertyView property(ServerPlayer player, PropertyService.PropertyDetails details) {
        return new RealEstateSnapshot.PropertyView(details.displayName(), details.dimension(), details.width(), details.length(), player.getGameProfile().getName());
    }
    private void requireSell() { if (tab != 1) throw new IllegalArgumentException("Open Sell Property first."); }
    private boolean remember(UUID request) { Objects.requireNonNull(request); if (!requests.add(request)) return false; if (requests.size() > 128) requests.remove(requests.iterator().next()); return true; }
    private static UUID target(RealEstateAction action) { if (RealEstateAction.NONE.equals(action.targetId())) throw new IllegalArgumentException("Select a valid listing."); return action.targetId(); }
    private static String clean(String value, int max) { value = value == null ? "" : value.strip(); if (value.length() > max) throw new IllegalArgumentException("Search is too long."); return value; }
    private static RealEstateSnapshot empty(int id) { return new RealEstateSnapshot(id, 0, 0, 0, "", false, 0, "", 0, List.of(), null, null, 0, 0, List.of(), 0); }
    @Override public boolean stillValid(Player player) {
        if (!(player instanceof ServerPlayer)) return true;
        double range = EconomyEvents.config().realEstateTerminalRange();
        return player.level().getBlockState(pos).is(ModContent.REAL_ESTATE_TERMINAL_BLOCK.get())
                && (terminal == null || player.level().getBlockEntity(pos) == terminal)
                && player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) <= range * range;
    }
    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
}
