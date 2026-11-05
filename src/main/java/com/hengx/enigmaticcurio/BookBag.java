package com.hengx.enigmaticcurio;

import java.lang.reflect.Method;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 只读地查询「古旧书袋」({@code enigmaticaddons:antique_bag})里有没有某本书。
 *
 * <p>反编译依据(enigmaticaddons 1.2.5/1.2.6 一致):</p>
 * <ul>
 *   <li>书袋本身是普通物品 {@code auviotre.enigmatic.addon.contents.items.AntiqueBag}(extends {@code ItemBase},
 *       <b>不是</b> curio),右键开一个 12 格 GUI;真正的内容物不在物品 NBT 里,而是挂在**玩家(实体)**上的
 *       capability {@code AntiqueBagCapability.INVENTORY}({@code IAntiqueBagHandler},内部 {@code ItemStackHandler(12)}),
 *       随玩家存档保存、Clone 时用 {@code writeTag/readTag} 复制。<b>这个 capability 没有任何网络同步</b>
 *       —— 这就是"客户端 tooltip 看不到袋内效果"的根本原因(见 {@link BagHudSync});</li>
 *   <li>扩展自己提供了现成的只读查询
 *       {@code auviotre.enigmatic.addon.handlers.SuperAddonHandler#findBookInBag(Player, Item)}:
 *       它先要求玩家身上有古旧书袋,再查 capability,命中就返回那本书的 ItemStack,否则返回空。</li>
 * </ul>
 * 这里**直接复用扩展这个 API**(反射调用,缺扩展时安全返回空),因此判定口径与扩展自己完全一致,
 * 也不会对书袋/书做任何写入。
 */
public final class BookBag {

    /** 古旧书袋的注册 id。 */
    public static final String BAG_ID = "enigmaticaddons:antique_bag";

    private static Method findBookInBag;
    private static boolean resolved;

    private BookBag() {
    }

    /** 返回袋里那本书的 ItemStack(没有/书袋不在身上 → 空)。 */
    public static ItemStack findBook(Player player, ResourceLocation itemId) {
        if (player == null || itemId == null) {
            return ItemStack.EMPTY;
        }
        Item item = ForgeRegistries.ITEMS.getValue(itemId);
        if (item == null || item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        return findBookInBag(player, item);
    }

    /** 该物品是否在玩家的古旧书袋里。 */
    public static boolean hasBook(Player player, ResourceLocation itemId) {
        return !findBook(player, itemId).isEmpty();
    }

    /** 反射调用扩展的 {@code SuperAddonHandler.findBookInBag}。 */
    private static ItemStack findBookInBag(Player player, Item item) {
        try {
            Method method = method();
            if (method == null) {
                return ItemStack.EMPTY;
            }
            Object result = method.invoke(null, player, item);
            return result instanceof ItemStack stack ? stack : ItemStack.EMPTY;
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return ItemStack.EMPTY;
        }
    }

    private static synchronized Method method() {
        if (resolved) {
            return findBookInBag;
        }
        resolved = true;
        try {
            Class<?> handler = Class.forName("auviotre.enigmatic.addon.handlers.SuperAddonHandler",
                false, BookBag.class.getClassLoader());
            findBookInBag = handler.getMethod("findBookInBag", Player.class, Item.class);
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            findBookInBag = null;
        }
        return findBookInBag;
    }
}
