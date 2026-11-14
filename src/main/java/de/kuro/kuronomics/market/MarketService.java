package de.kuro.kuronomics.market;

import de.kuro.kuronomics.economy.*;
import de.kuro.kuronomics.economy.data.*;
import de.kuro.kuronomics.util.PlayerNames;
import java.util.*;
import java.time.Instant;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Money and virtual stock change in one server-thread ledger transaction, without needing a loaded chunk. */
public final class MarketService {
    private static final String MARKERS = "kuronomicsMarketTransfers";
    private static final Map<MinecraftServer, MarketService> SERVICES = new WeakHashMap<>();
    private static double currentTax = 3;
    private final MinecraftServer server;
    private final EconomyLedger ledger;
    private MarketConfig config;
    private long catalogRevision = -1;
    private List<UUID> ordered = List.of();
    private record Query(String text, int filter) {}
    private final LinkedHashMap<Query, List<UUID>> queries = new LinkedHashMap<>(32, .75f, true);
    private MarketService(MinecraftServer server) {
        this.server = server; ledger = EconomyLedger.get(server); config = de.kuro.kuronomics.event.EconomyEvents.config().marketplace(); currentTax = config.salesTaxPercent().doubleValue();
        page("", 0, null, 0); // Build the catalog on startup so the first player to open it doesn't pay that cost.
    }
    public static MarketService get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Marketplace accessed off server thread");
        return SERVICES.computeIfAbsent(server, MarketService::new);
    }
    public static void stop(MinecraftServer server) { SERVICES.remove(server); }
    public static double getCurrentTaxPercent() { return currentTax; }
    public MarketConfig config() { return config; }
    public void reload() { de.kuro.kuronomics.event.EconomyEvents.reload(server); }
    public static void configure(MinecraftServer server, MarketConfig replacement) {
        currentTax = replacement.salesTaxPercent().doubleValue();
        var service = SERVICES.get(server);
        if (service != null) {
            service.config = replacement;
            de.kuro.kuronomics.menu.MarketMenu.refresh(server, null, true);
        }
    }
    public record Page(List<MarketListing> listings, int total, int page) {}
    public Page page(String query, int filter, EconomicOwner owner, int requested) {
        if (query.length() > 128 || filter < 0 || filter > 2) throw new IllegalArgumentException("Invalid marketplace search");
        var book = ledger.market(); List<UUID> ids;
        if (owner != null) ids = book.owned(owner).stream().sorted(order(book)).toList();
        else {
            if (catalogRevision != book.catalogRevision()) {
                ordered = new ArrayList<>(book.listings().values().stream().sorted(Comparator.comparingLong(MarketListing::createdAt).reversed().thenComparing(MarketListing::id)).map(MarketListing::id).toList());
                queries.clear(); catalogRevision = book.catalogRevision();
            }
            Query key = new Query(query.strip().toLowerCase(Locale.ROOT), filter);
            ids = queries.get(key);
            if (ids == null) {
                String[] terms = key.text().isEmpty() ? new String[0] : key.text().split("\\s+");
                ids = ordered.stream().filter(id -> { var l = book.listing(id);
                    if (filter == 1 && !l.seller().isPlayer() || filter == 2 && !l.seller().isCompany()) return false;
                    for (String term : terms) if (!l.searchText().contains(term)) return false; return true;
                }).toList();
                if (queries.size() >= 32) queries.remove(queries.keySet().iterator().next()); queries.put(key, ids);
            }
        }
        int page = Math.max(0, Math.min(requested, Math.max(0, (ids.size() - 1) / 4))), from = page * 4;
        return new Page(ids.subList(from, Math.min(ids.size(), from + 4)).stream().map(book::listing).toList(), ids.size(), page);
    }
    private static Comparator<UUID> order(MarketBook book) { return Comparator.<UUID>comparingLong(id -> book.listing(id).createdAt()).reversed().thenComparing(Comparator.naturalOrder()); }
    public int limit(EconomicOwner owner) {
        if (owner.isPlayer()) return config.maxPrivateListings();
        var company = ledger.companies().company(owner.id());
        return company == null ? 0 : (int) Math.min(config.maxCompanyListings(), config.companyBaseListings() + (long) config.companyListingsPerEmployee() * company.members().size());
    }
    public boolean active(EconomicOwner owner) {
        var company = owner.isCompany() ? ledger.companies().company(owner.id()) : null;
        return owner.isPlayer() || company != null && company.status() == de.kuro.kuronomics.company.Company.Status.ACTIVE;
    }
    public MarketListing require(UUID id) {
        MarketListing listing = ledger.market().listing(id);
        if (listing == null) throw new IllegalArgumentException("This listing no longer exists.");
        if (ledger.market().locked(id)) throw new IllegalArgumentException("This listing has an inventory transfer waiting for recovery.");
        return listing;
    }
    private void authorize(ServerPlayer player, MarketListing listing, boolean senior) {
        if (!MarketPermissions.allowed(ledger, listing.seller(), player.getUUID(), senior)) throw new IllegalArgumentException("You cannot manage this listing.");
    }
    private boolean begin(ServerPlayer player, UUID request) {
        if (!server.isSameThread() || player.server != server) throw new IllegalStateException("Marketplace operation off its server thread");
        FinancialWriter.check(server);
        recover(player);
        if (ledger.hasTransaction(request)) return false;
        new EconomyService(ledger).requireActive(player.getUUID()); return true;
    }
    public UUID create(ServerPlayer player, EconomicOwner owner, int slot, ItemStack expected, long price, UUID request) {
        if (!begin(player, request)) return null;
        if (!MarketPermissions.allowed(ledger, owner, player.getUUID(), true)) throw new IllegalArgumentException("You need Manager or higher to create a company listing.");
        if (ledger.market().owned(owner).size() >= limit(owner)) throw new IllegalArgumentException("This seller has reached its listing limit.");
        Money.positive(price); ItemStack stack = inventoryStack(player, slot);
        if (expected.isEmpty() || !ItemStack.matches(stack, expected)) throw new IllegalArgumentException("The selected stack changed. Select it again.");
        CompoundTag itemData = (CompoundTag) stack.copyWithCount(1).save(server.registryAccess());
        // Apply the configured item-data limit, then save the same frozen template we checked.
        try {
            if (config.maxItemDataKiB() > 0) {
                var bytes = new java.io.ByteArrayOutputStream(); NbtIo.write(itemData, new java.io.DataOutputStream(bytes));
                if ((long) bytes.size() > (long) config.maxItemDataKiB() * 1024)
                    throw new IllegalArgumentException("This item's data is too large for a marketplace listing (" + config.maxItemDataKiB() + " KiB maximum).");
            }
        } catch (java.io.IOException impossible) { throw new IllegalArgumentException("Could not save this item.", impossible); }
        UUID id = EconomyService.stableReference("market-listing", request.toString());
        String search = (stack.getHoverName().getString() + " " + BuiltInRegistries.ITEM.getKey(stack.getItem()) + " " + PlayerNames.owner(server, owner)).toLowerCase(Locale.ROOT);
        var listing = new MarketListing(id, owner, stack, price, stack.getCount(), 0, System.currentTimeMillis(), search,
                itemData);
        var change = new MarketTransfer.Change(slot, stack, ItemStack.EMPTY);
        transact(player, request, id, listing, List.of(change), "create", 0, null); return id;
    }
    public void restock(ServerPlayer player, UUID id, int quantity, UUID request) {
        if (!begin(player, request)) return;
        var listing = require(id); authorize(player, listing, false);
        if (quantity <= 0) throw new IllegalArgumentException("Enter a positive whole-number quantity.");
        ItemStack template = listing.item(); List<MarketTransfer.Change> changes = new ArrayList<>(); int left = quantity;
        for (int slot = 0; slot < 36 && left > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!ItemStack.isSameItemSameComponents(template, stack)) continue;
            int taken = Math.min(left, stack.getCount());
            changes.add(new MarketTransfer.Change(slot, stack, stack.copyWithCount(stack.getCount() - taken))); left -= taken;
        }
        if (left != 0) throw new IllegalArgumentException(changes.isEmpty() ? "You don't have matching items." : "Not enough matching items for that quantity.");
        transact(player, request, id, listing.stock(Math.addExact(listing.stock(), quantity)), changes, "restock", 0, null);
    }
    public void price(ServerPlayer player, UUID id, long price, long expectedRevision, UUID request) {
        if (!begin(player, request)) return;
        var listing = require(id); authorize(player, listing, false); Money.positive(price); checkRevision(listing, expectedRevision);
        ledger.commitMarket(request, "market-price|" + player.getUUID() + "|" + id + "|" + price, Map.of(), List.of(), ledger.companies(), ledger.market().update(id, listing.price(price), null));
        FinancialPersistence.save(server); changed(id, false);
    }
    public void buy(ServerPlayer player, UUID id, int quantity, long expectedRevision, UUID request) {
        if (!begin(player, request)) return;
        if (!config.open(Instant.now())) throw new IllegalArgumentException("Marketplace Closed · " + config.hours());
        var listing = require(id); checkRevision(listing, expectedRevision);
        if (!active(listing.seller())) throw new IllegalArgumentException("The owning company is closed.");
        if (listing.seller().equals(EconomicOwner.player(player.getUUID()))) throw new IllegalArgumentException("You cannot buy your own listing.");
        if (quantity <= 0 || quantity > listing.stock()) throw new IllegalArgumentException("Choose a quantity between 1 and the available stock.");
        long total;
        try { total = Math.multiplyExact(listing.price(), (long) quantity); }
        catch (ArithmeticException overflow) { throw new IllegalArgumentException("The total price is too large."); }
        if (new EconomyService(ledger).getAvailableBalance(player.getUUID()) < total) throw new IllegalArgumentException("Not enough money.");
        List<MarketTransfer.Change> changes = delivery(player.getInventory(), listing.item(), quantity);
        transact(player, request, id, listing.stock(listing.stock() - quantity), changes, "buy", total, listing.seller());
    }
    public void withdraw(ServerPlayer player, UUID id, int quantity, boolean remove, UUID request) { withdraw(player, id, quantity, remove, false, request); }
    public void reclaim(ServerPlayer player, UUID id, UUID request) { withdraw(player, id, 0, true, true, request); }
    private void withdraw(ServerPlayer player, UUID id, int quantity, boolean remove, boolean reclaim, UUID request) {
        if (!begin(player, request)) return;
        var listing = require(id);
        if (reclaim) {
            if (!player.hasPermissions(2) || !listing.seller().isCompany() || active(listing.seller())) throw new IllegalArgumentException("Only admins may recover stock from a closed company.");
        } else authorize(player, listing, true);
        if (remove) quantity = Math.toIntExact(listing.stock());
        if (quantity < 0 || !remove && quantity == 0 || quantity > listing.stock()) throw new IllegalArgumentException("Invalid withdrawal quantity.");
        if (quantity == 0) {
            ledger.commitMarket(request, "market-remove|" + id + "|" + player.getUUID(), Map.of(), List.of(), ledger.companies(), ledger.market().update(id, null, null));
            FinancialPersistence.save(server); changed(id, true); return;
        }
        List<MarketTransfer.Change> changes = delivery(player.getInventory(), listing.item(), quantity);
        transact(player, request, id, remove ? null : listing.stock(listing.stock() - quantity), changes, "withdraw", 0, null);
    }
    private static void checkRevision(MarketListing listing, long expected) {
        if (listing.revision() != expected) throw new IllegalArgumentException("This listing changed. Review its current price and stock.");
    }
    private void transact(ServerPlayer player, UUID request, UUID id, MarketListing next, List<MarketTransfer.Change> changes, String action, long price, EconomicOwner seller) {
        var operation = new MarketTransfer(request, player.getUUID(), id, changes).freeze(server.registryAccess());
        boolean structural = ledger.market().listing(id) == null || next == null;
        MarketBook before = ledger.market(), planned = before.update(id, next, operation);
        // Newly mined or moved items might not be in the player's last normal save.
        // Save this inventory baseline so recovery compares slots against it, not against an older inventory.
        PlayerPersistence.save(player);
        if (ledger.market() != before) throw new IllegalArgumentException("The marketplace changed while saving your inventory. Please try again.");
        if (seller != null) new EconomyService(ledger).fundedSale(player.getUUID(), seller, price, config.salesTaxPercent(), request, "Marketplace Sales", planned);
        else ledger.commitMarket(request, "market-" + action + "|" + player.getUUID() + "|" + id, Map.of(), List.of(), ledger.companies(), planned);
        FinancialPersistence.save(server); // Save the money, stock and recovery request before changing inventory.
        recover(player); changed(id, structural);
    }
    public void recover(ServerPlayer player) {
        if (!server.isSameThread() || player.server != server) throw new IllegalStateException("Marketplace recovery off its server thread");
        MarketTransfer transfer = ledger.market().pending(player.getUUID());
        if (transfer == null) {
            for (Tag raw : player.getPersistentData().getList(MARKERS, Tag.TAG_INT_ARRAY))
                if (!ledger.hasTransaction(NbtUtils.loadUUID(raw))) throw new IllegalStateException("Market inventory marker has no transaction evidence.");
            return; // Keep the last saved marker until a newer transaction replaces it.
        }
        FinancialWriter.check(server);
        if (!hasMarker(player, transfer.id())) {
            for (var change : transfer.changes()) if (!ItemStack.matches(player.getInventory().getItem(change.slot()), change.before()))
                throw new IllegalStateException("Marketplace inventory recovery needs review; the reserved inventory slots changed.");
            for (var change : transfer.changes()) player.getInventory().setItem(change.slot(), change.after());
        }
        ListTag markers = new ListTag(); markers.add(NbtUtils.createUUID(transfer.id())); player.getPersistentData().put(MARKERS, markers);
        PlayerPersistence.save(player);
        ledger.commitMarket(EconomyService.stableReference("market-finish", transfer.id().toString()), "market-finish|" + transfer.id(), Map.of(), List.of(), ledger.companies(), ledger.market().finish(transfer));
        // Keep the marker in memory too; logout might save the player before batched ledger cleanup.
        // Only replace it after the next stock/money commit has saved that cleanup.
        player.getInventory().setChanged(); changed(transfer.listing(), false);
    }
    private static boolean hasMarker(ServerPlayer player, UUID id) {
        for (Tag raw : player.getPersistentData().getList(MARKERS, Tag.TAG_INT_ARRAY)) if (id.equals(NbtUtils.loadUUID(raw))) return true; return false;
    }
    private static ItemStack inventoryStack(ServerPlayer player, int slot) {
        if (slot < 0 || slot >= 36) throw new IllegalArgumentException("Choose an inventory stack.");
        ItemStack stack = player.getInventory().getItem(slot); if (stack.isEmpty()) throw new IllegalArgumentException("Choose an inventory stack."); return stack.copy();
    }
    public static List<MarketTransfer.Change> delivery(Inventory inventory, ItemStack item, int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("Quantity must be a positive whole number.");
        List<MarketTransfer.Change> changes = new ArrayList<>(); int left = quantity;
        for (int pass = 0; pass < 2 && left > 0; pass++) for (int slot = 0; slot < 36 && left > 0; slot++) {
            ItemStack before = inventory.getItem(slot);
            if (pass == 0 ? before.isEmpty() || !ItemStack.isSameItemSameComponents(before, item) : !before.isEmpty()) continue;
            int capacity = Math.min(inventory.getMaxStackSize(item), item.getMaxStackSize()) - before.getCount();
            int count = Math.min(left, Math.max(0, capacity)); if (count == 0) continue;
            changes.add(new MarketTransfer.Change(slot, before, item.copyWithCount(before.getCount() + count))); left -= count;
        }
        if (left != 0) throw new IllegalArgumentException("Not enough inventory space for all requested items.");
        return changes;
    }
    private void changed(UUID id, boolean structural) {
        var book = ledger.market();
        if (structural && catalogRevision == book.catalogRevision() - 1) {
            if (book.listing(id) == null) ordered.remove(id);
            else { int at = Collections.binarySearch(ordered, id, order(book)); ordered.add(at < 0 ? -at - 1 : at, id); }
            catalogRevision = book.catalogRevision(); queries.clear();
        }
        de.kuro.kuronomics.menu.MarketMenu.refresh(server, id, structural);
    }
}
