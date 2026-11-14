package de.kuro.kuronomics.economy.data;

public record Account(long balance, long pendingIncome, long playTicks, long paydaySequence,
                      String job, String entry, boolean suspended, String suspensionReason, long jobEarnings, java.util.Map<String, Long> taskEarnings) {
    public Account(long balance, long pendingIncome, long playTicks, long paydaySequence, String job, String entry, boolean suspended, String reason) {
        this(balance, pendingIncome, playTicks, paydaySequence, job, entry, suspended, reason, 0, java.util.Map.of());
    }
    public Account {
        taskEarnings = java.util.Map.copyOf(taskEarnings);
        if (jobEarnings < 0 || taskEarnings.values().stream().anyMatch(v -> v < 0)) throw new IllegalArgumentException("Invalid job earnings");
        if (pendingIncome < 0 || playTicks < 0 || paydaySequence < 0 || job == null || job.isBlank() || entry == null || suspensionReason == null) throw new IllegalArgumentException("Invalid account data");
    }
    private static final Account EMPTY = new Account(0, 0, 0, 0, "unemployed", "\"no entry\"", false, "");
    public static Account empty() { return EMPTY; }
    public Account balance(long value) { return new Account(value, pendingIncome, playTicks, paydaySequence, job, entry, suspended, suspensionReason, jobEarnings, taskEarnings); }
    public Account pending(long value) { return new Account(balance, value, playTicks, paydaySequence, job, entry, suspended, suspensionReason, jobEarnings, taskEarnings); }
    public Account ticks(long value) { return new Account(balance, pendingIncome, value, paydaySequence, job, entry, suspended, suspensionReason, jobEarnings, taskEarnings); }
    public Account job(String value) { return new Account(balance, pendingIncome, playTicks, paydaySequence, value, entry, suspended, suspensionReason, value.equals(job) ? jobEarnings : 0, value.equals(job) ? taskEarnings : java.util.Map.of()); }
    public Account entry(String value) { return new Account(balance, pendingIncome, playTicks, paydaySequence, job, value, suspended, suspensionReason, jobEarnings, taskEarnings); }
    public Account suspension(boolean value, String reason) { return new Account(balance, pendingIncome, playTicks, paydaySequence, job, entry, value, reason, jobEarnings, taskEarnings); }
    public Account earnings(long amount, java.util.Map<String, Long> counters) { return new Account(balance, pendingIncome, playTicks, paydaySequence, job, entry, suspended, suspensionReason, amount, counters); }
    public Account paid(long value) { return new Account(value, 0, 0, Math.incrementExact(paydaySequence), job, entry, suspended, suspensionReason, 0, java.util.Map.of()); }
}
