package com.hengx.enigmaticcurio;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.event.entity.player.PlayerXpEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 神秘遗物扩展:无知诅咒卷轴({@code enigmaticaddons:cursed_xp_scroll})—— 防止经验溢出报废。
 *
 * <h2>根因(为什么之前那版无效)</h2>
 * 原来只挂了 {@link LivingExperienceDropEvent}(<b>生物掉落</b>经验),而溢出的真实来源是
 * <b>玩家自己直接获得经验</b>:扩展的卷轴在"取回"时走
 * {@code Player#giveExperiencePoints(int)} / 附魔、烧炼等也直接加 {@code totalExperience},
 * 根本不经过掉落事件。所以那条拦截从来没被调用过。
 *
 * <h2>现在的三层(参照主模组 {@code ExperienceOverflowGuard},行为一致)</h2>
 * <ol>
 *   <li><b>{@link PlayerXpEvent.XpChange}</b>(主):每一次玩家获得经验都会经过这里,把单次数值
 *       夹到配置上限 {@code xpScrollCapValue},并保证 {@code totalExperience} 不会越过绝对上限
 *       {@code Integer.MAX_VALUE - 1}。这是"提前夹住",不会先溢出再修;</li>
 *   <li><b>每 20 tick 的兜底扫描</b>({@link #onTick}):扫一次玩家总经验/等级与身上(背包/副手/Curios)卷轴的
 *       {@code XPStored},已经溢成负数的直接拉回上限 —— 对应主模组原来的
 *       {@code ExperienceOverflowGuard#onTick},用来救"装上本模组之前就已经溢出"的存档;</li>
 *   <li><b>{@link LivingExperienceDropEvent}</b>:保留旧实现作为兜底(掉落事件确实也是经验来源之一)。</li>
 * </ol>
 * 卷轴自身 {@code XPStored} 的上限取 {@code 1_000_000_000}(与主模组一致的"留安全余量"做法):
 * 卷轴自己还会继续计数,夹在 int 上限更容易被下一次写入顶爆。
 */
public final class XpScrollCapHandler {

    private static final String SCROLL_ID = "enigmaticaddons:cursed_xp_scroll";

    /** 经验绝对值上限。 */
    private static final int CAP = Integer.MAX_VALUE - 1;

    /** 卷轴自身 {@code XPStored} 的上限(留出安全余量)。 */
    private static final int SCROLL_CAP = 1_000_000_000;

    /** 卷轴存经验的 NBT 键(反编译 {@code CursedXPScroll} 确认)。 */
    private static final String NBT_KEY = "XPStored";

    private static Item scroll;

    private XpScrollCapHandler() {
    }

    // ------------------------------------------------------------------ ① 主拦截:XpChange

    /**
     * 玩家获得经验的必经点。只夹"正向"数值,扣经验(负数)一律不动。
     *
     * <p>条件与旧实现一致:卷轴装在 Curios 槽里,或(作为兼容)带在背包/副手上。</p>
     */
    @SubscribeEvent
    public static void onXpChange(PlayerXpEvent.XpChange event) {
        try {
            if (!CurioConfig.XP_SCROLL_CAP.get()) {
                return;
            }
            Player player = event.getEntity();
            if (player == null || player.level().isClientSide) {
                return;
            }
            int amount = event.getAmount();
            if (amount <= 0) {
                return;   // 扣经验/无变化,与本功能无关
            }
            if (!carrying(player)) {
                return;
            }
            int cap = CurioConfig.XP_SCROLL_CAP_VALUE.get();
            int clamped = Math.min(amount, cap);
            // 再按"总量不许越过绝对上限"收紧,避免总经验溢出成负数。
            long room = (long) CAP - (long) Math.max(player.totalExperience, 0);
            if (room < clamped) {
                clamped = (int) Math.max(room, 0L);
            }
            if (clamped != amount) {
                event.setAmount(clamped);
                DebugLog.info("[卷轴] 本次经验 {} -> {}(单次上限 {} / 总量上限 {})",
                    amount, clamped, cap, CAP);
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------ ② 兜底:每 20 tick 夹一次

    @SubscribeEvent
    public static void onTick(LivingEvent.LivingTickEvent event) {
        try {
            if (!CurioConfig.XP_SCROLL_CAP.get()) {
                return;
            }
            if (!(event.getEntity() instanceof Player player) || player.level().isClientSide) {
                return;
            }
            if (player.tickCount % 20 != 0) {
                return;
            }
            if (player.totalExperience < 0) {
                player.totalExperience = 0;
            }
            else if (player.totalExperience > CAP) {
                player.totalExperience = CAP;
            }
            if (player.experienceLevel < 0) {
                player.experienceLevel = 0;
                player.experienceProgress = 0.0F;
            }
            else if (player.experienceLevel > CAP) {
                player.experienceLevel = CAP;
            }
            clampScrolls(player);
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    /** 扫 Curios 槽 / 背包 / 副手里的卷轴,把已经溢出的 {@code XPStored} 拉回安全上限。 */
    private static void clampScrolls(Player player) {
        // 用反射扫 Curios 饰品槽(编译期不依赖 Curios 泛型签名,缺 Curios 直接跳过)
        try {
            Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi");
            Object inv = api.getMethod("getCuriosInventory", net.minecraft.world.entity.LivingEntity.class)
                .invoke(null, player);
            if (inv instanceof java.util.Optional<?> opt && opt.isPresent()) {
                Object handler = opt.get();
                Object curios = handler.getClass().getMethod("getCurios").invoke(handler);
                if (curios instanceof java.util.Map<?, ?> map) {
                    for (Object value : map.values()) {
                        clampCurioStacks(value);
                    }
                }
            }
        }
        catch (Throwable ignored) {
            // 没有 Curios 就跳过
        }
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            clampScroll(player.getInventory().getItem(i));
        }
        clampScroll(player.getOffhandItem());
    }

    private static void clampCurioStacks(Object stacks) {
        try {
            Object stackHandler = stacks.getClass().getMethod("getStacksHandler").invoke(stacks);
            Object inv = stackHandler.getClass().getMethod("getStacks").invoke(stackHandler);
            int slots = (int) inv.getClass().getMethod("getSlots").invoke(inv);
            for (int i = 0; i < slots; i++) {
                clampScroll((ItemStack) inv.getClass().getMethod("getStackInSlot", int.class).invoke(inv, i));
            }
        }
        catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void clampScroll(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty() || !isScroll(stack)) {
                return;
            }
            CompoundTag tag = stack.getTag();
            if (tag == null || !tag.contains(NBT_KEY)) {
                return;
            }
            int stored = tag.getInt(NBT_KEY);
            if (stored > SCROLL_CAP) {
                tag.putInt(NBT_KEY, SCROLL_CAP);
            }
            else if (stored < 0) {
                tag.putInt(NBT_KEY, SCROLL_CAP);   // 已溢出 → 拉回安全上限
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------ ③ 兜底:旧的掉落事件拦截

    @SubscribeEvent
    public static void onXpDrop(LivingExperienceDropEvent event) {
        try {
            if (!CurioConfig.XP_SCROLL_CAP.get()) {
                return;
            }
            Player player = event.getAttackingPlayer();
            if (player == null) {
                return;
            }
            if (!carrying(player)) {
                return;
            }
            int cap = CurioConfig.XP_SCROLL_CAP_VALUE.get();
            if (event.getDroppedExperience() > cap) {
                event.setDroppedExperience(cap);
                DebugLog.info("[卷轴] 掉落经验夹到上限 {}", cap);
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    // ------------------------------------------------------------------ 公共判定

    /** 卷轴物品(取不到时为 null)。 */
    private static Item scrollItem() {
        if (scroll == null) {
            scroll = Superposition.item(SCROLL_ID);
        }
        return scroll;
    }

    private static boolean isScroll(ItemStack stack) {
        Item item = scrollItem();
        return item != null && stack != null && !stack.isEmpty() && stack.is(item);
    }

    /** 玩家身上(饰品槽 / 背包 / 副手)是否带着卷轴。 */
    public static boolean carrying(Player player) {
        try {
            if (player == null) {
                return false;
            }
            Item item = scrollItem();
            if (item == null) {
                return false;
            }
            if (Superposition.hasCurio(player, item)) {
                return true;
            }
            for (ItemStack stack : player.getInventory().items) {
                if (isScroll(stack)) {
                    return true;
                }
            }
            return isScroll(player.getOffhandItem());
        }
        catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }
}
