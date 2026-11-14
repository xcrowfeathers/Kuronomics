package de.kuro.kuronomics.integration.worldguard;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.domains.DefaultDomain;
import de.kuro.worldguard.api.WorldGuardNeoForge;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.managers.storage.StorageException;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import de.kuro.kuronomics.integration.PropertyIntegration;
import de.kuro.kuronomics.integration.RegionProtectionService;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Keep optional WorldGuard/EngineHub imports inside this package so the mod works without them. */
public final class WorldGuardIntegration implements PropertyIntegration {
    public WorldGuardIntegration() {
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(ProtectionOverrides::decide);
    }
    @Override public boolean available() {
        try {
            return WorldGuardNeoForge.isReady()
                    && WorldGuard.getInstance().getPlatform().getRegionContainer() != null;
        } catch (RuntimeException | LinkageError unavailable) { return false; }
    }

    private RegionManager manager(ServerLevel level) {
        if (!available()) throw new IllegalStateException(REQUIRED_MESSAGE);
        RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(WorldGuardNeoForge.world(level));
        if (manager == null) throw new IllegalStateException("WorldGuard regions are unavailable in this dimension.");
        return manager;
    }

    @Override public Optional<Region> find(ServerLevel level, String id) {
        return Optional.ofNullable(manager(level).getRegion(id)).filter(r -> !(r instanceof GlobalProtectedRegion))
                .map(r -> snapshot(level, r));
    }
    @Override public Set<String> regionNames(ServerLevel level) {
        var names = new TreeSet<>(manager(level).getRegions().keySet()); names.remove("__global__"); return names;
    }
    @Override public boolean containsRegion(ServerLevel level, String id, String binding, BlockPos pos) {
        if (!available()) return false;
        ProtectedRegion region = manager(level).getRegion(id);
        if (region == null || region instanceof GlobalProtectedRegion || !region.contains(vector(pos))) return false;
        return binding.equals(binding(level, region));
    }
    static String binding(ServerLevel level, ProtectedRegion region) {
        String geometry = region.getType().name() + ":" + region.getMinimumPoint() + ":" + region.getMaximumPoint() + ":" + region.getPoints();
        return UUID.nameUUIDFromBytes((level.dimension().location() + "|" + region.getId() + "|" + geometry).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
    @Override public Optional<Region> at(ServerPlayer player) {
        return at(player.serverLevel(), player.blockPosition()).stream()
                .max(Comparator.comparingInt(Region::priority).thenComparing(Region::name));
    }
    @Override public List<Region> at(ServerLevel level, BlockPos position) {
        return manager(level).getApplicableRegions(vector(position)).getRegions().stream()
                .filter(r -> !(r instanceof GlobalProtectedRegion)).map(r -> snapshot(level, r)).toList();
    }
    @Override public RegionProtectionService.Access access(ServerPlayer player, BlockPos position, RegionProtectionService.Capability capability) {
        var world = WorldGuardNeoForge.world(player.serverLevel());
        var local = WorldGuardNeoForge.player(player);
        if (WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(local, world))
            return RegionProtectionService.Access.ALLOW;
        StateFlag flag = switch (capability) {
            case BREAK -> Flags.BLOCK_BREAK;
            case PLACE -> Flags.BLOCK_PLACE;
            case INTERACT, ENTITY_INTERACT -> Flags.INTERACT;
            case CONTAINER -> Flags.CHEST_ACCESS;
            case ENTITY_DAMAGE -> Flags.DAMAGE_ANIMALS;
        };
        // Don't let a missing region manager fall through to RegionQuery's permissive virtual set.
        manager(player.serverLevel());
        boolean allowed = WorldGuard.getInstance().getPlatform().getRegionContainer().createQuery().testBuild(
                new com.sk89q.worldedit.util.Location(world, position.getX(), position.getY(), position.getZ()), local, flag);
        return allowed ? RegionProtectionService.Access.ALLOW : RegionProtectionService.Access.DENY;
    }
    private static BlockVector3 vector(BlockPos pos) { return BlockVector3.at(pos.getX(), pos.getY(), pos.getZ()); }
    private ProtectedRegion requireRegion(RegionManager manager, String id) {
        ProtectedRegion region = manager.getRegion(id);
        if (region == null || region instanceof GlobalProtectedRegion)
            throw new IllegalArgumentException("WorldGuard property region not found: " + id);
        // Named/group domains can't safely represent an estate owned by UUIDs.
        requireUuidDomain(region.getOwners()); requireUuidDomain(region.getMembers());
        return region;
    }
    private static void requireUuidDomain(DefaultDomain domain) {
        if (!uuidDomain(domain))
            throw new IllegalArgumentException("Estate owners and residents must use player UUIDs; ask an administrator to convert named/group entries.");
    }
    private static boolean uuidDomain(DefaultDomain domain) { return domain.getPlayers().isEmpty() && domain.getGroups().isEmpty(); }
    private Region snapshot(ServerLevel level, ProtectedRegion region) {
        var min = region.getMinimumPoint(); var max = region.getMaximumPoint();
        return new Region(region.getId(), level.dimension().location().toString(),
                Optional.of(new Bounds(min.x(), min.y(), min.z(), max.x(), max.y(), max.z())), region.getPriority(),
                players(level, region.getOwners()), players(level, region.getMembers()),
                region.getType().name() + ":" + min + ":" + max + ":" + region.getPoints(),
                uuidDomain(region.getOwners()) && uuidDomain(region.getMembers()));
    }
    private static Map<UUID, String> players(ServerLevel level, DefaultDomain domain) {
        Map<UUID, String> players = new HashMap<>();
        for (UUID id : domain.getUniqueIds()) players.put(id, de.kuro.kuronomics.util.PlayerNames.get(level.getServer(), id));
        return players;
    }
    @Override public void replaceOwner(ServerLevel level, String id, UUID owner, UUID formerTenant) {
        mutate(level, id, region -> {
            DefaultDomain owners = new DefaultDomain();
            if (owner != null) owners.addPlayer(owner);
            region.setOwners(owners);
            if (formerTenant != null) region.getMembers().removePlayer(formerTenant);
            if (owner != null) region.getMembers().removePlayer(owner);
        });
    }
    @Override public void setResident(ServerLevel level, String id, UUID player, boolean present) {
        mutate(level, id, region -> {
            if (present) region.getMembers().addPlayer(player);
            else region.getMembers().removePlayer(player);
        });
    }
    @Override public void updateDomains(ServerLevel level, String id, Set<UUID> ownersToAdd, Set<UUID> ownersToRemove,
                                         Set<UUID> membersToAdd, Set<UUID> membersToRemove) {
        mutate(level, id, region -> {
            ownersToRemove.forEach(region.getOwners()::removePlayer); membersToRemove.forEach(region.getMembers()::removePlayer);
            ownersToAdd.forEach(region.getOwners()::addPlayer); membersToAdd.forEach(region.getMembers()::addPlayer);
        });
    }
    private void mutate(ServerLevel level, String id, java.util.function.Consumer<ProtectedRegion> change) {
        de.kuro.kuronomics.economy.FinancialWriter.await(level.getServer());
        RegionManager manager = manager(level); ProtectedRegion region = requireRegion(manager, id);
        DefaultDomain owners = new DefaultDomain(region.getOwners()), members = new DefaultDomain(region.getMembers());
        try { change.accept(region); manager.save(); }
        catch (StorageException | RuntimeException failure) {
            region.setOwners(owners); region.setMembers(members);
            try { manager.save(); } catch (StorageException | RuntimeException rollback) { failure.addSuppressed(rollback); }
            throw new IllegalStateException("WorldGuard could not save property ownership; operation remains pending.", failure);
        }
    }
}
