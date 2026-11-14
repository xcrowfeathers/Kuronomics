package de.kuro.kuronomics.company;

import de.kuro.kuronomics.economy.*;
import de.kuro.kuronomics.economy.data.*;
import de.kuro.kuronomics.economy.payday.PaydayReport;
import static de.kuro.kuronomics.economy.payday.PaydayReport.Group.*;
import java.util.*;

/** One pass over companies, members, contracts and credits. Budgets are frozen; no world access or disk writes. */
public final class CompanyPaydayService {
    public record Metrics(int companies, int members, int contracts, int credits, int commits) {}
    public record Result(boolean committed, long cycleDay, Map<UUID, Company.Statement> statements,
                         List<CompanyContract> failed, Set<UUID> unpaidSalaries, Metrics metrics) {}
    private record Transfer(EconomicOwner payer, EconomicOwner recipient, long amount, String category, String association) {}
    private static final class Budget {
        final Company company;
        final Account original;
        final long released;
        final long limit;
        long balance, available, taxable, openingTax;
        final PaydayReport lines = new PaydayReport();
        Budget(Company c, Account a, long reserved, long limit) {
            company = c; original = a; released = a.pendingIncome();
            this.limit = limit;
            balance = Math.addExact(a.balance(), released); available = Math.subtractExact(balance, reserved);
        }
        boolean debit(long amount) {
            if (available < -limit) return false;
            long capacity = available > Long.MAX_VALUE - limit ? Long.MAX_VALUE : Math.addExact(available, limit);
            if (capacity < amount) return false;
            balance = Math.subtractExact(balance, amount); available = Math.subtractExact(available, amount); return true;
        }
        void income(long amount) { balance = Math.addExact(balance, amount); available = Math.addExact(available, amount); }
    }
    public static Result process(EconomyLedger ledger, CompanyConfig config, long day, long cutoff) {
        return process(ledger, config, day, cutoff, ledger.companies().companies().keySet());
    }
    /** The order can be changed in tests to check that it doesn't change who gets funded. */
    public static Result process(EconomyLedger ledger, CompanyConfig config, long day, long cutoff, Collection<UUID> order) {
        return process(ledger, config, day, cutoff, order, null);
    }
    /** Debug paydays use their own saved retry key and don't advance the daily payday date. */
    public static Result debug(EconomyLedger ledger, CompanyConfig config, long day, long cutoff, UUID request) {
        return process(ledger, config, day, cutoff, ledger.companies().companies().keySet(), Objects.requireNonNull(request));
    }
    private static Result process(EconomyLedger ledger, CompanyConfig config, long day, long cutoff, Collection<UUID> order, UUID debug) {
        if (day < 0 || cutoff < 0) throw new IllegalArgumentException("Invalid Company PayDay cycle");
        CompanyBook book = ledger.companies();
        if (debug == null && day <= book.cycleDay() || debug != null && ledger.hasTransaction(debug)) return new Result(false, day, Map.of(), List.of(), Set.of(), new Metrics(0, 0, 0, 0, 0));
        if (new HashSet<>(order).size() != order.size() || !new HashSet<>(order).equals(book.companies().keySet())) throw new IllegalArgumentException("Incomplete Company settlement order");
        UUID reference = debug == null ? EconomyService.stableReference("company-payday", Long.toString(day)) : debug;
        var loanEdit=ledger.bankLoans().borrowers().isEmpty()?null:ledger.bankLoans().edit();
        var edit = book.edit(); Map<UUID, Budget> budgets = new HashMap<>();
        List<de.kuro.kuronomics.economy.bank.TreasuryMovement> treasury = new ArrayList<>();
        List<Transfer> transfers = new ArrayList<>(); List<CompanyContract> failed = new ArrayList<>(); Set<UUID> unpaid = new HashSet<>();
        int members = 0, contracts = 0, credits = 0; long stateExpense = 0;
        Map<UUID, List<CompanyContract>> stateIncome = new HashMap<>();
        for (CompanyContract c : book.outgoing(EconomicOwner.STATE)) stateIncome.computeIfAbsent(c.recipient().id(), ignored -> new ArrayList<>()).add(c);
        // Freeze every company's budget before moving money between companies.
        for (UUID id : order) {
            Company c = book.company(id); if (c.status() != Company.Status.ACTIVE) continue;
            budgets.put(id, new Budget(c, ledger.account(EconomicOwner.company(id)), ledger.reserved(EconomicOwner.company(id)), config.debtLimit()));
        }
        // Plan each payer against its own frozen budget.
        for (UUID id : order) {
            Budget b = budgets.get(id); if (b == null) continue; EconomicOwner owner = EconomicOwner.company(id);
            long taxable = 0, described = 0;
            for (CompanyCredit credit : book.buffer(id).values()) {
                credits++; described = Math.addExact(described, credit.amount());
                if (credit.taxable()) taxable = Math.addExact(taxable, credit.amount());
                b.lines.add(new Company.Line("INCOMING", credit.label() + " from", credit.amount(), credit.source()));
            }
            if (described != b.released) throw new IllegalStateException("Unfunded Company buffer");
            edit.buffers.remove(id);
            if (config.interestEnabled() && b.original.balance() >= config.interestMinimum()) {
                long interest = Math.min(Money.percent(b.original.balance(), config.interestPercent()), config.interestMaximum());
                if (interest > 0) { stateExpense = Math.addExact(stateExpense, interest); b.income(interest); taxable = Math.addExact(taxable, interest); b.lines.add(new Company.Line("INCOMING", "Interest", interest)); audit(treasury, edit, reference, cutoff, owner, EconomicOwner.STATE, owner, "STATE_INTEREST", interest, ""); }
            }
            for (var fixed : config.fixedPayments()) if (fixed.amount() > 0) {
                stateExpense = Math.addExact(stateExpense, fixed.amount()); b.income(fixed.amount()); b.lines.add(FIXED, new Company.Line("INCOMING", fixed.name(), fixed.amount()));
                audit(treasury, edit, reference, cutoff, owner, EconomicOwner.STATE, owner, "STATE_FIXED", fixed.amount(), fixed.name());
            }
            for (CompanyContract c : stateIncome.getOrDefault(id, List.of())) {
                contracts++; stateExpense = Math.addExact(stateExpense, c.amount()); b.income(c.amount()); b.lines.add(new Company.Line("INCOMING", "Bank contract '" + c.name() + "'", c.amount()));
                audit(treasury, edit, reference, cutoff, owner, EconomicOwner.STATE, owner, "STATE_CONTRACT", c.amount(), c.id().toString());
            }
            b.taxable = taxable;
            long tax = config.incomeTax(taxable); b.openingTax = tax;
            if (tax > 0) {
                b.balance = Math.subtractExact(b.balance, tax); b.available = Math.subtractExact(b.available, tax);
                transfers.add(new Transfer(owner, EconomicOwner.STATE, tax, "COMPANY_TAX", "")); b.lines.add(new Company.Line("OUTGOING", "Income tax", tax));
            }
            for (var fixed : config.fixedPayments()) if (fixed.amount() < 0) {
                long amount = Math.negateExact(fixed.amount());
                if (b.debit(amount)) { transfers.add(new Transfer(owner, EconomicOwner.STATE, amount, "COMPANY_FIXED", fixed.name())); b.lines.add(FIXED, new Company.Line("OUTGOING", fixed.name(), amount)); }
                else { b.lines.add(FIXED, new Company.Line("WARNING", "Unpaid fixed payment: " + fixed.name(), amount)); audit(treasury, edit, reference, cutoff, owner, owner, EconomicOwner.STATE, "COMPANY_FIXED_FAILED", amount, fixed.name()); }
            }
            if(loanEdit!=null && !loanEdit.loans(owner).isEmpty()){
                for(var loan:loanEdit.loans(owner)){long amount=loan.instalment();b.balance=Math.subtractExact(b.balance,amount);b.available=Math.subtractExact(b.available,amount);
                    transfers.add(new Transfer(owner,EconomicOwner.STATE,amount,"BANK_LOAN",loan.id().toString()));b.lines.add(new Company.Line("OUTGOING","Bank loan ("+(loan.paymentsLeft()-1)+" left)",amount));}
                loanEdit.repay(owner);
            }
            // Required fees can push a company past its credit limit. Use buffered income to repay debt first.
            if (b.balance < 0) {
                long fee = Money.percent(Math.negateExact(b.balance), config.debtFeePercent());
                b.balance = Math.subtractExact(b.balance, fee); b.available = Math.subtractExact(b.available, fee);
                if (fee > 0) { transfers.add(new Transfer(owner, EconomicOwner.STATE, fee, "COMPANY_DEBT_FEE", "")); b.lines.add(new Company.Line("OUTGOING", "Debt fee", fee)); }
            }
            for (CompanyContract c : book.outgoing(owner)) if (c.stateContract()) {
                contracts++;
                if (b.debit(c.amount())) {
                    transfers.add(new Transfer(owner, EconomicOwner.STATE, c.amount(), "COMPANY_STATE_CONTRACT", c.id().toString()));
                    b.lines.add(new Company.Line("OUTGOING", "Bank contract '" + c.name() + "'", c.amount()));
                } else {
                    failed.add(c); edit.contracts.remove(c.id()); b.lines.add(new Company.Line("WARNING", "Bank contract '" + c.name() + "' cancelled", c.amount()));
                    audit(treasury, edit, reference, cutoff, owner, owner, EconomicOwner.STATE, "COMPANY_STATE_CONTRACT_FAILED", c.amount(), c.id().toString());
                }
            }
            long salaryTotal = 0;
            for (var member : b.company.members().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                members++; long amount = member.getValue().salary(); if (amount == 0) continue;
                EconomicOwner recipient = EconomicOwner.player(member.getKey());
                if (b.debit(amount)) {
                    salaryTotal = Math.addExact(salaryTotal, amount); transfers.add(new Transfer(owner, recipient, amount, "COMPANY_SALARY", ""));
                } else { unpaid.add(member.getKey()); b.lines.add(new Company.Line("WARNING", "Unpaid salary for", amount, recipient)); audit(treasury, edit, reference, cutoff, owner, owner, recipient, "COMPANY_SALARY_FAILED", amount, ""); }
            }
            if (salaryTotal > 0) b.lines.add(new Company.Line("OUTGOING", "Salaries", salaryTotal));
            for (CompanyContract c : book.outgoing(owner)) {
                if (c.stateContract()) continue;
                contracts++;
                if (b.debit(c.amount())) {
                    transfers.add(new Transfer(owner, c.recipient(), c.amount(), "COMPANY_CONTRACT", c.id().toString()));
                    b.lines.add(new Company.Line("OUTGOING", "Contract '" + c.name() + "' to", c.amount(), c.recipient()));
                } else {
                    failed.add(c); edit.contracts.remove(c.id()); b.lines.add(new Company.Line("WARNING", "Contract '" + c.name() + "' cancelled to", c.amount(), c.recipient()));
                    audit(treasury, edit, reference, cutoff, owner, owner, c.recipient(), "COMPANY_CONTRACT_FAILED", c.amount(), c.id().toString());
                }
            }
        }
        // Don't apply incoming credits until all outgoing budgets have been worked out.
        Map<EconomicOwner, Account> updates = new HashMap<>(); List<Receipt> receipts = new ArrayList<>();
        for (CompanyContract cancelled : failed) if (cancelled.recipient().isCompany()) {
            Budget target = budgets.get(cancelled.recipient().id());
            if (target != null) target.lines.add(new Company.Line("WARNING", "Incoming contract '" + cancelled.name() + "' cancelled from", cancelled.amount(), cancelled.payer()));
        }
        budgets.forEach((id, b) -> updates.put(EconomicOwner.company(id), b.original.pending(0).balance(b.balance)));
        de.kuro.kuronomics.economy.StateTreasury.stage(ledger, updates, Math.negateExact(stateExpense));
        for (Transfer transfer : transfers) {
            EconomicOwner recipient = transfer.recipient();
            Account current = updates.getOrDefault(recipient, ledger.account(recipient)); long credit = transfer.amount();
            if (recipient.isCompany()) {
                Budget target = budgets.get(recipient.id()); if (target == null) throw new IllegalStateException("Inactive Company recipient");
                updates.put(recipient, current.balance(Math.addExact(current.balance(), credit)));
                target.taxable = Math.addExact(target.taxable, credit);
                target.lines.add(new Company.Line("INCOMING", contractLabel(book, transfer.association()) + " from", transfer.amount(), transfer.payer()));
            } else if (recipient.isPlayer()) {
                updates.put(recipient, current.pending(Math.addExact(current.pendingIncome(), credit)));
                String label = (transfer.category().equals("COMPANY_SALARY") ? "Company Salary: " : "Company " + contractLabel(book, transfer.association()) + ": ") + book.company(transfer.payer().id()).name();
                receipts.add(new Receipt(recipient.id(), label, credit));
            } else updates.put(recipient, current.balance(Math.addExact(current.balance(), credit)));
            audit(treasury, edit, reference, cutoff, transfer.payer(), transfer.payer(), recipient, transfer.category(), transfer.amount(), transfer.association());
        }
        // Tax the whole cycle's income together so splitting it into contracts can't dodge a tax bracket.
        // Incoming credits can't fund outgoing payments in this same cycle.
        for (var entry : budgets.entrySet()) {
            Budget b = entry.getValue(); EconomicOwner owner = EconomicOwner.company(entry.getKey());
            long delta = Math.subtractExact(config.incomeTax(b.taxable), b.openingTax);
            if (delta == 0) continue;
            Account current = updates.get(owner), state = updates.getOrDefault(EconomicOwner.STATE, ledger.account(EconomicOwner.STATE));
            updates.put(owner, current.balance(Math.subtractExact(current.balance(), delta)));
            updates.put(EconomicOwner.STATE, state.balance(Math.addExact(state.balance(), delta)));
            b.lines.add(new Company.Line(delta > 0 ? "OUTGOING" : "INCOMING", delta > 0 ? "Income tax adjustment" : "Income tax refund", Math.abs(delta)));
            audit(treasury, edit, reference, cutoff, owner, delta > 0 ? owner : EconomicOwner.STATE, delta > 0 ? EconomicOwner.STATE : owner, "COMPANY_TAX_ADJUSTMENT", Math.abs(delta), "");
        }
        // All incoming credits are now counted, so bankruptcy checks won't depend on company order.
        Set<UUID> bankrupt = new HashSet<>();
        for (var entry : budgets.entrySet()) {
            EconomicOwner owner = EconomicOwner.company(entry.getKey()); Budget b = entry.getValue();
            long closing = updates.get(owner).balance();
            if (closing >= 0 || Math.negateExact(closing) < config.debtLimit()) continue;
            long debt = Math.negateExact(closing), penalty = Money.percent(debt, config.ceoPenaltyPercent());
            EconomicOwner ceo = EconomicOwner.player(b.company.ceo()); Account personal = updates.getOrDefault(ceo, ledger.account(ceo));
            Account state = updates.getOrDefault(EconomicOwner.STATE, ledger.account(EconomicOwner.STATE));
            updates.put(ceo, personal.balance(Math.subtractExact(personal.balance(), penalty)));
            updates.put(EconomicOwner.STATE, state.balance(Math.addExact(state.balance(), penalty)));
            long remaining=loanEdit==null?0:loanEdit.discharge(owner);if(remaining>0){
                var ceoAccount=updates.get(ceo);updates.put(ceo,ceoAccount.balance(Math.subtractExact(ceoAccount.balance(),remaining)));StateTreasury.stage(ledger,updates,remaining);
                b.lines.add(new Company.Line("WARNING","Remaining bank loans charged to CEO",remaining,ceo));audit(treasury, edit,reference,cutoff,owner,ceo,EconomicOwner.STATE,"BANK_LOAN_CEO",remaining,"");}
            edit.companies.put(entry.getKey(), b.company.bankrupt(new Company.Bankruptcy(debt, penalty, cutoff, remaining)));
            bankrupt.add(entry.getKey());
            b.lines.add(new Company.Line("WARNING", "Bankrupt — CEO charged", penalty, ceo));
            audit(treasury, edit, reference, cutoff, owner, ceo, EconomicOwner.STATE, "COMPANY_BANKRUPTCY_PENALTY", penalty, "");
        }
        if (!bankrupt.isEmpty()) edit.contracts.values().removeIf(c -> c.payer().isCompany() && bankrupt.contains(c.payer().id()) || c.recipient().isCompany() && bankrupt.contains(c.recipient().id()));
        Map<UUID, Company.Statement> statements = new HashMap<>();
        budgets.forEach((id, b) -> {
            List<Company.Line> lines = b.lines.companyLines().stream().sorted(Comparator.comparing(Company.Line::kind).thenComparing(Company.Line::label).thenComparing(l -> l.party() == null ? "" : l.party().toString())).toList();
            Company.Statement s = new Company.Statement(day, cutoff, b.original.balance(), b.released, updates.get(EconomicOwner.company(id)).balance(), lines, debug != null);
            statements.put(id, s); edit.companies.put(id, edit.companies.get(id).statement(s));
        });
        if (debug == null || edit.cycleDay < 0) edit.cycleDay = day;
        // Save the exact access change with this cycle. WorldGuard work happens later.
        for (Budget b : budgets.values()) {
            if (bankrupt.contains(b.company.id())) continue;
            var estates = ledger.estatesOwned(EconomicOwner.company(b.company.id()));
            if (estates.isEmpty()) continue;
            var mapping = CompanyAccess.desired(b.company);
            for (var estate : estates) {
            if (bankrupt.contains(b.company.id())) continue;
            if (edit.operations.containsKey(estate.key())) continue;
            var previous = edit.access.get(estate.key());
            if (previous == null) throw new IllegalStateException("Company property access evidence missing");
            var desired = new CompanyAccess(b.company.id(), mapping.owners(), mapping.members(), previous.purchaseCost());
            if (previous.equals(desired)) continue;
            UUID operation = EconomyService.stableReference("company-access-cycle", (debug == null ? Long.toString(day) : "debug:" + debug) + ":" + estate.key());
            edit.operations.put(estate.key(), new CompanyAssetOperation(operation, null, b.company.id(), estate.key(), CompanyAssetOperation.Kind.SYNC,
                    estate.economicOwner(), 0, cutoff, previous, desired));
            }
        }
        boolean committed = ledger.commitOwnersLoans(reference, "company-payday|" + day + (debug == null ? "" : "|debug|" + debug), updates, receipts, edit.build(), loanEdit==null?ledger.bankLoans():loanEdit.build(),treasury);
        var immutableStatements = Map.copyOf(statements);
        if (committed) {
            ledger.notifyAfterCommit(new de.kuro.kuronomics.api.event.CompanyPaydayCompletedEvent(reference, day, immutableStatements, debug != null));
            for (var contract : failed) ledger.notifyAfterCommit(new de.kuro.kuronomics.api.event.ContractChangedEvent(null, reference, de.kuro.kuronomics.api.event.ContractChangedEvent.Change.UNFUNDED, contract, null));
        }
        return new Result(committed, day, immutableStatements, List.copyOf(failed), Set.copyOf(unpaid), new Metrics(budgets.size(), members, contracts, credits, committed ? 1 : 0));
    }
    private static String contractLabel(CompanyBook book, String association) {
        CompanyContract contract = book.contracts().get(UUID.fromString(association));
        if (contract == null) throw new IllegalStateException("Missing Company contract payment evidence");
        return "Contract '" + contract.name() + "'";
    }
    private static void audit(List<de.kuro.kuronomics.economy.bank.TreasuryMovement> treasury, CompanyBook.Editor edit, UUID cycle, long time, EconomicOwner owner, EconomicOwner payer, EconomicOwner recipient, String category, long amount, String association) {
        if (amount > 0 && !category.endsWith("_FAILED") && (payer.equals(EconomicOwner.STATE) || recipient.equals(EconomicOwner.STATE)))
            treasury.add(new de.kuro.kuronomics.economy.bank.TreasuryMovement(payer,recipient,amount,category.endsWith("FIXED") && !association.isBlank()?"Fixed: "+association:category,null));
        edit.auditDeferred(() -> {
        UUID reference = EconomyService.stableReference("company-cycle-entry", cycle + ":" + owner + ":" + payer + ":" + recipient + ":" + category + ":" + association);
        return new CompanyAudit(reference, time, null, owner, payer, recipient, category, amount, association);
        });
    }
    private CompanyPaydayService() {}
}
