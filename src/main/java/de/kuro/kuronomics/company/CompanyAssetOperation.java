package de.kuro.kuronomics.company;

import de.kuro.kuronomics.economy.EconomicOwner;
import java.util.*;

/** Save the operation in the ledger before changing anything in WorldGuard. */
public record CompanyAssetOperation(UUID id, UUID actor, UUID company, String property, Kind kind,
                                    EconomicOwner target, long amount, long createdAt,
                                    CompanyAccess previous, CompanyAccess desired) {
    public enum Kind { BUY, SELL, TRANSFER, RETURN_TO_STATE, SYNC, RENT }
    /** Keep retry fingerprints small, even when a property has thousands of staff entries. */
    public String fingerprint() {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            try (var out = new java.io.DataOutputStream(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest))) {
                uuid(out, id); out.writeBoolean(actor != null); if (actor != null) uuid(out, actor); uuid(out, company);
                byte[] name = property.getBytes(java.nio.charset.StandardCharsets.UTF_8); out.writeInt(name.length); out.write(name);
                out.writeUTF(kind.name()); out.writeUTF(target.type().name()); out.writeBoolean(target.id() != null); if (target.id() != null) uuid(out, target.id());
                out.writeLong(amount); out.writeLong(createdAt); access(out, previous); access(out, desired);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void uuid(java.io.DataOutputStream out, UUID id) throws java.io.IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static void access(java.io.DataOutputStream out, CompanyAccess access) throws java.io.IOException {
        uuid(out, access.company()); out.writeLong(access.purchaseCost());
        out.writeInt(access.owners().size()); for (UUID id : access.owners().stream().sorted().toList()) uuid(out, id);
        out.writeInt(access.members().size()); for (UUID id : access.members().stream().sorted().toList()) uuid(out, id);
    }
    public CompanyAssetOperation {
        Objects.requireNonNull(id); Objects.requireNonNull(company); Objects.requireNonNull(property);
        Objects.requireNonNull(kind); Objects.requireNonNull(target); Objects.requireNonNull(previous); Objects.requireNonNull(desired);
        if (amount < 0 || createdAt < 0) throw new IllegalArgumentException("Invalid property operation");
        if (property.isBlank() || !company.equals(previous.company()) || !company.equals(desired.company())
                || (kind == Kind.BUY || kind == Kind.SYNC || kind == Kind.RENT) && !target.equals(EconomicOwner.company(company))
                || (kind == Kind.SELL || kind == Kind.RETURN_TO_STATE) && !target.equals(EconomicOwner.STATE)
                || kind == Kind.TRANSFER && !target.isPlayer() || kind == Kind.RENT && (actor == null || amount == 0))
            throw new IllegalArgumentException("Invalid property operation identity/target");
    }
}
