package de.kuro.kuronomics.jobs;

import java.util.*;
import de.kuro.kuronomics.integration.RegionProtectionService.Capability;

/** Register types before compiling the config, then report actions only after they succeed on the server. */
public final class TaskTypes {
    public enum Target { BLOCK, ITEM, ENTITY }
    public record Type(String id, Target target, Set<Capability> capabilities, boolean natural) {
        public Type { capabilities = Set.copyOf(capabilities); }
    }
    private static final Map<String, Type> TYPES = new LinkedHashMap<>();
    static {
        register("break_block", Target.BLOCK, true, Capability.BREAK);
        register("place_block", Target.BLOCK, false, Capability.PLACE);
        register("plant_crop", Target.BLOCK, false, Capability.PLACE, Capability.INTERACT);
        register("harvest_crop", Target.BLOCK, false, Capability.BREAK, Capability.INTERACT);
        register("craft_item", Target.ITEM, false, Capability.INTERACT);
        register("smelt_item", Target.ITEM, false, Capability.CONTAINER);
        register("kill_entity", Target.ENTITY, false, Capability.ENTITY_DAMAGE);
        register("feed_animal", Target.ENTITY, false, Capability.ENTITY_INTERACT);
        register("breed_animal", Target.ENTITY, false, Capability.ENTITY_INTERACT);
        register("fish_item", Target.ITEM, false, Capability.INTERACT);
        register("shear_entity", Target.ENTITY, false, Capability.ENTITY_INTERACT);
        register("tame_entity", Target.ENTITY, false, Capability.ENTITY_INTERACT);
        register("deliver_item", Target.ITEM, false, Capability.CONTAINER);
    }
    public static synchronized void register(String id, Target target, boolean natural, Capability... capabilities) {
        if (TYPES.putIfAbsent(id, new Type(id, target, Set.of(capabilities), natural)) != null)
            throw new IllegalArgumentException("Duplicate task type: " + id);
    }
    public static Type require(String id) {
        Type type = TYPES.get(id);
        if (type == null) throw new IllegalArgumentException("unknown task type " + id);
        return type;
    }
    static Set<String> ids() { return Set.copyOf(TYPES.keySet()); }
    private TaskTypes() {}
}
