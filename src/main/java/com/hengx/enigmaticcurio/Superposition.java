package com.hengx.enigmaticcurio;

import net.minecraft.world.entity.LivingEntity;
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