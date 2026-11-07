package com.hengx.enigmaticcurio;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
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
 *   <li><b>受伤优先级</b>:{@code 伤害吸收(金心)→ 假血条 → 真血(死亡)},见下一节;</li>
 *   <li>受伤走假血条:不扣真血;假血 ≤ 0 时才真正死亡;</li>
 *   <li>假血条上限 = 当前最大生命({@code setHealth} 自己会夹),生命上限变动自动反映。</li>
 * </ul>
 *
 * <h2>伤害结算顺序:吸收 → 假血 → 真血(1.20.1 实测)</h2>
 * <p><b>原版扣伤害吸收的确切位置</b>:{@code net.minecraft.world.entity.player.Player#actuallyHurt}
 * (以及父类 {@code net.minecraft.world.entity.LivingEntity#actuallyHurt},两者的字节码这一段完全一致)。</p>
 * <pre>
 *   protected void actuallyHurt(DamageSource, float)          // javap -p -c 序号
 *     11: invokestatic ForgeHooks.onLivingHurt(this, src, amount)  &lt;-- LivingHurtEvent 在这里(最开头)
 *     18: ifgt 22 / 21: return        &lt;-- 事件被 cancel ⇒ 函数直接返回,后面全都不会执行
 *     25: getDamageAfterArmorAbsorb                              &lt;-- ① 护甲
 *     32: getDamageAfterMagicAbsorb                              &lt;-- ② 保护附魔
 *     38: getAbsorptionAmount / 41: fsub / 43: Math.max(.,0)      &lt;-- ③ 吸收:先扣金心
 *     56: setAbsorptionAmount(getAbsorptionAmount() - 吸收量)
 *     61: invokestatic ForgeHooks.onLivingDamage(this, src, 溢出) &lt;-- LivingDamageEvent 只拿到溢出量
 *    147: setHealth(getHealth() - 溢出)                          &lt;-- ④ 最后才动真血
 * </pre>
 * <p>结论:假血条要 {@code setCanceled(true)} 拦真血,就<b>必须自己按 ③ 的顺序先扣 absorption</b>,
 * 否则金心永远不会被消耗(旧版的 bug)。分配算术抽到 {@link BloodbathDamage}(纯函数,可脱离
 * 游戏单测)。真实血量全程不动,所以吸收伤害也不可能穿透到真血致死。</p>
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
 * 触发方式与主模组一致:<b>Ctrl + 左键</b>(客户端识手势 → {@link CurioNetwork} 包 → 服务端校验 →
 * 写 NBT → 同步回客户端),统一由 {@link ItemToggleHandler} 处理;戴着的那一件在饰品栏界面里
 * Ctrl+左键 点它、拿在手上时在世界里 Ctrl+左键 都行。
 * <p>旧的「潜行 + 右键」已作废:潜行右键会被 Curios 抢去佩戴饰品,开关点不到。</p>
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

    /** 这叠东西是不是血战沙场之证。 */
    public static boolean isCharm(ItemStack stack) {
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

    /** 写入/清除"锁血已关闭"标记(清除时若 NBT 空了就一并清掉,不留空 CompoundTag)。 */
    public static void setLockOff(ItemStack stack, boolean off) {
        try {
            if (stack == null || stack.isEmpty()) {
                return;
            }
            if (off) {
                stack.getOrCreateTag().putBoolean(NBT_LOCK_DISABLED, true);
                return;
            }
            CompoundTag tag = stack.getTag();
            if (tag == null) {
                return;
            }
            tag.remove(NBT_LOCK_DISABLED);
            if (tag.isEmpty()) {
                stack.setTag(null);
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
            // 真血条只负责显示假血条的值(生命上限仍由属性决定)。
            // 注意:这里只碰 setHealth,**绝不碰 absorption** —— 1.20.1 里玩家的 absorption 是
            // Player 的同步数据 DATA_PLAYER_ABSORPTION_ID,和 DATA_HEALTH_ID 是两回事,setHealth
            // 不会动它,所以金心在每 tick 同步时会原样保留。
            if (Math.abs(player.getHealth() - fake) > 1.0E-3F) {
                player.setHealth(fake);
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------ 受伤 / 回血

    /**
     * 外部扣血 → <b>先扣伤害吸收(金心),溢出的部分再扣假血条</b>;真实血量永远不动。
     *
     * <p>为什么吸收要自己扣:见类注释里的字节码 —— {@code Player#actuallyHurt} 里
     * {@code ForgeHooks.onLivingHurt}(即本事件)在**方法最开头**,吸收代码在它**后面**;
     * 我们一 cancel,原版的吸收结算就整体被跳过(旧版"金心永不消耗"的根因)。</p>
     *
     * <p>为什么不用"放行给原版扣吸收":{@code LivingHurtEvent} 拿到的是**护甲/保护附魔减免之前**的
     * 伤害,原版随后会先算 {@code getDamageAfterArmorAbsorb}/{@code getDamageAfterMagicAbsorb}
     * 再和 absorption 相减;想精确地"只放行吸收那一段、其余转假血"就必须预测这两步的减免结果,
     * 而它们在事件里看不到(还存在自定义护甲公式的模组)。只要溢出量 > 0,放行就必然有真血损失
     * —— 与"假血 ≤0 才死"冲突。所以这里选**自己扣**:顺序固定、结果可复算、真血零风险。</p>
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingHurt(LivingHurtEvent event) {
        try {
            if (!(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            if (!barActive(player)) {
                return;
            }
            float amount = event.getAmount();
            if (!(amount > 0.0F) || !Float.isFinite(amount)) {
                // 0 / 负数 / NaN:没东西可扣,直接放行给原版(不能因为脏数据把人弄死)
                return;
            }
            float absorption = player.getAbsorptionAmount();
            BloodbathDamage.Split split = BloodbathDamage.split(absorption, amount);
            // ① 金心先挨打。Player#setAbsorptionAmount 写的是同步数据 DATA_PLAYER_ABSORPTION_ID,
            //    所以客户端的金心 HUD 会跟着掉,和原版表现一致。
            if (split.absorbed > 0.0F) {
                player.setAbsorptionAmount(Math.max(0.0F, absorption - split.absorbed));
            }
            // ② 这次伤害整体由假血条接管:真血一点不动,吸收也不会穿透到真血
            event.setAmount(0.0F);
            event.setCanceled(true);
            // ③ 只有金心吃不完的溢出部分才记到假血条上
            if (split.overflow > 0.0F) {
                float fake = fakeHealth(player);
                boolean fatal = BloodbathDamage.isFatal(fake, split.overflow);
                setFakeHealth(player, BloodbathDamage.after(fake, split.overflow));
                DebugLog.info("[血战沙场] {} 伤害 {} / 吸收 {} → 吸收剩 {} ,假血条 {} -> {}",
                    player.getName().getString(), amount, split.absorbed,
                    Math.max(0.0F, absorption - split.absorbed), fake,
                    BloodbathDamage.after(fake, split.overflow));
                if (fatal) {
                    killLocked(player);
                }
            }
            else {
                DebugLog.info("[血战沙场] {} 伤害 {} 全部被吸收吃下(吸收剩 {})",
                    player.getName().getString(), amount, Math.max(0.0F, absorption - split.absorbed));
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 失败放行:按原版受伤结算(吸收也回到原版逻辑,不会丢)
        }
    }

    /**
     * 回血一律无效(假血条不接受治疗)—— <b>但这条拦不到伤害吸收,也不需要拦</b>。
     *
     * <p>1.20.1 的字节码事实:</p>
     * <ul>
     *   <li>{@code LivingEntity#heal(float)} 只做三件事:{@code ForgeEventFactory.onLivingHeal}
     *       (就是本事件)→ 结果为 ≤0 则 return → {@code setHealth(health + amount)}。
     *       它<b>完全不碰</b> {@code absorptionAmount};</li>
     *   <li>金心只由 {@code net.minecraft.world.effect.AbsoptionMobEffect#addAttributeModifiers}
     *       写入({@code setAbsorptionAmount(get + 4*(amplifier+1))}),移除时对称扣回。
     *       喝金苹果 = 加吸收效果,走的是 {@code addEffect},<b>不经过 {@code heal()}</b>。</li>
     * </ul>
     * <p>所以取消本事件不可能吞掉金心/金苹果的吸收;吸收与再生是同一次金苹果给的两件东西,
     * 被拦下的只有再生那部分(本来就该无效)。反过来,这里也绝不能为了"放过吸收"而放行真实
     * 治疗 —— 见下面那道 {@link #barActive} 闸门的意义。</p>
     *
     * <p><b>只在这个状态下拦</b>:存活、非濒死、假血数据存在、开关没关、不在复活免疫窗口。
     * 这就是"复活后被无限吞治疗 → 反复死亡"那个死循环的闸门。</p>
     */
    @SubscribeEvent
    public static void onLivingHeal(LivingHealEvent event) {
        try {
            if (!(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            if (!(event.getAmount() > 0.0F)) {
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

    // ------------------------------------------------------------------ Ctrl + 左键 切换

    // 切换本身由 ItemToggleHandler 统一处理:客户端 Ctrl+左键 → CurioNetwork 包 → 服务端校验 →
    // setLockOff + afterToggle(resetFakeHealth)。本类只提供读写与"这一件是不是勋章"的判定。
}
