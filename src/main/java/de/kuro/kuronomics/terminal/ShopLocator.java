package de.kuro.kuronomics.terminal;

import de.kuro.kuronomics.event.EconomyEvents;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;

public final class ShopLocator {
    // Use vanilla's container reach limit so the shop GUI doesn't open and immediately close again.
    private static final double MAX_OPEN_DISTANCE_SQUARED = 64.0;
    private ShopLocator() {}

    public static Optional<TerminalLocator.Located<ShopTerminal>> nearest(ServerPlayer player) {
        return nearest(player, EconomyEvents.config().shopRange());
    }

    static Optional<TerminalLocator.Located<ShopTerminal>> nearest(ServerPlayer player, int range) {
        var level = player.serverLevel();
        return TerminalLocator.nearest(level, player.position(), player.blockPosition(), range, blockEntity -> {
            if (blockEntity instanceof ShopTerminal terminal && terminal.isValidShopTerminal(level) && terminal.canOpenShop(player)
                    && player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(blockEntity.getBlockPos()))
                    <= MAX_OPEN_DISTANCE_SQUARED) return terminal;
            return null;
        });
    }
}
