package com.hengx.enigmaticcurio;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 神秘遗物:「血战沙场之证」({@code enigmaticlegacy:berserk_charm},
 * 类 {@code com.aizistral.enigmaticlegacy.items.BerserkEmblem})的**假血条锁血**。
 *
 * <h2>设计</h2>
 * <ul>
 *   <li><b>假血条</b>(NBT 键 {@value #TAG_FAKE}):真正参与"死亡判定 / 饰品加成 / 扣血"的血量,
 *       默认 = 锁值;只受**外部扣血**下降,<b>回血一律无效</b>;</li>
 *   <li><b>真血条</b>:只负责"生命上限(属性)与生命值加成",当前血量每 tick 被同步成假血条的值,
 *       所以界面上看到的就是假血条;</li>
 *   <li>受伤走假血条:不扣真血,只扣假血;假血 ≤ 0 时才真正死亡;</li>
 *   <li>假血条上限 = 当前最大生命({@code setHealth} 自己会夹),生命上限变动自动反映。</li>
 * </ul>
 *
 * <h2>必须同时做到的五个坑(踩过的)</h2>
 * <ol>
 *   <li><b>回血穿透</b>:只拦受伤事件的话,再生/吸血/瞬间治疗会把血拉起来 ⇒ 必须同时取消
 *       {@link LivingHealEvent},并且每 tick 把真血强制同步成假血;</li>
 *   <li><b>写 0 血自杀</b>:锁值必须 ≥ {@link #MIN_LOCK_VALUE}(0.5),绝不能写 0 或负数,
 *       否则会触发死亡/除零;</li>
 *   <li><b>真血与假血不同步</b>:只改假血不每 tick 同步真血,界面与其它模组读到的血量都是错的;</li>
 *   <li><b>死亡后无限死</b>:假血 ≤ 0 真正死一次之后必须立刻把假血重置回锁值,否则复活后被 tick
 *       再次判定就无限死亡;</li>
 *   <li><b>复活后死循环(最难的一个)</b>:取消治疗是无差别的,复活瞬间血量很低,之后所有治疗
 *       (原版复活恢复、金苹果、再生)统统被吞掉 ⇒ 下一次伤害立刻致死 ⇒ 再复活 ⇒ 无限死亡。
 *       本类用三道闸解决:
 *       <ul>
 *         <li>取消治疗只在本类认定的"假血条激活"状态下做(存活 + 非濒死 + 数据存在 + 开关没关 +
 *             不在复活豁免窗口);</li>
 *         <li>复活窗口:{@link #GRACE_TICKS} tick 内**整套锁血暂时不生效**(不拦伤害、不拦治疗、
 *             不同步真血),让玩家先把血回起来;</li>
 *         <li>克隆 / 复活 / 登录三个时机都把假血条重置成锁值(不小于
 *             {@link #MIN_LOCK_VALUE}),并主动把真血抬到不低于 {@link #MIN_LOCK_VALUE}。</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <h2>假血条存在哪(重要)</h2>
 * 存在 {@code player.getPersistentData()}(Forge 的 {@code ForgeData})。这块数据在死亡复活时
 * 会被 Forge 通过 {@code PlayerPersisted} capability **复制到新实体**上 —— 也就是说假血条
 * <b>跨死亡保留</b>。所以 {@link PlayerEvent.Clone} 里必须显式重置:否则新身体会带着上一世的
 * 假血(可能已经被扣成 0.5 甚至 0),一受伤就死,这是"一直死亡"的另一半原因。
 *
 * <h2>开关(逐件 NBT)</h2>
 * 状态写在勋章自己的 NBT {@value #NBT_LOCK_DISABLED} 上(<b>键不存在 = 开 = 默认行为</b>)。
 * 主模组用 Ctrl+左键 + 自定义网络包切换饰品槽里的那一件;本模组没有网络通道,改成
 * <b>潜行 + 右键</b>:手上拿着这枚勋章时切换手上那件,空手时切换**饰品槽里戴着的那件**。
 * 由服务端写 NBT(客户端随后收到同步),所以两端状态一致。
 */
public final class BerserkCharmHandler {

    /** 物品 NBT 键:true = 玩家关掉了这个锁血;键不存在 = 开。 */
    public static final String NBT_LOCK_DISABLED = "enigmaticcurio:BloodbathLockDisabled";

    /** 假血条 NBT 键(存在 ForgeData 里,跨死亡保留 ⇒ Clone 时必须重置)。 */
    private static final String TAG_FAKE = "bloodbath_fake";

    /** 锁血允许的最小值(绝不写 0/负数)。 */
    public static final float MIN_LOCK_VALUE = 0.5F;

    /** 复活/登录后的免疫窗口:这段时间内整套锁血不生效,让玩家能把血回起来。 */
    public static final int GRACE_TICKS = 100;

    /** 血战沙场之证的注册 id。 */
    private static final String CHARM_ID = "enigmaticlegacy:berserk_charm";

    /**
     * 同一次右键会按手分别触发事件,这里做去抖:同一 tick 内只处理一次切换,
     * 避免"空手 + 双手都空"时被翻两次。
     */
    private static final Map<UUID, Integer> LAST_TOGGLE_TICK = new ConcurrentHashMap<>();

    private static Item charm;

    private BerserkCharmHandler() {
    }

    // ------------------------------------------------------------------ 判定 / 配置

    /** 血战沙场之证物品(取不到时为 null)。 */
    public static Item charmItem() {
        try {
            if (charm == null) {
                charm = Superposition.item(CHARM_ID);
            }
            return charm;
        }
        catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static boolean isCharm(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) {
                return false;
            }
            Item item = charmItem();
            return item != null && stack.is(item);
        }
        catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 锁到多少点生命(配置下限 {@value #MIN_LOCK_VALUE},这里再兜一次)。 */
    public static float lockValue() {
        try {
            return Math.max(MIN_LOCK_VALUE, CurioConfig.BLOODBATH_LOCK_VALUE.get().floatValue());
        }
        catch (RuntimeException | LinkageError e) {
            return Math.max(MIN_LOCK_VALUE, 1.0F);
        }
    }

    /** 该勋章是否被玩家关掉了锁血(键不存在 = 开)。 */
    public static boolean isLockOff(ItemStack stack) {
        try {
            CompoundTag tag = stack.getTag();
            return tag != null && tag.getBoolean(NBT_LOCK_DISABLED);
        }
        catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 写入/清除"锁血已关闭"标记。 */
    public static void setLockOff(ItemStack stack, boolean off) {
        try {
            CompoundTag tag = stack.getOrCreateTag();
            if (off) {
                tag.putBoolean(NBT_LOCK_DISABLED, true);
            }
            else {
                tag.remove(NBT_LOCK_DISABLED);
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    /** 基础条件:总开关 + 佩戴该勋章 + 该件的锁血没被关掉。 */
    public static boolean lockActive(Player player) {
        try {
            if (player == null || !CurioConfig.BLOODBATH_ENABLED.get()) {
                return false;
            }
            Item item = charmItem();
            if (item == null || !Superposition.hasCurio(player, item)) {
                return false;
            }
            ItemStack worn = Superposition.getCurioStack(player, item);
            return worn.isEmpty() || !isLockOff(worn);
        }
        catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 假血条数据是否已经存在(没写过 = 还没进入过锁血状态)。 */
    public static boolean hasFakeHealth(ServerPlayer player) {
        try {
            return player.getPersistentData().contains(TAG_FAKE);
        }
        catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 是否处于复活/登录后的免疫窗口(整套锁血暂停)。 */
    public static boolean inGrace(ServerPlayer player) {
        try {
            return player.tickCount < GRACE_TICKS;
        }
        catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * 假血条此刻是否真正"接管"血量:存活 + 非濒死 + 已初始化 + 开关没关 + 不在免疫窗口。
     * 受伤、回血、每 tick 同步三处都用这一个判定(第 5 个坑的三道闸之二)。
     */
    public static boolean barActive(ServerPlayer player) {
        return player != null
            && player.isAlive()
            && !player.isDeadOrDying()
            && lockActive(player)
            && hasFakeHealth(player)
            && !inGrace(player);
    }

    // ------------------------------------------------------------------ 假血条读写

    /** 假血条当前血量(第一次读时初始化为锁值)。 */
    public static float fakeHealth(ServerPlayer player) {
        CompoundTag tag = player.getPersistentData();
        if (!tag.contains(TAG_FAKE)) {
            tag.putFloat(TAG_FAKE, lockValue());
        }
        return tag.getFloat(TAG_FAKE);
    }

    public static void setFakeHealth(ServerPlayer player, float value) {
        player.getPersistentData().putFloat(TAG_FAKE, Math.max(0.0F, value));
    }

    /** 把假血条重置为锁值(重新开启锁血 / 复活 / 登录时用)。 */
    public static void resetFakeHealth(ServerPlayer player) {
        resetFakeHealth(player, lockValue());
    }

    /**
     * 把假血条重置为指定值(不小于 {@link #MIN_LOCK_VALUE},避免写 0 直接死),
     * 并顺手把真血抬到不低于这个值 —— 保证"复活后不是 0 血"。
     */
    public static void resetFakeHealth(ServerPlayer player, float value) {
        try {
            float safe = Math.max(MIN_LOCK_VALUE, value);
            player.getPersistentData().putFloat(TAG_FAKE, safe);
            if (player.isAlive() && player.getHealth() < safe) {
                player.setHealth(safe);
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------ 每 tick 同步真血

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        try {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            if (!(event.player instanceof ServerPlayer player)) {
                return;
            }
            if (!barActive(player)) {
                return;
            }
            float fake = fakeHealth(player);
            if (fake <= 0.0F) {
                killLocked(player);
                return;
            }
            // 真血条只负责显示假血条的值(生命上限仍由属性决定)
            if (Math.abs(player.getHealth() - fake) > 1.0E-3F) {
                player.setHealth(fake);
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------ 受伤 / 回血

    /** 外部扣血 → 扣假血条(真血不动)。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingHurt(LivingHurtEvent event) {
        try {
            if (!(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            if (!barActive(player)) {
                return;
            }
            float fake = fakeHealth(player);
            float amount = event.getAmount();
            // 真血一点不动
            event.setAmount(0.0F);
            event.setCanceled(true);
            setFakeHealth(player, fake - amount);
            DebugLog.info("[血战沙场] {} 假血条 {} -> {}", player.getName().getString(), fake, fake - amount);
            if (fake - amount <= 0.0F) {
                killLocked(player);
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 失败放行:按原版受伤结算
        }
    }

    /**
     * 回血一律无效(假血条不接受治疗)。
     *
     * <p><b>只在这个状态下拦</b>({@link #barActive}):存活、非濒死、假血数据存在、开关没关、
     * 不在复活免疫窗口。这就是"复活后被无限吞治疗 → 反复死亡"那个死循环的闸门。</p>
     */
    @SubscribeEvent
    public static void onLivingHeal(LivingHealEvent event) {
        try {
            if (!(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            if (!barActive(player)) {
                return;
            }
            event.setAmount(0.0F);
            event.setCanceled(true);
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------ 死亡 / 复活 / 登录

    private static void killLocked(ServerPlayer player) {
        try {
            player.setHealth(0.0F);
            player.die(player.damageSources().genericKill());
            // 死完立刻把假血重置,否则复活后 tick 判定 fake<=0 会再死一次 —— 无限死亡。
            resetFakeHealth(player);
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    /**
     * 死亡复活(克隆新实体)。这里必须重置假血条:它存在 ForgeData 里、会被复制到新身体,
     * 不重置就会带着上一世的低血量继续,一碰就死。
     */
    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        try {
            if (event.getEntity() instanceof ServerPlayer player) {
                resetFakeHealth(player);
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    /** 复活时再兜一道(克隆事件之后血条会被原版重置,这里保证假血与真血都健康)。 */
    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        try {
            if (event.getEntity() instanceof ServerPlayer player) {
                resetFakeHealth(player);
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    /** 登录时也重置一次(跨存档/重进游戏后不会带着旧的假血)。 */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        try {
            if (event.getEntity() instanceof ServerPlayer player) {
                resetFakeHealth(player);
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    /** 维度切换时同样兜一道(维度切换会重建一部分状态,防出现"复活般的低血")。 */
    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        try {
            if (event.getEntity() instanceof ServerPlayer player && !hasFakeHealth(player)) {
                resetFakeHealth(player);
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------ 潜行 + 右键 切换

    /**
     * 潜行+右键 切换锁血:手上拿着这枚勋章就切换手上那件;空手则切换**饰品槽里戴着的那件**。
     * 只在服务端写 NBT(客户端随后收到同步),同一 tick 内只处理一次。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClick(PlayerInteractEvent.RightClickItem event) {
        try {
            if (!CurioConfig.BLOODBATH_ENABLED.get()) {
                return;
            }
            Player player = event.getEntity();
            if (!player.isShiftKeyDown()) {
                return;
            }
            if (player.level().isClientSide) {
                return;   // 服务端权威
            }
            Integer last = LAST_TOGGLE_TICK.get(player.getUUID());
            if (last != null && last == player.tickCount) {
                return;   // 同一次右键的另一只手,忽略
            }
            ItemStack held = event.getItemStack();
            ItemStack target = ItemStack.EMPTY;
            if (isCharm(held)) {
                target = held;
            }
            else if (held.isEmpty()) {
                Item item = charmItem();
                if (item != null) {
                    ItemStack worn = Superposition.getCurioStack(player, item);
                    if (!worn.isEmpty()) {
                        target = worn;
                    }
                }
            }
            if (target.isEmpty()) {
                return;
            }
            LAST_TOGGLE_TICK.put(player.getUUID(), player.tickCount);
            boolean next = !isLockOff(target);
            setLockOff(target, next);
            if (!next && player instanceof ServerPlayer serverPlayer) {
                // 重新开启:假血拉回锁值,真血不低于锁值
                resetFakeHealth(serverPlayer);
            }
            player.sendSystemMessage(Component
                .translatable(next ? "msg.enigmaticcurio.berserk.off" : "msg.enigmaticcurio.berserk.on")
                .withStyle(next ? ChatFormatting.RED : ChatFormatting.GREEN));
            event.setCanceled(true);
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }
}
