package de.kuro.kuronomics.menu;

import de.kuro.kuronomics.network.ServerShopAction;
import de.kuro.kuronomics.network.ServerShopSnapshot;
import de.kuro.kuronomics.registry.ModContent;
import de.kuro.kuronomics.servershop.ServerShop;
import de.kuro.kuronomics.servershop.ServerShopService;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ServerShopEditorMenu extends AbstractContainerMenu {
    private static final int MAX_QUANTITY = 1_000_000;
    private final UUID shopId;
    private final Player viewer;
    private final int openedAtTick;
    private boolean initialSyncPending = true;
    private int lastAuthoritativeSyncTick;
    private final SimpleContainer ghost = new SimpleContainer(1);
    private final List<DraftOffer> offers = new ArrayList<>();
    private final LinkedHashSet<UUID> requests = new LinkedHashSet<>();
    private ServerShop.Mode mode = ServerShop.Mode.PLAYER_BUYS;
    private ServerShop.Rotation rotation = ServerShop.Rotation.disabled();
    private String shopName = "", status = "";
    private long baseRevision;
    private int selected = -1;
    private ItemStack pendingItem = ItemStack.EMPTY;
    private boolean overview = true;
    private int page;
    public ServerShopSnapshot state;

    public ServerShopEditorMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(id, inventory, buffer.readUUID(), null);
    }

    private ServerShopEditorMenu(int id, Inventory inventory, UUID shopId, ServerShop shop) {
        super(ModContent.SERVER_SHOP_EDITOR_MENU.get(), id); this.shopId = shopId;
        this.viewer = inventory.player;
        this.openedAtTick = viewer instanceof ServerPlayer serverPlayer ? serverPlayer.server.getTickCount() : -1;
        this.lastAuthoritativeSyncTick = openedAtTick;
        addSlot(new Slot(ghost, 0, 28, 47) {
            @Override public boolean isActive() { return !overview; }
            @Override public boolean mayPlace(ItemStack stack) { return false; }
            @Override public boolean mayPickup(Player player) { return false; }
        });
        for (int row = 0; row < 3; row++) for (int col = 0; col < 9; col++)
            addSlot(new Slot(inventory, col + row * 9 + 9, 70 + col * 18, 168 + row * 18) { @Override public boolean isActive() { return !overview; } });
        for (int col = 0; col < 9; col++) addSlot(new Slot(inventory, col, 70 + col * 18, 226) { @Override public boolean isActive() { return !overview; } });
        if (shop != null) {
            shopName = shop.name(); mode = shop.mode(); rotation = shop.rotation(); baseRevision = shop.revision();
            for (ServerShop.Offer offer : shop.offers()) offers.add(new DraftOffer(offer));
            if (!offers.isEmpty()) selected = 0;
            showSelected();
        }
        state = empty(id, shopName, mode);
    }

    public ServerShopEditorMenu(int id, Inventory inventory, ServerShop shop) { this(id, inventory, shop.id(), shop); }

    public static void open(ServerPlayer player, ServerShop shop) {
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new ServerShopEditorMenu(id, inv, shop.id(), shop),
                Component.literal("Edit Server Shop")), b -> b.writeUUID(shop.id()));
        if (player.containerMenu instanceof ServerShopEditorMenu menu) menu.sync(player);
    }

    public void apply(ServerShopSnapshot snapshot) { state = snapshot; overview = snapshot.overview(); }

    public void action(ServerPlayer player, ServerShopAction action) {
        if (player.containerMenu != this || !stillValid(player) || !remember(action.requestId())) return;
        try {
            requireAdmin(player);
            switch (action.action()) {
                case "page_previous" -> page = Math.max(0, page - 1);
                case "page_next" -> page++;
                case "edit", "move_up", "move_down" -> {
                    if (!overview) throw new IllegalArgumentException("Open the shop overview first.");
                    int index = -1;
                    for (int i = 0; i < offers.size(); i++) if (offers.get(i).id.equals(action.targetId())) { index = i; break; }
                    if (index < 0 || index / 6 != page) throw new IllegalArgumentException("Offer is no longer on this page.");
                    selected = index;
                    if (action.action().equals("edit")) { overview = false; showSelected(); }
                    else { int target = index + (action.action().equals("move_up") ? -1 : 1);
                        if (target >= 0 && target < offers.size()) { Collections.swap(offers, index, target); selected = target; persist(player); page = target / 6; }
                    }
                }
                case "new" -> {
                    if (!overview || offers.size() >= ServerShop.MAX_OFFERS) throw new IllegalArgumentException("Cannot add another offer.");
                    offers.add(new DraftOffer(UUID.randomUUID(), ItemStack.EMPTY, 1, 0, 0, ServerShop.StockMode.INFINITE, 0));
                    selected = offers.size() - 1; overview = false; showSelected();
                }
                case "back" -> { reload(player); overview = true; }

                case "previous" -> { applyFields(action); select(selected - 1); }
                case "next" -> { applyFields(action); select(selected + 1); }
                case "mode" -> { applyFields(action); mode = ServerShop.Mode.values()[(mode.ordinal() + 1) % ServerShop.Mode.values().length]; }
                case "stock_mode" -> { applyFields(action); current().stockMode = current().stockMode == ServerShop.StockMode.INFINITE ? ServerShop.StockMode.SHARED : ServerShop.StockMode.INFINITE; }
                case "pool" -> {
                    applyFields(action);
                    boolean pooled = offers.stream().allMatch(offer -> offer.rotationPool);
                    for (DraftOffer offer : offers) offer.rotationPool = !pooled;
                }
                case "rotation" -> { applyFields(action); setRotation(action, !rotation.enabled()); }
                case "rotation_settings" -> { applyFields(action); setRotation(action, rotation.enabled()); }
                case "apply" -> applyFields(action);
                case "add" -> add(action);
                case "remove" -> { remove(); persist(player); overview = true; }
                case "up" -> move(action, -1);
                case "down" -> move(action, 1);
                case "save" -> save(player, action);
                case "cancel" -> { new ServerShopService(player.server).endEditing(player.getUUID()); player.closeContainer(); return; }
                default -> throw new IllegalArgumentException("Invalid server shop editor action.");
            }

        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException ex) { status = ex.getMessage(); }
        if (player.containerMenu == this) sync(player);
    }

    private void add(ServerShopAction action) {
        if (offers.size() >= ServerShop.MAX_OFFERS) throw new IllegalArgumentException("This server shop has reached its offer safety limit.");
        ItemStack item = pendingItem.isEmpty() && selected >= 0 ? current().item : pendingItem;
        if (item.isEmpty()) throw new IllegalArgumentException("Insert or carry an item before adding an offer.");
        boolean pooled = !offers.isEmpty() && offers.stream().allMatch(offer -> offer.rotationPool);
        DraftOffer draft = new DraftOffer(UUID.randomUUID(), item, checkedQuantity(action.quantity()), checkedPrice(action.buyPrice()),
                checkedPrice(action.sellPrice()), ServerShop.StockMode.INFINITE, 0);
        draft.rotationPool = pooled;
        offers.add(draft); selected = offers.size() - 1; pendingItem = ItemStack.EMPTY; showSelected();
    }

    private void applyFields(ServerShopAction action) {
        if (selected < 0) return;
        DraftOffer draft = current();
        draft.quantity = checkedQuantity(action.quantity()); draft.buyPrice = checkedPrice(action.buyPrice());
        draft.sellPrice = checkedPrice(action.sellPrice());
        draft.stock = draft.stockMode == ServerShop.StockMode.INFINITE ? 0 : checkedStock(action.stock());
    }

    private void setRotation(ServerShopAction action, boolean enabled) {
        if (!enabled) { rotation = ServerShop.Rotation.disabled(); return; }
        if (action.rotationDuration() < 1_000) throw new IllegalArgumentException("Rotation duration must be at least one second.");
        if (action.rotationCount() <= 0 || action.rotationCount() > ServerShop.MAX_OFFERS)
            throw new IllegalArgumentException("Active rotated offers must be between 1 and " + ServerShop.MAX_OFFERS + ".");
        rotation = new ServerShop.Rotation(true, action.rotationDuration(), action.rotationCount(),
                rotation.enabled() ? rotation.nextRotationAt() : 0, rotation.enabled() ? rotation.sequence() : 0);
    }

    private void remove() {
        if (selected < 0) throw new IllegalArgumentException("There is no offer to remove.");
        offers.remove(selected); selected = offers.isEmpty() ? -1 : Math.min(selected, offers.size() - 1); showSelected();
    }

    private void move(ServerShopAction action, int direction) {
        applyFields(action); if (selected < 0) return; int target = selected + direction;
        if (target < 0 || target >= offers.size()) return;
        Collections.swap(offers, selected, target); selected = target; showSelected();
    }

    private void save(ServerPlayer player, ServerShopAction action) {
        if (selected < 0 && !pendingItem.isEmpty()) add(action);
        applyFields(action); setRotation(action, rotation.enabled()); persist(player); overview = true;
    }
    private void persist(ServerPlayer player) {
        List<ServerShop.Offer> validated = offers.stream().map(DraftOffer::toOffer).toList();
        var service = new ServerShopService(player.server);
        ServerShop updated = service.updateFromEditor(player, shopName, mode, validated, rotation);
        baseRevision = updated.revision(); status = "Saved " + updated.name();
        de.kuro.kuronomics.economy.FinancialPersistence.save(player.server);
        ServerShopTerminalMenu.refreshShop(player.server, updated.id());
        service.beginEditing(player, updated.name());
    }
    private void reload(ServerPlayer player) {
        var service = new ServerShopService(player.server);
        ServerShop shop = service.find(shopId).orElseThrow();
        offers.clear(); shop.offers().forEach(offer -> offers.add(new DraftOffer(offer)));
        mode = shop.mode(); rotation = shop.rotation(); baseRevision = shop.revision();
        pendingItem = ItemStack.EMPTY; selected = offers.isEmpty() ? -1 : Math.min(Math.max(0, selected), offers.size() - 1);
        service.beginEditing(player, shop.name()); showSelected();
    }

    @Override public void clicked(int slot, int button, ClickType type, Player player) {
        if (overview) return;
        if (slot == 0 && type == ClickType.PICKUP && player instanceof ServerPlayer serverPlayer) {
            try {
                requireAdmin(serverPlayer); ItemStack carried = getCarried();
                ItemStack selectedItem = carried.isEmpty() || button == 1 ? ItemStack.EMPTY : carried.copyWithCount(1);
                if (selected >= 0) current().item = selectedItem; else pendingItem = selectedItem;
                showSelected(); status = selectedItem.isEmpty() ? "Offer item cleared" : "Selected " + selectedItem.getHoverName().getString();
            } catch (IllegalArgumentException ex) { status = ex.getMessage(); }
            sync(serverPlayer); return;
        }
        super.clicked(slot, button, type, player);
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (overview || !(player instanceof ServerPlayer serverPlayer) || index <= 0 || index >= slots.size()) return ItemStack.EMPTY;
        try {
            requireAdmin(serverPlayer); ItemStack item = slots.get(index).getItem();
            if (!item.isEmpty()) { if (selected >= 0) current().item = item.copyWithCount(1); else pendingItem = item.copyWithCount(1); showSelected(); sync(serverPlayer); }
        } catch (IllegalArgumentException ex) { status = ex.getMessage(); sync(serverPlayer); }
        return ItemStack.EMPTY;
    }

    private void select(int index) {
        if (offers.isEmpty()) selected = -1; else selected = Math.max(0, Math.min(index, offers.size() - 1));
        showSelected();
    }
    private DraftOffer current() {
        if (selected < 0 || selected >= offers.size()) throw new IllegalArgumentException("There is no selected offer.");
        return offers.get(selected);
    }
    private void showSelected() {
        ghost.setItem(0, selected >= 0 ? current().item.copyWithCount(1) : pendingItem.copy()); broadcastChanges();
    }
    private static int checkedQuantity(int value) {
        if (value <= 0 || value > MAX_QUANTITY) throw new IllegalArgumentException("Quantity must be between 1 and 1,000,000."); return value;
    }
    private static long checkedPrice(long value) { if (value < 0) throw new IllegalArgumentException("Prices cannot be negative."); return value; }
    private static long checkedStock(long value) { if (value < 0) throw new IllegalArgumentException("Stock cannot be negative."); return value; }
    private void requireAdmin(ServerPlayer player) {
        if (!player.hasPermissions(2) || new ServerShopService(player.server).find(shopId).isEmpty())
            throw new IllegalArgumentException("You do not have permission to edit this server shop, or it no longer exists.");
        var session = new ServerShopService(player.server).editor(player.getUUID());
        if (session.isEmpty() || !session.get().shopId().equals(shopId)) throw new IllegalArgumentException("This editor session is no longer valid.");
    }
    private boolean remember(UUID request) {
        if (!requests.add(request)) return false; if (requests.size() > 128) requests.remove(requests.iterator().next()); return true;
    }

    private void sync(ServerPlayer player) {
        lastAuthoritativeSyncTick = player.server.getTickCount();
        new ServerShopService(player.server).editor(player.getUUID())
                .filter(session -> session.shopId().equals(shopId))
                .ifPresent(session -> baseRevision = session.revision());
        DraftOffer current = selected >= 0 ? current() : null;
        var view = new ServerShopSnapshot.OfferView(current == null ? new UUID(0, 0) : current.id, current == null ? pendingItem : current.item,
                current == null ? 1 : current.quantity, current == null ? 0 : current.buyPrice,
                current == null ? 0 : current.sellPrice, current == null ? ServerShop.StockMode.INFINITE.name() : current.stockMode.name(),
                current == null ? 0 : current.stock, !offers.isEmpty() && offers.stream().allMatch(offer -> offer.rotationPool), false, false, "", "");
        page = Math.max(0, Math.min(page, Math.min(offers.size(), ServerShop.MAX_OFFERS - 1) / 6));
        List<ServerShopSnapshot.OfferView> views = overview ? offers.stream().skip((long) page * 6).limit(6)
                .map(o -> new ServerShopSnapshot.OfferView(o.id, o.item, o.quantity, o.buyPrice, o.sellPrice, o.stockMode.name(), o.stock, o.rotationPool, false, false, "", "")).toList() : List.of(view);
        state = new ServerShopSnapshot(containerId, true, true, status, shopName, mode.name(), baseRevision,
                overview ? page : Math.max(0, selected), offers.size(), selected, List.of(), views,
                rotation.enabled(), rotation.durationMillis(), rotation.visibleOffers(), rotation.nextRotationAt(), 0, overview);
        PacketDistributor.sendToPlayer(player, state);
    }

    @Override public boolean stillValid(Player player) {
        if (!player.hasPermissions(2)) return false;
        if (player instanceof ServerPlayer serverPlayer) {
            var service = new ServerShopService(serverPlayer.server);
            return service.find(shopId).isPresent() && service.editor(serverPlayer.getUUID())
                    .filter(session -> session.shopId().equals(shopId)).isPresent();
        }
        return true;
    }
    @Override public void broadcastChanges() {
        super.broadcastChanges();
        if (viewer instanceof ServerPlayer player && player.containerMenu == this) {
            int now = player.server.getTickCount();
            if (initialSyncPending && now > openedAtTick) {
                initialSyncPending = false;
                sync(player);
            } else if (!initialSyncPending && now - lastAuthoritativeSyncTick >= 40) sync(player);
        }
    }
    @Override public void removed(Player player) {
        super.removed(player);
        if (player instanceof ServerPlayer serverPlayer) new ServerShopService(serverPlayer.server).endEditing(serverPlayer.getUUID());
    }

    private static ServerShopSnapshot empty(int id, String name, ServerShop.Mode mode) {
        return new ServerShopSnapshot(id, true, false, "", name, mode.name(), 0, 0, 0, -1, List.of(), List.of());
    }

    private static final class DraftOffer {
        private final UUID id; private ItemStack item; private int quantity; private long buyPrice, sellPrice;
        private ServerShop.StockMode stockMode; private long stock; private boolean rotationPool;
        private DraftOffer(ServerShop.Offer offer) { this(offer.id(), offer.item(), offer.quantity(), offer.playerBuyPrice(), offer.playerSellPrice(), offer.stock().mode(), offer.stock().available(), offer.rotationPool()); }
        private DraftOffer(UUID id, ItemStack item, int quantity, long buyPrice, long sellPrice, ServerShop.StockMode stockMode, long stock) {
            this(id, item, quantity, buyPrice, sellPrice, stockMode, stock, false);
        }
        private DraftOffer(UUID id, ItemStack item, int quantity, long buyPrice, long sellPrice, ServerShop.StockMode stockMode, long stock, boolean rotationPool) {
            this.id = id; this.item = item.copyWithCount(1); this.quantity = quantity; this.buyPrice = buyPrice; this.sellPrice = sellPrice; this.stockMode = stockMode; this.stock = stock;
            this.rotationPool = rotationPool;
        }
        private ServerShop.Offer toOffer() {
            return new ServerShop.Offer(id, item, quantity, buyPrice, sellPrice,
                    stockMode == ServerShop.StockMode.INFINITE ? ServerShop.Stock.infinite() : ServerShop.Stock.shared(stock), rotationPool);
        }
    }
}
