package com.hengx.enigmaticcurio.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hengx.enigmaticcurio.RevivalLeafHandler;

import net.minecraft.world.entity.player.Player;

/** 复苏之叶:关了开关就撤销它自己授予的那次飞行,别的来源不动。 */
@Mixin(auviotre.enigmatic.addon.contents.items.RevivalLeaf.class)
public class RevivalLeafFlightMixin {

    @Inject(method = "handleFlight", at = @At("HEAD"), remap = false, require = 0)
    private void enigmaticcurio$record(Player player, CallbackInfo ci) {
        RevivalLeafHandler.recordFlight(player);
    }

    @Inject(method = "handleFlight", at = @At("RETURN"), remap = false, require = 0)
    private void enigmaticcurio$revoke(Player player, CallbackInfo ci) {
        if (player != null && RevivalLeafHandler.flightDisabled(player)) {
            RevivalLeafHandler.revokeGrantedFlight(player);
        }
        else {
            RevivalLeafHandler.clearPending();
        }
    }
}