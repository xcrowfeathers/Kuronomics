package de.kuro.kuronomics.jobs;

import com.google.gson.*;
import de.kuro.kuronomics.api.*;
import de.kuro.kuronomics.company.Company;
import de.kuro.kuronomics.economy.EconomicOwner;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.GsonHelper;

/** Translate the supported JSON checks into reusable predicates. This isn't a full expression language. */
final class TaskConditions {
    static Predicate<ServerPlayer> compile(JsonObject task, Set<String> jobs) {
        if (!task.has("conditions")) return null;
        var array = GsonHelper.getAsJsonArray(task, "conditions"); Predicate<ServerPlayer> result = p -> true;
        for (JsonElement element : array) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("Each condition must be an object");
            var c = element.getAsJsonObject(); String type = text(c, "type");
            Predicate<ServerPlayer> condition = switch (type) {
                case "funds" -> { keys(c, "owner", "amount"); yield EconomicConditions.funds(owner(c), JobTaskLoader.money(c, "amount", -1)); }
                case "account_exists" -> { keys(c, "owner"); yield EconomicConditions.accountExists(Objects.requireNonNull(owner(c), "account_exists requires owner")); }
                case "company_permission" -> { keys(c, "company", "permission"); yield EconomicConditions.companyPermission(UUID.fromString(text(c, "company")), CompanyApi.Permission.valueOf(text(c, "permission"))); }
                case "company_rank" -> { keys(c, "company", "rank", "minimum"); yield EconomicConditions.companyRank(UUID.fromString(text(c, "company")), Company.Rank.valueOf(text(c, "rank")), flag(c, "minimum")); }
                case "job" -> { keys(c, "job"); yield EconomicConditions.job(job(c, jobs)); }
                case "job_eligible" -> { keys(c, "job"); yield EconomicConditions.eligibleForJob(job(c, jobs)); }
                case "property_owner" -> { keys(c, "property", "owner"); yield EconomicConditions.ownsProperty(text(c, "property"), owner(c)); }
                case "property_manage" -> { keys(c, "property"); yield EconomicConditions.managesProperty(text(c, "property")); }
                default -> throw new IllegalArgumentException("Unknown economic condition " + type);
            };
            result = result.and(condition);
        }
        return result;
    }
    private static String text(JsonObject object, String key) {
        var value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key + " must be text");
        return value.getAsString();
    }
    private static boolean flag(JsonObject object, String key) {
        if (!object.has(key)) return false;
        var value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException(key + " must be boolean");
        return value.getAsBoolean();
    }
    private static String job(JsonObject object, Set<String> jobs) {
        String id = text(object, "job");
        if (!jobs.contains(id)) throw new IllegalArgumentException("Unknown condition job " + id); return id;
    }
    private static void keys(JsonObject object, String... fields) {
        var allowed = new HashSet<>(List.of(fields)); allowed.add("type");
        for (String key : object.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("Unknown condition field " + key);
    }
    private static EconomicOwner owner(JsonObject condition) {
        if (!condition.has("owner")) return null;
        var owner = GsonHelper.getAsJsonObject(condition, "owner");
        var type = EconomicOwner.Type.valueOf(text(owner, "type"));
        keys(owner, type == EconomicOwner.Type.STATE ? new String[0] : new String[]{"id"});
        return new EconomicOwner(type, type == EconomicOwner.Type.STATE ? null : UUID.fromString(text(owner, "id")));
    }
    private TaskConditions() {}
}
