package de.kuro.kuronomics.integration.worldguard;

import de.kuro.worldguard.api.ProtectionDecisionEvent;
import de.kuro.worldguard.api.WorldGuardNeoForge;
import de.kuro.kuronomics.economy.data.EconomyLedger;
import de.kuro.kuronomics.integration.RegionProtectionService.Capability;
import de.kuro.kuronomics.jobs.JobService;

/** Provide estate/job decisions here. WorldGuard still checks and enforces the actual action. */
final class ProtectionOverrides {
    static void decide(ProtectionDecisionEvent event) {
        var player = event.getActor();
        if (!player.server.isSameThread() || player.serverLevel() != event.getLevel()) return;
        Capability capability = switch (event.getAction()) {
            case BREAK, TRAMPLE, BUCKET_FILL, VEHICLE_DESTROY -> Capability.BREAK;
            case PLACE, VEHICLE_PLACE, BUCKET_EMPTY, IGNITE -> Capability.PLACE;
            case USE -> Capability.INTERACT;
            case ITEM_ON_BLOCK, BONE_MEAL -> Capability.PLACE;
            case INVENTORY -> Capability.CONTAINER;
            case ENTITY_INTERACT, RIDE -> Capability.ENTITY_INTERACT;
            case ENTITY_DAMAGE -> event.getTargetEntity() instanceof net.minecraft.world.entity.player.Player
                    ? null : Capability.ENTITY_DAMAGE;
            default -> null;
        };
        Capability workCapability = switch (event.getAction()) {
            case BREAK, PLACE, USE, INVENTORY, BONE_MEAL, ENTITY_INTERACT, ENTITY_DAMAGE -> capability;
            default -> null;
        };
        // Leave PvP and XP drops to WorldGuard. Other player actions inside an estate stay private,
        // even when the region's flags or passthrough would normally allow them.
        if (event.getAction() == ProtectionDecisionEvent.Action.PVP
                || event.getAction() == ProtectionDecisionEvent.Action.EXP_DROP) return;
        var ledger = EconomyLedger.get(player.server);
        boolean interaction = (capability == Capability.INTERACT || capability == Capability.CONTAINER
                || capability == Capability.ENTITY_INTERACT
                || event.getAction() == ProtectionDecisionEvent.Action.ITEM_PICKUP
                || event.getAction() == ProtectionDecisionEvent.Action.ITEM_DROP) && !event.getFlagName().equals("tnt");
        if (ledger.hasEstates()) {
            var context = event.getApplicableRegions();
            if (context == null || context.isVirtual()) return;
            var regions = context.getRegions();
            var local = WorldGuardNeoForge.player(player);
            boolean allow = false;
            for (var region : regions) {
                var estate = ledger.estateByRegion(event.getLevel().dimension().location().toString(), region.getId());
                if (estate == null || estate.regionBinding().isEmpty()) continue;
                if (ledger.companies().operations().containsKey(estate.key())) { event.setAllowed(false); return; }
                if (!estate.regionBinding().equals(WorldGuardIntegration.binding(event.getLevel(), region))) {
                    event.setAllowed(false); return;
                }
                boolean owner = region.isOwner(local), member = region.isMember(local);
                boolean guest = estate.guests().contains(player.getUUID());
                var companyAccess = ledger.companies().access().get(estate.key());
                boolean validTitle = estate.economicOwner().isCompany() ? companyAccess != null
                        && region.getOwners().getUniqueIds().containsAll(companyAccess.owners())
                        : estate.owner() != null && region.getOwners().contains(estate.owner());
                if (guest && !validTitle) {
                    event.setAllowed(false); return;
                }
                boolean permitted = owner || (guest ? interaction : member && (interaction || estate.buildingAllowed()));
                if (!permitted && workCapability != null) permitted = work(event, workCapability, zone ->
                        zone.region().equals(region.getId()) && zone.binding().equals(estate.regionBinding()));
                if (!permitted) { event.setAllowed(false); return; }
                allow = true;
            }
            if (allow) { event.setAllowed(true); return; }
        }
        if (workCapability != null && !event.isAllowed() && work(event, workCapability, zone -> {
            var context = event.getApplicableRegions();
            if (context == null || context.isVirtual()) return false;
            for (var region : context.getRegions()) {
                if (region.getId().equals(zone.region())
                        && zone.binding().equals(WorldGuardIntegration.binding(event.getLevel(), region))) return true;
            }
            return false;
        }))
            event.setAllowed(true);
    }
    private static boolean work(ProtectionDecisionEvent event, Capability capability, java.util.function.Predicate<de.kuro.kuronomics.jobs.WorkZones.Zone> contains) {
        return JobService.get(event.getActor().server).workAccess(event.getActor(), event.getPos(), capability,
                event.getTargetEntity() == null ? null : event.getTargetEntity().getType(), contains);
    }
    private ProtectionOverrides() {}
}
