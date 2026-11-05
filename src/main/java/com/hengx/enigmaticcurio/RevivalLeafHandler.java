package com.hengx.enigmaticcurio;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 复苏之叶的飞行开关。
 *
 * <p>扩展的 {@code RevivalLeaf#handleFlight} 在"附近有植物"时自己授予 {@code abilities.mayfly}。
 * 本类只负责:</p>
 * <ul>
 *   <li>Ctrl + 左键 切换开关(状态存在物品 NBT);</li>
 *   <li>开关关闭时,把那一次授予的飞行撤掉(不影响创造模式/其它模组的飞行)。</li>
 * </ul>
 */
public final class RevivalLeafHandler {

    public static final ResourceLocation LEAF_ID = new ResourceLocation("enigmaticaddons", "revival_leaf");
    public static final String NBT_KEY = "enigmaticcurio:flightDisabled";

    /** 记录"上一次调用前是否已经能飞",用于区分是不是这枚术石给的。 */
    private static final ThreadLocal<Boolean> HAD_FLIGHT = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final Map<UUID, Boolean> DISABLED = new ConcurrentHashMap<>();

    private RevivalLeafHandler() {
    }

    public static boolean isLeaf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && LEAF_ID.equals(id);
    }

    public static boolean isDisabled(ItemStack stack) {
        try {
            CompoundTag tag = stack.getTag();
            return tag != null && tag.getBoolean(NBT_KEY);
        }
        catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** 玩家身上的术石是否处于"关闭飞行"状态。 */
    public static boolean flightDisabled(Player player) {
        if (player == null) {
            return false;
        }
        try {
            Item leaf = ForgeRegistries.ITEMS.getValue(LEAF_ID);
            if (leaf == null) {
                return false;
            }
            Boolean cached = DISABLED.get(player.getUUID());
            if (cached != null) {
                return cached;
            }
            for (ItemStack s : player.getInventory().items) {
                if (isLeaf(s) && isDisabled(s)) {
                    return true;
                }
            }
            for (ItemStack s : player.getInventory().offhand) {
                if (isLeaf(s) && isDisabled(s)) {
                    return true;
                }
            }
            return false;
        }
        catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** Ctrl + 左键:切换开关(只对物品栏里点到的术石生效)。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClick(PlayerInteractEvent.LeftClickEmpty event) {
        try {
            if (!event.getEntity().isShiftKeyDown()) {
                return;
            }
            ItemStack stack = event.getEntity().getItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND);
            if (!isLeaf(stack)) {
                return;
            }
            CompoundTag tag = stack.getOrCreateTag();
            boolean next = !tag.getBoolean(NBT_KEY);
            tag.putBoolean(NBT_KEY, next);
            DISABLED.remove(event.getEntity().getUUID());
            if (event.getEntity().level().isClientSide) {
                event.getEntity().sendSystemMessage(Component
                    .translatable(next ? "msg.enigmaticcurio.flight.off" : "msg.enigmaticcurio.flight.on")
                    .withStyle(next ? ChatFormatting.RED : ChatFormatting.GREEN));
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    /** 供 mixin 使用:记录调用前状态。 */
    public static void recordFlight(Player player) {
        try {
            HAD_FLIGHT.set(player != null && player.getAbilities().mayfly);
        }
        catch (RuntimeException | LinkageError ignored) {
            HAD_FLIGHT.set(Boolean.FALSE);
        }
    }

    /** 清掉本次记录(不需要撤销时调用)。 */
    public static void clearPending() {
        HAD_FLIGHT.remove();
    }

    /** 供 mixin 使用:只撤销术石刚授予的那一次飞行。 */
    public static void revokeGrantedFlight(Player player) {
        try {
            boolean hadBefore = Boolean.TRUE.equals(HAD_FLIGHT.get());
            HAD_FLIGHT.remove();
            if (player == null || hadBefore) {
                return;
            }
            if (player.isCreative() || player.isSpectator()) {
                return;
            }
            if (player.getAbilities().mayfly) {
                player.getAbilities().mayfly = false;
                player.getAbilities().flying = false;
                player.onUpdateAbilities();
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }
}
