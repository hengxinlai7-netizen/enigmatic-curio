package com.hengx.enigmaticcurio.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import auviotre.enigmatic.addon.contents.items.BlessRing;
import com.hengx.enigmaticcurio.BlessRingRules;
import com.hengx.enigmaticcurio.DebugLog;
import com.hengx.enigmaticcurio.RingHandler;


import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;


/**
 * 允许「救赎之戒」与「七咒之戒」一起佩戴。
 *
 * <p>反编译 {@code auviotre.enigmatic.addon.contents.items.BlessRing#canEquip}:
 * 戴着七咒之戒时它返回 {@code false}(即"不能戴")。本注入在入口处按配置放行。</p>
 *
 * <h2>1.1.10-beta1 的 bug(已修)</h2>
 * 旧代码判的是 {@code RingHandler.wearingBoth(player)} —— 即"两戒都在身上"。但 {@code canEquip}
 * 是<b>佩戴瞬间</b>调用的,那时救赎之戒还没戴上,{@code hasCurio(bless)} 必然为 false,
 * 于是判据恒 false、永远不放行。现在的判据是"身上带着七咒之戒"。
 *
 * <p>{@code remap = false};{@code required = false} 见 mixins.json(没装扩展只是跳过)。</p>
 */
@Mixin(BlessRing.class)
public class BlessRingMixin {

    @Inject(method = "canEquip", at = @At("HEAD"), cancellable = true, remap = false)
    private void enigmaticcurio$allowWithCursedRing(
        top.theillusivec4.curios.api.SlotContext context, ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!(context.entity() instanceof Player player)) {
                return;
            }
            if (!BlessRingRules.canEquip(RingHandler.enabled(), wearingCursedRing(player))) {
                return;
            }
            cir.setReturnValue(true);
            DebugLog.info("[救赎之戒] {} 身上带着七咒之戒,放行佩戴", player.getName().getString());
        }
        catch (RuntimeException | LinkageError ignored) {
            // 失败就交回扩展自己判定(保持原行为),绝不让别的模组崩
        }
    }

    /** 玩家身上(Curios 槽或背包)是否带着七咒之戒。 */
    private static boolean wearingCursedRing(Player player) {
        Item cursed = RingHandler.cursedRing();
        if (cursed == null) {
            return false;
        }
        if (RingHandler.hasCursedRing(player)) {
            return true;
        }
        // 兜底:Curios 判定拿不到时扫一遍背包
        for (ItemStack s : player.getInventory().items) {
            if (!s.isEmpty() && s.is(cursed)) {
                return true;
            }
        }
        for (ItemStack s : player.getInventory().offhand) {
            if (!s.isEmpty() && s.is(cursed)) {
                return true;
            }
        }
        return false;
    }
}
