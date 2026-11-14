package de.kuro.kuronomics.estate;

import de.kuro.kuronomics.economy.*;
import de.kuro.kuronomics.economy.data.*;
import de.kuro.kuronomics.company.*;
import de.kuro.kuronomics.mailbox.MailboxService;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class EstateService {
    private final MinecraftServer server;
    private final EconomyLedger ledger;
    private final PropertyService properties;
    public EstateService(MinecraftServer server) { this.server = server; ledger = EconomyLedger.get(server); properties = new PropertyService(server); }
    public Estate find(ServerPlayer player, String name) {
        String region = name == null ? properties.propertyAt(player) : name;
        return ledger.estates().stream().filter(e -> e.key().equals(region) || e.dimension().equals(player.level().dimension().location().toString()) && e.region().equalsIgnoreCase(region))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("This zone is not registered as an estate."));
    }
    public Estate rented(ServerPlayer player, String name) {
        if (name == null) return find(player, null);
        var matches = ledger.estates().stream()
                .filter(e -> player.getUUID().equals(e.tenant()) || isLandlord(player, e) || player.hasPermissions(2))
                .filter(e ->
                (e.key().equals(name) || e.region().equalsIgnoreCase(name) || e.region().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").equals(name.toLowerCase(Locale.ROOT)))).toList();
        if (matches.size() != 1) throw new IllegalArgumentException(matches.isEmpty() ? "No accessible rental matches that estate." : "Several rentals match; use the full estate key.");
        return matches.getFirst();
    }
    public void requireOwner(ServerPlayer player, Estate e) {
        if (e.economicOwner().isCompany()) {
            CompanyPermissionService.require(CompanyService.get(server).require(e.economicOwner().id()), player.getUUID(), CompanyPermissionService.Capability.LANDLORD);
            properties.requireOwnershipState(e); return;
        }
        if (!player.getUUID().equals(e.owner()) || !properties.isOwner(e, player.getUUID())) throw new IllegalArgumentException("You don't own that estate.");
    }
    public boolean isLandlord(ServerPlayer player, Estate e) {
        if (!e.economicOwner().isCompany()) return player.getUUID().equals(e.owner());
        Company c = ledger.companies().company(e.economicOwner().id());
        return c != null && CompanyPermissionService.allowed(c, player.getUUID(), CompanyPermissionService.Capability.LANDLORD);
    }
    private void updateLandlord(ServerPlayer actor, Estate old, Estate updated, String reason) {
        if (!old.economicOwner().isCompany()) { ledger.estate(updated); return; }
        var context = new ActionContext(actor.getUUID(), old.economicOwner(), UUID.randomUUID());
        var edit = ledger.companies().edit(); edit.audit(CompanyAudit.of(context, context.owner(), context.owner(), reason, 0, old.key()));
        ledger.commitOwners(context.operation(), reason + "|" + old.key() + "|" + context.actor(), Map.of(), List.of(), edit.build(), Map.of(old.key(), updated));
    }
    public enum SuggestionUse { INFO, OWNER_INFO, OWNER, BUILDING, BUY, RENT, UNRENT, REMOVE }
    /** Only a hint for command completion. Check the current state again when the command actually runs. */
    public boolean canSuggest(ServerPlayer player, Estate e, SuggestionUse use) {
        if (use == SuggestionUse.INFO) return true;
        try {
            if (use == SuggestionUse.OWNER_INFO) { requireOwner(player, e); return true; }
            properties.requireUnlocked(e.key());
            switch (use) {
                case OWNER -> requireOwner(player, e);
                case BUILDING -> {
                    if (e.economicOwner().isCompany()) requireOwner(player, e);
                    else if (e.owner() == null && player.hasPermissions(2)) properties.region(e);
                    else if (!properties.owners(e).containsKey(player.getUUID())) return false;
                }
                case REMOVE -> { if (!player.hasPermissions(2) || e.economicOwner().isCompany()) return false; properties.region(e); }
                case UNRENT -> {
                    if (e.tenant() == null || !player.getUUID().equals(e.tenant()) && !isLandlord(player, e) && !player.hasPermissions(2)) return false;
                    properties.region(e);
                }
                case BUY, RENT -> {
                    if (e.tenant() != null || !e.economicOwner().isCompany() && !properties.residents(e).isEmpty()) return false;
                    if (use == SuggestionUse.BUY) {
                        if (!e.economicOwner().equals(EconomicOwner.STATE) || !properties.owners(e).isEmpty()) return false;
                    } else {
                        if (e.locked() || e.rent() == 0 || player.getUUID().equals(e.owner())) return false;
                        properties.requireOwnershipState(e);
                    }
                    var economy = new EconomyService(ledger); economy.requireActive(player.getUUID());
                    if (economy.getAvailableBalance(player.getUUID()) < (use == SuggestionUse.BUY ? e.price() : e.rent())) return false;
                }
                default -> { return false; }
            }
            return true;
        } catch (IllegalArgumentException | IllegalStateException unavailable) { return false; }
    }
    public void price(ServerPlayer player, long price, Long rent) {
        if (!player.hasPermissions(2)) throw new IllegalArgumentException("Operator permission required");
        if (price < 0 || rent != null && rent < 0) throw new IllegalArgumentException("Negative estate price");
        String name = properties.propertyAt(player), dim = player.level().dimension().location().toString();
        Estate old = ledger.estateByKey(dim + "|" + name);
        if (old == null) old = ledger.estates().stream()
                .filter(e -> e.dimension().equals(dim) && e.region().equalsIgnoreCase(name)).findFirst().orElse(null);
        if (old == null) {
            var owners = properties.registrationOwners(player, name);
            if (owners.size() > 1) throw new IllegalArgumentException("Estate must have at most one owner");
            old = new Estate(dim, name, price, rent == null ? 0 : rent, owners.isEmpty() ? null : owners.keySet().iterator().next(), null, false);
        } else properties.requireUnlocked(old.key());
        ledger.estate(properties.registerRegion(old, name).prices(price, rent == null ? old.rent() : rent));
        de.kuro.kuronomics.economy.FinancialPersistence.save(server);
    }
    public void buy(ServerPlayer player, Estate e) {
        properties.requireUnlocked(e.key());
        if (!e.economicOwner().equals(EconomicOwner.STATE) || e.tenant() != null || !properties.owners(e).isEmpty() || !properties.residents(e).isEmpty()) throw new IllegalArgumentException("Estate already has owners or residents");
        var economy = new EconomyService(ledger); economy.requireActive(player.getUUID());
        if (economy.getAvailableBalance(player.getUUID()) < e.price()) throw new IllegalArgumentException("Insufficient funds");
        Math.addExact(StateTreasury.balance(ledger), e.price());
        properties.setOwner(e, player.getUUID(), false);
        UUID reference = UUID.randomUUID();
        if (e.price() > 0 && !economy.collectState(player.getUUID(), e.price(), reference, "estate purchase " + e.key())) return;
        ledger.estate(e.owner(player.getUUID()).locked(true));
        if (e.price() > 0) new MailboxService(server).payment(reference, player.getUUID(), null, e.price(),
                economy.getBalance(player.getUUID()), null, "Estate purchase: " + e.region());
    }
    public void sell(ServerPlayer player, Estate e) {
        if (e.economicOwner().isCompany()) throw new IllegalArgumentException("Use /company estate sell for Company property");
        requireOwner(player, e); properties.requireUnlocked(e.key()); var economy = new EconomyService(ledger);
        long refund = e.price() / 2;
        Math.subtractExact(StateTreasury.balance(ledger), refund);
        properties.setOwner(e, null, true);
        removeRentContracts(e); ledger.clearEviction(e.key());
        UUID reference = UUID.randomUUID();
        if (refund > 0 && !economy.payFromState(player.getUUID(), refund, reference, "estate sale " + e.key())) return;
        ledger.estate(e.tenant(null).owner(null).locked(false).building(true));
        if (refund > 0) new MailboxService(server).payment(reference, null, player.getUUID(), refund, 0,
                economy.getBalance(player.getUUID()), "Estate sale: " + e.region());
    }
    public void remove(ServerPlayer player, Estate e) {
        if (e.economicOwner().isCompany()) throw new IllegalArgumentException("Sell or close the Company before removing its property");
        if (!player.hasPermissions(2)) throw new IllegalArgumentException("Operator permission required");
        properties.requireUnlocked(e.key());
        properties.setOwner(e, null, true);
        removeRentContracts(e); ledger.clearEviction(e.key()); ledger.removeEstate(e.key());
    }
    public void rent(ServerPlayer player, Estate e) {
        if (e.economicOwner().isCompany()) { CompanyAssetService.get(server).rentToPlayer(player.getUUID(), e); return; }
        properties.requireUnlocked(e.key());
        properties.requireOwnershipState(e);
        if (e.locked() || e.rent() == 0 || e.tenant() != null || !e.economicOwner().isCompany() && !properties.residents(e).isEmpty()) throw new IllegalArgumentException("This estate is not available for rent");
        if (player.getUUID().equals(e.owner())) throw new IllegalArgumentException("You cannot rent your own estate");
        var economy = new EconomyService(ledger); economy.requireActive(player.getUUID());
        if (economy.getAvailableBalance(player.getUUID()) < e.rent()) throw new IllegalArgumentException("Insufficient funds");
        if (e.owner() == null) Math.addExact(StateTreasury.balance(ledger), e.rent());
        properties.setResident(e, player.getUUID(), true);
        UUID reference = UUID.randomUUID();
        if (e.owner() == null) {
            if (!economy.collectState(player.getUUID(), e.rent(), reference, "state rent " + e.key())) return;
        } else if (!economy.transfer(player.getUUID(), e.owner(), e.rent(), false, true,
                "Rent " + e.region() + " from " + player.getUUID(), reference)) return;
        ledger.estate(e.tenant(player.getUUID()));
        new MailboxService(server).payment(reference, player.getUUID(), e.owner(), e.rent(),
                economy.getBalance(player.getUUID()), null, "Rent: " + e.region());
    }
    public void unrent(ServerPlayer player, Estate e) {
        properties.requireUnlocked(e.key());
        if (!player.getUUID().equals(e.tenant()) && !isLandlord(player, e) && !player.hasPermissions(2)) throw new IllegalArgumentException("You are not the tenant or owner");
        if (e.tenant() == null) throw new IllegalArgumentException("Estate is not rented");
        evict(e);
        if (e.economicOwner().isCompany()) updateLandlord(player, e, ledger.estateByKey(e.key()), "LANDLORD_UNRENT");
    }
    public void evict(Estate e) {
        if (e.tenant() == null) return;
        properties.requireUnlocked(e.key());
        var access = ledger.companies().access().get(e.key());
        if (access == null || !access.members().contains(e.tenant())) properties.setResident(e, e.tenant(), false);
        removeRentContracts(e); ledger.estate(e.tenant(null)); ledger.clearEviction(e.key());
    }
    private void removeRentContracts(Estate e) {
        String legacy = e.dimension().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_") + "__" + e.region().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        for (var c : ledger.contracts()) if (c.name().equals("Rent:" + legacy) || c.name().equals("Rent:" + e.key())) ledger.removeContract(c);
    }
    public void lock(ServerPlayer player, Estate e, boolean value) { requireOwner(player, e); properties.requireUnlocked(e.key()); updateLandlord(player, e, e.locked(value), "LANDLORD_LOCK"); FinancialPersistence.save(server); }
    public void building(ServerPlayer player, Estate estate, boolean allow) {
        if (estate.economicOwner().isCompany()) requireOwner(player, estate);
        else if (estate.owner() == null && player.hasPermissions(2)) properties.region(estate);
        else if (!properties.owners(estate).containsKey(player.getUUID())) throw new IllegalArgumentException("You don't own that estate.");
        properties.requireUnlocked(estate.key());
        updateLandlord(player, estate, estate.building(allow), "LANDLORD_BUILDING"); de.kuro.kuronomics.economy.FinancialPersistence.save(server);
    }
    public void rentPrice(ServerPlayer player, Estate e, long value) { requireOwner(player, e); properties.requireUnlocked(e.key()); if (value < 0) throw new IllegalArgumentException("Negative rent"); updateLandlord(player, e, e.prices(e.price(), value), "LANDLORD_RENT"); FinancialPersistence.save(server); }
    public void transfer(ServerPlayer player, Estate e, ServerPlayer target) {
        if (e.economicOwner().isCompany()) { CompanyAssetService.get(server).transfer(new ActionContext(player.getUUID(), e.economicOwner(), UUID.randomUUID()), e, target.getUUID()); return; }
        requireOwner(player, e); properties.requireUnlocked(e.key()); if (player == target) throw new IllegalArgumentException("You already own this estate");
        properties.setOwner(e, target.getUUID(), false);
        removeRentContracts(e); ledger.estate(e.owner(target.getUUID()));
        if (target.getUUID().equals(e.tenant())) ledger.clearEviction(e.key());
        FinancialPersistence.save(server);
        target.sendSystemMessage(de.kuro.kuronomics.util.ModText.estate("§e§l" + player.getGameProfile().getName() + " §7transferred §b§l" + e.region() + " §7to you."));
    }
    public void member(ServerPlayer player, Estate e, ServerPlayer target, boolean add) {
        requireOwner(player, e); properties.requireUnlocked(e.key());
        if (e.economicOwner().isCompany() && ledger.companies().access().get(e.key()).owners().contains(target.getUUID())
                || e.economicOwner().isCompany() && ledger.companies().access().get(e.key()).members().contains(target.getUUID()))
            throw new IllegalArgumentException("Company access is managed through Company ranks");
        if (target.getUUID().equals(e.owner()) || target.getUUID().equals(e.tenant()))
            throw new IllegalArgumentException("Owners and renters already have access; use /unrent to end a rental.");
        // Kuronomy manages guest access. Don't grant WorldGuard membership or command permissions.
        // Only convert or remove an old non-paying resident when this command explicitly manages them.
        if (properties.residents(e).containsKey(target.getUUID())) properties.setResident(e, target.getUUID(), false);
        updateLandlord(player, e, e.guest(target.getUUID(), add), "LANDLORD_GUEST");
        de.kuro.kuronomics.economy.FinancialPersistence.save(server);
        target.sendSystemMessage(de.kuro.kuronomics.util.ModText.estate("You were " + (add ? "granted interaction-only access to " : "removed from interaction-only access to ")
                + "§b§l" + e.region() + " §7by §e§l" + player.getGameProfile().getName() + "§7."));
    }
}
