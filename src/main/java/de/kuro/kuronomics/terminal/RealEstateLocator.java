package de.kuro.kuronomics.terminal;

import de.kuro.kuronomics.block.RealEstateTerminalBlockEntity;
import de.kuro.kuronomics.event.EconomyEvents;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;

public final class RealEstateLocator {
    private RealEstateLocator() {}
    public static Optional<TerminalLocator.Located<RealEstateTerminalBlockEntity>> nearest(ServerPlayer player) {
        int range = EconomyEvents.config().realEstateTerminalRange();
        return TerminalLocator.nearest(player.serverLevel(), player.position(), player.blockPosition(), range, blockEntity ->
                blockEntity instanceof RealEstateTerminalBlockEntity terminal && terminal.isValidTerminal(player.serverLevel()) ? terminal : null)
                .filter(located -> located.distanceSquared() <= (double) range * range);
    }
}
