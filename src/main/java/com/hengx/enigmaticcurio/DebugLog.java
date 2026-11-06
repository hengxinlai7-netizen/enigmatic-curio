package com.hengx.enigmaticcurio;

import com.mojang.logging.LogUtils;

/** 统一日志出口:默认静默,配置 debugLog=true 才输出。 */
public final class DebugLog {

    private static final org.slf4j.Logger LOGGER = EnigmaticCurio.LOGGER;

    private DebugLog() {
    }

    private static boolean enabled() {
        try {
            return CurioConfig.debugLog();
        }
        catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    public static void info(String format, Object... args) {
        if (enabled()) {
            LOGGER.info(format, args);
        }
    }

    /**
     * <b>不受 debugLog 门控</b>的日志:只给"必须让用户看见"的诊断用(书袋同步链路),
     * 调用方自己负责节流(5 秒一次),避免刷屏。
     */
    public static void infoAlways(String format, Object... args) {
        try {
            LOGGER.info(format, args);
        }
        catch (RuntimeException | LinkageError ignored) {
            // 记日志失败绝不能影响游戏
        }
    }

    public static void warn(String format, Object... args) {
        LOGGER.warn(format, args);
    }
}