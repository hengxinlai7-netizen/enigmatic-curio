package com.hengx.enigmaticcurio.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import auviotre.enigmatic.addon.handlers.AddonEventHandler;
import com.hengx.enigmaticcurio.ScrollHandler;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

/**
 * 「万钧之护」的雷击开关(扩展侧)。
 *
 * <p>反编译依据(enigmaticaddons 1.2.5 / 1.2.6,{@code AddonEventHandler#onEntityHurt}):</p>
 * <pre>
 * if (hasCurio(攻击者, THUNDER_SCROLL)) {                       // 戴着万钧之护
 *     if (攻击者.getMainHandItem().canPerformAction(SWORD_SWEEP)) {   // 主手是剑
 *         int electric = 目标.getPersistentData().getInt("Electric");
 *         目标.getPersistentData().putInt("Electric",
 *             electric + 60 + rand(80) + (int)(event.getAmount() * 10.0F));   // &lt;= 就是这一处 putInt
 *     }
 *     event.setAmount(ThunderScroll.modify(目标, event.getAmount()));   // 护甲穿透,没碰
 * }
 * </pre>
 *
 * <p>于是:</p>
 * <ul>
 *   <li>HEAD 处先算好"这次命中是否允许给目标累积电荷"({@link ScrollHandler#decideCharge});
 *       该判定同时看<b>全局配置</b>({@code scaleEnabled} / {@code scaleLightning})和<b>这一件卷轴自己的 NBT</b>
 *       {@code enigmaticcurio:thunderOff}(Ctrl + 左键 切换,见 {@link ItemToggleHandler});</li>
 *   <li>再用 {@code @Redirect} 包住方法体内**所有** {@code CompoundTag.putInt(String,int)} 调用,
 *       只对 key = {@code Electric} 且本次不允许时跳过写入(等价于"不累积"),
 *       其余 key 原样透传 —— 所以不依赖 ordinal,也不受其它模组注入顺序变化影响;</li>
 *   <li>护甲穿透的 {@code setAmount} 在 if 之外,依旧生效,关掉雷击不会顺带关掉别的东西。</li>
 * </ul>
 *
 * <p>关掉雷击后目标永远累积不到 {@code onTick} 里的 1200 阈值,自然不会再落雷。</p>
 */
@Mixin(AddonEventHandler.class)
public class AddonThunderLightningMixin {

    @Inject(method = "onEntityHurt(Lnet/minecraftforge/event/entity/living/LivingHurtEvent;)V",
        at = @At("HEAD"), remap = false)
    private void enigmaticcurio$decideLightningCharge(LivingHurtEvent event, CallbackInfo ci) {
        try {
            LivingEntity attacker = event.getSource().getEntity() instanceof LivingEntity living ? living : null;
            ScrollHandler.decideCharge(attacker);
        }
        catch (RuntimeException | LinkageError ignored) {
            // 失败放行:按扩展原逻辑累积电荷
        }
    }

    @Redirect(method = "onEntityHurt(Lnet/minecraftforge/event/entity/living/LivingHurtEvent;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/nbt/CompoundTag;m_128405_(Ljava/lang/String;I)V"),
        remap = false)
    private void enigmaticcurio$gateElectric(CompoundTag tag, String key, int value) {
        try {
            if (ScrollHandler.ELECTRIC.equals(key) && !ScrollHandler.chargeAllowed()) {
                return;   // 不写 = 电荷保持原值,即"这一击不累积"
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 失败放行
        }
        tag.putInt(key, value);
    }
}
