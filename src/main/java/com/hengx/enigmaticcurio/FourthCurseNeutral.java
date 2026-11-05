package com.hengx.enigmaticcurio;

import com.aizistral.omniconfig.wrappers.Omniconfig;

/**
 * 构造"修正后的第四重咒惩罚参数"。
 *
 * <p>本类**只在神秘遗物本体存在时才会被加载**(引用了它的 {@code Omniconfig.PerhapsParameter}),
 * 所以只由 mixin(目标类就是神秘遗物的类,缺模组时整份 mixin 配置会被跳过)引用;
 * 事件那侧只用 {@link FourthCurseFix},不会因此缺少类。</p>
 *
 * <p>本体实现:{@code Perhaps.asModifierInverted() = 1 - probability/100},因此
 * {@code new PerhapsParameter(0)} 的系数就是 1.0F = 完全不削减。</p>
 */
public final class FourthCurseNeutral {

    private static Omniconfig.PerhapsParameter neutral;

    private FourthCurseNeutral() {
    }

    /** 修正后的惩罚参数(残留比例固定为 {@link FourthCurseFix#residualPercent()})。 */
    public static Omniconfig.PerhapsParameter parameter() {
        if (neutral == null) {
            neutral = new Omniconfig.PerhapsParameter(FourthCurseFix.residualPercent());
        }
        return neutral;
    }
}
