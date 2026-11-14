package de.kuro.kuronomics.company;

import de.kuro.kuronomics.util.ModText;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.*;

public final class CompanyChat {
    public static MutableComponent card(String title, String body) {
        return ModText.legacy("§8--- §6§l" + title + " §8---\n§r" + body);
    }
    public static MutableComponent finish(MutableComponent card) { return card.append(ModText.legacy("\n§8--------------------------------")); }
    public static Component confirm(String title, String warning, String label, String command, int seconds) {
        return finish(card(title, warning + "\n\n").append(button(label, command, ChatFormatting.GREEN))
                .append(ModText.legacy("\n§7Expires in " + seconds + " seconds.")));
    }
    public static MutableComponent button(String label, String command, ChatFormatting color) {
        return Component.literal("[" + label + "]").withStyle(s -> s.withColor(color).withBold(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to " + label.toLowerCase(java.util.Locale.ROOT)))));
    }
    public static String rank(Company.Rank rank) { return rank == Company.Rank.CEO ? "CEO" : rank.name().substring(0, 1) + rank.name().substring(1).toLowerCase(java.util.Locale.ROOT); }
    public static String category(String value) {
        return switch (value) {
            case "COMPANY_PAYMENT" -> "Payment";
            case "COMPANY_CAPITAL" -> "Deposit";
            case "COMPANY_SALARY" -> "Salary";
            case "COMPANY_CONTRACT" -> "Contract";
            case "COMPANY_DEBT_FEE" -> "Debt fee";
            case "COMPANY_BANKRUPTCY_PENALTY" -> "Bankruptcy penalty";
            default -> {
                String text = value.replace("COMPANY_", "").replace("_", " ").toLowerCase(java.util.Locale.ROOT);
                yield text.isEmpty() ? "Update" : Character.toUpperCase(text.charAt(0)) + text.substring(1);
            }
        };
    }
    private CompanyChat() {}
}
