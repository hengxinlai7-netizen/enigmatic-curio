package com.hengx.enigmaticcurio;

import java.lang.reflect.Method;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 对神秘遗物 {@code SuperpositionHandler} 的薄封装(纯反射,未装 EL 时安全返回空)。
 */
public final class Superposition {

    private Superposition() {
    }

    /** 该实体身上是否戴着某件 curio。 */
    public static boolean hasCurio(LivingEntity entity, Item item) {
        try {
            Class<?> c = Class.forName("com.aizistral.enigmaticlegacy.handlers.SuperpositionHandler",
                false, Superposition.class.getClassLoader());
            return (boolean) c.getMethod("hasCurio", LivingEntity.class, Item.class).invoke(null, entity, item);
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 取该实体身上某件 curio 的物品栈(没有则空)。 */
    public static ItemStack getCurioStack(LivingEntity entity, Item item) {
        try {
            Class<?> c = Class.forName("com.aizistral.enigmaticlegacy.handlers.SuperpositionHandler",
                false, Superposition.class.getClassLoader());
            Object v = c.getMethod("getCurioStack", LivingEntity.class, Item.class).invoke(null, entity, item);
            return v instanceof ItemStack s ? s : ItemStack.EMPTY;
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * 直接调用本体自己的 {@code SuperpositionHandler.hasItem(Player, Item)}(口径与本体完全一致,
     * 含它对末影箱/饰品栏的处理),而不是本类 {@link #hasCurio} 那个近似实现。
     *
     * <p>1.1.9:给 {@link BookBag#hasBag} 的一次性前置判定用 —— 扩展的
     * {@code SuperAddonHandler.findBookInBag} 内部就是先调它,所以"它为 false ⇒ 袋里的书不会生效"
     * 是严格等价的判断,但只需扫一次背包/饰品栏。缺模组时返回 false。</p>
     */
    public static boolean legacyHasItem(Player player, Item item) {
        if (player == null || item == null) {
            return false;
        }
        try {
            Method method = legacyHasItemMethod();
            if (method == null) {
                return false;
            }
            Object result = method.invoke(null, player, item);
            return result instanceof Boolean && (Boolean) result;
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 解析结果(只解析一次;失败保持 null,语义 = "模组不在")。 */
    private static Method legacyHasItemMethod;
    private static boolean legacyResolved;

    private static synchronized Method legacyHasItemMethod() {
        if (legacyResolved) {
            return legacyHasItemMethod;
        }
        legacyResolved = true;
        try {
            Class<?> handler = Class.forName("com.aizistral.enigmaticlegacy.handlers.SuperpositionHandler",
                false, Superposition.class.getClassLoader());
            legacyHasItemMethod = handler.getMethod("hasItem", Player.class, Item.class);
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            legacyHasItemMethod = null;
        }
        return legacyHasItemMethod;
    }

    /** 按注册 id 找物品。 */
    public static Item item(String id) {
        try {
            return ForgeRegistries.ITEMS.getValue(new net.minecraft.resources.ResourceLocation(id));
        }
        catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}