package com.hengx.enigmaticcurio.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import auviotre.enigmatic.addon.contents.items.BlessRing;
import com.hengx.enigmaticcurio.RingHandler;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 让救赎之戒「不再破碎」—— 拦的是背叛值 {@code BlessBetrayal},不是 {@code BlessDuration}。
 *
 * <p>字节码事实(enigmaticaddons 1.2.5 / 1.2.6,见 research/ea-report.md):</p>
 * <ul>
 *   <li>条子上的"耐久"就是 {@code BlessBetrayal};</li>
 *   <li>{@code BlessDuration} 只是"已经破损了 N 次"的计数器,破损时 +1,并让
 *       {@code getMaxBetrayal = 1250 - 100*BlessDuration} 递减 —— 它是节流阀,不是耐久;</li>
 *   <li>背叛值唯一写入口是 {@code BlessRing$Helper.setBlessAttribute(stack, key, value)}
 *       (它内部就是 {@code stack.getOrCreateTag().putInt(key, value)}),
 *       上游的 {@code Helper.addBetrayal(Player, int)} 被 {@code AddonEventHandler} 等 4 处直接调用。</li>
 * </ul>
 *
 * <p>做法:在唯一写入口上把 {@code BlessBetrayal} 钉成 0(直接写进物品 NBT 后取消原写入),
 * 背叛值永远到不了 {@code getMaxBetrayal},破损分支(播雷声 + 换成破损之戒 + destroyCurio)不可能再触发;
 * {@code BlessDuration}、救赎等级({@code SavedSevenCurseLevel})等其它键原样透传,救赎进度不受影响。
 * 另外在 {@code addBetrayal} 的 HEAD 再拦一道,覆盖绕过 {@code setBlessAttribute} 的直接调用。</p>
 */
@Mixin(BlessRing.Helper.class)
public class BlessRingHelperMixin {

    /** 背叛值(条子上的"耐久")的 NBT 键,字面量与 {@code BlessRing.BETRAYAL} 一致。 */
    private static final String BETRAYAL = "BlessBetrayal";

    @Inject(method = "setBlessAttribute(Lnet/minecraft/world/item/ItemStack;Ljava/lang/String;I)V",
        at = @At("HEAD"), cancellable = true, remap = false)
    private static void enigmaticcurio$freezeBetrayal(ItemStack stack, String key, int value, CallbackInfo ci) {
        try {
            if (!RingHandler.enabled() || !BETRAYAL.equals(key)) {
                return;
            }
            // 把已有的背叛值直接清 0(老存档里可能已经攒了不少),再取消扩展这次的写入。
            stack.getOrCreateTag().putInt(BETRAYAL, 0);
            ci.cancel();
        }
        catch (RuntimeException | LinkageError ignored) {
            // 失败放行:按扩展原逻辑写值
        }
    }

    @Inject(method = "addBetrayal(Lnet/minecraft/world/entity/player/Player;I)V",
        at = @At("HEAD"), cancellable = true, remap = false)
    private static void enigmaticcurio$freezeBetrayalAdd(Player player, int amount, CallbackInfo ci) {
        try {
            if (RingHandler.enabled()) {
                ci.cancel();
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 失败放行
        }
    }
}
