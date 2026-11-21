package com.hengx.enigmaticcurio;

import java.util.Arrays;

import com.mojang.logging.LogUtils;

/**
 * 统一日志出口:默认静默,配置 debugLog=true 才输出。
 *
 * <h2>格式串用 {@code %s} / {@code %d}(不是 slf4j 的 {@code {}})</h2>
 * <p>本模组所有日志都按 {@link String#format} 的写法传参(作者习惯,约 50 处调用),
 * 而 slf4j 的 {@code info(String, Object...)} 只认 {@code {}} —— 直接透传的话
 * {@code %s} 会原样打进日志、参数全丢,排查时看不到任何实际值。
 * 这里统一先 {@code String.format} 再交给 slf4j;万一某个格式串里混了裸 {@code %},
 * 格式化会抛异常,那就退回"格式串 + 参数原样",绝不因为记日志失败而影响游戏。</p>
 */
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
            LOGGER.info(render(format, args));
        }
    }

    /**
     * <b>不受 debugLog 门控</b>的日志:只给"必须让用户看见"的诊断用(自检 / 注入目标核对),
     * 调用方自己负责节流,避免刷屏。
     */
    public static void infoAlways(String format, Object... args) {
        try {
            LOGGER.info(render(format, args));
        }
        catch (RuntimeException | LinkageError ignored) {
            // 记日志失败绝不能影响游戏
        }
    }

    public static void warn(String format, Object... args) {
        try {
            LOGGER.warn(render(format, args));
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    /** {@code %s} 风格格式化 + 失败兜底。 */
    private static String render(String format, Object... args) {
        if (format == null) {
            return "null";
        }
        if (args == null || args.length == 0) {
            return format;
        }
        try {
            return String.format(normalize(format), args);
        }
        catch (RuntimeException e) {
            // 格式串里混了裸 % 之类:退回原文 + 参数,至少信息不丢
            return normalize(format) + " " + Arrays.toString(args);
        }
    }

    /**
     * 兼容 slf4j 风格的 {@code {}} 占位符。
     *
     * <p>项目里两种写法混用:多数调用点按 {@link String#format} 用 {@code %s},
     * 但也有几十处按 slf4j 习惯写成 {@code {}}。{@code String.format} 不认识 {@code {}},
     * 结果就是日志里原样出现 {@code 注入目标存在:{}#{}} 而参数全丢。</p>
     *
     * <p>这里统一把 {@code {}} 换成 {@code %s};已经全是 {@code %s} 的格式串原样返回,
     * 混用的情况也能正确处理(按出现顺序消耗参数)。</p>
     */
    private static String normalize(String format) {
        if (format.indexOf("{}") < 0) {
            return format;
        }
        return format.replace("{}", "%s");
    }
}
