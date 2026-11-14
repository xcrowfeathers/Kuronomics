package de.kuro.kuronomics.block;

import de.kuro.kuronomics.menu.ServerShopTerminalMenu;
import de.kuro.kuronomics.registry.ModContent;
import de.kuro.kuronomics.servershop.ServerShop;
import de.kuro.kuronomics.servershop.ServerShopService;
import de.kuro.kuronomics.terminal.ShopTerminal;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class ServerShopBlockEntity extends BlockEntity implements ShopTerminal {
    private UUID boundShopId;

    public ServerShopBlockEntity(BlockPos pos, BlockState state) { super(ModContent.SERVER_SHOP_TERMINAL_ENTITY.get(), pos, state); }

    public Optional<UUID> boundShopId() { return Optional.ofNullable(boundShopId); }

    public Optional<ServerShop> boundShop(ServerLevel level) {
        if (boundShopId == null) return Optional.empty();
        Optional<ServerShop> shop = new ServerShopService(level.getServer()).find(boundShopId);
        if (shop.isEmpty()) { boundShopId = null; setChanged(); }
        return shop;
    }

    public boolean bind(ServerPlayer administrator, UUID shopId) {
        if (!administrator.hasPermissions(2) || administrator.serverLevel() != level) return false;
        if (shopId != null && new ServerShopService(administrator.server).find(shopId).isEmpty()) return false;
        boundShopId = shopId;
        setChanged();
        return true;
    }

    @Override public boolean isValidShopTerminal(ServerLevel level) {
        return this.level == level && !isRemoved() && level.isLoaded(worldPosition)
                && level.getBlockEntity(worldPosition) == this && level.getBlockState(worldPosition).is(ModContent.SERVER_SHOP_TERMINAL_BLOCK.get());
    }

    @Override public boolean canOpenShop(ServerPlayer player) {
        return player.hasPermissions(2) || boundShop(player.serverLevel()).isPresent();
    }

    @Override public void openShop(ServerPlayer player) {
        if (!isValidShopTerminal(player.serverLevel())) return;
        Optional<ServerShop> shop = boundShop(player.serverLevel());
        if (shop.isEmpty() && !player.hasPermissions(2)) {
            player.sendSystemMessage(Component.literal("No server shop setup").withStyle(ChatFormatting.RED));
            return;
        }
        ServerShopTerminalMenu.open(player, this);
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (boundShopId != null) tag.putUUID("BoundShop", boundShopId);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        boundShopId = tag.hasUUID("BoundShop") ? tag.getUUID("BoundShop") : null;
    }
}
