package de.kuro.kuronomics.menu;

import de.kuro.kuronomics.block.MachineKind;

/** Share these positions so server slots line up with the GUI artwork. */
public record MachineLayout(int width, int height, String texture, int inventoryX, int inventoryY, boolean playerInventory) {
    public static MachineLayout of(MachineKind kind, int page) {
        if (kind == MachineKind.ATM) return switch (page) {
            case 1 -> new MachineLayout(237, 176, "atm_deposit", 0, 0, false);
            case 2 -> new MachineLayout(237, 176, "atm_withdraw", 0, 0, false);
            case 3 -> new MachineLayout(303, 190, "atm_checks", 72, 96, true);
            case 4 -> new MachineLayout(299, 188, "atm", 0, 0, false);
            default -> new MachineLayout(299, 188, "atm", 0, 0, false);
        };
        if (kind.vending()) return new MachineLayout(175, 213, kind == MachineKind.VENDING ? "vending_gui" : kind == MachineKind.STACK_VENDING ? "stack_vending_gui" : "creative_vending_gui", 8, 126, true);
        if (kind.shop()) return new MachineLayout(176, 166, kind == MachineKind.SHOP || kind == MachineKind.COMPANY_SHOP ? "shop_gui_common" : kind == MachineKind.ADMIN_SHOP ? "shop_gui" : "shop_gui_server", 8, 84, true);
        if (kind == MachineKind.PRINTER) return new MachineLayout(176, 120, "printer_gui", 8, 38, true);
        return new MachineLayout(255, 169, "check_pass", 48, 85, true);
    }
    public static int slotX(MachineKind kind, int slot) {
        if (kind == MachineKind.ATM) return new int[]{8, 260, 224, 161}[slot];
        if (kind.vending()) return 25 + slot % 7 * 18;
        if (kind.shop()) return 7 + slot % 9 * 18;
        return kind == MachineKind.PRINTER ? 7 : 29;
    }
    public static int slotY(MachineKind kind, int slot) {
        if (kind == MachineKind.ATM) return slot == 1 ? 83 : 56;
        if (kind.vending()) return 22 + slot / 7 * 27;
        if (kind.shop()) return 26 + slot / 9 * 18;
        return kind == MachineKind.PRINTER ? 12 : 27;
    }
}
