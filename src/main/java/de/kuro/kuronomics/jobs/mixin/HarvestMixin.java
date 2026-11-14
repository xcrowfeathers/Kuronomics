package de.kuro.kuronomics.jobs.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import de.kuro.kuronomics.jobs.*;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerPlayerGameMode.class)
public abstract class HarvestMixin {
    @WrapMethod(method = "useItemOn")
    private InteractionResult work(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand, BlockHitResult hit, Operation<InteractionResult> original) {
        var state = level.getBlockState(hit.getBlockPos()); var jobs = JobService.get(player.server);
        boolean relevant = jobs.relevant(player, "harvest_crop", state.getBlock()) && JobActions.mature(state);
        var tool = relevant ? stack.copy() : ItemStack.EMPTY;
        var result = original.call(player, level, stack, hand, hit);
        if (relevant && result.consumesAction() && !state.equals(level.getBlockState(hit.getBlockPos())))
            jobs.completed(player, "harvest_crop", state.getBlock(), hit.getBlockPos(), tool, 1, true);
        return result;
    }
}
