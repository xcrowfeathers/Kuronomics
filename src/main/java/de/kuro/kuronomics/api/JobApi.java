package de.kuro.kuronomics.api;

import java.util.*;

/** Read job info here. Task completion still has to pass the normal server checks. */
public interface JobApi {
    enum WorkCapability { BREAK, PLACE, INTERACT, CONTAINER, ENTITY_DAMAGE, ENTITY_INTERACT }
    record Job(String id, String name, String mode, long baseSalary, long maximumSalary, long gainCap, int slots, boolean appointed) {}
    record Employment(Job job, long accumulatedEarnings, long salary, Map<String, Long> taskEarnings) {
        public Employment { taskEarnings = Map.copyOf(taskEarnings); }
    }
    Collection<Job> registered();
    Optional<Job> find(String id);
    Optional<Employment> employment(UUID player);
    /** Checks unemployment, suspension, appointments and open slots. It doesn't reserve a slot. */
    boolean canStart(UUID player, String id);
    /**
     * Checks the job/WorkZone rules at this position. Entity checks also need the target type.
     * This doesn't override protection or give the caller permission to act.
     */
    default boolean canWork(net.minecraft.server.level.ServerPlayer player, net.minecraft.core.BlockPos position,
                            WorkCapability capability, net.minecraft.world.entity.EntityType<?> target) {
        throw new UnsupportedOperationException("This service provider does not support work capability queries");
    }
    default boolean canWork(net.minecraft.server.level.ServerPlayer player, net.minecraft.core.BlockPos position, WorkCapability capability) {
        return canWork(player, position, capability, null);
    }
}
