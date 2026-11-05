package com.hengx.enigmaticcurio;

import java.lang.reflect.Method;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 「七咒之戒第四重咒」的修正(纯**额外效果**,不改动/不拦截那三本书自身的任何作用)。
 *
 * <h2>第四重咒是哪一条</h2>
 * 神秘遗物本体自己的书页 {@code book.enigmaticlegacy.entry.cursed_ring.page.4} 明确编号:
 * <pre>第四重咒 —— 削弱你对怪物的攻击力量</pre>
 * 实现方式是事件而非属性:{@code EnigmaticEventHandler#onEntityHurt(LivingHurtEvent)} 里,
 * 受害者是 {@code Monster}/{@code EnderDragon}、攻击者是玩家且 {@code SuperpositionHandler.isTheCursedOne} 时,
 * {@code event.setAmount(amount * CursedRing.monsterDamageDebuff.getValue().asModifierInverted())}
 * (默认 50 ⇒ ×0.5);主手拿着 倒转之启/无止之言/邪术之锅 时本体自己会跳过这一步。
 *
 * <h2>修正条件</h2>
 * 总开关开 + 佩戴七咒之戒(curio)+ 古旧书袋在玩家身上 + 袋内**同时**有
 * 倒转之启({@code enigmaticlegacy:the_twist})、恩惠之典({@code enigmaticaddons:the_bless})、
 * 无止之言({@code enigmaticlegacy:the_infinitum})。
 * 任一条不满足时立刻回到原版(本类不保留任何状态,判定是每次事件即时算的)。
 *
 * <h2>修正方式</h2>
 * 只把"第四重咒的那个惩罚系数"换成中性值(残留 0% ⇒ 系数 1.0),见
 * {@code mixin.FourthCurseMixin} / {@code mixin.FourthCurseAddonMixin}:
 * 它们拦的是同一个 {@code CursedRing.monsterDamageDebuff} 字段读取,其余一切
 * (第一重咒的受伤加倍、第三重咒的护甲削减、三本书各自的在袋效果……)都不碰。
 */
public final class FourthCurseFix {

    /** 倒转之启(神秘遗物本体)。 */
    public static final ResourceLocation THE_TWIST = new ResourceLocation("enigmaticlegacy", "the_twist");

    /** 恩惠之典(神秘遗物扩展)。 */
    public static final ResourceLocation THE_BLESS = new ResourceLocation("enigmaticaddons", "the_bless");

    /** 无止之言(神秘遗物本体)。 */
    public static final ResourceLocation THE_INFINITUM = new ResourceLocation("enigmaticlegacy", "the_infinitum");

    /** 七咒之戒(神秘遗物本体)。 */
    public static final ResourceLocation CURSED_RING = new ResourceLocation("enigmaticlegacy", "cursed_ring");

    /**
     * 本次 {@code LivingHurtEvent} 处理期间"是否把第四重咒的惩罚换成修正值"。
     * 在 HEAD 注入处写、在同一方法里读取字段的地方读,同一次调用内完成;ThreadLocal 保证线程安全。
     */
    private static final ThreadLocal<Boolean> NEUTRALIZE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static Method findBookInBag;
    private static boolean bookLookupResolved;

    private FourthCurseFix() {
    }

    /** HEAD 注入调用:根据这次事件算好修正与否。 */
    public static void decide(LivingHurtEvent event) {
        try {
            NEUTRALIZE.set(active(attacker(event)));
        }
        catch (RuntimeException | LinkageError e) {
            NEUTRALIZE.set(Boolean.FALSE);
        }
    }

    /** 字段读取注入调用:是否用修正值。 */
    public static boolean neutralizing() {
        try {
            return NEUTRALIZE.get();
        }
        catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 本次事件的攻击者(没有玩家攻击者时为 null)。 */
    public static Player attacker(LivingHurtEvent event) {
        return event.getSource().getEntity() instanceof Player player ? player : null;
    }

    /** 完整生效条件(每次即时计算,不缓存任何状态)。 */
    public static boolean active(Player player) {
        if (player == null) {
            return false;
        }
        try {
            if (!CurioConfig.FOURTH_CURSE_FIX.get()) {
                return false;
            }
            Item ring = Superposition.item(CURSED_RING.toString());
            if (ring == null || !Superposition.hasCurio(player, ring)) {
                return false;   // 没戴七咒之戒
            }
            return hasBook(player, THE_TWIST) && hasBook(player, THE_BLESS) && hasBook(player, THE_INFINITUM);
        }
        catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** 修正后仍然保留的惩罚百分比(固定 0 = 完全不削减)。 */
    public static int residualPercent() {
        return 0;
    }

    // ------------------------------------------------------------------ 古旧书袋

    /**
     * 该物品是否在玩家的古旧书袋里。直接复用扩展自己的只读查询
     * {@code auviotre.enigmatic.addon.handlers.SuperAddonHandler#findBookInBag(Player, Item)}
     * (它先要求玩家身上有书袋,再查玩家身上的书袋 capability),所以"书袋在身上 + 书在袋里"的判定
     * 与扩展的口径完全一致,也对书袋/书没有任何写入。
     */
    private static boolean hasBook(Player player, ResourceLocation itemId) {
        try {
            Item item = ForgeRegistries.ITEMS.getValue(itemId);
            if (item == null) {
                return false;
            }
            Method method = bookLookup();
            if (method == null) {
                return false;
            }
            Object result = method.invoke(null, player, item);
            return result instanceof ItemStack stack && !stack.isEmpty();
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static synchronized Method bookLookup() {
        if (bookLookupResolved) {
            return findBookInBag;
        }
        bookLookupResolved = true;
        try {
            Class<?> handler = Class.forName("auviotre.enigmatic.addon.handlers.SuperAddonHandler",
                false, FourthCurseFix.class.getClassLoader());
            findBookInBag = handler.getMethod("findBookInBag", Player.class, Item.class);
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            findBookInBag = null;
        }
        return findBookInBag;
    }
}
