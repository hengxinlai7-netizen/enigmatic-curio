package com.hengx.enigmaticcurio;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;

/**
 * 「救赎之戒」增强的公共判定。
 *
 * <p>物品本体是神秘遗物扩展的 {@code enigmaticaddons:bless_ring}
 * (类 {@code auviotre.enigmatic.addon.contents.items.BlessRing},是 curio),
 * 三项需求都由 mixin 拦它自己的方法实现:</p>
 * <ul>
 *   <li>去惩罚条 / 不再破碎 → {@code mixin.BlessRingBarMixin} + {@code mixin.BlessRingHelperMixin};</li>
 *   <li>可与七咒之戒同戴 → {@code mixin.BlessRingMixin}(canEquip);</li>
 *   <li>同戴时不触发每 tick 的惩罚流程 → {@code mixin.BlessRingTickMixin}(curioTick)。</li>
 * </ul>
 *
 * <p>本类只做"配置 + 身上有没有某枚戒指"的判定,全部走反射封装({@link Superposition}),
 * 缺神秘遗物 / 缺扩展时安全返回 false,不会让 mixin 抛异常。</p>
 */
public final class RingHandler {

    /** 救赎之戒的注册 id。 */
    public static final String RING_ID = "enigmaticaddons:bless_ring";

    /** 七咒之戒的注册 id(神秘遗物本体)。 */
    public static final String CURSED_RING_ID = "enigmaticlegacy:cursed_ring";

    private static Item ring;
    private static Item cursedRing;

    private RingHandler() {
    }

    /** 总开关(救赎之戒增强)。 */
    public static boolean enabled() {
        try {
            return CurioConfig.RING_ENHANCED.get();
        }
        catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 救赎之戒物品(取不到时为 null)。 */
    public static Item ring() {
        try {
            if (ring == null) {
                ring = Superposition.item(RING_ID);
            }
            return ring;
        }
        catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** 七咒之戒物品(取不到时为 null)。 */
    public static Item cursedRing() {
        try {
            if (cursedRing == null) {
                cursedRing = Superposition.item(CURSED_RING_ID);
            }
            return cursedRing;
        }
        catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** 该实体身上是否同时戴着救赎之戒与七咒之戒(扩展的"惩罚者"判定就是这两枚)。 */
    public static boolean wearingBoth(LivingEntity entity) {
        try {
            if (entity == null) {
                return false;
            }
            Item bless = ring();
            Item cursed = cursedRing();
            if (bless == null || cursed == null) {
                return false;
            }
            return Superposition.hasCurio(entity, bless) && Superposition.hasCurio(entity, cursed);
        }
        catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
