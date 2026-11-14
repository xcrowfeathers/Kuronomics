package de.kuro.kuronomics.jobs.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import de.kuro.kuronomics.jobs.JobActions;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.context.UseOnContext;
import net.neoforged.neoforge.common.CommonHooks;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(CommonHooks.class)
public abstract class PlacementMixin {
    @WrapMethod(method = "onPlaceItemIntoWorld")
    private static InteractionResult work(UseOnContext context, Operation<InteractionResult> original) {
        if (!(context.getPlayer() instanceof ServerPlayer player)) return original.call(context);
        var job = de.kuro.kuronomics.jobs.JobService.get(player.server).employment(player);
        if (job == null || !job.index().containsKey("place_block") && !job.index().containsKey("plant_crop")) return original.call(context);
        var previous = JobActions.PLACEMENTS.get(); var events = new java.util.ArrayList<net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent>();
        JobActions.PLACEMENTS.set(events); var tool = context.getItemInHand().copy();
        try { var result = original.call(context); if (result.consumesAction()) JobActions.placed(player, events, tool); return result; }
        finally { if (previous == null) JobActions.PLACEMENTS.remove(); else JobActions.PLACEMENTS.set(previous); }
    }
}
