package de.kuro.kuronomics.terminal;

import de.kuro.kuronomics.block.JobTerminalBlockEntity;
import de.kuro.kuronomics.event.EconomyEvents;
import java.util.Optional;
import net.minecraft.server.level.ServerPlayer;

public final class JobLocator {
    private JobLocator() {}
    public static Optional<TerminalLocator.Located<JobTerminalBlockEntity>> nearest(ServerPlayer player) {
        int range = EconomyEvents.config().jobTerminalRange();
        return TerminalLocator.nearest(player.serverLevel(), player.position(), player.blockPosition(),
                range, entity -> entity instanceof JobTerminalBlockEntity terminal && terminal.isValidTerminal(player.serverLevel()) ? terminal : null)
                .filter(found -> found.distanceSquared() <= (double) range * range);
    }
}
