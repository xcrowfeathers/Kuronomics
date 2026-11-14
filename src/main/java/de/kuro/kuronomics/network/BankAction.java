package de.kuro.kuronomics.network;

import java.util.UUID;
import de.kuro.kuronomics.menu.CentralBankMenu;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

public record BankAction(int container, String action, UUID request) implements CustomPacketPayload {
    public static final Type<BankAction> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("kuronomics", "bank_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BankAction> CODEC = StreamCodec.of((b,a) -> {
        b.writeVarInt(a.container); b.writeUtf(a.action,16); b.writeUUID(a.request);
    }, b -> new BankAction(b.readVarInt(),b.readUtf(16),b.readUUID()));
    public static void register(RegisterPayloadHandlersEvent event) {
        var r=event.registrar("4");
        r.playToClient(BankSnapshot.TYPE,BankSnapshot.CODEC,(m,c)-> { if(c.player().containerMenu instanceof CentralBankMenu menu && menu.containerId==m.container()) menu.apply(m); });
        r.playToServer(TYPE,CODEC,(m,c)-> { if(c.player() instanceof ServerPlayer p && p.containerMenu instanceof CentralBankMenu menu && menu.containerId==m.container())
            de.kuro.kuronomics.economy.EconomyActions.submit(p,menu,m.request(),()->menu.action(p,m)); });
    }
    @Override public Type<BankAction> type() { return TYPE; }
}
