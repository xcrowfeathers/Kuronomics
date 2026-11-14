package de.kuro.kuronomics.event;

import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.block.MachineKind;
import de.kuro.kuronomics.registry.*;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.BasicItemListing;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.village.VillagerTradesEvent;

@EventBusSubscriber(modid = Kuronomics.MODID)
public final class BankerEvents {
    private BankerEvents() {}
    @SubscribeEvent public static void trades(VillagerTradesEvent event) {
        if (event.getType() != BankerContent.BANKER.get()) return;
        event.getTrades().get(1).add(new BasicItemListing(new ItemStack(Items.DIAMOND), new ItemStack(ModContent.CASH.get(10).get(), 5), 100, 1, .05f));
        event.getTrades().get(1).add(new BasicItemListing(new ItemStack(Items.DIAMOND), new ItemStack(ModContent.CASH.get(50).get()), 50, 1, .05f));
        event.getTrades().get(2).add(new BasicItemListing(new ItemStack(Items.DIAMOND, 2), new ItemStack(ModContent.CASH.get(100).get()), 50, 1, .05f));
        event.getTrades().get(2).add(new BasicItemListing(new ItemStack(Items.DIAMOND, 10), new ItemStack(ModContent.CASH.get(500).get()), 25, 3, .05f));
        for (MachineKind kind : new MachineKind[]{MachineKind.VENDING, MachineKind.STACK_VENDING})
            event.getTrades().get(3).add(new BasicItemListing(new ItemStack(ModContent.CASH.get(500).get(), 2), new ItemStack(ModContent.MACHINES.get(kind).get()), 10, 5, .05f));
    }
    @SubscribeEvent public static void interact(PlayerInteractEvent.EntityInteract event) {
        if (event.getTarget() instanceof Villager villager && villager.getVillagerData().getProfession() == BankerContent.BANKER.get() && !event.getLevel().getGameRules().getBoolean(BankerContent.ENABLED)) {
            event.setCanceled(true); event.setCancellationResult(InteractionResult.FAIL);
        }
    }
}
