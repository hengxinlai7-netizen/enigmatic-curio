package com.hengx.enigmaticcurio;

import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/**
 * 启动 / 热重载时把「万钧之护」相关配置的<b>当前实际值</b>打一行日志(受 {@code debugLog} 门控)。
 *
 * <p>这条日志是给"开关到底读到没有"用的:玩家报告"关了雷击还落雷"时,先看这一行 ——</p>
 * <pre>[万钧之护] 配置加载: 总开关 enigmaticcurio.addons.scaleEnabled=true 雷击开关 enigmaticcurio.addons.scaleLightning=false 加成 100.0% 恢复横扫=false → 雷击:关闭</pre>
 * <p>它同时证明了"改配置后有没有被重新读":Forge 的 COMMON 配置在文件变化时会发
 * {@link ModConfigEvent.Reloading},这里会再打一行"配置重载"。而本模组的三个判定
 * ({@link ScrollHandler#lightningAllowed()})每次都是<b>实时读</b>配置,不缓存,所以
 * 改完配置<b>不需要重启</b>。</p>
 */
public final class ConfigLogHandler {

    private ConfigLogHandler() {
    }

    /** 配置首次加载(游戏启动 / 进入世界)。 */
    public static void onLoading(ModConfigEvent.Loading event) {
        if (ours(event)) {
            log("加载");
        }
    }

    /** 配置文件被改动后重载(热生效)。 */
    public static void onReloading(ModConfigEvent.Reloading event) {
        if (ours(event)) {
            log("重载");
        }
    }

    /** 只关心本模组自己的配置;别人的配置加载/重载不该刷我们的日志。 */
    private static boolean ours(ModConfigEvent event) {
        try {
            ModConfig config = event.getConfig();
            return config != null && EnigmaticCurio.MODID.equals(config.getModId());
        }
        catch (RuntimeException | LinkageError e) {
            return true;   // 判断不出来就照打,宁可多一条也不丢信息
        }
    }

    private static void log(String phase) {
        try {
            if (!CurioConfig.debugLog()) {
                return;
            }
            boolean total = CurioConfig.SCALE_ENABLED.get();
            boolean lightning = CurioConfig.SCALE_LIGHTNING.get();
            DebugLog.info("[万钧之护] 配置%s: 总开关 enigmaticcurio.addons.scaleEnabled=%s 雷击开关 "
                    + "enigmaticcurio.addons.scaleLightning=%s 全伤加成=%s%% 恢复横扫=%s → 雷击:%s",
                phase, total, lightning, CurioConfig.SCALE_ATTACK_DAMAGE_PERCENT.get(),
                CurioConfig.SCALE_RESTORE_SWEEP.get(), total && lightning ? "开启" : "关闭");
        }
        catch (RuntimeException | LinkageError ignored) {
            // 记日志失败绝不能影响游戏
        }
    }
}
