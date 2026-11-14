package de.kuro.kuronomics.company;

import java.util.UUID;
import static de.kuro.kuronomics.company.Company.Rank.*;

public final class CompanyPermissionService {
    public static final class PermissionDeniedException extends IllegalArgumentException {
        public PermissionDeniedException(String message) { super(message); }
    }
    public enum Capability { MEMBER, STAFF, FINANCE, ASSET, LANDLORD, STATEMENT, SUCCESSION, CLOSE, COMMERCE }
    public static boolean allowed(Company company, UUID actor, Capability capability) {
        var member = company.members().get(actor);
        if (member == null || company.status() != Company.Status.ACTIVE) return false;
        var rank = member.rank();
        return switch (capability) {
            case MEMBER -> true;
            case STAFF, FINANCE, ASSET, LANDLORD -> rank.ordinal() >= DIRECTOR.ordinal();
            case COMMERCE -> rank.ordinal() >= MANAGER.ordinal();
            case SUCCESSION, CLOSE -> rank == CEO;
            case STATEMENT -> rank == CEO || rank == DIRECTOR;
        };
    }
    public static void require(Company company, UUID actor, Capability capability) {
        if (!allowed(company, actor, capability)) throw new PermissionDeniedException("Your rank does not have access to this command.");
    }
    public static void manageRank(Company company, UUID actor, UUID target, Company.Rank next) {
        require(company, actor, Capability.STAFF);
        Company.Rank authority = company.members().get(actor).rank();
        var current = company.members().get(target);
        if (actor.equals(target) || current != null && current.rank().ordinal() >= authority.ordinal()
                || next == CEO || next.ordinal() >= authority.ordinal())
            throw new PermissionDeniedException("You can only manage people below your rank. Use /company ceo to change the CEO.");
    }
    public static void fire(Company company, UUID actor, UUID target) {
        var manager = company.members().get(actor); var employee = company.members().get(target);
        if (company.status() == Company.Status.ACTIVE && manager != null && manager.rank() == MANAGER
                && employee != null && employee.rank() == EMPLOYEE) return;
        manageRank(company, actor, target, EMPLOYEE);
    }
    public static void salary(Company company, UUID actor, UUID target) {
        var manager = company.members().get(actor); var employee = company.members().get(target);
        if (company.status() == Company.Status.ACTIVE && manager != null && manager.rank() == MANAGER
                && employee != null && employee.rank() == EMPLOYEE) return;
        require(company, actor, Capability.FINANCE);
    }
    private CompanyPermissionService() {}
}

