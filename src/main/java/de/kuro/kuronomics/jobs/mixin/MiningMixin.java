package de.kuro.kuronomics.jobs.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import de.kuro.kuronomics.jobs.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.*;

@Mixin(ServerPlayerGameMode.class)
public abstract class MiningMixin {
    @Shadow @Final protected ServerPlayer player;
    @WrapMethod(method = "destroyBlock")
    private boolean work(BlockPos pos, Operation<Boolean> original) {
        BlockState before = player.serverLevel().getBlockState(pos);
        var service = JobService.get(player.server);
        boolean relevant = service.relevant(player, "break_block", before.getBlock()) || service.relevant(player, "harvest_crop", before.getBlock());
        var tool = relevant ? player.getMainHandItem().copy() : net.minecraft.world.item.ItemStack.EMPTY;
        boolean result = original.call(pos);
        if (result && !player.serverLevel().getBlockState(pos).equals(before)) {
            if (relevant) JobActions.broken(player, pos, before, tool);
            else if (service.registry().naturalBlocks().contains(before.getBlock())) PlacedWorkBlocks.get(player.serverLevel()).removed(pos);
        }
        return result;
    }
}
