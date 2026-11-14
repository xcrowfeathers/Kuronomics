package de.kuro.kuronomics.jobs;

import de.kuro.kuronomics.Kuronomics;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

@EventBusSubscriber(modid = Kuronomics.MODID)
public final class JobPermissions {
    public static final PermissionNode<Boolean> MANAGE_APPLICATIONS = new PermissionNode<>(
            Kuronomics.MODID, "jobs.applications.manage", PermissionTypes.BOOLEAN,
            (player, playerId, context) -> player != null && player.hasPermissions(3));
    public static final PermissionNode<Boolean> EDIT_TERMINALS = new PermissionNode<>(
            Kuronomics.MODID, "jobs.terminals.edit", PermissionTypes.BOOLEAN,
            (player, playerId, context) -> player != null && player.hasPermissions(3));
    private JobPermissions() {}
    @SubscribeEvent public static void register(PermissionGatherEvent.Nodes event) {
        event.addNodes(MANAGE_APPLICATIONS, EDIT_TERMINALS);
    }
    public static boolean manage(ServerPlayer player) { return player.hasPermissions(3) || PermissionAPI.getPermission(player, MANAGE_APPLICATIONS); }
    public static boolean edit(ServerPlayer player) { return player.hasPermissions(3) || PermissionAPI.getPermission(player, EDIT_TERMINALS); }
}
