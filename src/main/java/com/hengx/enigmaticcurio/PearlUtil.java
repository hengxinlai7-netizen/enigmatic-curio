package com.hengx.enigmaticcurio;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 「附魔师的珍珠」({@code enigmaticlegacy:enchanter_pearl})携带判定。
 *
 * <p>只按物品 id 判定,避免编译期依赖神秘遗物的类。
 * "携带" = Curios 槽里 / 背包里 / 副手,任意一处有就算。</p>
 */
public final class PearlUtil {

    /** 附魔师珍珠的注册 id。 */
    public static final String PEARL_ID = "enigmaticlegacy:enchanter_pearl";

    private static Item pearl;

    private PearlUtil() {
    }

    /** 附魔师珍珠物品(取不到时为 null)。 */
    public static Item pearlItem() {
        try {
            if (pearl == null) {
                pearl = Superposition.item(PEARL_ID);
            }
            return pearl;
        }
        catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** 玩家是否携带附魔师珍珠(饰品槽 / 背包 / 副手)。 */
    public static boolean wearing(Player player) {
        try {
            if (player == null) {
                return false;
            }
            Item item = pearlItem();
            if (item == null) {
                return false;
            }
            if (Superposition.hasCurio(player, item)) {
                return true;
            }
            for (ItemStack stack : player.getInventory().items) {
                if (isPearl(stack, item)) {
                    return true;
                }
            }
            return isPearl(player.getOffhandItem(), item);
        }
        catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static boolean isPearl(ItemStack stack, Item item) {
        return stack != null && !stack.isEmpty() && stack.is(item);
    }
}
