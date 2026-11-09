package com.hengx.enigmaticcurio;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 「万钧之护」({@code enigmaticaddons:thunder_scroll},
 * 类 {@code auviotre.enigmatic.addon.contents.items.ThunderScroll})的两项开关。
 *
 * <h2>① 雷击效果:**逐件**、游戏内可开关(Ctrl + 左键)</h2>
 * 扩展原本的做法(反编译 {@code AddonEventHandler#onEntityHurt}):攻击者戴着卷轴、且主手物品
 * {@code canPerformAction(ToolActions.SWORD_SWEEP)}(剑)时,给**目标**的持久数据 {@code Electric}
 * 累加 {@code 60 + rand(80) + (int)(伤害*10)};{@code AddonEventHandler#onTick} 里 {@code Electric}
 * 超过 1200 就给目标劈一道雷。
 *
 * <p>开关状态写在**卷轴自己的 NBT** {@value #NBT_THUNDER_OFF} 上(键不存在 = 开),所以是"逐件"的:
 * 换一把卷轴就是另一个开关,跟着物品走(存档/交易/掉落都保留)。切换方式与主模组一致:
 * <b>客户端识别 Ctrl + 左键 → {@link CurioNetwork} 发包 → 服务端校验并写 NBT</b>
 * (由 {@link ItemToggleHandler} 统一处理)。</p>
 *
 * <p>旧的「潜行 + 右键」已作废 —— 那个手势会被 Curios 抢去佩戴饰品,开关点不到。</p>
 *
 * <p>关掉后 {@link #allowLightningCharge} 返回 false,{@code AddonThunderLightningMixin} 便不再写
 * {@code Electric};目标永远累积不到阈值,自然就不会落雷,护甲穿透等其它效果不受影响。</p>
 *
 * <h2>② 加成效果可自由配置</h2>
 * 扩展原本用 {@code MixinEnchantmentHelper.getSweeping} 把
 * {@code EnchantmentHelper#getSweepingDamageRatio}(SRG {@code m_44821_})抬到 {@code max(1.0, 原值)},
 * 于是横扫攻击按 100% 伤害结算。本模组把它还原成原版公式
 * ({@code mixin.EnchantmentHelperSweepMixin},priority 500 抢在扩展之前定值),
 * 作为交换,佩戴者造成的一切伤害按 {@link CurioConfig#SCALE_ATTACK_DAMAGE_PERCENT}
 * (默认 100%)乘法提升,口径与扩展「地狱刃片」一致 —— 见
 * {@link ScrollDamageHandler}({@code LivingHurtEvent},直接攻击类型 ×2.0、其余 ×1.5)。
 * 想要扩展原来的"只加横扫"手感,把 {@code scaleRestoreSweep} 设成 true 即可。
 *
 * <p><b>注意</b>:这里以前是给 {@code generic.attack_damage} 挂 {@code MULTIPLY_TOTAL}
 * 属性修饰器(只加近战攻击力)。现已整体移除 —— 属性修饰器只作用于"攻击力"这一个数值,
 * 箭矢/法术/爆炸等根本读不到它,达不到"全伤"的目的。</p>
 */
public final class ScrollHandler {

    /** 万钧之护的注册 id。 */
    public static final String SCROLL_ID = "enigmaticaddons:thunder_scroll";

    /** 目标持久数据里的雷击电荷键(与扩展一致)。 */
    public static final String ELECTRIC = "Electric";

    /**
     * 卷轴物品自身的 NBT 键:为 true 表示"这一件卷轴的雷击已被玩家关掉"。
     * <b>键不存在 = 开</b>(沿用扩展原行为)。
     */
    public static final String NBT_THUNDER_OFF = "enigmaticcurio:thunderOff";

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

    /**
     * 全伤加成百分比(0 = 不加成)。直接攻击类型按这个数值全额、其余类型半额,
     * 与扩展「地狱刃片」的 {@code damageMultiplier} 口径相同。
     */
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

    // ------------------------------------------------------------------ 逐件开关(Ctrl + 左键)

    /** 这一件卷轴的雷击是否已被关掉(键不存在 = 开)。 */
    public static boolean isThunderOff(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty() || !stack.hasTag()) {
                return false;
            }
            CompoundTag tag = stack.getTag();
            return tag != null && tag.getBoolean(NBT_THUNDER_OFF);
        }
        catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 写入/清除"雷击已关闭"标记(清除时若 NBT 空了就一并清掉,不留空 CompoundTag)。 */
    public static void setThunderOff(ItemStack stack, boolean off) {
        try {
            if (stack == null || stack.isEmpty()) {
                return;
            }
            if (off) {
                stack.getOrCreateTag().putBoolean(NBT_THUNDER_OFF, true);
                return;
            }
            CompoundTag tag = stack.getTag();
            if (tag == null) {
                return;
            }
            tag.remove(NBT_THUNDER_OFF);
            if (tag.isEmpty()) {
                stack.setTag(null);
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
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
            ItemStack worn = wornScroll(attacker);
            if (worn.isEmpty()) {
                return true;   // 攻击者没戴卷轴
            }
            // 逐件开关:这一件卷轴被玩家关掉了就不累积电荷
            if (isThunderOff(worn)) {
                DebugLog.info("[万钧之护] {} 这一件卷轴的雷击已关闭,本次不累积电荷",
                    attacker.getName().getString());
                return false;
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

    // ------------------------------------------------------------------ 全伤害加成(见 ScrollDamageHandler)

    /**
     * 全伤加成是否生效:总开关 + 没选"恢复扩展横扫" + 数值不为 0。
     *
     * <p>判定与旧版属性修饰器完全一致,只是落点从 {@code generic.attack_damage} 换成了
     * {@link ScrollDamageHandler} 的 {@code LivingHurtEvent} 乘法。</p>
     */
    public static boolean damageBoostActive() {
        return enabled() && !restoreSweep() && attackDamagePercent() != 0.0D;
    }
}
