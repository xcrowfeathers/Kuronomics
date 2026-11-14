package de.kuro.kuronomics.menu;

import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.block.*;
import de.kuro.kuronomics.market.MarketPermissions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

@EventBusSubscriber(modid = Kuronomics.MODID)
public final class CompanyShopMenu {
    public static void assignOrOpen(ServerPlayer player, MachineBlockEntity machine) {
        if (!player.getUUID().equals(machine.owner()) || machine.assigned()) { player.sendSystemMessage(Component.literal("This shop is not assigned yet.")); return; }
        var choices = MarketPermissions.companies(player, true);
        if (choices.size() == 1) { machine.assignCompany(player, choices.getFirst().id()); return; }
        if (choices.isEmpty()) { player.sendSystemMessage(Component.literal("You need Manager or higher to assign this shop. Sneak-right-click to pick it back up.")); return; }
        MarketMenu.open(player, machine.getBlockPos());
    }
    @SubscribeEvent public static void breaking(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent e) {
        if (e.getLevel().getBlockEntity(e.getPos()) instanceof MachineBlockEntity m && m.kind == MachineKind.COMPANY_SHOP && !m.canRemove(e.getPlayer())) e.setCanceled(true);
    }
    @SubscribeEvent public static void explosion(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate e) {
        e.getAffectedBlocks().removeIf(pos -> e.getLevel().getBlockEntity(pos) instanceof MachineBlockEntity m && m.kind == MachineKind.COMPANY_SHOP);
    }
    private CompanyShopMenu() {}
}
