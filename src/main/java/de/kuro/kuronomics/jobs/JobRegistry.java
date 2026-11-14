package de.kuro.kuronomics.jobs;

import java.util.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import de.kuro.kuronomics.economy.data.Account;
import de.kuro.kuronomics.integration.RegionProtectionService.Capability;

/** Resolve targets and tags before publishing this snapshot. Don't change it afterward. */
public record JobRegistry(Map<String, Job> jobs, Set<Object> naturalBlocks, List<de.kuro.kuronomics.api.JobTaskApi.Task> tasks) {
    public JobRegistry(Map<String, Job> jobs, Set<Object> naturalBlocks) { this(jobs, naturalBlocks, List.of()); }
    public static final JobRegistry EMPTY = new JobRegistry(Map.of(), Set.of());
    public JobRegistry { jobs = Map.copyOf(jobs); naturalBlocks = Set.copyOf(naturalBlocks); tasks = List.copyOf(tasks); }
    public enum Mode { STATIC, TASK_BASED }
    public enum Appointments { NONE, COMMON, DEPARTMENT, ALL }
    public record Authority(String department, int tier, Appointments appointments) {
        public static final Authority COMMON = new Authority("", 0, Appointments.NONE);
        public boolean appointed() { return tier > 0 || appointments != Appointments.NONE; }
        public boolean canManage(Authority target) {
            return switch (appointments) {
                case NONE -> false;
                case ALL -> true;
                case COMMON -> target.tier <= tier && target.appointments != Appointments.ALL && target.appointments != Appointments.DEPARTMENT;
                case DEPARTMENT -> !department.isEmpty() && department.equals(target.department) && target.tier < tier && target.appointments != Appointments.ALL;
            };
        }
    }
    public record Rule(String id, long gain, long cap, boolean natural, Set<Item> tools,
                       java.util.function.Predicate<de.kuro.kuronomics.api.JobTaskType.Trigger<?>> matcher,
                       java.util.function.Predicate<net.minecraft.server.level.ServerPlayer> condition) {
        public Rule(String id, long gain, long cap, boolean natural, Set<Item> tools) { this(id, gain, cap, natural, tools, null, null); }
        public Rule(String id, long gain, long cap, boolean natural, Set<Item> tools, java.util.function.Predicate<de.kuro.kuronomics.api.JobTaskType.Trigger<?>> matcher) { this(id, gain, cap, natural, tools, matcher, null); }
        public Rule { tools = Set.copyOf(tools); }
    }
    public record Job(String id, String name, Mode mode, long base, long max, long gainCap, int slots,
                      List<String> zones, Set<ResourceLocation> dimensions, Set<ResourceLocation> biomes,
                      Set<Capability> capabilities, Map<String, Map<Object, List<Rule>>> index, Authority authority) {
        public Job(String id, String name, Mode mode, long base, long max, long gainCap, int slots,
                   List<String> zones, Set<ResourceLocation> dimensions, Set<ResourceLocation> biomes,
                   Set<Capability> capabilities, Map<String, Map<Object, List<Rule>>> index) {
            this(id, name, mode, base, max, gainCap, slots, zones, dimensions, biomes, capabilities, index, Authority.COMMON);
        }
        public List<Rule> rules(String type, Object target) {
            var byTarget = index.get(type);
            return byTarget == null ? List.of() : byTarget.getOrDefault(target, List.of());
        }
        public long salary(Account account) { return mode == Mode.STATIC ? base : base + Math.min(account.jobEarnings(), max - base); }
    }
    public long salary(Account account) {
        Job job = jobs.get(account.job());
        return job == null ? 0 : job.salary(account);
    }
}
