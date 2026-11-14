package de.kuro.kuronomics.jobs;

import de.kuro.kuronomics.api.JobApi;
import de.kuro.kuronomics.economy.ApiAccess;
import de.kuro.kuronomics.economy.data.EconomyLedger;
import java.util.*;
import net.minecraft.server.MinecraftServer;

public final class PublicJobApi implements JobApi {
    private final MinecraftServer server;
    public PublicJobApi(MinecraftServer server) { this.server = Objects.requireNonNull(server); ApiAccess.thread(server); }
    private JobService service() { ApiAccess.thread(server); return JobService.get(server); }
    @Override public boolean canWork(net.minecraft.server.level.ServerPlayer player, net.minecraft.core.BlockPos position,
                                     WorkCapability capability, net.minecraft.world.entity.EntityType<?> target) {
        ApiAccess.thread(server);
        if (player.server != server) throw new IllegalArgumentException("Player belongs to another server");
        return service().workAccess(player, position, de.kuro.kuronomics.integration.RegionProtectionService.Capability.valueOf(capability.name()), target,
                zone -> de.kuro.kuronomics.integration.PropertyIntegrations.get().containsRegion(player.serverLevel(), zone.region(), zone.binding(), position));
    }
    private Job view(JobRegistry.Job job) {
        return new Job(job.id(), job.name(), job.mode().name(), job.base(), job.max(), job.gainCap(), job.slots(), job.authority().appointed());
    }
    @Override public Collection<Job> registered() { return service().registry().jobs().values().stream().map(this::view).toList(); }
    @Override public Optional<Job> find(String id) { return Optional.ofNullable(service().registry().jobs().get(id)).map(this::view); }
    @Override public Optional<Employment> employment(UUID player) {
        var service = service(); var account = EconomyLedger.get(server).employment(player);
        if (account == null) return Optional.empty();
        var job = service.registry().jobs().get(account.job());
        return job == null ? Optional.empty() : Optional.of(new Employment(view(job), account.jobEarnings(), job.salary(account), account.taskEarnings()));
    }
    @Override public boolean canStart(UUID player, String id) {
        var service = service(); var account = EconomyLedger.get(server).employment(player);
        var job = service.registry().jobs().get(id);
        return account != null && !account.suspended() && account.job().equals("unemployed") && job != null
                && !job.authority().appointed() && service.occupancy(id) < job.slots() && account.jobEarnings() == 0
                && !account.taskEarnings().containsKey("kuronomics:appointed-pay:" + account.paydaySequence() + ":" + id);
    }
}
