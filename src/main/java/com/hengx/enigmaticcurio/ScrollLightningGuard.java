package com.hengx.enigmaticcurio;

import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 「万钧之护」雷击开关的<b>兜底闸</b>(普通 Forge 监听器,不是 mixin)。
 *
 * <h2>为什么要有一层不依赖 mixin 的兜底</h2>
 * <p>扩展的雷击开关是靠 {@code AddonThunderLightningMixin} 注入到
 * {@code auviotre.enigmatic.addon.handlers.AddonEventHandler} 里实现的。注入是"按方法名 + 描述符"
 * 匹配的:扩展哪天一改版(方法改名、逻辑搬家、换类),注入就会失配 —— 而我们配置了
 * {@code injectors.defaultRequire: 0},失配是<b>静默</b>的,玩家只会看到"开关又没用了"。</p>
 *
 * <p>所以这里再挂一条完全独立的保险:只要本模组的开关是<b>关</b>的,就无视扩展到底有没有写电荷,
 * 在 {@link LivingHurtEvent} 的 {@link EventPriority#LOWEST}(扩展的 {@code onEntityHurt} 是
 * {@code NORMAL},一定先跑)把目标的 {@code Electric} 清掉。<b>开关开着时第一行就返回</b>,
 * 不给正常战斗的热路径增加任何反射开销。</p>
 *
 * <p>这一层只在"关掉雷击"时才有存在感,所以它<b>不会</b>影响开关开着时的任何手感。</p>
 */
public final class ScrollLightningGuard {

    private ScrollLightningGuard() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingHurt(LivingHurtEvent event) {
        ScrollHandler.applyHurtFallback(event);
    }
}
