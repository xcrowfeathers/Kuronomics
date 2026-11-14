package de.kuro.kuronomics.estate;

import de.kuro.kuronomics.economy.data.EconomyLedger;
import de.kuro.kuronomics.integration.*;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class PropertyService {
    public enum MarketplaceTransferStatus { READY, TRANSFERRED, INVALID, UNAVAILABLE }
    public record AuctionableProperty(String id, String displayName, String dimension) {}
    public record PropertyDetails(String id, String displayName, String dimension, long width, long length) {
        public PropertyDetails {
            if (id == null || id.isBlank() || displayName == null || displayName.isBlank()
                    || dimension == null || dimension.isBlank() || width <= 0 || length <= 0)
                throw new IllegalArgumentException("Invalid property details");
        }
    }
    private final MinecraftServer server;
    private final EconomyLedger ledger;
    private final PropertyLockStore locks;
    private final PropertyIntegration integration;

    public PropertyService(MinecraftServer server) {
        this.server = Objects.requireNonNull(server); checkThread();
        ledger = EconomyLedger.get(server); locks = PropertyLockStore.get(server); integration = PropertyIntegrations.get();
    }
    public boolean integrationAvailable() { return integration.available(); }
    public void requireIntegration() {
        if (!integration.available()) throw new IllegalStateException(PropertyIntegration.REQUIRED_MESSAGE);
    }
    public PropertyIntegration.Region region(Estate estate) {
        checkThread(); requireIntegration();
        if (estate.regionBinding().isEmpty()) throw new IllegalStateException("This estate needs registration by an administrator standing inside its WorldGuard region using /estate setprice before it can be used.");
        var region = integration.require(level(estate), estate.region());
        requireUuidDomains(region);
        if (!estate.regionBinding().equals(region.binding())) throw new IllegalArgumentException("Property geometry or region identity changed; administrator review is required.");
        return region;
    }
    public boolean awaitingRegistration(String propertyId) {
        Estate estate = ledger.estateByKey(propertyId); return estate != null && estate.regionBinding().isEmpty();
    }
    public Map<UUID, String> registrationOwners(ServerPlayer player, String regionId) {
        checkThread(); requireIntegration(); var region = integration.require(player.serverLevel(), regionId);
        requireUuidDomains(region); return region.owners();
    }
    private static void requireUuidDomains(PropertyIntegration.Region region) {
        if (!region.uuidDomains()) throw new IllegalArgumentException("Estate owners and residents must use player UUIDs; ask an administrator to convert named/group entries.");
    }
    public void requireOwnershipState(Estate estate) {
        var owners = owners(estate);
        if (estate.economicOwner().isCompany()) {
            var managed = ledger.companies().access().get(estate.key());
            if (managed == null || !managed.company().equals(estate.economicOwner().id()) || !owners.keySet().containsAll(managed.owners()))
                throw new IllegalArgumentException("Company property access requires recovery or administrator review.");
            return;
        }
        if (estate.owner() == null ? !owners.isEmpty() : !owners.keySet().equals(Set.of(estate.owner())))
            throw new IllegalArgumentException("Property ownership changed outside Kuronomy.");
    }
    /** Use setprice's registration checks. Keep the original estate IDs and transaction references. */
    public Estate registerRegion(Estate estate, String regionId) {
        checkThread(); requireIntegration();
        var region = integration.require(level(estate), regionId);
        requireUuidDomains(region);
        if (region.bounds().isEmpty()) throw new IllegalArgumentException("Property has no usable geometry.");
        if (ledger.estates().stream().anyMatch(e -> !e.key().equals(estate.key()) && e.dimension().equals(estate.dimension())
                && e.region().equalsIgnoreCase(region.name()))) throw new IllegalArgumentException("Region already belongs to another estate.");
        var lock = locks.lock(estate.key());
        UUID expectedOwner = estate.owner();
        var managed = ledger.companies().access().get(estate.key());
        boolean originalOwner = estate.economicOwner().isCompany() ? managed != null && region.owners().keySet().containsAll(managed.owners())
                : expectedOwner == null ? region.owners().isEmpty() : region.owners().keySet().equals(Set.of(expectedOwner));
        boolean pendingTarget = lock != null && lock.transferTarget() != null && region.owners().keySet().equals(Set.of(lock.transferTarget()));
        if (!originalOwner && !pendingTarget) throw new IllegalArgumentException("Region owners do not match the saved estate or its pending transfer.");
        if (estate.tenant() != null && !region.residents().containsKey(estate.tenant()))
            throw new IllegalArgumentException("The saved tenant is missing from the region members.");
        if (!estate.regionBinding().isEmpty() && lock != null)
            throw new IllegalArgumentException("Cannot register a property during an active transaction.");
        return estate.bound(region.name(), region.binding());
    }
    public boolean rentUsable(Estate estate) {
        try {
            var region = region(estate);
            if (ledger.companies().operations().containsKey(estate.key())) return false;
            if (estate.economicOwner().isCompany()) {
                requireOwnershipState(estate);
                return estate.tenant() != null && region.residents().containsKey(estate.tenant());
            }
            return estate.tenant() != null && region.residents().containsKey(estate.tenant())
                    && (estate.owner() == null ? region.owners().isEmpty() : region.owners().keySet().equals(Set.of(estate.owner())));
        } catch (IllegalArgumentException | IllegalStateException invalid) { return false; }
    }
    public String propertyAt(ServerPlayer player) {
        checkThread(); requireIntegration();
        return integration.at(player).orElseThrow(() -> new IllegalArgumentException("You are not inside an estate zone.")).name();
    }
    public Map<UUID, String> owners(Estate estate) { return region(estate).owners(); }
    public Map<UUID, String> residents(Estate estate) { return region(estate).residents(); }
    public boolean isOwner(Estate estate, UUID owner) { return owners(estate).keySet().equals(Set.of(owner)); }
    public void setOwner(Estate estate, UUID owner, boolean clearResidents) {
        if (estate.economicOwner().isCompany()) throw new IllegalArgumentException("Company title requires a Company asset operation");
        region(estate);
        // Only the recorded tenant is ours to remove. Leave admin-added members alone.
        integration.replaceOwner(level(estate), estate.region(), owner, clearResidents ? estate.tenant() : null);
    }
    public void setResident(Estate estate, UUID player, boolean present) {
        region(estate); integration.setResident(level(estate), estate.region(), player, present);
    }
    public List<AuctionableProperty> getAuctionableProperties(UUID owner) {
        checkThread(); Objects.requireNonNull(owner);
        if (!integration.available()) return List.of();
        return ledger.estates().stream().filter(e -> owner.equals(e.owner()) && e.tenant() == null && locks.lock(e.key()) == null)
                .filter(e -> isValidOwner(e, owner)).map(e -> new AuctionableProperty(e.key(), e.region(), e.dimension()))
                .sorted(Comparator.comparing(AuctionableProperty::displayName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(AuctionableProperty::id)).toList();
    }
    public List<PropertyDetails> getMarketableProperties(UUID owner) {
        checkThread(); Objects.requireNonNull(owner);
        if (!integration.available()) return List.of();
        List<PropertyDetails> result = new ArrayList<>();
        for (Estate estate : ledger.estates()) {
            if (!owner.equals(estate.owner()) || estate.tenant() != null || locks.lock(estate.key()) != null) continue;
            try { result.add(requireMarketable(owner, estate.key())); }
            catch (IllegalArgumentException | IllegalStateException unavailable) { /* Skip stale regions or ones changed outside the mod. */ }
        }
        return result.stream().sorted(Comparator.comparing(PropertyDetails::displayName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(PropertyDetails::id)).toList();
    }
    public boolean canAuction(UUID owner, String propertyId) {
        checkThread(); Estate estate = ledger.estateByKey(propertyId);
        return estate != null && owner.equals(estate.owner()) && estate.tenant() == null
                && locks.lock(propertyId) == null && isValidOwner(estate, owner);
    }
    public Estate requireAuctionable(UUID owner, String propertyId) {
        checkThread(); Estate estate = ledger.estateByKey(Objects.requireNonNull(propertyId));
        requireIntegration();
        if (estate == null) throw new IllegalArgumentException("This property no longer exists.");
        if (!owner.equals(estate.owner())) throw new IllegalArgumentException("You do not own this property.");
        if (estate.tenant() != null) throw new IllegalArgumentException("Rented properties cannot be auctioned.");
        if (locks.lock(propertyId) != null) throw new IllegalArgumentException("This property is already listed or locked.");
        if (!isValidOwner(estate, owner)) throw new IllegalArgumentException("Property ownership could not be verified with WorldGuard.");
        return estate;
    }
    public PropertyDetails requireMarketable(UUID owner, String propertyId) {
        return requireMarketable(owner, propertyId, null);
    }
    /** Allow this listing's ID during recovery: a crash may have saved the lock before saving the listing. */
    public PropertyDetails requireMarketable(UUID owner, String propertyId, UUID allowedListingId) {
        checkThread(); Objects.requireNonNull(owner); Objects.requireNonNull(propertyId); requireIntegration();
        Estate estate = ledger.estateByKey(propertyId);
        if (estate == null) throw new IllegalArgumentException("This property no longer exists.");
        if (!owner.equals(estate.owner())) throw new IllegalArgumentException("You do not own this property.");
        if (estate.tenant() != null) throw new IllegalArgumentException("Rented properties cannot be listed.");
        PropertyLockStore.Lock existing = locks.lock(propertyId);
        if (existing != null && !(existing.purpose() == PropertyLockStore.Purpose.MARKETPLACE
                && allowedListingId != null && existing.operationId().equals(allowedListingId)
                && existing.sellerId().equals(owner)))
            throw new IllegalArgumentException("This property is already listed or locked.");
        if (!isValidOwner(estate, owner)) throw new IllegalArgumentException("Property ownership could not be verified with WorldGuard.");
        PropertyIntegration.Region region = region(estate);
        PropertyIntegration.Bounds bounds = region.bounds().orElseThrow(() ->
                new IllegalArgumentException("Property bounds are unavailable."));
        return new PropertyDetails(estate.key(), region.name(), region.dimension(), bounds.width(), bounds.length());
    }
    public void lockForAuction(UUID owner, String propertyId, UUID auctionId) {
        checkThread(); Estate estate = ledger.estateByKey(propertyId); PropertyLockStore.Lock existing = locks.lock(propertyId);
        if (existing != null) {
            if (existing.purpose() == PropertyLockStore.Purpose.AUCTION
                    && existing.operationId().equals(auctionId) && existing.sellerId().equals(owner)) return;
            throw new IllegalArgumentException("This property is already listed or locked.");
        }
        if (estate == null || !owner.equals(estate.owner()) || estate.tenant() != null || !isValidOwner(estate, owner))
            throw new IllegalArgumentException("This property is no longer eligible for auction.");
        locks.add(new PropertyLockStore.Lock(propertyId, PropertyLockStore.Purpose.AUCTION,
                auctionId, owner, null, System.currentTimeMillis())); save();
    }
    public void unlockAfterFailedAuction(UUID owner, String propertyId, UUID auctionId) {
        checkThread(); PropertyLockStore.Lock lock = locks.lock(propertyId);
        if (lock == null) return;
        if (lock.purpose() != PropertyLockStore.Purpose.AUCTION || !lock.operationId().equals(auctionId)
                || !lock.sellerId().equals(owner))
            return; // Recovering an old auction mustn't remove a newer operation's lock.
        if (lock.transferTarget() != null) throw new IllegalStateException("Property ownership transfer is already in progress.");
        locks.remove(propertyId, PropertyLockStore.Purpose.AUCTION, auctionId); save();
    }
    public void lockForMarketplace(UUID owner, String propertyId, UUID listingId) {
        checkThread(); Objects.requireNonNull(listingId);
        requireMarketable(owner, propertyId, listingId);
        locks.add(new PropertyLockStore.Lock(propertyId, PropertyLockStore.Purpose.MARKETPLACE,
                listingId, owner, null, System.currentTimeMillis())); save();
    }
    public void unlockMarketplace(UUID owner, String propertyId, UUID listingId) {
        checkThread(); PropertyLockStore.Lock lock = locks.lock(propertyId);
        if (lock == null) return;
        if (lock.purpose() != PropertyLockStore.Purpose.MARKETPLACE || !lock.operationId().equals(listingId)
                || !lock.sellerId().equals(owner))
            throw new IllegalStateException("Property is locked by another operation.");
        if (lock.transferTarget() != null) throw new IllegalStateException("Property ownership transfer is already in progress.");
        locks.remove(propertyId, PropertyLockStore.Purpose.MARKETPLACE, listingId); save();
    }
    public boolean isStillValid(String propertyId) {
        checkThread(); Estate estate = ledger.estateByKey(propertyId);
        if (!integration.available() || estate == null) return false;
        try { return region(estate).bounds().isPresent(); }
        catch (IllegalArgumentException | IllegalStateException invalid) { return false; }
    }
    public boolean isEscrowedForAuction(String propertyId, UUID auctionId, UUID seller) {
        checkThread(); PropertyLockStore.Lock lock = locks.lock(propertyId); Estate estate = ledger.estateByKey(propertyId);
        return lock != null && lock.purpose() == PropertyLockStore.Purpose.AUCTION
                && lock.operationId().equals(auctionId) && lock.sellerId().equals(seller)
                && estate != null && seller.equals(estate.owner()) && estate.tenant() == null && isValidOwner(estate, seller);
    }
    public boolean isLockedForMarketplace(String propertyId, UUID listingId, UUID seller) {
        checkThread(); PropertyLockStore.Lock lock = locks.lock(propertyId); Estate estate = ledger.estateByKey(propertyId);
        return lock != null && lock.purpose() == PropertyLockStore.Purpose.MARKETPLACE
                && lock.operationId().equals(listingId) && lock.sellerId().equals(seller)
                && estate != null && seller.equals(estate.owner()) && estate.tenant() == null && isValidOwner(estate, seller);
    }
    public Collection<PropertyLockStore.Lock> marketplaceLocks() {
        checkThread();
        return locks.locks().stream().filter(lock -> lock.purpose() == PropertyLockStore.Purpose.MARKETPLACE).toList();
    }
    public MarketplaceTransferStatus marketplaceTransferStatus(String propertyId, UUID listingId, UUID seller, UUID buyer) {
        checkThread(); Objects.requireNonNull(propertyId); Objects.requireNonNull(listingId); Objects.requireNonNull(seller); Objects.requireNonNull(buyer);
        if (!integration.available()) return MarketplaceTransferStatus.UNAVAILABLE;
        Estate estate = ledger.estateByKey(propertyId); PropertyLockStore.Lock lock = locks.lock(propertyId);
        if (estate == null) return MarketplaceTransferStatus.INVALID;
        if (estate.regionBinding().isEmpty()) return MarketplaceTransferStatus.UNAVAILABLE;
        if (lock != null && (lock.purpose() != PropertyLockStore.Purpose.MARKETPLACE || !lock.operationId().equals(listingId)
                || !lock.sellerId().equals(seller) || lock.transferTarget() != null && !lock.transferTarget().equals(buyer)))
            return MarketplaceTransferStatus.INVALID;
        try {
            region(estate);
            boolean buyerOwns = isOwner(estate, buyer), sellerOwns = isOwner(estate, seller);
            if (buyerOwns && (buyer.equals(estate.owner()) || lock != null && buyer.equals(lock.transferTarget()) && seller.equals(estate.owner()))) return MarketplaceTransferStatus.TRANSFERRED;
            if (lock != null && sellerOwns && seller.equals(estate.owner()) && estate.tenant() == null)
                return MarketplaceTransferStatus.READY;
            return MarketplaceTransferStatus.INVALID;
        } catch (IllegalArgumentException missing) { return MarketplaceTransferStatus.INVALID; }
        catch (IllegalStateException unavailable) { return MarketplaceTransferStatus.UNAVAILABLE; }
    }
    /** Retries must stay safe even if a crash happened between saves during this ownership transfer. */
    public boolean transferMarketplaceToBuyer(String propertyId, UUID listingId, UUID seller, UUID buyer) {
        checkThread(); Objects.requireNonNull(buyer); PropertyLockStore.Lock lock = locks.lock(propertyId); Estate estate = ledger.estateByKey(propertyId);
        if (lock == null) return estate != null && buyer.equals(estate.owner()) && isValidOwner(estate, buyer);
        if (lock.purpose() != PropertyLockStore.Purpose.MARKETPLACE || !lock.operationId().equals(listingId) || !lock.sellerId().equals(seller))
            throw new IllegalStateException("Property is locked by another operation.");
        if (lock.transferTarget() != null && !lock.transferTarget().equals(buyer)) throw new IllegalStateException("Property transfer target changed.");
        if (estate == null) throw new IllegalStateException("The property no longer exists.");
        if (estate.tenant() != null) throw new IllegalStateException("The listed property unexpectedly has a tenant.");
        if (!seller.equals(estate.owner()) && !buyer.equals(estate.owner()))
            throw new IllegalStateException("Property ownership changed outside the market.");
        requireIntegration();
        if (lock.transferTarget() == null && !isOwner(estate, seller))
            throw new IllegalStateException("Property ownership changed before the market transfer.");
        if (lock.transferTarget() == null) { lock = lock.transferring(buyer); locks.replace(lock); save(); }
        requireIntegration();
        if (!isOwner(estate, buyer)) {
            if (!isOwner(estate, seller)) throw new IllegalStateException("WorldGuard property ownership changed outside the market.");
            setOwner(estate, buyer, true);
        }
        if (!buyer.equals(estate.owner())) ledger.estate(estate.owner(buyer).tenant(null));
        locks.remove(propertyId, PropertyLockStore.Purpose.MARKETPLACE, listingId); save(); return true;
    }
    /** Only remove this listing's lock if ownership wasn't transferred to the buyer. */
    public void abortMarketplaceTransfer(String propertyId, UUID listingId, UUID seller, UUID buyer) {
        checkThread(); MarketplaceTransferStatus status = marketplaceTransferStatus(propertyId, listingId, seller, buyer);
        if (status == MarketplaceTransferStatus.TRANSFERRED || status == MarketplaceTransferStatus.UNAVAILABLE)
            throw new IllegalStateException("Marketplace transfer cannot be safely aborted.");
        PropertyLockStore.Lock lock = locks.lock(propertyId);
        if (lock != null && lock.purpose() == PropertyLockStore.Purpose.MARKETPLACE
                && lock.operationId().equals(listingId) && lock.sellerId().equals(seller)) {
            locks.remove(propertyId, PropertyLockStore.Purpose.MARKETPLACE, listingId); save();
        }
    }
    /** Retries must work across a crash between the WorldGuard, ledger and lock-store saves. */
    public boolean transferToBuyer(String propertyId, UUID auctionId, UUID buyer) {
        checkThread(); Objects.requireNonNull(buyer); PropertyLockStore.Lock lock = locks.lock(propertyId);
        Estate estate = ledger.estateByKey(propertyId);
        if (lock == null) return estate != null && buyer.equals(estate.owner()) && isValidOwner(estate, buyer);
        if (lock.purpose() != PropertyLockStore.Purpose.AUCTION || !lock.operationId().equals(auctionId))
            throw new IllegalStateException("Property is locked by another operation.");
        if (lock.transferTarget() != null && !lock.transferTarget().equals(buyer)) throw new IllegalStateException("Property transfer target changed.");
        if (estate == null) throw new IllegalStateException("The property no longer exists.");
        if (estate.tenant() != null) throw new IllegalStateException("The auctioned property unexpectedly has a tenant.");
        if (!lock.sellerId().equals(estate.owner()) && !buyer.equals(estate.owner()))
            throw new IllegalStateException("Property ownership changed outside the auction.");
        requireIntegration();
        if (lock.transferTarget() == null && !isOwner(estate, lock.sellerId()))
            throw new IllegalStateException("Property ownership changed before the auction transfer.");
        if (lock.transferTarget() == null) { lock = lock.transferring(buyer); locks.replace(lock); save(); }
        requireIntegration();
        if (!isOwner(estate, buyer)) {
            if (!isOwner(estate, lock.sellerId())) throw new IllegalStateException("WorldGuard property ownership changed outside the auction.");
            setOwner(estate, buyer, true);
        }
        if (!buyer.equals(estate.owner())) ledger.estate(estate.owner(buyer).tenant(null));
        locks.remove(propertyId, PropertyLockStore.Purpose.AUCTION, auctionId); save(); return true;
    }
    public void requireUnlocked(String propertyId) {
        checkThread();
        if (ledger.companies().operations().containsKey(propertyId))
            throw new IllegalArgumentException("This property has an unfinished company change. Ask an admin to use /companyadmin recover.");
        if (locks.lock(propertyId) != null)
            throw new IllegalArgumentException("This property is locked in an active auction or market listing.");
    }
    public String displayName(String propertyId) {
        Estate estate = ledger.estateByKey(propertyId); return estate == null ? propertyId.substring(propertyId.lastIndexOf('|') + 1) : estate.region();
    }
    public Optional<PropertyLockStore.Lock> lock(String propertyId) { checkThread(); return Optional.ofNullable(locks.lock(propertyId)); }
    private boolean isValidOwner(Estate estate, UUID owner) {
        if (!integration.available()) return false;
        try { return isOwner(estate, owner); }
        catch (IllegalArgumentException | IllegalStateException unavailable) { return false; }
    }
    private ServerLevel level(Estate estate) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(estate.dimension())));
        if (level == null) throw new IllegalArgumentException("Property dimension is unavailable."); return level;
    }
    private void save() { de.kuro.kuronomics.economy.FinancialPersistence.save(server); }
    private void checkThread() { if (!server.isSameThread()) throw new IllegalStateException("Properties accessed off server thread"); }
}
