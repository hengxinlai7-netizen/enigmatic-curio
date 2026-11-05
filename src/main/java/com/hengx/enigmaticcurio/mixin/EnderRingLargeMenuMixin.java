package com.hengx.enigmaticcurio.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraftforge.network.NetworkEvent;

/**
 * 1.2.21:神秘遗物「末影箱」也变成 6 行(54 格)。
 *
 * <p>神秘遗物的末影箱不是原版方块:末影之戒 → 背包按钮 → {@code PacketEnderRingKey} →
 * 服务端构造 {@code new ChestMenu(MenuType.GENERIC_9x3, ..., 3)} 打开 27 格界面。
 * 容器用的是 {@code player.getEnderChestInventory()}(本模组已把它扩到 54 格),
 * 所以这里只把**界面**换成 9x6/6 行,物品与存档完全不动。</p>
 *
 * <p>注入点:{@code PacketEnderRingKey#handle} 的 HEAD,直接接管打开逻辑并取消原调用。
 * 只在包确实表示"按下"时接管;异常/失败时交回神秘遗物自己的实现(退回 27 格)。</p>
 */
@Mixin(com.aizistral.enigmaticlegacy.packets.server.PacketEnderRingKey.class)
public class EnderRingLargeMenuMixin {

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private static void enigmaticcurio$openLargeEnderChest(
        com.aizistral.enigmaticlegacy.packets.server.PacketEnderRingKey packet,
        java.util.function.Supplier<NetworkEvent.Context> supplier, CallbackInfo ci) {
        try {
            NetworkEvent.Context context = supplier.get();
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            player.openMenu(new SimpleMenuProvider(
                (id, inventory, p) -> new ChestMenu(MenuType.GENERIC_9x6, id, inventory,
                    p.getEnderChestInventory(), 6),
                Component.translatable("container.enderchest")));
            context.setPacketHandled(true);
            ci.cancel();
        }
        catch (Throwable ignored) {
            // 失败 ⇒ 交回神秘遗物原本的 27 格界面
        }
    }
}