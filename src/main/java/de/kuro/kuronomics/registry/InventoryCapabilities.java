package de.kuro.kuronomics.registry;

import de.kuro.kuronomics.block.MachineKind;
import de.kuro.kuronomics.item.WalletStorage;
import net.neoforged.neoforge.capabilities.*;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;

public final class InventoryCapabilities {
    private InventoryCapabilities() {}
    public static void register(RegisterCapabilitiesEvent event) {
        event.registerItem(Capabilities.ItemHandler.ITEM, (stack, context) -> new WalletStorage(stack), ModContent.WALLET.get());
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModContent.MACHINE_ENTITIES.get(MachineKind.PRINTER).get(),
                (machine, side) -> side == null ? null : new SidedInvWrapper(machine, side));
    }
}
