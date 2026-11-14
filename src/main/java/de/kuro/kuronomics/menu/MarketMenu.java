package de.kuro.kuronomics.menu;

import de.kuro.kuronomics.market.*;
import de.kuro.kuronomics.economy.*;
import de.kuro.kuronomics.economy.data.EconomyLedger;
import de.kuro.kuronomics.block.MachineBlockEntity;
import de.kuro.kuronomics.registry.ModContent;
import de.kuro.kuronomics.network.*;
import de.kuro.kuronomics.util.PlayerNames;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

public final class MarketMenu extends AbstractContainerMenu {
    private static final Map<MinecraftServer, Set<MarketMenu>> VIEWERS = new WeakHashMap<>();
    public MarketSnapshot state;
    private final Player viewer;
    private final BlockPos shop;
    private final SimpleContainer preview = new SimpleContainer(1);
    private String view, query = "", status = "";
    private int page, filter, selectedSlot = -1;
    private UUID selected;
    private ItemStack expected = ItemStack.EMPTY;
    private EconomicOwner owner;
    private boolean dirty = true;
    public MarketMenu(int id, Inventory inventory, RegistryFriendlyByteBuf b) { this(id, inventory, b.readUtf(16), b.readBoolean() ? b.readBlockPos() : null); }
    private MarketMenu(int id, Inventory inventory, String view, BlockPos shop) {
        super(ModContent.MARKET_MENU.get(), id); this.viewer = inventory.player; this.shop = shop; this.view = view;
        owner = EconomicOwner.player(viewer.getUUID()); state = MarketSnapshot.empty(id, view);
        if (viewer instanceof ServerPlayer p) {
            VIEWERS.computeIfAbsent(p.server, ignored -> Collections.newSetFromMap(new IdentityHashMap<>())).add(this);
            if (shop != null) owner = MarketPermissions.companies(p, true).getFirst();
        }
        addSlot(new Slot(preview, 0, 20, 57) {
            @Override public boolean mayPickup(Player p) { return false; }
            @Override public boolean mayPlace(ItemStack s) { return false; }
            @Override public boolean isActive() { return MarketMenu.this.view.equals("create"); }
        });
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++) playerSlot(inventory, col + row * 9 + 9, 99 + col * 18, 148 + row * 18);
        for (int col = 0; col < 9; col++) playerSlot(inventory, col, 99 + col * 18, 206);
    }
    private void playerSlot(Inventory inventory, int slot, int x, int y) { addSlot(new Slot(inventory, slot, x, y) {
        @Override public boolean isActive() { return view.equals("create"); }
        @Override public boolean mayPickup(Player p) { return false; }
        @Override public boolean mayPlace(ItemStack s) { return false; }
    }); }
    public static void open(ServerPlayer p) { open(p, null); }
    public static void open(ServerPlayer p, BlockPos shop) {
        MarketService.get(p.server).recover(p);
        String view = shop == null ? "catalog" : "assign";
        p.openMenu(new SimpleMenuProvider((id, inv, player) -> new MarketMenu(id, inv, view, shop), Component.literal(shop == null ? "Marketplace" : "Assign Company Shop")), b -> {
            b.writeUtf(view, 16); b.writeBoolean(shop != null); if (shop != null) b.writeBlockPos(shop);
        });
        if (p.containerMenu instanceof MarketMenu menu) menu.sync(p);
    }
    public void apply(MarketSnapshot snapshot) { state = snapshot; view = snapshot.view(); }
    public static void refresh(MinecraftServer server, UUID listing, boolean structural) {
        for (MarketMenu menu : VIEWERS.getOrDefault(server, Set.of())) if (structural || listing == null || menu.state.detail() != null && listing.equals(menu.selected)
                || menu.state.offers().stream().anyMatch(o -> o.id().equals(listing))) menu.dirty = true;
    }
    @Override public void removed(Player p) { super.removed(p); if (p instanceof ServerPlayer sp) { var menus = VIEWERS.get(sp.server); if (menus != null) menus.remove(this); } }
    public static void stop(MinecraftServer server) { VIEWERS.remove(server); }
    @Override public boolean stillValid(Player p) {
        return p == viewer && (shop == null || p.level().getBlockEntity(shop) instanceof MachineBlockEntity m && !m.assigned()
                && p.getUUID().equals(m.owner()) && p.distanceToSqr(shop.getX() + .5, shop.getY() + .5, shop.getZ() + .5) <= 64);
    }
    private List<EconomicOwner> choices(ServerPlayer p) {
        var all = new ArrayList<EconomicOwner>(); if (shop == null) all.add(EconomicOwner.player(p.getUUID()));
        all.addAll(MarketPermissions.companies(p, view.equals("create") || shop != null)); return all;
    }
    public void action(ServerPlayer p, MarketAction a) {
        if (p != viewer || p.containerMenu != this || !stillValid(p)) return;
        try {
            var service = MarketService.get(p.server); var ledger = EconomyLedger.get(p.server); status = "";
            if (shop != null && !Set.of("owner", "assign").contains(a.action())) throw new IllegalArgumentException("Choose a company for this shop.");
            switch (a.action()) {
                case "catalog" -> { view = "catalog"; page = 0; }
                case "mine" -> { view = "owned"; page = 0; owner = EconomicOwner.player(p.getUUID()); }
                case "create_view" -> { view = "create"; selectedSlot = -1; expected = ItemStack.EMPTY; preview.setItem(0, ItemStack.EMPTY);
                    if (!MarketPermissions.allowed(ledger, owner, p.getUUID(), true)) owner = EconomicOwner.player(p.getUUID()); }
                case "owner" -> { var choices = choices(p); if (choices.isEmpty()) throw new IllegalArgumentException("No eligible company."); int at = choices.indexOf(owner); owner = choices.get((at + 1) % choices.size()); page = 0; }
                case "assign" -> { if (shop == null || !(p.level().getBlockEntity(shop) instanceof MachineBlockEntity m)) throw new IllegalArgumentException("Shop unavailable."); m.assignCompany(p, owner.id()); p.closeContainer(); MachineMenu.open(p, m, 0); return; }
                case "search" -> { if (a.text().length() > 128) throw new IllegalArgumentException("Search too long."); query = a.text(); page = 0; }
                case "filter" -> { if (a.quantity() < 0 || a.quantity() > 2) throw new IllegalArgumentException("Invalid filter."); filter = a.quantity(); page = 0; }
                case "previous" -> page = Math.max(0, page - 1);
                case "next" -> page++;
                case "create" -> { if (!view.equals("create")) throw new IllegalArgumentException("Open Create Listing first.");
                    selected = service.create(p, owner, selectedSlot, expected, Money.parseDisplay(a.text()), a.request());
                    view = "owned"; expected = ItemStack.EMPTY; preview.setItem(0, ItemStack.EMPTY); selectedSlot = -1; status = "Listing created."; }
                case "edit" -> { var listing = service.require(a.listing()); if (!MarketPermissions.allowed(ledger, listing.seller(), p.getUUID(), false)) throw new IllegalArgumentException("You cannot manage this listing."); selected = listing.id(); view = "edit"; }
                case "buy_view" -> { selected = service.require(a.listing()).id(); view = "buy"; }
                case "buy" -> { service.buy(p, a.listing(), a.quantity(), a.revision(), a.request()); status = "Purchase complete."; }
                case "price" -> { requireSelected(a); service.price(p, selected, Money.parseDisplay(a.text()), a.revision(), a.request()); status = "Price updated."; }
                case "restock" -> { requireSelected(a); service.restock(p, selected, a.quantity(), a.request()); status = "Stock deposited."; }
                case "remove_view" -> { requireSelected(a); var l = service.require(selected); if (!MarketPermissions.allowed(ledger, l.seller(), p.getUUID(), true)) throw new IllegalArgumentException("You cannot remove this listing."); view = "remove"; }
                case "withdraw" -> { requireSelected(a); service.withdraw(p, selected, a.quantity(), false, a.request()); status = "Stock withdrawn."; }
                case "remove" -> { requireSelected(a); service.withdraw(p, selected, 0, true, a.request()); view = "owned"; selected = null; status = "Listing removed."; }
                default -> throw new IllegalArgumentException("Unknown marketplace action.");
            }
        } catch (ArithmeticException e) { status = "That amount or quantity is too large."; }
        catch (IllegalArgumentException e) { status = Objects.toString(e.getMessage(), "Invalid marketplace action."); }
        dirty = true; sync(p);
    }
    private void requireSelected(MarketAction a) { if (selected == null || !selected.equals(a.listing()) || !Set.of("edit", "remove").contains(view)) throw new IllegalArgumentException("Open the listing first."); }
    @Override public void clicked(int slot, int button, ClickType type, Player p) {
        if (!(p instanceof ServerPlayer sp) || !stillValid(p) || slot <= 0 || slot >= slots.size() || type != ClickType.PICKUP && type != ClickType.QUICK_MOVE || button != 0) return;
        int inventorySlot = slots.get(slot).getContainerSlot();
        if (view.equals("create")) { selectedSlot = inventorySlot; expected = sp.getInventory().getItem(inventorySlot).copy(); preview.setItem(0, expected.copyWithCount(1)); dirty = true; sync(sp); }
    }
    @Override public ItemStack quickMoveStack(Player p, int slot) { clicked(slot, 0, ClickType.PICKUP, p); return ItemStack.EMPTY; }
    @Override public void broadcastChanges() { super.broadcastChanges(); if (dirty && viewer instanceof ServerPlayer p) sync(p); }
    private MarketSnapshot.Offer offer(ServerPlayer p, MarketListing l) {
        var ledger = EconomyLedger.get(p.server); var service = MarketService.get(p.server);
        boolean edit = MarketPermissions.allowed(ledger, l.seller(), p.getUUID(), false), senior = MarketPermissions.allowed(ledger, l.seller(), p.getUUID(), true);
        return new MarketSnapshot.Offer(l.id(), l.seller(), PlayerNames.owner(p.server, l.seller()), l.item(), l.price(), l.stock(), l.revision(), edit, senior,
                l.stock() > 0 && service.active(l.seller()) && !ledger.market().locked(l.id()) && !l.seller().equals(EconomicOwner.player(p.getUUID())));
    }
    public void sync(ServerPlayer p) {
        var service = MarketService.get(p.server); var ledger = EconomyLedger.get(p.server);
        if (shop == null && !MarketPermissions.allowed(ledger, owner, p.getUUID(), false)) owner = EconomicOwner.player(p.getUUID());
        MarketListing detail = selected == null || !Set.of("edit", "buy", "remove").contains(view) ? null : ledger.market().listing(selected);
        if (Set.of("edit", "buy", "remove").contains(view) && detail == null) {
            view = "owned"; selected = null; page = 0; status = "Listing no longer available.";
        }
        List<MarketSnapshot.Offer> offers = List.of(); int total = 0;
        if (view.equals("catalog") || view.equals("owned")) { var result = service.page(query, filter, view.equals("owned") ? owner : null, page); page = result.page(); total = result.total(); offers = result.listings().stream().map(l -> offer(p, l)).toList(); }
        var cfg = service.config();
        state = new MarketSnapshot(containerId, view, page, total, filter, query, status.length() > 256 ? status.substring(0, 256) : status,
                owner, owner.isPlayer() ? "Personal Account" : PlayerNames.owner(p.server, owner), choices(p).size(), service.limit(owner), new EconomyService(ledger).getAvailableBalance(p.getUUID()),
                cfg.enabled(), cfg.openingHour(), cfg.closingHour(), cfg.zone().getId(), System.currentTimeMillis(), offers, detail == null ? null : offer(p, detail), view.equals("create") ? expected.copy() : ItemStack.EMPTY);
        PacketDistributor.sendToPlayer(p, state); dirty = false;
    }
}
