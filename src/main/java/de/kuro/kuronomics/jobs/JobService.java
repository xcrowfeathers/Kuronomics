package de.kuro.kuronomics.jobs;

import java.util.*;
import de.kuro.kuronomics.economy.*;
import de.kuro.kuronomics.economy.data.*;
import de.kuro.kuronomics.integration.RegionProtectionService.Capability;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/** Run all job operations on the server thread. */
public final class JobService {
    private final MinecraftServer server;
    private final EconomyLedger ledger;
    private final WorkZones zones;
    private long spatialChecks;
    public long spatialChecks() { return spatialChecks; }
    private final Map<String, Integer> occupied = new HashMap<>();
    private boolean occupancyReady;
    private JobRegistry baseRegistry = JobRegistry.EMPTY;
    private boolean definitionsInitialized;
    private long registryRevision, occupancyRevision;
    public long registryRevision() { return registryRevision; }
    public long occupancyRevision() { return occupancyRevision; }
    public int occupancy(String id) { return Math.max(0, occupied.getOrDefault(id, 0)); }
    private static final Map<MinecraftServer, JobService> SERVICES = new WeakHashMap<>();
    private JobService(MinecraftServer server) { this.server = server; ledger = EconomyLedger.get(server); zones = WorkZones.get(server); }
    public static JobService get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Jobs accessed off server thread");
        return SERVICES.computeIfAbsent(server, JobService::new);
    }
    public static void stop(MinecraftServer server) { SERVICES.remove(server); JobActions.clear(); JobTaskProgress.stop(server); }
    public void reload() { install(JobsConfig.load(server)); }
    /** Rename the old ID once, keeping earned money and task counters. */
    void migrateMinerExample() {
        if(!occupancyReady)rebuildOccupancy();
        if(occupancy("miner_example")==0)return;
        Map<UUID,Account> updates = new HashMap<>();
        ledger.employmentAccounts().forEach((id,a)->{if(a.job().equals("miner_example"))updates.put(id,new Account(a.balance(),a.pendingIncome(),a.playTicks(),a.paydaySequence(),"miner",a.entry(),a.suspended(),a.suspensionReason(),a.jobEarnings(),a.taskEarnings()));});
        if(!updates.isEmpty()) {ledger.commitTransaction(UUID.randomUUID(),"rename-task-miner",updates,List.of(),List.of(),Set.of());rebuildOccupancy();}
    }
    public void install(JobRegistry registry) {
        if (!server.isSameThread()) throw new IllegalStateException("Job definitions accessed off server thread");
        JobRegistry merged;
        try { merged = JobTaskLoader.compile(server, registry); }
        catch (RuntimeException invalid) {
            // A missing addon shouldn't disable otherwise valid TOML jobs on first startup.
            if (!definitionsInitialized) { publish(registry); baseRegistry = registry; definitionsInitialized = true; }
            throw invalid;
        }
        publish(merged); baseRegistry = registry;
        definitionsInitialized = true;
    }
    /** Only call on the server thread after resources reload successfully and tags are ready. */
    public void refreshDatapacks() { publish(JobTaskLoader.compile(server, baseRegistry)); }
    private void publish(JobRegistry registry) {
        if (!server.isSameThread()) throw new IllegalStateException("Job definitions accessed off server thread");
        if (!occupancyReady) rebuildOccupancy();
        for (String office : List.of("president", "central_bank_governor")) if (occupancy(office) > 1)
            throw new IllegalArgumentException("Multiple holders of " + office + "; resolve duplicate assignments before loading job authority.");
        // Keep tracking old natural-only targets until the server stops. Otherwise a player could remove a
        // pack,
        // place ore, re-enable the pack and mine it for rewards.
        if (!registry.naturalBlocks().containsAll(ledger.jobs().naturalBlocks())) {
            var watched = new HashSet<>(ledger.jobs().naturalBlocks()); watched.addAll(registry.naturalBlocks());
            registry = new JobRegistry(registry.jobs(), watched, registry.tasks());
        }
        ledger.jobs(registry); registryRevision++;
        TaskExtensions.API.writeReference(registry);
        de.kuro.kuronomics.menu.CentralBankMenu.refreshAll(server);
        JobApplications.get(server).invalidateMissing(server, registry.jobs().keySet());
        de.kuro.kuronomics.menu.JobMenu.refreshAll(server);
        if (!de.kuro.kuronomics.integration.PropertyIntegrations.get().available() && registry.jobs().values().stream().anyMatch(j -> !j.zones().isEmpty()))
            com.mojang.logging.LogUtils.getLogger().warn(WorkZones.UNAVAILABLE);
    }
    public JobRegistry registry() { return ledger.jobs(); }
    public void rebuildOccupancy() {
        occupied.clear();
        ledger.employmentAccounts().values().forEach(a -> occupied.merge(a.job(), 1, Integer::sum));
        occupancyReady = true; occupancyRevision++;
    }
    public void imported(UUID playerId, Account account) {
        if (!occupancyReady) rebuildOccupancy();
        else { occupied.merge(account.job(), 1, Integer::sum); occupancyRevision++; }
        de.kuro.kuronomics.menu.JobMenu.refreshEmployment(server, playerId, "unemployed", account.job());
    }
    /** Use the same server-thread assignment path for online players, offline players and admin changes. */
    public boolean assign(UUID playerId, String id) {
        return assign(playerId, id, false);
    }
    public boolean assignNotified(UUID playerId,String id,String notice){return assign(playerId,id,true,notice);}
    private boolean assign(UUID playerId,String id,boolean preserveEarned){return assign(playerId,id,preserveEarned,null);}
    private boolean assign(UUID playerId, String id, boolean preserveEarned,String notice) {
        if (!server.isSameThread()) throw new IllegalStateException("Jobs accessed off server thread");
        if (!occupancyReady) rebuildOccupancy();
        var job = registry().jobs().get(id);
        if (job == null && !id.equals("unemployed")) throw new IllegalArgumentException("Unknown job '" + id + "'");
        Account old = ledger.account(playerId);
        if (old.job().equals(id)) return false;
        if (old.jobEarnings() != 0 && !preserveEarned) throw new IllegalArgumentException("Finish PayDay earnings before changing jobs.");
        String lockPrefix = "kuronomics:appointed-pay:" + old.paydaySequence() + ":";
        if (old.taskEarnings().containsKey(lockPrefix + id))
            throw new IllegalArgumentException("Collect your next personal PayDay before returning to this job.");
        if (job != null && occupancy(id) >= job.slots()) throw new IllegalArgumentException("Job is full.");
        Map<String, Long> settledJobs = new HashMap<>();
        old.taskEarnings().forEach((key, value) -> { if (key.startsWith(lockPrefix)) settledJobs.put(key, value); });
        if (old.jobEarnings() != 0) settledJobs.put(lockPrefix + old.job(), old.jobEarnings());
        Account replacement = old.job(id).earnings(0, settledJobs);
        if (old.jobEarnings() != 0) replacement = replacement.pending(Math.addExact(old.pendingIncome(), old.jobEarnings()));
        // Changing job info mustn't pay out or erase shop receipts or already-taxed credits.
        UUID reference = UUID.randomUUID(); String fingerprint = "job-change|" + playerId + "|" + old.job() + "|" + id;
        if (old.jobEarnings() == 0) ledger.commitTransaction(reference, fingerprint, Map.of(playerId, replacement), List.of(), List.of(), Set.of());
        else {
            var updates = EconomyLedger.personalUpdates(Map.of(playerId, replacement));
            StateTreasury.stage(ledger, updates, Math.negateExact(old.jobEarnings()));
            ledger.commitOwners(reference, fingerprint, updates, List.of(new Receipt(playerId, "Earned job pay: " + old.job(), old.jobEarnings())), ledger.companies(), Map.of());
        }
        occupied.merge(old.job(), -1, Integer::sum);
        ledger.notifyAfterCommit(new de.kuro.kuronomics.api.event.JobChangedEvent(playerId, reference, old.job(), id));
        occupied.merge(id, 1, Integer::sum);
        occupancyRevision++;
        de.kuro.kuronomics.menu.CentralBankMenu.employmentChanged(server, playerId);
        de.kuro.kuronomics.menu.JobMenu.refreshEmployment(server, playerId, old.job(), id);
        var player = server.getPlayerList().getPlayer(playerId);
        if (player == null) de.kuro.kuronomics.mailbox.MailboxStore.get(server).jobNotice(playerId, true);
        else if(notice==null)notifyJob(player);else{player.sendSystemMessage(de.kuro.kuronomics.util.ModText.legacy(notice));de.kuro.kuronomics.mailbox.MailboxStore.get(server).jobNotice(playerId,false);}
        return true;
    }
    private void notifyJob(ServerPlayer player) {
        Account account = ledger.account(player.getUUID());
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(account.job().equals("unemployed")
                ? "You no longer have a job." : "You are now working as " + displayName(account) + ".")
                .withStyle(account.job().equals("unemployed") ? net.minecraft.ChatFormatting.YELLOW : net.minecraft.ChatFormatting.GREEN));
        de.kuro.kuronomics.mailbox.MailboxStore.get(server).jobNotice(player.getUUID(), false);
    }
    public void notifyOnLogin(ServerPlayer player) {
        if (de.kuro.kuronomics.mailbox.MailboxStore.get(server).hasJobNotice(player.getUUID())) {
            notifyJob(player); FinancialPersistence.save(server);
        }
    }
    public void requireUnemployed(UUID player) {
        if (!ledger.account(player).job().equals("unemployed")) throw new IllegalArgumentException("Quit your current job first with /jobs quit.");
    }
    public boolean start(UUID player, String id) {
        requireUnemployed(player); requireOpen(id); return assign(player, id);
    }
    public void quit(ServerPlayer player) {
        if (ledger.account(player.getUUID()).job().equals("unemployed")) throw new IllegalArgumentException("You don't have a job to quit.");
        JobDelivery.recover(player); assign(player.getUUID(), "unemployed", true); FinancialPersistence.save(server);
    }
    public void assign(Collection<ServerPlayer> players, String id) {
        if (!occupancyReady) rebuildOccupancy();
        for (var player : players) JobDelivery.recover(player);
        var job = registry().jobs().get(id);
        if (job == null && !id.equals("unemployed")) throw new IllegalArgumentException("Unknown job '" + id + "'");
        long joining = players.stream().filter(p -> !ledger.account(p.getUUID()).job().equals(id)).count();
        if (job != null && joining > job.slots() - occupied.getOrDefault(id, 0)) throw new IllegalArgumentException("No free slots for " + job.name());
        for (ServerPlayer p : players) {
            Account old = ledger.account(p.getUUID()); if (old.job().equals(id)) continue;
            if (old.taskEarnings().containsKey("kuronomics:appointed-pay:" + old.paydaySequence() + ":" + id))
                throw new IllegalArgumentException("Collect the next personal PayDay before returning to this job.");
        }
        boolean changed = false;
        for (ServerPlayer p : players) changed |= assign(p.getUUID(), id, true);
        if (changed) FinancialPersistence.save(server);
    }
    public JobRegistry.Job employment(ServerPlayer player) {
        Account account = ledger.employment(player.getUUID());
        return account == null || account.suspended() ? null : registry().jobs().get(account.job());
    }
    public void requireOpen(String id) {
        var job = registry().jobs().get(id);
        if (job == null || job.authority().appointed()) throw new IllegalArgumentException("This position needs an appointment.");
    }
    public void requireAppointment(UUID actor, UUID target, String next) {
        Account from = ledger.account(actor), current = ledger.account(target);
        var authority = registry().jobs().get(from.job());
        if ((from.job().equals("president") || from.job().equals("central_bank_governor")) && occupancy(from.job()) != 1)
            throw new IllegalArgumentException("This office has conflicting assignments. Ask an administrator to resolve them.");
        if (from.suspended() || authority == null || authority.authority().appointments() == JobRegistry.Appointments.NONE)
            throw new IllegalArgumentException("Your job cannot appoint or dismiss people.");
        for (String id : List.of(current.job(), next)) {
            if (id.equals("unemployed")) continue;
            var job = registry().jobs().get(id);
            if (job == null ? authority.authority().appointments() != JobRegistry.Appointments.ALL
                    : !authority.authority().canManage(job.authority()))
                throw new IllegalArgumentException("That position is outside your appointment authority.");
        }
        if (!next.equals("unemployed") && !registry().jobs().containsKey(next)) throw new IllegalArgumentException("Unknown job.");
    }
    public boolean appoint(UUID actor, UUID target, String id) {
        requireAppointment(actor, target, id);
        return assign(target, id, true);
    }
    public boolean relevant(ServerPlayer player, String type, Object target) {
        var job = employment(player);
        return job != null && job.mode() == JobRegistry.Mode.TASK_BASED && !job.rules(type, target).isEmpty();
    }
    private boolean location(JobRegistry.Job job, ServerPlayer player, BlockPos pos) {
        return location(job, player, pos, zone -> de.kuro.kuronomics.integration.PropertyIntegrations.get()
                .containsRegion(player.serverLevel(), zone.region(), zone.binding(), pos));
    }
    private boolean location(JobRegistry.Job job, ServerPlayer player, BlockPos pos, java.util.function.Predicate<WorkZones.Zone> contains) {
        spatialChecks++;
        if (!job.dimensions().isEmpty() && !job.dimensions().contains(player.level().dimension().location())) return false;
        if (!job.biomes().isEmpty() && !job.biomes().contains(player.serverLevel().getBiome(pos).unwrapKey().orElseThrow().location())) return false;
        return job.zones().isEmpty() || zones.matches(player.serverLevel(), job.zones(), contains);
    }
    public boolean workAccess(ServerPlayer player, BlockPos pos, Capability capability) {
        var job = employment(player);
        return job != null && !job.zones().isEmpty() && job.capabilities().contains(capability)
                && workTarget(job, player, pos, capability, null) && location(job, player, pos);
    }
    public boolean workAccess(ServerPlayer player, BlockPos pos, Capability capability, java.util.function.Predicate<WorkZones.Zone> contains) {
        return workAccess(player, pos, capability, null, contains);
    }
    public boolean workAccess(ServerPlayer player, BlockPos pos, Capability capability, net.minecraft.world.entity.EntityType<?> target,
                              java.util.function.Predicate<WorkZones.Zone> contains) {
        var job = employment(player);
        return job != null && !job.zones().isEmpty() && job.capabilities().contains(capability)
                && workTarget(job, player, pos, capability, target)
                && location(job, player, pos, contains);
    }
    private boolean workTarget(JobRegistry.Job job, ServerPlayer player, BlockPos pos, Capability capability, net.minecraft.world.entity.EntityType<?> target) {
        // Construction/mining tasks keep their broader work permissions. Crop tasks only allow
        // the configured plants, not building or breaking anything else.
        switch (capability) {
            case BREAK -> { if (job.index().containsKey("break_block")) return true; }
            case PLACE -> { if (job.index().containsKey("place_block")) return true; }
            case INTERACT -> { if (job.index().containsKey("craft_item") || job.index().containsKey("fish_item")) return true; }
            case CONTAINER -> { return true; }
            case ENTITY_DAMAGE -> { return target != null && !job.rules("kill_entity", target).isEmpty(); }
            case ENTITY_INTERACT -> { return target != null && (!job.rules("feed_animal", target).isEmpty() || !job.rules("breed_animal", target).isEmpty()
                    || !job.rules("shear_entity", target).isEmpty() || !job.rules("tame_entity", target).isEmpty()); }
        }
        if (!player.serverLevel().hasChunkAt(pos)) return false;
        var state = player.serverLevel().getBlockState(pos);
        return switch (capability) {
            case BREAK -> JobActions.mature(state) && !job.rules("harvest_crop", state.getBlock()).isEmpty();
            case PLACE -> !job.rules("plant_crop", state.getBlock()).isEmpty();
            case INTERACT -> !job.rules("plant_crop", state.getBlock()).isEmpty() || JobActions.mature(state) && !job.rules("harvest_crop", state.getBlock()).isEmpty();
            default -> false;
        };
    }
    public long completed(ServerPlayer player, String type, Object target, BlockPos pos, ItemStack tool, int count, boolean natural) {
        Account after = preview(player, type, target, pos, tool, count, natural);
        if (after == null) return 0;
        Account before = ledger.account(player.getUUID());
        long previous = before.jobEarnings();
        ledger.taskEarnings(player.getUUID(), after);
        taskRecorded(player, type, target, count, null, before, after);
        return after.jobEarnings() - previous;
    }
    public de.kuro.kuronomics.api.JobTaskApi.Completion completeCustom(net.minecraft.resources.ResourceLocation type, UUID occurrence,
            net.minecraft.resources.ResourceLocation target, de.kuro.kuronomics.api.JobTaskType.Trigger<?> trigger) {
        var player = trigger.player();
        de.kuro.kuronomics.economy.FinancialWriter.check(server);
        UUID reference = EconomyService.stableReference("job-task-action", type + ":" + player.getUUID() + ":" + occurrence);
        String fingerprint = "job-task-action|" + type + "|" + player.getUUID() + "|" + target + "|" + trigger.count()
                + "|" + player.level().dimension().location() + "|" + trigger.position().asLong();
        if (ledger.transactionRecorded(reference, fingerprint)) return new de.kuro.kuronomics.api.JobTaskApi.Completion(de.kuro.kuronomics.api.JobTaskApi.Status.DUPLICATE, 0);
        if (!relevant(player, type.toString(), target)) return new de.kuro.kuronomics.api.JobTaskApi.Completion(de.kuro.kuronomics.api.JobTaskApi.Status.NO_REWARD, 0);
        Account after = preview(player, type.toString(), target, trigger.position(), player.getMainHandItem().copy(), trigger.count(), true, trigger);
        if (after == null) return new de.kuro.kuronomics.api.JobTaskApi.Completion(de.kuro.kuronomics.api.JobTaskApi.Status.NO_REWARD, 0);
        long gain = Math.subtractExact(after.jobEarnings(), ledger.account(player.getUUID()).jobEarnings());
        Account before = ledger.account(player.getUUID());
        ledger.commitTransaction(reference, fingerprint, Map.of(player.getUUID(), after), List.of(), List.of(), Set.of());
        taskRecorded(player, type.toString(), target, trigger.count(), reference, before, after);
        return new de.kuro.kuronomics.api.JobTaskApi.Completion(de.kuro.kuronomics.api.JobTaskApi.Status.REWARDED, gain);
    }
    public Account preview(ServerPlayer player, String type, Object target, BlockPos pos, ItemStack tool, int count, boolean natural) {
        return preview(player, type, target, pos, tool, count, natural, null);
    }
    private void taskRecorded(ServerPlayer player, String type, Object target, int count, UUID reference, Account before, Account after) {
        JobTaskProgress.record(player,before,after);
        var rewards = new ArrayList<de.kuro.kuronomics.api.event.JobTaskCompletedEvent.Reward>();
        for (var rule : registry().jobs().get(before.job()).rules(type, target)) {
            long gain = after.taskEarnings().getOrDefault(rule.id(), 0L) - before.taskEarnings().getOrDefault(rule.id(), 0L);
            if (gain > 0) rewards.add(new de.kuro.kuronomics.api.event.JobTaskCompletedEvent.Reward(rule.id(), gain));
        }
        var typeId = type.contains(":") ? net.minecraft.resources.ResourceLocation.parse(type) : net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("kuronomics", type);
        EconomicEventQueue.accrual(new de.kuro.kuronomics.api.event.JobTaskCompletedEvent(player.getUUID(), reference, before.job(), typeId, count, rewards));
    }
    private Account preview(ServerPlayer player, String type, Object target, BlockPos pos, ItemStack tool, int count, boolean natural,
                            de.kuro.kuronomics.api.JobTaskType.Trigger<?> trigger) {
        if (player.isCreative() || player.isSpectator() || count <= 0) return null;
        var job = employment(player); if (job == null || job.mode() != JobRegistry.Mode.TASK_BASED) return null;
        var rules = job.rules(type, target); if (rules.isEmpty()) return null;
        JobDelivery.recover(player);
        Account account = ledger.account(player.getUUID());
        long limit = Math.min(job.max() - job.base(), job.gainCap());
        if (account.jobEarnings() >= limit) return null;
        long earnings = account.jobEarnings(); var counters = new HashMap<>(account.taskEarnings());
        for (var rule : rules) {
            if (rule.natural() && !natural || !rule.tools().isEmpty() && !rule.tools().contains(tool.getItem())) continue;
            long used = counters.getOrDefault(rule.id(), 0L);
            long room = Math.min(limit - earnings, Math.max(0, rule.cap() - used));
            if (room > 0 && rule.condition() != null && !rule.condition().test(player)) continue;
            if (room <= 0 || rule.matcher() != null && (trigger == null || !rule.matcher().test(trigger))) continue;
            long gain = rule.gain() > room / count ? room : rule.gain() * count;
            if (gain <= 0) continue;
            earnings += gain; counters.put(rule.id(), Math.addExact(used, gain));
        }
        return earnings == account.jobEarnings() || !location(job, player, pos) ? null : account.earnings(earnings, counters);
    }
    public String describe(Account a) {
        var j = registry().jobs().get(a.job()); if (j == null) return a.job().equals("unemployed") ? "Unemployed" : a.job() + " (definition unavailable)";
        return j.name() + " | " + j.mode() + " | Next PayDay: " + Money.format(j.salary(a))
                + (j.mode() == JobRegistry.Mode.STATIC ? " | Fixed: " + Money.format(j.base()) : " | Base: " + Money.format(j.base()) + " | Max: " + Money.format(j.max()))
                + (j.zones().isEmpty() ? "" : " | WorkZones: " + String.join(", ", j.zones()));
    }
    public String displayName(Account account) {
        var job = registry().jobs().get(account.job());
        return job == null ? account.job().equals("unemployed") ? "Unemployed" : account.job() : job.name();
    }
    public net.minecraft.network.chat.Component summary(Account account) {
        var job = registry().jobs().get(account.job());
        if (job == null) return net.minecraft.network.chat.Component.translatable("kuronomics.jobs.unavailable", account.job().equals("unemployed") ? "—" : account.job());
        var text = net.minecraft.network.chat.Component.translatable("kuronomics.jobs.summary", job.name(),
                net.minecraft.network.chat.Component.translatable("kuronomics.jobs.mode." + job.mode().name().toLowerCase(Locale.ROOT)), Money.format(job.salary(account)));
        text.append(net.minecraft.network.chat.Component.translatable(job.mode() == JobRegistry.Mode.STATIC ? "kuronomics.jobs.fixed" : "kuronomics.jobs.range", Money.format(job.base()), Money.format(job.max())));
        if (!job.zones().isEmpty()) text.append(net.minecraft.network.chat.Component.translatable("kuronomics.jobs.zones", String.join(", ", job.zones())));
        return text;
    }
}
