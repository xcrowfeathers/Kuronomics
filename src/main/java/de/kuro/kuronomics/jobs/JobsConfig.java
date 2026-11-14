package de.kuro.kuronomics.jobs;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import de.kuro.kuronomics.economy.Money;
import de.kuro.kuronomics.integration.PropertyIntegrations;
import de.kuro.kuronomics.integration.RegionProtectionService.Capability;
import java.nio.file.*;
import java.util.*;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;

public final class JobsConfig {
    private static final String[][] CIVIC_ROLES = {
            {"president", "President", "government", "100", "ALL", "1"},
            {"employment_officer", "Employment Officer", "government", "1", "COMMON", "2147483647"},
            {"central_bank_governor", "Central Bank Governor", "bank", "3", "DEPARTMENT", "1"},
            {"bank_chairman", "Bank Chairman", "bank", "2", "DEPARTMENT", "2147483647"},
            {"banker", "Banker", "bank", "1", "NONE", "2147483647"}
    };
    private static String civicRole(String[] role) {
        return "\n[[jobs]]\nid = \"" + role[0] + "\"\nname = \"" + role[1]
                + "\"\nmode = \"STATIC\"\nsalary = 0\ndepartment = \"" + role[2] + "\"\ntier = " + role[3]
                + "\nappointments = \"" + role[4] + "\"\n" + (role[5].equals("1") ? "job_slots = 1\n" : "");
    }
    public static Path path() { return de.kuro.kuronomics.config.EconomyConfig.directory().resolve("economy_jobs.toml"); }
    public static final String DEFAULT = """
            # All money values are dollars, two decimal place-values are allowed.
            # Task gain is money per completed block/item/entity.
            [[jobs]]
            id = "guard"
            name = "Guard"
            mode = "STATIC"
            salary = 1500

            [[jobs]]
            id = "miner"
            name = "Miner"
            mode = "TASK_BASED"
            base_salary = 100
            max_salary = 1900
            # work_zones = []
            # work_dimensions = ["minecraft:overworld"]
            # work_biomes = ["minecraft:plains", "#minecraft:is_mountain"]
            # job_slots = 20

            [[jobs.tasks]]
            id = "coal"
            type = "break_block"
            target = "minecraft:coal_ore"
            gain = 2
            natural_only = true
            # required_tool_tag = "minecraft:pickaxes"
            # max_gain_per_payday = 500

            [[jobs.tasks]]
            id = "iron"
            type = "break_block"
            target = "minecraft:iron_ore"
            gain = 3
            natural_only = true

            [[jobs.tasks]]
            id = "diamond"
            type = "break_block"
            target = "minecraft:diamond_ore"
            gain = 4
            natural_only = true
            # Targets also support tags, e.g. target = "#minecraft:logs".
            """;
    public static JobRegistry load(MinecraftServer server) {
        try {
            if (!Files.exists(path())) {
                Files.createDirectories(path().getParent());
                StringBuilder text = new StringBuilder("# Predefined System Jobs. Do not remove these!\n");
                for (String[] role : CIVIC_ROLES) text.append(civicRole(role));
                text.append("\n# Common Jobs\n").append(DEFAULT);
                String defaults = hierarchical(text.toString());
                compile(server, defaults);
                Files.writeString(path(), defaults, StandardOpenOption.CREATE_NEW);
            }
            String original = Files.readString(path());
            String normalized = categorized(original);
            JobRegistry candidate = compile(server, normalized);
            if (!original.equals(normalized)) Files.writeString(path(), normalized);
            // Also handle a renamed config that reached disk before an interrupted server save.
            var miner=candidate.jobs().get("miner");
            if(miner!=null && miner.mode()==JobRegistry.Mode.TASK_BASED && !candidate.jobs().containsKey("miner_example"))JobService.get(server).migrateMinerExample();
            return candidate;
        } catch (java.io.IOException e) { throw new IllegalArgumentException("Cannot read " + path() + ": " + e.getMessage(), e); }
    }
    /** On config load, put system jobs first without changing job bodies or admin values. */
    public static String categorized(String text) {
        if (text.contains("\"\"\"") || text.contains("'''")) return text;
        var header = java.util.regex.Pattern.compile("(?m)^\\s*\\[\\[jobs]]\\s*$").matcher(text);
        List<Integer> starts = new ArrayList<>(); while (header.find()) starts.add(header.start());
        if (starts.isEmpty()) return text;
        Map<String,String> system = new LinkedHashMap<>(); List<String> common = new ArrayList<>();
        Set<String> systemIds = new HashSet<>(); for (var role : CIVIC_ROLES) systemIds.add(role[0]);
        List<String> blocks = new ArrayList<>();
        for (int i=0;i<starts.size();i++) blocks.add(text.substring(starts.get(i),i+1<starts.size()?starts.get(i+1):text.length()).strip());
        boolean taskExample = blocks.stream().anyMatch(b -> field(b,"id").equals("miner_example") && field(b,"mode").equals("TASK_BASED"));
        boolean staticMiner = blocks.stream().anyMatch(b -> field(b,"id").equals("miner") && field(b,"mode").equals("STATIC"));
        boolean renameExample=taskExample && (staticMiner || blocks.stream().noneMatch(b->field(b,"id").equals("miner")));
        for (String block : blocks) {
            String id=field(block,"id");if(id.isEmpty())return text;
            if(renameExample && staticMiner && id.equals("miner")) continue;
            if(renameExample && id.equals("miner_example")) block=block.replaceFirst("(?m)^(\\s*id\\s*=\\s*)[\"']miner_example[\"']", "$1\"miner\"").replaceFirst("(?m)^(\\s*name\\s*=\\s*)[\"'][^\"']*[\"']", "$1\"Miner\"");
            // Write generated category headings once below; don't attach them to the previous job.
            block=block.replaceAll("(?m)^\\s*# (?:Predefined System Jobs\\. Do not remove these!|Common Jobs)\\s*$", "").strip();
            if(systemIds.contains(id)) { if(system.putIfAbsent(id,block)!=null) return text; } else common.add(block);
        }
        if(system.isEmpty() && !renameExample)return text;
        String prefix=text.substring(0,starts.getFirst()).replaceAll("(?m)^\\s*# (?:Predefined System Jobs\\. Do not remove these!|Common Jobs)\\s*$", "").strip();
        var result=new StringBuilder();if(!prefix.isEmpty())result.append(prefix).append("\n\n");
        result.append("# Predefined System Jobs. Do not remove these!\n");
        for(var role:CIVIC_ROLES)if(system.containsKey(role[0]))result.append(system.get(role[0])).append("\n\n");
        result.append("# Common Jobs\n");for(var block:common)result.append(block).append("\n\n");
        return result.toString();
    }
    private static String field(String block,String key){
        var value=java.util.regex.Pattern.compile("(?m)^\\s*"+key+"\\s*=\\s*[\"']([^\"']*)[\"']").matcher(block);
        return value.find()?value.group(1):"";
    }
    public static String hierarchical(String text) {
        if (text.contains("\"\"\"") || text.contains("'''")) return text;
        StringBuilder output = new StringBuilder("# Job sections contain [[jobs.tasks]] tables.\n# A new [[jobs]] starts the next job; a new [[jobs.tasks]] a new task..\n\n");
        boolean task = false;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.equals("[[jobs]]")) {
                task = false;
                output.append("\n# ══ JOB ══════════════════════════════════════\n").append(line).append('\n');
            }
            else if (line.equals("[[jobs.tasks]]")) { task = true; output.append("    ").append(line).append('\n'); }
            else output.append(line.isEmpty() ? "" : task ? "        " : "    ").append(line).append('\n');
        }
        return output.toString();
    }
    public static JobRegistry compile(MinecraftServer server, String text) {
        UnmodifiableConfig root;
        try { root = new TomlParser().parse(text); }
        catch (RuntimeException e) { throw new IllegalArgumentException("economy_jobs.toml syntax: " + e.getMessage(), e); }
        keys(root, Set.of("jobs"));
        Map<String, JobRegistry.Job> jobs = new LinkedHashMap<>(); Set<Object> natural = new HashSet<>();
        for (UnmodifiableConfig c : tables(root, "jobs")) {
            String id = string(c, "id", "");
            try {
                if (!id.matches("[a-z0-9_][a-z0-9_.-]*") || id.equals("unemployed")) throw new IllegalArgumentException("invalid/reserved ID");
                keys(c, Set.of("id", "name", "mode", "salary", "base_salary", "min_salary", "max_salary", "work_zones", "work_biomes", "work_dimensions", "job_slots", "max_gain_per_payday", "tasks", "department", "tier", "appointments"));
                var mode = JobRegistry.Mode.valueOf(string(c, "mode", ""));
                String name = string(c, "name", id); if (name.isBlank()) throw new IllegalArgumentException("empty display name");
                long base, max;
                if (mode == JobRegistry.Mode.STATIC) {
                    for (String field : List.of("base_salary", "min_salary", "max_salary", "tasks", "work_zones", "work_biomes", "work_dimensions", "max_gain_per_payday"))
                        if (c.contains(field)) throw new IllegalArgumentException("STATIC cannot use " + field);
                    base = money(c, "salary", -1); max = base;
                } else {
                    if (c.contains("salary")) throw new IllegalArgumentException("TASK_BASED cannot use salary");
                    if (c.contains("base_salary") && c.contains("min_salary")) throw new IllegalArgumentException("choose base_salary or min_salary, not both");
                    base = money(c, c.contains("min_salary") ? "min_salary" : "base_salary", 0); max = money(c, "max_salary", -1);
                }
                if (base < 0 || max < base) throw new IllegalArgumentException("salary/max_salary missing or below base_salary");
                long gainCap = money(c, "max_gain_per_payday", Long.MAX_VALUE);
                Number slotValue = c.getOrElse("job_slots", Integer.MAX_VALUE);
                long slots = slotValue.longValue();
                if (slots < 1 || slots > Integer.MAX_VALUE || slotValue.doubleValue() != slots) throw new IllegalArgumentException("job_slots must be a positive integer");
                if (id.equals("president") || id.equals("central_bank_governor")) {
                    if (c.contains("job_slots") && slots != 1) throw new IllegalArgumentException("this office must have job_slots = 1");
                    slots = 1;
                }
                String[] role = Arrays.stream(CIVIC_ROLES).filter(r -> r[0].equals(id)).findFirst().orElse(null);
                String department = string(c, "department", role == null ? "" : role[2]);
                if (!department.isEmpty() && !department.matches("[a-z0-9_][a-z0-9_.-]*")) throw new IllegalArgumentException("invalid department ID");
                int tier;
                Object tierValue = c.get("tier"); if (tierValue == null) tierValue = role == null ? "0" : role[3];
                try { tier = new java.math.BigDecimal(tierValue.toString()).intValueExact(); }
                catch (RuntimeException invalid) { throw new IllegalArgumentException("tier must be a nonnegative integer"); }
                if (tier < 0) throw new IllegalArgumentException("tier must be a nonnegative integer");
                var appointments = JobRegistry.Appointments.valueOf(string(c, "appointments", role == null ? "NONE" : role[4]));
                if ((id.equals("president") || id.equals("central_bank_governor")) && (tier == 0 || appointments == JobRegistry.Appointments.NONE || appointments == JobRegistry.Appointments.COMMON))
                    throw new IllegalArgumentException("this office requires protected appointment authority");
                if (appointments == JobRegistry.Appointments.DEPARTMENT && department.isEmpty()) throw new IllegalArgumentException("department appointments need a department");
                var authority = new JobRegistry.Authority(department, tier, appointments);
                var zones = strings(c, "work_zones");
                for (String zone : zones) {
                    if (PropertyIntegrations.get().available()) WorkZones.get(server).validate(server, zone);
                }
                Set<ResourceLocation> dims = new HashSet<>();
                for (String dim : strings(c, "work_dimensions")) {
                    ResourceLocation key = ResourceLocation.parse(dim);
                    if (server.levelKeys().stream().noneMatch(k -> k.location().equals(key))) throw new IllegalArgumentException("unknown dimension " + dim);
                    dims.add(key);
                }
                Set<ResourceLocation> biomes = new HashSet<>(); var biomeRegistry = server.registryAccess().registryOrThrow(Registries.BIOME);
                for (String biome : strings(c, "work_biomes")) for (var value : resolve(biomeRegistry, biome, "biome")) biomes.add(biomeRegistry.getKey(value));
                Map<String, Map<Object, List<JobRegistry.Rule>>> index = new HashMap<>(); Set<Capability> capabilities = new HashSet<>(); Set<String> ruleIds = new HashSet<>();
                for (UnmodifiableConfig task : tables(c, "tasks")) {
                    keys(task, Set.of("id", "type", "target", "gain", "natural_only", "required_tool", "required_tool_tag", "max_gain_per_payday"));
                    var type = TaskTypes.require(string(task, "type", "")); String target = string(task, "target", "");
                    String ruleId = string(task, "id", type.id() + ":" + target);
                    if (ruleId.isBlank() || !ruleIds.add(ruleId)) throw new IllegalArgumentException("duplicate/empty task ID " + ruleId);
                    long gain = money(task, "gain", 0); if (gain <= 0) throw new IllegalArgumentException("gain must be positive");
                    boolean naturalOnly = task.getOrElse("natural_only", false);
                    if (naturalOnly && !type.natural()) throw new IllegalArgumentException("natural_only is only supported for break_block");
                    if (task.contains("required_tool") && task.contains("required_tool_tag")) throw new IllegalArgumentException("choose required_tool or required_tool_tag");
                    Set<Item> tools = new HashSet<>();
                    if (task.contains("required_tool")) tools.addAll(resolve(BuiltInRegistries.ITEM, string(task, "required_tool", ""), "item"));
                    if (task.contains("required_tool_tag")) tools.addAll(resolve(BuiltInRegistries.ITEM, "#" + string(task, "required_tool_tag", "").replaceFirst("^#", ""), "item"));
                    Set<?> targets = switch (type.target()) {
                        case BLOCK -> resolve(BuiltInRegistries.BLOCK, target, "block");
                        case ITEM -> resolve(BuiltInRegistries.ITEM, target, "item");
                        case ENTITY -> resolve(BuiltInRegistries.ENTITY_TYPE, target, "entity");
                    };
                    var rule = new JobRegistry.Rule(ruleId, gain, money(task, "max_gain_per_payday", Long.MAX_VALUE), naturalOnly, Set.copyOf(tools));
                    for (Object value : targets) index.computeIfAbsent(type.id(), k -> new HashMap<>()).computeIfAbsent(value, k -> new ArrayList<>()).add(rule);
                    if (naturalOnly) natural.addAll(targets);
                    capabilities.addAll(type.capabilities());
                }
                // A TOML job can have no tasks of its own and get all its tasks from datapacks.
                Map<String, Map<Object, List<JobRegistry.Rule>>> frozen = new HashMap<>();
                index.forEach((type, targets) -> { Map<Object, List<JobRegistry.Rule>> copy = new HashMap<>(); targets.forEach((key, value) -> copy.put(key, List.copyOf(value))); frozen.put(type, Map.copyOf(copy)); });
                var job = new JobRegistry.Job(id, name, mode, base, max, gainCap, (int) slots, List.copyOf(zones), Set.copyOf(dims), Set.copyOf(biomes), Set.copyOf(capabilities), Map.copyOf(frozen), authority);
                if (jobs.putIfAbsent(id, job) != null) throw new IllegalArgumentException("duplicate job ID");
            } catch (RuntimeException e) { throw new IllegalArgumentException("Job '" + id + "': " + e.getMessage(), e); }
        }
        return new JobRegistry(jobs, natural);
    }
    private static void keys(UnmodifiableConfig c, Set<String> allowed) { for (var e : c.entrySet()) if (!allowed.contains(e.getKey())) throw new IllegalArgumentException("unknown argument " + e.getKey()); }
    private static String string(UnmodifiableConfig c, String key, String fallback) { Object v = c.get(key); if (v == null) return fallback; if (!(v instanceof String s)) throw new IllegalArgumentException(key + " must be text"); return s; }
    private static long money(UnmodifiableConfig c, String key, long fallback) {
        Object value = c.get(key); if (value == null) return fallback;
        try { long result = new java.math.BigDecimal(value.toString()).movePointRight(2).longValueExact(); if (result < 0) throw new ArithmeticException(); return result; }
        catch (RuntimeException e) { throw new IllegalArgumentException(key + " must be nonnegative money with at most two decimals and no overflow"); }
    }
    @SuppressWarnings("unchecked") private static List<UnmodifiableConfig> tables(UnmodifiableConfig c, String key) {
        Object value = c.get(key); if (value == null) return List.of();
        if (!(value instanceof List<?> list) || list.stream().anyMatch(v -> !(v instanceof UnmodifiableConfig))) throw new IllegalArgumentException(key + " must be tables");
        return (List<UnmodifiableConfig>) list;
    }
    private static List<String> strings(UnmodifiableConfig c, String key) {
        Object value = c.get(key); if (value == null) return List.of();
        if (!(value instanceof List<?> list) || list.stream().anyMatch(v -> !(v instanceof String))) throw new IllegalArgumentException(key + " must be a list of strings");
        return list.stream().map(Object::toString).toList();
    }
    static <T> Set<T> resolve(Registry<T> registry, String text, String kind) {
        Set<T> result = new HashSet<>();
        if (text.startsWith("#")) {
            var tag = registry.getTag(TagKey.create(registry.key(), ResourceLocation.parse(text.substring(1))));
            if (tag.isEmpty()) throw new IllegalArgumentException("unknown " + kind + " tag " + text);
            tag.get().forEach(h -> result.add(h.value()));
        } else {
            ResourceLocation id = ResourceLocation.parse(text);
            if (!registry.containsKey(id)) throw new IllegalArgumentException("unknown " + kind + " " + text);
            result.add(registry.get(id));
        }
        if (result.isEmpty()) throw new IllegalArgumentException("empty " + kind + " target " + text);
        return result;
    }
    private JobsConfig() {}
}
