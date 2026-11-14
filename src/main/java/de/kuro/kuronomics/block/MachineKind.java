package de.kuro.kuronomics.block;

public enum MachineKind {
    ATM("atm_block", 4), VENDING("vending_machine", 21), PASS_READER("block_pass_reader", 1),
    ADMIN_VENDING("vending_machine_admin", 21), STACK_VENDING("vending_machine_stack", 21),
    ID_CHECKER("id_checker", 0), ADMIN_SHOP("shop_block_admin", 18), SERVER_SHOP("shop_block_server", 18),
    SHOP("shop_block", 18), PRINTER("printer", 1), COMPANY_SHOP("company_shop", 18);
    public final String id;
    public final int slots;
    MachineKind(String id, int slots) { this.id = id; this.slots = slots; }
    public boolean vending() { return this == VENDING || this == ADMIN_VENDING || this == STACK_VENDING; }
    public boolean shop() { return this == SHOP || this == ADMIN_SHOP || this == SERVER_SHOP || this == COMPANY_SHOP; }
    public boolean unlimited() { return this == ADMIN_VENDING || this == ADMIN_SHOP || this == SERVER_SHOP; }
    public boolean wholeStack() { return this == STACK_VENDING || this == ADMIN_VENDING || this == ADMIN_SHOP || this == SERVER_SHOP; }
}
