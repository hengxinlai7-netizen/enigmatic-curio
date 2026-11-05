package com.hengx.enigmaticcurio;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 神秘佳肴:佩戴「无止暴食之证」时,饱食度锁 0、饱和度锁 1。
 *
 * <p>完全不改佳肴的代码,只在玩家 tick 时把食物数据钉住。</p>
 */
public final class GluttonyCharmHandler {

    /** 无止暴食之证。 */
    private static final String CHARM_ID = "enigmaticdelicacy:gluttony_charm";

    private static Item charm;

    private GluttonyCharmHandler() {
    }

    @SubscribeEvent
    public static void onPlayerTick(LivingEvent.LivingTickEvent event) {
        try {
            if (!(event.getEntity() instanceof Player player) || player.level().isClientSide) {
                return;
            }
            if (player.tickCount % 5 != 0) {
                return;   // 每 5 tick 校正一次,足够稳且不耗性能
            }
            if (!CurioConfig.GLUTTONY_LOCK.get()) {
                return;
            }
            if (!wearing(player)) {
                return;
            }
            FoodData food = player.getFoodData();
            if (food.getFoodLevel() != 0) {
                food.setFoodLevel(0);
            }
            if (Math.abs(food.getSaturationLevel() - 1.0F) > 0.01F) {
                food.setSaturation(1.0F);
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    private static boolean wearing(Player player) {
        if (charm == null) {
            charm = Superposition.item(CHARM_ID);
        }
        return charm != null && Superposition.hasCurio(player, charm);
    }
}