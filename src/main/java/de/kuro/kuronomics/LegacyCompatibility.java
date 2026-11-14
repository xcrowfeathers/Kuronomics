package de.kuro.kuronomics;

import net.minecraft.resources.ResourceLocation;

/** Old IDs are only here so worlds from before the rename still load. */
public final class LegacyCompatibility {
    public static final String MOD_ID = "kuronomy";
    public static final String LEDGER = MOD_ID + "_economy";
    public static final String PLAYER_ATTACHMENT = MOD_ID + ":player_variables";

    private LegacyCompatibility() {}

    public static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(MOD_ID, path); }
}
