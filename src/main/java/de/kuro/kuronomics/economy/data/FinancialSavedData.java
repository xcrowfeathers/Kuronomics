package de.kuro.kuronomics.economy.data;

import de.kuro.kuronomics.economy.FinancialWriter;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.world.level.saveddata.SavedData;

/** Vanilla autosaves must use the same write order as financial saves. */
public abstract class FinancialSavedData extends SavedData {
    @Override public void save(java.io.File file, HolderLookup.Provider registries) {
        if (!isDirty()) return;
        CompoundTag data = save(new CompoundTag(), registries), frozenData = new CompoundTag();
        // FinancialHistory already returns its own arrays. Only copy sections that contain item components;
        // don't copy the large retry-guard array a second time.
        for (String key : data.getAllKeys()) frozenData.put(key, key.equals("historyRetention") ? data.get(key) : data.get(key).copy());
        CompoundTag root = new CompoundTag(); root.put("data", frozenData); NbtUtils.addCurrentDataVersion(root);
        FinancialWriter.write(file.toPath(), () -> root); setDirty(false);
    }
}
