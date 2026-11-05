package com.hengx.enigmaticcurio;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * 「附魔师的珍珠」在神化(Apotheosis)路径上的等价实现。
 *
 * <p><b>珍珠在神秘遗物里的原本实现</b>:{@code com.aizistral.enigmaticlegacy.mixin.MixinEnchantmentContainer}
 * 注入 {@code EnchantmentMenu#clickMenuButton(Player,int)} 与 {@code EnchantmentMenu#getGoldCount()}。
 * 前者的语义是:当 {@code EnchanterPearl.isPresent(player)} 时,自己接管这次附魔 ——</p>
 * <ol>
 *   <li>主附魔列表仍然来自附魔台自己的 {@code getEnchantmentList}(虚调用,所以神化的覆写会被用上);</li>
 *   <li>额外再掷一次 {@code EnchantmentHelper.enchantItem(random, 输入.copy(), min(costs[slot] + 7, 40), false)},
 *       并用 {@code SuperpositionHandler.mergeEnchantments} 合并进结果,再用
 *       {@code maybeApplyEternalBinding} 判定永恒绑定诅咒;</li>
 *   <li>全程不 shrink 青金石(配上 {@code getGoldCount() = 64},等于"附魔不再需要青金石")。</li>
 * </ol>
 *
 * <p>神化的 {@code ApothEnchantmentMenu} 覆写了 {@code clickMenuButton},神秘遗物的注入被遮蔽,
 * 所以珍珠在神化附魔台上失效。这里在神化那一侧补回等价的第 2、3 步
 * (主列表仍由神化自己算,不碰它的行为)。</p>
 *
 * <h2>触发条件的口径</h2>
 * {@code EnchanterPearl#isPresent(Player)} 的原始定义 = {@code isTheCursedOne(player) && hasCurio(player, this)}
 * —— 必须<b>身为七咒之人</b>且<b>把珍珠装进 Curios 槽</b>。本模组额外放行"珍珠在背包/副手"的情况
 * (主模组 1.2.22 的 {@code PearlUtil} 就是这个口径:携带即算),因为很多玩家是把珍珠放在背包里的;
 * 七咒之人的前置条件<b>保留</b>,不放宽。
 *
 * <h2>诊断</h2>
 * 关键分支都用 {@link DebugLog#warn}(<b>不受 {@code debugLog} 配置门控</b>、带 5 秒节流)打日志,
 * 因为这一项在上一版里"mixin 已 apply 但游戏内无效果",只能靠日志定位到底走到哪一步。
 */
public final class PearlHandler {

    /** 附魔师珍珠的注册 id。 */
    public static final String PEARL_ID = "enigmaticlegacy:enchanter_pearl";

    private static Method isPresent;
    private static Method isTheCursedOne;
    private static Method mergeEnchantments;
    private static Method maybeApplyEternalBinding;
    private static boolean isPresentFailed;
    private static boolean helperFailed;
    private static boolean cursedCheckFailed;

    /** 节流:同一条诊断 5 秒内只打一次,避免菜单每 tick 调用把日志刷爆。 */
    private static final Map<String, Long> LAST_WARN = new HashMap<>();
    private static final long WARN_INTERVAL_MS = 5000L;

    private PearlHandler() {
    }

    // ------------------------------------------------------------------ 携带判定

    /**
     * 珍珠是否"在场"(= 原来 {@code EnchanterPearl#isPresent} 的语义,携带口径放宽到背包/副手)。
     *
     * <p>判定顺序:</p>
     * <ol>
     *   <li>拿不到珍珠物品(没装神秘遗物 / id 变了)→ false(打一条诊断);</li>
     *   <li>直接问珍珠自己的 {@code isPresent(Player)}:true 就直接通过(与本体完全一致);</li>
     *   <li>它返回 false 或反射失败 → 退化成"身上带着珍珠 + 七咒之人"({@link PearlUtil#wearing} +
     *       {@code SuperpositionHandler.isTheCursedOne});这条分支会打诊断,所以看日志就知道走的是哪条;</li>
     *   <li>连"七咒之人"都问不到(反射失败)→ 只按"带着珍珠"算(最宽松的兜底,保证功能不至于整条失效)。</li>
     * </ol>
     */
    public static boolean pearlPresent(Player player) {
        if (player == null) {
            return false;
        }
        if (PearlUtil.pearlItem() == null) {
            warnThrottled("no-item", "[附魔珍珠] 取不到物品 {}:没装神秘遗物或 id 变了,珍珠增强不会生效",
                PEARL_ID);
            return false;
        }
        // ① 本体自己的实现
        try {
            Method m = isPresent;
            if (m == null && !isPresentFailed) {
                m = PearlUtil.pearlItem().getClass().getMethod("isPresent", Player.class);
                isPresent = m;
            }
            if (m != null) {
                Object result = m.invoke(PearlUtil.pearlItem(), player);
                if (result instanceof Boolean b && b) {
                    return true;
                }
            }
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            isPresentFailed = true;
            warnThrottled("isPresent-fail",
                "[附魔珍珠] EnchanterPearl#isPresent 反射失败({}),退化为按携带判定", e.toString());
        }
        // ② 退化:携带 + 七咒之人
        if (!PearlUtil.wearing(player)) {
            warnThrottled("not-wearing", "[附魔珍珠] 玩家 {} 身上没有附魔师珍珠,本次不生效",
                player.getName().getString());
            return false;
        }
        Boolean cursed = cursedOne(player);
        if (cursed == null) {
            warnThrottled("cursed-unknown",
                "[附魔珍珠] 问不到 isTheCursedOne,按\"只携带珍珠\"放行(可能放宽了前置条件)");
            return true;
        }
        if (!cursed) {
            warnThrottled("not-cursed",
                "[附魔珍珠] 玩家 {} 带着珍珠但不是七咒之人(未佩戴七咒之戒),本次不生效",
                player.getName().getString());
            return false;
        }
        warnThrottled("carried-cursed",
            "[附魔珍珠] 玩家 {} 走\"携带珍珠 + 七咒之人\"分支生效", player.getName().getString());
        return true;
    }

    /** {@code SuperpositionHandler.isTheCursedOne(Player)};问不到时 null(区别于 false)。 */
    private static Boolean cursedOne(Player player) {
        try {
            Method m = isTheCursedOne;
            if (m == null) {
                if (cursedCheckFailed) {
                    return null;
                }
                Class<?> handler = Class.forName("com.aizistral.enigmaticlegacy.handlers.SuperpositionHandler",
                    false, PearlHandler.class.getClassLoader());
                m = handler.getMethod("isTheCursedOne", Player.class);
                isTheCursedOne = m;
            }
            Object result = m.invoke(null, player);
            return result instanceof Boolean b ? b : null;
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            cursedCheckFailed = true;
            return null;
        }
    }

    // ------------------------------------------------------------------ 附魔台补掷

    /**
     * 神化附魔台上的"珍珠补掷":公式与神秘遗物原版一致 {@code min(cost + 7, 40)},然后合并
     * (含永恒绑定诅咒判定)。
     *
     * @param player   附魔的玩家
     * @param result   神化已经附魔完成的结果栈(会被替换)
     * @param original 附魔前的原始输入栈(用于按原版规则掷取额外附魔)
     * @param cost     该选项的附魔威力({@code EnchantmentMenu.costs[slot]})
     * @return 合并后的栈(即使没变化也返回原栈)
     */
    public static ItemStack applyExtraEnchant(Player player, ItemStack result, ItemStack original, int cost) {
        try {
            int power = Math.min(cost + 7, 40);
            ItemStack extra = EnchantmentHelper.enchantItem(player.getRandom(), original.copy(), power, false);
            // 3.8.5(主模组的结论):EnchantmentHelper#enchantItem 是一次【独立的原版掷取】,
            // 它不知道附魔台上挂了什么书架,所以会把过滤书架(例如海洋过滤书架)本该排掉的附魔
            // 重新塞进结果 —— 表现就是"过滤失效"。默认把补掷裁剪成"只提升输入栈本来就有的附魔"。
            if (onlyExisting()) {
                extra = keepOnlyExisting(extra, original);
            }
            ItemStack merged = mergeEnchantments(result, extra);
            merged = maybeApplyEternalBinding(merged);
            DebugLog.warn("[附魔珍珠] 神化附魔台补掷完成: power={} cost={} extra={} -> {}",
                power, cost, extra.getAllEnchantments(), merged.getAllEnchantments());
            return merged;
        }
        catch (RuntimeException | LinkageError e) {
            DebugLog.warn("[附魔珍珠] 补掷异常({}),本次不追加附魔", e.toString());
            return result;
        }
    }

    /** 补掷是否只提升已有附魔(默认 true,过滤书架兼容)。 */
    private static boolean onlyExisting() {
        try {
            return CurioConfig.PEARL_EXTRA_ONLY_EXISTING.get();
        }
        catch (RuntimeException | LinkageError e) {
            return true;
        }
    }

    /** 只保留 {@code base} 上已经存在的附魔(数量取栈本数量,不制造新附魔)。 */
    private static ItemStack keepOnlyExisting(ItemStack rolled, ItemStack base) {
        try {
            var baseEnch = base.getAllEnchantments();
            var rolledEnch = rolled.getAllEnchantments();
            if (rolledEnch.isEmpty()) {
                return rolled;
            }
            ItemStack out = base.copy();
            boolean any = false;
            for (var e : rolledEnch.entrySet()) {
                if (baseEnch.containsKey(e.getKey())) {
                    out.enchant(e.getKey(), e.getValue());
                    any = true;
                }
            }
            return any ? out : base.copy();
        }
        catch (RuntimeException | LinkageError ignored) {
            return rolled;
        }
    }

    /** {@code SuperpositionHandler.mergeEnchantments(ItemStack, ItemStack, boolean, boolean)}。 */
    public static ItemStack mergeEnchantments(ItemStack base, ItemStack other) {
        Object result = invokeHelper("mergeEnchantments",
            new Class<?>[] {ItemStack.class, ItemStack.class, boolean.class, boolean.class},
            base, other, Boolean.FALSE, Boolean.FALSE);
        return result instanceof ItemStack stack ? stack : base;
    }

    /** {@code SuperpositionHandler.maybeApplyEternalBinding(ItemStack)}。 */
    public static ItemStack maybeApplyEternalBinding(ItemStack stack) {
        Object result = invokeHelper("maybeApplyEternalBinding", new Class<?>[] {ItemStack.class}, stack);
        return result instanceof ItemStack value ? value : stack;
    }

    private static Object invokeHelper(String name, Class<?>[] types, Object... args) {
        if (helperFailed) {
            return null;
        }
        try {
            Method m = "mergeEnchantments".equals(name) ? mergeEnchantments : maybeApplyEternalBinding;
            if (m == null) {
                Class<?> handler = Class.forName("com.aizistral.enigmaticlegacy.handlers.SuperpositionHandler",
                    false, PearlHandler.class.getClassLoader());
                m = handler.getMethod(name, types);
                if ("mergeEnchantments".equals(name)) {
                    mergeEnchantments = m;
                }
                else {
                    maybeApplyEternalBinding = m;
                }
            }
            return m.invoke(null, args);
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            helperFailed = true;
            DebugLog.warn("[附魔珍珠] SuperpositionHandler#{} 反射失败({}),之后不再尝试",
                name, e.toString());
            return null;
        }
    }

    // ------------------------------------------------------------------ 诊断

    /** 不受配置门控的诊断日志,同一个 key 5 秒内只打一次。 */
    public static void warnThrottled(String key, String format, Object... args) {
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

    /** 清空节流表(自检用)。 */
    public static void resetWarnThrottle() {
        LAST_WARN.clear();
    }

    // ------------------------------------------------------------------ 启动探针

    /**
     * 启动时用反射核对"注入目标是否真的存在"。
     *
     * <p>为什么需要:两个珍珠 mixin 的注入器都是 {@code require = 0}(神化版本漂移时不许崩游戏),
     * 代价是**注入失败也是静默的** —— 日志里只会有一句 "Mixing ... into ..."。
     * 这里把编译器当时用的方法名/描述符在运行期再核一遍,结果直接写进日志(不受 {@code debugLog} 门控),
     * 这样"mixin 生效但没反应"时,第一步就能排除"目标根本不存在"。</p>
     */
    public static void probeTargets(net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            probeClass("dev.shadowsoffire.apotheosis.ench.table.ApothEnchantmentMenu",
                "m_6366_", Player.class, int.class);
            probeClass("dev.shadowsoffire.apotheosis.adventure.affix.reforging.ReforgingMenu",
                "getSigilCost", int.class);
            probeClass("dev.shadowsoffire.apotheosis.adventure.affix.reforging.ReforgingMenu",
                "getMatCost", int.class);
            probeClass("dev.shadowsoffire.apotheosis.adventure.affix.reforging.ReforgingMenu",
                "getMatCount");
            // 远古重铸台(独立模组 ancientreforging):玩家实际用的很可能就是它
            probeClass("com.ianm1647.ancientreforging.screen.AncientReforgingMenu",
                "getSigilCost", int.class);
            probeClass("com.ianm1647.ancientreforging.screen.AncientReforgingMenu",
                "getMatCost", int.class);
            probeClass("com.ianm1647.ancientreforging.screen.AncientReforgingMenu",
                "getMatCount");
            // 神化强化台(不消耗强化符石)
            probeClass("dev.shadowsoffire.apotheosis.adventure.affix.augmenting.AugmentingMenu",
                "getSigils");
            probeClass("dev.shadowsoffire.apotheosis.adventure.affix.augmenting.AugmentingMenu",
                "m_6366_", Player.class, int.class);
            probeClass("com.aizistral.enigmaticlegacy.items.EnchanterPearl", "isPresent", Player.class);
            DebugLog.warn("[附魔珍珠] 珍珠物品解析:{}", PearlUtil.pearlItem() == null ? "失败(id 变了或没装神秘遗物)" : "成功");
        });
    }

    private static void probeClass(String className, String method, Class<?>... params) {
        try {
            Class<?> cls = Class.forName(className, false, PearlHandler.class.getClassLoader());
            Method m = cls.getMethod(method, params);
            DebugLog.warn("[附魔珍珠] 注入目标存在:{}#{} -> {}", cls.getSimpleName(), m.getName(),
                m.getReturnType().getSimpleName());
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            DebugLog.warn("[附魔珍珠] 注入目标缺失:{}({}) —— 对应的 mixin 注入不会生效({})",
                className, method, e.getClass().getSimpleName());
        }
    }
}
