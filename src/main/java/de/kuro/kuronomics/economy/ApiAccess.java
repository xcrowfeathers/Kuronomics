package de.kuro.kuronomics.economy;

import de.kuro.kuronomics.api.OperationResult;
import de.kuro.kuronomics.company.*;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Shared checks for API adapters. This class isn't part of the supported addon API. */
public final class ApiAccess {
    public static final class Failure extends RuntimeException {
        final OperationResult.Status status;
        public Failure(OperationResult.Status status, String message) { super(message); this.status = status; }
    }
    public static void thread(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Kuronomy API requires the server thread");
    }
    public static void actor(MinecraftServer server, ServerPlayer actor) {
        thread(server);
        if (actor == null || actor.server != server || server.getPlayerList().getPlayer(actor.getUUID()) != actor)
            throw new SecurityException("Actor must be the connected server player");
    }
    public static void spending(MinecraftServer server, ServerPlayer actor, EconomicOwner owner) {
        actor(server, actor);
        if (owner.isPlayer() && owner.id().equals(actor.getUUID())) return;
        if (owner.isCompany()) {
            Company c = de.kuro.kuronomics.economy.data.EconomyLedger.get(server).companies().company(owner.id());
            if (c != null && CompanyPermissionService.allowed(c, actor.getUUID(), CompanyPermissionService.Capability.FINANCE)) return;
        }
        throw new SecurityException("Actor cannot spend this account");
    }
    public static OperationResult mutate(MinecraftServer server, BooleanSupplier operation) {
        thread(server);
        final boolean changed;
        try {
            FinancialWriter.check(server);
            changed = operation.getAsBoolean();
        } catch (Failure failure) {
            return new OperationResult(failure.status, failure.getMessage());
        } catch (EconomyService.InsufficientFundsException insufficient) {
            return new OperationResult(OperationResult.Status.INSUFFICIENT_FUNDS, insufficient.getMessage());
        } catch (CompanyPermissionService.PermissionDeniedException denied) {
            return new OperationResult(OperationResult.Status.DENIED, denied.getMessage());
        } catch (SecurityException denied) {
            return new OperationResult(OperationResult.Status.DENIED, denied.getMessage());
        } catch (IllegalArgumentException | ArithmeticException | NullPointerException invalid) {
            return new OperationResult(OperationResult.Status.INVALID, java.util.Objects.toString(invalid.getMessage(), "Invalid operation"));
        } catch (RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger().error("Public API operation could not complete", failure);
            return new OperationResult(OperationResult.Status.STORAGE_UNCERTAIN, "Retry with the same reference; financial completion is uncertain");
        }
        try { FinancialPersistence.save(server); }
        catch (RuntimeException failure) {
            com.mojang.logging.LogUtils.getLogger().error("Public API checkpoint failed", failure);
            return new OperationResult(OperationResult.Status.STORAGE_UNCERTAIN, "Retry with the same reference; financial completion is uncertain");
        }
        return new OperationResult(changed ? OperationResult.Status.SUCCESS : OperationResult.Status.ALREADY_APPLIED, "");
    }
    private ApiAccess() {}
}
