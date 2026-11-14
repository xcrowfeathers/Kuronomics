package de.kuro.kuronomics;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;

@Mod(Kuronomics.MODID)
public final class Kuronomics {

    public static final String MODID = "kuronomics";

    public Kuronomics(IEventBus modEventBus, ModContainer container) {
        de.kuro.kuronomics.registry.ModContent.register(modEventBus);
        de.kuro.kuronomics.registry.BankerContent.register(modEventBus);
        modEventBus.addListener(de.kuro.kuronomics.network.MachineAction::register);
        modEventBus.addListener(de.kuro.kuronomics.network.ServerShopAction::register);
        modEventBus.addListener(de.kuro.kuronomics.network.AuctionAction::register);
        modEventBus.addListener(de.kuro.kuronomics.network.RealEstateAction::register);
        modEventBus.addListener(de.kuro.kuronomics.network.JobAction::register);
        modEventBus.addListener(de.kuro.kuronomics.network.MarketAction::register);
        modEventBus.addListener(de.kuro.kuronomics.network.BankAction::register);
        modEventBus.addListener(de.kuro.kuronomics.registry.InventoryCapabilities::register);
    }
}
