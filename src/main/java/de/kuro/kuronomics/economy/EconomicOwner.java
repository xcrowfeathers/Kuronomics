package de.kuro.kuronomics.economy;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

/** Identifies whose money this is. Don't use a fake player or a personal command's selected account as a proxy. */
public record EconomicOwner(Type type, UUID id) implements Comparable<EconomicOwner> {
    public enum Type { PLAYER, COMPANY, STATE }
    public static final EconomicOwner STATE = new EconomicOwner(Type.STATE, null);
    public EconomicOwner {
        Objects.requireNonNull(type);
        if ((type == Type.STATE) != (id == null)) throw new IllegalArgumentException("Invalid economic owner");
    }
    public static EconomicOwner player(UUID id) { return new EconomicOwner(Type.PLAYER, Objects.requireNonNull(id)); }
    public static EconomicOwner company(UUID id) { return new EconomicOwner(Type.COMPANY, Objects.requireNonNull(id)); }
    public static EconomicOwner personalOrState(UUID id) { return id == null ? STATE : player(id); }
    public boolean isPlayer() { return type == Type.PLAYER; }
    public boolean isCompany() { return type == Type.COMPANY; }
    public UUID playerId() {
        if (!isPlayer()) throw new IllegalArgumentException("This economic owner is not a player");
        return id;
    }
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.putString("type", type.name());
        if (id != null) tag.putUUID("id", id); return tag;
    }
    public static EconomicOwner load(CompoundTag tag) {
        Type type = Type.valueOf(tag.getString("type"));
        return new EconomicOwner(type, type == Type.STATE ? null : tag.getUUID("id"));
    }
    @Override public int compareTo(EconomicOwner other) { return toString().compareTo(other.toString()); }
    @Override public String toString() { return type + (id == null ? "" : ":" + id); }
}
