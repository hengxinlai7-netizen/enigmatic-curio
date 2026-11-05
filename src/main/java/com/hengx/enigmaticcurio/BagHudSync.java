package com.hengx.enigmaticcurio;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 把"古旧书袋里会提供加成的物品 + 加成"送到**客户端 tooltip** 上。
 *
 * <h2>根因(为什么之前完全不显示)</h2>
 * 书袋内容在**服务端 capability** {@code AntiqueBagCapability.INVENTORY} 里(反编译确认:它只有
 * {@code writeTag/readTag},<b>没有任何网络同步</b>),石英花当前属性更是只在服务端的
 * {@code ArtificialFlower.PLAYER_ATTRIBUTE_MAP} 里。而 tooltip 是纯客户端渲染 —— 客户端手上
 * 根本没有这两份数据,所以"照搬一个 BagHudEffects 类"本身是**不够**的,必须有一步把结果送到客户端。
 *
 * <h2>为什么不用网络包</h2>
 * 本模组没有自己的网络通道,也不为此新建一条。这里换成**把结果写进书袋物品自己的 NBT**
 * ({@value BagHudEffects#NBT_KEY}) —— 物品 NBT 本来就会被原版随容器/背包同步给客户端,
 * 等于白拿一条现成的同步通道,不新增任何通道、也不动扩展的数据结构。
 *
 * <h2>只在变化时写</h2>
 * 每 20 tick 做一次:算条目 → {@link BagHudEffects#signature}(物品 + 效果 + 数值 + 生效状态)
 * → 与上一次的签名比较;**相同就什么都不做**(不写 NBT、不触发同步)。
 * 书袋进/出、书增删、石英花属性变化、前置条件(七咒/义人)变化都会让签名变化 → 恰好写一次。
 * 石英花的"剩余 tick"故意不进签名(见 {@link BagHudEffects#signature}),否则每秒都会写一次。
 */
public final class BagHudSync {

    /** 每个玩家上一次已同步的签名(只在内存里,重登自然会重写一次)。 */
    private static final Map<UUID, String> LAST_SIGNATURE = new HashMap<>();

    private static Item bagItem;

    private BagHudSync() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        try {
            if (!CurioConfig.BAG_TOOLTIP_ENABLED.get()) {
                return;
            }
            if (event.getServer() == null || event.getServer().getTickCount() % 20 != 0) {
                return;
            }
            for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
                tickUpdate(player);
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略:书袋提示失败绝不能影响游戏
        }
    }

    /** 单个玩家的一次"检查并写入"。 */
    public static boolean tickUpdate(ServerPlayer player) {
        try {
            if (player == null) {
                return false;
            }
            // 快速跳过:身上没有书袋就完全不必算(省掉每秒 9 次反射查袋)
            if (!hasBag(player)) {
                return false;
            }
            List<BagHudEffects.Entry> entries = BagHudEffects.collect(player);
            String signature = BagHudEffects.signature(entries);
            String previous = LAST_SIGNATURE.get(player.getUUID());
            if (signature.equals(previous)) {
                return false;
            }
            LAST_SIGNATURE.put(player.getUUID(), signature);
            boolean wrote = writeToBags(player, entries);
            if (wrote && player.inventoryMenu != null) {
                // 让改动后的 NBT 立刻同步给客户端(tooltip 马上就能看到)
                player.inventoryMenu.broadcastChanges();
            }
            DebugLog.info("[书袋] 同步 {} 条:{}", entries.size(), signature);
            return wrote;
        }
        catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** 把条目写进玩家身上(背包 / 副手)所有古旧书袋的 NBT。 */
    private static boolean writeToBags(ServerPlayer player, List<BagHudEffects.Entry> entries) {
        boolean any = false;
        try {
            for (ItemStack stack : player.getInventory().items) {
                if (isBag(stack)) {
                    any |= BagHudEffects.store(stack, entries);
                }
            }
            for (ItemStack stack : player.getInventory().offhand) {
                if (isBag(stack)) {
                    any |= BagHudEffects.store(stack, entries);
                }
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
        return any;
    }

    /** 玩家背包/副手里有没有古旧书袋。 */
    private static boolean hasBag(ServerPlayer player) {
        try {
            for (ItemStack stack : player.getInventory().items) {
                if (isBag(stack)) {
                    return true;
                }
            }
            for (ItemStack stack : player.getInventory().offhand) {
                if (isBag(stack)) {
                    return true;
                }
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
        return false;
    }

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

    /** 清空签名缓存(自检/重登用)。 */
    public static void resetCache() {
        LAST_SIGNATURE.clear();
    }
}
