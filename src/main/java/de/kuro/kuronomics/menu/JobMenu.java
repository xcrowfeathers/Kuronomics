package de.kuro.kuronomics.menu;

import de.kuro.kuronomics.block.JobTerminalBlockEntity;
import de.kuro.kuronomics.economy.data.EconomyLedger;
import de.kuro.kuronomics.event.EconomyEvents;
import de.kuro.kuronomics.jobs.*;
import de.kuro.kuronomics.network.*;
import de.kuro.kuronomics.registry.ModContent;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/** The server owns page/selection state. Send new snapshots when something changes, not every menu tick. */
public final class JobMenu extends AbstractContainerMenu {
    private static final int PAGE_SIZE = 4;
    private static final UUID NONE = new UUID(0, 0);
    private final Player viewer;
    private final JobTerminalBlockEntity terminal;
    private final int kind; // 0 = player, 1 = terminal editor, 2 = applications
    private final BlockPos pos;
    private boolean initialSyncPending = true;
    private String view, selectedJob = "", status = "";
    private UUID selectedApplication = NONE;
    private int page;
    public JobSnapshot state;

    public JobMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(id, inventory, buffer.readVarInt(), buffer.readBlockPos(), null);
    }
    private JobMenu(int id, Inventory inventory, int kind, BlockPos pos, JobTerminalBlockEntity terminal) {
        super(ModContent.JOB_MENU.get(), id);
        this.viewer = inventory.player; this.kind = kind; this.pos = pos; this.terminal = terminal;
        this.view = kind == 2 ? "applications" : kind == 1 ? "editor" : "catalog";
        state = new JobSnapshot(id, view, 0, 0, "", "None", "unemployed", 0, 0, 0, 0, List.of(), List.of(), "", NONE);
    }
    public static void open(ServerPlayer player, JobTerminalBlockEntity terminal, boolean editor) {
        if (editor && !JobPermissions.edit(player)) { player.sendSystemMessage(Component.translatable("kuronomics.jobs.permission")); return; }
        if (!nearTerminal(player, terminal.getBlockPos())) {
            player.sendSystemMessage(Component.literal("Move closer to the Job Terminal.")); return;
        }
        int kind = editor ? 1 : 0;
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new JobMenu(id, inv, kind, terminal.getBlockPos(), terminal),
                Component.translatable(editor ? "kuronomics.jobs.editor" : "kuronomics.jobs.terminal")),
                b -> { b.writeVarInt(kind); b.writeBlockPos(terminal.getBlockPos()); });
        if (player.containerMenu instanceof JobMenu menu) menu.sync(player);
    }
    public static void openApplications(ServerPlayer player) {
        if (!JobPermissions.manage(player)) { player.sendSystemMessage(Component.translatable("kuronomics.jobs.permission")); return; }
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new JobMenu(id, inv, 2, BlockPos.ZERO, null),
                Component.translatable("kuronomics.jobs.applications")),
                b -> { b.writeVarInt(2); b.writeBlockPos(BlockPos.ZERO); });
        if (player.containerMenu instanceof JobMenu menu) menu.sync(player);
    }
    public void apply(JobSnapshot snapshot) { state = snapshot; }
    public static void refreshAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            if (player.containerMenu instanceof JobMenu menu && menu.stillValid(player)) menu.sync(player);
    }
    public static void refreshEmployment(MinecraftServer server, UUID playerId, String oldJob, String newJob) {
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            if (player.containerMenu instanceof JobMenu menu && menu.kind != 2 && menu.stillValid(player)
                    && (player.getUUID().equals(playerId) || menu.terminal.offer(oldJob) != null || menu.terminal.offer(newJob) != null)) menu.sync(player);
    }
    public static void refreshApplications(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            if (player.containerMenu instanceof JobMenu menu && menu.kind == 2 && menu.stillValid(player)) menu.sync(player);
    }
    public static void refreshTerminal(MinecraftServer server, JobTerminalBlockEntity terminal) {
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            if (player.containerMenu instanceof JobMenu menu && menu.terminal == terminal && menu.stillValid(player)) menu.sync(player);
    }
    private void requireRevisions(JobAction action, JobService jobs, JobApplications applications) {
        if (action.registryRevision() != jobs.registryRevision()) throw new IllegalArgumentException("Jobs changed. Please review the updated offer.");
        if (kind != 2 && action.terminalRevision() != terminal.revision()) throw new IllegalArgumentException("Terminal offers changed. Please review again.");
        if (kind == 2 && action.applicationRevision() != applications.revision()) throw new IllegalArgumentException("Applications changed. Please review again.");
    }
    public void action(ServerPlayer player, JobAction action) {
        if (viewer != player || player.containerMenu != this || !stillValid(player)) return;
        JobService jobs = JobService.get(player.server);
        JobApplications applications = JobApplications.get(player.server);
        try {
            switch (action.action()) {
                case "back" -> { requireRevisions(action, jobs, applications); view = kind == 2 ? "applications" : kind == 1 ? "editor" : "catalog"; selectedJob = ""; selectedApplication = NONE; status = ""; }
                case "next" -> { requireRevisions(action, jobs, applications); page++; }
                case "previous" -> { requireRevisions(action, jobs, applications); page = Math.max(0, page - 1); }
                case "details" -> {
                    requireRevisions(action, jobs, applications);
                    if (kind == 2) {
                        JobApplications.Application a = applications.find(action.applicationId());
                        if (a == null) throw new IllegalArgumentException("Application is no longer pending.");
                        selectedApplication = a.id(); view = "application_details";
                    } else {
                        if (terminal.offer(action.jobId()) == null || !jobs.registry().jobs().containsKey(action.jobId()))
                            throw new IllegalArgumentException("Offer is no longer available.");
                        selectedJob = action.jobId(); view = kind == 1 ? "edit_details" : "details";
                    }
                    status = "";
                }
                case "apply_dialog" -> {
                    requireRevisions(action, jobs, applications); requireSelected("details", action.jobId());
                    jobs.requireUnemployed(player.getUUID());
                    if (terminal.offer(selectedJob).mode() != JobTerminalBlockEntity.Availability.APPLICATION_REQUIRED)
                        throw new IllegalArgumentException("This job is open without application.");
                    view = "apply"; status = "";
                }
                case "start" -> {
                    requireRevisions(action, jobs, applications); requireSelected("details", action.jobId());
                    jobs.requireOpen(selectedJob);
                    if (terminal.offer(selectedJob).mode() != JobTerminalBlockEntity.Availability.OPEN)
                        throw new IllegalArgumentException("This job requires an application.");
                    if (player.isSpectator() || EconomyLedger.get(player.server).account(player.getUUID()).suspended())
                        throw new IllegalArgumentException("You are not eligible for this job.");
                    JobDelivery.recover(player);
                    if (!jobs.start(player.getUUID(), selectedJob)) throw new IllegalArgumentException("This is already your current job.");
                    de.kuro.kuronomics.economy.FinancialPersistence.save(player.server);
                    status = ""; view = "catalog";
                }
                case "leave" -> {
                    requireRevisions(action, jobs, applications); requireSelected("details", action.jobId());
                    if (!EconomyLedger.get(player.server).account(player.getUUID()).job().equals(selectedJob))
                        throw new IllegalArgumentException("This is no longer your current job.");
                    jobs.quit(player);
                    status = ""; view = "catalog";
                }
                case "submit" -> {
                    requireRevisions(action, jobs, applications); requireSelected("apply", action.jobId());
                    applications.submit(player, terminal, selectedJob, action.text());
                    status = "Application submitted."; view = "catalog";
                }
                case "add_screen" -> { requireEditor(player); requireRevisions(action, jobs, applications); view = "add"; page = 0; status = ""; }
                case "add" -> {
                    requireEditor(player); requireRevisions(action, jobs, applications);
                    if (!view.equals("add") || !jobs.registry().jobs().containsKey(action.jobId())) throw new IllegalArgumentException("Job is unavailable.");
                    terminal.add(action.jobId()); selectedJob = action.jobId(); view = "edit_details";
                }
                case "remove" -> {
                    requireEditor(player); requireRevisions(action, jobs, applications); requireSelected("edit_details", action.jobId());
                    terminal.remove(selectedJob); selectedJob = ""; view = "editor";
                }
                case "toggle" -> {
                    requireEditor(player); requireRevisions(action, jobs, applications); requireSelected("edit_details", action.jobId());
                    var offer = terminal.offer(selectedJob);
                    terminal.update(selectedJob, offer.description(), offer.mode() == JobTerminalBlockEntity.Availability.OPEN
                            ? JobTerminalBlockEntity.Availability.APPLICATION_REQUIRED : JobTerminalBlockEntity.Availability.OPEN);
                }
                case "description" -> {
                    requireEditor(player); requireRevisions(action, jobs, applications); requireSelected("edit_details", action.jobId());
                    terminal.update(selectedJob, action.text(), terminal.offer(selectedJob).mode()); status = "Description saved.";
                }
                case "up", "down" -> {
                    requireEditor(player); requireRevisions(action, jobs, applications);
                    if (!view.equals("editor") || terminal.offer(action.jobId()) == null) throw new IllegalArgumentException("Choose a job in the overview.");
                    terminal.move(action.jobId(), action.action().equals("up") ? -1 : 1);
                }
                case "accept", "reject" -> {
                    requireManager(player);
                    if (action.registryRevision() != jobs.registryRevision()) throw new IllegalArgumentException("Jobs changed. Please review the application.");
                    // Another application may have changed the list revision. Check the selected UUID below;
                    // JobService still checks the slot cap when it finally assigns the job.
                    if (!view.equals("application_details") || !selectedApplication.equals(action.applicationId())) throw new IllegalArgumentException("Open application details first.");
                    boolean changed = action.action().equals("accept") ? applications.accept(player.server, selectedApplication)
                            : applications.reject(player.server, selectedApplication);
                    if (!changed) throw new IllegalArgumentException("Application was already processed.");
                    view = "applications"; selectedApplication = NONE; status = action.action().equals("accept") ? "Application accepted." : "Application rejected.";
                }
                default -> throw new IllegalArgumentException("Invalid job action.");
            }
        } catch (IllegalArgumentException | IllegalStateException error) { status = error.getMessage(); }
        sync(player);
    }
    private void requireSelected(String expectedView, String id) {
        if (!view.equals(expectedView) || !selectedJob.equals(id) || terminal.offer(id) == null)
            throw new IllegalArgumentException("Open the job details first.");
    }
    private void requireEditor(ServerPlayer player) {
        if (kind != 1 || !JobPermissions.edit(player)) throw new IllegalArgumentException("Editor permission required.");
    }
    private void requireManager(ServerPlayer player) {
        if (kind != 2 || !JobPermissions.manage(player)) throw new IllegalArgumentException("Application permission required.");
    }
    public void sync(ServerPlayer player) {
        if (player.containerMenu != this || !stillValid(player)) return;
        JobService jobs = JobService.get(player.server);
        JobApplications applications = JobApplications.get(player.server);
        String currentId = EconomyLedger.get(player.server).account(player.getUUID()).job();
        JobRegistry.Job current = jobs.registry().jobs().get(currentId);
        String currentName = current == null ? currentId.equals("unemployed") ? "None" : currentId : current.name();
        List<JobSnapshot.OfferView> offers = new ArrayList<>(); List<JobSnapshot.ApplicationView> appViews = new ArrayList<>(); int total = 0;
        if (kind == 2) {
            List<JobApplications.Application> source = applications.pending(); total = source.size();
            page = clampPage(page, total);
            if (view.equals("application_details")) {
                var a = applications.find(selectedApplication);
                if (a == null) { view = "applications"; selectedApplication = NONE; }
                else appViews.add(applicationView(a, true));
            }
            if (view.equals("applications")) source.stream().skip((long) page * PAGE_SIZE).limit(PAGE_SIZE)
                    .forEach(a -> appViews.add(applicationView(a, false)));
        } else {
            List<String> ids = view.equals("add") ? jobs.registry().jobs().keySet().stream().sorted().toList()
                    : terminal.offers().stream().map(JobTerminalBlockEntity.Offer::jobId).filter(jobs.registry().jobs()::containsKey).toList();
            total = ids.size(); page = clampPage(page, total);
            if (view.equals("details") || view.equals("edit_details") || view.equals("apply")) {
                if (terminal.offer(selectedJob) == null || !jobs.registry().jobs().containsKey(selectedJob)) {
                    view = kind == 1 ? "editor" : "catalog"; selectedJob = "";
                } else offers.add(offerView(jobs, selectedJob, terminal.offer(selectedJob)));
            }
            if (view.equals("catalog") || view.equals("editor") || view.equals("add"))
                ids.stream().skip((long) page * PAGE_SIZE).limit(PAGE_SIZE).forEach(id ->
                        offers.add(offerView(jobs, id, terminal.offer(id))));
        }
        state = new JobSnapshot(containerId, view, page, total, status, currentName, currentId, terminal == null ? 0 : terminal.revision(),
                jobs.registryRevision(), jobs.occupancyRevision(), applications.revision(), offers, appViews, selectedJob, selectedApplication);
        PacketDistributor.sendToPlayer(player, state);
    }
    private static int clampPage(int page, int total) { return Math.max(0, Math.min(page, Math.max(0, (total - 1) / PAGE_SIZE))); }
    private static JobSnapshot.ApplicationView applicationView(JobApplications.Application a, boolean detail) {
        return new JobSnapshot.ApplicationView(a.id(), a.applicantName(), a.jobName(), detail ? a.text() : "", a.submittedAt());
    }
    private static JobSnapshot.OfferView offerView(JobService service, String id, JobTerminalBlockEntity.Offer offer) {
        JobRegistry.Job job = service.registry().jobs().get(id);
        return new JobSnapshot.OfferView(id, job.name(), job.mode().name(), job.base(), job.max(), job.slots(),
                service.occupancy(id), job.authority().appointed() ? "APPOINTMENT_REQUIRED" : offer == null ? "OPEN" : offer.mode().name(), offer == null ? "" : offer.description());
    }
    @Override public void broadcastChanges() {
        super.broadcastChanges();
        if (initialSyncPending && viewer instanceof ServerPlayer player && player.containerMenu == this) {
            initialSyncPending = false; sync(player);
        }
    }
    @Override public boolean stillValid(Player player) {
        if (!(player instanceof ServerPlayer p)) return true;
        if (kind == 2) return JobPermissions.manage(p);
        if (kind == 1 && !JobPermissions.edit(p)) return false;
        return p.serverLevel() == terminal.getLevel() && terminal.isValidTerminal(p.serverLevel())
                && nearTerminal(p, pos);
    }
    private static boolean nearTerminal(ServerPlayer player, BlockPos pos) {
        int range = EconomyEvents.config().jobTerminalRange();
        return player.distanceToSqr(pos.getX() + .5, pos.getY() + .5, pos.getZ() + .5) <= (double) range * range;
    }
    @Override public ItemStack quickMoveStack(Player player, int slot) { return ItemStack.EMPTY; }
}
