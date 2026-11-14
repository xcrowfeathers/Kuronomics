package de.kuro.kuronomics.block;

import de.kuro.kuronomics.menu.JobMenu;
import de.kuro.kuronomics.registry.ModContent;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Only save the terminal's display and availability here. Job definitions live elsewhere. */
public final class JobTerminalBlockEntity extends BlockEntity {
    public enum Availability { OPEN, APPLICATION_REQUIRED }
    public record Offer(String jobId, String description, Availability mode) {}
    private final List<Offer> offers = new ArrayList<>();
    private long revision;
    public JobTerminalBlockEntity(BlockPos pos, BlockState state) { super(ModContent.JOB_TERMINAL_ENTITY.get(), pos, state); }
    public List<Offer> offers() { return List.copyOf(offers); }
    public long revision() { return revision; }
    public Offer offer(String jobId) { return offers.stream().filter(o -> o.jobId().equals(jobId)).findFirst().orElse(null); }
    public void add(String jobId) {
        if (offer(jobId) != null) throw new IllegalArgumentException("Job is already offered here.");
        if (offers.size() >= 128) throw new IllegalArgumentException("Terminal offer limit reached.");
        offers.add(new Offer(jobId, "", Availability.OPEN)); changed();
    }
    public void remove(String jobId) { if (offers.removeIf(o -> o.jobId().equals(jobId))) changed(); }
    public void update(String jobId, String description, Availability mode) {
        if (description.length() > 1000) throw new IllegalArgumentException("Description is too long.");
        for (int i = 0; i < offers.size(); i++) if (offers.get(i).jobId().equals(jobId)) {
            Offer next = new Offer(jobId, description.strip(), mode);
            if (!offers.get(i).equals(next)) { offers.set(i, next); changed(); } return;
        }
        throw new IllegalArgumentException("Offer no longer exists.");
    }
    public void move(String jobId, int direction) {
        for (int i = 0; i < offers.size(); i++) if (offers.get(i).jobId().equals(jobId)) {
            int to = i + direction; if (to < 0 || to >= offers.size()) return;
            Collections.swap(offers, i, to); changed(); return;
        }
        throw new IllegalArgumentException("Offer no longer exists.");
    }
    private void changed() { revision++; setChanged(); if (level instanceof ServerLevel serverLevel) JobMenu.refreshTerminal(serverLevel.getServer(), this); }
    public boolean isValidTerminal(ServerLevel level) {
        return this.level == level && !isRemoved() && level.isLoaded(worldPosition) && level.getBlockEntity(worldPosition) == this
                && level.getBlockState(worldPosition).is(ModContent.JOB_TERMINAL_BLOCK.get());
    }
    public void open(ServerPlayer player, boolean editor) { if (isValidTerminal(player.serverLevel())) JobMenu.open(player, this, editor); }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries); tag.putLong("Revision", revision);
        ListTag list = new ListTag(); for (Offer offer : offers) {
            CompoundTag row = new CompoundTag(); row.putString("JobId", offer.jobId());
            row.putString("Description", offer.description()); row.putString("Availability", offer.mode().name()); list.add(row);
        }
        tag.put("Offers", list);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries); revision = tag.getLong("Revision"); offers.clear();
        for (Tag raw : tag.getList("Offers", Tag.TAG_COMPOUND)) {
            CompoundTag row = (CompoundTag) raw;
            String id = row.getString("JobId"), description = row.getString("Description");
            if (id.isBlank() || description.length() > 1000 || offer(id) != null || offers.size() >= 128) continue;
            Availability mode; try { mode = Availability.valueOf(row.getString("Availability")); }
            catch (IllegalArgumentException invalid) { mode = Availability.OPEN; }
            offers.add(new Offer(id, description, mode));
        }
    }
}
