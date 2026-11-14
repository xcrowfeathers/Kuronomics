package de.kuro.kuronomics.util;

import de.kuro.kuronomics.economy.Money;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.ItemStack;

/** Use styled components so formatting codes don't show up as literal text. */
public final class ModText {
    private ModText() {}

    public static MutableComponent legacy(String text) {
        MutableComponent result = Component.empty();
        Style style = Style.EMPTY;
        int start = 0;
        for (int i = 0; i < text.length() - 1; i++) {
            if (text.charAt(i) != '§') continue;
            ChatFormatting format = ChatFormatting.getByCode(text.charAt(i + 1));
            if (format == null) continue;
            if (i > start) result.append(Component.literal(text.substring(start, i)).setStyle(style));
            style = format == ChatFormatting.RESET ? Style.EMPTY
                    : (format.isColor() ? Style.EMPTY : style).applyFormat(format);
            start = ++i + 1;
        }
        if (start < text.length()) result.append(Component.literal(text.substring(start)).setStyle(style));
        return result;
    }

    public static MutableComponent priceName(ItemStack stack, long cents) {
        return priceLabel(stack.getItem().getName(stack), cents);
    }

    public static MutableComponent priceLabel(Component baseName, long cents) {
        return baseName.copy().withStyle(s -> s.withItalic(false))
                .append(Component.literal(" | ").withStyle(s -> s.withColor(ChatFormatting.GRAY).withItalic(false)))
                .append(Component.literal(Money.formatPrice(cents)).withStyle(s -> s.withColor(ChatFormatting.GREEN).withItalic(false)));
    }

    public static MutableComponent checkName(long cents) { return checkName("Check", cents); }

    public static MutableComponent checkName(String label, long cents) {
        String amount = Money.format(cents);
        return Component.literal(label + " (").withStyle(s -> s.withItalic(false))
                .append(Component.literal("$").withStyle(s -> s.withBold(true).withColor(ChatFormatting.AQUA).withItalic(false)))
                .append(Component.literal(amount.substring(1)).withStyle(s -> s.withBold(true).withColor(ChatFormatting.YELLOW).withItalic(false)))
                .append(Component.literal(")").withStyle(s -> s.withItalic(false)));
    }

    public static MutableComponent paydayLine(String line) {
        ChatFormatting color = line.startsWith("+") ? ChatFormatting.GREEN
                : line.startsWith("-") || line.contains(": $-") ? ChatFormatting.RED
                : line.startsWith("! ") || line.startsWith("Cancelled") || line.startsWith("Rent cancelled") ? ChatFormatting.YELLOW
                : ChatFormatting.GRAY;
        return Component.literal(line).withStyle(color);
    }

    public static MutableComponent estate(String body) { return legacy("§6◆ §e§lESTATE §6◆ §7" + body); }
    public static MutableComponent rent(String body) { return legacy("§6◆ §e§lRENT §6◆ §7" + body); }
}
