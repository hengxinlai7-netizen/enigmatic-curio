package com.hengx.enigmaticcurio;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.items.SlotItemHandler;

/**
 * 「Ctrl + 左键」开关的**服务端实现**(复苏之叶 / 万钧之护 / 血战沙场之证 / 书籍 四件共用一套)。
 *
 * <h2>触发链路(为什么必须是"客户端识别 + 网络包")</h2>
 * <ol>
 *   <li>客户端({@code client.ToggleKeyHandler})在<b>容器界面里 Ctrl+左键点某格</b>,或
 *       <b>世界里 Ctrl+左键(手上拿着该物品)</b> 时,取消这次点击/攻击,发一条
 *       {@link CurioNetwork.SlotTogglePacket} / {@link CurioNetwork.HandTogglePacket};</li>
 *   <li>服务端收到后**完全重新校验**(见 {@link #handleSlotRequest} / {@link #handleHandRequest}),
 *       确认那一格/那只手里确实是这四件之一、配置允许、并且不是刷包;</li>
 *   <li>服务端把新状态写进**物品自己的 NBT**,再 {@code broadcastChanges()} 把新 NBT 同步回客户端
 *       (客户端 tooltip 因此立刻变成新状态)。</li>
 * </ol>
 *
 * <h2>为什么不再用「潜行 + 右键」</h2>
 * <p>潜行 + 右键会被 <b>Curios 抢走</b>:戴着/拿着饰品潜行右键时,Curios 先把它当成"佩戴/卸下"处理,
 * 玩家点十次有十次是把饰品戴上,开关根本轮不到 —— 所以这个方案整体作废。
 * Ctrl + 左键不会被 Curios、也不会被原版容器逻辑抢(我们在客户端就把这次点击取消了)。</p>
 *
 * <h2>状态约定(两套,别混)</h2>
 * <ul>
 *   <li>复苏之叶 / 万钧之护 / 血战沙场之证:<b>键不存在 = 开(默认)</b>,写 true = 玩家主动关掉;</li>
 *   <li>书籍:{@code enigmaticcurio:bookLocked} 存在 = 已锁定(默认不锁)。</li>
 * </ul>
 * 具体读写在各自 handler 里,本类只通过 {@link Target} 转调,不重复实现。
 */
public final class ItemToggleHandler {

    /** 一个"支持 Ctrl+左键 开关"的物品。 */
    public interface Target {

        /** 这叠物品是不是我管的。 */
        boolean matches(ItemStack stack);

        /** 当前是否处于"关闭 / 已锁定"状态。 */
        boolean isOff(ItemStack stack);

        /** 写入 / 清除"关闭 / 已锁定"状态。 */
        void setOff(ItemStack stack, boolean off);

        /** 配置是否允许玩家自己切(不允许时服务端也拒绝)。 */
        boolean toggleAllowed();

        /** 日志里用的名字(中文)。 */
        String displayName();

        /** 切换后给玩家看的提示文案 key(参数 = 切换后是否启用)。 */
        String messageKey(boolean enabledNow);

        /** 切换成功后的附加处理(默认什么都不做)。 */
        default void afterToggle(ServerPlayer player, ItemStack stack, boolean enabledNow) {
        }
    }

    /**
     * 两次切换之间的最小间隔(tick)。
     * <p>防的是"连点 / 刷包把 NBT 反复翻转"这类明显不合理的状态变化,不是防正常操作
     * (人手连点最快也就 5~6 次/秒,4 tick = 200ms 完全够用)。</p>
     */
    private static final int MIN_INTERVAL_TICKS = 4;

    /** 每个玩家上一次成功切换的 tick(只在内存里)。 */
    private static final Map<UUID, Integer> LAST_TOGGLE_TICK = new ConcurrentHashMap<>();

    private static List<Target> targets;

    private ItemToggleHandler() {
    }

    // ------------------------------------------------------------------ 目标集合

    /** 四件支持开关的物品(延迟构建,避免类初始化顺序问题)。 */
    public static synchronized List<Target> targets() {
        if (targets == null) {
            targets = List.of(
                new Target() {

                    @Override
                    public boolean matches(ItemStack stack) {
                        return RevivalLeafHandler.isLeaf(stack);
                    }

                    @Override
                    public boolean isOff(ItemStack stack) {
                        return RevivalLeafHandler.isDisabled(stack);
                    }

                    @Override
                    public void setOff(ItemStack stack, boolean off) {
                        RevivalLeafHandler.setFlightDisabled(stack, off);
                    }

                    @Override
                    public boolean toggleAllowed() {
                        return flag(CurioConfig.REVIVAL_LEAF_TOGGLE);
                    }

                    @Override
                    public String displayName() {
                        return "复苏之叶";
                    }

                    @Override
                    public String messageKey(boolean enabledNow) {
                        return enabledNow ? "msg.enigmaticcurio.flight.on" : "msg.enigmaticcurio.flight.off";
                    }
                },
                new Target() {

                    @Override
                    public boolean matches(ItemStack stack) {
                        return ScrollHandler.isScroll(stack);
                    }

                    @Override
                    public boolean isOff(ItemStack stack) {
                        return ScrollHandler.isThunderOff(stack);
                    }

                    @Override
                    public void setOff(ItemStack stack, boolean off) {
                        ScrollHandler.setThunderOff(stack, off);
                    }

                    @Override
                    public boolean toggleAllowed() {
                        return flag(CurioConfig.SCALE_ENABLED);
                    }

                    @Override
                    public String displayName() {
                        return "万钧之护";
                    }

                    @Override
                    public String messageKey(boolean enabledNow) {
                        return enabledNow ? "msg.enigmaticcurio.thunder.on" : "msg.enigmaticcurio.thunder.off";
                    }
                },
                new Target() {

                    @Override
                    public boolean matches(ItemStack stack) {
                        return BerserkCharmHandler.isCharm(stack);
                    }

                    @Override
                    public boolean isOff(ItemStack stack) {
                        return BerserkCharmHandler.isLockOff(stack);
                    }

                    @Override
                    public void setOff(ItemStack stack, boolean off) {
                        BerserkCharmHandler.setLockOff(stack, off);
                    }

                    @Override
                    public boolean toggleAllowed() {
                        return flag(CurioConfig.BLOODBATH_ENABLED);
                    }

                    @Override
                    public String displayName() {
                        return "血战沙场之证";
                    }

                    @Override
                    public String messageKey(boolean enabledNow) {
                        return enabledNow ? "msg.enigmaticcurio.berserk.on" : "msg.enigmaticcurio.berserk.off";
                    }

                    @Override
                    public void afterToggle(ServerPlayer player, ItemStack stack, boolean enabledNow) {
                        // 重新开启锁血:假血条拉回锁值,真血不低于锁值(否则一开就死)
                        if (enabledNow) {
                            BerserkCharmHandler.resetFakeHealth(player);
                        }
                    }
                },
                new Target() {

                    @Override
                    public boolean matches(ItemStack stack) {
                        return BookLockHandler.isTargetBook(stack);
                    }

                    @Override
                    public boolean isOff(ItemStack stack) {
                        return BookLockHandler.isLocked(stack);
                    }

                    @Override
                    public void setOff(ItemStack stack, boolean off) {
                        BookLockHandler.setLocked(stack, off);
                    }

                    @Override
                    public boolean toggleAllowed() {
                        return flag(CurioConfig.BOOKS_ENABLED);
                    }

                    @Override
                    public String displayName() {
                        return "书籍";
                    }

                    @Override
                    public String messageKey(boolean enabledNow) {
                        // 书籍语义反过来:enabledNow = 未锁定
                        return enabledNow ? "msg.enigmaticcurio.book.unlocked" : "msg.enigmaticcurio.book.locked";
                    }
                });
        }
        return targets;
    }

    /** 找一个能管这叠物品的 Target(没有则 null)。 */
    public static Target targetFor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        for (Target target : targets()) {
            if (target.matches(stack)) {
                return target;
            }
        }
        return null;
    }

    /** 客户端用:这叠物品现在能不能被 Ctrl+左键 切换。 */
    public static boolean canToggle(ItemStack stack) {
        Target target = targetFor(stack);
        return target != null && target.toggleAllowed();
    }

    /** 切换一次,返回"切换后是否启用"。 */
    public static boolean toggle(Target target, ItemStack stack) {
        boolean off = target.isOff(stack);
        target.setOff(stack, !off);
        return off;
    }

    // ------------------------------------------------------------------ 服务端:处理客户端请求

    /**
     * 服务端处理"切换当前菜单第 slotIndex 格"的请求。
     *
     * <p><b>一条都不信客户端</b>,逐条重新校验:</p>
     * <ol>
     *   <li>必须是玩家<b>当前真正打开的那个菜单</b>({@code menu.containerId == containerId}),
     *       而且这个菜单此刻仍然有效({@code stillValid},箱子类菜单会检查距离);</li>
     *   <li>槽位下标必须在范围内且是激活槽位;</li>
     *   <li>槽里的物品必须真的是四件之一(按注册 id / 类层次判定,不看客户端怎么说),配置允许切换;</li>
     *   <li>槽位确实属于玩家:玩家背包、饰品槽({@link SlotItemHandler})、或当前打开且已校验的容器;</li>
     *   <li>同一玩家 {@value #MIN_INTERVAL_TICKS} tick 内只接受一次(防连点刷包)。</li>
     * </ol>
     * 任何一条不满足都**静默丢弃**(不写 NBT、不提示),伪造请求无效。
     */
    public static void handleSlotRequest(ServerPlayer sender, int containerId, int slotIndex) {
        try {
            if (sender == null) {
                return;
            }
            AbstractContainerMenu menu = sender.containerMenu;
            if (menu == null || menu.containerId != containerId) {
                return;
            }
            if (!menu.stillValid(sender)) {
                return;
            }
            if (slotIndex < 0 || slotIndex >= menu.slots.size()) {
                return;
            }
            Slot slot = menu.getSlot(slotIndex);
            if (!slot.isActive()) {
                return;
            }
            if (!slotBelongsToPlayer(sender, menu, slot)) {
                return;
            }
            ItemStack stack = slot.getItem();
            Target target = targetFor(stack);
            if (target == null || !target.toggleAllowed()) {
                return;
            }
            if (!allowNow(sender)) {
                return;
            }
            apply(sender, menu, slot, target, stack);
        }
        catch (RuntimeException | LinkageError ignored) {
            // 开关失败绝不能影响游戏
        }
    }

    /**
     * 服务端处理"切换手上这一件"的请求(世界里 Ctrl + 左键)。
     *
     * <p>包里只有"哪只手";物品是服务端**自己**从玩家手上读的,客户端没有机会指定物品或状态。</p>
     */
    public static void handleHandRequest(ServerPlayer sender, int handOrdinal) {
        try {
            if (sender == null) {
                return;
            }
            InteractionHand[] hands = InteractionHand.values();
            if (handOrdinal < 0 || handOrdinal >= hands.length) {
                return;
            }
            ItemStack stack = sender.getItemInHand(hands[handOrdinal]);
            Target target = targetFor(stack);
            if (target == null || !target.toggleAllowed()) {
                return;
            }
            if (!allowNow(sender)) {
                return;
            }
            apply(sender, sender.containerMenu, null, target, stack);
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    /**
     * 槽位是否"确实是玩家的":背包 / 饰品槽 / 当前打开且已校验的容器。
     * <p>Curios 的饰品槽与古旧书袋这类能力槽位都是 {@link SlotItemHandler},
     * 它们的 {@code container} 只是占位用的空容器,物品真身在玩家身上 —— 所以直接放行。</p>
     */
    private static boolean slotBelongsToPlayer(ServerPlayer sender, AbstractContainerMenu menu, Slot slot) {
        if (slot.container == sender.getInventory()) {
            return true;
        }
        if (slot instanceof SlotItemHandler) {
            return true;
        }
        // 其它容器(箱子、书袋方块等):菜单本身已经通过 stillValid 的距离/归属校验
        return slot.container != null && menu.stillValid(sender);
    }

    /** 写入 NBT + 同步 + 提示。 */
    private static void apply(ServerPlayer sender, AbstractContainerMenu menu, Slot slot, Target target,
                              ItemStack stack) {
        boolean enabled = toggle(target, stack);
        if (slot != null) {
            slot.setChanged();
        }
        if (menu != null) {
            menu.broadcastChanges();
        }
        // 手上/背包里的那一份也要同步(饰品槽与背包是两个不同的容器视图)
        if (sender.containerMenu != sender.inventoryMenu) {
            sender.inventoryMenu.broadcastChanges();
        }
        target.afterToggle(sender, stack, enabled);
        sender.displayClientMessage(Component.translatable(target.messageKey(enabled))
            .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED), true);
        if (CurioConfig.debugLog()) {
            DebugLog.info("[物品开关] 玩家 {} 切换 {} -> {}({}),槽位 {}",
                sender.getGameProfile().getName(), target.displayName(),
                enabled ? "已启用" : "已关闭",
                target.messageKey(enabled), slot == null ? "手上" : String.valueOf(slot.index));
        }
    }

    /** 速率限制:同一玩家 {@value #MIN_INTERVAL_TICKS} tick 内只接受一次切换。 */
    private static boolean allowNow(ServerPlayer sender) {
        int now = sender.tickCount;
        Integer last = LAST_TOGGLE_TICK.get(sender.getUUID());
        if (last != null && now >= last && now - last < MIN_INTERVAL_TICKS) {
            return false;
        }
        LAST_TOGGLE_TICK.put(sender.getUUID(), now);
        return true;
    }

    /** 配置读取(配置还没加载时按"不允许"处理,绝不抛异常)。 */
    private static boolean flag(ForgeConfigSpec.BooleanValue value) {
        try {
            return value.get();
        }
        catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
