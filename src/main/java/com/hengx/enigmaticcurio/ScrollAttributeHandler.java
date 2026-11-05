package com.hengx.enigmaticcurio;

import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import top.theillusivec4.curios.api.event.CurioAttributeModifierEvent;

/**
 * 给饰品挂属性修饰符(纯事件,不注入代码):万钧之护的 {@code generic.attack_damage} 百分比加成。
 *
 * <p>用 Curios 的 {@link CurioAttributeModifierEvent},也就是 Curios 合并
 * {@code ICurioItem#getAttributeModifiers} 的同一条管线
 * (反编译 {@code CuriosImplMixinHooks#getAttributeModifiers} →
 * {@code MinecraftForge.EVENT_BUS.post(new CurioAttributeModifierEvent(...))}),
 * 所以和神秘遗物本体/扩展自己给饰品挂属性修饰符的写法完全一致。</p>
 *
 * <p>注意:本类引用 Curios 的类,**只有 Curios 已加载时才会被注册**
 * (见 {@link EnigmaticCurio}),缺 Curios 时本类根本不会加载。</p>
 */
public final class ScrollAttributeHandler {

    public ScrollAttributeHandler() {
    }

    @SubscribeEvent
    public void onCurioAttributeModifier(CurioAttributeModifierEvent event) {
        try {
            if (ScrollHandler.attackDamageActiveFor(event.getItemStack())) {
                event.addModifier(Attributes.ATTACK_DAMAGE, ScrollHandler.attackDamageModifier());
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }
}
