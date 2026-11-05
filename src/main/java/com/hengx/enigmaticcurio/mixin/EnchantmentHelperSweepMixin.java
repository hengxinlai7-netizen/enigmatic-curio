package com.hengx.enigmaticcurio.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hengx.enigmaticcurio.ScrollHandler;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * 撤掉「万钧之护」原本的"+100% 横扫伤害"。
 *
 * <p>反编译依据:扩展的 {@code auviotre.enigmatic.addon.mixin.MixinEnchantmentHelper#getSweeping}
 * 注入 {@code EnchantmentHelper#getSweepingDamageRatio}(SRG <b>{@code m_44821_}</b>,
 * 描述符 {@code (Lnet/minecraft/world/entity/LivingEntity;)F},原版实现 = {@code level > 0 ? level/(level+1) : 0},
 * 最高 0.75)的 RETURN,对佩戴者做 {@code max(1.0F, 原值)} —— 这就是"横扫攻击造成 100% 伤害"。</p>
 *
 * <p><b>关键点 1:必须抢在它前面执行。</b>转换后的方法里两个回调按 mixin priority 升序依次调用,
 * 前一个 {@code setReturnValue} 会把这个方法直接 return、后一个不再执行。
 * 所以本 mixin 的 priority 设成 <b>500</b>(小于扩展的 1000,先应用先执行):
 * 在 RETURN 处"提前定值",扩展那次 {@code max(1.0, ...)} 便不再执行,方法直接返回此刻的原版公式值。</p>
 *
 * <p><b>关键点 2:不能反过来去改扩展的 mixin 类。</b>Mixin 0.8.5 明确拒绝
 * ({@code Cannot add target ... because the target is a mixin}),只能从目标方法这一侧下手。</p>
 *
 * <ul>
 *   <li>总开关关 / 用户选择恢复横扫加成都完全不动作,扩展原版横扫 100% 照旧;</li>
 *   <li>没戴卷轴时完全不动作;</li>
 *   <li>代价:priority 比 500 更晚的其它模组对**这一个方法**的 RETURN 回调,在戴卷轴时会被跳过。</li>
 * </ul>
 *
 * <p>卷轴的伤害来源于是换成 {@code generic.attack_damage} 的百分比修饰符
 * (见 {@code ScrollAttributeHandler} 与 {@link ScrollHandler#attackDamageModifier()})。</p>
 */
@Mixin(value = EnchantmentHelper.class, priority = 500)
public class EnchantmentHelperSweepMixin {

    @Inject(method = "m_44821_(Lnet/minecraft/world/entity/LivingEntity;)F",
        at = @At("RETURN"), cancellable = true, remap = false)
    private static void enigmaticcurio$keepVanillaSweeping(LivingEntity entity, CallbackInfoReturnable<Float> cir) {
        try {
            if (!ScrollHandler.enabled()) {
                return;   // 总开关关 = 保留扩展原版(横扫 100%)
            }
            if (ScrollHandler.restoreSweep()) {
                return;   // 用户选择"保留扩展原本的横扫加成"
            }
            if (ScrollHandler.wornScroll(entity).isEmpty()) {
                return;   // 没戴卷轴,与本功能无关
            }
            // 提前定值 = 取消后续回调,扩展的 max(1.0, ...) 不会再跑
            cir.setReturnValue(cir.getReturnValueF());
        }
        catch (RuntimeException | LinkageError ignored) {
            // 失败放行:保留扩展原本的横扫加成
        }
    }
}
