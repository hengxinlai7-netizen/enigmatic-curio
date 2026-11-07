package com.hengx.enigmaticcurio;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * 「锻造者宝石」对<b>多词条附魔书</b>的升级算法。
 *
 * <p>语义:两本书<b>都拥有、且等级相同</b>的每一个附魔,全部升到它在当前环境里的上限;
 * 只在一本书里出现、或两本等级不同的附魔<b>一律不动</b>(保持左槽原样)。</p>
 *
 * <p>本类刻意不引用任何 Minecraft 类型:只吃 {@code Map<附魔id, 等级>} 与一个
 * "取上限" 的函数,方便用 {@code java} 直接跑离线校验
 * (见 {@code src/test/java/com/hengx/enigmaticcurio/UpgradePlanCheck.java})。</p>
 */
public final class UpgradePlan {

    private UpgradePlan() {
    }

    /**
     * 求「可升级集合」:两本书都拥有、且等级相同的附魔 → 当前等级。
     *
     * <p>迭代顺序跟随 {@code a}(左槽),便于日志稳定输出。不修改入参,返回值永远可写。</p>
     *
     * @param a 左槽书的附魔表(id → 等级),允许为 {@code null} / 空
     * @param b 右槽书的附魔表(id → 等级),允许为 {@code null} / 空
     * @return 可升级集合;没有任何共同同级附魔时返回空表(不是 {@code null})
     */
    public static Map<String, Integer> candidates(Map<String, Integer> a, Map<String, Integer> b) {
        Map<String, Integer> common = new LinkedHashMap<>();
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return common;
        }
        for (Map.Entry<String, Integer> entry : a.entrySet()) {
            String id = entry.getKey();
            Integer level = entry.getValue();
            if (id == null || level == null) {
                continue;
            }
            Integer other = b.get(id);
            // 只在右槽也有、且等级完全相同的时候才算"可升级"
            if (other != null && other.intValue() == level.intValue()) {
                common.put(id, level);
            }
        }
        return common;
    }

    /**
     * 在可升级集合上套用上限:当前等级 &lt; 上限的 → 写上限;已经满级的不进结果。
     *
     * @param candidates    可升级集合({@link #candidates} 的返回值)
     * @param maxLevelOf    附魔 id → 上限(调用方给"原版上限与神化上限取大")
     * @return id → 目标等级;全部已满级时返回空表
     */
    public static Map<String, Integer> applyMax(Map<String, Integer> candidates,
                                                ToIntFunction<String> maxLevelOf) {
        Map<String, Integer> plan = new LinkedHashMap<>();
        if (candidates == null || candidates.isEmpty() || maxLevelOf == null) {
            return plan;
        }
        for (Map.Entry<String, Integer> entry : candidates.entrySet()) {
            Integer level = entry.getValue();
            if (level == null) {
                continue;
            }
            int target = maxLevelOf.applyAsInt(entry.getKey());
            if (target > level) {
                plan.put(entry.getKey(), target);
            }
        }
        return plan;
    }

    /**
     * 一步求"最终要写回的目标等级":可升级集合 ∩ 未满级。
     *
     * @return id → 目标等级;没有可升级项、或全部已满级时返回空表
     */
    public static Map<String, Integer> plan(Map<String, Integer> a,
                                            Map<String, Integer> b,
                                            ToIntFunction<String> maxLevelOf) {
        return applyMax(candidates(a, b), maxLevelOf);
    }

    /** 稳定可读的 {@code {id=lv, ...}} 描述,日志与离线校验共用。 */
    public static String describe(Map<String, Integer> levels) {
        StringBuilder sb = new StringBuilder("{");
        if (levels != null) {
            for (Map.Entry<String, Integer> entry : levels.entrySet()) {
                if (sb.length() > 1) {
                    sb.append(", ");
                }
                sb.append(entry.getKey()).append('=').append(entry.getValue());
            }
        }
        return sb.append('}').toString();
    }
}
