package de.kuro.kuronomics.economy;

import net.minecraft.server.level.ServerPlayer;
import de.kuro.kuronomics.economy.mixin.PlayerListSaveAccess;

/** Save this player's data, stats and advancements, without saving every other online player too. */
public final class PlayerPersistence {
    private static final ThreadLocal<ServerPlayer> financialSave = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> saved = new ThreadLocal<>();
    public static boolean financialSave(net.minecraft.world.entity.player.Player player) { return financialSave.get() == player; }
    public static void save(ServerPlayer player) {
        if (!player.server.isSameThread()) throw new IllegalStateException("Player save off server thread");
        // NeoForge queues SavedData writes. Wait for them before saving inventory recovery markers.
        if (financialSave.get() != null) throw new IllegalStateException("Nested financial player save");
        financialSave.set(player); saved.set(false);
        try {
            FinancialWriter.await(player.server);
            ((PlayerListSaveAccess) player.server.getPlayerList()).kuronomy$savePlayer(player);
            if (!saved.get()) throw new IllegalStateException("Player inventory checkpoint was not saved");
        } catch (RuntimeException failure) {
            // Don't let delivered items or cash move before they're saved while the journal still says
            // delivery is pending.
            // On reconnect, load the saved inventory and let recovery finish the transaction.
            if (player.connection != null && player.connection.getConnection().isConnected())
                player.connection.disconnect(net.minecraft.network.chat.Component.literal("Your inventory could not be saved. Please reconnect after the server storage issue is resolved; your transaction will recover."));
            throw failure;
        } finally { financialSave.remove(); saved.remove(); }
    }
    /**
     * Vanilla player saves swallow I/O errors. Financial saves need an atomic, forced write that throws
     * on failure, so the service can't mark delivery as finished too soon.
     */
    public static void writePlayerData(ServerPlayer player, java.io.File directory) {
        java.nio.file.Path temporary = null;
        try {
            var root = directory.toPath(); java.nio.file.Files.createDirectories(root);
            temporary = java.nio.file.Files.createTempFile(root, player.getStringUUID() + "-financial-", ".dat");
            var tag = player.saveWithoutId(new net.minecraft.nbt.CompoundTag());
            try (var channel = java.nio.channels.FileChannel.open(temporary, java.nio.file.StandardOpenOption.WRITE)) {
                var bytes = new java.io.ByteArrayOutputStream(); net.minecraft.nbt.NbtIo.writeCompressed(tag, bytes);
                var buffer = java.nio.ByteBuffer.wrap(bytes.toByteArray()); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            java.nio.file.Files.move(temporary, root.resolve(player.getStringUUID() + ".dat"),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            if (!System.getProperty("os.name", "").startsWith("Windows"))
                try (var channel = java.nio.channels.FileChannel.open(root, java.nio.file.StandardOpenOption.READ)) { channel.force(true); }
            net.neoforged.neoforge.event.EventHooks.firePlayerSavingEvent(player, directory, player.getStringUUID());
            saved.set(true);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("Player inventory checkpoint failed; transaction remains pending", failure);
        } finally {
            if (temporary != null) try { java.nio.file.Files.deleteIfExists(temporary); } catch (java.io.IOException ignored) {}
        }
    }
    private PlayerPersistence() {}
}
