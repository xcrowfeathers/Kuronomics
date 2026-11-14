package de.kuro.kuronomics.jobs;

import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.block.JobTerminalBlockEntity;
import de.kuro.kuronomics.menu.JobMenu;
import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;

/** Keep applications outside terminal chunks. Only change them on the server thread. */
public final class JobApplications extends SavedData {
    public record Application(UUID id, UUID applicant, String applicantName, String jobId,
                              String jobName, String origin, String text, long submittedAt) {}
    private final Map<UUID, Application> pending = new LinkedHashMap<>();
    private final Map<UUID, Set<String>> byApplicant = new HashMap<>();
    private long revision;
    public long revision() { return revision; }
    public static JobApplications get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Applications accessed off server thread");
        return server.overworld().getDataStorage().computeIfAbsent(
                new Factory<>(JobApplications::new, JobApplications::load), Kuronomics.MODID + "_job_applications");
    }
    public static JobApplications load(CompoundTag tag, HolderLookup.Provider registries) {
        JobApplications store = new JobApplications();
        for (Tag raw : tag.getList("Pending", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) raw;
            try {
                Application a = new Application(row.getUUID("Id"), row.getUUID("Applicant"), row.getString("ApplicantName"),
                        row.getString("JobId"), row.getString("JobName"), row.getString("Origin"),
                        row.getString("Text"), row.getLong("SubmittedAt"));
                store.pending.put(a.id(), a);
                store.byApplicant.computeIfAbsent(a.applicant(), ignored -> new HashSet<>()).add(a.jobId());
            } catch (RuntimeException invalid) { throw new IllegalStateException("Invalid saved job application", invalid); }
        }
        return store;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Application a : pending.values()) {
            CompoundTag row = new CompoundTag(); row.putUUID("Id", a.id()); row.putUUID("Applicant", a.applicant());
            row.putString("ApplicantName", a.applicantName()); row.putString("JobId", a.jobId());
            row.putString("JobName", a.jobName()); row.putString("Origin", a.origin());
            row.putString("Text", a.text()); row.putLong("SubmittedAt", a.submittedAt()); list.add(row);
        }
        tag.put("Pending", list); return tag;
    }
    public List<Application> pending() { return List.copyOf(pending.values()); }
    public Application find(UUID id) { return pending.get(id); }
    public boolean hasPending(UUID applicant, String jobId) {
        return byApplicant.getOrDefault(applicant, Set.of()).contains(jobId);
    }
    public Application submit(ServerPlayer player, JobTerminalBlockEntity terminal, String jobId, String text) {
        text = text == null ? "" : text.strip();
        if (text.length() < 20 || text.length() > 2000) throw new IllegalArgumentException("Application must be 20–2000 characters.");
        int range = de.kuro.kuronomics.event.EconomyEvents.config().jobTerminalRange();
        var pos = terminal.getBlockPos();
        if (!terminal.isValidTerminal(player.serverLevel()) || player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) > (double) range * range)
            throw new IllegalArgumentException("Job Terminal is no longer nearby.");
        JobTerminalBlockEntity.Offer offer = terminal.offer(jobId);
        if (offer == null || offer.mode() != JobTerminalBlockEntity.Availability.APPLICATION_REQUIRED)
            throw new IllegalArgumentException("This job no longer requires an application.");
        JobService service = JobService.get(player.server);
        JobRegistry.Job job = service.registry().jobs().get(jobId);
        if (job == null) throw new IllegalArgumentException("Job definition is unavailable.");
        service.requireOpen(jobId);
        service.requireUnemployed(player.getUUID());
        if (jobId.length() > 512 || job.name().length() > 2048)
            throw new IllegalArgumentException("Job name or ID exceeds the application limit.");
        if (player.isSpectator() || de.kuro.kuronomics.economy.data.EconomyLedger.get(player.server).account(player.getUUID()).suspended())
            throw new IllegalArgumentException("You are not eligible for this job.");
        if (de.kuro.kuronomics.economy.data.EconomyLedger.get(player.server).account(player.getUUID()).job().equals(jobId))
            throw new IllegalArgumentException("You already hold this job.");
        if (hasPending(player.getUUID(), jobId)) throw new IllegalArgumentException("You already have a pending application for this job.");
        Application a = new Application(UUID.randomUUID(), player.getUUID(), player.getGameProfile().getName(),
                jobId, job.name(), player.level().dimension().location() + ":" + terminal.getBlockPos().asLong(),
                text, System.currentTimeMillis());
        pending.put(a.id(), a); byApplicant.computeIfAbsent(a.applicant(), ignored -> new HashSet<>()).add(jobId);
        changed(player.server);
        de.kuro.kuronomics.economy.FinancialPersistence.save(player.server);
        return a;
    }
    private void remove(Application a, MinecraftServer server) {
        pending.remove(a.id()); Set<String> jobs = byApplicant.get(a.applicant());
        if (jobs != null) { jobs.remove(a.jobId()); if (jobs.isEmpty()) byApplicant.remove(a.applicant()); }
        changed(server);
    }
    private void changed(MinecraftServer server) { revision++; setDirty(); JobMenu.refreshApplications(server); }
    public boolean reject(MinecraftServer server, UUID id) {
        Application a = pending.get(id); if (a == null) return false;
        remove(a, server); ServerPlayer player = server.getPlayerList().getPlayer(a.applicant());
        de.kuro.kuronomics.economy.FinancialPersistence.save(server);
        if (player != null) player.sendSystemMessage(net.minecraft.network.chat.Component.translatable("kuronomics.jobs.application.rejected", a.jobName()));
        return true;
    }
    public boolean accept(MinecraftServer server, UUID id) {
        Application a = pending.get(id); if (a == null) return false;
        JobService jobs = JobService.get(server);
        if (!jobs.registry().jobs().containsKey(a.jobId())) { remove(a, server); throw new IllegalArgumentException("Job definition is unavailable; application invalidated."); }
        jobs.requireOpen(a.jobId());
        var account = de.kuro.kuronomics.economy.data.EconomyLedger.get(server).account(a.applicant());
        if (account.suspended()) throw new IllegalArgumentException("Applicant is not eligible.");
        if (account.job().equals(a.jobId())) { remove(a, server); throw new IllegalArgumentException("Applicant already holds this job."); }
        // assign checks the slot cap just before committing. Admin requests run one at a time on the server
        // thread.
        jobs.start(a.applicant(), a.jobId());
        remove(a, server);
        // Save employment before application state. A crash can leave an application to clean up,
        // but can't remove it before the job assignment is saved.
        de.kuro.kuronomics.economy.FinancialPersistence.save(server);
        return true;
    }
    public void invalidateMissing(MinecraftServer server, Set<String> activeJobs) {
        boolean removed = false;
        for (Application a : List.copyOf(pending.values())) if (!activeJobs.contains(a.jobId())) {
            pending.remove(a.id()); Set<String> jobs = byApplicant.get(a.applicant());
            if (jobs != null) { jobs.remove(a.jobId()); if (jobs.isEmpty()) byApplicant.remove(a.applicant()); }
            removed = true;
        }
        if (removed) changed(server);
    }
}
