package de.kuro.kuronomics.block;

import de.kuro.kuronomics.item.Prices;
import de.kuro.kuronomics.menu.MachineMenu;
import de.kuro.kuronomics.registry.ModContent;
import de.kuro.kuronomics.economy.Money;
import de.kuro.kuronomics.terminal.ShopTerminal;
import java.util.UUID;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class MachineBlockEntity extends BaseContainerBlockEntity implements WorldlyContainer, ShopTerminal {
    public final MachineKind kind;
    private NonNullList<ItemStack> items;
    private UUID owner;
    private UUID company;
    private long profit;
    private long price = -1;
    private int cardColor;
    private final java.util.Set<UUID> shopTransactions = new java.util.LinkedHashSet<>();
    public MachineBlockEntity(MachineKind kind, BlockPos pos, BlockState state) {
        super(ModContent.MACHINE_ENTITIES.get(kind).get(), pos, state); this.kind = kind;
        items = NonNullList.withSize(kind.slots, ItemStack.EMPTY);
    }
    public UUID owner() { return owner; }
    public void owner(UUID id) { owner = id; setChanged(); }
    public UUID companyId() { return company; }
    public boolean assigned() { return kind != MachineKind.COMPANY_SHOP || company != null; }
    public de.kuro.kuronomics.company.Company company() {
        return company == null || !(level instanceof ServerLevel server) ? null
                : de.kuro.kuronomics.economy.data.EconomyLedger.get(server.getServer()).companies().company(company);
    }
    public void assignCompany(ServerPlayer player, UUID id) {
        if (kind != MachineKind.COMPANY_SHOP || company != null || !player.getUUID().equals(owner))
            throw new IllegalArgumentException("Only the placer can assign this shop.");
        var c = de.kuro.kuronomics.economy.data.EconomyLedger.get(player.server).companies().company(id);
        if (!de.kuro.kuronomics.market.MarketPermissions.canCreate(c, player.getUUID()))
            throw new IllegalArgumentException("You need to be a Manager or higher in that company.");
        company = id; owner = null; setChanged();
    }
    public boolean canRemove(Player player) {
        if (kind != MachineKind.COMPANY_SHOP) return canManage(player);
        if (pendingSale()) return false;
        if (company == null) return player.getUUID().equals(owner) && isEmpty();
        return isEmpty() && removalRole(player);
    }
    public boolean removalRole(Player player) {
        if (kind != MachineKind.COMPANY_SHOP) return canManage(player);
        var c = company();
        return de.kuro.kuronomics.market.MarketPermissions.canCreate(c, player.getUUID())
                || (c == null || c.status() != de.kuro.kuronomics.company.Company.Status.ACTIVE) && player.hasPermissions(2);
    }
    public boolean canManage(Player player) {
        if (kind == MachineKind.COMPANY_SHOP) {
            if (company == null || pendingSale()) return false;
            var c = company();
            return c != null && c.status() == de.kuro.kuronomics.company.Company.Status.ACTIVE
                    ? de.kuro.kuronomics.company.CompanyPermissionService.allowed(c, player.getUUID(), de.kuro.kuronomics.company.CompanyPermissionService.Capability.MEMBER) : player.hasPermissions(2);
        }
        if (kind.shop() || kind.vending()) return owner != null && player.getUUID().equals(owner);
        return player.getUUID().equals(owner) || player.hasPermissions(2);
    }
    private boolean pendingSale() {
        return level instanceof ServerLevel server && de.kuro.kuronomics.economy.data.PlayerShopTransactionStore.get(server.getServer())
                .pendingAt(server.dimension().location().toString(), worldPosition.asLong());
    }
    @Override public boolean isValidShopTerminal(ServerLevel level) {
        return kind.shop() && !isRemoved() && getLevel() == level
                && level.getBlockState(worldPosition).is(ModContent.MACHINES.get(kind).get())
                && level.getBlockEntity(worldPosition) == this;
    }
    @Override public void openShop(ServerPlayer player) {
        if (player.serverLevel() != getLevel() || !isValidShopTerminal(player.serverLevel()))
            throw new IllegalStateException("This shop is no longer available.");
        MachineMenu.open(player, this, 0);
    }
    public long profit() { return profit; }
    public void profit(long value) { if (value < 0) throw new IllegalArgumentException("Negative profit"); profit = value; setChanged(); }
    public int cardColor() { return cardColor; }
    public void cycleCard() { cardColor = (cardColor + 1) % 4; setChanged(); }
    public long price() { return price; }
    public void price(long value) { if (value < 0) throw new IllegalArgumentException("Negative price"); price = value == 0 ? -1 : value; priceSlot(); setChanged(); }
    private void priceSlot() { if (kind == MachineKind.PRINTER && price >= 0 && !items.getFirst().isEmpty()) Prices.set(items.getFirst(), price); }
    public boolean hasShopTransaction(UUID reference) { return shopTransactions.contains(reference); }
    public void markShopTransaction(UUID reference) { if (shopTransactions.add(reference)) setChanged(); }
    public void clearShopTransaction(UUID reference) { if (shopTransactions.remove(reference)) setChanged(); }
    @Override protected Component getDefaultName() { return Component.translatable("block.kuronomics." + kind.id); }
    @Override protected AbstractContainerMenu createMenu(int id, Inventory inventory) { return new MachineMenu(id, inventory, this); }
    @Override public int getContainerSize() { return items.size(); }
    @Override protected NonNullList<ItemStack> getItems() { return items; }
    @Override protected void setItems(NonNullList<ItemStack> value) { items = value; }
    @Override public void setItem(int index, ItemStack stack) { super.setItem(index, stack); if (level != null && !level.isClientSide) priceSlot(); }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries); ContainerHelper.saveAllItems(tag, items, registries);
        if (owner != null) tag.putUUID("Owner", owner);
        if (company != null) tag.putUUID("Company", company);
        tag.putLong("ProfitCents", profit); tag.putLong("PriceCents", price); tag.putInt("CardColor", cardColor);
        ListTag trades = new ListTag(); shopTransactions.forEach(id -> trades.add(NbtUtils.createUUID(id))); tag.put("ShopTransactions", trades);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries); items = NonNullList.withSize(kind.slots, ItemStack.EMPTY); ContainerHelper.loadAllItems(tag, items, registries);
        CompoundTag old = getPersistentData();
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : old.getString("owner").isBlank() ? null : UUID.fromString(old.getString("owner"));
        company = tag.hasUUID("Company") ? tag.getUUID("Company") : null;
        profit = tag.contains("ProfitCents") ? tag.getLong("ProfitCents") : Money.legacy(old.getDouble("profit"));
        price = tag.contains("PriceCents") ? tag.getLong("PriceCents") : tag.contains("PrinterPrice") ? tag.getInt("PrinterPrice") : -1;
        cardColor = tag.contains("CardColor") ? tag.getInt("CardColor") : (int) old.getDouble("id");
        shopTransactions.clear();
        for (Tag raw : tag.getList("ShopTransactions", Tag.TAG_INT_ARRAY)) shopTransactions.add(NbtUtils.loadUUID(raw));
        if (profit < 0 || price < -1 || cardColor < 0 || cardColor > 3) throw new IllegalArgumentException("Invalid machine data");
    }
    @Override public int[] getSlotsForFace(Direction direction) { return kind == MachineKind.PRINTER ? new int[]{0} : new int[0]; }
    @Override public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return kind == MachineKind.PRINTER && direction == getBlockState().getValue(MachineBlock.FACING).getOpposite();
    }
    @Override public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return kind == MachineKind.PRINTER && direction != getBlockState().getValue(MachineBlock.FACING).getOpposite();
    }
}
