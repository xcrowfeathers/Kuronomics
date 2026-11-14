package de.kuro.kuronomics.servershop;

import de.kuro.kuronomics.event.EconomyEvents;
import de.kuro.kuronomics.economy.EconomyService;
import de.kuro.kuronomics.economy.Money;
import de.kuro.kuronomics.economy.StateTreasury;
import de.kuro.kuronomics.economy.data.EconomyLedger;
import de.kuro.kuronomics.mailbox.MailboxService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class ServerShopService {
    private static final String PLAYER_TRADES = "kuronomicsServerShopTransactions";
    private final MinecraftServer server;
    private final ServerShopStore store;

    public record EditorSession(UUID administrator, UUID shopId, long revision) {}
    public record TradeResult(UUID reference, boolean buy, String itemName, int quantity, long amount, long balance,
                              long revision) {}
    public record TradeAvailability(boolean allowed, String reason) {
        private static TradeAvailability allowedTrade() { return new TradeAvailability(true, ""); }
        private static TradeAvailability blocked(String reason) { return new TradeAvailability(false, reason); }
    }

    public ServerShopService(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server"); this.store = ServerShopStore.get(server);
    }

    public ServerShopService(ServerShopStore store) { this.server = null; this.store = Objects.requireNonNull(store, "store"); }

    public ServerShop create(String name) {
        checkThread(); String validName = ServerShop.validateName(name);
        ServerShop shop = new ServerShop(UUID.randomUUID(), validName, ServerShop.Mode.PLAYER_BUYS,
                List.of(), ServerShop.Rotation.disabled(), 0);
        store.create(shop); return shop;
    }

    public Optional<ServerShop> find(UUID id) { checkThread(); return Optional.ofNullable(store.byId(Objects.requireNonNull(id))); }
    public Optional<ServerShop> find(String name) { checkThread(); return Optional.ofNullable(store.byName(name)); }
    public List<ServerShop> shops() { checkThread(); return List.copyOf(store.all()); }
    public List<String> names() {
        checkThread(); return store.all().stream().map(ServerShop::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public ServerShop update(UUID id, long expectedRevision, String name, ServerShop.Mode mode,
                             List<ServerShop.Offer> offers, ServerShop.Rotation rotation) {
        checkThread(); ServerShop current = require(id);
        if (store.trades().stream().anyMatch(trade -> trade.shopId().equals(id) && !trade.settled()))
            throw new IllegalStateException("This server shop has a transaction awaiting recovery. Try again after the player reconnects.");
        if (current.revision() != expectedRevision)
            throw new IllegalStateException("This server shop was changed by another editor. Reopen the editor and try again.");
        rotation = normalizeRotation(current.rotation(), rotation, System.currentTimeMillis());
        ServerShop updated = new ServerShop(current.id(), name, mode, offers, rotation, Math.addExact(current.revision(), 1));
        store.replace(updated); return updated;
    }

    public ServerShop delete(String name) {
        checkThread(); ServerShop shop = require(name);
        if (store.trades().stream().anyMatch(trade -> trade.shopId().equals(shop.id()) && !trade.settled()))
            throw new IllegalStateException("This server shop has a transaction awaiting recovery and cannot be deleted yet.");
        store.delete(shop.id()); return shop;
    }

    public ServerShop activeShop(UUID id) {
        checkThread(); ServerShop shop = require(id); return rotateIfDue(shop, System.currentTimeMillis());
    }

    public List<ServerShop.Offer> activeOffers(ServerShop shop) {
        if (!shop.rotation().enabled()) return shop.offers();
        List<ServerShop.Offer> pool = new ArrayList<>(shop.offers().stream().filter(ServerShop.Offer::rotationPool).toList());
        if (pool.isEmpty()) return shop.offers().stream().filter(offer -> !offer.rotationPool()).toList();
        int count = Math.min(shop.rotation().visibleOffers(), pool.size());
        long seed = shop.id().getMostSignificantBits() ^ Long.rotateLeft(shop.id().getLeastSignificantBits(), 23)
                ^ shop.rotation().sequence() * 0x9E3779B97F4A7C15L;
        Collections.shuffle(pool, new Random(seed));
        Set<UUID> selected = new HashSet<>();
        for (int i = 0; i < count; i++) selected.add(pool.get(i).id());
        return shop.offers().stream().filter(offer -> !offer.rotationPool() || selected.contains(offer.id())).toList();
    }

    public boolean canBuy(ServerPlayer player, ServerShop.Offer offer) {
        return buyAvailability(player, offer).allowed();
    }

    public TradeAvailability buyAvailability(ServerPlayer player, ServerShop.Offer offer) {
        try {
            long amount = Money.positive(offer.playerBuyPrice());
            if (offer.stock().mode() == ServerShop.StockMode.SHARED && offer.stock().available() < offer.quantity())
                return TradeAvailability.blocked("Out of stock");
            var economy = new EconomyService(EconomyLedger.get(server)); economy.requireActive(player.getUUID());
            return economy.getAvailableBalance(player.getUUID()) >= amount && hasCapacity(player.getInventory(), offer.item(), offer.quantity())
                    ? TradeAvailability.allowedTrade() : TradeAvailability.blocked("Missing space or funds");
        } catch (RuntimeException error) { return TradeAvailability.blocked("Account unavailable"); }
    }

    public boolean canSell(ServerPlayer player, ServerShop.Offer offer) {
        return sellAvailability(player, offer).allowed();
    }

    public TradeAvailability sellAvailability(ServerPlayer player, ServerShop.Offer offer) {
        try {
            Money.positive(offer.playerSellPrice()); var economy = new EconomyService(EconomyLedger.get(server)); economy.requireActive(player.getUUID());
            if (dailySellLimitReached(player.getUUID())) return TradeAvailability.blocked("Daily selling limit reached");
            Math.addExact(economy.getBalance(player.getUUID()), offer.playerSellPrice());
            if (offer.stock().mode() == ServerShop.StockMode.SHARED) Math.addExact(offer.stock().available(), offer.quantity());
            return hasExactItems(player.getInventory(), offer.item(), offer.quantity())
                    ? TradeAvailability.allowedTrade() : TradeAvailability.blocked("Missing items");
        } catch (RuntimeException error) { return TradeAvailability.blocked("Account unavailable"); }
    }

    public long dailySellEarned(UUID player) { checkThread(); return store.dailySales(player, currentEpochDay()); }
    public long dailySellLimit() {
        long configured = Money.legacy(EconomyEvents.config().serverShopDailySellLimit());
        return configured == 0 ? Long.MAX_VALUE : configured;
    }
    private boolean dailySellLimitReached(UUID player) { return dailySellEarned(player) >= dailySellLimit(); }
    private static long currentEpochDay() { return LocalDate.now(ZoneId.systemDefault()).toEpochDay(); }

    public TradeResult buy(ServerPlayer player, UUID shopId, UUID offerId, long expectedRevision, UUID reference) {
        return trade(player, shopId, offerId, expectedRevision, reference, ServerShopStore.TradeType.BUY);
    }

    public TradeResult sell(ServerPlayer player, UUID shopId, UUID offerId, long expectedRevision, UUID reference) {
        return trade(player, shopId, offerId, expectedRevision, reference, ServerShopStore.TradeType.SELL);
    }

    private TradeResult trade(ServerPlayer player, UUID shopId, UUID offerId, long expectedRevision, UUID reference,
                              ServerShopStore.TradeType type) {
        checkThread(); Objects.requireNonNull(player); Objects.requireNonNull(reference);
        if (player.server != server) throw new IllegalArgumentException("Player is not connected to this server.");
        ServerShopStore.Trade existing = store.trade(reference);
        ServerShopStore.CompletedTrade completed = store.completedTrade(reference);
        String fingerprint = tradeFingerprint(type, player.getUUID(), shopId, offerId, expectedRevision);
        if (completed != null) {
            if (!completed.fingerprint().equals(fingerprint))
                throw new IllegalArgumentException("Transaction reference was already used for another server shop operation");
            clearPlayerMarker(player, reference);
            return result(completed);
        }
        if (existing != null) {
            if (!existing.fingerprint().equals(fingerprint))
                throw new IllegalArgumentException("Transaction reference was already used for another server shop operation");
            execute(player, existing);
            return result(existing);
        }
        if (new EconomyService(EconomyLedger.get(server)).isTransactionProcessed(reference))
            throw new IllegalArgumentException("Transaction reference was already used for another financial operation");
        ServerShop shop = activeShop(shopId);
        if (shop.revision() != expectedRevision) throw new IllegalStateException("This server shop changed. Please try again.");
        ServerShop.Offer offer = activeOffers(shop).stream().filter(entry -> entry.id().equals(offerId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("This offer is no longer active."));
        boolean buy = type == ServerShopStore.TradeType.BUY;
        if (buy && shop.mode() == ServerShop.Mode.PLAYER_SELLS) throw new IllegalArgumentException("This shop does not sell items.");
        if (!buy && shop.mode() == ServerShop.Mode.PLAYER_BUYS) throw new IllegalArgumentException("This shop does not buy items.");
        long amount = Money.positive(buy ? offer.playerBuyPrice() : offer.playerSellPrice());
        int quantity = offer.quantity();
        var economy = new EconomyService(EconomyLedger.get(server)); economy.requireActive(player.getUUID());
        if (buy) {
            if (offer.stock().mode() == ServerShop.StockMode.SHARED && offer.stock().available() < quantity)
                throw new IllegalArgumentException("This offer is out of stock.");
            if (economy.getAvailableBalance(player.getUUID()) < amount || !hasCapacity(player.getInventory(), offer.item(), quantity))
                throw new IllegalArgumentException("Missing space or funds.");
        } else {
            if (dailySellLimitReached(player.getUUID())) throw new IllegalArgumentException("Daily selling limit reached.");
            if (!hasExactItems(player.getInventory(), offer.item(), quantity))
                throw new IllegalArgumentException("Missing items.");
            Math.addExact(economy.getBalance(player.getUUID()), amount);
            if (offer.stock().mode() == ServerShop.StockMode.SHARED) Math.addExact(offer.stock().available(), quantity);
        }
        ServerShopStore.Trade prepared = new ServerShopStore.Trade(reference, fingerprint, type, player.getUUID(), shopId,
                offerId, offer.item(), quantity, amount, offer.stock().mode() == ServerShop.StockMode.SHARED,
                false, false, false, System.currentTimeMillis());
        store.prepare(prepared);
        // Save the journal before changing money, inventory or stock.
        de.kuro.kuronomics.economy.FinancialPersistence.save(server);
        execute(player, prepared);
        return result(prepared);
    }

    private void execute(ServerPlayer player, ServerShopStore.Trade trade) {
        if (!player.getUUID().equals(trade.playerId())) throw new IllegalArgumentException("Transaction belongs to another player.");
        var economy = new EconomyService(EconomyLedger.get(server));
        String reason = "server shop " + trade.type().name().toLowerCase(Locale.ROOT) + ":" + trade.shopId() + ":" + trade.offerId();
        if (!economy.isTransactionProcessed(trade.reference())) Math.addExact(StateTreasury.balance(EconomyLedger.get(server)),
                trade.type() == ServerShopStore.TradeType.BUY ? trade.amount() : Math.negateExact(trade.amount()));
        if (trade.type() == ServerShopStore.TradeType.BUY) {
            economy.collectState(trade.playerId(), trade.amount(), trade.reference(), reason);
            applyStock(trade);
            saveData();
            if (!hasPlayerMarker(player, trade.reference())) {
                if (!hasCapacity(player.getInventory(), trade.item(), trade.quantity()))
                    throw new IllegalStateException("The purchased items are awaiting inventory space.");
                addItems(player.getInventory(), trade.item(), trade.quantity()); markPlayer(player, trade.reference());
                player.inventoryMenu.sendAllDataToRemote();
                player.playNotifySound(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, net.minecraft.sounds.SoundSource.PLAYERS, .7F, 1.1F);
            }
            de.kuro.kuronomics.economy.PlayerPersistence.save(player);
        } else {
            if (!hasPlayerMarker(player, trade.reference())) {
                if (!hasExactItems(player.getInventory(), trade.item(), trade.quantity()))
                    throw new IllegalStateException("The sale is awaiting the exact required items.");
                removeExactItems(player.getInventory(), trade.item(), trade.quantity()); markPlayer(player, trade.reference());
                player.inventoryMenu.sendAllDataToRemote();
            }
            de.kuro.kuronomics.economy.PlayerPersistence.save(player);
            applyStock(trade);
            economy.payFromState(trade.playerId(), trade.amount(), trade.reference(), reason);
            ServerShopStore.Trade current = store.trade(trade.reference());
            if (!current.dailyLimitApplied()) {
                store.recordDailySale(trade.playerId(), currentEpochDay(), trade.amount());
                store.trade(current.withDailyLimitApplied());
            }
        }
        ServerShopStore.Trade current = store.trade(trade.reference());
        if (!current.settled()) store.trade(current.withSettled());
        try {
            String item = trade.item().getHoverName().getString();
            if (trade.type() == ServerShopStore.TradeType.BUY)
                new MailboxService(server).withdrawal(trade.playerId(), trade.amount(), economy.getBalance(trade.playerId()), trade.reference(), "Bank Shop Purchase: " + item);
            else new MailboxService(server).deposit(trade.playerId(), trade.amount(), economy.getBalance(trade.playerId()), trade.reference(), "Bank Shop Sale: " + item);
        } catch (RuntimeException error) {
            com.mojang.logging.LogUtils.getLogger().error("Server shop transaction completed but mailbox notification failed", error);
        }
        store.completeTrade(trade.reference()); saveData(); clearPlayerMarker(player, trade.reference());
    }

    private void applyStock(ServerShopStore.Trade trade) {
        ServerShopStore.Trade currentTrade = store.trade(trade.reference());
        if (currentTrade.stockApplied()) return;
        if (!currentTrade.sharedStock()) { store.trade(currentTrade.withStockApplied()); return; }
        ServerShop shop = require(currentTrade.shopId()); List<ServerShop.Offer> offers = new ArrayList<>(shop.offers());
        int index = -1; for (int i = 0; i < offers.size(); i++) if (offers.get(i).id().equals(currentTrade.offerId())) { index = i; break; }
        if (index < 0) throw new IllegalStateException("The transaction offer no longer exists.");
        ServerShop.Offer offer = offers.get(index);
        if (!ItemStack.isSameItemSameComponents(offer.item(), currentTrade.item()) || offer.quantity() != currentTrade.quantity())
            throw new IllegalStateException("The transaction offer was changed while settlement was pending.");
        long stock = currentTrade.type() == ServerShopStore.TradeType.BUY
                ? Math.subtractExact(offer.stock().available(), currentTrade.quantity())
                : Math.addExact(offer.stock().available(), currentTrade.quantity());
        if (stock < 0) throw new IllegalStateException("This offer is out of stock.");
        offers.set(index, new ServerShop.Offer(offer.id(), offer.item(), offer.quantity(), offer.playerBuyPrice(), offer.playerSellPrice(),
                ServerShop.Stock.shared(stock), offer.rotationPool()));
        store.replace(new ServerShop(shop.id(), shop.name(), shop.mode(), offers, shop.rotation(), Math.addExact(shop.revision(), 1)));
        store.trade(currentTrade.withStockApplied());
    }

    public void recover(ServerPlayer player) {
        checkThread();
        for (ServerShopStore.Trade trade : store.trades()) if (trade.playerId().equals(player.getUUID())) {
            try { execute(player, trade); }
            catch (RuntimeException error) { com.mojang.logging.LogUtils.getLogger().error("Could not recover server shop transaction {}", trade.reference(), error); }
        }
    }

    public void processStartup() {
        checkThread(); processOverdueRotations();
        for (ServerShopStore.Trade trade : store.trades()) {
            try {
                if (trade.settled()) { store.completeTrade(trade.reference()); saveData(); continue; }
                if (trade.type() != ServerShopStore.TradeType.BUY) continue;
                var economy = new EconomyService(EconomyLedger.get(server));
                economy.collectState(trade.playerId(), trade.amount(), trade.reference(), "server shop buy:" + trade.shopId() + ":" + trade.offerId());
                applyStock(trade); saveData();
            } catch (RuntimeException error) { com.mojang.logging.LogUtils.getLogger().error("Could not recover server shop purchase {}", trade.reference(), error); }
        }
    }

    public Set<UUID> processOverdueRotations() {
        checkThread(); long now = System.currentTimeMillis();
        if (store.nextRotationDeadline() > now) return Set.of();
        Set<UUID> changed = new HashSet<>();
        for (ServerShop shop : List.copyOf(store.all())) {
            ServerShop rotated = rotateIfDue(shop, now); if (rotated != shop) changed.add(shop.id());
        }
        return Set.copyOf(changed);
    }

    private ServerShop rotateIfDue(ServerShop shop, long now) {
        ServerShop.Rotation rotation = shop.rotation();
        if (!rotation.enabled() || rotation.nextRotationAt() > now) return shop;
        long overdue = Math.max(0, now - rotation.nextRotationAt()); long steps = 1 + overdue / rotation.durationMillis();
        int poolSize = (int) shop.offers().stream().filter(ServerShop.Offer::rotationPool).count();
        long sequence = poolSize == 0 ? 0 : rotation.sequence() > Long.MAX_VALUE - steps ? 0 : rotation.sequence() + steps;
        long next;
        try { next = Math.addExact(rotation.nextRotationAt(), Math.multiplyExact(steps, rotation.durationMillis())); }
        catch (ArithmeticException overflow) { next = Math.addExact(now, rotation.durationMillis()); }
        var advanced = new ServerShop.Rotation(true, rotation.durationMillis(), rotation.visibleOffers(), next, sequence);
        ServerShop updated = new ServerShop(shop.id(), shop.name(), shop.mode(), shop.offers(), advanced, Math.addExact(shop.revision(), 1));
        store.replace(updated);
        // Rotation ticks only change timing/bookkeeping. Update open editors to the new baseline
        // so their otherwise valid drafts aren't rejected as stale.
        store.advanceEditors(shop.id(), shop.revision(), updated.revision());
        return updated;
    }

    private static ServerShop.Rotation normalizeRotation(ServerShop.Rotation old, ServerShop.Rotation requested, long now) {
        if (!requested.enabled()) return ServerShop.Rotation.disabled();
        boolean unchanged = old.enabled() && old.durationMillis() == requested.durationMillis()
                && old.visibleOffers() == requested.visibleOffers();
        long deadline = unchanged && old.nextRotationAt() > 0 ? old.nextRotationAt()
                : !old.enabled() && requested.nextRotationAt() > 0 ? requested.nextRotationAt()
                : Math.addExact(now, requested.durationMillis());
        return new ServerShop.Rotation(true, requested.durationMillis(), requested.visibleOffers(),
                deadline, unchanged ? old.sequence() : requested.sequence());
    }

    private TradeResult result(ServerShopStore.Trade trade) {
        ServerShop shop = require(trade.shopId());
        return new TradeResult(trade.reference(), trade.type() == ServerShopStore.TradeType.BUY,
                trade.item().getHoverName().getString(), trade.quantity(), trade.amount(),
                new EconomyService(EconomyLedger.get(server)).getBalance(trade.playerId()), shop.revision());
    }
    private TradeResult result(ServerShopStore.CompletedTrade trade) {
        ServerShop shop = store.byId(trade.shopId());
        return new TradeResult(trade.reference(), trade.type() == ServerShopStore.TradeType.BUY,
                trade.itemName(), trade.quantity(), trade.amount(),
                new EconomyService(EconomyLedger.get(server)).getBalance(trade.playerId()), shop == null ? 0 : shop.revision());
    }

    private static String tradeFingerprint(ServerShopStore.TradeType type, UUID player, UUID shop, UUID offer, long revision) {
        return type + "|" + player + "|" + shop + "|" + offer + "|" + revision;
    }

    private static boolean hasCapacity(Inventory inventory, ItemStack template, int count) {
        long room = 0; int max = Math.min(inventory.getMaxStackSize(template), template.getMaxStackSize());
        for (ItemStack stack : inventory.items) {
            if (stack.isEmpty()) room += max;
            else if (ItemStack.isSameItemSameComponents(stack, template)) room += Math.max(0, max - stack.getCount());
            if (room >= count) return true;
        }
        return false;
    }
    private static void addItems(Inventory inventory, ItemStack template, int count) {
        int remaining = count, max = Math.min(inventory.getMaxStackSize(template), template.getMaxStackSize());
        for (ItemStack stack : inventory.items) if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, template) && remaining > 0) {
            int moved = Math.min(remaining, Math.max(0, max - stack.getCount())); stack.grow(moved); remaining -= moved;
        }
        for (int i = 0; i < inventory.items.size() && remaining > 0; i++) if (inventory.items.get(i).isEmpty()) {
            int moved = Math.min(remaining, max); inventory.items.set(i, template.copyWithCount(moved)); remaining -= moved;
        }
        if (remaining != 0) throw new IllegalStateException("Inventory capacity changed during purchase");
        inventory.setChanged();
    }
    private static boolean hasExactItems(Inventory inventory, ItemStack template, int count) {
        long found = 0; for (ItemStack stack : inventory.items) if (ItemStack.isSameItemSameComponents(stack, template)) {
            found += stack.getCount(); if (found >= count) return true;
        } return false;
    }
    private static void removeExactItems(Inventory inventory, ItemStack template, int count) {
        int remaining = count;
        for (int i = 0; i < inventory.items.size() && remaining > 0; i++) {
            ItemStack stack = inventory.items.get(i); if (!ItemStack.isSameItemSameComponents(stack, template)) continue;
            int removed = Math.min(remaining, stack.getCount()); stack.shrink(removed); remaining -= removed;
            if (stack.isEmpty()) inventory.items.set(i, ItemStack.EMPTY);
        }
        if (remaining != 0) throw new IllegalStateException("Required items changed during sale"); inventory.setChanged();
    }
    private static boolean hasPlayerMarker(ServerPlayer player, UUID reference) {
        for (Tag raw : player.getPersistentData().getList(PLAYER_TRADES, Tag.TAG_INT_ARRAY))
            if (reference.equals(NbtUtils.loadUUID(raw))) return true;
        return false;
    }
    private static void markPlayer(ServerPlayer player, UUID reference) {
        if (hasPlayerMarker(player, reference)) return;
        ListTag list = player.getPersistentData().getList(PLAYER_TRADES, Tag.TAG_INT_ARRAY); list.add(NbtUtils.createUUID(reference));
        player.getPersistentData().put(PLAYER_TRADES, list);
    }
    private static void clearPlayerMarker(ServerPlayer player, UUID reference) {
        de.kuro.kuronomics.economy.FinancialWriter.await(player.server); // Wait for the completed transaction to reach disk before clearing its recovery marker.

        ListTag list = player.getPersistentData().getList(PLAYER_TRADES, Tag.TAG_INT_ARRAY);
        boolean removed = list.removeIf(raw -> reference.equals(NbtUtils.loadUUID(raw)));
        if (!removed) return;
        if (list.isEmpty()) player.getPersistentData().remove(PLAYER_TRADES); else player.getPersistentData().put(PLAYER_TRADES, list);
        
    }

    public EditorSession beginEditing(ServerPlayer administrator, String name) {
        checkThread(); requireAdministrator(administrator); ServerShop shop = require(name);
        store.edit(administrator.getUUID(), shop);
        return new EditorSession(administrator.getUUID(), shop.id(), shop.revision());
    }

    public Optional<EditorSession> editor(UUID administrator) {
        checkThread(); var state = store.editor(Objects.requireNonNull(administrator));
        if (state == null || store.byId(state.shopId()) == null) {
            if (state != null) store.clearEditor(administrator);
            return Optional.empty();
        }
        return Optional.of(new EditorSession(administrator, state.shopId(), state.revision()));
    }

    public void endEditing(UUID administrator) { checkThread(); store.clearEditor(Objects.requireNonNull(administrator)); }

    /** Editor packets must go through this path so the server checks permissions, targets and stale revisions. */
    public ServerShop updateFromEditor(ServerPlayer administrator, String name, ServerShop.Mode mode,
                                       List<ServerShop.Offer> offers, ServerShop.Rotation rotation) {
        checkThread(); requireAdministrator(administrator);
        EditorSession session = editor(administrator.getUUID())
                .orElseThrow(() -> new IllegalStateException("No server shop editor is open."));
        ServerShop updated = update(session.shopId(), session.revision(), name, mode, offers, rotation);
        store.edit(administrator.getUUID(), updated); return updated;
    }

    private ServerShop require(UUID id) {
        ServerShop shop = store.byId(Objects.requireNonNull(id));
        if (shop == null) throw new IllegalArgumentException("This server shop no longer exists.");
        return shop;
    }
    private ServerShop require(String name) {
        ServerShop shop = store.byName(name);
        if (shop == null) throw new IllegalArgumentException("No server shop named '" + name + "' exists.");
        return shop;
    }
    private void requireAdministrator(ServerPlayer player) {
        if (server == null || player.server != server || !player.hasPermissions(2))
            throw new IllegalArgumentException("You do not have permission to edit server shops.");
    }
    private void checkThread() {
        if (server != null && !server.isSameThread()) throw new IllegalStateException("Server shops accessed off server thread");
    }
    private void saveData() { de.kuro.kuronomics.economy.FinancialPersistence.save(server); }
}
