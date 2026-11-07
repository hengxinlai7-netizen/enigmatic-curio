package com.hengx.enigmaticcurio;

import net.minecraft.nbt.CompoundTag;
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
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 神秘遗物扩展:增强「锻造者宝石」。
 *
 * <p>原版铁砧合并两本"同级同附魔"的书只会 +1 级。现在携带锻造者宝石时,
 * 直接升到该附魔在<b>当前环境</b>里的最高等级。</p>
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
            Map<Enchantment, Integer> a = EnchantmentHelper.getEnchantments(left);
            Map<Enchantment, Integer> b = EnchantmentHelper.getEnchantments(right);
            if (a.size() != 1 || b.size() != 1) {
                return;
            }
            Map.Entry<Enchantment, Integer> ea = a.entrySet().iterator().next();
            Map.Entry<Enchantment, Integer> eb = b.entrySet().iterator().next();
            if (!ea.getKey().equals(eb.getKey()) || !ea.getValue().equals(eb.getValue())) {
                return;   // 必须同一附魔、同一等级
            }

            Enchantment ench = ea.getKey();
            int level = ea.getValue();
            int vanillaMax = ench.getMaxLevel();
            int apothMax = apothMaxLevel(ench);
            int target = Math.max(vanillaMax, apothMax);
            if (level >= target) {
                return;   // 已达当前环境的上限
            }

            // 关键:复制左槽整本书(display/lore/HideFlags/RepairCost/affix_data 等全部保留)
            ItemStack out = left.copy();
            out.setCount(1);
            // 取出附魔表,把目标附魔"替换"为上限级,再写回(不是额外多挂一条)
            Map<Enchantment, Integer> enchants = EnchantmentHelper.getEnchantments(out);
            enchants.put(ench, target);
            EnchantmentHelper.setEnchantments(enchants, out);

            event.setOutput(out);
            event.setCost(Math.max(1, target));
            event.setMaterialCost(1);   // 与普通铁砧一致:始终消耗 1 本副书

            if (shouldLog()) {
                DebugLog.infoAlways(
                    "[锻造者宝石] 附魔书直升 {} | 左={} | 右={} | 等级 {} -> {} | vanillaMax={} apothMax={} targetMax={} | 输出NBT={}",
                    ench.getDescriptionId(), describe(a), describe(b), level, target,
                    vanillaMax, apothMax < 0 ? "n/a" : Integer.valueOf(apothMax), target, tagKeys(out));
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 铁砧是热路径:任何异常都不能影响原版行为
        }
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

    private static String describe(Map<Enchantment, Integer> enchants) {
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<Enchantment, Integer> entry : enchants.entrySet()) {
            if (sb.length() > 1) {
                sb.append(", ");
            }
            sb.append(entry.getKey().getDescriptionId()).append('=').append(entry.getValue());
        }
        return sb.append('}').toString();
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
