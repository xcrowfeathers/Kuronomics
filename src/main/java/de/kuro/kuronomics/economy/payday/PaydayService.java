package de.kuro.kuronomics.economy.payday;

import de.kuro.kuronomics.config.EconomyConfig;
import de.kuro.kuronomics.economy.Money;
import de.kuro.kuronomics.economy.EconomyService;
import de.kuro.kuronomics.economy.EconomicOwner;
import de.kuro.kuronomics.company.*;
import de.kuro.kuronomics.economy.data.*;
import java.math.BigDecimal;
import java.util.*;

public final class PaydayService {
    private static void treasury(List<de.kuro.kuronomics.economy.bank.TreasuryMovement> movements, UUID player, long delta, String label) {
        if (delta != 0) movements.add(de.kuro.kuronomics.economy.bank.TreasuryMovement.payment(EconomicOwner.player(player),delta,
                label.length()>128?label.substring(0,128):label));
    }
    public record Interest(boolean enabled, BigDecimal percent, long minimum, long maximum) {}
    public record Item(Kind kind, String label, Long amount) { public enum Kind { INCOMING, OUTGOING, WARNING, INFO } }
    public record RentWarning(String estate, long amount) {}
    public record Result(long opening, long closing, List<String> lines, List<RecurringPayment> cancelled,
                         List<Item> items, List<RentWarning> rentWarnings, List<Receipt> received,
                         List<PaydayCharge> paidCharges, List<PaydayCharge> failedCharges, UUID reference) {}
    private PaydayService() {}

    /** Calculate everything before committing, so overflow can't leave a half-paid payday. */
    public static Result process(EconomyLedger ledger, UUID player, long expectedSequence,
        EconomyConfig config, Interest interest, java.util.function.Predicate<de.kuro.kuronomics.estate.Estate> rentUsable,
        java.util.function.Predicate<PaydayCharge> chargeUsable) {
        EconomyService economy = new EconomyService(ledger);
        Account original = economy.account(player);
        if (original.paydaySequence() != expectedSequence) throw new IllegalArgumentException("Payday already processed");
        UUID reference = EconomyService.stableReference("payday", player + ":" + expectedSequence);
        long balance = original.balance(), available = economy.getAvailableBalance(player), taxable = 0, stateDelta = 0;
        PaydayReport report = new PaydayReport();
        List<de.kuro.kuronomics.economy.bank.TreasuryMovement> treasury = new ArrayList<>();
        if (interest.enabled() && balance >= interest.minimum()) {
            long amount = Math.min(Money.percent(balance, interest.percent()), interest.maximum());
            balance = Math.addExact(balance, amount); available = Math.addExact(available, amount); taxable = Math.addExact(taxable, amount);
            stateDelta = Math.subtractExact(stateDelta, amount);
            treasury(treasury,player,-amount,"Interest");
            report(report, PaydayReport.Group.INTEREST, Item.Kind.INCOMING, "Interest", amount);
        }
        long nonTaxablePending = ledger.nonTaxablePending(player);
        if (nonTaxablePending < 0 || nonTaxablePending > original.pendingIncome()) throw new IllegalStateException("Invalid non-taxable PayDay income");
        balance = Math.addExact(balance, original.pendingIncome()); available = Math.addExact(available, original.pendingIncome());
        taxable = Math.addExact(taxable, Math.subtractExact(original.pendingIncome(), nonTaxablePending));
        long described = 0;
        for (Receipt receipt : ledger.marketplaceReceipts(player)) {
            described = Math.addExact(described, receipt.amount());
            String source = switch (receipt.sourceType()) {
                case AUCTION_SALE -> "Auction Sale";
                case REAL_ESTATE_SALE -> "Real Estate Sale";
                case GLOBAL_MARKET_SALE -> "Marketplace Sale";
            };
            report(report, salesGroup(receipt), Item.Kind.INFO, source + " gross", receipt.grossAmount());
            report(report, PaydayReport.Group.SALES_TAX, Item.Kind.INFO, source + " sales tax withheld", receipt.marketplaceTax());
            report(report, salesGroup(receipt), Item.Kind.INCOMING, source + " net payout", receipt.netAmount());
        }
        for (var receipt : ledger.receipts(player).entrySet()) {
            described = Math.addExact(described, receipt.getValue());
            report(report, PaydayReport.category(receipt.getKey()), Item.Kind.INCOMING, receipt.getKey().matches("[A-Z]+(?:_[A-Z]+)+") ? de.kuro.kuronomics.company.CompanyChat.category(receipt.getKey()) : receipt.getKey(), receipt.getValue());
        }
        if (original.pendingIncome() > described) report(report, PaydayReport.Group.OTHER, Item.Kind.INCOMING, "Other deferred income", original.pendingIncome() - described);
        long salary = ledger.jobs().salary(original); balance = Math.addExact(balance, salary); available = Math.addExact(available, salary); taxable = Math.addExact(taxable, salary);
        stateDelta = Math.subtractExact(stateDelta, salary);
        treasury(treasury,player,-salary,"Job salary");
        if (salary > 0) report(report, PaydayReport.Group.SALARIES, Item.Kind.INCOMING, "Job Salary", salary);
        List<RecurringPayment> cancelled = new ArrayList<>();
        List<RecurringPayment> contracts = ledger.contracts(player);
        for (RecurringPayment c : contracts) {
            if (!c.bank() || !c.payer().equals(player)) continue;
            if (c.amount() < 0 && available < Math.negateExact(c.amount())) {
                cancelled.add(c); report(report, PaydayReport.Group.BANK_CONTRACTS, Item.Kind.WARNING, "Cancelled " + c.name() + " (insufficient funds)", null);
            } else {
                balance = Math.addExact(balance, c.amount()); available = Math.addExact(available, c.amount());
                stateDelta = Math.subtractExact(stateDelta, c.amount());
                treasury(treasury,player,-c.amount(),"Bank contract: " + c.name());
                report(report, PaydayReport.Group.BANK_CONTRACTS, c.amount() > 0 ? Item.Kind.INCOMING : Item.Kind.OUTGOING, c.name(), magnitude(c.amount()));
            }
        }
        for (EconomyConfig.FixedPayment payment : config.fixedPayments()) {
            balance = Math.addExact(balance, payment.amount()); available = Math.addExact(available, payment.amount());
            stateDelta = Math.subtractExact(stateDelta, payment.amount());
            treasury(treasury,player,-payment.amount(),payment.name());
            report(report, PaydayReport.Group.FIXED, payment.amount() > 0 ? Item.Kind.INCOMING : Item.Kind.OUTGOING, payment.name(), magnitude(payment.amount()));
        }
        long tax = config.tax().calculate(taxable); balance = Math.subtractExact(balance, tax); available = Math.subtractExact(available, tax);
        stateDelta = Math.addExact(stateDelta, tax);
        treasury(treasury,player,tax,"Income tax");
        if (tax > 0) report(report, PaydayReport.Group.TAXES, Item.Kind.OUTGOING, "Tax", tax);
        var nextLoans=ledger.bankLoans();var loans=nextLoans.forBorrower(EconomicOwner.player(player));
        if(!loans.isEmpty()){
            for(var loan:loans.values()){long amount=loan.instalment();balance=Math.subtractExact(balance,amount);available=Math.subtractExact(available,amount);stateDelta=Math.addExact(stateDelta,amount);treasury(treasury,player,amount,"Loan repayment");
                report(report,PaydayReport.Group.BANK_CONTRACTS,Item.Kind.OUTGOING,"Bank loan ("+(loan.paymentsLeft()-1)+" left)",amount);}
            var edit=nextLoans.edit();edit.repay(EconomicOwner.player(player));nextLoans=edit.build();
        }
        Map<UUID, Account> updates = new HashMap<>();
        Map<EconomicOwner, Account> companyUpdates = new HashMap<>();
        var companyBook = ledger.companies().edit();
        List<Receipt> received = new ArrayList<>();
        for (RecurringPayment c : contracts) {
            if (c.bank() || !c.payer().equals(player)) continue;
            if (available < c.amount()) {
                cancelled.add(c); report(report, PaydayReport.Group.CONTRACTS, Item.Kind.WARNING, "Cancelled " + c.name() + " (insufficient funds)", null); continue;
            }
            Account receiver = updates.getOrDefault(c.receiver(), economy.account(c.receiver()));
            updates.put(c.receiver(), receiver.pending(Math.addExact(receiver.pendingIncome(), c.amount())));
            received.add(new Receipt(c.receiver(), "Contract '" + c.name() + "' from " + player, c.amount()));
            balance = Math.subtractExact(balance, c.amount()); available = Math.subtractExact(available, c.amount());
            report(report, PaydayReport.Group.CONTRACTS, Item.Kind.OUTGOING, c.name(), c.amount());
        }
        var companyContracts = CompanyPlayerPayments.contracts(ledger, player, available, reference, companyUpdates, companyBook);
        balance = Math.subtractExact(balance, companyContracts.debit()); available = Math.subtractExact(available, companyContracts.debit());
        for (var c : companyContracts.paid()) report(report, PaydayReport.Group.CONTRACTS, Item.Kind.OUTGOING, "Company contract '" + c.name() + "': " + ledger.companies().company(c.recipient().id()).name(), c.amount());
        for (var c : companyContracts.failed()) report(report, PaydayReport.Group.CONTRACTS, Item.Kind.WARNING, "Company contract '" + c.name() + "' cancelled (insufficient funds): " + ledger.companies().company(c.recipient().id()).name(), null);
        List<PaydayCharge> paidCharges = new ArrayList<>(), failedCharges = new ArrayList<>();
        for (PaydayCharge charge : ledger.paydayCharges(player)) {
            if (!chargeUsable.test(charge)) {
                report(report, PaydayReport.Group.AUCTION_PAYMENTS, Item.Kind.WARNING, "Auction asset unavailable", charge.amount());
                continue;
            }
            Reservation hold = ledger.reservation(charge.reservationReference());
            boolean validHold = hold != null && hold.active() && hold.owner().equals(player) && hold.amount() == charge.amount();
            boolean affordable = false;
            if (validHold) try { affordable = Math.addExact(available, hold.amount()) >= charge.amount(); }
            catch (ArithmeticException overflow) { affordable = false; }
            if (!validHold || !affordable) {
                failedCharges.add(charge); report(report, PaydayReport.Group.AUCTION_PAYMENTS, Item.Kind.WARNING, "Auction payment pending: " + charge.description(), charge.amount());
                continue;
            }
            balance = Math.subtractExact(balance, charge.amount());
            PaydayCharge paid = charge.paid(reference); paidCharges.add(paid);
            report(report, PaydayReport.Group.AUCTION_PAYMENTS, Item.Kind.OUTGOING, charge.description(), charge.amount());
        }
        List<String> evictions = new ArrayList<>();
        List<RentWarning> rentWarnings = new ArrayList<>();
        Set<String> pendingEvictions = ledger.evictions();
        for (var estate : ledger.estatesForTenant(player)) {
            if (!rentUsable.test(estate)) {
                report(report, PaydayReport.Group.RENTS, Item.Kind.WARNING, "Property unavailable: " + estate.region(), null);
                continue;
            }
            if (pendingEvictions.contains(estate.key())) continue;
            if (available < estate.rent()) {
                evictions.add(estate.key()); rentWarnings.add(new RentWarning(estate.region(), estate.rent()));
                report(report, PaydayReport.Group.RENTS, Item.Kind.WARNING, "Rent unpaid: " + estate.region(), estate.rent()); continue;
            }
            balance = Math.subtractExact(balance, estate.rent()); available = Math.subtractExact(available, estate.rent());
            report(report, PaydayReport.Group.RENTS, Item.Kind.OUTGOING, "Rent " + estate.region(), estate.rent());
            if (estate.economicOwner().isCompany()) {
                CompanyPlayerPayments.fund(ledger, EconomicOwner.player(player), estate.economicOwner().id(), estate.rent(), "Rent " + estate.region(), estate.key(), reference, companyUpdates, companyBook);
            } else if (estate.owner() != null) {
                Account owner = updates.getOrDefault(estate.owner(), economy.account(estate.owner()));
                updates.put(estate.owner(), owner.pending(Math.addExact(owner.pendingIncome(), estate.rent())));
                if (estate.rent() > 0) received.add(new Receipt(estate.owner(), "Rent " + estate.region() + " from " + player, estate.rent()));
            } else { stateDelta = Math.addExact(stateDelta, estate.rent()); treasury(treasury,player,estate.rent(),"State rent: " + estate.region()); }
        }
        Account paidAccount = original.paid(balance);
        if (!ledger.jobs().jobs().containsKey(original.job())) paidAccount = paidAccount.earnings(original.jobEarnings(), original.taskEarnings());
        updates.put(player, paidAccount);
        companyUpdates.putAll(EconomyLedger.personalUpdates(updates));
        de.kuro.kuronomics.economy.StateTreasury.stage(ledger, companyUpdates, stateDelta);
        ledger.commitPaydayLoans(reference, "payday-settlement|payday:" + player + ":" + expectedSequence + "|" + reference,
                companyUpdates, cancelled, received, Set.of(player), paidCharges, companyBook.build(),nextLoans,treasury);
        evictions.forEach(ledger::queueEviction);
        ledger.notifyAfterCommit(new de.kuro.kuronomics.api.event.PlayerPaydayCompletedEvent(player, reference, expectedSequence, original.balance(), balance));
        for (var failed : companyContracts.failed()) ledger.notifyAfterCommit(new de.kuro.kuronomics.api.event.ContractChangedEvent(null, reference, de.kuro.kuronomics.api.event.ContractChangedEvent.Change.UNFUNDED, failed, null));
        for (var failed : cancelled) {
            EconomicOwner personal = EconomicOwner.player(failed.payer());
            var contract = new de.kuro.kuronomics.api.ContractApi.PersonalContract(failed.bank() && failed.amount() > 0 ? EconomicOwner.STATE : personal,
                    failed.bank() ? (failed.amount() > 0 ? personal : EconomicOwner.STATE) : EconomicOwner.player(failed.receiver()), Math.abs(failed.amount()), failed.name());
            ledger.notifyAfterCommit(new de.kuro.kuronomics.api.event.ContractChangedEvent(null, reference, de.kuro.kuronomics.api.event.ContractChangedEvent.Change.UNFUNDED, null, contract));
        }
        var entries = report.entries();
        var items = entries.stream().map(e -> new Item(e.kind(), e.label(), e.amount())).toList();
        var lines = entries.stream().map(e -> (e.kind() == Item.Kind.WARNING ? "! " : e.kind() == Item.Kind.INFO ? "" : e.kind() == Item.Kind.OUTGOING ? "- " : "+ ")
                + e.label() + (e.amount() == null ? "" : ": " + Money.format(e.amount()))).toList();
        return new Result(original.balance(), balance, lines, List.copyOf(cancelled),
                List.copyOf(items), List.copyOf(rentWarnings), List.copyOf(received), List.copyOf(paidCharges),
                List.copyOf(failedCharges), reference);
    }

    private static void report(PaydayReport report, PaydayReport.Group group, Item.Kind kind, String label, Long amount) {
        report.add(group, kind, label, amount, null);
    }
    private static void report(PaydayReport report, PaydayReport.Group group, Item.Kind kind, String label, long amount) {
        report.add(group, kind, label, amount, null);
    }
    private static PaydayReport.Group salesGroup(Receipt receipt) {
        return switch (receipt.sourceType()) {
            case AUCTION_SALE -> PaydayReport.Group.AUCTION_SALES;
            case REAL_ESTATE_SALE -> PaydayReport.Group.PROPERTY_SALES;
            case GLOBAL_MARKET_SALE -> PaydayReport.Group.MARKET_SALES;
        };
    }
    private static long magnitude(long amount) { return amount < 0 ? Math.negateExact(amount) : amount; }
}
