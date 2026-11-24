package com.hengx.enigmaticcurio;

/**
 * 「救赎之戒能否戴上」的纯判定逻辑(无客户端依赖,可在服务端自检里断言)。
 *
 * <h2>为什么单独抽出来</h2>
 * 扩展的 {@code BlessRing#canEquip} 在<b>佩戴瞬间</b>被调用,此时救赎之戒<b>还没戴上</b>,
 * 所以任何"两戒都在身上"的判据必然为 false(1.1.10-beta1 的 bug 就出在这里:
 * {@code RingHandler.wearingBoth} 恒 false ⇒ 永远不放行)。
 *
 * <p>正确判据与 enhance 4.10.0 一致:只看<b>玩家身上是否带着七咒之戒</b>,外加本模组的总开关。</p>
 */
public final class BlessRingRules {

    private BlessRingRules() {}

    /**
     * 是否放行救赎之戒的佩戴。
     *
     * @param enabled   本模组的「救赎之戒增强」总开关({@code RingHandler.enabled()})
     * @param hasCursed 玩家身上是否带着七咒之戒(Curios 槽或背包)
     */
    public static boolean canEquip(boolean enabled, boolean hasCursed) {
        return enabled && hasCursed;
    }
}
