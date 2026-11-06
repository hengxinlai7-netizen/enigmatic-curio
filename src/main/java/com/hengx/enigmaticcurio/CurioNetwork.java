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
 * 本模组的网络通道:{@code enigmaticcurio:main}。
 *
 * <p>为什么需要它:<b>Ctrl 的按键状态只有客户端知道</b>({@code Minecraft}/{@code InputConstants}
 * 只在客户端存在),服务端读不到。所以「Ctrl + 左键」这个手势只能由客户端识别,
 * 然后发一条包告诉服务端"我点了哪个槽位 / 哪只手",由服务端**自己重新校验**并写物品 NBT。</p>
 *
 * <h2>通道约定</h2>
 * <ul>
 *   <li>{@link NetworkRegistry#newSimpleChannel} 建通道,协议版本号 {@value #VERSION} 自定义;
 *       两端版本不一致时 Forge 会在握手阶段直接拒绝,不会出现"半新半旧"的错位解码;</li>
 *   <li>{@code 0/1} 是<b>客户端 → 服务端</b>({@code PLAY_TO_SERVER}):包体只有"槽位号 / 手"这些
 *       **索引**,不含物品 id、不含目标状态、不含 NBT —— 客户端无法指定要写什么,只能请求
 *       "把我当前打开的这个菜单里的这一格切一下",服务端再按自己的视角重新判定
 *       (见 {@link ItemToggleHandler});</li>
 *   <li>{@code 2/3} 是 {@code 1.2.0} 新增的<b>书袋效果</b>包,见下。</li>
 * </ul>
 *
 * <h2>书袋效果:为什么必须有一条 S2C 通道</h2>
 * 书袋内容只在服务端的 {@code AntiqueBagCapability.INVENTORY} 里,原版没有任何同步。
 * 早期做法是"服务端把结果写进书袋物品 NBT,靠原版容器同步带到客户端" —— 但那条同步是
 * **一次性**的:服务端只在内容签名变化时写一次,而那一次 {@code ClientboundContainerSetSlotPacket}
 * 如果在登录/切维度那几 tick 被客户端忽略(此时客户端的容器菜单还不是 id 0),客户端就永远
 * 停在「等待服务器同步」。所以现在:
 * <ul>
 *   <li>NBT 照写(顺带让别的模组/存档也能看到),同时**逐格显式发包**重发一次;</li>
 *   <li>再加一条独立于容器的 {@link BagEffectsPacket}(S2C),内容变化 / 登录 / 客户端主动请求时推一次;</li>
 *   <li>客户端没数据时可以发 {@link BagRequestPacket}(C2S)要一次,服务端现算现发。</li>
 * </ul>
 */
public final class CurioNetwork {

    /** 协议版本:改了包结构就 +1(旧客户端会被 Forge 直接踢掉,不会错位解码)。 */
    private static final String VERSION = "2";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(EnigmaticCurio.MODID, "main"),
        () -> VERSION, VERSION::equals, VERSION::equals);

    private CurioNetwork() {
    }

    /** 在 mod 构造里调用(双端各一次)。 */
    public static void register() {
        CHANNEL.registerMessage(0, SlotTogglePacket.class,
            SlotTogglePacket::encode, SlotTogglePacket::decode, SlotTogglePacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, HandTogglePacket.class,
            HandTogglePacket::encode, HandTogglePacket::decode, HandTogglePacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_SERVER));
        // 1.2.0:书袋效果 —— 服务端算好推给客户端(tooltip 的第二条数据来源)
        CHANNEL.registerMessage(2, BagEffectsPacket.class,
            BagEffectsPacket::encode, BagEffectsPacket::decode, BagEffectsPacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        // 1.2.0:客户端没有数据时主动要一次
        CHANNEL.registerMessage(3, BagRequestPacket.class,
            BagRequestPacket::encode, BagRequestPacket::decode, BagRequestPacket::handle,
            Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    /**
     * S2C:这名玩家书袋里"会生效的效果"整份列表。
     *
     * <p>格式与物品 NBT 里那份完全一致(每条 5 段 {@code bookId|effectKey|value|active|detail}),
     * 所以两端解析共用 {@link BagHudEffects#toLines}/{@link BagHudEffects#parseLines}。</p>
     */
    public static final class BagEffectsPacket {

        private final java.util.List<String> lines;

        public BagEffectsPacket(java.util.List<String> lines) {
            this.lines = java.util.List.copyOf(lines);
        }

        public static void encode(BagEffectsPacket msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.lines.size());
            for (String line : msg.lines) {
                buf.writeUtf(line);
            }
        }

        public static BagEffectsPacket decode(FriendlyByteBuf buf) {
            int size = buf.readVarInt();
            java.util.List<String> lines = new java.util.ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                lines.add(buf.readUtf());
            }
            return new BagEffectsPacket(lines);
        }

        public static void handle(BagEffectsPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            if (context.getDirection() != NetworkDirection.PLAY_TO_CLIENT) {
                context.setPacketHandled(true);
                return;
            }
            context.enqueueWork(() -> BagHudClient.accept(BagHudEffects.parseLines(msg.lines)));
            context.setPacketHandled(true);
        }
    }

    /** C2S:客户端没有书袋数据时请求服务端现算一份(服务端最多回一条 {@link BagEffectsPacket})。 */
    public static final class BagRequestPacket {

        public BagRequestPacket() {
        }

        public static void encode(BagRequestPacket msg, FriendlyByteBuf buf) {
            // 空包
        }

        public static BagRequestPacket decode(FriendlyByteBuf buf) {
            return new BagRequestPacket();
        }

        public static void handle(BagRequestPacket msg, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            if (context.getDirection() != NetworkDirection.PLAY_TO_SERVER) {
                context.setPacketHandled(true);
                return;
            }
            context.enqueueWork(() -> {
                ServerPlayer sender = context.getSender();
                if (sender != null) {
                    BagHudSync.handleRequest(sender);
                }
            });
            context.setPacketHandled(true);
        }
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
