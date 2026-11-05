package com.hengx.enigmaticcurio;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 神秘遗物(及其扩展)的「书籍」:可自由锁定,锁定后手持右键不再打开。
 *
 * <h2>操作</h2>
 * <ul>
 *   <li><b>Ctrl + 左键</b>:切换锁定状态(客户端 {@code client.ToggleKeyHandler} 识手势 →
 *       {@link CurioNetwork} 发包 → 服务端校验后由 {@link ItemToggleHandler} 写 NBT);</li>
 *   <li>锁定后再右键:直接取消,不打开界面。</li>
 * </ul>
 *
 * <h2>为什么不是「潜行 + 右键」</h2>
 * <p>潜行右键会被 <b>Curios 抢去佩戴饰品</b>(戴着饰品时潜行右键 = 直接戴上),
 * 玩家永远点不到开关,所以这个方案整体作废。Ctrl 的按键状态服务端读不到,
 * 所以改回主模组那套"客户端识 Ctrl + 左键 → 自定义网络包 → 服务端写 NBT"。</p>
 *
 * <p>状态写在物品 NBT({@code enigmaticcurio:bookLocked})上,换背包/掉落/重登都保留。</p>
 */
public final class BookLockHandler {

    private static final String NBT_LOCKED = "enigmaticcurio:bookLocked";

    /** Patchouli 的书基类与神秘遗物的「启示之证」,用于判断"这是不是一本书"。 */
    private static Class<?> patchouliBookClass;
    private static Class<?> acknowledgmentClass;
    private static boolean bookClassesLooked;

    private BookLockHandler() {
    }

    /**
     * 供描述(tooltip)使用:这件物品是不是本功能意义上的"书"
     * (神秘遗物/扩展的书,或 Patchouli 的原生书;已经带锁定标记的物品也算)。
     * 类层次判定走反射,缺 Patchouli / 缺神秘遗物时安全返回 false。
     */
    public static boolean isTargetBook(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) {
                return false;
            }
            if (isLocked(stack)) {
                return true;
            }
            Item item = stack.getItem();
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id == null) {
                return false;
            }
            String ns = id.getNamespace();
            if (!"enigmaticlegacy".equals(ns) && !"enigmaticaddons".equals(ns)) {
                return false;
            }
            lookupBookClasses();
            if (patchouliBookClass != null && patchouliBookClass.isAssignableFrom(item.getClass())) {
                return true;
            }
            return acknowledgmentClass != null && acknowledgmentClass.isAssignableFrom(item.getClass());
        }
        catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static void lookupBookClasses() {
        if (bookClassesLooked) {
            return;
        }
        bookClassesLooked = true;
        try {
            patchouliBookClass = Class.forName("vazkii.patchouli.common.item.ItemModBook");
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            patchouliBookClass = null;
        }
        try {
            acknowledgmentClass = Class.forName("com.aizistral.enigmaticlegacy.items.TheAcknowledgment");
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            acknowledgmentClass = null;
        }
    }

    /** 这本书是否已被锁定(键不存在 = 未锁定)。 */
    public static boolean isLocked(ItemStack stack) {
        try {
            CompoundTag tag = stack.getTag();
            return tag != null && tag.getBoolean(NBT_LOCKED);
        }
        catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** 写入/清除锁定标记(清除时若 NBT 空了就一并清掉,避免留下空 tag)。 */
    public static void setLocked(ItemStack stack, boolean locked) {
        try {
            if (stack == null || stack.isEmpty()) {
                return;
            }
            if (locked) {
                stack.getOrCreateTag().putBoolean(NBT_LOCKED, true);
                return;
            }
            CompoundTag tag = stack.getTag();
            if (tag == null) {
                return;
            }
            tag.remove(NBT_LOCKED);
            if (tag.isEmpty()) {
                stack.setTag(null);
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    /**
     * 已锁定的书:普通右键直接取消,不打开界面。
     *
     * <p>切换锁定**不在这里**,由 {@link ItemToggleHandler}(Ctrl + 左键 + 网络包)负责 ——
     * 右键只读不写。</p>
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClick(PlayerInteractEvent.RightClickItem event) {
        try {
            if (!CurioConfig.BOOKS_ENABLED.get()) {
                return;
            }
            Player player = event.getEntity();
            ItemStack stack = event.getItemStack();
            if (stack.isEmpty() || !isTargetBook(stack)) {
                return;
            }
            if (isLocked(stack)) {
                event.setCanceled(true);
                DebugLog.info("[书籍] 已锁定,阻止打开 {}", stack.getDisplayName().getString());
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }
}
