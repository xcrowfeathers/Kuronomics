package de.kuro.kuronomics.util;

import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.server.MinecraftServer;

public final class PlayerNames {
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE);
    private PlayerNames() {}
    public static String owner(MinecraftServer server, de.kuro.kuronomics.economy.EconomicOwner owner) {
        return switch (owner.type()) {
            case STATE -> "Bank";
            case PLAYER -> get(server, owner.id());
            case COMPANY -> {
                var company = de.kuro.kuronomics.economy.data.EconomyLedger.get(server).companies().company(owner.id());
                yield company == null ? "Unknown Company" : company.name();
            }
        };
    }
    public static String get(MinecraftServer server, UUID id) {
        if (id == null) return "Bank";
        var player = server.getPlayerList().getPlayer(id);
        if (player != null) return player.getGameProfile().getName();
        String remembered = de.kuro.kuronomics.mailbox.MailboxStore.get(server).knownName(id);
        if (remembered != null) return remembered;
        var cache = server.getProfileCache();
        return cache == null ? "Unknown player" : cache.get(id).map(com.mojang.authlib.GameProfile::getName).orElse("Unknown player");
    }
    public static String resolve(MinecraftServer server, String summary) {
        return resolve(summary, id -> get(server, id));
    }
    public static String resolve(String summary, java.util.function.Function<UUID, String> resolver) {
        return UUID_TEXT.matcher(summary).replaceAll(match -> java.util.regex.Matcher.quoteReplacement(resolver.apply(UUID.fromString(match.group()))));
    }
}
