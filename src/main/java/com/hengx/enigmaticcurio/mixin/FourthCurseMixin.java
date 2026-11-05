package com.hengx.enigmaticcurio.mixin;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.aizistral.enigmaticlegacy.handlers.EnigmaticEventHandler;
import com.aizistral.enigmaticlegacy.items.CursedRing;
import com.aizistral.omniconfig.wrappers.Omniconfig;
import com.hengx.enigmaticcurio.DebugLog;
import com.hengx.enigmaticcurio.FourthCurseFix;
import com.hengx.enigmaticcurio.FourthCurseNeutral;

import net.minecraftforge.event.entity.living.LivingHurtEvent;

/**
 * 七咒之戒「第四重咒」修正的注入点(神秘遗物本体侧)。
 *
 * <p>反编译依据({@code com.aizistral.enigmaticlegacy.handlers.EnigmaticEventHandler},
 * Enigmaticlegacy 2.30.1 字节码):{@code onEntityHurt(LivingHurtEvent)} 里,
 * 受击者是 {@code Monster}/{@code EnderDragon}、攻击者是玩家且 {@code SuperpositionHandler.isTheCursedOne} 时:</p>
 * <pre>
 * if (!bypass)                                            // 主手 倒转之启/无止之言/邪术之锅 时 bypass=true
 *     event.setAmount(event.getAmount()
 *         * CursedRing.monsterDamageDebuff.getValue().asModifierInverted());   // &lt;= 就是这一处字段读取
 * </pre>
 *
 * <p>该字段在这个方法里只被读一次(整个类里也只有这一处),所以用
 * {@code @At(value = "FIELD", opcode = Opcodes.GETSTATIC)} 作为**唯一且带语义**的注入锚点
 * (不用 ordinal,也不会误伤别的效果;Mixin 0.8.5 不接受 {@code @At("GETSTATIC")}),把它的值换成"修正后的惩罚参数":</p>
 * <ul>
 *   <li>条件满足 → 返回 {@code new PerhapsParameter(0)}(系数 1.0F ⇒ 完全不削减);</li>
 *   <li>条件不满足 → 返回本体自己的 {@code CursedRing.monsterDamageDebuff},行为与不打这个补丁**逐指令一致**;</li>
 *   <li>本体自己的 bypass(主手拿那三件)根本不会走到这次读取,所以不存在"重复抵消";</li>
 *   <li>{@code require = 0, expect = 0}:万一将来本体改了这里,注入静默不生效而不是让游戏崩。</li>
 * </ul>
 *
 * <p>之所以用"换掉那一次字段读取"而不是"事件里再把伤害乘回去":本体自己的绕过逻辑
 * 与扩展的补偿逻辑(见 {@code FourthCurseAddonMixin})都会乘/除同一个系数,
 * 事件层反算会出现重复抵消(把伤害翻倍)。只替换这一次读取是唯一精确、且只影响第四重咒的做法。</p>
 */
@Mixin(EnigmaticEventHandler.class)
public class FourthCurseMixin {

    @Inject(method = "onEntityHurt(Lnet/minecraftforge/event/entity/living/LivingHurtEvent;)V",
        at = @At("HEAD"), remap = false)
    private void enigmaticcurio$decideFourthCurse(LivingHurtEvent event, CallbackInfo ci) {
        try {
            FourthCurseFix.decide(event);
            if (FourthCurseFix.neutralizing()) {
                DebugLog.info("[第四重咒] 三书齐于书袋且佩戴七咒之戒 → 本次对怪物的伤害惩罚已修正");
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 失败放行:按本体原逻辑计算
        }
    }

    @Redirect(method = "onEntityHurt(Lnet/minecraftforge/event/entity/living/LivingHurtEvent;)V",
        at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
            target = "Lcom/aizistral/enigmaticlegacy/items/CursedRing;monsterDamageDebuff:Lcom/aizistral/omniconfig/wrappers/Omniconfig$PerhapsParameter;"),
        remap = false, require = 0, expect = 0)
    private Omniconfig.PerhapsParameter enigmaticcurio$fourthCurseDebuff() {
        try {
            return FourthCurseFix.neutralizing() ? FourthCurseNeutral.parameter() : CursedRing.monsterDamageDebuff;
        }
        catch (RuntimeException | LinkageError e) {
            return CursedRing.monsterDamageDebuff;
        }
    }
}
