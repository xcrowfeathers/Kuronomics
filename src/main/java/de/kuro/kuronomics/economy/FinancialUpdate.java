package de.kuro.kuronomics.economy;

import net.minecraft.nbt.*;

/**
 * The writer owns this update. Don't change its data after handing it over.
 * If the writer's base doesn't match, use the normal diff, including on first save and export.
 */
public final class FinancialUpdate {
    private final CompoundTag base, snapshot, patch;
    private FinancialUpdate(CompoundTag base, CompoundTag snapshot, CompoundTag patch) {
        this.base = base; this.snapshot = snapshot; this.patch = patch;
    }
    CompoundTag base() { return base; }
    CompoundTag snapshot() { return snapshot; }
    CompoundTag patch() { return patch; }
    public static FinancialUpdate between(CompoundTag base, CompoundTag snapshot) {
        return new FinancialUpdate(base, snapshot, base == null ? null : FinancialJournal.diff(base, snapshot));
    }
}
