package com.hengx.enigmaticcurio.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import auviotre.enigmatic.addon.contents.items.BlessRing;
import com.hengx.enigmaticcurio.RingHandler;

import net.minecraft.world.item.ItemStack;

/**
 * 隐藏救赎之戒的「惩罚条」。
 *
 * <p>玩家看到的那条金色"耐久"其实是 {@code BlessBetrayal}(背叛值)伪装出来的:
 * {@code BlessRing} 覆写了原版 {@code Item#isBarVisible}(生产环境 SRG 名 <b>{@code m_142522_}</b>,
 * 描述符 {@code (Lnet/minecraft/world/item/ItemStack;)Z}),只在 {@code BlessBetrayal > 0} 时返回 true。
 * 这里在 HEAD 直接返回 false,条子就再也不显示了。</p>
 *
 * <p>注意:{@code m_142522_} 是<b>原版</b>方法,目标类里的名字就是 SRG 名,
 * 所以必须 {@code remap = false} + 字面 SRG 名 + 完整描述符。
 * {@code getBarWidth}/{@code getBarColor}({@code m_142158_}/{@code m_142159_})不必拦 ——
 * 条子不显示时它们不参与绘制。</p>
 */
@Mixin(BlessRing.class)
public class BlessRingBarMixin {

    @Inject(method = "m_142522_(Lnet/minecraft/world/item/ItemStack;)Z",
        at = @At("HEAD"), cancellable = true, remap = false)
    private void enigmaticcurio$hideBetrayalBar(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (RingHandler.enabled()) {
                cir.setReturnValue(false);
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 失败放行:保留扩展原来的显示逻辑
        }
    }
}
