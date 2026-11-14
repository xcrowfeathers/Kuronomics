package de.kuro.kuronomics.jobs.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import de.kuro.kuronomics.jobs.JobService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(AbstractHorse.class)
public abstract class HorseTamingMixin {
    @WrapMethod(method = "tameWithName")
    private boolean work(Player player, Operation<Boolean> original) {
        var horse = (AbstractHorse)(Object)this; boolean tamed = horse.isTamed(); boolean result = original.call(player);
        if (!tamed && result && horse.isTamed() && player instanceof ServerPlayer p)
            JobService.get(p.server).completed(p, "tame_entity", horse.getType(), horse.blockPosition(), p.getMainHandItem(), 1, true);
        return result;
    }
}
