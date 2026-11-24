package com.hengx.enigmaticcurio;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 抑制扩展「惩罚态」的自检({@code -Denigmaticcurio.selftest=addonpunished})。
 *
 * <p>覆盖判定函数的六个分支 + 装了扩展时的反射冒烟。</p>
 */
public final class AddonPunishedSelfTest {

    private AddonPunishedSelfTest() {}

    /** 由 {@code EnigmaticCurio} 调用;只在 -D 开关命中时注册。 */
    public static void register() {
        String selftest = System.getProperty("enigmaticcurio.selftest");
        if (selftest == null || !selftest.contains("addonpunished")) {
            return;
        }
        MinecraftForge.EVENT_BUS.register(AddonPunishedSelfTest.class);
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        int pass = 0;
        int fail = 0;

        boolean t1 = AddonPunishedRules.shouldSuppressPunished(true, true, true, true);
        pass += t1 ? 1 : 0; fail += t1 ? 0 : 1;
        log("T1 (true,true,true,true) -> 抑制: " + (t1 ? "PASS" : "FAIL"));

        boolean t2 = !AddonPunishedRules.shouldSuppressPunished(true, true, true, false);
        pass += t2 ? 1 : 0; fail += t2 ? 0 : 1;
        log("T2 只有救赎之戒 -> 不抑制: " + (t2 ? "PASS" : "FAIL"));

        boolean t3 = !AddonPunishedRules.shouldSuppressPunished(true, true, false, true);
        pass += t3 ? 1 : 0; fail += t3 ? 0 : 1;
        log("T3 只有七咒之戒 -> 不抑制: " + (t3 ? "PASS" : "FAIL"));

        boolean t4 = !AddonPunishedRules.shouldSuppressPunished(true, true, false, false);
        pass += t4 ? 1 : 0; fail += t4 ? 0 : 1;
        log("T4 两戒都不在 -> 不抑制: " + (t4 ? "PASS" : "FAIL"));

        boolean t5 = !AddonPunishedRules.shouldSuppressPunished(true, false, true, true);
        pass += t5 ? 1 : 0; fail += t5 ? 0 : 1;
        log("T5 玩家不允许同戴(ringEnhanced=false) -> 不抑制: " + (t5 ? "PASS" : "FAIL"));

        boolean t6 = !AddonPunishedRules.shouldSuppressPunished(false, true, true, true);
        pass += t6 ? 1 : 0; fail += t6 ? 0 : 1;
        log("T6 补丁开关关 -> 不抑制: " + (t6 ? "PASS" : "FAIL"));

        // T8/T9: canEquip 场景(1.1.10 修的 bug —— 判据是"有七咒",不是"两戒都在")
        boolean t8 = BlessRingRules.canEquip(true, true);
        pass += t8 ? 1 : 0; fail += t8 ? 0 : 1;
        log("T8 有七咒之戒、救赎之戒未戴 -> 放行(true): " + (t8 ? "PASS" : "FAIL"));

        boolean t9 = !BlessRingRules.canEquip(true, false);
        pass += t9 ? 1 : 0; fail += t9 ? 0 : 1;
        log("T9 没有七咒之戒 -> 不干预(false): " + (t9 ? "PASS" : "FAIL"));
        // T7:反射冒烟(装了扩展时);没装扩展只报 SKIP
        try {
            Class<?> handler = Class.forName("auviotre.enigmatic.addon.handlers.SuperAddonHandler");
            Object result = handler.getMethod("isPunishedOne",
                    net.minecraft.world.entity.LivingEntity.class).invoke(null, new Object[] { null });
            log("T7 SuperAddonHandler 已加载, isPunishedOne(null)=" + result + " -> PASS");
            pass++;
        }
        catch (ClassNotFoundException | NoClassDefFoundError e) {
            log("T7 SKIP:未安装神秘遗物扩展");
        }
        catch (Throwable e) {
            log("T7 反射失败: " + e);
        }

        log("RESULT PASSED=" + pass + " FAILED=" + fail);
    }

    private static void log(String message) {
        EnigmaticCurio.LOGGER.info("[惩罚态补丁自检] {}", message);
    }
}
