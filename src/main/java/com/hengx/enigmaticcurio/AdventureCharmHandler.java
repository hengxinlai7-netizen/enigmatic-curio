package com.hengx.enigmaticcurio;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 神秘遗物扩展:冒险者徽章 / 绝望者徽章。
 *
 * <ul>
 *   <li>去除"无法挖掘"的限制(取消 BreakSpeed 上的压制);</li>
 *   <li>手持冒险者徽章死亡 ⇒ 自动转化成绝望者徽章。</li>
 * </ul>
 */
public final class AdventureCharmHandler {

    private static final String CHARM_ID = "enigmaticaddons:adventure_charm";
    private static final String INSIGNIA_ID = "enigmaticaddons:despair_insignia";

    private AdventureCharmHandler() {
    }

    /** 允许挖掘:把被压到 0 的破坏速度还原成正常速度。 */
    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        try {
            if (!CurioConfig.ADVENTURE_CHARM_MINING.get()) {
                return;
            }
            Player player = event.getEntity();
            if (player == null) {
                return;
            }
            if (event.getNewSpeed() > 0.0F) {
                return;   // 没被压制
            }
            Item charm = Superposition.item(CHARM_ID);
            if (charm == null) {
                return;
            }
            boolean wearing = Superposition.hasCurio(player, charm);
            if (!wearing) {
                return;
            }
            float restored = player.getDestroySpeed(event.getState());
            if (restored > 0.0F) {
                event.setNewSpeed(restored);
                DebugLog.info("[徽章] 放开 {} 的挖掘限制,速度={}", player.getName().getString(), restored);
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    /** 手持冒险者徽章死亡 ⇒ 转化成绝望者徽章。 */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        try {
            if (!CurioConfig.ADVENTURE_CHARM_CONVERT.get()) {
                return;
            }
            if (!(event.getEntity() instanceof Player player) || player.level().isClientSide) {
                return;
            }
            Item charm = Superposition.item(CHARM_ID);
            Item insignia = Superposition.item(INSIGNIA_ID);
            if (charm == null || insignia == null) {
                return;
            }
            boolean converted = false;
            for (ItemStack s : player.getInventory().items) {
                if (!s.isEmpty() && s.is(charm)) {
                    s.shrink(1);
                    converted = true;
                    break;
                }
            }
            if (!converted && !player.getOffhandItem().isEmpty() && player.getOffhandItem().is(charm)) {
                player.getOffhandItem().shrink(1);
                converted = true;
            }
            if (converted) {
                ItemStack reward = new ItemStack(insignia);
                if (!player.getInventory().add(reward)) {
                    player.drop(reward, false);
                }
                DebugLog.info("[徽章] {} 死亡,冒险者徽章 -> 绝望者徽章", player.getName().getString());
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }
}