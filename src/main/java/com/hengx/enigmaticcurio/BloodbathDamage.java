package com.hengx.enigmaticcurio;

/**
 * 「血战沙场之证」假血条的**伤害分配算术**。
 *
 * <p>纯函数、不 import 任何 Minecraft 类 —— 这样这段"最不能算错"的算术可以直接在
 * JVM 里单独跑(见 {@code tools/BloodbathDamageCheck.java}),不用起游戏。</p>
 *
 * <h2>为什么需要它</h2>
 * <p>1.20.1 里**伤害吸收(absorption / 金心)是在 {@code Player#actuallyHurt} 里扣的**,
 * 而 {@code LivingHurtEvent} 是在这个方法<b>最开头</b>被 {@code ForgeHooks.onLivingHurt}
 * 触发的。假血条方案要 {@code setCanceled(true)} 来拦住真血,一 cancel 原版那段吸收代码就
 * 再也跑不到 —— 这就是"戴着勋章金心永远不消耗"的根因。所以吸收必须由我们自己按原版**同样的
 * 顺序**在这之前扣掉:吸收 → 假血 → 真血。</p>
 *
 * <p>分配规则:</p>
 * <ul>
 *   <li>{@code absorbed = min(absorption, amount)} —— 金心最多只能吃下"这一下"的全部伤害;</li>
 *   <li>{@code overflow = amount - absorbed} —— 只有金心吃不完的部分才轮到假血条;</li>
 *   <li>假血扣完若 {@code ≤ 0} 才真正死亡(锁血语义不变)。</li>
 * </ul>
 */
public final class BloodbathDamage {

    /** 一次伤害在「金心(absorption)→ 假血条」之间的分配结果。 */
    public static final class Split {

        /** 被金心吃掉的伤害(要从 absorption 里减掉)。 */
        public final float absorbed;

        /** 穿透金心、需要从假血条里扣的伤害。 */
        public final float overflow;

        Split(float absorbed, float overflow) {
            this.absorbed = absorbed;
            this.overflow = overflow;
        }

        @Override
        public String toString() {
            return "Split{absorbed=" + this.absorbed + ", overflow=" + this.overflow + '}';
        }
    }

    private BloodbathDamage() {
    }

    /**
     * 把一次伤害拆成"金心吃掉的部分"和"要记到假血条上的部分"。
     *
     * <p>非法输入(非正数 / NaN / 无穷)一律当成"没有伤害":{@code absorbed = 0},
     * {@code overflow = 0},调用方不要做任何扣减,交给原版。</p>
     */
    public static Split split(float absorption, float amount) {
        if (!(amount > 0.0F) || !Float.isFinite(amount)) {
            return new Split(0.0F, 0.0F);
        }
        float absorbable = Float.isFinite(absorption) ? Math.max(0.0F, absorption) : 0.0F;
        float absorbed = Math.min(absorbable, amount);
        return new Split(absorbed, amount - absorbed);
    }

    /** 假血条扣掉 {@code overflow} 之后会不会死(假血 ≤ 0 才真死)。 */
    public static boolean isFatal(float fakeHealth, float overflow) {
        return fakeHealth - overflow <= 0.0F;
    }

    /** 扣完之后的假血条(夹在 0 以上,NBT 里绝不写负数)。 */
    public static float after(float fakeHealth, float overflow) {
        return Math.max(0.0F, fakeHealth - overflow);
    }
}
