package de.kuro.kuronomics.jobs;

/** Only convert old job display names into stable IDs during import/load. */
public final class JobIds {
    public static String legacy(String name) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Empty saved job ID");
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.matches("[a-z0-9_][a-z0-9_.-]*")) return lower;
        return "legacy_" + java.util.UUID.nameUUIDFromBytes(lower.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString().replace("-", "");
    }
    private JobIds() {}
}
