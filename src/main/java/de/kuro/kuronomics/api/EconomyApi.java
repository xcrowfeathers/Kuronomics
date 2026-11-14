package de.kuro.kuronomics.api;

import de.kuro.kuronomics.economy.EconomicOwner;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/** Use the server thread. Amounts are whole cents. Reading an account won't create it or touch disk. */
public interface EconomyApi {
    record Balance(EconomicOwner owner, long posted, long spendable, long pending, long reserved, boolean suspended) {}
    /** On retry, reuse the reference and every other field. Use a namespaced addon/action ID for the reason. */
    record Context(UUID reference, ResourceLocation reason, String description) {
        public Context {
            java.util.Objects.requireNonNull(reference); java.util.Objects.requireNonNull(reason);
            if (description == null || description.length() > 128 || description.indexOf('|') >= 0
                    || description.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid description");
        }
    }
    record Hold(UUID reference, EconomicOwner owner, long amount, boolean active) {}
    enum Timing { IMMEDIATE, NEXT_PAYDAY }
    static EconomicOwner player(UUID id) { return EconomicOwner.player(id); }
    static EconomicOwner company(UUID id) { return EconomicOwner.company(id); }
    static EconomicOwner state() { return EconomicOwner.STATE; }
    boolean exists(EconomicOwner owner);
    Optional<Balance> balance(EconomicOwner owner);
    boolean canAfford(EconomicOwner owner, long cents);
    /** Use the connected player and their own account, or a company they have FINANCE permission for. */
    OperationResult transfer(ServerPlayer actor, EconomicOwner payer, EconomicOwner recipient, long cents, Timing timing, Context context);
    /** API holds are separate from gameplay holds. These methods can't capture or release gameplay holds. */
    OperationResult reserve(ServerPlayer actor, EconomicOwner payer, long cents, Context context);
    Optional<Hold> reservation(UUID reference);
    /** Captures the whole hold in one commit. Partial capture isn't supported. */
    OperationResult capture(ServerPlayer actor, UUID holdReference, EconomicOwner recipient, Timing timing, Context context);
    OperationResult release(ServerPlayer actor, UUID holdReference, Context context);
    /** Treasury actions still go through the bank's roles, command toggles, daily limits and audit. */
    OperationResult bankPayment(ServerPlayer actor, UUID player, long cents, Context context);
    OperationResult bankDebit(ServerPlayer actor, UUID player, long cents, Context context);
}
