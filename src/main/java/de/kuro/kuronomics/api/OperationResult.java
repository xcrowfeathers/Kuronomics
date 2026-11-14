package de.kuro.kuronomics.api;

/** If storage is uncertain, retry with the same reference. A new reference could pay twice. */
public record OperationResult(Status status, String message) {
    public enum Status { SUCCESS, ALREADY_APPLIED, NOT_FOUND, DENIED, INVALID, INSUFFICIENT_FUNDS, STORAGE_UNCERTAIN }
    public boolean successful() { return status == Status.SUCCESS || status == Status.ALREADY_APPLIED; }
}
