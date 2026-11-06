package com.hengx.enigmaticcurio.client;

import com.hengx.enigmaticcurio.CurioNetwork;
import com.hengx.enigmaticcurio.EnigmaticCurio;
import com.hengx.enigmaticcurio.ItemToggleHandler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 客户端:捕获「Ctrl + 左键」→ 发一条 C2S 包让服务端校验并写 NBT。
 *
 * <p><b>为什么只能在客户端做</b>:Ctrl 的按键状态({@code InputConstants}/{@code Minecraft})
 * 只存在于客户端,服务端根本读不到;所以由客户端识别手势、服务端负责校验与落 NBT。</p>
 *
 * <h2>两个入口</h2>
 * <ol>
 *   <li><b>容器界面里</b>(背包 / 饰品栏 / 箱子 / 书袋):Ctrl+左键 点在某格上 → 取消这次点击
 *       (不搬物品、不佩戴),发 {@link CurioNetwork.SlotTogglePacket}(菜单 id + 槽位号)。</li>
 *   <li><b>世界里</b>(没开任何界面):Ctrl+左键 且 主手拿着这四件之一 → 取消这次攻击/挖掘
 *       (不会误伤、不会挖方块),发 {@link CurioNetwork.HandTogglePacket}(哪只手)。</li>
 * </ol>
 *
 * <p>本类只在客户端加载({@code value = Dist.CLIENT}),服务端不会触碰任何 {@code net.minecraft.client} 类型。</p>
 */
@Mod.EventBusSubscriber(modid = EnigmaticCurio.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ToggleKeyHandler {

    private ToggleKeyHandler() {
    }

    /** 入口 1:容器界面里 Ctrl+左键 点槽位。 */
    @SubscribeEvent
    public static void onMouseButtonPressed(ScreenEvent.MouseButtonPressed.Pre event) {
        try {
            if (event.getButton() != 0 || !Screen.hasControlDown()) {
                return;
            }
            if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
                return;
            }
            Slot slot = screen.getSlotUnderMouse();
            if (slot == null || !slot.hasItem() || !ItemToggleHandler.canToggle(slot.getItem())) {
                return;
            }
            // 不执行原本的搬运/交换/佩戴;改为请求切换这一格的开关。
            event.setCanceled(true);
            CurioNetwork.CHANNEL.sendToServer(
                new CurioNetwork.SlotTogglePacket(screen.getMenu().containerId, slot.index));
        }
        catch (RuntimeException | LinkageError ignored) {
            // 客户端手势识别失败绝不能影响游戏
        }
    }

    /** 入口 2:世界里(无界面)Ctrl+左键,主手拿着可开关的物品。 */
    @SubscribeEvent
    public static void onInteractionKeyMapping(InputEvent.InteractionKeyMappingTriggered event) {
        try {
            if (!event.isAttack() || !Screen.hasControlDown()) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.screen != null) {
                return;   // 界面里走入口 1
            }
            Player player = minecraft.player;
            if (player == null) {
                return;
            }
            ItemStack stack = player.getMainHandItem();
            if (!ItemToggleHandler.canToggle(stack)) {
                return;
            }
            // 取消这次攻击/挖掘(手臂照常挥一下当作反馈),改为请求切换开关。
            event.setCanceled(true);
            event.setSwingHand(true);
            CurioNetwork.CHANNEL.sendToServer(
                new CurioNetwork.HandTogglePacket(InteractionHand.MAIN_HAND.ordinal()));
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    /**
     * 退服时清掉书袋效果的客户端缓存,免得把上一个服务器的数据带到下一个
     * (见 {@link com.hengx.enigmaticcurio.BagHudClient})。
     */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        try {
            com.hengx.enigmaticcurio.BagHudClient.reset();
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }
}
