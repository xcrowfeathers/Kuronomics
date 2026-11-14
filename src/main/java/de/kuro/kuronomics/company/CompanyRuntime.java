package de.kuro.kuronomics.company;

import de.kuro.kuronomics.economy.*;
import de.kuro.kuronomics.economy.data.*;
import de.kuro.kuronomics.mailbox.*;
import de.kuro.kuronomics.util.PlayerNames;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/** The timer uses wall-clock time. All changes still run on the server thread. */
public final class CompanyRuntime {
    private static final Map<MinecraftServer, CompanyRuntime> RUNTIMES = new WeakHashMap<>();
    private final MinecraftServer server;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Kuronomy Company PayDay"); t.setDaemon(true); return t;
    });
    private CompanyConfig config;
    private ScheduledFuture<?> trigger;
    private long generation;
    private boolean stopped;
    private UUID debugRequest, debugActor;
    private long debugExpires;
    private CompanyRuntime(MinecraftServer server, CompanyConfig config) { this.server = server; this.config = config; }
    public static CompanyRuntime get(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Company runtime off server thread");
        var runtime = RUNTIMES.get(server); if (runtime == null) throw new IllegalStateException("Company economy is not loaded"); return runtime;
    }
    public CompanyConfig config() { return config; }
    public static ZonedDateTime occurrence(LocalDate day, CompanyConfig config) {
        // For DST, Java skips missing times and picks the earlier offset for repeated times. Use the date to
        // detect retries.
        return ZonedDateTime.of(day, config.paydayTime(), config.zone());
    }
    public static ZonedDateTime next(Instant now, CompanyConfig config) {
        LocalDate day = now.atZone(config.zone()).toLocalDate(); var due = occurrence(day, config);
        return due.toInstant().isAfter(now) ? due : occurrence(day.plusDays(1), config);
    }
    public static ZonedDateTime latest(Instant now, CompanyConfig config) {
        LocalDate day = now.atZone(config.zone()).toLocalDate(); var due = occurrence(day, config);
        return due.toInstant().isAfter(now) ? occurrence(day.minusDays(1), config) : due;
    }
    public static void start(MinecraftServer server) {
        CompanyConfig config = CompanyConfig.load(); var runtime = new CompanyRuntime(server, config); RUNTIMES.put(server, runtime);
        var ledger = EconomyLedger.get(server); var due = latest(Instant.now(), config);
        var employmentChanges = CompanyEmploymentPolicy.reconcile(ledger);
        if (!employmentChanges.isEmpty()) {
            for (UUID id : employmentChanges) CompanyAssetService.get(server).refreshMembershipAccess(id);
            FinancialPersistence.save(server);
        }
        ledger.companyDebtLimit(config.debtLimit());
        if (ledger.companies().cycleDay() < 0) {
            var edit = ledger.companies().edit(); edit.cycleDay = due.toLocalDate().toEpochDay();
            ledger.commitOwners(UUID.randomUUID(), "company-clock-initialize|" + edit.cycleDay, Map.of(), List.of(), edit.build(), Map.of());
            FinancialPersistence.save(server);
        } else runtime.settle(due);
        CompanyAssetService.get(server).recover(); runtime.schedule();
    }
    public void reload() {
        CompanyConfig updated = CompanyConfig.load();
        de.kuro.kuronomics.economy.payday.PaydayReport.reload();
        config = updated; generation++;
        EconomyLedger.get(server).companyDebtLimit(config.debtLimit());
        if (trigger != null) trigger.cancel(false); schedule();
    }
    private void schedule() {
        if (stopped) return;
        Instant now = Instant.now(); var due = next(now, config); long token = generation;
        trigger = scheduler.schedule(() -> server.execute(() -> {
            if (stopped || token != generation) return;
            try { settle(latest(Instant.now(), config)); }
            catch (RuntimeException failure) { com.mojang.logging.LogUtils.getLogger().error("Company PayDay refused; financial recovery/administrator review required", failure); }
            finally { schedule(); }
        }), Math.max(1, Duration.between(now, due.toInstant()).toMillis()), TimeUnit.MILLISECONDS);
    }
    private void settle(ZonedDateTime due) {
        FinancialWriter.check(server);
        var ledger = EconomyLedger.get(server);
        // Only catch up the latest due date. Don't pay out every missed day.
        var result = CompanyPaydayService.process(ledger, config, due.toLocalDate().toEpochDay(), System.currentTimeMillis());
        publish(result);
    }
    public UUID requestDebug(UUID actor) {
        debugRequest = UUID.randomUUID(); debugActor = actor;
        debugExpires = System.currentTimeMillis() + config.confirmationSeconds() * 1000L; return debugRequest;
    }
    public void debug(UUID actor, UUID request) {
        if (!Objects.equals(debugActor, actor) || !request.equals(debugRequest) || System.currentTimeMillis() > debugExpires)
            throw new IllegalArgumentException("This payday confirmation has expired. Run /companyadmin dopayday again.");
        FinancialWriter.check(server); var ledger = EconomyLedger.get(server);
        publish(CompanyPaydayService.debug(ledger, config, Math.max(0, ledger.companies().cycleDay()), System.currentTimeMillis(), request));
        debugRequest = null;
    }
    private void publish(CompanyPaydayService.Result result) {
        if (!result.committed()) return;
        FinancialPersistence.save(server);
        var ledger = EconomyLedger.get(server);
        for (var entry : result.statements().entrySet()) {
            Company c = ledger.companies().company(entry.getKey()); Component report = null;
            for (var member : c.members().entrySet()) if (member.getValue().rank() == Company.Rank.CEO || member.getValue().rank() == Company.Rank.DIRECTOR) {
                var player = server.getPlayerList().getPlayer(member.getKey());
                if (player != null) { if (report == null) report = statement(server, c, entry.getValue()); player.sendSystemMessage(report); }
            }
            if (c.bankruptcy() != null) {
                var b = c.bankruptcy();
                var bankruptcyDetails=new java.util.ArrayList<MailboxDetail>();
                bankruptcyDetails.add(new MailboxDetail(MailboxDetail.Kind.WARNING,"Bankruptcy penalty charged to your account",b.penalty()));
                if(b.bankLoanRemainder()>0)bankruptcyDetails.add(new MailboxDetail(MailboxDetail.Kind.OUTGOING,"Remaining company bank loan charged to your account",b.bankLoanRemainder()));
                new MailboxService(server).system(c.ceo(), EconomyService.stableReference("company-bankruptcy-mail", c.id().toString()), "Company closed: " + c.name(),
                        bankruptcyDetails);
            }
        }
        CompanyAssetService.get(server).recover();
        var mailbox = new MailboxService(server);
        for (var contract : result.failed()) if (contract.recipient().isPlayer()) {
            String subject = "Company contract '" + contract.name() + "' cancelled: " + PlayerNames.owner(server, contract.payer());
            UUID reference = EconomyService.stableReference("company-failure-mail", result.cycleDay() + ":" + contract.id());
            mailbox.system(contract.recipient().id(), reference, subject, List.of(new MailboxDetail(MailboxDetail.Kind.WARNING, "Payment could not be funded", contract.amount())));
        }
        if (result.failed().stream().anyMatch(c -> c.recipient().isPlayer()) || result.statements().keySet().stream().anyMatch(id -> ledger.companies().company(id).bankruptcy() != null)) FinancialPersistence.save(server);
    }
    public static Component statement(MinecraftServer server, Company company, Company.Statement s) {
        StringBuilder text = new StringBuilder("§7Old balance: §f" + Money.format(s.opening()));
        var lines = de.kuro.kuronomics.economy.payday.PaydayReport.shopSales(s.lines()).stream().sorted(Comparator.comparingInt((Company.Line line) ->
                line.kind().equals("WARNING") ? 3 : line.kind().equals("OUTGOING") ? 1 : line.label().equals("Interest") ? 2 : 0)).toList();
        for (var line : lines) {
            boolean warning = line.kind().equals("WARNING"), outgoing = line.kind().equals("OUTGOING");
            String label = line.label().startsWith("State contract '") ? "Bank" + line.label().substring(5) : line.label();
            text.append("\n").append(warning ? "§e! " : outgoing ? "§c-" : "§a+");
            if (warning) text.append(label).append(line.party() == null ? "" : " " + PlayerNames.owner(server, line.party())).append(": ").append(Money.format(line.amount()));
            else text.append(Money.format(line.amount())).append(" §7").append(label).append(line.party() == null ? "" : " " + PlayerNames.owner(server, line.party()));
        }
        // Subtract as decimals: two valid signed balances can still overflow a long when finding the
        // difference.
        var netChange = java.math.BigDecimal.valueOf(s.closing(), 2).subtract(java.math.BigDecimal.valueOf(s.opening(), 2));
        var format = java.text.NumberFormat.getNumberInstance(java.util.Locale.GERMANY);
        format.setMinimumFractionDigits(2); format.setMaximumFractionDigits(2);
        text.append("\n\n§bNet change: ").append(netChange.signum() > 0 ? "+" : netChange.signum() < 0 ? "-" : "").append("$").append(format.format(netChange.abs()));
        text.append("\n").append(s.closing() < 0 ? "§c" : "§a").append("= New balance: ").append(Money.format(s.closing()));
        if (company.bankruptcy() != null) text.append("\n§cCompany bankrupt. Properties return to the Bank.");
        return CompanyChat.finish(CompanyChat.card("Company Payday · " + company.name() + " · "
                + (s.debug() ? Instant.ofEpochMilli(s.cutoff()).atZone(get(server).config().zone()).toLocalDate() : LocalDate.ofEpochDay(s.cycleDay())), text.toString()));
    }
    public static void stop(MinecraftServer server) {
        var runtime = RUNTIMES.remove(server);
        if (runtime != null) { runtime.stopped = true; runtime.generation++; runtime.scheduler.shutdownNow(); }
        CompanyService.stop(server); CompanyAssetService.stop(server);
    }
}

