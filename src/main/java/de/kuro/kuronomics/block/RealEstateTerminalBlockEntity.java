package de.kuro.kuronomics.block;

import de.kuro.kuronomics.menu.RealEstateMenu;
import de.kuro.kuronomics.registry.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class RealEstateTerminalBlockEntity extends BlockEntity {
    public RealEstateTerminalBlockEntity(BlockPos pos, BlockState state) { super(ModContent.REAL_ESTATE_TERMINAL_ENTITY.get(), pos, state); }
    public boolean isValidTerminal(ServerLevel level) {
        return this.level == level && !isRemoved() && level.isLoaded(worldPosition) && level.getBlockEntity(worldPosition) == this
                && level.getBlockState(worldPosition).is(ModContent.REAL_ESTATE_TERMINAL_BLOCK.get());
    }
    public void open(ServerPlayer player) { if (isValidTerminal(player.serverLevel())) RealEstateMenu.open(player, this); }
}
