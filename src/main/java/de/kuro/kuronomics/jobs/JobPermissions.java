package de.kuro.kuronomics.jobs;

import net.minecraft.server.level.ServerPlayer;

public final class JobPermissions {
    private JobPermissions() {}

    private static boolean employmentOfficer(ServerPlayer player) {
        var job = JobService.get(player.server).employment(player);
        return job != null && job.id().equals("employment_officer");
    }
    public static boolean manage(ServerPlayer player) { return player.hasPermissions(3) || employmentOfficer(player); }
    public static boolean edit(ServerPlayer player) { return player.hasPermissions(3) || employmentOfficer(player); }
}
