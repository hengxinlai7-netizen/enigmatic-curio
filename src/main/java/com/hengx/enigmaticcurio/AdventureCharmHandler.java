package com.hengx.enigmaticcurio;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 神秘遗物扩展:冒险者徽章 / 绝望者徽章。
 *
 * <ul>
 *   <li>去除"无法挖掘"的限制 —— <b>这一项不在本类里做</b>,见
 *       {@link com.hengx.enigmaticcurio.mixin.AddonMiningMixin}:扩展压制的做法是把
 *       {@code PlayerEvent$BreakSpeed} <b>取消</b>,不是把破坏速度改小,
 *       所以只能在 mixin 里拦那两条 {@code setCanceled}。原来那版在这里用
 *       {@code getNewSpeed() == 0} 判断、再 {@code setNewSpeed(...)},两个前提都不成立,
 *       因此实测完全没效果(已删除,避免再被误认为生效);</li>
 *   <li>手持冒险者徽章死亡 ⇒ 自动转化成绝望者徽章。</li>
 * </ul>
 */
public final class AdventureCharmHandler {

    private static final String CHARM_ID = "enigmaticaddons:adventure_charm";
    private static final String INSIGNIA_ID = "enigmaticaddons:despair_insignia";

    private AdventureCharmHandler() {
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