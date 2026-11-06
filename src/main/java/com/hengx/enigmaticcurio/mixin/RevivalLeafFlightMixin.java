package com.hengx.enigmaticcurio.mixin;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.hengx.enigmaticcurio.RevivalLeafHandler;

import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;

/**
 * 复苏之叶:开关关闭时<b>不让扩展授予飞行</b>(方案 A),而不是事后把 {@code mayfly} 撤回。
 *
 * <h2>目标字节码(实测,{@code javap -p -c},扩展 1.2.5 实例 jar / 1.2.6 编译 jar 两版完全一致)</h2>
 * <pre>
 * protected void handleFlight(net.minecraft.world.entity.player.Player);
 *    0: aload_0
 *    1: aload_1
 *    2: invokevirtual  hasPlantBy:(Lnet/minecraft/world/entity/player/Player;)Z
 *    5: ifeq           48                 // 没植物 → 走撤销分支
 *    8: aload_1
 *    9: invokevirtual  Player.m_150110_:()Lnet/minecraft/world/entity/player/Abilities;   // getAbilities()
 *   12: getfield       Abilities.f_35936_:Z                     // mayfly(读)
 *   15: ifne           30                 // 已经能飞 → 跳过授予(不动别人的值)
 *   18: aload_1
 *   19: invokevirtual  Player.m_150110_:()Lnet/minecraft/world/entity/player/Abilities;
 *   22: iconst_1
 *   23: putfield       Abilities.f_35936_:Z                     // ★ 唯一的"授予"写点:mayfly = true
 *   26: aload_1
 *   27: invokevirtual  Player.m_6885_:()V                       // onUpdateAbilities()
 *   30: flyMap.put(player, 5)                                   // 5 tick 宽限期计数
 *   ...
 *  103: (计数器 == 1 时)撤销:
 *  123: invokevirtual  Player.m_7500_:()Z                       // isCreative() → 创造模式直接跳过
 *  131: getfield / 134: iconst_0 / 135: putfield Abilities.f_35936_:Z    // mayfly = false
 *  141: getfield / 143: iconst_0 / 143: putfield Abilities.f_35935_:Z    // flying = false  ← 踢下飞行
 *  146: invokevirtual  Player.m_6885_:()V                       // onUpdateAbilities()
 *  160: flyMap.put(player, 0)
 *  208: return
 * </pre>
 *
 * <p>结论(按字节码,不猜):</p>
 * <ol>
 *   <li>授予飞行<b>不是</b>调用 helper,就是一条 {@code PUT_FIELD Abilities.mayfly(Z)};读-判-写-同步四步。
 *       所以 {@code @At(FIELD, opcode = PUT_FIELD)} 是唯一精确的注入点;</li>
 *   <li>{@code handleFlight} 里对 {@code mayfly} 的写入一共<b>两处</b>(0x17 授予 true、0x87 撤销 false),
 *       对 {@code flying} 一处(0x8f)、{@code onUpdateAbilities()} 两处(0x1b、0x92)。
 *       一个 {@code @Redirect} 会同时接管同类写入的全部出现位置,正好把"授予"和"扩展自己的撤销"一起管住;</li>
 *   <li>授予那一段外面还有 {@code if (mayfly) skip} 的守卫——玩家本来就能飞(创造/旁观/其它模组)时扩展
 *       本来就不写,我们的拦截只可能作用在"本来就没有飞行"的玩家身上。</li>
 * </ol>
 *
 * <h2>为什么用 SRG 名 + {@code remap = false}</h2>
 * <p>本工程没有 mixin gradle 插件、发布的 jar 里也没有 refmap(已核对 {@code enigmaticcurio-1.1.0.jar}),
 * 所以 {@code @At} 的 target 串在运行期是<b>原样使用</b>的。扩展是第三方模组,发布 jar 里对 MC 成员的引用
 * 已经是 SRG 名({@code f_35936_} = mayfly、{@code f_35935_} = flying、{@code m_6885_} = onUpdateAbilities,
 * 见 {@code srg_to_official_1.20.1.tsrg}),因此这里直接写 SRG 名,和工程里
 * {@code EnchantmentHelperSweepMixin} / {@code RsiJeiInventoryEntryMixin} 的做法一致。
 * 写成官方名反而在运行期对不上(没有任何东西会去 remap 它)。</p>
 *
 * <h2>为什么这样不可能误伤别的飞行来源</h2>
 * <p>开关关闭 且 玩家戴着/带着复苏之叶 时,本 mixin 让 {@code handleFlight} 对玩家能力的<b>所有</b>写入
 * ({@code mayfly}、{@code flying}、{@code onUpdateAbilities()})全部跳过;开关打开时三条 {@code @Redirect}
 * 原样转调,扩展行为与未装本模组时逐指令一致。我们既不记录也不比较"是不是自己给的",
 * 因为我们已经<b>没有撤销这个动作</b>了——别人的 {@code mayfly} 我们从来没写过,自然踩不掉。</p>
 *
 * <p>{@code require = 0}:扩展缺席或将来改了这个方法时,注入静默不生效(退回扩展原行为),不会让游戏崩。</p>
 */
@Mixin(auviotre.enigmatic.addon.contents.items.RevivalLeaf.class)
public class RevivalLeafFlightMixin {

    /** HEAD:把"本次调用是否被开关压制"算出来并记住(供下面三个 {@code @Redirect} 读)。 */
    @Inject(method = "handleFlight", at = @At("HEAD"), remap = false, require = 0)
    private void enigmaticcurio$enter(Player player, CallbackInfo ci) {
        RevivalLeafHandler.enterHandleFlight(player);
    }

    /** RETURN:清掉本次记录(不撤销任何东西)。 */
    @Inject(method = "handleFlight", at = @At("RETURN"), remap = false, require = 0)
    private void enigmaticcurio$exit(Player player, CallbackInfo ci) {
        RevivalLeafHandler.exitHandleFlight();
    }

    /**
     * 拦住 {@code mayfly} 的写入(两处:0x17 授予 {@code true}、0x87 撤销 {@code false})。
     * <p>开关关闭 → 一条都不写,玩家的 {@code mayfly} 保持原样(谁给的就是谁的)。</p>
     */
    @Redirect(method = "handleFlight",
        at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lnet/minecraft/world/entity/player/Abilities;f_35936_:Z"),
        remap = false, require = 0)
    private void enigmaticcurio$mayfly(Abilities abilities, boolean value) {
        if (RevivalLeafHandler.suppressed()) {
            return;
        }
        abilities.mayfly = value;
    }

    /**
     * 拦住 {@code flying} 的写入(0x8f,扩展自己撤销时会顺手把玩家踢下飞行)。
     * <p>开关关闭 → 不写,正在飞的人不会因为这条指令掉下去。</p>
     */
    @Redirect(method = "handleFlight",
        at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
            target = "Lnet/minecraft/world/entity/player/Abilities;f_35935_:Z"),
        remap = false, require = 0)
    private void enigmaticcurio$flying(Abilities abilities, boolean value) {
        if (RevivalLeafHandler.suppressed()) {
            return;
        }
        abilities.flying = value;
    }

    /**
     * 拦住能力同步包(0x1b、0x92 的 {@code onUpdateAbilities()})。
     * <p>开关关闭 → 不发包,免得把别处刚设好的飞行状态用一包旧能力顶掉。</p>
     */
    @Redirect(method = "handleFlight",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;m_6885_()V"),
        remap = false, require = 0)
    private void enigmaticcurio$onUpdateAbilities(Player player) {
        if (RevivalLeafHandler.suppressed()) {
            return;
        }
        player.onUpdateAbilities();
    }
}
