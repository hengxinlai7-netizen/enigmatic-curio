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
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;

/**
 * 「万钧之护」的雷击开关(扩展侧)。
 *
 * <p>反编译依据(enigmaticaddons 1.2.5 / 1.2.6 / 1.2.7,{@code AddonEventHandler}):</p>
 * <pre>
 * // ① onEntityHurt(累积)
 * if (hasCurio(攻击者, THUNDER_SCROLL)) {                       // 戴着万钧之护
 *     if (攻击者.getMainHandItem().canPerformAction(SWORD_SWEEP)) {   // 主手是剑
 *         int electric = 目标.getPersistentData().getInt("Electric");
 *         目标.getPersistentData().putInt("Electric",
 *             electric + 60 + rand(80) + (int)(event.getAmount() * 10.0F));   // &lt;= 就是这一处 putInt
 *     }
 *     event.setAmount(ThunderScroll.modify(目标, event.getAmount()));   // 护甲穿透,没碰
 * }
 *
 * // ② onTick(落雷)
 * int electric = data.getInt("Electric");
 * if (electric &gt; 1200) { 创建 LightningBolt(带 "HarmlessThunder" 标签) -&gt; addFreshEntity }
 * </pre>
 *
 * <h2>1.1.9:两段都要拦</h2>
 * <p>1.1.8 只在 ① 处拦了 {@code putInt},② 处<b>没有任何开关判定</b>。于是目标身上只要还有电荷
 * (关开关之前累积的、别的路径写进去的、旧存档带过来的)就会照常落雷 —— 也就是
 * 「关闭雷击还是能打出来」。</p>
 * <ul>
 *   <li>① {@code onEntityHurt} HEAD 先算判定({@link ScrollHandler#decideCharge}),再用
 *       {@code @Redirect} 包住方法体内所有 {@code CompoundTag.putInt(String,int)}:key 是
 *       {@code Electric} 且不允许时<b>不写(并且把已有的电荷清掉)</b>,其余 key 原样透传 ——
 *       不依赖 ordinal,也不受其它模组注入顺序影响;</li>
 *   <li>② {@code onTick} HEAD 调 {@link ScrollHandler#gateStrike}:电荷过 1200、马上要劈雷的那一刻
 *       再核一次开关,关了就直接清零电荷、放弃这次落雷。这是真正把"关了就不落雷"钉死的地方;</li>
 *   <li>护甲穿透的 {@code setAmount} 在 if 之外,依旧生效 —— 关掉雷击不会顺带关掉别的东西。</li>
 * </ul>
 *
 * <p>两个注入点<b>都不改变开关开着时的原行为</b>:允许时 {@code putInt} 原值写入,
 * 拦不拦只看配置和这一件卷轴的 NBT。</p>
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
                // 不累积。1.1.9 起还要把目标身上<已有的>电荷一起清掉:
                // 光"这一击不加"留着旧电荷照样会落雷,那就是玩家报告的那个 bug。
                tag.remove(key);
                return;
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 失败放行
        }
        tag.putInt(key, value);
    }

    /**
     * 落雷闸:扩展 {@code onTick} 里 {@code Electric > 1200} 就 {@code addFreshEntity(LightningBolt)}。
     * 这里在方法最开始先让本模组表个态 —— 开关关着就把电荷清零,扩展随后读到 0,自然不落雷。
     */
    @Inject(method = "onTick(Lnet/minecraftforge/event/entity/living/LivingEvent$LivingTickEvent;)V",
        at = @At("HEAD"), remap = false)
    private void enigmaticcurio$gateStrike(LivingEvent.LivingTickEvent event, CallbackInfo ci) {
        try {
            ScrollHandler.gateStrike(event.getEntity());
        }
        catch (RuntimeException | LinkageError ignored) {
            // 失败放行:落雷照旧走扩展逻辑
        }
    }
}
