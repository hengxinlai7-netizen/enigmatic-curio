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

    public static void warn(String format, Object... args) {
        LOGGER.warn(format, args);
    }
}