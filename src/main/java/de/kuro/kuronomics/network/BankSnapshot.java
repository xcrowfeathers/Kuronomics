package de.kuro.kuronomics.network;

import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Send eight overview totals or five grouped account rows, never the whole history. */
public record BankSnapshot(int container,String position,int rank,long balance,String dailyNet,boolean limited,
        long payRemaining,long debitRemaining,String status,String category,boolean window,int historyDays,int maximumAccounts,
        boolean partial,int page,int total,List<Metric> metrics,List<Row> history) implements CustomPacketPayload {
    public record Metric(String id,String label,String incoming,String outgoing,String net,long payments) { }
    public record Row(UUID owner,String kind,String name,String incoming,String outgoing,String net,long payments,boolean archived) { }
    public static final Type<BankSnapshot> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("kuronomics","bank_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf,BankSnapshot> CODEC=StreamCodec.of((b,s)->{
        b.writeVarInt(s.container);b.writeUtf(s.position,128);b.writeVarInt(s.rank);b.writeLong(s.balance);b.writeUtf(s.dailyNet,96);b.writeBoolean(s.limited);
        b.writeLong(s.payRemaining);b.writeLong(s.debitRemaining);b.writeUtf(s.status,256);b.writeUtf(s.category,16);b.writeBoolean(s.window);
        b.writeVarInt(s.historyDays);b.writeVarInt(s.maximumAccounts);b.writeBoolean(s.partial);b.writeVarInt(s.page);b.writeVarInt(s.total);
        b.writeVarInt(s.metrics.size());for(var m:s.metrics){b.writeUtf(m.id,16);b.writeUtf(m.label,32);b.writeUtf(m.incoming,96);b.writeUtf(m.outgoing,96);b.writeUtf(m.net,96);b.writeLong(m.payments);}
        b.writeVarInt(s.history.size());for(var row:s.history){b.writeUUID(row.owner);b.writeUtf(row.kind,16);b.writeUtf(row.name,160);b.writeUtf(row.incoming,96);b.writeUtf(row.outgoing,96);b.writeUtf(row.net,96);b.writeLong(row.payments);b.writeBoolean(row.archived);}
    },b->{
        int id=b.readVarInt();String position=b.readUtf(128);int rank=b.readVarInt();long balance=b.readLong();String net=b.readUtf(96);boolean limited=b.readBoolean();
        long pay=b.readLong(),debit=b.readLong();String status=b.readUtf(256),category=b.readUtf(16);boolean window=b.readBoolean();
        int days=b.readVarInt(),maximum=b.readVarInt();boolean partial=b.readBoolean();int page=b.readVarInt(),total=b.readVarInt();
        int count=b.readVarInt();if(count<0||count>8||days<1||days>30||maximum<1||maximum>10000||page<0||total<0||total>10001)throw new IllegalArgumentException("Invalid bank overview");
        var metrics=new ArrayList<Metric>();for(int i=0;i<count;i++)metrics.add(new Metric(b.readUtf(16),b.readUtf(32),b.readUtf(96),b.readUtf(96),b.readUtf(96),b.readLong()));
        count=b.readVarInt();if(count<0||count>5)throw new IllegalArgumentException("Invalid bank history page");
        var rows=new ArrayList<Row>();for(int i=0;i<count;i++)rows.add(new Row(b.readUUID(),b.readUtf(16),b.readUtf(160),b.readUtf(96),b.readUtf(96),b.readUtf(96),b.readLong(),b.readBoolean()));
        return new BankSnapshot(id,position,rank,balance,net,limited,pay,debit,status,category,window,days,maximum,partial,page,total,List.copyOf(metrics),List.copyOf(rows));
    });
    public static BankSnapshot empty(int id){return new BankSnapshot(id,"",0,0,"$0.00",false,0,0,"","",false,7,2048,false,0,0,List.of(),List.of());}
    @Override public Type<BankSnapshot> type(){return TYPE;}
}
