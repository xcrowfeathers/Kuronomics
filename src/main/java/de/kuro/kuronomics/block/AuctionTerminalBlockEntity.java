package de.kuro.kuronomics.block;

import de.kuro.kuronomics.menu.AuctionMenu;
import de.kuro.kuronomics.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** The block only stores its location. Auctions and escrow live in AuctionHouseStore. */
public final class AuctionTerminalBlockEntity extends BlockEntity {
    public AuctionTerminalBlockEntity(BlockPos pos, BlockState state) { super(ModContent.AUCTION_TERMINAL_ENTITY.get(), pos, state); }
    public boolean isValidTerminal(ServerLevel level) {
        return this.level == level && !isRemoved() && level.isLoaded(worldPosition) && level.getBlockEntity(worldPosition) == this
                && level.getBlockState(worldPosition).is(ModContent.AUCTION_TERMINAL_BLOCK.get());
    }
    public void open(ServerPlayer player) { if (isValidTerminal(player.serverLevel())) AuctionMenu.open(player, this); }
}
