package com.hengx.enigmaticcurio;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 神秘遗物扩展:「失落引擎」({@code enigmaticaddons:lost_engine})的雷击惩罚拦截。
 *
 * <h2>扩展的惩罚到底是什么(反编译 {@code enigmaticaddons-1.2.5} 的 {@code AddonEventHandler#onEntityDamage})</h2>
 * <p>它是挂在 <b>{@code LivingDamageEvent}</b> 上的,判据是<b>受伤者是玩家</b> +
 * {@code hasCurio(LOST_ENGINE)} + {@code DamageSource.is(DamageTypeTags.IS_LIGHTNING)}
 * (字节码:{@code DamageTypeTags.f_268725_} = {@code IS_LIGHTNING},已用官方映射核对)。命中后它做三件事:</p>
 * <ol>
 *   <li>遍历玩家背包里每一件护甲,按 {@code maxDamage * (护甲 1.0 / 其它 1.4)} 直接扣耐久 → <b>整套装备毁掉</b>
 *       (还顺手删掉不可破坏标签);</li>
 *   <li>通过 Curios API 把佩戴的饰品也按同样比例毁掉 → <b>饰品全毁</b>;</li>
 *   <li>{@code setAmount(amount * (4 + rand(4)) + getMaxHealth())} → <b>必死</b>。</li>
 * </ol>
 *
 * <h2>我们怎么拦(与主模组 {@code EngineDamageGuard} 一致)</h2>
 * <p>在**最早**的一层 {@link LivingAttackEvent} 就把这次闪电攻击取消掉(佩戴引擎的玩家),
 * 于是 {@code LivingHurtEvent}/{@code LivingDamageEvent} 根本不会触发,扩展那段改写没有任何机会跑,
 * 护甲/饰品自然不会掉耐久;再在 {@link LivingDamageEvent} 兜一层(LOWEST),防止有别的路径绕开
 * LivingAttack 直接进 Damage。</p>
 *
 * <p><b>为什么不再只靠"雷击实体进入世界时取消"</b>:实体层只能拦住"还没落下来的雷",而扩展的惩罚
 * 是挂在**伤害事件**上的 —— 任何来源的闪电伤害(别的模组的闪电、已经存在的雷、直接
 * {@code hurt(lightning)} 的路径)都会触发它。旧版本只拦实体,所以玩家一旦被闪电打到就会
 * 掉光装备 + 必死,这是必须修掉的严重问题。实体层仍然保留(见 {@link #onJoin}),只作为附加的视觉/点燃防护。</p>
 *
 * <h2>日志</h2>
 * <p>两条关键路径都打日志且**不受 {@code debugLog} 门控**(带 3 秒节流):
 * "拦下闪电伤害" / "检测到雷击但附近没有佩戴者 → 放行"。这样用户戴引擎跑一会儿就能从日志确认到底拦没拦住。</p>
 */
public final class EngineGuardHandler {

    private static final String ENGINE_ID = "enigmaticaddons:lost_engine";

    /** 实体层拦截的判定半径(与主模组一致:以雷击为中心 16 格内的佩戴者)。 */
    private static final double BOLT_RADIUS = 16.0D;

    /** 诊断日志节流(毫秒)。 */
    private static final long WARN_INTERVAL_MS = 3000L;

    private static final Map<String, Long> LAST_WARN = new HashMap<>();

    private static Item engine;

    private EngineGuardHandler() {
    }

    private static Item engineItem() {
        try {
            if (engine == null) {
                engine = Superposition.item(ENGINE_ID);
            }
            return engine;
        }
        catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static boolean enabled() {
        try {
            return CurioConfig.ENGINE_GUARD.get();
        }
        catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 真正的防护:闪电伤害事件

    /**
     * 佩戴失落引擎的玩家受到闪电伤害时,在**最早**的一层取消。
     * <p>这同时挡住了扩展 {@code AddonEventHandler#onEntityDamage} 里的"必死 + 摧毁全部护甲/饰品"。</p>
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingAttack(LivingAttackEvent event) {
        try {
            if (!isEngineLightning(event.getSource(), event.getEntity())) {
                return;
            }
            event.setCanceled(true);
            warnThrottled("attack",
                "[失落引擎] 已取消佩戴者 {} 受到的闪电攻击(伤害={},来源={}) —— 惩罚不生效",
                event.getEntity().getName().getString(), event.getAmount(), event.getSource().getMsgId());
        }
        catch (RuntimeException | LinkageError ignored) {
            // 失败放行:按原版结算(绝不能因为守卫自身出错而误伤别的伤害)
        }
    }

    /** 兜底:万一有路径绕开 {@link LivingAttackEvent},在 Damage 层把闪电伤害清零。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLivingDamage(LivingDamageEvent event) {
        try {
            if (!isEngineLightning(event.getSource(), event.getEntity())) {
                return;
            }
            event.setCanceled(true);
            event.setAmount(0.0F);
            warnThrottled("damage", "[失落引擎] 已把佩戴者 {} 的闪电伤害清零(来源={}) —— 惩罚不生效",
                event.getEntity().getName().getString(), event.getSource().getMsgId());
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    /** 这次伤害是不是"佩戴失落引擎的玩家被闪电打"。 */
    private static boolean isEngineLightning(DamageSource source, Entity entity) {
        if (!enabled() || source == null || !(entity instanceof Player player)) {
            return false;
        }
        if (!source.is(DamageTypeTags.IS_LIGHTNING)) {
            return false;
        }
        Item item = engineItem();
        return item != null && carries(player, item);
    }

    // ------------------------------------------------------------------ 附加防护:雷击实体本身

    /**
     * 由玩家引起的雷击(例如三叉戟引雷、别的模组"玩家召唤的雷")落在佩戴者附近时,直接不让它进世界。
     * <p>只做**附加**防护:自然雷({@code cause == null})不碰(不然会把雷暴天的世界行为也改了),
     * 扩展自己的 {@code HarmlessThunder} 标记雷(万钧之护那种"无害雷")也不碰。</p>
     */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        try {
            if (event.getLevel().isClientSide() || !enabled()) {
                return;
            }
            if (event.getEntity().getType() != EntityType.LIGHTNING_BOLT) {
                return;
            }
            LightningBolt bolt = (LightningBolt) event.getEntity();
            // 扩展自己标记的无害雷(万钧之护的落雷):不碰
            if (bolt.getTags().contains("HarmlessThunder")) {
                return;
            }
            // 自然雷(cause == null)与生物引起的雷:不碰,交给上面的伤害层处理
            if (!(bolt.getCause() instanceof Player)) {
                return;
            }
            Item item = engineItem();
            if (item == null) {
                return;
            }
            for (Player player : event.getLevel().getEntitiesOfClass(Player.class, bolt.getBoundingBox().inflate(BOLT_RADIUS))) {
                if (carries(player, item)) {
                    event.setCanceled(true);
                    warnThrottled("bolt", "[失落引擎] 拦下玩家引起的雷击 at {}:{} 佩戴引擎(距 {} 格)",
                        (int) bolt.getX(), (int) bolt.getZ(), player.getName().getString(),
                        String.format(java.util.Locale.ROOT, "%.1f", player.distanceTo(bolt)));
                    return;
                }
            }
            warnThrottled("bolt-pass", "[失落引擎] 检测到玩家引起的雷击 at {}:{},但 {} 格内没有佩戴者 → 放行",
                (int) bolt.getX(), (int) bolt.getZ(), String.format(java.util.Locale.ROOT, "%.0f", BOLT_RADIUS));
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 饰品栏 / 双手 / 背包 任一位置带着该物品(与主模组 {@code SuperpositionHandler#hasItem} 同口径)。 */
    private static boolean carries(Player player, Item item) {
        try {
            if (Superposition.hasCurio(player, item)) {
                return true;
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 落到手上/背包检查
        }
        if (player.getMainHandItem().is(item) || player.getOffhandItem().is(item)) {
            return true;
        }
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(item)) {
                return true;
            }
        }
        return false;
    }

    /** 不受 {@code debugLog} 门控的诊断日志,同一个 key 3 秒内只打一次。 */
    private static void warnThrottled(String key, String format, Object... args) {
        try {
            long now = System.currentTimeMillis();
            Long last = LAST_WARN.get(key);
            if (last != null && now - last < WARN_INTERVAL_MS) {
                return;
            }
            LAST_WARN.put(key, now);
            DebugLog.warn(format, args);
        }
        catch (RuntimeException | LinkageError ignored) {
            // 日志本身绝不能出错
        }
    }
}
