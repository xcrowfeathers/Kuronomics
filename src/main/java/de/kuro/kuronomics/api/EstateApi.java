package de.kuro.kuronomics.api;

import de.kuro.kuronomics.economy.EconomicOwner;
import de.kuro.kuronomics.estate.Estate;
import java.util.*;

/** This checks economic ownership, not WorldGuard owners or members. */
public interface EstateApi {
    Optional<Estate> find(String propertyId);
    List<Estate> ownedBy(EconomicOwner owner);
    boolean owns(EconomicOwner owner, String propertyId);
    boolean integrationAvailable();
    /** This is only an eligibility check. Buying still needs to go through the recoverable purchase flow. */
    boolean canPurchase(net.minecraft.server.level.ServerPlayer actor, String propertyId);
    boolean canManage(net.minecraft.server.level.ServerPlayer actor, String propertyId);
}
