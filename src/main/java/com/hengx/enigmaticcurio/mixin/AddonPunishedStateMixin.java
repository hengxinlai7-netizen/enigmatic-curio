package com.hengx.enigmaticcurio.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hengx.enigmaticcurio.AddonPunishedRules;
import com.hengx.enigmaticcurio.CurioConfig;
import com.hengx.enigmaticcurio.RingHandler;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 抑制神秘遗物扩展的「惩罚态」判定。
 *
 * <h2>为什么修判据而不是修消费方</h2>
 * 扩展的 {@code SuperAddonHandler#isPunishedOne(LivingEntity)} 是「两戒同戴 = 惩罚态」的唯一判据:
 * <pre>
 * public static boolean isPunishedOne(LivingEntity entity) {
 *     if (entity instanceof Player player)
 *         return hasBlessRing(player) &amp;&amp; SuperpositionHandler.isTheCursedOne(player);
 *     return false;
 * }
 * </pre>
 * 它被 {@code BlessRing.curioTick}(惩罚执行)、{@code MixinLightTexture}(客户端每帧覆盖 lightmap)等多处读取。
 * 本模组允许两戒同戴并拦截惩罚,那么惩罚态就应当恒为 false —— 从源头抑制,所有消费方一起解决。
 *
 * <p>{@code isPunishedOne} 是 <b>static</b> 方法(javap:{@code (Lnet/minecraft/world/entity/LivingEntity;)Z}),
 * 所以 handler 也是 static;{@code remap = false}、{@code require = 0}、整体 {@code try/catch}。</p>
 */
@Mixin(targets = "auviotre.enigmatic.addon.handlers.SuperAddonHandler", remap = false)
public final class AddonPunishedStateMixin {

    /** 七咒之戒的历史别名(老版本注册名)。 */
    private static final ResourceLocation CURSED_RING_ALIAS = new ResourceLocation("enigmaticlegacy", "the_cursed_ring");

    private static Item aliasItem;
    private static boolean aliasResolved;

    private AddonPunishedStateMixin() {}

    @Inject(method = "isPunishedOne", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void enigmaticcurio$suppressPunishedState(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!CurioConfig.suppressAddonPunishedState()) {
                return;   // 补丁关掉:扩展原判定
            }
            if (!(entity instanceof Player player)) {
                return;
            }
            boolean hasBless = hasItem(player, RingHandler.ring());
            boolean hasCursed = hasItem(player, RingHandler.cursedRing()) || hasItem(player, aliasCursedRing());
            if (AddonPunishedRules.shouldSuppressPunished(true, RingHandler.enabled(), hasBless, hasCursed)) {
                cir.setReturnValue(Boolean.FALSE);
            }
        }
        catch (Throwable ignored) {
            // 补丁自身绝不打断判定
        }
    }

    /** 七咒之戒别名物品(取不到返回 null)。 */
    private static Item aliasCursedRing() {
        if (!aliasResolved) {
            aliasResolved = true;
            try {
                aliasItem = ForgeRegistries.ITEMS.getValue(CURSED_RING_ALIAS);
            }
            catch (Throwable ignored) {
                aliasItem = null;
            }
        }
        return aliasItem;
    }

    /** Curios 槽 + 背包 + 副手都算「在身上」。 */
    private static boolean hasItem(Player player, Item item) {
        if (item == null) {
            return false;
        }
        try {
            var inv = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player).resolve();
            if (inv.isPresent()) {
                boolean[] found = { false };
                inv.get().getCurios().forEach((slot, handler) -> {
                    for (int i = 0; i < handler.getSlots() && !found[0]; i++) {
                        ItemStack stack = handler.getStacks().getStackInSlot(i);
                        if (!stack.isEmpty() && stack.is(item)) {
                            found[0] = true;
                        }
                    }
                });
                if (found[0]) {
                    return true;
                }
            }
        }
        catch (Throwable ignored) {
            // Curios 不在:继续扫背包
        }
        try {
            for (ItemStack stack : player.getInventory().items) {
                if (!stack.isEmpty() && stack.is(item)) {
                    return true;
                }
            }
            for (ItemStack stack : player.getInventory().offhand) {
                if (!stack.isEmpty() && stack.is(item)) {
                    return true;
                }
            }
        }
        catch (Throwable ignored) {
            // ignore
        }
        return false;
    }
}
