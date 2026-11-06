package com.hengx.enigmaticcurio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

/**
 * 把"古旧书袋里会提供加成的物品 + 加成"送到**客户端 tooltip** 上。
 *
 * <h2>数据为什么在服务端</h2>
 * 书袋内容不是物品 NBT,而是挂在**玩家实体**上的 capability
 * {@code AntiqueBagCapability.INVENTORY}(反编译确认:只有 {@code writeTag/readTag},
 * 没有任何网络同步;{@code AddonEventHandler#attachEntitiesCapabilities} 只对 {@code Player} 挂)。
 * 石英花当前属性更是只在服务端的 {@code ArtificialFlower.PLAYER_ATTRIBUTE_MAP}。
 * 而 tooltip 是纯客户端渲染 —— 所以必须由服务端算好再送过去。
 *
 * <h2>两条通道(1.2.0)</h2>
 * <ol>
 *   <li><b>物品 NBT</b>({@link BagHudEffects#NBT_KEY}):写进玩家身上**所有**位置的古旧书袋
 *       —— 背包(含快捷栏)、副手、盔甲格、当前打开的容器(箱子/末影箱/书袋界面)、
 *       **Curios 饰品槽**({@link CuriosCompat});写完<b>逐格显式</b>发
 *       {@link ClientboundContainerSetSlotPacket}(再跟一次 {@code broadcastChanges()} 兜底),
 *       这样不依赖"remoteSlots 恰好认为它变了"这种隐式条件;</li>
 *   <li><b>自己的 S2C 包</b>({@link CurioNetwork.BagEffectsPacket}):它不经过容器,
 *       登录 / 内容变化 / 客户端主动请求({@link CurioNetwork.BagRequestPacket})时各推一次,
 *       客户端存进 {@link BagHudClient},tooltip 在没有 NBT 时用它。</li>
 * </ol>
 *
 * <h2>1.1.3 修掉的"永远等待服务器同步"</h2>
 * 旧实现只在签名变化时写一次 NBT,然后指望那一次隐式容器同步一定到达客户端。
 * 登录/切维度那几 tick 客户端容器菜单还不是 id 0,原版 {@code ClientboundContainerSetSlotPacket}
 * 会被直接丢弃,而之后签名不再变化 ⇒ <b>再也不会重写</b>,客户端永久停在「等待服务器同步」。
 * 现在:每 20 tick 都比一次 NBT(不等就补写)、每 200 tick 强制重发一遍格子包、
 * 每 600 tick 补推一次 S2C 包,客户端还可以随时主动要 —— 任何一次丢失都会自动恢复。
 *
 * <h2>诊断</h2>
 * 关键分支都打**不受 debugLog 门控**的日志({@link DebugLog#infoAlways}),按 5 秒节流:
 * 找到几个书袋、分别在哪个容器、capability 有没有取到 / 袋内几件、算出几条效果、
 * 有没有写 NBT / 有没有发同步包。用户复现时把日志发回来就能定位卡在哪一步。
 */
public final class BagHudSync {

    /** 每个玩家上一次已通知客户端的签名(只在内存里,重登会清掉并重发)。 */
    private static final Map<UUID, String> LAST_SIGNATURE = new HashMap<>();

    /** 诊断节流:每人上一次打印的内容与时刻。 */
    private static final Map<UUID, Diag> LAST_DIAG = new HashMap<>();

    /** 客户端主动请求的节流:每人上一次处理请求的毫秒时刻(防刷包)。 */
    private static final Map<UUID, Long> LAST_REQUEST = new HashMap<>();

    /** 同一个客户端的请求最多 500 毫秒处理一次。 */
    private static final long REQUEST_THROTTLE_MILLIS = 500L;

    /** 多久扫一次(20 tick = 1 秒)。 */
    private static final int TICK_INTERVAL = 20;

    /** 每 200 tick(10 秒)强制重发一遍格子包,自愈任何一次丢包。 */
    private static final int FORCE_RESYNC_TICKS = 200;

    /** 每 600 tick(30 秒)补推一次 S2C 包(没有变化也推,成本可忽略)。 */
    private static final int PACKET_HEARTBEAT_TICKS = 600;

    /** 诊断日志节流(毫秒)。 */
    private static final long DIAG_THROTTLE_MILLIS = 5000L;

    private static Item bagItem;

    private BagHudSync() {
    }

    // ------------------------------------------------------------------ 事件

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        try {
            if (event.getServer() == null) {
                return;
            }
            int tick = event.getServer().getTickCount();
            if (tick % TICK_INTERVAL != 0) {
                return;
            }
            if (!enabled()) {
                return;
            }
            boolean heartbeat = tick % PACKET_HEARTBEAT_TICKS == 0;
            boolean force = tick % FORCE_RESYNC_TICKS == 0;
            for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
                try {
                    tickUpdate(player, heartbeat, force, "周期");
                }
                catch (RuntimeException | LinkageError e) {
                    diagAlways(player, "异常:" + e);
                }
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略:书袋提示失败绝不能影响游戏
        }
    }

    /** 登录:立刻算一次并推包(此时客户端可能还没准备好收格子包,但 S2C 包与后续自愈能兜住)。 */
    @SubscribeEvent
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        try {
            if (event.getEntity() instanceof ServerPlayer player) {
                handleRequest(player, "登录");
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    /** 换维度:新连接,格子包要重发一遍。 */
    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        try {
            if (event.getEntity() instanceof ServerPlayer player) {
                handleRequest(player, "换维度");
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    /** 重生:新 ServerPlayer(同 UUID),清掉签名让下一次 tick 重写重发。 */
    @SubscribeEvent
    public static void onClone(PlayerEvent.Clone event) {
        try {
            forget(event.getEntity() == null ? null : event.getEntity().getUUID());
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    /** 退出:清缓存(同一进程里重新进服要重发一次)。 */
    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        try {
            forget(event.getEntity() == null ? null : event.getEntity().getUUID());
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------ 一次检查 / 一次请求

    /** 兼容旧调用:单个玩家的一次"检查并同步"(不带心跳/强制重发标记)。 */
    public static boolean tickUpdate(ServerPlayer player) {
        return tickUpdate(player, false, false, "手动");
    }

    /**
     * 单个玩家的一次"检查并同步"。
     *
     * @param heartbeat 即使内容没变也推一次 S2C 包
     * @param force     即使 NBT 没变也重发一遍格子同步包
     * @param trigger   诊断日志里的触发原因
     * @return true = 内容变了(签名的确不同)
     */
    public static boolean tickUpdate(ServerPlayer player, boolean heartbeat, boolean force, String trigger) {
        if (player == null) {
            return false;
        }
        List<BagHudEffects.Entry> entries = BagHudEffects.collect(player);
        String signature = BagHudEffects.signature(entries);
        String previous = LAST_SIGNATURE.get(player.getUUID());
        boolean changed = !signature.equals(previous);

        Report report = writeToBags(player, entries);
        syncSlots(player, report, force);

        boolean packetSent = false;
        if (changed || heartbeat) {
            LAST_SIGNATURE.put(player.getUUID(), signature);
            packetSent = sendPacket(player, entries);
        }
        // 有书袋 或 内容变化 或 出问题时要看日志;除此之外(没书袋且一直没变化)静默
        if (!report.where.isEmpty() || changed || packetSent) {
            diag(player, trigger, entries, report, packetSent);
        }
        return changed;
    }

    /** 客户端主动要数据:现算一份、写 NBT、强制重发格子、再推一次包(不看签名)。 */
    public static void handleRequest(ServerPlayer player) {
        handleRequest(player, "客户端请求");
    }

    public static void handleRequest(ServerPlayer player, String trigger) {
        try {
            if (player == null || !enabled()) {
                return;
            }
            // 客户端可以"没有数据就请求一次",所以要防刷:同一个人的请求 500ms 内只处理一次
            if ("客户端请求".equals(trigger)) {
                long now = System.currentTimeMillis();
                Long last = LAST_REQUEST.get(player.getUUID());
                if (last != null && now - last < REQUEST_THROTTLE_MILLIS) {
                    return;
                }
                LAST_REQUEST.put(player.getUUID(), now);
            }
            List<BagHudEffects.Entry> entries = BagHudEffects.collect(player);
            LAST_SIGNATURE.put(player.getUUID(), BagHudEffects.signature(entries));
            Report report = writeToBags(player, entries);
            syncSlots(player, report, true);
            boolean packetSent = sendPacket(player, entries);
            diag(player, trigger, entries, report, packetSent);
        }
        catch (RuntimeException | LinkageError e) {
            diagAlways(player, "请求处理异常:" + e);
        }
    }

    public static void forget(UUID id) {
        if (id == null) {
            return;
        }
        LAST_SIGNATURE.remove(id);
        LAST_DIAG.remove(id);
        LAST_REQUEST.remove(id);
    }

    /** 清空签名缓存(自检/重登用)。 */
    public static void resetCache() {
        LAST_SIGNATURE.clear();
        LAST_DIAG.clear();
        LAST_REQUEST.clear();
    }

    // ------------------------------------------------------------------ 写 NBT

    /** 这一次扫描里发现的书袋位置。 */
    private static final class Report {

        /** 人类可读的位置列表(诊断用),如 {@code 背包#9} / {@code 副手} / {@code curios:ring#0}。 */
        final List<String> where = new ArrayList<>();

        /** 命中 {@code inventoryMenu} 的槽位号(能直接发原版格子包)。 */
        final List<Integer> inventorySlots = new ArrayList<>();

        /** 命中的 Curios 槽位。 */
        final List<CuriosCompat.CurioSlot> curios = new ArrayList<>();

        /** 当前打开的别的容器里也有书袋(写完要 broadcastChanges)。 */
        boolean openMenuTouched;

        /** NBT 真的被改了(不是"已经一样")。 */
        boolean written;
    }

    /** 把条目写进玩家身上**所有**能找到的古旧书袋。 */
    private static Report writeToBags(ServerPlayer player, List<BagHudEffects.Entry> entries) {
        Report report = new Report();
        AbstractContainerMenu inventoryMenu = player.inventoryMenu;
        if (inventoryMenu != null) {
            // 这个菜单覆盖 背包(0-35) / 盔甲(36-39) / 副手(40),一次扫完最省事
            for (int i = 0; i < inventoryMenu.slots.size(); i++) {
                Slot slot = inventoryMenu.slots.get(i);
                ItemStack stack = slot.getItem();
                if (!isBag(stack)) {
                    continue;
                }
                report.written |= BagHudEffects.store(stack, entries);
                report.inventorySlots.add(i);
                report.where.add(inventorySlotLabel(slot));
            }
        }
        AbstractContainerMenu openMenu = player.containerMenu;
        if (openMenu != null && openMenu != inventoryMenu) {
            for (int i = 0; i < openMenu.slots.size(); i++) {
                ItemStack stack = openMenu.slots.get(i).getItem();
                if (!isBag(stack)) {
                    continue;
                }
                report.written |= BagHudEffects.store(stack, entries);
                report.openMenuTouched = true;
                report.where.add("容器#" + i);
            }
        }
        // Curios 饰品槽(书袋本身不是 curio,但别的包里/以后的版本可能加进来;有就一起写)
        for (CuriosCompat.CurioSlot curio : CuriosCompat.equipped(player)) {
            if (!isBag(curio.stack())) {
                continue;
            }
            report.written |= BagHudEffects.store(curio.stack(), entries);
            report.curios.add(curio);
            report.where.add(curio.describe());
        }
        return report;
    }

    /**
     * 把改过的格子**显式**同步给客户端。
     *
     * <p>原版 {@code broadcastChanges()} 靠 {@code remoteSlots} 比较,理论上够用;这里仍然逐格
     * 直接发 {@link ClientboundContainerSetSlotPacket} —— 因为"那一 tick 客户端容器还不是 id 0"
     * 时包会被客户端丢掉,而我们又要保证每个周期都有一次机会(配合 {@code force})。</p>
     */
    private static void syncSlots(ServerPlayer player, Report report, boolean force) {
        if (player.connection == null) {
            return;
        }
        if (!report.written && !force) {
            return;
        }
        AbstractContainerMenu inventoryMenu = player.inventoryMenu;
        if (inventoryMenu != null) {
            for (int slotIndex : report.inventorySlots) {
                if (slotIndex < 0 || slotIndex >= inventoryMenu.slots.size()) {
                    continue;
                }
                ItemStack stack = inventoryMenu.slots.get(slotIndex).getItem();
                if (stack.isEmpty()) {
                    continue;
                }
                ItemStack copy = stack.copy();
                inventoryMenu.setRemoteSlot(slotIndex, copy);
                player.connection.send(new ClientboundContainerSetSlotPacket(
                    inventoryMenu.containerId, inventoryMenu.incrementStateId(), slotIndex, copy));
            }
            // 兜底:其余槽位交给原版自己比较(正常情况下这里是空操作)
            inventoryMenu.broadcastChanges();
        }
        if (report.openMenuTouched && player.containerMenu != null
            && player.containerMenu != inventoryMenu) {
            player.containerMenu.broadcastChanges();
        }
        // Curios:NBT 是原地改的,Curios 自己每 tick 比较 previousStack 会同步;
        // 能反射到它的 SPacketSyncStack 再显式推一次。
        for (CuriosCompat.CurioSlot curio : report.curios) {
            CuriosCompat.sync(player, curio);
        }
    }

    /** 背包菜单里的槽位 → 人类可读位置。 */
    private static String inventorySlotLabel(Slot slot) {
        try {
            if (slot.container instanceof net.minecraft.world.entity.player.Inventory) {
                int index = slot.getContainerSlot();
                if (index >= 0 && index <= 35) {
                    return "背包#" + index;
                }
                if (index >= 36 && index <= 39) {
                    return "盔甲#" + (39 - index);
                }
                if (index == 40) {
                    return "副手";
                }
                return "玩家物品栏#" + index;
            }
            return "菜单#" + slot.index;
        }
        catch (RuntimeException ignored) {
            return "槽位#?";
        }
    }

    private static boolean sendPacket(ServerPlayer player, List<BagHudEffects.Entry> entries) {
        try {
            if (player.connection == null) {
                return false;
            }
            CurioNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new CurioNetwork.BagEffectsPacket(BagHudEffects.toLines(entries)));
            return true;
        }
        catch (RuntimeException | LinkageError e) {
            diagAlways(player, "发包失败:" + e);
            return false;
        }
    }

    // ------------------------------------------------------------------ 诊断

    private record Diag(long millis, String text) {
    }

    /** 一条"不受 debugLog 门控"的诊断日志(5 秒节流,内容相同不重复刷)。 */
    private static void diag(ServerPlayer player, String trigger, List<BagHudEffects.Entry> entries,
        Report report, boolean packetSent) {
        try {
            BookBag.BagState state = BookBag.contents(player);
            StringBuilder sb = new StringBuilder();
            sb.append("触发=").append(trigger)
                .append(" 玩家=").append(player.getGameProfile().getName())
                .append(" 书袋=").append(report.where.size()).append(report.where)
                .append(" capability=").append(state.describe())
                .append(" 条目=").append(entries.size())
                .append(" 写入=").append(report.written ? "是" : "否(内容已一致)")
                .append(" 发包=").append(packetSent ? "是" : "否")
                .append(" 签名=").append(Integer.toHexString(BagHudEffects.signature(entries).hashCode()));
            if (!entries.isEmpty()) {
                sb.append(" 内容=").append(BagHudEffects.toLines(entries));
            }
            emit(player, sb.toString());
        }
        catch (RuntimeException | LinkageError ignored) {
            // 诊断失败不影响游戏
        }
    }

    private static void diagAlways(ServerPlayer player, String text) {
        try {
            String name = player == null ? "?" : player.getGameProfile().getName();
            emit(player, "触发=异常 玩家=" + name + " " + text);
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    private static void emit(ServerPlayer player, String text) {
        long now = System.currentTimeMillis();
        UUID id = player == null ? null : player.getUUID();
        Diag last = id == null ? null : LAST_DIAG.get(id);
        if (last != null && text.equals(last.text()) && now - last.millis() < DIAG_THROTTLE_MILLIS) {
            return;
        }
        if (id != null) {
            LAST_DIAG.put(id, new Diag(now, text));
        }
        DebugLog.infoAlways("[书袋诊断] {}", text);
    }

    // ------------------------------------------------------------------ 杂项

    /** 这叠东西是不是古旧书袋。 */
    public static boolean isBag(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) {
                return false;
            }
            if (bagItem == null) {
                bagItem = Superposition.item(BookBag.BAG_ID);
            }
            return bagItem != null && stack.is(bagItem);
        }
        catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static boolean enabled() {
        try {
            return CurioConfig.BAG_TOOLTIP_ENABLED.get();
        }
        catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
