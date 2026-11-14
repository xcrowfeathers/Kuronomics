package de.kuro.kuronomics.jobs.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import de.kuro.kuronomics.jobs.JobService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ShearsItem.class)
public abstract class ShearingMixin {
    @WrapMethod(method = "interactLivingEntity")
    private InteractionResult work(ItemStack stack, Player player, LivingEntity entity, InteractionHand hand, Operation<InteractionResult> original) {
        if (!(player instanceof ServerPlayer employed) || !JobService.get(employed.server).relevant(employed, "shear_entity", entity.getType())) return original.call(stack, player, entity, hand);
        var tool = stack.copy(); var result = original.call(stack, player, entity, hand);
        if (player instanceof ServerPlayer p && result.consumesAction())
            JobService.get(p.server).completed(p, "shear_entity", entity.getType(), entity.blockPosition(), tool, 1, true);
        return result;
    }
}
