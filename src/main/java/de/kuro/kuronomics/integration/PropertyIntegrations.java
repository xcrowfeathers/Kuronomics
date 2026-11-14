package de.kuro.kuronomics.integration;

import net.neoforged.fml.ModList;

/** Only load the WorldGuard bridge once the server has confirmed the mod is installed. */
public final class PropertyIntegrations {
    private static volatile PropertyIntegration instance;
    private PropertyIntegrations() {}

    public static PropertyIntegration get() {
        PropertyIntegration current = instance;
        if (current != null) return current;
        synchronized (PropertyIntegrations.class) {
            if (instance == null) instance = create();
            return instance;
        }
    }

    private static PropertyIntegration create() {
        if (!ModList.get().isLoaded("worldguard") || !ModList.get().isLoaded("worldedit")) return new UnavailablePropertyIntegration();
        try {
            Class<?> bridge = Class.forName("de.kuro.kuronomics.integration.worldguard.WorldGuardIntegration", true,
                    PropertyIntegrations.class.getClassLoader());
            PropertyIntegration integration = (PropertyIntegration) bridge.getDeclaredConstructor().newInstance();
            return integration;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException error) {
            com.mojang.logging.LogUtils.getLogger().error("WorldGuard integration unavailable; economy remains enabled", error);
            return new UnavailablePropertyIntegration();
        }
    }
}
