package com.hengx.enigmaticcurio;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 神化(Apotheosis)镶嵌配方 {@code SocketingRecipe#assemble} 越界防御的记账器。
 *
 * <p>被 {@code ApothSocketingRecipeMixin} 调用。日志节流 10 秒一条:
 * JEI 预览一个"任意底材 × 任意宝石"的通用镶嵌配方时,会为每个组合调一次 {@code assemble},
 * 一次注册能触发成百上千次。</p>
 */
public final class ApothSocketingGuard {

    private static final long WARN_INTERVAL_NS = 10_000_000_000L;

    /** 累计"追加"次数(插槽列表为空/未 padding 时把宝石放到末尾)。 */
    private static final AtomicLong TOTAL_APPENDED = new AtomicLong();

    /** 累计"丢弃"次数(索引既不是原位也不是末尾,没法安全放)。 */
    private static final AtomicLong TOTAL_DISCARDED = new AtomicLong();

    /** 累计异常次数。 */
    private static final AtomicLong TOTAL_ERRORS = new AtomicLong();

    private static final AtomicLong LAST_APPEND_WARN = new AtomicLong();
    private static final AtomicLong LAST_DISCARD_WARN = new AtomicLong();
    private static final AtomicLong LAST_ERROR_WARN = new AtomicLong();

    private ApothSocketingGuard() {
    }

    /** 原版代码想 {@code gems.set(0, gem)} 但列表长度是 0 —— 改成追加。 */
    public static void onAppended(int index) {
        long total = TOTAL_APPENDED.incrementAndGet();
        if (!throttle(LAST_APPEND_WARN)) {
            return;
        }
        DebugLog.warn("[神化镶嵌防御] 镶槽列表长度不足(set({}) 越界),已改为追加写入;"
            + "累计 {} 次。多见于 JEI 用空气底材预览 apotheosis:socketing", index, total);
    }

    /** 索引既不是原位也不是末尾(负索引/跳空),安全起见丢掉这颗宝石。 */
    public static void onDiscarded(int index, int size) {
        long total = TOTAL_DISCARDED.incrementAndGet();
        if (!throttle(LAST_DISCARD_WARN)) {
            return;
        }
        DebugLog.warn("[神化镶嵌防御] 镶槽索引 {} 超出 [0,{}],本次不写入宝石;累计 {} 次", index, size, total);
    }

    /** 兜底:改写逻辑自身出错,退化回"不动列表"。 */
    public static void onError(Throwable error) {
        long total = TOTAL_ERRORS.incrementAndGet();
        if (!throttle(LAST_ERROR_WARN)) {
            return;
        }
        DebugLog.warn("[神化镶嵌防御] 改写 List#set 失败({}),本次不写入宝石;累计 {} 次", error.toString(), total);
    }

    public static long totalAppended() {
        return TOTAL_APPENDED.get();
    }

    public static long totalDiscarded() {
        return TOTAL_DISCARDED.get();
    }

    public static long totalErrors() {
        return TOTAL_ERRORS.get();
    }

    private static boolean throttle(AtomicLong lastWarn) {
        long now = System.nanoTime();
        long last = lastWarn.get();
        return now - last >= WARN_INTERVAL_NS && lastWarn.compareAndSet(last, now);
    }
}
