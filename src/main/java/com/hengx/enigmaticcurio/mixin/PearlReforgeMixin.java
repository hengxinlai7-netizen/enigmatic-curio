package com.hengx.enigmaticcurio.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hengx.enigmaticcurio.CurioConfig;
import com.hengx.enigmaticcurio.PearlUtil;

import dev.shadowsoffire.apotheosis.adventure.affix.reforging.ReforgingMenu;
import net.minecraft.world.entity.player.Player;

/**
 * 附魔师的珍珠:神化(Apotheosis)重铸台不消耗新生符石。
 *
 * <p>神化的重铸消耗在 {@code ReforgingMenu} 里按插槽算,其中
 * {@code getSigilCost(int)} 就是"新生符石"的数量。珍珠在场时让它返回 0 即可,
 * 经验与珍宝材料的结算保持神化原样。</p>
 *
 * <p>这里用 {@link PearlUtil#wearing} 判定 —— 只要身上(饰品槽/背包/副手)带着珍珠就生效。
 * {@code require = 0}:神化版本漂移、方法改名时静默不生效,不会让游戏崩。</p>
 */
@Mixin(ReforgingMenu.class)
public abstract class PearlReforgeMixin {

    @Shadow(remap = false)
    @Final
    protected Player player;

    @Inject(method = "getSigilCost(I)I", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void enigmaticcurio$freeSigilReforge(int slot, CallbackInfoReturnable<Integer> cir) {
        try {
            if (!CurioConfig.PEARL_FIX_REFORGE.get()) {
                return;
            }
            if (this.player == null || !PearlUtil.wearing(this.player)) {
                return;
            }
            cir.setReturnValue(0);
        }
        catch (RuntimeException | LinkageError ignored) {
            // 失败放行:按神化原本的消耗结算
        }
    }
}
