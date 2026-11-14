package de.kuro.kuronomics.network;

import de.kuro.kuronomics.menu.MachineMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Only send to an open menu when its displayed account or machine values change. */
public record MachineSnapshot(int container, long balance, long profit, long price, boolean suspended, String reason) implements CustomPacketPayload {
    public static final Type<MachineSnapshot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("kuronomics", "machine_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MachineSnapshot> CODEC = StreamCodec.of(
            (b, m) -> { b.writeVarInt(m.container); b.writeLong(m.balance); b.writeLong(m.profit); b.writeLong(m.price); b.writeBoolean(m.suspended); b.writeUtf(m.reason, 2048); },
            b -> new MachineSnapshot(b.readVarInt(), b.readLong(), b.readLong(), b.readLong(), b.readBoolean(), b.readUtf(2048)));
    @Override public Type<MachineSnapshot> type() { return TYPE; }
    public void apply(MachineMenu menu) {
        menu.balance = balance; menu.profit = profit; menu.price = price; menu.suspended = suspended; menu.suspensionReason = reason;
    }
}
