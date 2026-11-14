package de.kuro.kuronomics.jobs;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import de.kuro.kuronomics.Kuronomics;
import de.kuro.kuronomics.api.JobTaskApi;
import de.kuro.kuronomics.integration.RegionProtectionService.Capability;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.*;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.*;

/**
 * Read JSON off-thread, then compile indexes with the server's bound tags before publishing them together.
 * Keep candidates tied to their resource manager so a failed reload can't replace the working task set.
 */
@EventBusSubscriber(modid = Kuronomics.MODID)
public final class JobTaskLoader extends SimplePreparableReloadListener<Map<ResourceLocation, JsonObject>> {
    public static final String DIRECTORY = "kuronomics/job_tasks";
    private static final Map<ResourceManager, Map<ResourceLocation, JsonObject>> CANDIDATES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Set<String> FIELDS = Set.of("enabled", "type", "job", "config", "reward", "max_gain_per_payday", "natural_only", "required_tool", "required_tool_tag", "conditions");

    @SubscribeEvent public static void listeners(AddReloadListenerEvent event) {
        TaskExtensions.API.freeze(); event.addListener(new JobTaskLoader());
    }
    @SubscribeEvent public static void synced(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) return; // A player joining shouldn't rebuild task indexes.
        var server = event.getPlayerList().getServer();
        try { JobService.get(server).refreshDatapacks(); }
        catch (RuntimeException invalid) {
            com.mojang.logging.LogUtils.getLogger().error("Kuronomy job task reload rejected; previous job/task snapshot retained: {}", invalid.getMessage());
        }
    }
    @Override protected Map<ResourceLocation, JsonObject> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, JsonObject> result = new TreeMap<>();
        // listResources already picks the highest-priority resource for each path.
        manager.listResources(DIRECTORY, id -> id.getPath().endsWith(".json")).forEach((file, resource) -> {
            String path = file.getPath();
            var id = ResourceLocation.fromNamespaceAndPath(file.getNamespace(), path.substring(DIRECTORY.length() + 1, path.length() - 5));
            try (var reader = resource.openAsReader()) { result.put(id, GsonHelper.parse(reader)); }
            catch (Exception invalid) { throw new IllegalArgumentException("Job task " + id + " in pack " + resource.sourcePackId() + ": " + invalid.getMessage(), invalid); }
        });
        return Map.copyOf(result);
    }
    @Override protected void apply(Map<ResourceLocation, JsonObject> definitions, ResourceManager manager, ProfilerFiller profiler) {
        CANDIDATES.put(manager, definitions);
    }
    static JobRegistry compile(MinecraftServer server, JobRegistry base) {
        return compile(server, base, CANDIDATES.getOrDefault(server.getResourceManager(), Map.of()));
    }
    /** Compile without changing accounts. Integration checks use this path too. */
    public static JobRegistry compile(MinecraftServer server, JobRegistry base, Map<ResourceLocation, JsonObject> definitions) {
        Map<String, JobRegistry.Job> jobs = new HashMap<>(base.jobs()); Set<Object> natural = new HashSet<>(base.naturalBlocks());
        Map<String, Map<String, Map<Object, List<JobRegistry.Rule>>>> additions = new HashMap<>();
        Map<String, Set<Capability>> capabilities = new HashMap<>(); List<JobTaskApi.Task> metadata = new ArrayList<>();
        // Keep a stable order so overlapping capped bonuses have the same priority after a reload.
        for (var entry : new TreeMap<>(definitions).entrySet()) {
            ResourceLocation id = entry.getKey(); JsonObject json = entry.getValue();
            try {
                for (String field : json.keySet()) if (!FIELDS.contains(field)) throw new IllegalArgumentException("Unknown field " + field);
                if (!flag(json, "enabled", true)) continue;
                ResourceLocation typeId = ResourceLocation.parse(text(json, "type"));
                var type = TaskExtensions.API.require(typeId);
                String jobId = text(json, "job");
                if (jobId.startsWith("kuronomics:")) jobId = jobId.substring("kuronomics:".length());
                var job = jobs.get(jobId);
                if (job == null) throw new IllegalArgumentException("Unknown configured job " + jobId);
                if (job.mode() != JobRegistry.Mode.TASK_BASED) throw new IllegalArgumentException("Job " + jobId + " is not TASK_BASED");
                long reward = money(json, "reward", -1), cap = money(json, "max_gain_per_payday", Long.MAX_VALUE);
                if (reward <= 0) throw new IllegalArgumentException("reward must be positive money");
                JsonObject config = GsonHelper.getAsJsonObject(json, "config");
                boolean naturalOnly = flag(json, "natural_only", false);
                if (naturalOnly && (type.builtin() == null || !type.builtin().natural())) throw new IllegalArgumentException("natural_only is only supported for break_block");
                if (type.builtin() != null && !config.keySet().equals(Set.of("target"))) throw new IllegalArgumentException("Built-in config requires only target");
                Set<Item> tools = tools(json);
                var compiled = decode(server, type, config);
                var conditions = TaskConditions.compile(json, base.jobs().keySet());
                var matcher = type.builtin() == null ? compiled.matcher() : null;
                var rule = new JobRegistry.Rule("kuronomics:datapack_task:" + id, reward, cap, naturalOnly, tools,
                        matcher, conditions);
                String action = type.builtin() == null ? typeId.toString() : type.builtin().id();
                var index = additions.computeIfAbsent(jobId, unused -> new HashMap<>()).computeIfAbsent(action, unused -> new HashMap<>());
                for (ResourceLocation key : compiled.targets()) {
                    Object target = type.builtin() == null ? key : switch (type.builtin().target()) {
                        case BLOCK -> BuiltInRegistries.BLOCK.get(key);
                        case ITEM -> BuiltInRegistries.ITEM.get(key);
                        case ENTITY -> BuiltInRegistries.ENTITY_TYPE.get(key);
                    };
                    index.computeIfAbsent(target, unused -> new ArrayList<>()).add(rule);
                    if (naturalOnly) natural.add(target);
                }
                if (type.builtin() != null) capabilities.computeIfAbsent(jobId, unused -> new HashSet<>()).addAll(type.builtin().capabilities());
                metadata.add(new JobTaskApi.Task(id, typeId, jobId, reward, cap));
            } catch (RuntimeException invalid) { throw new IllegalArgumentException("Job task " + id + ": " + invalid.getMessage(), invalid); }
        }
        additions.forEach((jobId, actions) -> {
            var job = jobs.get(jobId); Map<String, Map<Object, List<JobRegistry.Rule>>> merged = new HashMap<>(job.index());
            actions.forEach((action, targets) -> {
                Map<Object, List<JobRegistry.Rule>> byTarget = new HashMap<>(merged.getOrDefault(action, Map.of()));
                targets.forEach((target, rules) -> {
                    var combined = new ArrayList<>(byTarget.getOrDefault(target, List.of())); combined.addAll(rules);
                    byTarget.put(target, List.copyOf(combined));
                });
                merged.put(action, Map.copyOf(byTarget));
            });
            var access = new HashSet<>(job.capabilities()); access.addAll(capabilities.getOrDefault(jobId, Set.of()));
            jobs.put(jobId, new JobRegistry.Job(job.id(), job.name(), job.mode(), job.base(), job.max(), job.gainCap(), job.slots(),
                    job.zones(), job.dimensions(), job.biomes(), Set.copyOf(access), Map.copyOf(merged), job.authority()));
        });
        return new JobRegistry(jobs, natural, metadata);
    }
    private record Decoded(Set<ResourceLocation> targets, java.util.function.Predicate<de.kuro.kuronomics.api.JobTaskType.Trigger<?>> matcher) {}
    private static <C, A> Decoded decode(MinecraftServer server, TaskExtensions.Entry<C, A> entry, JsonObject json) {
        C config = entry.type().codec().parse(JsonOps.INSTANCE, json).getOrThrow(message -> new IllegalArgumentException("config: " + message));
        var targets = Set.copyOf(entry.type().targets(config, server.registryAccess()));
        if (targets.isEmpty()) throw new IllegalArgumentException("config resolves no targets");
        return new Decoded(targets, entry.matcher(config));
    }
    private static String text(JsonObject json, String key) {
        var value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key + " must be text");
        return value.getAsString();
    }
    private static boolean flag(JsonObject json, String key, boolean fallback) {
        if (!json.has(key)) return fallback;
        var value = json.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(key + " must be boolean");
        return value.getAsBoolean();
    }
    static long money(JsonObject json, String key, long fallback) {
        if (!json.has(key)) return fallback;
        try {
            var value = json.get(key);
            if (!value.isJsonPrimitive() || !(value.getAsJsonPrimitive().isNumber() || value.getAsJsonPrimitive().isString())) throw new IllegalArgumentException();
            long cents = new java.math.BigDecimal(value.getAsString()).movePointRight(2).longValueExact();
            if (cents < 0) throw new IllegalArgumentException(); return cents;
        } catch (RuntimeException invalid) { throw new IllegalArgumentException(key + " must be nonnegative money, at most two decimals, without overflow"); }
    }
    private static Set<Item> tools(JsonObject json) {
        if (json.has("required_tool") && json.has("required_tool_tag")) throw new IllegalArgumentException("Choose required_tool or required_tool_tag");
        if (json.has("required_tool")) return Set.copyOf(JobsConfig.resolve(BuiltInRegistries.ITEM, text(json, "required_tool"), "item"));
        if (json.has("required_tool_tag")) return Set.copyOf(JobsConfig.resolve(BuiltInRegistries.ITEM, "#" + text(json, "required_tool_tag").replaceFirst("^#", ""), "item"));
        return Set.of();
    }
}
