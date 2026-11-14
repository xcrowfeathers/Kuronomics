package de.kuro.kuronomics.api;

import com.mojang.serialization.Codec;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * One type of server action. Keep configs and returned target sets unchanged once they're handed over.
 * Configs are decoded and indexed on reload, not during gameplay. The addon must check that the
 * server action really succeeded before submitting it; a client's claim isn't enough.
 */
public interface JobTaskType<C, A> {
    Codec<C> codec();
    Class<A> actionClass();
    /** Resolve IDs and tags to matching keys once per reload. The result can't be empty. */
    Set<ResourceLocation> targets(C config, RegistryAccess registries);
    /** Return the lookup key for an action the server has already checked. */
    ResourceLocation target(A action);
    /** Only check the matching candidates here, on the server thread. Don't change game state. */
    boolean matches(C config, Trigger<A> trigger);

    /** Count means completed units, not money. Position is used for the job's location checks. */
    record Trigger<A>(ServerPlayer player, BlockPos position, A action, int count) {
        public Trigger {
            java.util.Objects.requireNonNull(player); java.util.Objects.requireNonNull(action);
            position = java.util.Objects.requireNonNull(position).immutable();
            if (count < 1) throw new IllegalArgumentException("Task count must be positive");
        }
    }
}
