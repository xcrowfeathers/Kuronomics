package de.kuro.kuronomics.jobs.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import de.kuro.kuronomics.jobs.JobService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.FurnaceResultSlot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;

@Mixin(FurnaceResultSlot.class)
public abstract class SmeltingMixin {
    @Shadow @Final private Player player;
    @Shadow private int removeCount;
    @WrapMethod(method = "checkTakeAchievements")
    private void work(ItemStack stack, Operation<Void> original) {
        int amount = removeCount; var item = stack.getItem(); original.call(stack);
        if (amount > 0 && player instanceof ServerPlayer p) JobService.get(p.server).completed(p, "smelt_item", item, p.blockPosition(), p.getMainHandItem(), amount, true);
    }
}
