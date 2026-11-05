package com.hengx.enigmaticcurio.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import auviotre.enigmatic.addon.contents.items.BlessRing;
import com.hengx.enigmaticcurio.DebugLog;
import com.hengx.enigmaticcurio.RingHandler;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.SlotContext;

/**
 * 七咒之戒 + 救赎之戒 同戴时,跳过救赎之戒的惩罚 tick。
 *
 * <p>反编译 {@code BlessRing#curioTick}(1.2.5 偏移 92-611):玩家满足
 * {@code SuperAddonHandler.isPunishedOne(player)}(= 戴着救赎之戒且是七咒之人)或
 * "两枚戒指同时戴着"时,每 tick:</p>
 * <ul>
 *   <li>{@code Punishment} 持久计数器 +1;</li>
 *   <li>每 20 tick:劈一道雷(伤害 1,起因是玩家)+ 火焰爆炸;</li>
 *   <li>每 20 tick:回满血、抢走灵魂水晶、清空背包/护甲/副手与除两枚戒指外的全部饰品;</li>
 *   <li>{@code Punishment > 100}(约 5 秒)后:抹掉救赎之戒、再清一次背包、清经验、
 *       用 1000 倍最大生命的伤害 + 爆炸(32,带火)把玩家打死。</li>
 * </ul>
 *
 * <p>这套流程就是"两枚戒指不能同戴"的实现,也是同戴后玩家会被反复打死的根因。
 * 在 HEAD 直接取消整次 {@code curioTick} 即可全部去掉(代价:同戴期间的缓慢回血也一起停,
 * 这是本功能的既定取舍)。不满足条件时一行都不动。</p>
 */
@Mixin(BlessRing.class)
public class BlessRingTickMixin {

    @Inject(method = "curioTick(Ltop/theillusivec4/curios/api/SlotContext;Lnet/minecraft/world/item/ItemStack;)V",
        at = @At("HEAD"), cancellable = true, remap = false)
    private void enigmaticcurio$stopPunishment(SlotContext context, ItemStack stack, CallbackInfo ci) {
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
            ci.cancel();
            DebugLog.info("[救赎之戒] 已跳过 {} 的惩罚 tick(七咒+救赎同戴)", player.getName().getString());
        }
        catch (RuntimeException | LinkageError ignored) {
            // 失败放行:按扩展原逻辑执行
        }
    }
}
