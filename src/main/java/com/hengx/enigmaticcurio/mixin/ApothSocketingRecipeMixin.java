package com.hengx.enigmaticcurio.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.hengx.enigmaticcurio.ApothSocketingGuard;

import dev.shadowsoffire.apotheosis.adventure.socket.SocketingRecipe;

/**
 * <b>神化(Apotheosis)镶嵌配方:别让 {@code ArrayList#set(0, ...)} 在空列表上越界。</b>
 *
 * <h2>崩溃链(已用 javap 核对 Apotheosis-1.20.1-7.4.8.jar + jei-1.20.1-forge-15.20.0.129.jar)</h2>
 * <pre>
 * ApothSmithingRecipe extends SmithingTransformRecipe  ⇒ base 配料恒为 Ingredient.EMPTY(原版设计)
 *   ⇒ JEI SmithingCategoryExtension.setOutput 显式把空配料替换成 ItemStack.EMPTY 填进合成容器 slot 1
 *      (SmithingCategoryExtension 字节码 82: getstatic ItemStack.f_41583_ / 85: List.of)
 *   ⇒ RecipeUtil.assembleResultItem ⇒ SocketingRecipe.assemble(container, access)
 *   ⇒ SocketHelper.getGems(ItemStack.EMPTY) ⇒ getGemsImpl:
 *        getSockets(stack) == 0(没有 affix_data.sockets) ⇒ 直接 return SocketedGems.EMPTY(size 0)
 *   ⇒ SocketHelper.getFirstEmptySocket: 循环 0 次,<b>兜底 return 0</b>(不是 -1)
 *   ⇒ new ArrayList&lt;&gt;(gems) 长度 0 ⇒ gems.set(0, ...) ⇒ IndexOutOfBoundsException(0, 0)
 * </pre>
 *
 * <p>日志里那一行正是 {@code Index 0 out of bounds for length 0}
 * ({@code JEI/IngredientSupplierHelper}: "Found a broken recipe ... Apotheosis apotheosis:socketing")。</p>
 *
 * <h2>注入点:{@code SocketingRecipe#m_5874_} 里唯一的 {@code List.set}</h2>
 * <pre>
 * 36: new ArrayList
 * 42: invokestatic SocketHelper.getGems(ItemStack)SocketedGems
 * 45: invokevirtual SocketedGems.gems()ImmutableList       // ← 列表来源(可能 size 0)
 * 48: invokespecial ArrayList.&lt;init&gt;(Collection)
 * 51: astore 7                                             // gems
 * 29: invokestatic SocketHelper.getFirstEmptySocket(ItemStack)I
 * 34: istore 6                                             // socket(空列表时 = 0)
 * 66: aload 7 / 68: iload 6 / 70..74: GemInstance.socketed(...)
 * 80: invokeinterface java/util/List.set(ILjava/lang/Object;)Ljava/lang/Object;   // ← 本 mixin 拦这一条
 * 85: pop                                                  // 返回值被丢弃,可以安全替换
 * </pre>
 *
 * <h2>为什么不是"HEAD 直接返回空栈"</h2>
 * <ul>
 *   <li>真实游戏里 {@code SmithingMenu} 先走 {@code matches()} 才 {@code assemble()},
 *       而 {@code matches} 要求 {@code hasEmptySockets(base) && gem.canApplyTo(base)},
 *       所以"没有镶槽的底材"这条路径在游戏内本来就进不来 —— 它只出现在 JEI 的模拟预览里。</li>
 *   <li>HEAD 返回 {@code ItemStack.EMPTY} 会让 JEI 得到"空结果"(配方看起来没产物),
 *       而且万一有别的模组/脚本直接调 {@code assemble},玩家会白丢一颗宝石。</li>
 *   <li>只改 {@code set} 时:索引合法就<b>完全照原逻辑</b>执行;索引刚好等于长度(空列表 = 0)
 *       就改成"追加"(作者本意就是"把宝石放进这个空镶槽");其它越界则不动列表。
 *       返回值在原字节码里紧接着 {@code pop},所以返回 null 不影响任何逻辑。</li>
 * </ul>
 *
 * <p>目标类是第三方模组 ⇒ {@code remap = false}、{@code require = 0},
 * 并登记在 {@code enigmaticcurio.optional.mixins.json}({@code required:false} / {@code defaultRequire:0}),
 * 玩家没装神化时整份配置静默跳过。</p>
 */
@Mixin(value = SocketingRecipe.class, remap = false)
public abstract class ApothSocketingRecipeMixin {

    @Redirect(
        method = "m_5874_(Lnet/minecraft/world/Container;Lnet/minecraft/core/RegistryAccess;)Lnet/minecraft/world/item/ItemStack;",
        at = @At(value = "INVOKE", target = "Ljava/util/List;set(ILjava/lang/Object;)Ljava/lang/Object;"),
        remap = false,
        require = 0)
    private static Object enigmaticcurio$safeSetGem(List<Object> gems, int socket, Object gem) {
        try {
            if (gems == null) {
                return null;
            }
            int size = gems.size();
            if (socket >= 0 && socket < size) {
                // 正常情况:原位替换,行为与未装本模组完全一致
                return gems.set(socket, gem);
            }
            if (socket == size) {
                // 空镶槽列表(size 0, socket 0)或未 padding 的列表:追加即为原本语义
                gems.add(gem);
                ApothSocketingGuard.onAppended(socket);
                return null;
            }
            // 负索引 / 跳空:放不进去,丢掉这颗宝石,但绝不抛异常
            ApothSocketingGuard.onDiscarded(socket, size);
            return null;
        }
        catch (RuntimeException | LinkageError e) {
            ApothSocketingGuard.onError(e);
            return null;
        }
    }
}
