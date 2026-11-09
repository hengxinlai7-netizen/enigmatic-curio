package com.hengx.enigmaticcurio;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 「万钧之护」的<b>全伤害加成</b> —— 口径与扩展「地狱刃片」
 * ({@code enigmaticaddons:hell_blade_charm})完全一致。
 *
 * <h2>反编译依据:地狱刃片到底怎么加的</h2>
 * <p>{@code auviotre.enigmatic.addon.handlers.AddonEventHandler#onEntityHurt(LivingHurtEvent)}
 * (标注 <b>{@code @SubscribeEvent}</b>,即 {@code EventPriority.NORMAL})里:</p>
 * <pre>
 *   // 1084: if (player.hasCurio(HELL_BLADE_CHARM))
 *   float mult = HellBladeCharm.damageMultiplier.getValue().asModifier();   // 百分比/100,默认 100 → 1.0
 *   ...
 *   ModifyDamageBaseOne(event, (NEMESIS_LIST 里 ? 1.0f : 0.5f) * mult);
 *
 *   private void ModifyDamageBaseOne(LivingHurtEvent event, double d) {
 *       if (d == 0) return;
 *       event.setAmount(event.getAmount() * (float) (1.0 + d));   // 100: fmul 后 setAmount
 *   }
 * </pre>
 * <ul>
 *   <li><b>事件</b>:{@link LivingHurtEvent},<b>优先级 NORMAL</b>(默认值)。它发生在
 *       {@code LivingEntity#hurt} 的护甲({@code getDamageAfterArmorAbsorb})与保护附魔
 *       ({@code getDamageAfterMagicAbsorb})减免<b>之前</b>,所以加成先乘、护甲抗性后算 —— 与
 *       地狱刃片同一个时机,不与护甲/抗性顺序冲突;</li>
 *   <li><b>乘法</b>:{@code amount *= (1 + d)},是<b>乘法</b>而非平加;</li>
 *   <li><b>范围</b>:只看 {@code source.getEntity()},要求是 {@link Player} 且戴着饰品 ——
 *       <b>不看主手、不看直接来源</b>。所以弓、弩、三叉戟、法术弹这类
 *       {@code getEntity() == 玩家} 的投掷物伤害<b>照样算</b>(但吃的是下面 0.5 那一档);</li>
 *   <li><b>排除项</b>:没有"真伤/反伤/环境伤害"黑名单。它只要求 {@code getEntity()} 是玩家:
 *       反伤(荆棘)的 {@code getEntity()} 是攻击者、环境伤害是 null,天然不算;
 *       玩家自己造成的伤害(含 {@code generic_kill} 这种真伤)都会经过这里。
 *       唯一的硬跳过是 {@code event.getAmount() >= Float.MAX_VALUE}(1088 之前的判断);</li>
 *   <li><b>两档倍率</b>:{@code NEMESIS_LIST} =
 *       {@code List.of(DamageTypes.MOB_ATTACK, DamageTypes.GENERIC, DamageTypes.PLAYER_ATTACK)}
 *       (静态块 10: getstatic f_268566_ / f_268433_ / f_268464_,
 *       {@code DamageSource#is(ResourceKey)} 由 invokedynamic #5 =
 *       {@code DamageSource.m_276093_} 完成)。命中这三个类型 → 全额 {@code 1+mult};
 *       其它类型 → 半额 {@code 1+0.5*mult}。默认 {@code damageMultiplier=100} 时分别是
 *       <b>×2.0</b> 与 <b>×1.5</b>。</li>
 * </ul>
 *
 * <h2>本模组的实现</h2>
 * <p>原先挂 {@code generic.attack_damage} 的 {@code MULTIPLY_TOTAL} 属性修饰器
 * (只会加近战攻击力)已经整体移除,改成这里同事件、同时机、同口径的乘法加成:</p>
 * <pre>
 *   amount *= 1 + (NEMESIS ? 1.0 : 0.5) * scaleAttackDamagePercent / 100
 * </pre>
 * <p>所以默认 100% 时:纯近战({@code player_attack})×2.0(与旧版近战 +100% 数值一致),
 * 箭矢/法术等 ×1.5(旧版完全吃不到)。</p>
 *
 * <h2>开销</h2>
 * <p>顺序是<b>配置 → 攻击者是不是玩家 → 有没有戴卷轴 → 才算数</b>:没戴卷轴时在第三道就返回
 * ({@link ScrollHandler#wornScroll}),不做任何算术、不拼日志。</p>
 *
 * <h2>日志</h2>
 * <p>不受 {@code debugLog} 门控({@link DebugLog#infoAlways}),按"伤害类型"5 秒节流:</p>
 * <pre>[万钧之护] 全伤加成 x1.5 原始=10.0 最终=15.0 类型=minecraft:arrow</pre>
 */
public final class ScrollDamageHandler {

    /**
     * 地狱刃片 {@code AddonEventHandler.NEMESIS_LIST} 的三个伤害类型:命中它们是全额,
     * 其它是半额。这里<b>照抄</b>,不自己加也不自己减。
     */
    private static final List<ResourceKey<DamageType>> NEMESIS_LIST = List.of(
        DamageTypes.MOB_ATTACK, DamageTypes.GENERIC, DamageTypes.PLAYER_ATTACK);

    /** 非 NEMESIS_LIST 类型的折扣系数(地狱刃片里那个 {@code ldc 0.5f})。 */
    private static final double NON_NEMESIS_FACTOR = 0.5D;

    /** 日志节流:同一伤害类型 5 秒最多一条。 */
    private static final long LOG_THROTTLE_MILLIS = 5000L;

    /** 上次打日志的时间,键 = 伤害类型 id(类型种类有限,不会无限增长)。 */
    private static final Map<String, Long> LAST_LOG = new ConcurrentHashMap<>();

    private ScrollDamageHandler() {
    }

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onLivingHurt(LivingHurtEvent event) {
        try {
            // ① 配置:总开关 / 用户是否选择"恢复扩展原本的横扫加成" / 加成数值是否为 0
            if (!ScrollHandler.damageBoostActive()) {
                return;
            }
            float amount = event.getAmount();
            // 与地狱刃片一致的硬跳过(它遇到 Float.MAX_VALUE 直接 return):
            // 0/负数/NaN 也跳过,免得把脏数据乘成更大的负数。
            if (!(amount > 0.0F) || amount >= Float.MAX_VALUE) {
                return;
            }
            DamageSource source = event.getSource();
            Entity attacker = source.getEntity();
            if (!(attacker instanceof Player player)) {
                return;   // 地狱刃片同样只认"玩家是来源实体",反伤/环境伤害天然不算
            }
            // ② 先判断 curio,没戴就到此为止(不做算术、不拼日志)
            ItemStack worn = ScrollHandler.wornScroll(player);
            if (worn.isEmpty()) {
                return;
            }
            double percent = ScrollHandler.attackDamagePercent();
            if (percent == 0.0D) {
                return;
            }
            // ③ 与地狱刃片同口径:直接攻击类型全额,其余半额;都是乘法
            double factor = (isNemesis(source) ? 1.0D : NON_NEMESIS_FACTOR) * percent / 100.0D;
            double multiplier = 1.0D + factor;
            if (!(multiplier > 0.0D)) {
                return;   // 负到把伤害抹平(percent 下限 -100)就按 0 处理,不做反向加血
            }
            float result = (float) (amount * multiplier);
            if (!Float.isFinite(result)) {
                return;   // 溢出:放行原值,绝不写 Inf/NaN
            }
            event.setAmount(result);
            logThrottled(multiplier, amount, result, source);
        }
        catch (RuntimeException | LinkageError ignored) {
            // 失败放行:不改伤害 = 旧行为之外最安全的结果
        }
    }

    /** 这次伤害的类型是否在"直接攻击"组(照抄地狱刃片的判定)。 */
    public static boolean isNemesis(DamageSource source) {
        try {
            if (source == null) {
                return false;
            }
            for (ResourceKey<DamageType> key : NEMESIS_LIST) {
                if (source.is(key)) {
                    return true;
                }
            }
            return false;
        }
        catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 伤害类型的注册 id(日志用;取不到时给 {@code ?})。 */
    private static String typeName(DamageSource source) {
        try {
            return source.typeHolder().unwrapKey()
                .map(key -> key.location().toString())
                .orElse("?");
        }
        catch (RuntimeException | LinkageError e) {
            return "?";
        }
    }

    /** 5 秒节流 + <b>不受 debugLog 门控</b> 的日志。 */
    private static void logThrottled(double multiplier, float before, float after, DamageSource source) {
        try {
            String type = typeName(source);
            long now = System.currentTimeMillis();
            Long last = LAST_LOG.get(type);
            if (last != null && now - last < LOG_THROTTLE_MILLIS) {
                return;
            }
            LAST_LOG.put(type, now);
            DebugLog.infoAlways("[万钧之护] 全伤加成 x%s 原始=%s 最终=%s 类型=%s",
                multiplierText(multiplier), amountText(before), amountText(after), type);
        }
        catch (RuntimeException | LinkageError ignored) {
            // 记日志失败绝不能影响伤害
        }
    }

    /** {@code 1.5 / 2.0 / 1.25} 这种紧凑写法(最多两位小数,去掉多余的 0)。 */
    private static String multiplierText(double multiplier) {
        String text = String.format(Locale.ROOT, "%.2f", multiplier);
        if (text.endsWith("0")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /** 伤害数值按一位小数输出。 */
    private static String amountText(float amount) {
        return String.format(Locale.ROOT, "%.1f", amount);
    }
}
