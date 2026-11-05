package com.hengx.enigmaticcurio.mixin;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import auviotre.enigmatic.addon.handlers.AddonEventHandler;
import com.aizistral.enigmaticlegacy.items.CursedRing;
import com.aizistral.omniconfig.wrappers.Omniconfig;
import com.hengx.enigmaticcurio.FourthCurseFix;
import com.hengx.enigmaticcurio.FourthCurseNeutral;

import net.minecraftforge.event.entity.living.LivingHurtEvent;

/**
 * 七咒之戒「第四重咒」修正的注入点(神秘遗物扩展侧,同一个系数的**第二处**读取)。
 *
 * <p>反编译依据({@code auviotre.enigmatic.addon.handlers.AddonEventHandler#onFirstHurt},1.2.5/1.2.6 一致):
 * 攻击者主手拿着「恩惠之典」且受击者是 {@code Monster}/{@code EnderDragon} 时:</p>
 * <pre>
 * event.setAmount(event.getAmount() / CursedRing.monsterDamageDebuff.getValue().asModifierInverted());
 * </pre>
 *
 * <p>也就是扩展自己在"手里拿着恩惠之典"时把这个惩罚**除回去**(抵消第四重咒)。
 * 如果我们只修正了本体那一次乘法(×1.0),这里就会变成 ÷0.5 = **伤害翻倍**,
 * 所以同一个修正条件成立时这里也必须换成同一个修正参数(×1.0 时 ÷1.0 = 不变),
 * 两边合起来才等于"这条诅咒完全不削减伤害"。</p>
 *
 * <p>其余情形(条件不满足)返回本体自己的参数,行为与不打补丁一致;
 * {@code require = 0, expect = 0} 保证版本漂移时静默失效而不崩。
 * 扩展自己的另一个读取点({@code ViolenceScroll#onHurt},受害方自己的公式)属于**该物品自身机制**,不在这里动。</p>
 */
@Mixin(AddonEventHandler.class)
public class FourthCurseAddonMixin {

    @Inject(method = "onFirstHurt(Lnet/minecraftforge/event/entity/living/LivingHurtEvent;)V",
        at = @At("HEAD"), remap = false)
    private void enigmaticcurio$decideFourthCurseAddon(LivingHurtEvent event, CallbackInfo ci) {
        try {
            FourthCurseFix.decide(event);
        }
        catch (RuntimeException | LinkageError ignored) {
            // 失败放行
        }
    }

    @Redirect(method = "onFirstHurt(Lnet/minecraftforge/event/entity/living/LivingHurtEvent;)V",
        at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
            target = "Lcom/aizistral/enigmaticlegacy/items/CursedRing;monsterDamageDebuff:Lcom/aizistral/omniconfig/wrappers/Omniconfig$PerhapsParameter;"),
        remap = false, require = 0, expect = 0)
    private Omniconfig.PerhapsParameter enigmaticcurio$fourthCurseDebuffAddon() {
        try {
            return FourthCurseFix.neutralizing() ? FourthCurseNeutral.parameter() : CursedRing.monsterDamageDebuff;
        }
        catch (RuntimeException | LinkageError e) {
            return CursedRing.monsterDamageDebuff;
        }
    }
}
