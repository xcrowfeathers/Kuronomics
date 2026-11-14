package de.kuro.kuronomics.jobs.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import de.kuro.kuronomics.jobs.JobService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(TamableAnimal.class)
public abstract class TamingMixin {
    @WrapMethod(method = "tame")
    private void work(Player player, Operation<Void> original) {
        var animal = (TamableAnimal)(Object)this; boolean tamed = animal.isTame(); original.call(player);
        if (!tamed && animal.isTame() && player instanceof ServerPlayer p && p.getUUID().equals(animal.getOwnerUUID()))
            JobService.get(p.server).completed(p, "tame_entity", animal.getType(), animal.blockPosition(), p.getMainHandItem(), 1, true);
    }
}
