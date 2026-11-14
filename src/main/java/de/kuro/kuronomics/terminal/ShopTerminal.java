package de.kuro.kuronomics.terminal;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public interface ShopTerminal {
    boolean isValidShopTerminal(ServerLevel level);
    default boolean canOpenShop(ServerPlayer player) { return true; }
    void openShop(ServerPlayer player);
}
