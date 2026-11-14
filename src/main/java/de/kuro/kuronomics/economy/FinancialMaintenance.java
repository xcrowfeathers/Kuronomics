package de.kuro.kuronomics.economy;

import de.kuro.kuronomics.economy.data.*;
import de.kuro.kuronomics.event.EconomyEvents;
import net.minecraft.server.MinecraftServer;

/** Handle one capped store batch per tick. Stores under their limits skip the record scan. */
public final class FinancialMaintenance {
    public static void tick(MinecraftServer server) {
        int tick = server.getTickCount();
        var config = EconomyEvents.config(); int maximum = config.historyMaximum();
        long age = config.historyAgeMillis(), now = System.currentTimeMillis();
        switch (Math.floorMod(tick, 6)) {
            case 0 -> EconomyLedger.get(server).pruneHistory(maximum, age, now);
            case 1 -> CashTransactionStore.get(server).pruneHistory(maximum, age, now);
            case 2 -> PlayerShopTransactionStore.get(server).pruneHistory(maximum, age, now);
            case 3 -> de.kuro.kuronomics.servershop.ServerShopStore.get(server).pruneHistory(maximum, age, now);
            case 4 -> de.kuro.kuronomics.auction.AuctionHouseStore.get(server).pruneHistory(maximum, age, now);
            case 5 -> de.kuro.kuronomics.estate.market.RealEstateMarketStore.get(server).pruneHistory(maximum, age, now,
                    de.kuro.kuronomics.estate.PropertyLockStore.get(server).operationReferences());
        }
    }
    private FinancialMaintenance() {}
}
