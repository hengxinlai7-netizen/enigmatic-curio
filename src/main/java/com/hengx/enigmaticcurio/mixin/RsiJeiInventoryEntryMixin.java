package com.hengx.enigmaticcurio.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.huanghuang.rsintegration.network.packet.JeiNetworkInventoryPacket;
import com.hengx.enigmaticcurio.RsiJeiGuard;

import net.minecraft.world.item.ItemStack;

/**
 * <b>Refined Storage Integration(RSI)→ JEI 库存同步:别让空堆栈把连接打断。</b>
 *
 * <h2>崩溃链(已用 javap 核对 rs_integration-1.5.2.3.jar)</h2>
 * <pre>
 * 服务端(enigmaticenhance 在,本客户端不在)
 *   → JeiNetworkInventorySyncManager#sendFull/send 构造非空的 Entry 并编码
 *   → 客户端 FriendlyByteBuf#readItem 读到本端注册表里没有的物品 id
 *   → Item.byId(id) 回落到 minecraft:air ⇒ ItemStack 变空(读包本身不报错)
 *   → JeiNetworkInventoryPacket#decode 循环里 new Entry(stack, amount)
 *   → JeiNetworkInventoryPacket$Entry.&lt;init&gt;(Lnet/minecraft/world/item/ItemStack;J)V
 *     throws new IllegalArgumentException("JEI inventory entry requires a non-empty stack")
 *   → netty 解码线程抛异常 ⇒ 客户端掉线"内部异常: ... 需要一个非空堆栈"
 * </pre>
 *
 * <p>目标方法字节码(javap 原文):</p>
 * <pre>
 * 0: aload_0
 * 1: invokespecial #22   // java/lang/Record."&lt;init&gt;":()V
 * 4: aload_1
 * 5: ifnull 15
 * 8: aload_1
 * 9: invokevirtual #28  // ItemStack.m_41619_:()Z      &lt;-- 本 mixin 拦这一条
 * 12: ifeq 25
 * 15: new #30  // IllegalArgumentException
 * 18: ldc #32   // "JEI inventory entry requires a non-empty stack"
 * 21: invokespecial #35  // IllegalArgumentException."&lt;init&gt;(Ljava/lang/String;)V
 * 24: athrow
 * </pre>
 *
 * <h2>为什么重定向 {@code m_41619_} 而不是 cancel 构造</h2>
 * <ul>
 *   <li>目标是 <b>record</b>,构造器开头必须先 {@code invokespecial Record.&lt;init&gt;};
 *       {@code @Inject(HEAD, cancellable)} 虽然会被 Mixin 挪到 super 之后,但 cancel 出去的对象
 *       两个 final 字段都是默认值({@code stack == null}),会被 {@code List.add} 收下,后面必然 NPE ——
 *       比现在更糟。</li>
 *   <li>把 {@code isEmpty()} 的返回值改成 {@code false},后面的
 *       {@code ItemStack#m_255036_(1)}(copyWithCount)对空栈是<b>安全的</b>(反编译确认:空栈直接
 *       return {@code ItemStack.EMPTY}),于是得到一条 {@code stack() == EMPTY} 的"坏条目"。</li>
 *   <li>坏条目随后由 {@link RsiJeiInventoryPacketMixin} 在 {@code decode} 返回处整条剔除 ——
 *       所以空堆栈<b>不会</b>漏进 JEI 的 ingredient 索引。</li>
 * </ul>
 *
 * <p>三个调用点(RSI 自己的 {@code sendFull}/{@code send}/{@code pollFallback})在构造 Entry 之前都
 * 先判了 {@code stack.isEmpty()} 就 {@code continue},所以服务端行为不受本重定向影响。</p>
 *
 * <p>目标类在玩家没装 RSI 时不存在 ⇒ {@code require = 0} + 可选配置
 * ({@code enigmaticcurio.optional.mixins.json}, {@code required:false} / {@code defaultRequire:0})。</p>
 */
@Mixin(JeiNetworkInventoryPacket.Entry.class)
public abstract class RsiJeiInventoryEntryMixin {

    /**
     * 把 record 构造里的"非空堆栈"前置条件改成恒真。
     *
     * <p>目标串用 <b>SRG</b> 名({@code m_41619_})并 {@code remap = false}:RSI 是第三方模组,
     * 它对 MC 方法的引用在发布 jar 里已经是 SRG 名(javap 已证实),走 refmap 反而对不上。</p>
     */
    @Redirect(
        method = "<init>(Lnet/minecraft/world/item/ItemStack;J)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;m_41619_()Z"),
        remap = false,
        require = 0)
    private static boolean enigmaticcurio$tolerateEmptyStack(ItemStack stack) {
        try {
            // 真·空栈才记账(正常条目也走这里,不能每次都记)
            if (stack != null && stack.isEmpty()) {
                RsiJeiGuard.onEmptyEntryBypassed();
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 记账失败绝不影响游戏
        }
        // 恒 false = "这条堆栈非空",让构造走完;坏条目由 decode 处剔除
        return false;
    }
}
