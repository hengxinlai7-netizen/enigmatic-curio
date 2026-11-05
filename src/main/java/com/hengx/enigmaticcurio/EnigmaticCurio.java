package com.hengx.enigmaticcurio;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/**
 * 神秘遗物饰品增强 —— 只做「对现有饰品的修改」,不新增任何物品。
 *
 * <ul>
 *   <li>磁吸戒指 / 超级磁吸戒指:吸取范围改为 36 格;</li>
 *   <li>复苏之叶:Ctrl+左键 开关它自己授予的创造飞行(不影响其它来源的飞行);</li>
 *   <li>血战沙场之证:自带锁定 1 血,Ctrl+右键 切换;</li>
 *   <li>书籍:Ctrl+右键 锁定,锁定后右键不再打开;</li>
 *   <li>冒险者之证 / 绝望者证章:可正常挖掘方块;手持冒险者之证死亡转化为绝望者证章;</li>
 *   <li>失落引擎:拦截惩罚雷击;</li>
 *   <li>锻造者的宝石:铁砧上两本同级同附魔书直接升满级;</li>
 *   <li>无知诅咒卷轴:单次获得的经验加上限,防溢出;</li>
 *   <li>无止暴食之证:饱食度锁 0、饱和度锁 1;</li>
 *   <li>末影之戒 / 末影箱:容量扩到 54 格(9x6);</li>
 *   <li>救赎之戒:不破损、隐藏背叛条、可与七咒之戒同戴(mixin);</li>
 *   <li>七咒之戒:古旧书袋收齐三书时修正第四重咒(mixin);</li>
 *   <li>万钧之护卷轴:雷击可开关、攻击伤害加成可配置(mixin);</li>
 *   <li>附魔师的珍珠:兼容神化附魔台、重铸台不消耗新生符石(mixin)。</li>
 * </ul>
 */
@Mod(EnigmaticCurio.MODID)
public class EnigmaticCurio {

    public static final String MODID = "enigmaticcurio";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EnigmaticCurio() {
        CurioConfig.register();
        MinecraftForge.EVENT_BUS.register(MagnetRangeHandler.class);
        MinecraftForge.EVENT_BUS.register(RevivalLeafHandler.class);
        MinecraftForge.EVENT_BUS.register(GluttonyCharmHandler.class);
        MinecraftForge.EVENT_BUS.register(EngineGuardHandler.class);
        MinecraftForge.EVENT_BUS.register(BookLockHandler.class);
        MinecraftForge.EVENT_BUS.register(AdventureCharmHandler.class);
        MinecraftForge.EVENT_BUS.register(BerserkCharmHandler.class);
        MinecraftForge.EVENT_BUS.register(XpScrollCapHandler.class);
        MinecraftForge.EVENT_BUS.register(ForgerGemAnvilHandler.class);
        MinecraftForge.EVENT_BUS.register(ItemTooltips.class);
        registerCuriosEvents();
        FMLJavaModLoadingContext.get().getModEventBus().addListener(MagnetRangeHandler::onCommonSetup);
        MagnetRangeHandler.apply();
    }

    /**
     * Curios 相关的事件注册。
     *
     * <p>{@link ScrollAttributeHandler} 引用了 Curios 的类型,所以必须"先确认 Curios 已加载,
     * 再去触碰这个类",否则缺 Curios 时会 NoClassDefFoundError。</p>
     */
    private static void registerCuriosEvents() {
        try {
            if (!net.minecraftforge.fml.ModList.get().isLoaded("curios")) {
                return;
            }
            MinecraftForge.EVENT_BUS.register(new ScrollAttributeHandler());
        }
        catch (RuntimeException | LinkageError e) {
            DebugLog.warn("[饰品增强] 跳过 Curios 属性事件注册: {}", e.toString());
        }
    }
}
