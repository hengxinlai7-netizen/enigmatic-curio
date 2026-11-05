package com.hengx.enigmaticcurio;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/**
 * 神秘遗物系饰品增强的配置(config/enigmaticcurio-common.toml)。
 *
 * <p>只放这 13 项饰品增强用得到的开关;战利品类配置仍留在主模组。</p>
 */
public final class CurioConfig {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue DEBUG_LOG;

    // 神秘遗物
    public static final ForgeConfigSpec.BooleanValue BOOKS_ENABLED;
    public static final ForgeConfigSpec.BooleanValue PEARL_FIX_ENCHANTING;
    public static final ForgeConfigSpec.BooleanValue PEARL_FIX_REFORGE;
    public static final ForgeConfigSpec.BooleanValue PEARL_EXTRA_ONLY_EXISTING;

    // 扩展
    public static final ForgeConfigSpec.BooleanValue BLOODBATH_ENABLED;
    public static final ForgeConfigSpec.DoubleValue BLOODBATH_LOCK_VALUE;
    public static final ForgeConfigSpec.BooleanValue ENGINE_GUARD;
    public static final ForgeConfigSpec.BooleanValue RING_ENHANCED;
    public static final ForgeConfigSpec.BooleanValue ADVENTURE_CHARM_MINING;
    public static final ForgeConfigSpec.BooleanValue ADVENTURE_CHARM_CONVERT;
    public static final ForgeConfigSpec.BooleanValue FORGER_GEM_MAX;
    public static final ForgeConfigSpec.BooleanValue SCALE_ENABLED;
    public static final ForgeConfigSpec.BooleanValue SCALE_LIGHTNING;
    public static final ForgeConfigSpec.BooleanValue SCALE_RESTORE_SWEEP;
    public static final ForgeConfigSpec.DoubleValue SCALE_ATTACK_DAMAGE_PERCENT;
    public static final ForgeConfigSpec.BooleanValue FOURTH_CURSE_FIX;
    public static final ForgeConfigSpec.BooleanValue XP_SCROLL_CAP;
    public static final ForgeConfigSpec.IntValue XP_SCROLL_CAP_VALUE;

    // 佳肴
    public static final ForgeConfigSpec.BooleanValue GLUTTONY_LOCK;

    // 已有两项
    public static final ForgeConfigSpec.IntValue MAGNET_RANGE;
    public static final ForgeConfigSpec.BooleanValue REVIVAL_LEAF_TOGGLE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();
        b.comment("神秘遗物系饰品增强:所有开关都在这里。").push("enigmaticcurio");

        b.comment("调试").push("debug");
        DEBUG_LOG = b.comment("输出详细日志。默认 false。").define("debugLog", false);
        b.pop();

        b.comment("神秘遗物本体").push("legacy");
        BOOKS_ENABLED = b.comment("书籍可 Ctrl+左键 锁定,锁定后手持右键不打开。默认 true。")
            .define("booksEnabled", true);
        PEARL_FIX_ENCHANTING = b.comment("附魔师珍珠兼容神化附魔台。默认 true。")
            .define("pearlFixEnchanting", true);
        PEARL_FIX_REFORGE = b.comment("装神化时,佩戴珍珠用重铸台不消耗新生符石。默认 true。")
            .define("pearlFixReforge", true);
        PEARL_EXTRA_ONLY_EXISTING = b.comment("珍珠在神化附魔台上的补掷是否只提升输入物品【已有】的附魔。",
                "true = 不新增任何新附魔(过滤书架/海洋过滤书架的限制不会被绕过);",
                "false = 允许补掷带出新附魔。默认 true。")
            .define("pearlExtraEnchantOnlyExisting", true);
        b.pop();

        b.comment("神秘遗物扩展").push("addons");
        BLOODBATH_ENABLED = b.comment("血战沙场之证:假血条锁血,可游戏内切换(潜行+右键)。默认 true。")
            .define("bloodbathEnabled", true);
        BLOODBATH_LOCK_VALUE = b.comment("血战沙场之证:锁到多少点生命值。下限 0.5(写 0 会直接死亡)。默认 1。")
            .defineInRange("bloodbathLockValue", 1.0D, 0.5D, 1024.0D);
        ENGINE_GUARD = b.comment("取消失落引擎的雷击惩罚。默认 true。")
            .define("engineGuard", true);
        RING_ENHANCED = b.comment("救赎之戒增强:去惩罚条、不破碎、可与七咒之戒同戴。默认 true。")
            .define("ringEnhanced", true);
        ADVENTURE_CHARM_MINING = b.comment("去除冒险者/绝望者徽章无法挖掘的限制。默认 true。")
            .define("adventureCharmMining", true);
        ADVENTURE_CHARM_CONVERT = b.comment("手持冒险者徽章死亡自动转化绝望者徽章。默认 true。")
            .define("adventureCharmConvert", true);
        FORGER_GEM_MAX = b.comment("锻造者宝石:铁砧两本同级同附魔书直接升满级。默认 true。")
            .define("forgerGemMax", true);
        SCALE_ENABLED = b.comment("万钧之护:总开关。关掉后雷击与加成都不生效,完全回到扩展原样。默认 true。")
            .define("scaleEnabled", true);
        SCALE_LIGHTNING = b.comment("万钧之护:雷击效果。关掉后攻击不再累积电荷、不会再落雷(护甲穿透照旧)。默认 true。")
            .define("scaleLightning", true);
        SCALE_RESTORE_SWEEP = b.comment("是否恢复扩展原本的横扫 100% 加成。默认 false。")
            .define("scaleRestoreSweep", false);
        SCALE_ATTACK_DAMAGE_PERCENT = b.comment("万钧之护:佩戴时获得的攻击伤害加成百分比(代替原本的横扫加成)。0 = 不加成。默认 100。")
            .defineInRange("scaleAttackDamagePercent", 100.0D, -100.0D, 1000.0D);
        FOURTH_CURSE_FIX = b.comment("古旧书袋三书同持时修正第四诅咒。默认 true。")
            .define("fourthCurseFix", true);
        XP_SCROLL_CAP = b.comment("无知诅咒卷轴加经验上限,防溢出。默认 true。")
            .define("xpScrollCap", true);
        XP_SCROLL_CAP_VALUE = b.comment("经验上限值。默认 1000000。")
            .defineInRange("xpScrollCapValue", 1_000_000, 1, Integer.MAX_VALUE);
        b.pop();

        b.comment("神秘佳肴").push("delicacy");
        GLUTTONY_LOCK = b.comment("无止暴食之证:饱食度锁 0、饱和度锁 1。默认 true。")
            .define("gluttonyLock", true);
        b.pop();

        b.comment("已有功能").push("existing");
        MAGNET_RANGE = b.comment("磁吸戒指/超级磁吸戒指 吸取范围。默认 36。")
            .defineInRange("magnetRange", 36, 1, 64);
        REVIVAL_LEAF_TOGGLE = b.comment("复苏之叶飞行开关(Ctrl+左键空挥)。默认 true。")
            .define("revivalLeafToggle", true);
        b.pop();

        b.pop();
        SPEC = b.build();
    }

    private CurioConfig() {
    }

    public static void register() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC, "enigmaticcurio-common.toml");
    }

    public static boolean debugLog() {
        return safe(DEBUG_LOG, false);
    }

    private static boolean safe(ForgeConfigSpec.BooleanValue v, boolean def) {
        try {
            return v.get();
        }
        catch (RuntimeException | LinkageError e) {
            return def;
        }
    }
}