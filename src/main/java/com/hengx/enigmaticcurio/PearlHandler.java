package com.hengx.enigmaticcurio;

import java.lang.reflect.Method;

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
 */
public final class PearlHandler {

    private static Method isPresent;
    private static Method mergeEnchantments;
    private static Method maybeApplyEternalBinding;
    private static boolean isPresentFailed;
    private static boolean helperFailed;

    private PearlHandler() {
    }

    /**
     * 珍珠的原始触发条件 —— 直接调用神秘遗物自己的实现
     * {@code EnchanterPearl#isPresent(Player)}(= 身为七咒之人 且 把珍珠装在 Curios 槽里)。
     * 反射不可用时退化成"身上带着珍珠"({@link PearlUtil#wearing})。
     */
    public static boolean pearlPresent(Player player) {
        if (player == null) {
            return false;
        }
        var item = PearlUtil.pearlItem();
        if (item == null) {
            return false;
        }
        try {
            Method m = isPresent;
            if (m == null) {
                if (isPresentFailed) {
                    return PearlUtil.wearing(player);
                }
                m = item.getClass().getMethod("isPresent", Player.class);
                isPresent = m;
            }
            Object result = m.invoke(item, player);
            return result instanceof Boolean b && b;
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            isPresentFailed = true;
            return PearlUtil.wearing(player);
        }
    }

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
            DebugLog.info("[附魔珍珠] 神化附魔台补掷: power={} cost={} extra={} -> {}",
                power, cost, extra.getAllEnchantments(), merged.getAllEnchantments());
            return merged;
        }
        catch (RuntimeException | LinkageError e) {
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
            return null;
        }
    }
}
