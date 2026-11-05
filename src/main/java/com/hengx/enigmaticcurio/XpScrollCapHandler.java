package com.hengx.enigmaticcurio;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 神秘遗物扩展:无知诅咒卷轴 —— 给经验加上限,防止溢出导致报废。
 *
 * <p>佩戴/携带该卷轴时,把一次经验获得的数值夹到配置上限以内。</p>
 */
public final class XpScrollCapHandler {

    private static final String SCROLL_ID = "enigmaticaddons:cursed_xp_scroll";

    private static Item scroll;

    private XpScrollCapHandler() {
    }

    @SubscribeEvent
    public static void onXpDrop(LivingExperienceDropEvent event) {
        try {
            if (!CurioConfig.XP_SCROLL_CAP.get()) {
                return;
            }
            Player player = event.getAttackingPlayer();
            if (player == null) {
                return;
            }
            if (scroll == null) {
                scroll = Superposition.item(SCROLL_ID);
            }
            if (scroll == null) {
                return;
            }
            boolean carried = Superposition.hasCurio(player, scroll);
            if (!carried) {
                for (var s : player.getInventory().items) {
                    if (!s.isEmpty() && s.is(scroll)) {
                        carried = true;
                        break;
                    }
                }
            }
            if (!carried) {
                return;
            }
            int cap = CurioConfig.XP_SCROLL_CAP_VALUE.get();
            if (event.getDroppedExperience() > cap) {
                event.setDroppedExperience(cap);
                DebugLog.info("[卷轴] 经验夹到上限 {}", cap);
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }
}