package de.kuro.kuronomics.registry;

import com.google.common.collect.ImmutableSet;
import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.LegacyCompatibility;
import de.kuro.kuronomics.block.MachineKind;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.*;

public final class BankerContent {
    private static final DeferredRegister<PoiType> POIS = DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE, Kuronomics.MODID);
    private static final DeferredRegister<VillagerProfession> PROFESSIONS = DeferredRegister.create(Registries.VILLAGER_PROFESSION, Kuronomics.MODID);
    public static final DeferredHolder<PoiType, PoiType> POI = POIS.register("banker", () -> new PoiType(ImmutableSet.copyOf(ModContent.MACHINES.get(MachineKind.ATM).get().getStateDefinition().getPossibleStates()), 1, 1));
    public static final DeferredHolder<VillagerProfession, VillagerProfession> BANKER = PROFESSIONS.register("banker", () -> new VillagerProfession("kuronomics:banker", p -> p.is(POI.getKey()), p -> p.is(POI.getKey()), ImmutableSet.of(), ImmutableSet.of(), ModContent.sound("cashreg")));
    static {
        var current = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Kuronomics.MODID, "banker");
        POIS.addAlias(LegacyCompatibility.id("banker"), current);
        PROFESSIONS.addAlias(LegacyCompatibility.id("banker"), current);
    }
    public static final GameRules.Key<GameRules.BooleanValue> ENABLED = GameRules.register("bankervillager", GameRules.Category.MOBS, GameRules.BooleanValue.create(true));
    private BankerContent() {}
    public static void register(IEventBus bus) { POIS.register(bus); PROFESSIONS.register(bus); }
}
