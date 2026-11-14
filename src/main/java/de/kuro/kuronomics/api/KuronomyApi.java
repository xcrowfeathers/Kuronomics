package de.kuro.kuronomics.api;

import net.minecraft.server.MinecraftServer;

/** API version 1. Call on the server thread after the world has loaded. Each server has its own adapters. */
public final class KuronomyApi {
    public static final int VERSION = 1;
    /** Queues follow-up work until the current event or settlement has finished. It won't run inline. */
    public static void defer(MinecraftServer server, Runnable action) {
        server.tell(new net.minecraft.server.TickTask(0, java.util.Objects.requireNonNull(action)));
    }
    public static JobTaskApi jobTasks() { return de.kuro.kuronomics.jobs.TaskExtensions.API; }
    public static EconomyApi economy(MinecraftServer server) { return new de.kuro.kuronomics.economy.PublicEconomyApi(server); }
    public static CompanyApi companies(MinecraftServer server) { return new de.kuro.kuronomics.company.PublicCompanyApi(server); }
    public static JobApi jobs(MinecraftServer server) { return new de.kuro.kuronomics.jobs.PublicJobApi(server); }
    public static EstateApi estates(MinecraftServer server) { return new de.kuro.kuronomics.estate.PublicEstateApi(server); }
    public static ContractApi contracts(MinecraftServer server) { return new de.kuro.kuronomics.company.PublicContractApi(server); }
    private KuronomyApi() {}
}
