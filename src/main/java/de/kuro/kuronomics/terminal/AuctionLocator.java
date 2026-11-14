package de.kuro.kuronomics.terminal;

import de.kuro.kuronomics.block.AuctionTerminalBlockEntity;
import de.kuro.kuronomics.event.EconomyEvents;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;

public final class AuctionLocator {
    private AuctionLocator() {}
    public static Optional<TerminalLocator.Located<AuctionTerminalBlockEntity>> nearest(ServerPlayer player) {
        int range = EconomyEvents.config().auctionTerminalRange();
        return TerminalLocator.nearest(player.serverLevel(), player.position(), player.blockPosition(), range, blockEntity ->
                blockEntity instanceof AuctionTerminalBlockEntity terminal && terminal.isValidTerminal(player.serverLevel()) ? terminal : null);
    }
}
