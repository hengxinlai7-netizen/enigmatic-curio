package com.hengx.enigmaticcurio.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import auviotre.enigmatic.addon.contents.items.BlessRing;
import com.hengx.enigmaticcurio.DebugLog;
import com.hengx.enigmaticcurio.RingHandler;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 允许「救赎之戒」与「七咒之戒」一起佩戴。
 *
 * <p>反编译 {@code auviotre.enigmatic.addon.contents.items.BlessRing#canEquip}
 * (1.2.5 / 1.2.6 一致):</p>
 * <pre>
 * if (super.canEquip(context, stack)) {
 *     if (context.entity() instanceof Player player) {
 *         return !SuperpositionHandler.isTheCursedOne(player)      // 戴着七咒之戒 → 为 true → 被否决
 *             &amp;&amp; !SuperpositionHandler.hasCurio(player, BROKEN_RING);
 *     }
 *     return false;
 * }
 * return false;
 * </pre>
 *
 * <p>这里在入口处判断:配置允许 + 玩家身上确实有七咒之戒时直接放行。
 * 用 {@code @Inject(cancellable)} 而不是改写逻辑,配置关掉即恢复扩展原行为。</p>
 *
 * <p>{@code required=false}(见 mixins.json):没装扩展时只是一条警告。</p>
 */
@Mixin(BlessRing.class)
public class BlessRingMixin {

    @Inject(method = "canEquip", at = @At("HEAD"), cancellable = true, remap = false)
    private void enigmaticcurio$allowWithCursedRing(
        top.theillusivec4.curios.api.SlotContext context, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!RingHandler.enabled()) {
                return;
            }
            if (!(context.entity() instanceof Player player)) {
                return;
            }
            if (!RingHandler.wearingBoth(player)) {
                return;
            }
            cir.setReturnValue(true);
            DebugLog.info("[救赎之戒] {} 同时戴着七咒之戒,放行佩戴", player.getName().getString());
        }
        catch (RuntimeException | LinkageError ignored) {
            // 失败放行:交给扩展自己判定
        }
    }
}
