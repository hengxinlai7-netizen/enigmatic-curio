package com.hengx.enigmaticcurio;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 复苏之叶的飞行开关。
 *
 * <p>扩展的 {@code RevivalLeaf#handleFlight} 在"附近有植物"时自己授予 {@code abilities.mayfly}。
 * 本类只负责:</p>
 * <ul>
 *   <li>读取 / 写入开关状态(存在物品 NBT {@value #NBT_KEY},<b>键不存在 = 开</b>);</li>
 *   <li>开关关闭时,把那一次授予的飞行撤掉(不影响创造模式/其它模组的飞行);</li>
 *   <li>给 {@link ItemToggleHandler} 提供"这叠是不是复苏之叶 / 当前是否关闭"的判定。</li>
 * </ul>
 *
 * <h2>触发方式:Ctrl + 左键(客户端识手势 → 网络包 → 服务端写 NBT)</h2>
 * <p>以前的「潜行 + 右键」已作废:潜行右键会被 <b>Curios 抢去佩戴饰品</b>,开关根本点不到。
 * 现在由客户端 {@code client.ToggleKeyHandler} 捕获 Ctrl+左键 并发
 * {@link CurioNetwork.SlotTogglePacket} / {@link CurioNetwork.HandTogglePacket},
 * 服务端校验后写 NBT 并同步回客户端。</p>
 *
 * <p>本类**不再监听任何事件**(切换逻辑统一在 {@link ItemToggleHandler}),所以 mod 构造里也不需要注册它。</p>
 */
public final class RevivalLeafHandler {

    public static final ResourceLocation LEAF_ID = new ResourceLocation("enigmaticaddons", "revival_leaf");
    public static final String NBT_KEY = "enigmaticcurio:flightDisabled";

    /** 记录"上一次调用前是否已经能飞",用于区分是不是这枚术石给的。 */
    private static final ThreadLocal<Boolean> HAD_FLIGHT = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private RevivalLeafHandler() {
    }

    public static boolean isLeaf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && LEAF_ID.equals(id);
    }

    /** 这一件术石的飞行是否已被玩家关掉(键不存在 = 开)。 */
    public static boolean isDisabled(ItemStack stack) {
        try {
            CompoundTag tag = stack.getTag();
            return tag != null && tag.getBoolean(NBT_KEY);
        }
        catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * 写入 / 清除"飞行已关闭"标记(清除时若 NBT 空了就一并清掉,不留空 CompoundTag)。
     * <p>只由服务端通过 {@link ItemToggleHandler} 调用。</p>
     */
    public static void setFlightDisabled(ItemStack stack, boolean disabled) {
        try {
            if (stack == null || stack.isEmpty()) {
                return;
            }
            if (disabled) {
                stack.getOrCreateTag().putBoolean(NBT_KEY, true);
                return;
            }
            CompoundTag tag = stack.getTag();
            if (tag == null) {
                return;
            }
            tag.remove(NBT_KEY);
            if (tag.isEmpty()) {
                stack.setTag(null);
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    /**
     * 玩家身上这一件术石是否处于"关闭飞行"状态。
     *
     * <p>先查**饰品槽里戴着的那一件**(复苏之叶是术石类 curio,戴着的时候不在
     * {@code player.getInventory()} 里,旧版只扫背包,戴着时开关就"没反应"),再查背包与副手。</p>
     */
    public static boolean flightDisabled(Player player) {
        if (player == null) {
            return false;
        }
        try {
            Item leaf = Superposition.item(LEAF_ID.toString());
            if (leaf != null) {
                ItemStack worn = Superposition.getCurioStack(player, leaf);
                if (!worn.isEmpty() && isDisabled(worn)) {
                    return true;
                }
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
