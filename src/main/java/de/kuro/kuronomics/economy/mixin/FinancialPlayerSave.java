package de.kuro.kuronomics.economy.mixin;

import de.kuro.kuronomics.economy.PlayerPersistence;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

/** Only Kuronomy transaction saves use this path. Normal player autosaves still use vanilla's path. */
@Mixin(PlayerList.class)
public abstract class FinancialPlayerSave {
    @WrapOperation(method = "save", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/storage/PlayerDataStorage;save(Lnet/minecraft/world/entity/player/Player;)V"))
    private void kuronomy$durableInventory(PlayerDataStorage storage, Player player, Operation<Void> original) {
        if (PlayerPersistence.financialSave(player)) PlayerPersistence.writePlayerData((ServerPlayer) player, storage.getPlayerDir());
        else original.call(storage, player);
    }
}
