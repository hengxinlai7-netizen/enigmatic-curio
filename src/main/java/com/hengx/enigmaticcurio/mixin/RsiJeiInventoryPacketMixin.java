package com.hengx.enigmaticcurio.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.huanghuang.rsintegration.network.packet.JeiNetworkInventoryPacket;
import com.hengx.enigmaticcurio.DebugLog;
import com.hengx.enigmaticcurio.RsiJeiGuard;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

/**
 * <b>把 RSI 同步包里"空堆栈"的 JEI 库存条目整条剔除。</b>
 *
 * <p>配套 {@link RsiJeiInventoryEntryMixin}:那边只是让 {@code Entry} 的构造不再抛
 * {@code IllegalArgumentException}(否则客户端在 netty 解码线程里直接掉线),但构造出来的条目
 * {@code stack()} 仍是个 {@code ItemStack.EMPTY}。空堆栈如果被塞进 JEI 的 ingredient 索引,
 * 只是把"连不上服务器"换成"JEI 里出现一个空气条目/索引里再抛一次",所以必须在这里剔除。</p>
 *
 * <h2>为什么选 {@code decode} 的 RETURN 而不是改包构造器</h2>
 * <ul>
 *   <li>RETURN 注入点必定命中,不需要猜局部变量槽位(构造器有 {@code Z JJ II} 参数,
 *       槽位写错就是隐性失效);</li>
 *   <li>只用到 {@code JeiNetworkInventoryPacket} 的公开 getter + 公开构造器,
 *       重建包时 <b>原封不动</b>保留 {@code full/epoch/sequence/chunkIndex/chunkCount/reference} ——
 *       分块序号和 epoch 校验因此完全一致,客户端不会把它当成"另一批数据"。</li>
 *   <li>RSI 服务端三处构造 Entry 前都判过 {@code isEmpty()},所以服务端/编码侧不受影响,本 mixin
 *       实际上只作用于客户端解码:缺物品的一方(客户端)自己吞掉坏条目。</li>
 * </ul>
 *
 * <p>目标方法(注意:目标串用 {@code remap = false},类名在运行期不变:<b>mod 类</b>不是 MC 类):</p>
 * <pre>
 * public static com.huanghuang.rsintegration.network.packet.JeiNetworkInventoryPacket
 *     decode(net.minecraft.network.FriendlyByteBuf)
 * </pre>
 *
 * <p>没装 RSI 时目标类不存在 ⇒ {@code require = 0} + 可选 mixin 配置。</p>
 */
@Mixin(JeiNetworkInventoryPacket.class)
public abstract class RsiJeiInventoryPacketMixin {

    @Inject(
        method = "decode(Lnet/minecraft/network/FriendlyByteBuf;)Lcom/huanghuang/rsintegration/network/packet/JeiNetworkInventoryPacket;",
        at = @At("RETURN"),
        cancellable = true,
        remap = false,
        require = 0)
    private static void enigmaticcurio$dropEmptyEntries(FriendlyByteBuf buf,
                                                        CallbackInfoReturnable<JeiNetworkInventoryPacket> cir) {
        try {
            JeiNetworkInventoryPacket packet = cir.getReturnValue();
            if (packet == null) {
                return;
            }
            List<JeiNetworkInventoryPacket.Entry> entries = packet.entries();
            if (entries == null || entries.isEmpty()) {
                return;
            }

            List<JeiNetworkInventoryPacket.Entry> kept = null;
            int dropped = 0;
            for (int i = 0; i < entries.size(); i++) {
                JeiNetworkInventoryPacket.Entry entry = entries.get(i);
                ItemStack stack = entry == null ? null : entry.stack();
                if (entry != null && stack != null && !stack.isEmpty()) {
                    if (kept != null) {
                        kept.add(entry);
                    }
                    continue;
                }
                // 第一次遇到坏条目才建新表,把前面已经确认没事的原样搬过来
                dropped++;
                if (kept == null) {
                    kept = new ArrayList<>(Math.max(0, entries.size() - 1));
                    for (int j = 0; j < i; j++) {
                        kept.add(entries.get(j));
                    }
                }
            }

            if (dropped == 0 || kept == null) {
                return;
            }

            // 只有这里的 new 可能抛(参数不合法),所以先建好再 setReturnValue,失败就原样放行
            JeiNetworkInventoryPacket rebuilt = new JeiNetworkInventoryPacket(
                packet.full(), packet.epoch(), packet.sequence(),
                packet.chunkIndex(), packet.chunkCount(), packet.reference(), kept);
            cir.setReturnValue(rebuilt);
            RsiJeiGuard.onDropped(dropped);
        }
        catch (RuntimeException | LinkageError e) {
            // 任何意外都退回"原包",最坏情况与未装本模组时一致
            DebugLog.warn("[RSI-JEI防御] 剔除空堆栈条目失败({}),按原样放行本次同步包", e.toString());
        }
    }
}
