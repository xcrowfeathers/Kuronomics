package de.kuro.kuronomics.jobs.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import de.kuro.kuronomics.jobs.JobActions;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(FishingHook.class)
public abstract class FishingMixin {
    @WrapMethod(method = "retrieve")
    private int work(ItemStack rod, Operation<Integer> original) {
        if (!(((FishingHook)(Object)this).getPlayerOwner() instanceof net.minecraft.server.level.ServerPlayer player)) return original.call(rod);
        var job = de.kuro.kuronomics.jobs.JobService.get(player.server).employment(player);
        if (job == null || !job.index().containsKey("fish_item")) return original.call(rod);
        var previous = JobActions.FISHING.get(); var events = new java.util.ArrayList<net.neoforged.neoforge.event.entity.player.ItemFishedEvent>();
        JobActions.FISHING.set(events); var tool = rod.copy();
        try { int result = original.call(rod); JobActions.fished(events, tool); return result; }
        finally { if (previous == null) JobActions.FISHING.remove(); else JobActions.FISHING.set(previous); }
    }
}
