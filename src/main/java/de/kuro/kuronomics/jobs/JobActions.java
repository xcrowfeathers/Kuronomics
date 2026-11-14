package de.kuro.kuronomics.jobs;

import java.util.*;
import de.kuro.kuronomics.Kuronomics;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.*;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;

@EventBusSubscriber(modid = Kuronomics.MODID)
public final class JobActions {
    public static final ThreadLocal<List<BlockEvent.EntityPlaceEvent>> PLACEMENTS = new ThreadLocal<>();
    public static final ThreadLocal<List<ItemFishedEvent>> FISHING = new ThreadLocal<>();
    private record Breeding(ServerPlayer player, EntityType<?> type) {}
    private static final Map<Entity, Breeding> BABIES = new WeakHashMap<>();
    public static void clear() { BABIES.clear(); }
    public static boolean mature(BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) return crop.isMaxAge(state);
        for (var property : state.getProperties()) if (property instanceof net.minecraft.world.level.block.state.properties.IntegerProperty age && age.getName().equals("age"))
            return state.getValue(age).equals(java.util.Collections.max(age.getPossibleValues()));
        return state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.BERRIES)
                && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.BERRIES);
    }
    public static void broken(ServerPlayer p, BlockPos pos, BlockState before, ItemStack tool) {
        var placed = PlacedWorkBlocks.get(p.serverLevel()); boolean natural = !placed.contains(pos);
        var jobs = JobService.get(p.server);
        jobs.completed(p, "break_block", before.getBlock(), pos, tool, 1, natural);
        if (mature(before)) jobs.completed(p, "harvest_crop", before.getBlock(), pos, tool, 1, true);
        placed.removed(pos);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void placement(BlockEvent.EntityPlaceEvent e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        // Track non-player placements too. A cancelled event can only block a reward, never create one.
        var natural = JobService.get(level.getServer()).registry().naturalBlocks();
        if (natural.contains(e.getPlacedBlock().getBlock())) PlacedWorkBlocks.get(level).placed(e.getPos());
        var pending = PLACEMENTS.get(); if (pending != null && !pending.contains(e)) pending.add(e);
    }
    public static void placed(ServerPlayer p, List<BlockEvent.EntityPlaceEvent> events, ItemStack tool) {
        Set<BlockPos> handled = new HashSet<>();
        for (var event : events) {
            if (event.isCanceled()) continue;
            var snapshots = event instanceof BlockEvent.EntityMultiPlaceEvent multi ? multi.getReplacedBlockSnapshots() : List.of(event.getBlockSnapshot());
            for (var snapshot : snapshots) {
                BlockPos pos = snapshot.getPos(); if (!handled.add(pos)) continue;
                BlockState state = p.serverLevel().getBlockState(pos);
                if (state.equals(snapshot.getState()) || state.isAir()) continue;
                var jobs = JobService.get(p.server);
                if (jobs.registry().naturalBlocks().contains(state.getBlock())) PlacedWorkBlocks.get(p.serverLevel()).placed(pos);
                jobs.completed(p, "place_block", state.getBlock(), pos, tool, 1, true);
                if (state.getBlock() instanceof CropBlock) jobs.completed(p, "plant_crop", state.getBlock(), pos, tool, 1, true);
            }
        }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void piston(PistonEvent.Pre e) {
        if (!(e.getLevel() instanceof ServerLevel level)) return;
        var targets = JobService.get(level.getServer()).registry().naturalBlocks(); if (targets.isEmpty()) return;
        var resolver = e.getStructureHelper(); if (resolver == null || !resolver.resolve()) return;
        var placed = PlacedWorkBlocks.get(level);
        for (BlockPos pos : resolver.getToPush()) if (targets.contains(level.getBlockState(pos).getBlock()) || placed.contains(pos)) {
            // Don't reward piston-moved task ore, even if the move was cancelled. Better to miss a reward than
            // allow farming.
            placed.placed(pos); placed.placed(pos.relative(e.getDirection())); placed.placed(pos.relative(e.getDirection().getOpposite()));
        }
    }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void baby(BabyEntitySpawnEvent e) { if (!e.isCanceled() && e.getChild() != null && e.getCausedByPlayer() instanceof ServerPlayer p) BABIES.put(e.getChild(), new Breeding(p, e.getParentA().getType())); }
    public static void spawned(Entity child) {
        var event = BABIES.remove(child);
        if (event == null) return;
        var p = event.player();
        JobService.get(p.server).completed(p, "breed_animal", event.type(), child.blockPosition(), p.getMainHandItem(), 1, true);
    }
    @SubscribeEvent(priority = EventPriority.LOWEST) public static void fish(ItemFishedEvent e) { var pending = FISHING.get(); if (pending != null && !pending.contains(e)) pending.add(e); }
    public static void fished(List<ItemFishedEvent> events, ItemStack tool) {
        for (var e : events) if (!e.isCanceled() && e.getEntity() instanceof ServerPlayer p)
            for (ItemStack stack : e.getDrops()) JobService.get(p.server).completed(p, "fish_item", stack.getItem(), e.getHookEntity().blockPosition(), tool, stack.getCount(), true);
    }
}
