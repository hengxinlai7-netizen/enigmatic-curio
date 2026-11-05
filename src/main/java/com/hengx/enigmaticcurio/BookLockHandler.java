package com.hengx.enigmaticcurio;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 神秘遗物:可自由锁定书籍,锁定后手持右键不再打开。
 *
 * <p>操作:</p>
 * <ul>
 *   <li><b>Ctrl + 右键</b>:切换锁定状态(会让这一次右键不生效);</li>
 *   <li>锁定后再右键:直接取消,不打开界面。</li>
 * </ul>
 *
 * <p>状态写在物品 NBT({@code enigmaticcurio:bookLocked}),对任何物品都适用,
 * 不限于神秘遗物的几本书 —— 这样行为一致、也不会误判 id。</p>
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

    public static boolean isLocked(ItemStack stack) {
        try {
            CompoundTag tag = stack.getTag();
            return tag != null && tag.getBoolean(NBT_LOCKED);
        }
        catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClick(PlayerInteractEvent.RightClickItem event) {
        try {
            if (!CurioConfig.BOOKS_ENABLED.get()) {
                return;
            }
            Player player = event.getEntity();
            ItemStack stack = event.getItemStack();
            if (stack.isEmpty()) {
                return;
            }
            // Ctrl 按下 ⇒ 切换锁定
            if (player.isShiftKeyDown() && player.isCrouching()) {
                // 潜行右键留给原版行为,不处理
            }
            boolean ctrl = ctrlDown(player);
            if (ctrl) {
                CompoundTag tag = stack.getOrCreateTag();
                boolean next = !tag.getBoolean(NBT_LOCKED);
                tag.putBoolean(NBT_LOCKED, next);
                if (player.level().isClientSide) {
                    player.sendSystemMessage(Component
                        .translatable(next ? "msg.enigmaticcurio.book.locked"
                                           : "msg.enigmaticcurio.book.unlocked")
                        .withStyle(next ? ChatFormatting.RED : ChatFormatting.GREEN));
                }
                event.setCanceled(true);
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

    /** 跨平台判断 Ctrl:客户端用 InputConstants,服务端拿不到就用潜行代替。 */
    private static boolean ctrlDown(Player player) {
        try {
            if (player.level().isClientSide) {
                long handle = net.minecraft.client.Minecraft.getInstance().getWindow().getWindow();
                boolean ctrl = com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                    handle, org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL)
                    || com.mojang.blaze3d.platform.InputConstants.isKeyDown(
                    handle, org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_CONTROL);
                return ctrl;
            }
        }
        catch (Throwable ignored) {
            // 服务端/无窗口
        }
        return false;
    }
}