package com.hengx.enigmaticcurio.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.entity.player.Player;

/**
 * 1.2.20:末影箱真的能显示 54 格。
 *
 * <p>上一版只把容器容量扩到 54,但原版 {@code EnderChestMenu} 用的是
 * {@code MenuType.GENERIC_9x3}(3 行 27 格,写死),所以界面仍是 27 格。</p>
 *
 * <p>这里在右键时改用原版现成的 {@code GENERIC_9x6}(6 行 54 格)箱子界面,
 * 容器仍是对应玩家的 {@code PlayerEnderChestContainer}(容量已扩到 54),
 * 因此不需要自定义 MenuType / Screen,客户端用原版大箱子界面即可。</p>
 */
@Mixin(EnderChestBlock.class)
public class EnderChestBlockMenuMixin {

    @Inject(method = "m_6227_", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void enigmaticcurio$openLargeMenu(net.minecraft.world.level.block.state.BlockState state, net.minecraft.world.level.Level level,
        net.minecraft.core.BlockPos pos, Player player, net.minecraft.world.InteractionHand hand,
        net.minecraft.world.phys.BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
        if (level.isClientSide) {
            return;
        }
        try {
            player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new ChestMenu(MenuType.GENERIC_9x6, id, inventory,
                    p.getEnderChestInventory(), 6),
                Component.translatable("block.minecraft.ender_chest")));
            cir.setReturnValue(InteractionResult.sidedSuccess(false));
        }
        catch (Throwable ignored) {
            // 失败就交给原版 27 格界面
        }
    }
}