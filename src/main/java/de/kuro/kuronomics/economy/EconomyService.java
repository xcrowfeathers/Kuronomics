package de.kuro.kuronomics.economy;

import de.kuro.kuronomics.economy.data.*;
import de.kuro.kuronomics.economy.tax.MarketplaceTaxService;
import de.kuro.kuronomics.company.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.UnaryOperator;

/** Check money changes here, then commit balances and the transaction reference together in the ledger. */
public final class EconomyService {
    private static final net.minecraft.resources.ResourceLocation TRANSFER_REASON = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("kuronomics", "transfer");
    public static final class InsufficientFundsException extends IllegalArgumentException {
        public InsufficientFundsException() { super("Insufficient spendable funds"); }
        public InsufficientFundsException(String message) { super(message); }
    }
    private final EconomyLedger ledger;

    public EconomyService(EconomyLedger ledger) { this.ledger = Objects.requireNonNull(ledger); }

    public Account account(UUID id) { return ledger.account(Objects.requireNonNull(id)); }
    public Account account(EconomicOwner owner) { return ledger.account(Objects.requireNonNull(owner)); }
    public long getBalance(EconomicOwner owner) { return account(owner).balance(); }
    public long getAvailableBalance(EconomicOwner owner) { return Math.subtractExact(getBalance(owner), ledger.reserved(owner)); }
    public long getReservedBalance(EconomicOwner owner) { return ledger.reserved(owner); }
    public long getBalance(UUID id) { return account(id).balance(); }
    public long getAvailableBalance(UUID id) { return Math.subtractExact(getBalance(id), ledger.reserved(id)); }
    public long getReservedBalance(UUID id) { return ledger.reserved(Objects.requireNonNull(id)); }
    public Optional<Reservation> getReservation(UUID reference) { return Optional.ofNullable(ledger.reservation(Objects.requireNonNull(reference))); }
    public Optional<PaydayCharge> getPaydayCharge(UUID reference) { return Optional.ofNullable(ledger.paydayCharge(Objects.requireNonNull(reference))); }
    public boolean isTransactionProcessed(UUID reference) { return ledger.hasTransaction(reference); }

    public void requireActive(UUID id) {
        Account account = account(id);
        if (account.suspended()) throw new IllegalArgumentException("Account suspended: " + account.suspensionReason());
    }
    public void requireActive(EconomicOwner owner) {
        if (account(owner).suspended()) throw new IllegalArgumentException("Account suspended");
        if (owner.isCompany()) {
            Company c = ledger.companies().company(owner.id());
            if (c == null || c.status() != Company.Status.ACTIVE) throw new IllegalArgumentException("Company is not active");
        }
    }

    public boolean deposit(UUID id, long amount, UUID reference, String reason) {
        Money.positive(amount);
        String fingerprint = fingerprint("deposit", id, amount, reason);
        if (ledger.transactionRecorded(reference, fingerprint)) return false;
        Account result = account(id).balance(Math.addExact(getBalance(id), amount));
        return ledger.commitTransaction(reference, fingerprint, Map.of(id, result), List.of(), List.of(), Set.of());
    }

    public boolean withdraw(UUID id, long amount, UUID reference, String reason) {
        Money.positive(amount); requireActive(id);
        String fingerprint = fingerprint("withdraw", id, amount, reason);
        if (ledger.transactionRecorded(reference, fingerprint)) return false;
        if (getAvailableBalance(id) < amount) throw new IllegalArgumentException("Your current balance is insufficient.");
        Account result = account(id).balance(Math.subtractExact(getBalance(id), amount));
        return ledger.commitTransaction(reference, fingerprint, Map.of(id, result), List.of(), List.of(), Set.of());
    }

    /** Only use this when the state is actually receiving the money. */
    public boolean collectState(UUID payer, long amount, UUID reference, String reason) {
        Money.positive(amount); requireActive(payer);
        String fingerprint = fingerprint("withdraw", payer, amount, reason);
        if (ledger.transactionRecorded(reference, fingerprint)) return false;
        if (getAvailableBalance(payer) < amount) throw new IllegalArgumentException("Your current balance is insufficient.");
        Map<EconomicOwner, Account> updates = new HashMap<>();
        updates.put(EconomicOwner.player(payer), account(payer).balance(Math.subtractExact(getBalance(payer), amount)));
        StateTreasury.stage(ledger, updates, amount);
        return ledger.commitOwners(reference, fingerprint, updates, List.of(), ledger.companies(), Map.of());
    }

    /** Automatic state obligations still go through when the Treasury is in debt. */
    public boolean payFromState(UUID recipient, long amount, UUID reference, String reason) {
        Money.positive(amount);
        String fingerprint = fingerprint("deposit", recipient, amount, reason);
        if (ledger.transactionRecorded(reference, fingerprint)) return false;
        Map<EconomicOwner, Account> updates = new HashMap<>();
        updates.put(EconomicOwner.player(recipient), account(recipient).balance(Math.addExact(getBalance(recipient), amount)));
        StateTreasury.stage(ledger, updates, Math.negateExact(amount));
        return ledger.commitOwners(reference, fingerprint, updates, List.of(), ledger.companies(), Map.of());
    }

    public boolean transfer(UUID payer, UUID receiver, long amount, boolean fee, UUID reference) {
        return transfer(payer, receiver, amount, fee, false, null, reference);
    }

    public boolean transfer(UUID payer, UUID receiver, long amount, boolean fee, boolean deferred,
                            String description, UUID reference) {
        Money.positive(amount);
        if (payer.equals(receiver)) throw new IllegalArgumentException("You cannot pay yourself");
        requireActive(payer);
        long transactionFee = fee ? Money.percent(amount, ledger.fee()) : 0;
        long cost = Math.addExact(amount, transactionFee);
        String label = deferred ? Objects.requireNonNull(description) : "immediate";
        String fingerprint = fingerprint("transfer", payer, receiver, amount, transactionFee, label);
        if (ledger.transactionRecorded(reference, fingerprint)) return false;
        return postTransfer(EconomicOwner.player(payer), EconomicOwner.player(receiver), amount, cost, deferred,
                label, true, reference, fingerprint, null);
    }
    public boolean transfer(EconomicOwner payer, EconomicOwner receiver, long amount, boolean deferred,
                             ActionContext context, String category, String association, boolean taxable) {
        Money.positive(amount); requireActive(payer);
        // Suspension blocks spending, just like /pay. It doesn't block incoming money.
        if (!receiver.isPlayer()) requireActive(receiver);
        if (payer.equals(receiver)) throw new IllegalArgumentException("You cannot pay the same account");
        String fingerprint = fingerprint("owned-transfer", payer, receiver, amount, deferred, context.actor(), category, association, taxable);
        if (ledger.transactionRecorded(context.operation(), fingerprint)) return false;
        return postTransfer(payer, receiver, amount, amount, deferred, category, taxable, context.operation(), fingerprint,
                CompanyAudit.of(context, payer, receiver, category, amount, association));
    }
    private boolean postTransfer(EconomicOwner payer, EconomicOwner receiver, long amount, long cost, boolean deferred,
                                  String description, boolean taxable, UUID reference, String fingerprint, CompanyAudit audit) {
        if (ledger.spendingCapacity(payer) < cost) throw new InsufficientFundsException();
        Account from = account(payer), to = account(receiver);
        Account credited = deferred ? to.pending(Math.addExact(to.pendingIncome(), amount)) : to.balance(Math.addExact(to.balance(), amount));
        List<Receipt> receipts = deferred && receiver.isPlayer() ? List.of(new Receipt(receiver.id(), description, amount, taxable)) : List.of();
        if (audit == null && payer.isPlayer() && receiver.isPlayer() && cost == amount) {
            boolean committed = ledger.commitTransaction(reference, fingerprint, Map.of(payer.id(), from.balance(Math.subtractExact(from.balance(), cost)), receiver.id(), credited), List.of(), receipts, Set.of());
            if (committed) transferEvent(payer.id(), payer, receiver, amount, deferred, reference, description, "");
            return committed;
        }
        var edit = ledger.companies().edit();
        if (deferred && receiver.isCompany()) edit.credit(receiver.id(), new CompanyCredit(payer, description, audit == null ? "" : audit.asset(), amount, taxable));
        if (audit != null) edit.audit(audit);
        Map<EconomicOwner, Account> updates = new HashMap<>();
        updates.put(payer, from.balance(Math.subtractExact(from.balance(), cost))); updates.put(receiver, credited);
        StateTreasury.stage(ledger, updates, Math.subtractExact(cost, amount));
        boolean committed = ledger.commitOwners(reference, fingerprint, updates, receipts, edit.build(), Map.of());
        if (committed) transferEvent(audit == null ? payer.id() : audit.actor(), payer, receiver, amount, deferred, reference, description, audit == null ? "" : audit.asset());
        return committed;
    }
    public void transferEvent(UUID actor, EconomicOwner payer, EconomicOwner recipient, long amount, boolean deferred, UUID reference, String category, String description) {
        var reason = category.contains(":") ? net.minecraft.resources.ResourceLocation.tryParse(category) : null;
        if (reason == null) reason = TRANSFER_REASON;
        String detail = description == null ? "" : description;
        if (detail.length() > 128) detail = detail.substring(0, 128);
        for (int i = 0; i < detail.length(); i++) if (detail.charAt(i) == '|' || Character.isISOControl(detail.charAt(i))) {
            detail = detail.replace('|', '/').replaceAll("[\\p{Cntrl}]", " "); break;
        }
        ledger.notifyAfterCommit(new de.kuro.kuronomics.api.event.EconomyTransactionEvent(actor, payer, recipient, amount,
                deferred ? de.kuro.kuronomics.api.EconomyApi.Timing.NEXT_PAYDAY : de.kuro.kuronomics.api.EconomyApi.Timing.IMMEDIATE,
                new de.kuro.kuronomics.api.EconomyApi.Context(reference, reason, detail)));
    }

    public boolean shopSale(UUID buyer, UUID owner, long amount, UUID reference) {
        if (!buyer.equals(owner))
            return transfer(buyer, owner, amount, false, true, Receipt.SHOP_SALES, reference);
        Money.positive(amount); requireActive(buyer);
        String fingerprint = fingerprint("shop", buyer, owner, amount);
        if (ledger.transactionRecorded(reference, fingerprint)) return false;
        if (getAvailableBalance(buyer) < amount) throw new IllegalArgumentException("Your current balance is insufficient.");
        Account current = account(buyer);
        Account after = current.balance(Math.subtractExact(current.balance(), amount))
                .pending(Math.addExact(current.pendingIncome(), amount));
        return ledger.commitTransaction(reference, fingerprint, Map.of(buyer, after), List.of(),
                List.of(new Receipt(owner, Receipt.SHOP_SALES, amount)), Set.of());
    }
    /** Debit the payer, buffer the seller's net income, credit bank tax and update virtual stock in one commit. */
    public boolean fundedSale(UUID buyer, EconomicOwner seller, long gross, java.math.BigDecimal taxPercent, UUID reference,
                              String label, de.kuro.kuronomics.market.MarketBook next) {
        Money.positive(gross);
        String fingerprint = "funded-sale|" + buyer + "|" + seller + "|" + gross + "|" + taxPercent + "|" + label;
        if (ledger.transactionRecorded(reference, fingerprint)) return false;
        requireActive(buyer); if (seller.isCompany()) requireActive(seller);
        EconomicOwner payer = EconomicOwner.player(buyer);
        if (payer.equals(seller)) throw new IllegalArgumentException("You cannot buy your own listing.");
        if (getAvailableBalance(buyer) < gross) throw new IllegalArgumentException("Not enough money.");
        int basisPoints = taxPercent.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).intValueExact();
        var breakdown = MarketplaceTaxService.calculate(MarketplaceTaxService.SourceType.GLOBAL_MARKET_SALE, gross, basisPoints);
        Map<EconomicOwner, Account> updates = new HashMap<>();
        updates.put(payer, account(payer).balance(Math.subtractExact(getBalance(payer), gross)));
        updates.put(seller, account(seller).pending(Math.addExact(account(seller).pendingIncome(), breakdown.net())));
        if (breakdown.tax() > 0) updates.put(EconomicOwner.STATE, account(EconomicOwner.STATE).balance(Math.addExact(getBalance(EconomicOwner.STATE), breakdown.tax())));
        var edit = ledger.companies().edit();
        if (seller.isCompany() && breakdown.net() > 0) edit.credit(seller.id(), new CompanyCredit(payer, label, "", breakdown.net(), true));
        if (seller.isCompany()) edit.audit(new CompanyAudit(reference, System.currentTimeMillis(), buyer, seller, payer, seller, "COMPANY_SHOP_SALE", gross, label));
        List<Receipt> receipts = seller.isPlayer() ? List.of(Receipt.marketplace(seller.id(), label, true, breakdown)) : List.of();
        return ledger.commitMarket(reference, fingerprint, updates, receipts, edit.build(), next);
    }

    public boolean reserveFunds(UUID owner, long amount, UUID reference) {
        return reserveFunds(EconomicOwner.player(owner), amount, reference);
    }
    public boolean reserveFunds(EconomicOwner owner, long amount, UUID reference) {
        Money.positive(amount); requireActive(owner);
        Reservation existing = ledger.reservation(reference);
        if (existing != null) return ledger.reserve(new Reservation(reference, owner, amount, true));
        if (ledger.spendingCapacity(owner) < amount) throw new InsufficientFundsException();
        return ledger.reserve(new Reservation(reference, owner, amount, true));
    }

    public boolean releaseReservation(UUID owner, UUID reference) { return ledger.release(reference, owner); }
    public boolean releaseReservation(EconomicOwner owner, UUID reference) { return ledger.release(reference, owner); }
    public boolean reserveAuthorized(EconomicOwner owner, long amount, UUID holdId, ActionContext context, String reason, String description) {
        Money.positive(amount); requireActive(owner);
        String fingerprint = fingerprint("api-reserve", owner, amount, holdId, context.actor(), reason, description);
        if (ledger.transactionRecorded(context.operation(), fingerprint)) return false;
        if (ledger.spendingCapacity(owner) < amount) throw new InsufficientFundsException();
        var edit = ledger.companies().edit(); edit.audit(CompanyAudit.of(context, owner, owner, reason, amount, description));
        return ledger.prepareOwned(context.operation(), fingerprint, new Reservation(holdId, owner, amount, true), edit.build());
    }
    public boolean releaseAuthorized(EconomicOwner owner, UUID holdId, ActionContext context, String reason, String description) {
        String fingerprint = fingerprint("api-release", owner, holdId, context.actor(), reason, description);
        var edit = ledger.companies().edit(); edit.audit(CompanyAudit.of(context, owner, owner, reason, 0, description));
        return ledger.releaseOwned(context.operation(), fingerprint, holdId, owner, edit.build());
    }
    /** Capture the whole hold with its balance and buffered income changes, just like property recovery. */
    public boolean captureReserved(EconomicOwner payer, UUID holdId, EconomicOwner receiver, boolean deferred,
                                   ActionContext context, String category, String association) {
        requireActive(payer);
        if (!receiver.isPlayer()) requireActive(receiver);
        if (payer.equals(receiver) || deferred && receiver.equals(EconomicOwner.STATE))
            throw new IllegalArgumentException("Invalid capture recipient/timing");
        Reservation hold = ledger.reservation(holdId);
        if (hold == null || !hold.economicOwner().equals(payer)) throw new IllegalArgumentException("Unknown hold");
        String fingerprint = fingerprint("full-capture", payer, holdId, receiver, hold.amount(), deferred, context.actor(), category, association);
        if (ledger.transactionRecorded(context.operation(), fingerprint)) return false;
        if (!hold.active()) throw new IllegalArgumentException("Hold is no longer active");
        long amount = hold.amount();
        var from = account(payer); var to = account(receiver);
        var credited = deferred ? to.pending(Math.addExact(to.pendingIncome(), amount)) : to.balance(Math.addExact(to.balance(), amount));
        var edit = ledger.companies().edit();
        edit.audit(CompanyAudit.of(context, payer, receiver, category, amount, association));
        if (deferred && receiver.isCompany()) edit.credit(receiver.id(), new CompanyCredit(payer, category, association, amount, true));
        var receipts = deferred && receiver.isPlayer() ? List.of(new Receipt(receiver.id(), category, amount, true)) : List.<Receipt>of();
        boolean committed = ledger.captureOwned(context.operation(), fingerprint, holdId, payer, amount,
                Map.of(payer, from.balance(Math.subtractExact(from.balance(), amount)), receiver, credited), receipts, edit.build());
        if (committed) transferEvent(context.actor(), payer, receiver, amount, deferred, context.operation(), category, association);
        return committed;
    }
    public boolean capturePartial(EconomicOwner payer, UUID hold, EconomicOwner receiver, long amount, ActionContext context, String purpose) {
        Money.positive(amount);
        String fingerprint = fingerprint("partial-capture", payer, hold, receiver, amount, context.actor(), purpose);
        if (ledger.transactionRecorded(context.operation(), fingerprint)) return false;
        Reservation reservation = ledger.reservation(hold);
        if (reservation == null || !reservation.active() || !reservation.economicOwner().equals(payer) || amount > reservation.amount())
            throw new IllegalArgumentException("Invalid reservation capture");
        if (payer.equals(receiver)) throw new IllegalArgumentException("Cannot capture to the same account");
        requireActive(receiver);
        Map<EconomicOwner, Account> updates = Map.of(payer, account(payer).balance(Math.subtractExact(getBalance(payer), amount)),
                receiver, account(receiver).balance(Math.addExact(getBalance(receiver), amount)));
        var edit = ledger.companies().edit(); edit.audit(CompanyAudit.of(context, payer, receiver, "HOLD_CAPTURE", amount, purpose));
        return ledger.captureOwned(context.operation(), fingerprint, hold, payer, amount, updates, List.of(), edit.build());
    }

    /** Move or resize the auction hold without leaving a gap where its money could be spent. */
    public boolean transitionReservation(UUID previousReference, UUID previousOwner,
                                         UUID nextReference, UUID nextOwner, long nextAmount,
                                         UUID transactionReference, String purpose) {
        Money.positive(nextAmount); requireActive(nextOwner);
        String fingerprint = fingerprint("reservation-transition", previousReference, previousOwner,
                nextReference, nextOwner, nextAmount, purpose);
        return ledger.transitionReservation(transactionReference, fingerprint, previousReference, previousOwner,
                nextReference, nextOwner, nextAmount);
    }

    public boolean registerPaydayDebit(UUID reference, UUID payer, long amount, UUID reservationReference, String description) {
        Money.positive(amount);
        Reservation hold = ledger.reservation(reservationReference);
        if (hold == null || !hold.active() || !hold.owner().equals(payer) || hold.amount() != amount)
            throw new IllegalStateException("Auction reservation does not match its PayDay debit");
        return ledger.addPaydayCharge(new PaydayCharge(reference, payer, amount, description, reservationReference,
                PaydayCharge.Status.ACTIVE, null));
    }

    public boolean cancelPaydayDebit(UUID chargeReference, UUID payer, UUID reservationReference, UUID transactionReference) {
        String fingerprint = fingerprint("cancel-payday-debit", chargeReference, payer, reservationReference);
        return ledger.cancelPaydayCharge(chargeReference, payer, reservationReference, transactionReference, fingerprint);
    }

    /** Queue income for the recipient's next payday, with a receipt and a reference that makes retries safe. */
    public boolean pushIncome(UUID recipient, long amount, UUID reference, String description) {
        return pushIncome(recipient, amount, reference, description, true);
    }

    /** Queue income for payday without taxing it again. Reusing the reference won't add it twice. */
    public boolean pushNonTaxableIncome(UUID recipient, long amount, UUID reference, String description) {
        return pushIncome(recipient, amount, reference, description, false);
    }

    /** Save one gross/tax/net marketplace receipt and queue its net payout for payday. */
    public boolean pushMarketplaceIncome(UUID recipient, UUID reference, String description,
                                         MarketplaceTaxService.Breakdown breakdown) {
        Objects.requireNonNull(breakdown); Account account = account(recipient);
        String fingerprint = fingerprint("push-marketplace-income", recipient, breakdown.sourceType(),
                breakdown.gross(), breakdown.taxBasisPoints(), breakdown.tax(), breakdown.net(), description);
        if (ledger.transactionRecorded(reference, fingerprint)) return false;
        Account updated = breakdown.net() == 0 ? account
                : account.pending(Math.addExact(account.pendingIncome(), breakdown.net()));
        Receipt receipt = Receipt.marketplace(recipient, description,
                breakdown.sourceType().generallyTaxable(), breakdown);
        Map<EconomicOwner, Account> updates = new HashMap<>(); updates.put(EconomicOwner.player(recipient), updated);
        StateTreasury.stage(ledger, updates, breakdown.tax());
        return ledger.commitOwners(reference, fingerprint, updates, List.of(receipt), ledger.companies(), Map.of());
    }

    private boolean pushIncome(UUID recipient, long amount, UUID reference, String description, boolean taxable) {
        Money.positive(amount); Account account = account(recipient);
        String fingerprint = taxable ? fingerprint("push-income", recipient, amount, description)
                : fingerprint("push-income-non-taxable", recipient, amount, description);
        if (ledger.transactionRecorded(reference, fingerprint)) return false;
        Account updated = account.pending(Math.addExact(account.pendingIncome(), amount));
        return ledger.commitTransaction(reference, fingerprint, Map.of(recipient, updated), List.of(),
                List.of(new Receipt(recipient, description, amount, taxable)), Set.of());
    }

    /** Capture auction funds now, using the saved charge and hold records to detect retries. */
    public boolean payReservedDebit(UUID chargeReference) {
        PaydayCharge charge = ledger.paydayCharge(chargeReference);
        if (charge == null) throw new IllegalArgumentException("Unknown reserved payment");
        if (charge.status() == PaydayCharge.Status.PAID) return false;
        if (charge.status() != PaydayCharge.Status.ACTIVE) throw new IllegalArgumentException("Payment was cancelled");
        requireActive(charge.payer());
        // Available funds exclude this whole hold. If they're negative, other commitments aren't fully
        // covered;
        // don't use another auction's reserved money to make up the difference.
        if (getAvailableBalance(charge.payer()) < 0) throw new IllegalArgumentException("Missing funds.");
        UUID reference = stableReference("auction-immediate-debit", chargeReference.toString());
        Account next = account(charge.payer()).balance(Math.subtractExact(getBalance(charge.payer()), charge.amount()));
        return ledger.commitPayday(reference, fingerprint("auction-immediate-debit", chargeReference, charge.payer(), charge.amount()),
                Map.of(charge.payer(), next), List.of(), List.of(), Set.of(), List.of(charge.paid(reference)));
    }

    /** Consume the whole hold and optionally pay another UUID in the same commit. */
    public boolean captureReservation(UUID owner, UUID reservationReference, UUID receiver, UUID transactionReference) {
        Reservation reservation = ledger.reservation(reservationReference);
        if (reservation == null) throw new IllegalArgumentException("Unknown reservation");
        String fingerprint = fingerprint("capture", owner, reservationReference, receiver, reservation.amount());
        if (ledger.transactionRecorded(transactionReference, fingerprint)) return false;
        if (!reservation.active()) throw new IllegalArgumentException("Reservation is no longer active");
        if (!reservation.owner().equals(owner)) throw new IllegalArgumentException("Reservation belongs to another account");
        Account payer = account(owner);
        Map<UUID, Account> updates = new HashMap<>();
        updates.put(owner, payer.balance(Math.subtractExact(payer.balance(), reservation.amount())));
        if (receiver != null) {
            if (receiver.equals(owner)) throw new IllegalArgumentException("You cannot pay yourself");
            Account recipient = account(receiver);
            updates.put(receiver, recipient.balance(Math.addExact(recipient.balance(), reservation.amount())));
        }
        return ledger.capture(transactionReference, fingerprint, reservationReference, owner, updates);
    }

    public boolean issueCheck(UUID payer, long amount, UUID claim) {
        Money.positive(amount); requireActive(payer);
        String fingerprint = fingerprint("check-issue", payer, amount, claim);
        if (ledger.transactionRecorded(claim, fingerprint)) return false;
        if (getAvailableBalance(payer) < amount) throw new IllegalArgumentException("Your current balance is insufficient.");
        Account result = account(payer).balance(Math.subtractExact(getBalance(payer), amount));
        return ledger.issueCheck(claim, payer, amount, result, fingerprint);
    }

    public boolean issueCheck(long amount, UUID claim) {
        Money.positive(amount);
        String fingerprint = fingerprint("check-issue-funded", amount, claim);
        return ledger.issueCheck(claim, amount, fingerprint);
    }

    public boolean redeemCheck(UUID recipient, UUID claim) {
        UUID redemption = stableReference("check-redemption", claim.toString());
        String fingerprint = fingerprint("check-redeem", recipient, claim);
        if (ledger.transactionRecorded(redemption, fingerprint)) return false;
        requireActive(recipient);
        long value = ledger.checkValue(claim);
        if (value <= 0) throw new IllegalArgumentException("This check has already been redeemed or is invalid.");
        Account result = account(recipient).balance(Math.addExact(getBalance(recipient), value));
        return ledger.redeemCheck(claim, redemption, recipient, result, fingerprint);
    }

    /** Admin/import balance changes can still leave an account in debt, as older saves allowed. */
    public void adjust(UUID id, long delta) { adjust(id, delta, UUID.randomUUID(), "account adjustment"); }
    public boolean adjust(UUID id, long delta, UUID reference, String reason) {
        if (delta == 0) throw new IllegalArgumentException("Amount must not be zero");
        String fingerprint = fingerprint("adjust", id, delta, reason);
        if (ledger.transactionRecorded(reference, fingerprint)) return false;
        Account result = account(id).balance(Math.addExact(getBalance(id), delta));
        return ledger.commitTransaction(reference, fingerprint, Map.of(id, result), List.of(), List.of(), Set.of());
    }

    public void set(UUID id, long cents) { set(id, cents, UUID.randomUUID(), "balance set"); }
    public boolean set(UUID id, long cents, UUID reference, String reason) {
        String fingerprint = fingerprint("set", id, cents, reason);
        if (ledger.transactionRecorded(reference, fingerprint)) return false;
        return ledger.commitTransaction(reference, fingerprint, Map.of(id, account(id).balance(cents)), List.of(), List.of(), Set.of());
    }

    public void updateAccount(UUID id, UnaryOperator<Account> update) {
        Account result = Objects.requireNonNull(update.apply(account(id)));
        ledger.commitTransaction(UUID.randomUUID(), fingerprint("metadata", id, result), Map.of(id, result), List.of(), List.of(), Set.of());
    }

    /** For admin, migration and test use. Normal features should use the money methods above. */
    public void replaceAccount(UUID id, Account replacement) {
        Objects.requireNonNull(replacement);
        ledger.commitTransaction(UUID.randomUUID(), fingerprint("replace-account", id, replacement),
                Map.of(id, replacement), List.of(), List.of(), Set.of(id));
    }

    public void importAccount(UUID id, Account account) {
        if (!ledger.contains(id)) ledger.commitTransaction(stableReference("import-account", id.toString()),
                fingerprint("import-account", id, account), Map.of(id, account), List.of(), List.of(), Set.of());
    }

    public boolean settle(UUID reference, String purpose, Map<UUID, Account> updates,
                          Collection<RecurringPayment> removed, Collection<Receipt> received, Set<UUID> settled) {
        String fingerprint = fingerprint("settlement", purpose, reference);
        return ledger.commitTransaction(reference, fingerprint, updates, removed, received, settled);
    }

    public boolean settlePayday(UUID reference, String purpose, Map<UUID, Account> updates,
                                Collection<RecurringPayment> removed, Collection<Receipt> received, Set<UUID> settled,
                                Collection<PaydayCharge> paidCharges) {
        return ledger.commitPayday(reference, fingerprint("payday-settlement", purpose, reference), updates,
                removed, received, settled, paidCharges);
    }

    public void depositAll(Collection<UUID> recipients, long amount, String reason) {
        depositAll(recipients, amount, UUID.randomUUID(), reason);
    }
    public boolean depositAll(Collection<UUID> recipients, long amount, UUID reference, String reason) {
        Money.positive(amount);
        Map<UUID, Account> updates = new HashMap<>();
        recipients.forEach(id -> updates.put(id, account(id).balance(Math.addExact(getBalance(id), amount))));
        return ledger.commitTransaction(reference, fingerprint("batch-deposit", amount, reason, new TreeSet<>(recipients)),
                updates, List.of(), List.of(), Set.of());
    }

    public void debit(UUID id, long amount) { withdraw(id, amount, UUID.randomUUID(), "debit"); }
    public void transfer(UUID payer, UUID receiver, long amount, boolean fee, boolean deferred) {
        transfer(payer, receiver, amount, fee, deferred, deferred ? Receipt.SHOP_SALES : null, UUID.randomUUID());
    }
    public void shopSale(UUID buyer, UUID owner, long amount) { shopSale(buyer, owner, amount, UUID.randomUUID()); }

    public static UUID stableReference(String type, String identity) {
        return UUID.nameUUIDFromBytes(("kuronomics:" + type + ":" + identity).getBytes(StandardCharsets.UTF_8));
    }

    private static String fingerprint(Object... parts) {
        return String.join("|", Arrays.stream(parts).map(String::valueOf).toList());
    }
}

