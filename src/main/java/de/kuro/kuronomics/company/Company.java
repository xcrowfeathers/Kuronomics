package de.kuro.kuronomics.company;

import java.text.Normalizer;
import java.util.*;

public record Company(UUID id, String name, long createdAt, UUID ceo,
                      Map<UUID, Member> members, Status status, Statement lastPayday, Bankruptcy bankruptcy) {
    public Company(UUID id, String name, long createdAt, UUID ceo, Map<UUID, Member> members, Status status, Statement lastPayday) {
        this(id, name, createdAt, ceo, members, status, lastPayday, null);
    }
    /** Records that the player's bankruptcy penalty was already charged in the same commit. */
    public record Bankruptcy(long debt, long penalty, long time, long bankLoanRemainder) {
        public Bankruptcy(long debt,long penalty,long time){this(debt,penalty,time,0);}
        public Bankruptcy { if (debt <= 0 || penalty < 0 || time < 0 || bankLoanRemainder < 0) throw new IllegalArgumentException("Invalid bankruptcy record"); }
    }
    public enum Rank { EMPLOYEE, MANAGER, DIRECTOR, CEO }
    public enum Status { ACTIVE, CLOSING, CLOSED }
    public record Member(Rank rank, long salary) {
        public Member { Objects.requireNonNull(rank); if (salary < 0) throw new IllegalArgumentException("Negative salary"); }
    }
    public record Line(String kind, String label, long amount, de.kuro.kuronomics.economy.EconomicOwner party) {
        public Line(String kind, String label, long amount) { this(kind, label, amount, null); }
        public Line { Objects.requireNonNull(kind); Objects.requireNonNull(label); if (amount < 0 || !Set.of("INCOMING", "OUTGOING", "WARNING").contains(kind)) throw new IllegalArgumentException("Invalid Company statement line"); }
    }
    public record Statement(long cycleDay, long cutoff, long opening, long released, long closing, List<Line> lines, boolean debug) {
        public Statement(long cycleDay, long cutoff, long opening, long released, long closing, List<Line> lines) { this(cycleDay, cutoff, opening, released, closing, lines, false); }
        public Statement { lines = List.copyOf(lines); if (cycleDay < 0 || cutoff < 0 || released < 0) throw new IllegalArgumentException("Invalid Company statement"); }
    }
    public Company {
        Objects.requireNonNull(id); Objects.requireNonNull(ceo); Objects.requireNonNull(status);
        name = displayName(name); members = Map.copyOf(members);
        if (createdAt < 0) throw new IllegalArgumentException("Invalid creation time");
        if (bankruptcy != null && status == Status.ACTIVE) throw new IllegalArgumentException("Bankrupt Company is still active");
        if (status != Status.CLOSED && (members.get(ceo) == null || members.get(ceo).rank() != Rank.CEO
                || members.values().stream().filter(m -> m.rank() == Rank.CEO).count() != 1))
            throw new IllegalArgumentException("A Company must have exactly one CEO");
        if (status == Status.CLOSED && !members.isEmpty()) throw new IllegalArgumentException("Closed Company retains members");
    }
    public static String displayName(String value) {
        String result = Normalizer.normalize(Objects.requireNonNull(value), Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ");
        if (result.length() < 2 || result.length() > 48 || result.codePoints().anyMatch(c -> Character.isISOControl(c) || c == '\u00a7'))
            throw new IllegalArgumentException("Company names must contain 2–48 printable characters");
        return result;
    }
    public static String normalizedName(String value) { return displayName(value).toLowerCase(Locale.ROOT); }
    public Company membership(Map<UUID, Member> value, UUID leader) { return new Company(id, name, createdAt, leader, value, status, lastPayday, bankruptcy); }
    public Company status(Status value) { return new Company(id, name, createdAt, ceo, value == Status.CLOSED ? Map.of() : members, value, lastPayday, bankruptcy); }
    public Company statement(Statement value) { return new Company(id, name, createdAt, ceo, members, status, value, bankruptcy); }
    public Company bankrupt(Bankruptcy value) { return new Company(id, name, createdAt, ceo, members, Status.CLOSING, lastPayday, value); }
}
