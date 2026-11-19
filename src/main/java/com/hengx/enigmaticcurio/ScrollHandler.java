package com.hengx.enigmaticcurio;

import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

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
 * <h2>1.1.9:开关为什么以前"关了还能打出来",以及现在的三道闸</h2>
 * <p>扩展的雷击其实有<b>两段</b>,不是一段:</p>
 * <ol>
 *   <li><b>累积</b>:{@code AddonEventHandler#onEntityHurt} 给目标的持久数据 {@code Electric}
 *       加 {@code 60 + rand(80) + 伤害*10};</li>
 *   <li><b>落雷</b>:{@code AddonEventHandler#onTick} 每 tick 读 {@code Electric},<b>超过 1200 就
 *       当场劈一道带 {@code HarmlessThunder} 标签的雷</b>。</li>
 * </ol>
 * <p>1.1.8 及以前<b>只拦了第 ① 段</b>,第 ② 段没有任何开关判定。于是只要目标身上还留着电荷
 * (关掉开关之前打出来的、别的玩家/别的路径写进去的、旧存档带过来的),开关关掉之后
 * {@code onTick} 照样落雷 —— 表现就是"关了雷击还是能打出来"。</p>
 * <p>现在三道闸:</p>
 * <ol>
 *   <li><b>累积闸</b>({@link #allowLightningCharge} + mixin 的 {@code putInt} 重定向):
 *       不允许时既不加电荷,还把目标身上已有的 {@code Electric} 清掉(1.1.9 起,以前只是"不加");</li>
 *   <li><b>落雷闸</b>({@link #gateStrike},由 mixin 在 {@code onTick} 的 HEAD 调用):
 *       电荷过阈值时再核一次开关,关了就直接清零并放弃这次落雷 —— 这是真正把"关了就不落雷"
 *       钉死的地方,不依赖第 ① 段有没有被拦住;</li>
 *   <li><b>兜底闸</b>({@link #applyHurtFallback},普通 Forge 监听器,不是 mixin):
 *       即使将来扩展改版把 {@code onEntityHurt} 的注入点挪走,只要本模组的开关是关的,
 *       这里仍会在 {@code LivingHurtEvent} 的 LOWEST 把目标的 {@code Electric} 清掉。</li>
 * </ol>
 * <p>三处读的都是同一份实时配置({@link #lightningAllowed()}),配置改动<b>热生效</b>,
 * 不需要重启游戏;启动/重载时由 {@link ConfigLogHandler} 打一条当前值(受 {@code debugLog} 门控)。</p>
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
     * 扩展 {@code onTick} 里的落雷阈值:电荷 {@code > 1200} 就劈一道 {@code HarmlessThunder} 雷,
     * 并把剩余电荷改成 {@code (electric - 1200) / 2 + 100}。反编译 1.2.5 / 1.2.6 / 1.2.7 完全一致。
     */
    public static final int STRIKE_THRESHOLD = 1200;

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

    /**
     * 雷击是否放行:<b>唯一判定</b>,三处闸口(累积 / 落雷 / 兜底)都读它,
     * 所以改配置立即生效(不需要重启,也不需要刷缓存)。
     */
    public static boolean lightningAllowed() {
        return enabled() && lightningEnabled();
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
            if (!lightningAllowed()) {
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

    // ------------------------------------------------------------------ 落雷闸(第二段:真正劈雷的地方)

    /**
     * 扩展 {@code onTick} 的 HEAD 注入调用:电荷过阈值、这一 tick 就要落雷之前,再核一次开关。
     *
     * <p><b>这是 1.1.9 的关键修复</b>:1.1.8 只拦"累积",没拦"落雷",所以任何留在目标身上的
     * 电荷(关开关之前累积的、别的路径写进去的、旧存档带过来的)照样会在 {@code onTick} 里劈下来。</p>
     *
     * <p>电荷离阈值还远时直接 return,不做任何额外工作(扩展自己每 tick 会 {@code -1} 衰减);
     * 真的到阈值了才:开关关 → 清零电荷并放弃落雷;开关开 → 记一条"雷击触发"诊断日志。</p>
     */
    public static void gateStrike(LivingEntity entity) {
        try {
            if (entity == null || entity.level().isClientSide()) {
                return;
            }
            CompoundTag data = entity.getPersistentData();
            if (data == null || !data.contains(ELECTRIC, Tag.TAG_INT)) {
                return;
            }
            int electric = data.getInt(ELECTRIC);
            if (electric <= STRIKE_THRESHOLD) {
                return;   // 没到阈值:onTick 自己会衰减,这里不插手
            }
            boolean allowed = lightningAllowed();
            if (!allowed) {
                clearCharge(data);
            }
            if (CurioConfig.debugLog()) {
                // 只在 debugLog 打开时才拼名字/拼日志,正常游戏这条路径零开销
                DebugLog.info(allowed
                        ? "[万钧之护] 雷击触发: 开关=true 路径=onTick 目标=%s 电荷=%d"
                        : "[万钧之护] 雷击跳过: 开关=false 路径=onTick 目标=%s 电荷=%d(已清零)",
                    nameOf(entity), electric);
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 判定失败一律放行:保持扩展原行为,绝不能因为本模组把雷击彻底弄坏
        }
    }

    /**
     * 兜底闸:{@code LivingHurtEvent} 的 LOWEST 处再核一次(普通监听器,不依赖 mixin 注入点还在不在)。
     *
     * <p>只有在<b>开关关着</b>的时候才工作 —— 开关开着时这里第一行就返回,不给热路径加任何反射开销。
     * 作用:即使以后扩展改版把 {@code onEntityHurt} 里的 {@code putInt} 挪走/改名(注入失配),
     * 只要本模组的开关是关的,目标的 {@code Electric} 依然会被清掉,落雷仍然不会发生。</p>
     */
    public static void applyHurtFallback(LivingHurtEvent event) {
        try {
            if (lightningAllowed()) {
                return;   // 开关开着:完全不管,保持扩展原行为
            }
            if (event == null) {
                return;
            }
            LivingEntity target = event.getEntity();
            if (target == null || target.level().isClientSide()) {
                return;
            }
            if (!(event.getSource().getEntity() instanceof LivingEntity attacker)) {
                return;
            }
            if (wornScroll(attacker).isEmpty()) {
                return;   // 攻击者没戴卷轴:不是本功能管的范围
            }
            CompoundTag data = target.getPersistentData();
            if (data == null || !data.contains(ELECTRIC, Tag.TAG_INT)) {
                return;
            }
            int electric = data.getInt(ELECTRIC);
            clearCharge(data);
            DebugLog.info("[万钧之护] 雷击跳过: 开关=false 路径=onEntityHurt(兜底) 攻击者=%s 目标=%s 电荷=%d",
                nameOf(attacker), nameOf(target), electric);
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    /** 判定失败要有个能读的名字,读不到就算了。 */
    private static String nameOf(LivingEntity entity) {
        try {
            return entity.getName().getString();
        }
        catch (RuntimeException | LinkageError e) {
            return "?";
        }
    }

    /** 清掉目标身上的雷击电荷(与扩展 {@code onTick} 里 {@code data.remove("Electric")} 等价)。 */
    private static void clearCharge(CompoundTag data) {
        data.remove(ELECTRIC);
    }

    // ------------------------------------------------------------------ 启动自检

    /**
     * 启动时核对扩展的注入目标还在不在(改名/改版会直接在日志里说清楚,不用玩家自己猜)。
     * 由 {@code EnigmaticCurio} 的 commonSetup 监听器调用;不需要 debugLog 也输出,
     * 因为"注入目标缺失"是必须让用户看见的诊断。
     */
    public static void probeAddonTargets() {
        try {
            Class<?> handler = Class.forName("auviotre.enigmatic.addon.handlers.AddonEventHandler",
                false, ScrollHandler.class.getClassLoader());
            boolean hurt = hasMethod(handler, "onEntityHurt", LivingHurtEvent.class);
            boolean tick = hasMethod(handler, "onTick", net.minecraftforge.event.entity.living.LivingEvent.LivingTickEvent.class);
            if (hurt && tick) {
                DebugLog.info("[万钧之护] 注入目标就绪: onEntityHurt / onTick 都在");
            }
            else {
                DebugLog.warn("[万钧之护] 扩展注入目标缺失(onEntityHurt=%s, onTick=%s):雷击开关可能失效,请把日志发给作者",
                    hurt, tick);
            }
        }
        catch (ClassNotFoundException e) {
            DebugLog.info("[万钧之护] 未安装神秘遗物扩展:雷击开关不参与");
        }
        catch (RuntimeException | LinkageError e) {
            // 自检失败不影响游戏
        }
    }

    private static boolean hasMethod(Class<?> owner, String name, Class<?>... params) {
        try {
            owner.getDeclaredMethod(name, params);
            return true;
        }
        catch (NoSuchMethodException e) {
            return false;
        }
        catch (RuntimeException | LinkageError e) {
            return false;
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
