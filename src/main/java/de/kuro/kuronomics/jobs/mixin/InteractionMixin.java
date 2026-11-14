package de.kuro.kuronomics.jobs.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import de.kuro.kuronomics.jobs.JobService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.*;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Player.class)
public abstract class InteractionMixin {
    @WrapMethod(method = "interactOn")
    private InteractionResult work(Entity target, InteractionHand hand, Operation<InteractionResult> original) {
        if (!((Object)this instanceof ServerPlayer p)) return original.call(target, hand);
        var jobs = JobService.get(p.server);
        boolean feed = jobs.relevant(p, "feed_animal", target.getType());
        if (!feed) return original.call(target, hand);
        var tool = p.getItemInHand(hand).copy();
        boolean love = target instanceof Animal a && a.isInLove(); int age = target instanceof Animal a ? a.getAge() : 0;
        float health = target instanceof LivingEntity living ? living.getHealth() : 0;
        var result = original.call(target, hand);
        if (result.consumesAction()) {
            if (feed && target instanceof Animal a && ((!love && a.isInLove()) || age < 0 && a.getAge() > age || a.getHealth() > health) && p.getItemInHand(hand).getCount() < tool.getCount())
                jobs.completed(p, "feed_animal", target.getType(), target.blockPosition(), tool, 1, true);
        }
        return result;
    }
}
