package com.hengx.enigmaticcurio;

import java.util.List;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * 客户端(以及双端共用的)「书袋效果」缓存 —— tooltip 的第二条数据来源。
 *
 * <h2>为什么要两条来源</h2>
 * <ol>
 *   <li><b>物品 NBT</b>({@link BagHudEffects#NBT_KEY}):服务端写进书袋物品,靠原版容器同步带过来。
 *       这条通道会"随容器状态"走 —— 一旦那一次同步恰好被丢掉(例如登录/切维度那几 tick,客户端
 *       的容器菜单还不是 id 0,原版 {@code ClientboundContainerSetSlotPacket} 会被直接忽略),
 *       而服务端又认为"内容没变,不用再写",客户端就会**永远**停在「等待服务器同步」;</li>
 *   <li><b>本模组自己的 S2C 包</b>({@link CurioNetwork.BagEffectsPacket}):内容变化时推一次,
 *       客户端没有数据时还能用 {@link CurioNetwork.BagRequestPacket} 主动要一次。
 *       它不依赖任何容器状态,所以不受上面那个坑影响。</li>
 * </ol>
 *
 * <p>本类只做纯数据(静态表 + 毫秒时间戳),不引用任何 {@code net.minecraft.client} 类型,
 * 所以它在专用服务端被类加载也是安全的;"要不要发包"这一步用 {@link FMLEnvironment#dist} 挡住。</p>
 */
public final class BagHudClient {

    /** 服务端推来的最近一份条目;{@code null} = 从没收到过包。 */
    private static volatile List<BagHudEffects.Entry> cache;

    /** 最近一次收到的时刻(毫秒),诊断用。 */
    private static volatile long lastAcceptMillis;

    /** 最后一次"向服务端要数据"的时刻(毫秒),用于节流。 */
    private static long lastRequestMillis;

    /** 客户端第一次发现"这件书袋没有任何数据"的时刻(毫秒);收到数据后清零。 */
    private static long noDataSinceMillis;

    /** 诊断日志节流。 */
    private static long lastNoteMillis;
    private static String lastNote = "";

    private BagHudClient() {
    }

    /** 收到服务端推来的条目(包处理线程/工作线程都可能在跑,只赋值 volatile)。 */
    public static void accept(List<BagHudEffects.Entry> entries) {
        cache = List.copyOf(entries);
        lastAcceptMillis = System.currentTimeMillis();
        noDataSinceMillis = 0L;
    }

    /** 服务端推来的条目;{@code null} = 从没收到过。 */
    public static List<BagHudEffects.Entry> cache() {
        return cache;
    }

    /** 退出服务器 / 换端时清空,免得把上一个服务器的数据带到下一个。 */
    public static void reset() {
        cache = null;
        lastAcceptMillis = 0L;
        noDataSinceMillis = 0L;
        lastRequestMillis = 0L;
    }

    /**
     * 客户端发现"这件书袋既没有 NBT 缓存、也没收到过包"时调用:
     * 记下起点、并(最多每秒一次)向服务端要一次数据。
     *
     * <p>服务端每 20 tick 才扫一次,所以给 {@value #NO_DATA_GRACE_MILLIS} 毫秒的宽限;
     * 超过宽限还没有数据,tooltip 就从「等待服务器同步…」改成「(服务端未提供数据)」,
     * 明确区分"还没到时候"和"真的没拿到"。</p>
     */
    public static void requestIfStale() {
        try {
            long now = System.currentTimeMillis();
            if (noDataSinceMillis == 0L) {
                noDataSinceMillis = now;
            }
            if (FMLEnvironment.dist != Dist.CLIENT) {
                return;
            }
            if (now - lastRequestMillis < REQUEST_INTERVAL_MILLIS) {
                return;
            }
            lastRequestMillis = now;
            CurioNetwork.CHANNEL.sendToServer(new CurioNetwork.BagRequestPacket());
        }
        catch (RuntimeException | LinkageError ignored) {
            // 请求失败不影响渲染
        }
    }

    /** 已经过了宽限期还是没有数据 → tooltip 显示「(服务端未提供数据)」。 */
    public static boolean serverProvidedNothing() {
        try {
            long since = noDataSinceMillis;
            return cache == null && since != 0L
                && System.currentTimeMillis() - since >= NO_DATA_GRACE_MILLIS;
        }
        catch (RuntimeException ignored) {
            return false;
        }
    }

    /** tooltip 数据来源的**不受 debugLog 门控**诊断(5 秒节流,内容相同不重复打)。 */
    public static void noteTooltip(String source, int count) {
        try {
            if (FMLEnvironment.dist != Dist.CLIENT) {
                return;   // 服务端也可能触发 tooltip 事件,只记客户端这一侧
            }
            long now = System.currentTimeMillis();
            String text = source + "/" + count;
            if (text.equals(lastNote) && now - lastNoteMillis < 5000L) {
                return;
            }
            lastNote = text;
            lastNoteMillis = now;
            DebugLog.infoAlways("[书袋诊断] 客户端 tooltip 数据来源={} 条目数={} 包缓存={} NBT 通道见服务端日志",
                source, count, cache == null ? "从未收到" : (lastAcceptMillis == 0L ? "?" : "已收到"));
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    /** 宽限:20 tick 的一次扫描再留一点余量。 */
    private static final long NO_DATA_GRACE_MILLIS = 1000L;

    /** 主动要数据的节流间隔。 */
    private static final long REQUEST_INTERVAL_MILLIS = 1000L;
}
