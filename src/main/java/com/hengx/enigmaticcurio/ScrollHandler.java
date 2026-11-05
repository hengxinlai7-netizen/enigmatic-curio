package com.hengx.enigmaticcurio;

import java.util.UUID;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 「万钧之护」({@code enigmaticaddons:thunder_scroll},
 * 类 {@code auviotre.enigmatic.addon.contents.items.ThunderScroll})的两项开关。
 *
 * <h2>① 雷击效果可自由开关</h2>
 * 扩展原本的做法(反编译 {@code AddonEventHandler#onEntityHurt}):攻击者戴着卷轴、且主手物品
 * {@code canPerformAction(ToolActions.SWORD_SWEEP)}(剑)时,给**目标**的持久数据 {@code Electric}
 * 累加 {@code 60 + rand(80) + (int)(伤害*10)};{@code AddonEventHandler#onTick} 里 {@code Electric}
 * 超过 1200 就给目标劈一道雷。
 * 本模组把"累积电荷"这一步做成可关({@link CurioConfig#SCALE_LIGHTNING}):
 * 关掉后目标永远累积不到阈值,自然就不会落雷,护甲穿透等其它效果不受影响。
 *
 * <h2>② 加成效果可自由配置</h2>
 * 扩展原本用 {@code MixinEnchantmentHelper.getSweeping} 把
 * {@code EnchantmentHelper#getSweepingDamageRatio}(SRG {@code m_44821_})抬到 {@code max(1.0, 原值)},
 * 于是横扫攻击按 100% 伤害结算。本模组把它还原成原版公式
 * ({@code mixin.EnchantmentHelperSweepMixin},priority 500 抢在扩展之前定值),
 * 作为交换,佩戴者直接获得 {@code generic.attack_damage} 上的百分比修饰符
 * ({@link CurioConfig#SCALE_ATTACK_DAMAGE_PERCENT},默认 +100%)。
 * 想要扩展原来的"只加横扫"手感,把 {@code scaleRestoreSweep} 设成 true 即可。
 */
public final class ScrollHandler {

    /** 万钧之护的注册 id。 */
    public static final String SCROLL_ID = "enigmaticaddons:thunder_scroll";

    /** 目标持久数据里的雷击电荷键(与扩展一致)。 */
    public static final String ELECTRIC = "Electric";

    /** 本模组的攻击伤害修饰符 UUID(只属于本模组)。 */
    public static final UUID ATTACK_DAMAGE_UUID = UUID.fromString("6b1d4d2a-0f3c-4e77-9a51-2c8e7f0d3b14");

    private static final String MODIFIER_NAME = "enigmaticcurio:thunder_scroll_attack_damage";

    /** {@code onEntityHurt} 期间"本次命中是否允许累积电荷":HEAD 处写、putInt 处读,同一次调用内完成。 */
    private static final ThreadLocal<Boolean> CHARGE_ALLOWED = ThreadLocal.withInitial(() -> Boolean.TRUE);

    private static Item scroll;

    private ScrollHandler() {
    }

    // ------------------------------------------------------------------ 配置

    /** 总开关(万钧之护增强)。 */
    public static boolean enabled() {
        try {
            return CurioConfig.SCALE_ENABLED.get();
        }
        catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 雷击子开关。 */
    public static boolean lightningEnabled() {
        try {
            return CurioConfig.SCALE_LIGHTNING.get();
        }
        catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 是否保留扩展原本的"+100% 横扫伤害"(true 时本模组完全不动横扫那一块)。 */
    public static boolean restoreSweep() {
        try {
            return CurioConfig.SCALE_RESTORE_SWEEP.get();
        }
        catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 攻击伤害加成百分比(0 = 不加成)。 */
    public static double attackDamagePercent() {
        try {
            return CurioConfig.SCALE_ATTACK_DAMAGE_PERCENT.get();
        }
        catch (RuntimeException | LinkageError e) {
            return 0.0D;
        }
    }

    // ------------------------------------------------------------------ 物品判定

    /** 万钧之护物品(取不到时为 null)。 */
    public static Item scrollItem() {
        try {
            if (scroll == null) {
                scroll = Superposition.item(SCROLL_ID);
            }
            return scroll;
        }
        catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** 这叠东西是不是万钧之护。 */
    public static boolean isScroll(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) {
                return false;
            }
            Item item = scrollItem();
            return item != null && stack.is(item);
        }
        catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 该实体身上戴着的万钧之护(没有则空栈)。 */
    public static ItemStack wornScroll(LivingEntity entity) {
        try {
            if (entity == null) {
                return ItemStack.EMPTY;
            }
            Item item = scrollItem();
            if (item == null) {
                return ItemStack.EMPTY;
            }
            return Superposition.getCurioStack(entity, item);
        }
        catch (RuntimeException | LinkageError e) {
            return ItemStack.EMPTY;
        }
    }

    // ------------------------------------------------------------------ 雷击开关

    /**
     * 一次"攻击命中"是否允许给目标累积雷击电荷。
     * true = 按扩展原逻辑累积(与本功能无关时一律 true,保证别人的行为不变)。
     */
    public static boolean allowLightningCharge(LivingEntity attacker) {
        try {
            if (!enabled() || !lightningEnabled()) {
                return false;
            }
            if (attacker == null) {
                // 没有攻击者时不是本功能管的范围,不改变原行为
                return true;
            }
            if (wornScroll(attacker).isEmpty()) {
                return true;   // 攻击者没戴卷轴
            }
            return true;
        }
        catch (RuntimeException | LinkageError e) {
            return true;
        }
    }

    /** HEAD 注入调用:把这次 {@code onEntityHurt} 的判定写进 {@link #CHARGE_ALLOWED}。 */
    public static void decideCharge(LivingEntity attacker) {
        try {
            CHARGE_ALLOWED.set(allowLightningCharge(attacker));
        }
        catch (RuntimeException | LinkageError ignored) {
            CHARGE_ALLOWED.set(Boolean.TRUE);
        }
    }

    /** putInt 注入调用:本次命中是否允许写电荷。 */
    public static boolean chargeAllowed() {
        try {
            return CHARGE_ALLOWED.get();
        }
        catch (RuntimeException | LinkageError e) {
            return true;
        }
    }

    // ------------------------------------------------------------------ 攻击伤害加成

    /** 攻击伤害修饰符:generic.attack_damage + MULTIPLY_TOTAL,数值 = 配置百分比 / 100。 */
    public static AttributeModifier attackDamageModifier() {
        return new AttributeModifier(ATTACK_DAMAGE_UUID, MODIFIER_NAME,
            attackDamagePercent() / 100.0D, AttributeModifier.Operation.MULTIPLY_TOTAL);
    }

    /** 攻击伤害加成是否生效(总开关 + 数值不为 0)。 */
    public static boolean attackDamageActive() {
        return enabled() && !restoreSweep() && attackDamagePercent() != 0.0D;
    }

    /** 这叠东西是否应该挂攻击伤害修饰符(Curios 属性事件用)。 */
    public static boolean attackDamageActiveFor(ItemStack stack) {
        return attackDamageActive() && isScroll(stack);
    }
}
