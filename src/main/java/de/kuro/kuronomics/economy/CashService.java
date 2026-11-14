package de.kuro.kuronomics.economy;

import de.kuro.kuronomics.economy.data.EconomyLedger;
import de.kuro.kuronomics.economy.data.CashTransactionStore;
import de.kuro.kuronomics.registry.ModContent;
import de.kuro.kuronomics.mailbox.MailboxService;
import java.util.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class CashService {
    private static final String PLAYER_MARKERS = "kuronomicsCashTransactions";
    private CashService() {}

    private record Note(int slot, ItemStack stack) {}
    private record Removal(int slot, ItemStack stack, int count) {}

    public static long available(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        long value = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            value = Math.addExact(value, (long) ModContent.cashValue(stack) * 100 * stack.getCount());
        }
        return value;
    }

    public static void deposit(ServerPlayer player, int denomination) { deposit(player, denomination, UUID.randomUUID()); }
    public static void deposit(ServerPlayer player, int denomination, UUID reference) {
        if (denomination < 0) throw new IllegalArgumentException("Invalid cash denomination.");
        String fingerprint = fingerprint(player.getUUID(), CashTransactionStore.Type.DEPOSIT, denomination);
        CashTransactionStore journal = CashTransactionStore.get(player.server);
        if (completed(journal, reference, fingerprint)) { clearMarker(player, reference); return; }
        CashTransactionStore.Transaction pending = journal.pending(reference);
        if (pending != null) { requireFingerprint(pending.fingerprint(), fingerprint); executeDeposit(player, pending); return; }
        var ledger = EconomyLedger.get(player.server);
        var economy = new EconomyService(ledger);
        if (economy.isTransactionProcessed(reference)) throw new IllegalArgumentException("Transaction reference was already used by another operation.");
        economy.requireActive(player.getUUID());
        Selection selection = select(player.getInventory(), denomination);
        long amount = selection.amount();
        if (amount <= 0) throw new IllegalArgumentException("Choose some cash to deposit.");
        pending = new CashTransactionStore.Transaction(reference, fingerprint, player.getUUID(), CashTransactionStore.Type.DEPOSIT,
                denomination, amount, false, false);
        journal.prepare(pending); saveData(player); executeDeposit(player, pending);
    }

    public static void withdraw(ServerPlayer player, long dollars) { withdraw(player, dollars, UUID.randomUUID()); }
    public static void withdraw(ServerPlayer player, long dollars, UUID reference) {
        long cents = Math.multiplyExact(dollars, 100);
        if (cents <= 0) throw new IllegalArgumentException("Choose an amount to withdraw.");
        if (dollars > 100000) throw new IllegalArgumentException("Maximum withdrawal is $100,000");
        String fingerprint = fingerprint(player.getUUID(), CashTransactionStore.Type.WITHDRAWAL, cents);
        CashTransactionStore journal = CashTransactionStore.get(player.server);
        if (completed(journal, reference, fingerprint)) { clearMarker(player, reference); return; }
        CashTransactionStore.Transaction pending = journal.pending(reference);
        if (pending != null) { requireFingerprint(pending.fingerprint(), fingerprint); executeWithdrawal(player, pending); return; }
        var economy = new EconomyService(EconomyLedger.get(player.server));
        if (economy.isTransactionProcessed(reference)) throw new IllegalArgumentException("Transaction reference was already used by another operation.");
        economy.requireActive(player.getUUID());
        if (economy.getAvailableBalance(player.getUUID()) < cents)
            throw new IllegalArgumentException("Your current balance is insufficient.");
        List<ItemStack> cash = cashOutputs(cents);
        if (!canReceive(player.getInventory(), List.of(), cash, ItemStack.EMPTY))
            throw new IllegalArgumentException("Not enough inventory space!");
        pending = new CashTransactionStore.Transaction(reference, fingerprint, player.getUUID(), CashTransactionStore.Type.WITHDRAWAL,
                0, cents, false, false);
        journal.prepare(pending); saveData(player); executeWithdrawal(player, pending);
    }

    /** Resume this player's unfinished ATM transactions when they reconnect. */
    public static void recover(ServerPlayer player) {
        CashTransactionStore journal = CashTransactionStore.get(player.server);
        for (CashTransactionStore.Transaction pending : journal.pendingFor(player.getUUID())) {
            try {
                if (pending.type() == CashTransactionStore.Type.DEPOSIT) executeDeposit(player, pending);
                else executeWithdrawal(player, pending);
            } catch (RuntimeException error) {
                com.mojang.logging.LogUtils.getLogger().error("ATM transaction {} recovery is pending", pending.reference(), error);
            }
        }
    }

    private static void executeDeposit(ServerPlayer player, CashTransactionStore.Transaction original) {
        CashTransactionStore journal = CashTransactionStore.get(player.server);
        CashTransactionStore.Transaction pending = Objects.requireNonNull(journal.pending(original.reference()));
        if (!pending.player().equals(player.getUUID()) || pending.type() != CashTransactionStore.Type.DEPOSIT)
            throw new IllegalArgumentException("ATM transaction belongs to another player.");
        if (!pending.physicalApplied()) {
            if (!hasMarker(player, pending.reference())) {
                Selection selection = select(player.getInventory(), pending.denomination());
                if (selection.amount() != pending.amount()) throw new IllegalStateException("The cash selected for this pending deposit has changed.");
                for (Note note : selection.notes()) {
                    if (pending.denomination() == 0) note.stack().setCount(0); else note.stack().shrink(1);
                }
                player.getInventory().setChanged(); mark(player, pending.reference());
                player.inventoryMenu.sendAllDataToRemote();
            }
            savePlayer(player); // A failed disk write can leave the marker in memory, so the player save still needs a retry.
            pending = pending.progress(true, pending.moneyApplied()); journal.update(pending);
        }
        if (!pending.moneyApplied()) {
            EconomyService economy = new EconomyService(EconomyLedger.get(player.server));
            if (!economy.isTransactionProcessed(pending.reference()))
                economy.deposit(player.getUUID(), pending.amount(), pending.reference(), "cash deposit");
            pending = pending.progress(true, true); journal.update(pending);
        }
        EconomyService economy = new EconomyService(EconomyLedger.get(player.server));
        new MailboxService(player.server).deposit(player.getUUID(), pending.amount(), economy.getBalance(player.getUUID()),
                pending.reference(), "ATM cash deposit");
        journal.complete(pending.reference()); saveData(player); clearMarker(player, pending.reference());
        player.playNotifySound(ModContent.sound("atm_print"),net.minecraft.sounds.SoundSource.BLOCKS,.25f,1f);
    }

    private static void executeWithdrawal(ServerPlayer player, CashTransactionStore.Transaction original) {
        CashTransactionStore journal = CashTransactionStore.get(player.server);
        CashTransactionStore.Transaction pending = Objects.requireNonNull(journal.pending(original.reference()));
        if (!pending.player().equals(player.getUUID()) || pending.type() != CashTransactionStore.Type.WITHDRAWAL)
            throw new IllegalArgumentException("ATM transaction belongs to another player.");
        EconomyService economy = new EconomyService(EconomyLedger.get(player.server));
        if (!pending.moneyApplied()) {
            if (!economy.isTransactionProcessed(pending.reference()))
                economy.withdraw(player.getUUID(), pending.amount(), pending.reference(), "cash withdrawal");
            pending = pending.progress(pending.physicalApplied(), true); journal.update(pending); saveData(player);
        }
        if (!pending.physicalApplied()) {
            if (!hasMarker(player, pending.reference())) {
                List<ItemStack> cash = cashOutputs(pending.amount());
                if (!canReceive(player.getInventory(), List.of(), cash, ItemStack.EMPTY))
                    throw new IllegalStateException("Not enough inventory space to recover the pending withdrawal.");
                deliver(player, cash); mark(player, pending.reference());
                player.inventoryMenu.sendAllDataToRemote();
            }
            savePlayer(player);
            pending = pending.progress(true, true); journal.update(pending);
        }
        new MailboxService(player.server).withdrawal(player.getUUID(), pending.amount(), economy.getBalance(player.getUUID()),
                pending.reference(), "ATM cash withdrawal");
        journal.complete(pending.reference()); saveData(player); clearMarker(player, pending.reference());
        player.playNotifySound(ModContent.sound("atm_print"),net.minecraft.sounds.SoundSource.BLOCKS,.25f,1f);
    }

    private record Selection(List<Note> notes, long amount) {}
    private static Selection select(Inventory inventory, int denomination) {
        List<Note> selected = new ArrayList<>(); long amount = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot); int value = ModContent.cashValue(stack);
            if (value > 0 && (denomination == 0 || denomination == value)) {
                selected.add(new Note(slot, stack));
                amount = Math.addExact(amount, (long) value * 100 * (denomination == 0 ? stack.getCount() : 1));
                if (denomination != 0) break;
            }
        }
        return new Selection(List.copyOf(selected), amount);
    }

    private static String fingerprint(UUID player, CashTransactionStore.Type type, long value) { return type + "|" + player + "|" + value; }
    private static void requireFingerprint(String actual, String expected) {
        if (!actual.equals(expected)) throw new IllegalArgumentException("ATM transaction reference was already used for different values.");
    }
    private static boolean completed(CashTransactionStore journal, UUID reference, String fingerprint) {
        CashTransactionStore.Completed completed = journal.completed(reference);
        if (completed == null) return false; requireFingerprint(completed.fingerprint(), fingerprint); return true;
    }
    private static boolean hasMarker(ServerPlayer player, UUID reference) {
        return player.getPersistentData().getCompound(PLAYER_MARKERS).getBoolean(reference.toString());
    }
    private static void mark(ServerPlayer player, UUID reference) {
        var markers = player.getPersistentData().getCompound(PLAYER_MARKERS); markers.putBoolean(reference.toString(), true);
        player.getPersistentData().put(PLAYER_MARKERS, markers);
    }
    private static void clearMarker(ServerPlayer player, UUID reference) {
        de.kuro.kuronomics.economy.FinancialWriter.await(player.server); // Wait for the completed transaction to reach disk before clearing its recovery marker.

        var markers = player.getPersistentData().getCompound(PLAYER_MARKERS);
        if (!markers.contains(reference.toString())) return;
        markers.remove(reference.toString());
        if (markers.isEmpty()) player.getPersistentData().remove(PLAYER_MARKERS); else player.getPersistentData().put(PLAYER_MARKERS, markers);
        
    }
    private static void saveData(ServerPlayer player) { de.kuro.kuronomics.economy.FinancialPersistence.save(player.server); }
    private static void savePlayer(ServerPlayer player) { de.kuro.kuronomics.economy.PlayerPersistence.save(player); }

    /** Keep cash selection in the original order: lowest denomination first, then inventory slot. */
    public static void pay(ServerPlayer player, long cents, ItemStack purchased) {
        if (cents <= 0) throw new IllegalArgumentException("This item does not have a valid price.");
        if (cents % 100 != 0) throw new IllegalArgumentException("Cash purchases require a whole-dollar price");
        Inventory inventory = player.getInventory();
        Map<Integer, List<Note>> notes = new HashMap<>();
        long available = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            int value = ModContent.cashValue(stack);
            if (value == 0) continue;
            notes.computeIfAbsent(value, ignored -> new ArrayList<>()).add(new Note(slot, stack));
            available = Math.addExact(available, (long) value * 100 * stack.getCount());
        }
        if (available < cents) throw new IllegalArgumentException("Not enough cash");
        List<Removal> removed = new ArrayList<>();
        long remaining = cents;
        for (int value : ModContent.CASH.keySet()) {
            for (Note note : notes.getOrDefault(value, List.of())) {
                if (remaining <= 0) break;
                long each = value * 100L;
                int count = (int) Math.min(note.stack().getCount(), (remaining + each - 1) / each);
                removed.add(new Removal(note.slot(), note.stack(), count));
                remaining -= count * each;
            }
        }
        List<ItemStack> change = remaining < 0 ? cashOutputs(-remaining) : List.of();
        if (!canReceive(inventory, removed, change, purchased))
            throw new IllegalArgumentException("Not enough inventory space!");
        for (Removal removal : removed) removal.stack().shrink(removal.count());
        deliver(player, change);
        inventory.setChanged();
    }

    public static void give(ServerPlayer player, long cents) { deliver(player, cashOutputs(cents)); }

    private static List<ItemStack> cashOutputs(long cents) {
        if (cents < 0 || cents % 100 != 0) throw new IllegalArgumentException("Invalid cash amount");
        long dollars = cents / 100;
        List<ItemStack> result = new ArrayList<>();
        for (int value : new int[]{500, 100, 50, 10, 5, 1}) {
            while (dollars >= value) {
                int count = (int) Math.min(64, dollars / value);
                dollars -= (long) count * value;
                result.add(new ItemStack(ModContent.CASH.get(value).get(), count));
            }
        }
        return result;
    }

    private static void deliver(ServerPlayer player, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) player.getInventory().placeItemBackInInventory(stack);
    }

    /** Match vanilla inventory placement: merge stacks first, then use the first free main-inventory slot. */
    private static boolean canReceive(Inventory inventory, List<Removal> removed, List<ItemStack> cash, ItemStack purchased) {
        ItemStack[] slots = new ItemStack[inventory.getContainerSize()];
        for (int i = 0; i < slots.length; i++) slots[i] = inventory.getItem(i).copy();
        for (Removal removal : removed) slots[removal.slot()].shrink(removal.count());
        for (ItemStack stack : cash) if (!simulateInsert(inventory, slots, stack)) return false;
        return simulateInsert(inventory, slots, purchased);
    }

    private static boolean simulateInsert(Inventory inventory, ItemStack[] slots, ItemStack incoming) {
        int remaining = incoming.getCount();
        while (remaining > 0) {
            int slot = mergeSlot(inventory, slots, inventory.selected, incoming);
            if (slot < 0) slot = mergeSlot(inventory, slots, 40, incoming);
            if (slot < 0) for (int i = 0; i < inventory.items.size(); i++) {
                slot = mergeSlot(inventory, slots, i, incoming);
                if (slot >= 0) break;
            }
            if (slot < 0) for (int i = 0; i < inventory.items.size(); i++)
                if (slots[i].isEmpty()) { slot = i; break; }
            if (slot < 0) return false;
            int room = incoming.getMaxStackSize() - slots[slot].getCount();
            int placed = Math.min(remaining, room);
            if (placed <= 0) return false;
            if (slots[slot].isEmpty()) slots[slot] = incoming.copyWithCount(placed);
            else slots[slot].grow(placed);
            remaining -= placed;
        }
        return true;
    }

    private static int mergeSlot(Inventory inventory, ItemStack[] slots, int slot, ItemStack incoming) {
        ItemStack existing = slots[slot];
        return !existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, incoming)
                && existing.isStackable() && existing.getCount() < inventory.getMaxStackSize(existing) ? slot : -1;
    }
}
