package com.hengx.enigmaticcurio;

import java.util.Optional;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 本模组唯一的网络通道:{@code enigmaticcurio:main}。
 *
 * <p>为什么需要它:<b>Ctrl 的按键状态只有客户端知道</b>({@code Minecraft}/{@code InputConstants}
 * 只在客户端存在),服务端读不到。所以「Ctrl + 左键」这个手势只能由客户端识别,
 * 然后发一条包告诉服务端"我点了哪个槽位 / 哪只手",由服务端**自己重新校验**并写物品 NBT。</p>
 *
 * <h2>通道约定</h2>
 * <ul>
 *   <li>{@link NetworkRegistry#newSimpleChannel} 建通道,协议版本号 {@value #VERSION} 自定义;
 *       两端版本不一致时 Forge 会在握手阶段直接拒绝,不会出现"半新半旧"的错位解码;</li>
 *   <li><b>只有客户端 → 服务端</b>:两条包都用 {@code Optional.of(NetworkDirection.PLAY_TO_SERVER)}
 *       注册,本模组<b>不向客户端发任何包</b>(客户端看到的状态靠物品 NBT 随容器同步刷回来);</li>
 *   <li>包体只有"槽位号 / 手"这些**索引**,不含物品 id、不含目标状态、不含 NBT ——
 *       客户端无法指定要写什么,只能请求"把我当前打开的这个菜单里的这一格切一下",
 *       服务端再按自己的视角重新判定(见 {@link ItemToggleHandler})。</li>
 * </ul>
 */
public final class CurioNetwork {

    /** 协议版本:改了包结构就 +1(旧客户端会被 Forge 直接踢掉,不会错位解码)。 */
    private static final String VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(EnigmaticCurio.MODID, "main"),
        () -> VERSION, VERSION::equals, VERSION::equals);

    private CurioNetwork() {
    }

    /** 在 mod 构造里调用(双端各一次;服务端只需要能收,客户端负责发)。 */
    public static void register() {
        CHANNEL.registerMessage(0, SlotTogglePacket.class,
            SlotTogglePacket::encode, SlotTogglePacket::decode, SlotTogglePacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, HandTogglePacket.class,
            HandTogglePacket::encode, HandTogglePacket::decode, HandTogglePacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    /** C2S:请求切换"当前打开的菜单里第 slotIndex 格"的开关(背包 / 饰品槽 / 打开的容器都走这条)。 */
    public static final class SlotTogglePacket {

        private final int containerId;
        private final int slotIndex;

        public SlotTogglePacket(int containerId, int slotIndex) {
            this.containerId = containerId;
            this.slotIndex = slotIndex;
        }

        public static void encode(SlotTogglePacket msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.containerId);
            buf.writeVarInt(msg.slotIndex);
        }

        public static SlotTogglePacket decode(FriendlyByteBuf buf) {
            return new SlotTogglePacket(buf.readVarInt(), buf.readVarInt());
        }

        public static void handle(SlotTogglePacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            // 只接受客户端 → 服务端的包;服务端也永远不会有"收包的玩家"以外的 sender。
            if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
                context.setPacketHandled(true);
                return;
            }
            context.enqueueWork(() -> {
                ServerPlayer sender = context.getSender();
                if (sender != null) {
                    ItemToggleHandler.handleSlotRequest(sender, msg.containerId, msg.slotIndex);
                }
            });
            context.setPacketHandled(true);
        }
    }

    /** C2S:请求切换"手上这一件"(世界里 Ctrl + 左键,不打开任何界面时走这条)。 */
    public static final class HandTogglePacket {

        /** {@code InteractionHand} 的序号(0 = 主手,1 = 副手)。 */
        private final int hand;

        public HandTogglePacket(int hand) {
            this.hand = hand;
        }

        public static void encode(HandTogglePacket msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.hand);
        }

        public static HandTogglePacket decode(FriendlyByteBuf buf) {
            return new HandTogglePacket(buf.readVarInt());
        }

        public static void handle(HandTogglePacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
                context.setPacketHandled(true);
                return;
            }
            context.enqueueWork(() -> {
                ServerPlayer sender = context.getSender();
                if (sender != null) {
                    ItemToggleHandler.handleHandRequest(sender, msg.hand);
                }
            });
            context.setPacketHandled(true);
        }
    }
}
