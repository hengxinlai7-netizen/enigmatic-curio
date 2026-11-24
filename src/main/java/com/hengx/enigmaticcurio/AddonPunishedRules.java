package com.hengx.enigmaticcurio;

/**
 * 「抑制扩展惩罚态」的纯判定逻辑(无客户端依赖,可在服务端自检里直接断言)。
 *
 * <h2>背景</h2>
 * 神秘遗物扩展的 {@code auviotre.enigmatic.addon.handlers.SuperAddonHandler#isPunishedOne(LivingEntity)}
 * 是「两戒同戴 = 处于惩罚态」的唯一判据。惩罚态下扩展会每帧覆盖原版光照贴图(lightmap,
 * 见其 {@code MixinLightTexture}),在现代化 UI(ModernUI) 环境里会让字体图集与实体/方块纹理采样异常 ——
 * 表现为界面文字全部消失、方块与实体纹理变黑。
 *
 * <p>本模组本来就允许两戒同戴并拦截惩罚执行,所以在这个前提下惩罚态应当恒为 false:
 * 从判据源头抑制一次,渲染与惩罚两条链路就一起解决了。</p>
 */
public final class AddonPunishedRules {

    private AddonPunishedRules() {}

    /**
     * 是否要把「惩罚态」判成 false。
     *
     * <p>三个条件同时成立才抑制:<b>补丁开关开</b>、<b>玩家允许同戴</b>(本模组的救赎之戒增强开关)、
     * <b>两枚戒指都在身上</b>。任一不满足都保持扩展原判定。</p>
     */
    public static boolean shouldSuppressPunished(boolean patchEnabled, boolean allowBoth,
                                                 boolean hasBless, boolean hasCursed) {
        return patchEnabled && allowBoth && hasBless && hasCursed;
    }
}
