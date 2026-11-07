package com.hengx.enigmaticcurio;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.event.AnvilUpdateEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.ToIntFunction;

/**
 * 神秘遗物扩展:增强「锻造者宝石」。
 *
 * <p>原版铁砧合并两本"同级同附魔"的书只会 +1 级。现在携带锻造者宝石时,
 * 把两本书<b>都拥有、且等级相同</b>的每一个附魔,直接升到它在<b>当前环境</b>里的最高等级
 * (见 {@link UpgradePlan})。只在一本书里出现、或两本等级不同的附魔<b>不动</b>。</p>
 *
 * <p><b>为什么上限不能直接用 {@link Enchantment#getMaxLevel()}:</b>
 * 神化(Apotheosis)用 coremod 把 {@code EnchantedBookItem}、{@code AnvilMenu} 等类里的
 * {@code Enchantment#getMaxLevel()} 调用重定向到
 * {@code dev.shadowsoffire.apotheosis.ench.asm.EnchHooks#getMaxLevel(Enchantment)}
 * (证据:神化 jar 内 {@code coremods/ench/ench_info_redirector.js} 的 maxLevelNode;
 * 该方法再走 {@code EnchModule.getEnchInfo(ench).getMaxLevel()})。本模组的类不在
 * 改写名单里,直接调用只会拿到原版上限,于是出现"升到了原版上限 / 与神化记录的上限不一致"。
 * 所以这里用反射走同一条链,神化缺席或反射失败时退回原版上限。</p>
 *
 * <p><b>为什么输出必须复制左槽:</b>铁砧惯例是左槽数据获胜。新建 {@code ItemStack} 会丢掉
 * {@code display.Name}/{@code display.Lore}/{@code RepairCost} 以及神化的 {@code affix_data} 等
 * 全部 tag。这里复制左槽后只重写附魔数据,其余 tag 原样保留。</p>
 */
public final class ForgerGemAnvilHandler {

    private static final String GEM_ID = "enigmaticaddons:forger_gem";

    private static final String APOTH_MODID = "apotheosis";
    /** 神化 coremod 实际使用的入口:EnchHooks#getMaxLevel(Enchantment)I */
    private static final String APOTH_HOOKS_CLASS = "dev.shadowsoffire.apotheosis.ench.asm.EnchHooks";
    /** 退化入口:EnchModule#getEnchInfo(Enchantment)EnchantmentInfo + EnchantmentInfo#getMaxLevel()I */
    private static final String APOTH_MODULE_CLASS = "dev.shadowsoffire.apotheosis.ench.EnchModule";
    private static final String APOTH_INFO_CLASS = "dev.shadowsoffire.apotheosis.ench.EnchantmentInfo";
    private static final String APOTH_MAX_LEVEL = "getMaxLevel";

    /** 诊断日志节流:5 秒一条。 */
    private static final long LOG_INTERVAL_NS = 5_000_000_000L;

    private static Item gem;

    /** 神化上限反射入口(只会解析一次)。 */
    private static boolean apothLookedUp;
    private static Method apothHooksMax;
    private static Method apothInfoGetter;
    private static Method apothInfoMax;

    private static final AtomicLong LAST_LOG = new AtomicLong();

    private ForgerGemAnvilHandler() {
    }

    @SubscribeEvent
    public static void onAnvil(AnvilUpdateEvent event) {
        try {
            if (!CurioConfig.FORGER_GEM_MAX.get()) {
                return;
            }
            Player player = event.getPlayer();
            if (player == null) {
                return;
            }
            ItemStack left = event.getLeft();
            ItemStack right = event.getRight();
            if (!left.is(Items.ENCHANTED_BOOK) || !right.is(Items.ENCHANTED_BOOK)) {
                return;
            }
            if (!carrying(player)) {
                return;
            }
            Map<Enchantment, Integer> leftEnch = EnchantmentHelper.getEnchantments(left);
            Map<Enchantment, Integer> rightEnch = EnchantmentHelper.getEnchantments(right);
            if (leftEnch.isEmpty() || rightEnch.isEmpty()) {
                return;
            }

            // 附魔 → "注册名 → 等级" + "注册名 → Enchantment"(日志、写回都要用)
            Map<String, Integer> leftLevels = new LinkedHashMap<>();
            Map<String, Enchantment> byId = new LinkedHashMap<>();
            index(leftEnch, leftLevels, byId);
            Map<String, Integer> rightLevels = new LinkedHashMap<>();
            index(rightEnch, rightLevels, null);

            // 1. 可升级集合:两本书都有、且等级相同(多词条书会命中多条)
            Map<String, Integer> candidates = UpgradePlan.candidates(leftLevels, rightLevels);
            if (candidates.isEmpty()) {
                return;   // 没有共同同级附魔:交回原版行为
            }

            // 2. 每个可升级附魔取上限 = max(原版上限, 神化上限);顺手缓存给日志用
            Map<String, int[]> maxes = new HashMap<>();   // id -> {vanillaMax, apothMax, targetMax}
            ToIntFunction<String> maxLookup = id -> {
                int[] cached = maxes.get(id);
                if (cached == null) {
                    Enchantment ench = byId.get(id);
                    int vanillaMax = ench == null ? 1 : ench.getMaxLevel();
                    int apothMax = ench == null ? -1 : apothMaxLevel(ench);
                    cached = new int[] {vanillaMax, apothMax, Math.max(vanillaMax, apothMax)};
                    maxes.put(id, cached);
                }
                return cached[2];
            };

            // 3. 已满级的剔除;一条都不剩 ⇒ 不输出(交回原版行为)
            Map<String, Integer> changes = UpgradePlan.applyMax(candidates, maxLookup);
            if (changes.isEmpty()) {
                if (shouldLog()) {
                    DebugLog.infoAlways(
                        "[锻造者宝石] 可升级附魔全部已在当前环境上限,不输出 | 可升级集合={} | 明细={} | 左={} | 右={}",
                        candidates.keySet(), detail(candidates, changes, maxes),
                        describeIds(leftLevels), describeIds(rightLevels));
                }
                return;
            }

            // 关键:复制左槽整本书(display/lore/HideFlags/RepairCost/affix_data 等全部保留)
            ItemStack out = left.copy();
            out.setCount(1);
            // 取出附魔表,逐条把目标附魔"替换"为上限级,再写回(不是额外多挂一条)
            Map<Enchantment, Integer> enchants = EnchantmentHelper.getEnchantments(out);
            int cost = 1;
            for (Map.Entry<String, Integer> change : changes.entrySet()) {
                Enchantment ench = byId.get(change.getKey());
                if (ench == null) {
                    continue;   // 理论上不会发生:candidates 的键全部来自左槽
                }
                enchants.put(ench, change.getValue());
                // 原版 AnvilMenu 在 cost >= 40 时判定"过于昂贵",取最大目标等级而不是求和,
                // 保证多词条书也能真的被取走(单词条时与旧行为完全一致)
                cost = Math.max(cost, change.getValue());
            }
            EnchantmentHelper.setEnchantments(enchants, out);

            event.setOutput(out);
            event.setCost(cost);
            event.setMaterialCost(1);   // 与普通铁砧一致:始终消耗 1 本副书

            if (shouldLog()) {
                DebugLog.infoAlways(
                    "[锻造者宝石] 附魔书直升(多词条) | 可升级集合={} | 明细={} | 左={} | 右={} | 实际改动={} | cost={} | 输出NBT={}",
                    candidates.keySet(), detail(candidates, changes, maxes),
                    describeIds(leftLevels), describeIds(rightLevels),
                    UpgradePlan.describe(changes), cost, tagKeys(out));
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 铁砧是热路径:任何异常都不能影响原版行为
        }
    }

    /** 附魔表 → 以注册名为键的两张表;{@code byId} 允许为 {@code null}(只需等级时)。 */
    private static void index(Map<Enchantment, Integer> source,
                              Map<String, Integer> levels,
                              Map<String, Enchantment> byId) {
        for (Map.Entry<Enchantment, Integer> entry : source.entrySet()) {
            String id = idOf(entry.getKey());
            levels.put(id, entry.getValue());
            if (byId != null) {
                byId.put(id, entry.getKey());
            }
        }
    }

    /** 附魔的稳定 id:注册名;极端情况下(未注册)退回 descriptionId。 */
    private static String idOf(Enchantment ench) {
        ResourceLocation key = BuiltInRegistries.ENCHANTMENT.getKey(ench);
        return key != null ? key.toString() : ench.getDescriptionId();
    }

    // ------------------------------------------------------------------
    // 神化附魔上限:EnchHooks#getMaxLevel(Enchantment)I
    // ------------------------------------------------------------------

    /**
     * @return 神化允许的最大等级;{@code >=1} 表示取到,{@code -1} 表示神化缺席或反射失败。
     */
    private static int apothMaxLevel(Enchantment ench) {
        if (!apothLookedUp) {
            apothLookedUp = true;
            resolveApoth();
        }
        try {
            if (apothHooksMax != null) {
                Object value = apothHooksMax.invoke(null, ench);
                if (value instanceof Integer i && i > 0) {
                    return i;
                }
            }
            else if (apothInfoGetter != null && apothInfoMax != null) {
                Object info = apothInfoGetter.invoke(null, ench);
                if (info != null) {
                    Object value = apothInfoMax.invoke(info);
                    if (value instanceof Integer i && i > 0) {
                        return i;
                    }
                }
            }
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // 只报一次,之后静默退回原版上限
            DebugLog.warn("[锻造者宝石] 读取神化附魔上限失败,本件按原版上限处理: {}", e.toString());
            apothHooksMax = null;
            apothInfoGetter = null;
            apothInfoMax = null;
        }
        return -1;
    }

    /** 解析一次神化上限入口:优先 coremod 实际调用的 EnchHooks,失败再试 EnchModule/EnchantmentInfo。 */
    private static void resolveApoth() {
        ModList modList = ModList.get();
        if (modList == null || !modList.isLoaded(APOTH_MODID)) {
            return;
        }
        ClassLoader cl = ForgerGemAnvilHandler.class.getClassLoader();
        try {
            Class<?> hooks = Class.forName(APOTH_HOOKS_CLASS, false, cl);
            apothHooksMax = findStatic(hooks, APOTH_MAX_LEVEL, Enchantment.class);
        }
        catch (ClassNotFoundException | LinkageError | RuntimeException e) {
            apothHooksMax = null;
        }
        if (apothHooksMax != null) {
            DebugLog.info("[锻造者宝石] 神化上限入口: {}#getMaxLevel(Enchantment)", APOTH_HOOKS_CLASS);
            return;
        }
        try {
            Class<?> module = Class.forName(APOTH_MODULE_CLASS, false, cl);
            Class<?> info = Class.forName(APOTH_INFO_CLASS, false, cl);
            apothInfoGetter = module.getMethod("getEnchInfo", Enchantment.class);
            apothInfoMax = info.getMethod(APOTH_MAX_LEVEL);
            DebugLog.info("[锻造者宝石] 神化上限入口: {}#getEnchInfo + {}#getMaxLevel()",
                APOTH_MODULE_CLASS, APOTH_INFO_CLASS);
        }
        catch (ClassNotFoundException | NoSuchMethodException | LinkageError | RuntimeException e) {
            apothInfoGetter = null;
            apothInfoMax = null;
            DebugLog.warn("[锻造者宝石] 神化已加载,但读不到附魔上限入口({}),按原版上限生效", e.toString());
        }
    }

    private static Method findStatic(Class<?> owner, String name, Class<?>... params) {
        try {
            Method m = owner.getMethod(name, params);
            return Modifier.isStatic(m.getModifiers()) ? m : null;
        }
        catch (NoSuchMethodException e) {
            // 参数类型在映射/版本上有差异时,按"名字 + 单个可接收 Enchantment 的参数"扫一遍
            for (Method m : owner.getMethods()) {
                if (!name.equals(m.getName()) || !Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                Class<?>[] actual = m.getParameterTypes();
                if (actual.length == 1 && actual[0].isAssignableFrom(params[0])) {
                    return m;
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 诊断
    // ------------------------------------------------------------------

    private static boolean shouldLog() {
        long now = System.nanoTime();
        long last = LAST_LOG.get();
        return now - last >= LOG_INTERVAL_NS && LAST_LOG.compareAndSet(last, now);
    }

    /** 可升级集合的逐条明细:{@code id N->T (vanillaMax=X, apothMax=Y)}。 */
    private static String detail(Map<String, Integer> candidates,
                                 Map<String, Integer> changes,
                                 Map<String, int[]> maxes) {
        StringBuilder sb = new StringBuilder("[");
        for (Map.Entry<String, Integer> entry : candidates.entrySet()) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            String id = entry.getKey();
            int[] max = maxes.get(id);
            Integer target = changes.get(id);
            sb.append(id).append(' ').append(entry.getValue()).append("->")
                .append(target != null ? target : entry.getValue())
                .append(" (vanillaMax=").append(max == null ? "?" : max[0])
                .append(", apothMax=").append(max == null || max[1] < 0 ? "n/a" : max[1])
                .append(')');
        }
        return sb.append(']').toString();
    }

    private static String describeIds(Map<String, Integer> levels) {
        return UpgradePlan.describe(levels);
    }

    private static String tagKeys(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? "[]" : tag.getAllKeys().toString();
    }

    // ------------------------------------------------------------------
    // 是否携带锻造者宝石
    // ------------------------------------------------------------------

    private static boolean carrying(Player player) {
        if (gem == null) {
            gem = Superposition.item(GEM_ID);
        }
        if (gem == null) {
            return false;
        }
        if (Superposition.hasCurio(player, gem)) {
            return true;
        }
        for (ItemStack s : player.getInventory().items) {
            if (!s.isEmpty() && s.is(gem)) {
                return true;
            }
        }
        return false;
    }
}
