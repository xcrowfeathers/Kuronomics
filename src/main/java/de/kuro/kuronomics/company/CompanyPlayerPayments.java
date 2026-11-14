package de.kuro.kuronomics.company;

import de.kuro.kuronomics.economy.*;
import de.kuro.kuronomics.economy.data.*;
import java.util.*;

/** Include company obligations in the player's normal payday commit. */
public final class CompanyPlayerPayments {
    public record Result(long debit, List<CompanyContract> paid, List<CompanyContract> failed) {}
    public static Result contracts(EconomyLedger ledger, UUID player, long available, UUID payday,
                                    Map<EconomicOwner, Account> updates, CompanyBook.Editor edit) {
        long debit = 0; List<CompanyContract> paid = new ArrayList<>(), failed = new ArrayList<>();
        for (CompanyContract c : ledger.companies().outgoing(EconomicOwner.player(player))) {
            if (available < c.amount()) {
                edit.contracts.remove(c.id()); failed.add(c);
                edit.audit(new CompanyAudit(EconomyService.stableReference("company-contract-failed", payday + ":" + c.id()), System.currentTimeMillis(), null, c.payer(), c.payer(), c.recipient(), "COMPANY_CONTRACT_FAILED", c.amount(), c.id().toString()));
                continue;
            }
            fund(ledger, c.payer(), c.recipient().id(), c.amount(), "Contract", c.id().toString(), "Contract '" + c.name() + "'", payday, updates, edit);
            available = Math.subtractExact(available, c.amount()); debit = Math.addExact(debit, c.amount()); paid.add(c);
        } return new Result(debit, List.copyOf(paid), List.copyOf(failed));
    }
    public static void fund(EconomyLedger ledger, EconomicOwner source, UUID company, long amount, String category,
                             String association, UUID payday, Map<EconomicOwner, Account> updates, CompanyBook.Editor edit) {
        fund(ledger, source, company, amount, category, association, CompanyChat.category(category), payday, updates, edit);
    }
    private static void fund(EconomyLedger ledger, EconomicOwner source, UUID company, long amount, String category,
                             String association, String label, UUID payday, Map<EconomicOwner, Account> updates, CompanyBook.Editor edit) {
        if (amount == 0) return;
        EconomicOwner recipient = EconomicOwner.company(company);
        Company c = ledger.companies().company(company);
        if (c == null || c.status() != Company.Status.ACTIVE) throw new IllegalArgumentException("Company recipient is not active");
        Account account = updates.getOrDefault(recipient, ledger.account(recipient));
        updates.put(recipient, account.pending(Math.addExact(account.pendingIncome(), amount)));
        edit.credit(company, new CompanyCredit(source, category, association, label, amount, true));
        edit.audit(new CompanyAudit(EconomyService.stableReference("company-player-credit", payday + ":" + category + ":" + association), System.currentTimeMillis(), null,
                source, source, recipient, category, amount, association));
    }
    private CompanyPlayerPayments() {}
}
