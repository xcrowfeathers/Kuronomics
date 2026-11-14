package de.kuro.kuronomics.economy.data;

import com.google.gson.*;
import de.kuro.kuronomics.economy.Money;
import de.kuro.kuronomics.economy.EconomyService;
import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.LegacyCompatibility;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/** Read old save formats without bringing back their runtime types. Leave the source files untouched. */
public final class LegacyImporter {
    private LegacyImporter() {}
    public static void account(MinecraftServer server, UUID id, EconomyLedger ledger) {
        if (ledger.contains(id)) return;
        Path file = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(id + ".dat");
        Account account = Account.empty();
        if (Files.exists(file)) {
            try {
                CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.create(64 * 1024 * 1024));
                CompoundTag attachments = root.getCompound("neoforge:attachments");
                CompoundTag old = attachments.contains(Kuronomics.MODID + ":player_variables")
                        ? attachments.getCompound(Kuronomics.MODID + ":player_variables")
                        : attachments.getCompound(LegacyCompatibility.PLAYER_ATTACHMENT);
                CompoundTag persistent = root.getCompound("NeoForgeData");
                String job = persistent.getString("job");
                if (job.isBlank()) job = "unemployed";
                job = de.kuro.kuronomics.jobs.JobIds.legacy(job);
                account = new Account(Money.legacy(old.getDouble("bal")), 0, 0, 0, job,
                        old.contains("entry") ? old.getString("entry") : Account.empty().entry(), old.getBoolean("sus"), old.getString("susreason"));
            } catch (IOException | RuntimeException e) { throw new IllegalStateException("Cannot import economy account " + id, e); }
        }
        new EconomyService(ledger).importAccount(id, account);
    }
    public static void world(MinecraftServer server) {
        Path data = server.getWorldPath(LevelResource.ROOT).resolve("data");
        Path existing = data.resolve(Kuronomics.MODID + "_economy.dat");
        if (!Files.exists(existing)) existing = data.resolve(LegacyCompatibility.LEDGER + ".dat");
        if (Files.exists(existing)) {
            // If money data can't be read, stop loading. Falling back to a fresh ledger would wipe everyone's
            // balances.
            try { EconomyLedger.load(NbtIo.readCompressed(existing, NbtAccounter.create(256 * 1024 * 1024)).getCompound("data"), server.registryAccess()); }
            catch (IOException | RuntimeException e) { throw new IllegalStateException("Economy ledger is unreadable; restore a backup before starting this world", e); }
        }
        EconomyLedger ledger = EconomyLedger.get(server);
        if (ledger.imported()) return;
        // Import into a separate ledger first, so a bad old file can't leave the live world half-imported.
        EconomyLedger staging = new EconomyLedger();
        Path root = server.getWorldPath(LevelResource.ROOT);
        try {
            Path playerDir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
            if (Files.isDirectory(playerDir)) {
                try (var files = Files.list(playerDir)) {
                    for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".dat")).toList())
                        account(server, UUID.fromString(file.getFileName().toString().replace(".dat", "")), staging);
                }
            }
            readPayments(root.resolve("serverconfig/contracts"), false, staging, server);
            readPayments(root.resolve("serverconfig/bankcontracts"), true, staging, server);
            Path estateFile = root.resolve("serverconfig/estates.json");
            if (Files.exists(estateFile)) {
                for (var entry : read(estateFile).getAsJsonObject().entrySet()) {
                    JsonObject e = entry.getValue().getAsJsonObject();
                    UUID owner = e.has("ownerUUID") && !e.get("ownerUUID").isJsonNull() ? UUID.fromString(e.get("ownerUUID").getAsString()) : null;
                    UUID tenant = e.has("tenantUUID") && !e.get("tenantUUID").isJsonNull() ? UUID.fromString(e.get("tenantUUID").getAsString()) : null;
                    if (owner != null) account(server, owner, staging); if (tenant != null) account(server, tenant, staging);
                    staging.estate(new de.kuro.kuronomics.estate.Estate(e.get("dimensionId").getAsString(), e.get("regionName").getAsString(), Money.parse(e.get("price").getAsString()),
                            e.has("rentPrice") ? Money.parse(e.get("rentPrice").getAsString()) : 0, owner, tenant, e.has("rentLocked") && e.get("rentLocked").getAsBoolean()));
                    // Rent now comes from the estate itself. Drop old duplicate contracts so it isn't charged
                    // twice.
                    for (var payment : staging.contracts()) if (payment.name().equals("Rent:" + entry.getKey())) staging.removeContract(payment);
                }
            }
            Path buffers = root.resolve("serverconfig/buffers");
            if (Files.isDirectory(buffers)) {
                try (var files = Files.list(buffers)) {
                    for (Path file : files.filter(p -> p.getFileName().toString().endsWith("_buffer.json")).toList()) {
                        UUID id = UUID.fromString(file.getFileName().toString().replace("_buffer.json", "")); account(server, id, staging);
                        long total = 0; List<Receipt> receipts = new ArrayList<>();
                        for (JsonElement raw : read(file).getAsJsonArray()) {
                            var entry = raw.getAsJsonObject(); long amount = Money.positive(Money.parse(entry.get("amount").getAsString()));
                            total = Math.addExact(total, amount);
                            String name = entry.has("name") && !entry.get("name").isJsonNull() ? entry.get("name").getAsString() : "Deferred income";
                            String from = entry.has("from") && !entry.get("from").isJsonNull() ? " from " + entry.get("from").getAsString() : "";
                            receipts.add(new Receipt(id, name + from, amount));
                        }
                        new EconomyService(staging).settle(EconomyService.stableReference("legacy-buffer", id.toString()),
                                "legacy buffer " + id, Map.of(id, staging.account(id).pending(total)), List.of(), receipts, Set.of());
                    }
                }
            }
            Path globals = root.resolve("data").resolve(LegacyCompatibility.MOD_ID + "_mapvars.dat");
            if (Files.exists(globals)) staging.fee(java.math.BigDecimal.valueOf(NbtIo.readCompressed(globals, NbtAccounter.create(1024 * 1024)).getCompound("data").getDouble("fee")));
            staging.paydayEnabled(ledger.paydayEnabled());
            var objective = server.getScoreboard().getObjective("payday");
            if (objective != null && server.getProfileCache() != null) {
                for (var score : server.getScoreboard().listPlayerScores(objective)) {
                    var profile = server.getProfileCache().get(score.owner());
                    if (profile.isPresent()) {
                        UUID id = profile.get().getId(); account(server, id, staging);
                        new EconomyService(staging).updateAccount(id, account -> account.ticks(Math.max(0, score.value())));
                    }
                }
            }
            staging.imported(true);
            server.overworld().getDataStorage().set(Kuronomics.MODID + "_economy", staging);
        } catch (IOException | RuntimeException e) { throw new IllegalStateException("Legacy economy import failed; original files unchanged", e); }
    }
    private static void readPayments(Path directory, boolean bank, EconomyLedger ledger, MinecraftServer server) throws IOException {
        if (!Files.isDirectory(directory)) return;
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith(bank ? ".json" : "_outgoing.json")).toList()) {
                for (JsonElement raw : read(file).getAsJsonArray()) {
                    JsonObject c = raw.getAsJsonObject();
                    UUID payer = UUID.fromString(c.get(bank ? "playerId" : "payerUUID").getAsString());
                    UUID receiver = bank ? null : UUID.fromString(c.get("receiverUUID").getAsString());
                    account(server, payer, ledger); if (receiver != null) account(server, receiver, ledger);
                    ledger.addContract(new RecurringPayment(payer, receiver, Money.parse(c.get("amount").getAsString()), c.get("name").getAsString()));
                }
            }
        }
    }
    private static JsonElement read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) { return JsonParser.parseReader(reader); }
    }
    public static void join(ServerPlayer player) {
        EconomyLedger ledger = EconomyLedger.get(player.server);
        boolean imported = !ledger.contains(player.getUUID());
        account(player.server, player.getUUID(), ledger);
        if (imported) de.kuro.kuronomics.jobs.JobService.get(player.server).imported(player.getUUID(), ledger.account(player.getUUID()));
    }
}
