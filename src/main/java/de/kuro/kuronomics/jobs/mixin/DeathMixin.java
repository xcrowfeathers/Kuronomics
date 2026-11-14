package de.kuro.kuronomics.jobs.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import de.kuro.kuronomics.jobs.JobService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.*;

@Mixin(LivingEntity.class)
public abstract class DeathMixin {
    @Shadow protected boolean dead;
    @WrapMethod(method = "die")
    private void work(DamageSource damage, Operation<Void> original) {
        boolean wasDead = dead; original.call(damage); var entity = (LivingEntity)(Object)this;
        if (!wasDead && dead && damage.getEntity() instanceof ServerPlayer p)
            JobService.get(p.server).completed(p, "kill_entity", entity.getType(), entity.blockPosition(), p.getMainHandItem(), 1, true);
    }
}
