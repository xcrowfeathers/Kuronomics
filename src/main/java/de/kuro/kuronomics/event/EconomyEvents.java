package de.kuro.kuronomics.event;

import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.config.EconomyConfig;
import de.kuro.kuronomics.economy.Money;
import de.kuro.kuronomics.economy.EconomyService;
import de.kuro.kuronomics.economy.CashService;
import de.kuro.kuronomics.economy.data.*;
import de.kuro.kuronomics.economy.payday.PaydayService;
import de.kuro.kuronomics.economy.payday.PaydayClock;
import de.kuro.kuronomics.util.ModText;
import de.kuro.kuronomics.mailbox.*;
import java.math.BigDecimal;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = Kuronomics.MODID)
public final class EconomyEvents {
    private static EconomyConfig config;
    private static final java.util.Map<net.minecraft.server.MinecraftServer, java.util.Set<java.util.UUID>> settlingPaydays = new java.util.WeakHashMap<>();
    private static final java.util.Map<net.minecraft.server.MinecraftServer, java.util.LinkedHashSet<java.util.UUID>> duePaydays = new java.util.WeakHashMap<>();
    private static final java.util.Map<net.minecraft.server.MinecraftServer, java.util.LinkedHashSet<String>> dueEvictions = new java.util.WeakHashMap<>();
    private EconomyEvents() {}
    /** Admin paydays use the normal capped queue and the same protection against repeated payouts. */
    public static void queuePayday(ServerPlayer player) {
        if (!player.server.isSameThread()) throw new IllegalStateException("Payday requested off server thread");
        var ledger = EconomyLedger.get(player.server);
        if (!ledger.paydayEnabled()) throw new IllegalArgumentException("Payday is disabled. Use /payday enable first.");
        PaydayClock.ready(player, config().paydayTicks());
        duePaydays.computeIfAbsent(player.server, ignored -> new java.util.LinkedHashSet<>()).add(player.getUUID());
    }
    public static EconomyConfig config() {
        if (config == null) throw new IllegalStateException("Economy is not loaded");
        return config;
    }
    public static void reload(net.minecraft.server.MinecraftServer server) {
        var replacement = EconomyConfig.load(server);
        var bank = de.kuro.kuronomics.economy.bank.BankConfig.load();
        de.kuro.kuronomics.economy.payday.PaydayReport.configure(replacement.paydayDetails());
        config = replacement;
        de.kuro.kuronomics.economy.bank.CentralBank.configure(server, bank);
        de.kuro.kuronomics.market.MarketService.configure(server, replacement.marketplace());
    }
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST) public static void starting(ServerAboutToStartEvent event) {
        try {
            de.kuro.kuronomics.economy.FinancialJournal.recoverDirectory(event.getServer()
                    .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data"));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Financial journal recovery failed. Preserve the world data and journal files; economy startup refused to prevent lost or duplicated funds.", failure);
        }
        reload(event.getServer());
    }
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        LegacyImporter.world(event.getServer());
        EconomyLedger.get(event.getServer()).trimBankActivity(de.kuro.kuronomics.economy.bank.CentralBank.config(event.getServer()).historyLimit());
        de.kuro.kuronomics.market.MarketService.get(event.getServer());
        try { de.kuro.kuronomics.jobs.JobService.get(event.getServer()).reload(); }
        catch (RuntimeException invalid) { com.mojang.logging.LogUtils.getLogger().error("Jobs/task configuration rejected; fix economy_jobs.toml or the reported datapack and use /jobs reload or /reload. Valid TOML jobs are retained on initial task-load failure: {}", invalid.getMessage()); }
        long unbound = EconomyLedger.get(event.getServer()).estates().stream().filter(e -> e.regionBinding().isEmpty()).count();
        if (unbound > 0) com.mojang.logging.LogUtils.getLogger().warn("{} estate records are preserved and waiting for operator review with /estate setprice", unbound);
        MailboxStore.validate(event.getServer());
        CashTransactionStore.validate(event.getServer());
        PlayerShopTransactionStore.validate(event.getServer());
        de.kuro.kuronomics.servershop.ServerShopStore.validate(event.getServer());
        de.kuro.kuronomics.auction.AuctionHouseStore.validate(event.getServer());
        de.kuro.kuronomics.auction.AuctionClaimStore.validate(event.getServer());
        de.kuro.kuronomics.estate.PropertyLockStore.validate(event.getServer());
        de.kuro.kuronomics.estate.market.RealEstateMarketStore.validate(event.getServer());
        de.kuro.kuronomics.estate.market.inbox.RealEstateInboxStore.validate(event.getServer());
        // Before recovery, only saved auction state can tell us which charge/reservation records
        // are safe to discard across files. During gameplay, cleanup only touches its own store.
        EconomyLedger.get(event.getServer()).pruneClosedAtStartup(
                de.kuro.kuronomics.auction.AuctionHouseStore.get(event.getServer()).financialReferences());
        new de.kuro.kuronomics.servershop.ServerShopService(event.getServer()).processStartup();
        var auctions = new de.kuro.kuronomics.auction.AuctionHouseService(event.getServer());
        auctions.recoverPropertyListings(); auctions.recoverBids(); auctions.recoverSettlements(); auctions.processExpired();
        new de.kuro.kuronomics.estate.market.RealEstateMarketService(event.getServer()).recover();
        // An orphan market lock may have been the only thing stopping an unfinished auction listing.
        auctions.recoverPropertyListings();
        if (EconomyLedger.get(event.getServer()).paydayEnabled()) PaydayClock.ensure(event.getServer());
        de.kuro.kuronomics.company.CompanyRuntime.start(event.getServer());
    }
    @SubscribeEvent public static void stopping(ServerStoppingEvent event) { de.kuro.kuronomics.company.CompanyRuntime.stop(event.getServer()); de.kuro.kuronomics.economy.FinancialSettlements.stop(event.getServer()); }
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST) public static void stopped(ServerStoppedEvent event) {
        try {
            de.kuro.kuronomics.economy.FinancialWriter.close(event.getServer());
        } finally {
            // Clear session state even if the final checkpoint fails.
            // Still report that failure; cleanup doesn't mean the save succeeded.
            de.kuro.kuronomics.economy.EconomyActions.stop(event.getServer());
            config = null;
            duePaydays.remove(event.getServer());
            settlingPaydays.remove(event.getServer());
            dueEvictions.remove(event.getServer());
            de.kuro.kuronomics.jobs.JobService.stop(event.getServer());
            de.kuro.kuronomics.company.CompanyRuntime.stop(event.getServer());
            de.kuro.kuronomics.economy.bank.CentralBank.stop(event.getServer());
            de.kuro.kuronomics.market.MarketService.stop(event.getServer());
            de.kuro.kuronomics.menu.MarketMenu.stop(event.getServer());
        }
    }
    @SubscribeEvent public static void joined(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            LegacyImporter.join(player);
            de.kuro.kuronomics.market.MarketService.get(player.server).recover(player);
            de.kuro.kuronomics.jobs.JobDelivery.recover(player);
            de.kuro.kuronomics.jobs.JobService.get(player.server).notifyOnLogin(player);
            new MailboxService(player.server).remember(player.getUUID(), player.getGameProfile().getName());
            new de.kuro.kuronomics.servershop.ServerShopService(player.server).recover(player);
            new de.kuro.kuronomics.auction.AuctionHouseService(player.server).recover(player);
            CashService.recover(player);
            new de.kuro.kuronomics.economy.PlayerShopService(player.server).recover(player);
            var ledger = EconomyLedger.get(player.server);
            PaydayClock.join(player, ledger);
            ledger.joined(player.getUUID(), player.server.getTickCount());
        }
    }
    @SubscribeEvent public static void left(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            de.kuro.kuronomics.economy.EconomyActions.left(player);
            de.kuro.kuronomics.menu.CentralBankMenu.left(player);
            EconomyLedger.get(player.server).observe(player.getUUID(), player.server.getTickCount(), true);
            new de.kuro.kuronomics.servershop.ServerShopService(player.server).endEditing(player.getUUID());
        }
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        var server = event.getServer();
        de.kuro.kuronomics.economy.FinancialMaintenance.tick(server);


        var due = duePaydays.computeIfAbsent(server, ignored -> new java.util.LinkedHashSet<>());
        var ledger = EconomyLedger.get(server);
        var evictions = dueEvictions.computeIfAbsent(server, ignored -> new java.util.LinkedHashSet<>());
        if (server.getTickCount() % 1200 == 0) evictions.addAll(ledger.evictions());
        if (server.getTickCount() % 20 == 0) {
            var bank=de.kuro.kuronomics.economy.bank.CentralBank.config(server);
            ledger.retainBankHistory(de.kuro.kuronomics.economy.bank.BankActivity.today(),bank.historyDays(),bank.maxHistoryAccountsPerCategory());
            for (var shopId : new de.kuro.kuronomics.servershop.ServerShopService(server).processOverdueRotations())
                de.kuro.kuronomics.menu.ServerShopTerminalMenu.refreshShop(server, shopId);
            ledger.sampleTick(server.getTickCount());
            if (ledger.paydayEnabled()) for (ServerPlayer player : server.getPlayerList().getPlayers())
                if (PaydayClock.score(player) >= config().paydayTicks()) due.add(player.getUUID());
        }
        de.kuro.kuronomics.economy.EconomyWorkCoordinator.run(server,
                () -> de.kuro.kuronomics.economy.FinancialSettlements.tick(server),
                () -> de.kuro.kuronomics.economy.EconomyActions.tick(server),
                () -> de.kuro.kuronomics.company.CompanyAssetService.get(server).tick(),
                () -> processEvictions(server, ledger, evictions),
                () -> processAuctions(server),
                () -> processPaydays(server, ledger, due));
    }
    private static void processEvictions(net.minecraft.server.MinecraftServer server, EconomyLedger ledger, java.util.LinkedHashSet<String> evictions) {
        if (!evictions.isEmpty()) {
            var iterator = evictions.iterator(); String key = iterator.next(); iterator.remove();
            var estate = ledger.estateByKey(key);
            if (estate != null && ledger.evictionPending(key)) {
                try { new de.kuro.kuronomics.estate.EstateService(server).evict(estate); ledger.clearEviction(key); }
                catch (RuntimeException e) { com.mojang.logging.LogUtils.getLogger().error("Rental eviction is pending for {}: {}", key, e.getMessage()); }
            }
        }
    }
    private static void processAuctions(net.minecraft.server.MinecraftServer server) {
        if (!de.kuro.kuronomics.economy.FinancialWriter.busy(server))
            for (var auctionId : new de.kuro.kuronomics.auction.AuctionHouseService(server).processExpired())
                de.kuro.kuronomics.menu.AuctionMenu.refreshAuction(server, auctionId);
    }
    private static void processPaydays(net.minecraft.server.MinecraftServer server, EconomyLedger ledger, java.util.LinkedHashSet<java.util.UUID> due) {
        if (!ledger.paydayEnabled()) { due.clear(); return; }
        if (de.kuro.kuronomics.economy.FinancialWriter.busy(server)) return;
        var economy = new EconomyService(ledger);
        var settling = settlingPaydays.computeIfAbsent(server, ignored -> new java.util.HashSet<>());
        long deadline = de.kuro.kuronomics.economy.EconomyWorkCoordinator.deadline();
        int processed = 0;
        while (!due.isEmpty() && processed < 4 && (processed == 0 || System.nanoTime() < deadline)) {
            var iterator = due.iterator(); var id = iterator.next(); iterator.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null || settling.contains(id)) continue;
            try { de.kuro.kuronomics.economy.FinancialSettlements.requireCapacity(server); }
            catch (IllegalStateException overloaded) { due.add(id); return; }
            processed++;
            try {
                var account = economy.account(id);
                if (PaydayClock.score(player) < config().paydayTicks()) continue;
                de.kuro.kuronomics.jobs.JobDelivery.recover(player);
                var interest = new PaydayService.Interest(config().interestEnabled(), BigDecimal.valueOf(config().interestPercent()),
                        Money.legacy(config().interestMinimumBalance()), Money.legacy(config().interestMaximumPayment()));
                var companyAgreements = ledger.companies().outgoing(de.kuro.kuronomics.economy.EconomicOwner.player(id));
                var result = PaydayService.process(ledger, id, account.paydaySequence(), config(), interest,
                        new de.kuro.kuronomics.estate.PropertyService(server)::rentUsable,
                        new de.kuro.kuronomics.auction.AuctionHouseService(server)::chargeUsable);
                try { new MailboxService(server).payday(id, result); }
                catch (RuntimeException notificationFailure) {
                    com.mojang.logging.LogUtils.getLogger().error("Payday committed but mailbox notification could not be staged", notificationFailure);
                }
                settling.add(id);
                de.kuro.kuronomics.economy.FinancialSettlements.after(server, () -> {
                try {
                try {
                    for (var auctionId : new de.kuro.kuronomics.auction.AuctionHouseService(server).onPayday(result))
                        de.kuro.kuronomics.menu.AuctionMenu.refreshAuction(server, auctionId);
                } catch (RuntimeException settlementFailure) {
                    // The payday debit is already on disk. Recovery can resume it with the same reference ID.
                    com.mojang.logging.LogUtils.getLogger().error("PayDay completed, but auction settlement recovery is pending", settlementFailure);
                }
                // Save the ledger sequence before changing the vanilla timer.
                if (ledger.paydayEnabled()) PaydayClock.reset(player);
                for (var agreement : companyAgreements) {
                    var currentAgreement = ledger.companies().contracts().get(agreement.id());
                    if (currentAgreement != null) continue;
                    var company = ledger.companies().company(agreement.recipient().id());
                    for (var member : company.members().entrySet())
                        if (de.kuro.kuronomics.company.CompanyPermissionService.allowed(company, member.getKey(), de.kuro.kuronomics.company.CompanyPermissionService.Capability.STATEMENT)) {
                            var recipient = server.getPlayerList().getPlayer(member.getKey());
                            if (recipient != null) recipient.sendSystemMessage(Component.literal("Company contract '" + agreement.name() + "' with " + player.getGameProfile().getName() + " cancelled due to non-payment: " + Money.format(agreement.amount())));
                        }
                }
                player.sendSystemMessage(Component.literal("======== PayDay Summary ========").withStyle(net.minecraft.ChatFormatting.GOLD, net.minecraft.ChatFormatting.BOLD));
                player.sendSystemMessage(Component.literal("Old balance: " + Money.format(result.opening())).withStyle(net.minecraft.ChatFormatting.GRAY));
                result.lines().forEach(line -> player.sendSystemMessage(ModText.paydayLine(de.kuro.kuronomics.util.PlayerNames.resolve(server, line))));
                var netChange = java.math.BigDecimal.valueOf(result.closing(), 2).subtract(java.math.BigDecimal.valueOf(result.opening(), 2));
                var format = java.text.NumberFormat.getNumberInstance(java.util.Locale.GERMANY);
                format.setMinimumFractionDigits(2); format.setMaximumFractionDigits(2);
                player.sendSystemMessage(Component.literal("= Net change: $" + format.format(netChange)).withStyle(net.minecraft.ChatFormatting.AQUA));
                player.sendSystemMessage(Component.literal("New balance: " + Money.format(result.closing())).withStyle(net.minecraft.ChatFormatting.GREEN));
                player.sendSystemMessage(Component.literal("===============================").withStyle(net.minecraft.ChatFormatting.GOLD, net.minecraft.ChatFormatting.BOLD));
                for (var cancelled : result.cancelled()) {
                    if (cancelled.receiver() != null) {
                        ServerPlayer receiver = server.getPlayerList().getPlayer(cancelled.receiver());
                        if (receiver != null) receiver.sendSystemMessage(Component.literal("Contract '" + cancelled.name() + "' cancelled due to non-payment.").withStyle(net.minecraft.ChatFormatting.YELLOW));
                    }
                }
                } finally { settling.remove(id); }
                }, failure -> {
                    settling.remove(id); ledger.paydayEnabled(false); PaydayClock.remove(server);
                    player.sendSystemMessage(Component.literal("Payday storage failed; completion is uncertain. Contact an administrator."));
                    com.mojang.logging.LogUtils.getLogger().error("Payday persistence failed", failure);
                });
            } catch (ArithmeticException | IllegalArgumentException | IllegalStateException e) {
                ledger.paydayEnabled(false);
                PaydayClock.remove(server);
                player.sendSystemMessage(Component.literal("Payday paused: " + e.getMessage()).withStyle(net.minecraft.ChatFormatting.RED));
                com.mojang.logging.LogUtils.getLogger().error("Payday failed; no partial payout was committed", e);
                return;
            }
        }
    }
}


