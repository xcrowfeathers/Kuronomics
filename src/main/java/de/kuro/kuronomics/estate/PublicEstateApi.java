package de.kuro.kuronomics.estate;

import de.kuro.kuronomics.api.EstateApi;
import de.kuro.kuronomics.economy.*;
import de.kuro.kuronomics.economy.data.EconomyLedger;
import java.util.*;
import net.minecraft.server.MinecraftServer;

public final class PublicEstateApi implements EstateApi {
    private final MinecraftServer server;
    public PublicEstateApi(MinecraftServer server) { this.server = Objects.requireNonNull(server); ApiAccess.thread(server); }
    private EconomyLedger ledger() { ApiAccess.thread(server); return EconomyLedger.get(server); }
    @Override public Optional<Estate> find(String propertyId) { return Optional.ofNullable(ledger().estateByKey(propertyId)); }
    @Override public List<Estate> ownedBy(EconomicOwner owner) { return ledger().estatesOwned(owner); }
    @Override public boolean owns(EconomicOwner owner, String propertyId) { return find(propertyId).filter(e -> e.economicOwner().equals(owner)).isPresent(); }
    @Override public boolean integrationAvailable() { ApiAccess.thread(server); return new PropertyService(server).integrationAvailable(); }
    @Override public boolean canPurchase(net.minecraft.server.level.ServerPlayer actor, String propertyId) {
        ApiAccess.actor(server, actor);
        return find(propertyId).filter(e -> new EstateService(server).canSuggest(actor, e, EstateService.SuggestionUse.BUY)).isPresent();
    }
    @Override public boolean canManage(net.minecraft.server.level.ServerPlayer actor, String propertyId) {
        ApiAccess.actor(server, actor);
        return find(propertyId).filter(e -> new EstateService(server).canSuggest(actor, e, EstateService.SuggestionUse.OWNER_INFO)).isPresent();
    }
}
