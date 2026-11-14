package de.kuro.kuronomics.jobs;

import com.mojang.serialization.Codec;
import de.kuro.kuronomics.api.*;
import de.kuro.kuronomics.economy.ApiAccess;
import java.util.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;

/** Register task types here. Live task lookup indexes stay in JobRegistry. */
public final class TaskExtensions implements JobTaskApi {
    public static final TaskExtensions API = new TaskExtensions();
    private final Map<ResourceLocation, Entry<?, ?>> types = new LinkedHashMap<>();
    private boolean frozen;
    record Entry<C, A>(JobTaskType<C, A> type, TaskTypes.Type builtin) {
        java.util.function.Predicate<JobTaskType.Trigger<?>> matcher(C config) {
            return trigger -> type.matches(config, cast(trigger));
        }
        @SuppressWarnings("unchecked") private JobTaskType.Trigger<A> cast(JobTaskType.Trigger<?> trigger) {
            if (!type.actionClass().isInstance(trigger.action())) throw new IllegalArgumentException("Wrong task action class");
            return (JobTaskType.Trigger<A>) trigger;
        }
        ResourceLocation target(JobTaskType.Trigger<?> trigger) { return type.target(cast(trigger).action()); }
    }
    private TaskExtensions() {
        for (String id : TaskTypes.ids()) {
            var builtin = TaskTypes.require(id);
            types.put(ResourceLocation.fromNamespaceAndPath("kuronomics", id), new Entry<>(new Builtin(builtin), builtin));
        }
    }
    private record Builtin(TaskTypes.Type descriptor) implements JobTaskType<String, ResourceLocation> {
        @Override public Codec<String> codec() { return Codec.STRING.fieldOf("target").codec(); }
        @Override public Class<ResourceLocation> actionClass() { return ResourceLocation.class; }
        @Override public Set<ResourceLocation> targets(String config, RegistryAccess registries) {
            var registry = switch (descriptor.target()) {
                case BLOCK -> net.minecraft.core.registries.BuiltInRegistries.BLOCK;
                case ITEM -> net.minecraft.core.registries.BuiltInRegistries.ITEM;
                case ENTITY -> net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE;
            };
            var result = new HashSet<ResourceLocation>();
            resolveKeys(registry, config, result); return Set.copyOf(result);
        }
        private static <T> void resolveKeys(net.minecraft.core.Registry<T> registry, String config, Set<ResourceLocation> output) {
            for (T value : JobsConfig.resolve(registry, config, "task target")) output.add(registry.getKey(value));
        }
        @Override public ResourceLocation target(ResourceLocation action) { return action; }
        @Override public boolean matches(String config, JobTaskType.Trigger<ResourceLocation> trigger) { return true; }
    }
    @Override public synchronized <C, A> void register(ResourceLocation id, JobTaskType<C, A> type) {
        Objects.requireNonNull(id); Objects.requireNonNull(type);
        if (frozen) throw new IllegalStateException("Job task registration closed before server datapack loading");
        Objects.requireNonNull(type.codec()); Objects.requireNonNull(type.actionClass());
        if (types.putIfAbsent(id, new Entry<>(type, null)) != null) throw new IllegalArgumentException("Duplicate job task type: " + id);
    }
    synchronized void freeze() { frozen = true; }
    synchronized Entry<?, ?> require(ResourceLocation id) {
        var entry = types.get(id);
        if (entry == null) throw new IllegalArgumentException("Unknown task type " + id + "; install its addon before loading this pack");
        return entry;
    }
    @Override public synchronized Set<ResourceLocation> types() { return Set.copyOf(types.keySet()); }
    /** Only create the reference if it's missing. Delete it and reload to generate a fresh copy. */
    public synchronized void writeReference(JobRegistry registry) {
        var path = de.kuro.kuronomics.config.EconomyConfig.directory().resolve("job_tasks_reference.md");
        if (java.nio.file.Files.exists(path)) return;
        var text = new StringBuilder("# Available job tasks\n\n");
        text.append("## Task types\n\n");
        text.append("| type | target registry | natural_only supported |\n|---|---|---|\n");
        TaskTypes.ids().stream().sorted().map(TaskTypes::require).forEach(type ->
                text.append("| ").append(type.id()).append(" | ").append(type.target()).append(" | ").append(type.natural()).append(" |\n"));
        text.append("\nExample:\n\n```toml\n[[jobs.tasks]]\nid = \"coal\"\ntype = \"break_block\"\ntarget = \"minecraft:coal_ore\"\ngain = 2\nnatural_only = true\n```\n\n");
        text.append("## Registered datapack task types\n\nPlace definitions at data/<namespace>/kuronomics/job_tasks/<name>.json. Addon-mods register types, datapacks configure definitions of registered types. Custom config fields are defined by the addon\n\n");
        types.keySet().stream().sorted().forEach(id -> text.append("- ").append(id).append(types.get(id).builtin()==null?" (addon)":"").append('\n'));
        text.append("\nExample JSON:\n\n```json\n{\n  \"type\": \"kuronomics:break_block\",\n  \"job\": \"miner\",\n  \"config\": {\"target\": \"minecraft:coal_ore\"},\n  \"reward\": 2,\n  \"natural_only\": true\n}\n```\n\n");
        text.append("## Active datapack task definitions\n\n| task ID | type | job | reward | cap per payday |\n|---|---|---|---|---|\n");
        registry.tasks().stream().sorted(Comparator.comparing(task -> task.id().toString())).forEach(task ->
                text.append("| ").append(task.id()).append(" | ").append(task.type()).append(" | ").append(task.job()).append(" | ")
                        .append(de.kuro.kuronomics.economy.Money.format(task.rewardCents())).append(" | ")
                        .append(task.capCents()==Long.MAX_VALUE?"Unlimited":de.kuro.kuronomics.economy.Money.format(task.capCents())).append(" |\n"));
        try {
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(path, text.toString(), java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (java.nio.file.FileAlreadyExistsException raced) { /* Someone else created the file first. Keep their copy. */
        } catch (java.io.IOException failure) { com.mojang.logging.LogUtils.getLogger().error("Could not write job task reference", failure); }
    }
    @Override public Collection<Task> tasks(MinecraftServer server) { ApiAccess.thread(server); return JobService.get(server).registry().tasks(); }
    @Override public Completion submit(ResourceLocation id, UUID occurrence, JobTaskType.Trigger<?> trigger) {
        Objects.requireNonNull(trigger); var player = trigger.player(); ApiAccess.actor(player.server, player);
        Objects.requireNonNull(occurrence);
        Entry<?, ?> entry = require(id);
        if (entry.builtin() != null) return new Completion(Status.REJECTED, 0);
        try { return JobService.get(player.server).completeCustom(id, occurrence, entry.target(trigger), trigger); }
        catch (RuntimeException invalid) {
            com.mojang.logging.LogUtils.getLogger().error("Custom job task {} rejected", id, invalid);
            return new Completion(Status.REJECTED, 0);
        }
    }
}
