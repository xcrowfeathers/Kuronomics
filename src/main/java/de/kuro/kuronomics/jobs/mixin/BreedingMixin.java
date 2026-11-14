package de.kuro.kuronomics.jobs.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import de.kuro.kuronomics.jobs.JobActions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerLevel.class)
public abstract class BreedingMixin {
    @WrapMethod(method = "addFreshEntity")
    private boolean work(Entity entity, Operation<Boolean> original) {
        boolean result = original.call(entity); if (result) JobActions.spawned(entity); return result;
    }
}
