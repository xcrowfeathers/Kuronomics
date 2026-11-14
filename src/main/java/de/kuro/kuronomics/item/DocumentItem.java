package de.kuro.kuronomics.item;

import de.kuro.kuronomics.event.EconomyEvents;
import de.kuro.kuronomics.economy.EconomyService;
import de.kuro.kuronomics.economy.data.EconomyLedger;
import java.time.LocalDate;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

public final class DocumentItem extends Item {
    private final int color;
    public DocumentItem(Properties properties, int color) { super(properties); this.color = color; }
    @Override public Component getName(ItemStack stack) {
        return super.getName(stack).copy().withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE);
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof ServerPlayer serverPlayer) {
            var old = Prices.data(stack);
            if (old.getString("uuid").isEmpty() || player.isShiftKeyDown() && old.getString("uuid").equals(player.getStringUUID())) {
                var account = new EconomyService(EconomyLedger.get(serverPlayer.server)).account(player.getUUID());
                CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
                    tag.putString("uuid", player.getStringUUID()); tag.putString("name", player.getGameProfile().getName());
                    if (!tag.contains("date")) tag.putString("date", LocalDate.now().toString());
                    tag.putString("ship", color < 4 ? EconomyEvents.config().city(color) : "Driver's License");
                    tag.putString("status", de.kuro.kuronomics.jobs.JobService.get(serverPlayer.server).displayName(account)); tag.putString("entry", account.entry());
                });
                player.displayClientMessage(Component.literal("Pass successfully validated.").withStyle(net.minecraft.ChatFormatting.GREEN), true);
                level.playSound(null, player.blockPosition(), net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, net.minecraft.sounds.SoundSource.PLAYERS, 1, 1);
            } else {
                de.kuro.kuronomics.menu.DocumentMenu.open(serverPlayer, stack);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        var data = Prices.data(stack);
        for (String key : new String[]{"name", "date", "ship", "status", "entry"}) {
            String value = key.equals("status") ? jobDisplayName(data.getString(key)) : data.getString(key);
            if (!value.isBlank()) tooltip.add(Component.literal(value).withStyle(net.minecraft.ChatFormatting.BLUE));
        }
    }
    /** Old cards put the whole job summary in status. Only show the job name. */
    public static String jobDisplayName(String status) {
        int staticMode = status.indexOf(" | STATIC | ");
        int taskMode = status.indexOf(" | TASK_BASED | ");
        int summaryStart = staticMode >= 0 ? staticMode : taskMode;
        return summaryStart >= 0 ? status.substring(0, summaryStart) : status;
    }
}
