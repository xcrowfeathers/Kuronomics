package de.kuro.kuronomics.network;

import de.kuro.kuronomics.Kuronomics;
import java.util.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record JobSnapshot(int container, String view, int page, int total, String status, String currentJob, String currentJobId,
                          long terminalRevision, long registryRevision, long occupancyRevision, long applicationRevision,
                          List<OfferView> offers, List<ApplicationView> applications, String selectedJob, UUID selectedApplication)
        implements CustomPacketPayload {
    public record OfferView(String id, String name, String mode, long base, long max, int slots, int occupied,
                            String availability, String description) {}
    public record ApplicationView(UUID id, String applicantName, String jobName, String text, long submittedAt) {}
    public static final Type<JobSnapshot> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Kuronomics.MODID, "job_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, JobSnapshot> CODEC = StreamCodec.of(JobSnapshot::write, JobSnapshot::read);
    private static void write(RegistryFriendlyByteBuf b, JobSnapshot s) {
        b.writeVarInt(s.container); b.writeUtf(s.view, 24); b.writeVarInt(s.page); b.writeVarInt(s.total);
        b.writeUtf(s.status, 256); b.writeUtf(s.currentJob, 2048); b.writeUtf(s.currentJobId, 512);
        b.writeLong(s.terminalRevision); b.writeLong(s.registryRevision); b.writeLong(s.occupancyRevision); b.writeLong(s.applicationRevision);
        b.writeVarInt(s.offers.size()); for (OfferView o : s.offers) {
            b.writeUtf(o.id, 512); b.writeUtf(o.name, 2048); b.writeUtf(o.mode, 24); b.writeLong(o.base); b.writeLong(o.max);
            b.writeVarInt(o.slots); b.writeVarInt(o.occupied); b.writeUtf(o.availability, 32); b.writeUtf(o.description, 1000);
        }
        b.writeVarInt(s.applications.size()); for (ApplicationView a : s.applications) {
            b.writeUUID(a.id); b.writeUtf(a.applicantName, 64); b.writeUtf(a.jobName, 2048);
            b.writeUtf(a.text, 2000); b.writeLong(a.submittedAt);
        }
        b.writeUtf(s.selectedJob, 512); b.writeUUID(s.selectedApplication);
    }
    private static JobSnapshot read(RegistryFriendlyByteBuf b) {
        int container = b.readVarInt(); String view = b.readUtf(24); int page = b.readVarInt(), total = b.readVarInt();
        String status = b.readUtf(256), currentJob = b.readUtf(2048), currentJobId = b.readUtf(512);
        long terminalRevision = b.readLong(), registryRevision = b.readLong(), occupancyRevision = b.readLong(), applicationRevision = b.readLong();
        int count = b.readVarInt(); if (count < 0 || count > 8) throw new IllegalArgumentException("Invalid job snapshot");
        List<OfferView> offers = new ArrayList<>(); for (int i = 0; i < count; i++) offers.add(new OfferView(
                b.readUtf(512), b.readUtf(2048), b.readUtf(24), b.readLong(), b.readLong(), b.readVarInt(), b.readVarInt(), b.readUtf(32), b.readUtf(1000)));
        count = b.readVarInt(); if (count < 0 || count > 8) throw new IllegalArgumentException("Invalid application snapshot");
        List<ApplicationView> applications = new ArrayList<>(); for (int i = 0; i < count; i++) applications.add(new ApplicationView(
                b.readUUID(), b.readUtf(64), b.readUtf(2048), b.readUtf(2000), b.readLong()));
        return new JobSnapshot(container, view, page, total, status, currentJob, currentJobId, terminalRevision, registryRevision,
                occupancyRevision, applicationRevision, offers, applications, b.readUtf(512), b.readUUID());
    }
    @Override public Type<JobSnapshot> type() { return TYPE; }
}
