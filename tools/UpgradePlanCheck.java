package com.hengx.enigmaticcurio;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「锻造者宝石」多词条升级算法的<b>离线校验</b>(不启动 Minecraft,也不依赖 JUnit)。
 *
 * <p>故意不放进 {@code src/test/java}:ForgeGradle 会把 Minecraft 运行时挂到
 * {@code testRuntimeClasspath},离线环境下 {@code :test} 解析不到 {@code srgutils},
 * 会让 {@code gradle build} 失败。这里用一个能直接 {@code java} 跑的类代替,构建与校验互不影响。</p>
 *
 * <p>跑法(任意 JDK 17+):
 * <pre>
 * javac -encoding UTF-8 -d out src/main/java/com/hengx/enigmaticcurio/UpgradePlan.java tools/UpgradePlanCheck.java
 * java -cp out com.hengx.enigmaticcurio.UpgradePlanCheck
 * </pre>
 * 全部通过时退出码 0,任一条失败时退出码 1 并打印失败项。</p>
 *
 * <p>{@link UpgradePlan} 本身不引用任何 Minecraft 类型,所以这里覆盖的就是游戏内实际执行的
 * 判定/取上限逻辑;只有"读原版/神化上限"和"往 ItemStack 写 NBT"留在
 * {@code ForgerGemAnvilHandler} 里(那部分需要真实注册表,无法离线跑)。</p>
 */
public final class UpgradePlanCheck {

    private static int passed;
    private static int failed;

    private UpgradePlanCheck() {
    }

    public static void main(String[] args) {
        // ---- 多词条:两本完全相同、都低于上限 => 全部升满 ----
        run("two identical multi-enchant books both upgraded", () -> {
            Map<String, Integer> a = book("minecraft:sharpness", 5, "minecraft:unbreaking", 3);
            Map<String, Integer> b = book("minecraft:sharpness", 5, "minecraft:unbreaking", 3);
            Map<String, Integer> max = book("minecraft:sharpness", 10, "minecraft:unbreaking", 5);
            Map<String, Integer> candidates = UpgradePlan.candidates(a, b);
            eq("candidates", book("minecraft:sharpness", 5, "minecraft:unbreaking", 3), candidates);
            eq("plan", book("minecraft:sharpness", 10, "minecraft:unbreaking", 5),
                UpgradePlan.plan(a, b, max::get));
            eq("output", book("minecraft:sharpness", 10, "minecraft:unbreaking", 5), apply(a, UpgradePlan.plan(a, b, max::get)));
        });

        // ---- 边界 1:两本完全相同且全部已满级 => 不输出 ----
        run("identical books already at max => empty plan (no output)", () -> {
            Map<String, Integer> a = book("minecraft:sharpness", 5, "minecraft:unbreaking", 3);
            Map<String, Integer> b = book("minecraft:sharpness", 5, "minecraft:unbreaking", 3);
            Map<String, Integer> max = book("minecraft:sharpness", 5, "minecraft:unbreaking", 3);
            eq("candidates still non-empty", book("minecraft:sharpness", 5, "minecraft:unbreaking", 3),
                UpgradePlan.candidates(a, b));
            eq("plan empty", book(), UpgradePlan.plan(a, b, max::get));
        });

        // ---- 用户原始反馈场景:{锋利V, 耐久III} 两本,神化锋利上限 10、耐久仍是 3 ----
        run("reported case: sharpness V -> X, unbreaking stays III", () -> {
            Map<String, Integer> a = book("minecraft:sharpness", 5, "minecraft:unbreaking", 3);
            Map<String, Integer> b = book("minecraft:sharpness", 5, "minecraft:unbreaking", 3);
            // 神化:锋利 10,耐久未改 => max = 3
            Map<String, Integer> max = book("minecraft:sharpness", 10, "minecraft:unbreaking", 3);
            eq("plan", book("minecraft:sharpness", 10), UpgradePlan.plan(a, b, max::get));
            eq("output keeps unbreaking III",
                book("minecraft:sharpness", 10, "minecraft:unbreaking", 3),
                apply(a, UpgradePlan.plan(a, b, max::get)));
        });

        // ---- 边界 2:左 3 附魔,右只有其中 1 个相同 => 只升那 1 个 ----
        run("3 enchants vs 1 shared => only that one upgraded", () -> {
            Map<String, Integer> a = book("minecraft:sharpness", 4, "minecraft:unbreaking", 2, "minecraft:mending", 1);
            Map<String, Integer> b = book("minecraft:sharpness", 4);
            Map<String, Integer> max = book("minecraft:sharpness", 10);
            eq("candidates", book("minecraft:sharpness", 4), UpgradePlan.candidates(a, b));
            eq("plan", book("minecraft:sharpness", 10), UpgradePlan.plan(a, b, max::get));
            // 其它两条附魔必须原样保留(handler 里是 left.copy() 后只改 changes)
            eq("output", book("minecraft:sharpness", 10, "minecraft:unbreaking", 2, "minecraft:mending", 1),
                apply(a, UpgradePlan.plan(a, b, max::get)));
        });

        // ---- 边界 3:同名附魔但等级不同(III vs IV)=> 该附魔不动 ----
        run("same enchant different level => untouched", () -> {
            Map<String, Integer> a = book("minecraft:sharpness", 3);
            Map<String, Integer> b = book("minecraft:sharpness", 4);
            Map<String, Integer> max = book("minecraft:sharpness", 10);
            eq("candidates empty", book(), UpgradePlan.candidates(a, b));
            eq("plan empty", book(), UpgradePlan.plan(a, b, max::get));
        });

        // ---- 混合:一条同级可升,另一条同名但等级不同 => 只升第一条 ----
        run("mixed: one matches, one differs in level", () -> {
            Map<String, Integer> a = book("minecraft:sharpness", 5, "minecraft:unbreaking", 3);
            Map<String, Integer> b = book("minecraft:sharpness", 5, "minecraft:unbreaking", 4);
            Map<String, Integer> max = book("minecraft:sharpness", 10, "minecraft:unbreaking", 5);
            eq("candidates", book("minecraft:sharpness", 5), UpgradePlan.candidates(a, b));
            eq("plan", book("minecraft:sharpness", 10), UpgradePlan.plan(a, b, max::get));
            eq("output leaves unbreaking III", book("minecraft:sharpness", 10, "minecraft:unbreaking", 3),
                apply(a, UpgradePlan.plan(a, b, max::get)));
        });

        // ---- 完全没有交集 => 空(交回原版) ----
        run("disjoint enchant sets => empty", () -> {
            Map<String, Integer> a = book("minecraft:sharpness", 5);
            Map<String, Integer> b = book("minecraft:unbreaking", 5);
            eq("plan empty", book(), UpgradePlan.plan(a, b, id -> 10));
        });

        // ---- null / 空表:不抛异常,返回可写的空表 ----
        run("null and empty inputs are safe", () -> {
            eq("null/null", book(), UpgradePlan.candidates(null, null));
            eq("null/map", book(), UpgradePlan.candidates(null, book("minecraft:sharpness", 5)));
            eq("map/null", book(), UpgradePlan.candidates(book("minecraft:sharpness", 5), null));
            eq("empty/map", book(), UpgradePlan.candidates(book(), book("minecraft:sharpness", 5)));
            eq("plan null", book(), UpgradePlan.plan(null, null, id -> 10));
            eq("applyMax null candidates", book(), UpgradePlan.applyMax(null, id -> 10));
            eq("applyMax null lookup", book(), UpgradePlan.applyMax(book("minecraft:sharpness", 5), null));
        });

        // ---- 上限不高于当前等级 => 不写入(不降级) ----
        run("max <= current => no change, never downgrades", () -> {
            Map<String, Integer> a = book("minecraft:sharpness", 7);
            Map<String, Integer> b = book("minecraft:sharpness", 7);
            eq("plan empty", book(), UpgradePlan.plan(a, b, id -> 5));
            eq("equal max", book(), UpgradePlan.plan(a, b, id -> 7));
        });

        // ---- 一条满级、一条没满 => 只写没满的那条 ----
        run("one already max, one below => only below is written", () -> {
            Map<String, Integer> a = book("minecraft:sharpness", 10, "minecraft:unbreaking", 3);
            Map<String, Integer> b = book("minecraft:sharpness", 10, "minecraft:unbreaking", 3);
            Map<String, Integer> max = book("minecraft:sharpness", 10, "minecraft:unbreaking", 3);
            eq("plan empty", book(), UpgradePlan.plan(a, b, max::get));

            Map<String, Integer> max2 = book("minecraft:sharpness", 10, "minecraft:unbreaking", 5);
            eq("plan only unbreaking", book("minecraft:unbreaking", 5), UpgradePlan.plan(a, b, max2::get));
        });

        // ---- 空表 / 单条也照旧工作 ----
        run("single enchant single-level merge still works", () -> {
            Map<String, Integer> a = book("minecraft:sharpness", 4);
            Map<String, Integer> b = book("minecraft:sharpness", 4);
            eq("candidates", book("minecraft:sharpness", 4), UpgradePlan.candidates(a, b));
            eq("plan", book("minecraft:sharpness", 10), UpgradePlan.plan(a, b, id -> 10));
        });

        // ---- 顺序:跟随左槽(日志稳定) ----
        run("candidate order follows left slot", () -> {
            Map<String, Integer> a = book("minecraft:mending", 1, "minecraft:sharpness", 5);
            Map<String, Integer> b = book("minecraft:sharpness", 5, "minecraft:mending", 1);
            StringBuilder order = new StringBuilder();
            for (String id : UpgradePlan.candidates(a, b).keySet()) {
                order.append(id).append('|');
            }
            eq("order", "minecraft:mending|minecraft:sharpness|", order.toString());
        });

        // ---- 入参不被修改 ----
        run("inputs are not mutated", () -> {
            Map<String, Integer> a = book("minecraft:sharpness", 5, "minecraft:unbreaking", 3);
            Map<String, Integer> b = book("minecraft:sharpness", 5, "minecraft:unbreaking", 3);
            Map<String, Integer> max = book("minecraft:sharpness", 10, "minecraft:unbreaking", 5);
            Map<String, Integer> aCopy = new LinkedHashMap<>(a);
            Map<String, Integer> bCopy = new LinkedHashMap<>(b);
            Map<String, Integer> maxCopy = new LinkedHashMap<>(max);
            UpgradePlan.plan(a, b, max::get);
            eq("a unchanged", aCopy, a);
            eq("b unchanged", bCopy, b);
            eq("max unchanged", maxCopy, max);
        });

        // ---- 返回值可写(handler 会再读一遍) ----
        run("returned maps are mutable", () -> {
            Map<String, Integer> plan = UpgradePlan.plan(
                book("minecraft:sharpness", 5), book("minecraft:sharpness", 5), id -> 10);
            plan.put("minecraft:test", 1);
            eq("mutable", book("minecraft:sharpness", 10, "minecraft:test", 1), plan);
        });

        System.out.println();
        System.out.println("UpgradePlanCheck: passed=" + passed + ", failed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------

    /** 可变有序表:id1, lv1, id2, lv2, ... */
    private static Map<String, Integer> book(Object... idLevelPairs) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < idLevelPairs.length; i += 2) {
            map.put((String) idLevelPairs[i], (Integer) idLevelPairs[i + 1]);
        }
        return map;
    }

    /** 模拟 handler 的 "left.copy() 后只写回 upgrade 项" —— 用于断言其它附魔原样保留。 */
    private static Map<String, Integer> apply(Map<String, Integer> left, Map<String, Integer> upgrade) {
        Map<String, Integer> out = new LinkedHashMap<>(left);
        out.putAll(upgrade);
        return out;
    }

    private static void eq(String what, Object expected, Object actual) {
        if (expected.equals(actual)) {
            passed++;
            return;
        }
        failed++;
        System.out.println("FAIL " + what + ": expected=" + expected + " actual=" + actual);
    }

    private static void run(String name, Runnable body) {
        int before = failed;
        try {
            body.run();
        }
        catch (Throwable t) {
            failed++;
            System.out.println("FAIL " + name + " threw " + t);
        }
        System.out.println((failed == before ? "PASS " : "FAIL ") + name);
    }
}
