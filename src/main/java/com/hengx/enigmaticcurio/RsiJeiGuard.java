package com.hengx.enigmaticcurio;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Refined Storage Integration(RSI) → JEI 库存同步链路上的<b>空堆栈隔离</b>记账器。
 *
 * <p>被两个 mixin 调用(见 {@code enigmaticcurio.optional.mixins.json}):
 * {@code RsiJeiInventoryEntryMixin}(放行 {@code Entry} 构造)与
 * {@code RsiJeiInventoryPacketMixin}(在 {@code decode} 返回值里剔除坏条目)。
 * 计数与日志集中在这里,避免往被注入的目标类里塞静态字段(record / final 类都不适合)。</p>
 *
 * <p>日志是<b>节流</b>的(同类消息 10 秒最多一条),因为坏条目的构造每发生一次就会调用一次,
 * 而一个存储网络一次全量同步可能包含成百上千条条目。</p>
 */
public final class RsiJeiGuard {

    /** 同类日志的最小间隔(纳秒)。 */
    private static final long WARN_INTERVAL_NS = 10_000_000_000L;

    /** 累计被放行(本应抛异常)的空堆栈条目数。 */
    private static final AtomicLong TOTAL_EMPTY = new AtomicLong();

    /** 累计从同步包里剔除的坏条目数。 */
    private static final AtomicLong TOTAL_DROPPED = new AtomicLong();

    private static final AtomicLong LAST_EMPTY_WARN = new AtomicLong();
    private static final AtomicLong LAST_DROP_WARN = new AtomicLong();

    private RsiJeiGuard() {
    }

    /**
     * {@code JeiNetworkInventoryPacket.Entry#<init>} 的空堆栈检查被放行时调用。
     *
     * <p>不在这里直接丢弃,是因为构造发生在 {@code List#add} 之前、由 {@code decode} 的循环驱动;
     * 真正的剔除在 {@link #onDropped(int)} 那一步完成。</p>
     */
    public static void onEmptyEntryBypassed() {
        long total = TOTAL_EMPTY.incrementAndGet();
        if (!throttle(LAST_EMPTY_WARN)) {
            return;
        }
        DebugLog.warn("[RSI-JEI防御] 拦下一次空堆栈构造:客户端收到了一条 ItemStack 为空的 JEI 库存条目"
            + "(通常是服务端有、本客户端没装的神秘遗物系列物品),已放行构造等待剔除;累计 {} 条", total);
    }

    /** {@code JeiNetworkInventoryPacket#decode} 的返回值里剔除了 {@code dropped} 条坏条目。 */
    public static void onDropped(int dropped) {
        if (dropped <= 0) {
            return;
        }
        long total = TOTAL_DROPPED.addAndGet(dropped);
        if (!throttle(LAST_DROP_WARN)) {
            return;
        }
        DebugLog.warn("[RSI-JEI防御] 已从 JEI 库存同步包中剔除 {} 条空堆栈条目"
            + "(累计 {} 条);存储终端里这几个物品会显示不出来,其余条目与合成计划不受影响", dropped, total);
    }

    /** 累计放行数({@code /rsi} 或日志排查用)。 */
    public static long totalEmptyBypassed() {
        return TOTAL_EMPTY.get();
    }

    /** 累计剔除数({@code /rsi} 或日志排查用)。 */
    public static long totalDropped() {
        return TOTAL_DROPPED.get();
    }

    private static boolean throttle(AtomicLong lastWarn) {
        long now = System.nanoTime();
        long last = lastWarn.get();
        return now - last >= WARN_INTERVAL_NS && lastWarn.compareAndSet(last, now);
    }
}
