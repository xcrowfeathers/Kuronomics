package de.kuro.kuronomics.menu;

import de.kuro.kuronomics.block.ServerShopBlockEntity;
import de.kuro.kuronomics.economy.EconomyService;
import de.kuro.kuronomics.economy.data.EconomyLedger;
import de.kuro.kuronomics.network.ServerShopAction;
import de.kuro.kuronomics.network.ServerShopSnapshot;
import de.kuro.kuronomics.registry.ModContent;
import de.kuro.kuronomics.servershop.ServerShop;
import de.kuro.kuronomics.servershop.ServerShopService;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ServerShopTerminalMenu extends AbstractContainerMenu {
    private static final int PAGE_SIZE = 6;
    public final BlockPos pos;
    public ServerShopSnapshot state;
    private final ServerShopBlockEntity terminal;
    private final Player viewer;
    private final int openedAtTick;
    private boolean initialSyncPending = true;
    private boolean refreshPending;
    private int lastAuthoritativeSyncTick;
    private final LinkedHashSet<UUID> requests = new LinkedHashSet<>();
    private int page, selected;
    private boolean selectionInitialized;
    private String status = "";

    public ServerShopTerminalMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(id, inventory, buffer.readBlockPos(), null);
    }

    private ServerShopTerminalMenu(int id, Inventory inventory, BlockPos pos, ServerShopBlockEntity terminal) {
        super(ModContent.SERVER_SHOP_TERMINAL_MENU.get(), id); this.pos = pos; this.terminal = terminal;
        this.viewer = inventory.player;
        this.openedAtTick = viewer instanceof ServerPlayer serverPlayer ? serverPlayer.server.getTickCount() : -1;
        this.lastAuthoritativeSyncTick = openedAtTick;
        state = empty(id, inventory.player.hasPermissions(2));
    }

    public ServerShopTerminalMenu(int id, Inventory inventory, ServerShopBlockEntity terminal) {
        this(id, inventory, terminal.getBlockPos(), terminal);
    }

    public static void open(ServerPlayer player, ServerShopBlockEntity terminal) {
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new ServerShopTerminalMenu(id, inv, terminal.getBlockPos(), terminal),
                Component.literal("Server Shop")), b -> b.writeBlockPos(terminal.getBlockPos()));
        if (player.containerMenu instanceof ServerShopTerminalMenu menu) menu.sync(player);
    }

    public void apply(ServerShopSnapshot snapshot) { state = snapshot; }

    public void action(ServerPlayer player, ServerShopAction action) {
        if (player.containerMenu != this || !stillValid(player)) return;
        boolean transaction = action.action().equals("buy") || action.action().equals("sell");
        if (!transaction && !remember(action.requestId())) return;
        try {
            switch (action.action()) {
                case "previous" -> page = Math.max(0, page - 1);
                case "next" -> page++;
                case "choice_previous" -> { requireAdmin(player); selected--; }
                case "choice_next" -> { requireAdmin(player); selected++; }
                case "bind" -> {
                    requireAdmin(player); List<ServerShop> choices = choices(player);
                    if (choices.isEmpty()) throw new IllegalArgumentException("No server shops exist yet.");
                    selected = Math.floorMod(selected, choices.size());
                    if (!terminal.bind(player, choices.get(selected).id())) throw new IllegalArgumentException("Could not bind this terminal.");
                    status = "Terminal bound to " + choices.get(selected).name();
                    refreshShop(player.server, choices.get(selected).id());
                }
                case "unbind" -> {
                    requireAdmin(player); UUID old = terminal.boundShopId().orElse(null);
                    if (!terminal.bind(player, null)) throw new IllegalArgumentException("Could not unbind this terminal.");
                    status = "Terminal unbound"; if (old != null) refreshShop(player.server, old);
                }
                case "buy", "sell" -> {
                    UUID shopId = terminal.boundShopId().orElseThrow(() -> new IllegalArgumentException("No server shop setup"));
                    if (action.targetId().getMostSignificantBits() == 0 && action.targetId().getLeastSignificantBits() == 0)
                        throw new IllegalArgumentException("Invalid server shop offer.");
                    var service = new ServerShopService(player.server);
                    var result = action.action().equals("buy")
                            ? service.buy(player, shopId, action.targetId(), action.revision(), action.requestId())
                            : service.sell(player, shopId, action.targetId(), action.revision(), action.requestId());
                    status = (result.buy() ? "Bought " : "Sold ") + result.quantity() + "x " + result.itemName()
                            + " for " + de.kuro.kuronomics.economy.Money.format(result.amount());
                    refreshShop(player.server, shopId);
                }
                default -> throw new IllegalArgumentException("Invalid server shop action.");
            }
        } catch (IllegalArgumentException | IllegalStateException ex) { status = ex.getMessage(); }
        if (lastAuthoritativeSyncTick == player.server.getTickCount()) refreshPending = true; else sync(player);
    }

    private void requireAdmin(ServerPlayer player) {
        if (!player.hasPermissions(2)) throw new IllegalArgumentException("You do not have permission to bind server shops.");
    }

    private List<ServerShop> choices(ServerPlayer player) {
        return new ServerShopService(player.server).shops().stream()
                .sorted(Comparator.comparing(ServerShop::name, String.CASE_INSENSITIVE_ORDER).thenComparing(ServerShop::id)).toList();
    }

    public void sync(ServerPlayer player) {
        if (terminal == null) return;
        lastAuthoritativeSyncTick = player.server.getTickCount(); refreshPending = false;
        Optional<ServerShop> bound = terminal.boundShop(player.serverLevel());
        var service = new ServerShopService(player.server);
        if (bound.isPresent()) bound = Optional.of(service.activeShop(bound.get().id()));
        List<ServerShop> shops = player.hasPermissions(2) ? choices(player) : List.of();
        if (!shops.isEmpty()) {
            int boundIndex = bound.map(shop -> shops.indexOf(shop)).orElse(-1);
            if (!selectionInitialized) { selected = Math.max(0, boundIndex); selectionInitialized = true; }
            selected = Math.floorMod(selected, shops.size());
        } else selected = 0;
        List<ServerShopSnapshot.OfferView> views = List.of(); String name = "No server shop setup", mode = ""; long revision = 0; int total = 0;
        if (bound.isPresent()) {
            ServerShop shop = bound.get(); name = shop.name(); mode = shop.mode().name(); revision = shop.revision();
            List<ServerShop.Offer> activeOffers = service.activeOffers(shop); total = activeOffers.size();
            int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE); page = Math.max(0, Math.min(page, pages - 1));
            int from = page * PAGE_SIZE, to = Math.min(total, from + PAGE_SIZE); var list = new ArrayList<ServerShopSnapshot.OfferView>(to - from);
            for (int i = from; i < to; i++) {
                ServerShop.Offer offer = activeOffers.get(i);
                boolean buyAllowed = shop.mode() != ServerShop.Mode.PLAYER_SELLS;
                boolean sellAllowed = shop.mode() != ServerShop.Mode.PLAYER_BUYS;
                var buyAvailability = buyAllowed ? service.buyAvailability(player, offer)
                        : new ServerShopService.TradeAvailability(false, "This shop does not sell items");
                var sellAvailability = sellAllowed ? service.sellAvailability(player, offer)
                        : new ServerShopService.TradeAvailability(false, "This shop does not buy items");
                list.add(new ServerShopSnapshot.OfferView(offer.id(), offer.item(), offer.quantity(), offer.playerBuyPrice(), offer.playerSellPrice(),
                        offer.stock().mode().name(), offer.stock().available(), offer.rotationPool(),
                        buyAllowed && buyAvailability.allowed(), sellAllowed && sellAvailability.allowed(),
                        buyAvailability.reason(), sellAvailability.reason()));
            }
            views = List.copyOf(list);
        } else page = 0;
        ServerShop.Rotation rotation = bound.map(ServerShop::rotation).orElse(ServerShop.Rotation.disabled());
        state = new ServerShopSnapshot(containerId, false, player.hasPermissions(2), status, name, mode, revision,
                page, total, selected, shops.stream().map(ServerShop::name).toList(), views,
                rotation.enabled(), rotation.durationMillis(), rotation.visibleOffers(), rotation.nextRotationAt(),
                new EconomyService(EconomyLedger.get(player.server)).getBalance(player.getUUID()));
        PacketDistributor.sendToPlayer(player, state);
    }

    public static void refreshShop(net.minecraft.server.MinecraftServer server, UUID shopId) {
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            if (player.containerMenu instanceof ServerShopTerminalMenu menu && menu.terminal != null
                    && menu.terminal.boundShopId().filter(shopId::equals).isPresent()) menu.refreshPending = true;
    }

    public static void refreshAll(net.minecraft.server.MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            if (player.containerMenu instanceof ServerShopTerminalMenu menu && menu.terminal != null) menu.refreshPending = true;
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

    private boolean remember(UUID request) {
        if (!requests.add(request)) return false;
        if (requests.size() > 128) requests.remove(requests.iterator().next());
        return true;
    }

    @Override public boolean stillValid(Player player) {
        return terminal == null ? player.level().getBlockState(pos).is(ModContent.SERVER_SHOP_TERMINAL_BLOCK.get())
                : !terminal.isRemoved() && player.level().getBlockEntity(pos) == terminal
                && player.level().getBlockState(pos).is(ModContent.SERVER_SHOP_TERMINAL_BLOCK.get())
                && player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) <= 64;
    }

    @Override public net.minecraft.world.item.ItemStack quickMoveStack(Player player, int slot) { return net.minecraft.world.item.ItemStack.EMPTY; }

    private static ServerShopSnapshot empty(int id, boolean admin) {
        return new ServerShopSnapshot(id, false, admin, "", "No server shop setup", "", 0, 0, 0, 0, List.of(), List.of());
    }
}
