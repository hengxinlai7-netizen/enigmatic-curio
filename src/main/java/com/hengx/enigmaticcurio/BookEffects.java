package com.hengx.enigmaticcurio;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 「书袋里这本书到底有没有加成、加成是什么」的**统一注册表**(1.1.9 新增)。
 *
 * <p>移植自 {@code enigmatic-enhance 4.8.4} 的 {@code BookEffects}(文本与判定逐条照抄),
 * 只保留**本模组运行环境里真实存在**的书:神秘遗物本体 5 本、神秘遗物扩展 4 本、诡厄遗物 8 本。
 * 主模组自建的那 7 件物品({@code bedtime_reading} / {@code history_mist} / {@code spirit_light} /
 * {@code styx_water} / {@code war_fog} / {@code bottled_sun} / {@code fishing_haul_bottle})
 * 本模组**不新增物品**,那些条目在这里没有意义,已全部剔除。</p>
 *
 * <h2>为什么要有这个类</h2>
 * 1.1.8 之前 {@link BagHudEffects#collect(Player)} 里是一条 9 分支的 switch,而且对
 * 《野猎指南》《生灵颂词》《虚伪审判》《血腥狩猎手册》只写了 {@code (条目)} 之类的占位文案。
 * 这里把「书 id → 若干条效果文本」集中成一张表,新增书只改这张表 + lang。</p>
 *
 * <h2>判定「在袋是否生效」的依据(逐条反编译核对,沿用主模组 4.8.4 报告)</h2>
 * <ul>
 * <li><b>神秘遗物本体</b>:效果代码用 {@code SuperpositionHandler.hasItem(player, book)} 判定。
 *     扩展的 {@code MixinSuperpositionHandler#hasItem} 在返回 false 时会再查一次
 *     {@code SuperAddonHandler#findBookInBag},而 {@code findBookInBag} 只要求「书袋在身 + 该物品在袋里」;
 *     书本身能否放进袋子由 {@code AntiqueBag.isBook(stack)} 决定 —— 本体/扩展的书
 *     **全部**写进了各自 jar 的 {@code data/minecraft/tags/items/bookshelf_books.json},
 *     而 {@code isBook} 正是 {@code extraBookList.contains(id) || stack.is(ItemTags.BOOKSHELF_BOOKS)},
 *     所以它们在袋里时 {@code hasItem} 为真 ⇒ **在袋生效**。</li>
 * <li><b>《启示之证》《倒转之启》《无止之言》</b>还额外被扩展直接
 *     {@code findBookInBag(...)} 判定(AddonEventHandler#onLivingHurt),是最硬的一路。</li>
 * <li><b>诡厄遗物</b>:被动由 {@code PassiveBookHandler#onPlayerTick} 驱动,它扫的是
 *     {@code InventoryQueryUtils.collectItemsFromInventoryAndBookBag → GoldenBookPassives.collect},
 *     内部通过 {@code com.qoocies.goeticlegacy.utils.enigmaticaddons.EnigmaticAddonsBookBagHelper}
 *     **显式遍历古旧书袋的 {@code AntiqueBagCapability.INVENTORY}} ⇒ 诡厄的书在袋里**确实会 tick 被动**。</li>
 * </ul>
 *
 * <h2>数值来源</h2>
 * 优先读运行时配置(本体/扩展的 Omniconfig、诡厄的 ForgeConfigSpec),读不到退回代码里的默认值。
 * 每条文本的出处写在 {@link #PROVIDERS} 的注释里。**本类只读,不改变任何物品/书袋的行为。**
 */
public final class BookEffects {

    /** 一条效果:语言键 + 参数(参数在客户端格式化,保证换语言/换数字都正确)。 */
    public record Line(String key, String[] args) {}

    /** 给一本书算效果行(书不在袋里不会被调用)。 */
    @FunctionalInterface
    private interface Provider {
        List<Line> lines(Player player, ItemStack stack);
    }

    /** 未收录(但确实是诡厄被动书)一行。 */
    public static final String KEY_UNKNOWN = "baghud.book.unknown_passive";

    /** 可存放但无任何加成一行。 */
    public static final String KEY_NO_BONUS = "baghud.book.no_bonus";

    private BookEffects() {}

    // 配置所在类(反射读取;类名写在这里是因为 static 块要用,必须声明在它前面)
    private static final String HUNTER = "com.aizistral.enigmaticlegacy.items.HunterGuidebook";
    private static final String BLESS = "auviotre.enigmatic.addon.contents.items.TheBless";
    private static final String ODE = "auviotre.enigmatic.addon.contents.items.OdeToLiving";
    private static final String SANG = "auviotre.enigmatic.addon.contents.items.SanguinaryHandbook";
    private static final String GOETIC_CONFIG = "com.qoocies.goeticlegacy.Config";

    // ------------------------------------------------------------------ 注册表

    private static final Map<String, Provider> PROVIDERS = new LinkedHashMap<>();

    /** 语言键:baghud.book.<namespace>.<path>.line<N>。 */
    public static String lineKey(String bookId, int line) {
        return "baghud.book." + bookId.replace(':', '.') + ".line" + line;
    }

    private static void put(String bookId, Provider provider) {
        PROVIDERS.put(bookId, provider);
    }

    private static Line line(String bookId, int index, Object... args) {
        String[] text = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            text[i] = args[i] instanceof Double || args[i] instanceof Float
                ? number(((Number) args[i]).doubleValue())
                : String.valueOf(args[i]);
        }
        return new Line(lineKey(bookId, index), text);
    }

    /** 定值文本(无参数)。 */
    private static Line text(String bookId, int index) {
        return new Line(lineKey(bookId, index), new String[0]);
    }

    static {
        // ---------------------------------------------------------- 神秘遗物本体

        // AddonEventHandler#onLivingHurt:findBookInBag(THE_ACKNOWLEDGMENT) -> victim.setSecondsOnFire(3)
        put("enigmaticlegacy:the_acknowledgment", (p, s) -> List.of(line("enigmaticlegacy:the_acknowledgment", 1, 3)));

        // 同上:ModifyDamageBaseOne(event, 0.1f) —— 该方法体是 amount * (1 + m)
        put("enigmaticlegacy:the_twist", (p, s) -> List.of(line("enigmaticlegacy:the_twist", 1, 10)));

        // 同上:0.2f;另 AddonEventHandler#onLivingHurt(吸血那段) lifesteal += amount * 0.1f
        put("enigmaticlegacy:the_infinitum", (p, s) -> List.of(
            line("enigmaticlegacy:the_infinitum", 1, 20),
            line("enigmaticlegacy:the_infinitum", 2, 10)));

        // EnigmaticEventHandler#onLivingHurt:TamableAnimal 受伤 -> 取消并转移给 owner;
        // 半径 = HunterGuidebook.effectiveDistance;若 owner 也有 ANIMAL_GUIDEBOOK,
        // 转移伤害 *= HunterGuidebook.synergyDamageReduction.asModifierInverted()
        put("enigmaticlegacy:hunter_guidebook", (p, s) -> List.of(
            line("enigmaticlegacy:hunter_guidebook", 1, paramInt(HUNTER, "effectiveDistance", 24)),
            line("enigmaticlegacy:hunter_guidebook", 2, paramPercent(HUNTER, "synergyDamageReduction", 50))));

        // EnigmaticEventHandler#onLivingHurt:攻击 Animal 时,若 hasItem(ANIMAL_GUIDEBOOK) 且
        // PetGuidebook#isProtectedAnimal -> 取消;另 CursedRing 的"第二重咒"中立改动
        put("enigmaticlegacy:animal_guidebook", (p, s) -> List.of(
            text("enigmaticlegacy:animal_guidebook", 1),
            text("enigmaticlegacy:animal_guidebook", 2),
            line("enigmaticlegacy:animal_guidebook", 3, paramPercent(HUNTER, "synergyDamageReduction", 50))));

        // ---------------------------------------------------------- 神秘遗物扩展

        // AddonEventHandler#onLivingTick -> player.clearFire();
        // AddonEventHandler#onLivingHurt -> player.invulnerableTime = TheBless.invulnerableTime
        put("enigmaticaddons:the_bless", (p, s) -> List.of(
            text("enigmaticaddons:the_bless", 1),
            line("enigmaticaddons:the_bless", 2, paramInt(BLESS, "invulnerableTime", 40))));

        // OdeToLiving#isProtectedAnimal(getItem 失败后走 findBookInBag)+ AddonEventHandler#onLivingHurt
        // + AddonEventHandler#onLivingChangeTarget(LivingChangeTargetEvent 取消)
        put("enigmaticaddons:ode_to_living", (p, s) -> List.of(
            text("enigmaticaddons:ode_to_living", 1),
            text("enigmaticaddons:ode_to_living", 2),
            line("enigmaticaddons:ode_to_living", 3, paramPercent(ODE, "synergyDamageReduction", 75))));

        // MixinPlayer#hurtMix / MixinLivingEntity#hurtMix:isTheCursedOne && hasItem(FALSE_JUSTICE)
        // -> 把 DamageSource 换成 EnigmaticAddonDamageTypes.FALSE_JUSTICE
        put("enigmaticaddons:false_justice", (p, s) -> List.of(
            text("enigmaticaddons:false_justice", 1)));

        // AddonEventHandler#onLivingHurt(宠物伤害加成)+ #onLivingHurt(宠物继承主人效果)
        put("enigmaticaddons:sanguinary_handbook", (p, s) -> List.of(
            line("enigmaticaddons:sanguinary_handbook", 1, paramPercentOfDouble(SANG, "DamageMultiplier", 0.25)),
            text("enigmaticaddons:sanguinary_handbook", 2)));

        // ---------------------------------------------------------- 诡厄遗物(软依赖,只按 id 匹配)

        // WindBookItem#onLivingFall:context(player) 存在 -> event.setCanceled(true)
        put("goeticlegacy:gale_tome", (p, s) -> List.of(
            text("goeticlegacy:gale_tome", 1)));

        // BygoneBookItem#onPassiveTick -> MAGIC_DAMAGE_MULTIPLIER + BooksConfig.antiquityMagicDamageBonus
        // BygoneBookItem#onChangeTarget -> 下界敌对生物生命 <= 玩家生命 * antiquityNeutralHealthThreshold
        put("goeticlegacy:antiquity_folio", (p, s) -> List.of(
            line("goeticlegacy:antiquity_folio", 1, goetic("antiquityMagicDamageBonus", 0.15) * 100.0D),
            line("goeticlegacy:antiquity_folio", 2, goetic("antiquityNeutralHealthThreshold", 2.5D))));

        // VizierBookEffects:恼鬼聚晶 -> 折磨仆从(vizierTormentorBaseCount / vizierTormentorStaffExtraMax)
        put("goeticlegacy:one_thousand_and_one_nights", (p, s) -> List.of(
            line("goeticlegacy:one_thousand_and_one_nights", 1,
                goeticInt("vizierTormentorBaseCount", 3), goeticInt("vizierTormentorStaffExtraMax", 2))));

        // PeepingBookEffects:攻击时按概率触发虚空震荡弹/波(peeperVoidBombChance / peeperVoidShockChance / ...ExtraRadius)
        put("goeticlegacy:the_peeper", (p, s) -> List.of(
            line("goeticlegacy:the_peeper", 1,
                goetic("peeperVoidBombChance", 0.12D) * 100.0D,
                goetic("peeperVoidShockChance", 0.3D) * 100.0D,
                goetic("peeperVoidShockExtraRadius", 0.4D))));

        // DivinationBookEffects:巫毒之诅咒每级承伤加成(seerMarkDamagePerLevel)
        put("goeticlegacy:the_seer_almanack", (p, s) -> List.of(
            line("goeticlegacy:the_seer_almanack", 1, goetic("seerMarkDamagePerLevel", 0.25D) * 100.0D)));

        // GodfatherBookItem:刌民仆从训练速度 + 新刌民紫袍强化概率(illWillTrainSpeedMultiplier / illWillMagicChance)
        put("goeticlegacy:ill_will", (p, s) -> List.of(
            line("goeticlegacy:ill_will", 1, goeticInt("illWillTrainSpeedMultiplier", 3)),
            line("goeticlegacy:ill_will", 2, goetic("illWillMagicChance", 0.6D) * 100.0D)));

        // PatriarchBookEffects:狱炎伤害倍率 + 目标最大生命附加(patriarchHellfireMultiplier / patriarchMaxHealthDamage)
        put("goeticlegacy:the_unholy_writ", (p, s) -> List.of(
            line("goeticlegacy:the_unholy_writ", 1, goetic("patriarchHellfireMultiplier", 2.0D)),
            line("goeticlegacy:the_unholy_writ", 2, goetic("patriarchMaxHealthDamage", 0.015D) * 100.0D)));

        // SpiderSpecimenEffects / SpiderSpecimenBookItem:荒野法术与仆从加成 + 蜘蛛中立阈值
        put("goeticlegacy:spider_specimen_book", (p, s) -> List.of(
            line("goeticlegacy:spider_specimen_book", 1, goetic("spiderSpellDamage", 0.5D) * 100.0D),
            line("goeticlegacy:spider_specimen_book", 2,
                goetic("spiderServantDamage", 0.5D) * 100.0D,
                goetic("spiderServantResistance", 0.25D) * 100.0D),
            line("goeticlegacy:spider_specimen_book", 3, goetic("spiderNeutralHealthThreshold", 2.5D))));
    }

    // ------------------------------------------------------------------ 对外查询

    /** 这本书有没有登记效果(不判断是否在袋里)。 */
    public static boolean hasEffect(String bookId) {
        return bookId != null && PROVIDERS.containsKey(bookId);
    }

    /** 这本书的效果行;未登记返回 {@code null}(调用方决定用兜底文案)。 */
    public static List<Line> linesFor(Player player, String bookId) {
        Provider provider = bookId == null ? null : PROVIDERS.get(bookId);
        if (provider == null) {
            return null;
        }
        try {
            return provider.lines(player, ItemStack.EMPTY);
        }
        catch (Throwable t) {
            return List.of();
        }
    }

    /** 已登记的书 id(自检/报告用,顺序固定)。 */
    public static List<String> knownIds() {
        return new ArrayList<>(PROVIDERS.keySet());
    }

    // ------------------------------------------------------------------ 数字格式

    /** 数字文本:整数不带小数点,小数最多两位并去掉尾零(2.5 / 0.4 / 3 / 50)。 */
    public static String number(double value) {
        if (Math.abs(value - Math.rint(value)) < 1.0E-9D) {
            return String.valueOf((long) Math.rint(value));
        }
        String text = String.format(Locale.ROOT, "%.2f", value);
        int end = text.length();
        while (end > 1 && text.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 1 && text.charAt(end - 1) == '.') {
            end--;
        }
        return text.substring(0, end);
    }

    // ------------------------------------------------------------------ 配置读取(缺模组/缺字段安全返回默认值)

    private static final Map<String, Object> PARAM_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Object MISSING = new Object();

    /** 取 {@code <owner>.<field>} 这个静态配置参数对象(缓存,取不到记 MISSING)。 */
    private static Object param(String owner, String field) {
        String key = owner + "#" + field;
        Object cached = PARAM_CACHE.get(key);
        if (cached != null) {
            return cached == MISSING ? null : cached;
        }
        Object value = null;
        try {
            Field f = Class.forName(owner, false, BookEffects.class.getClassLoader()).getField(field);
            value = f.get(null);
        }
        catch (Throwable ignored) {
            value = null;
        }
        PARAM_CACHE.put(key, value == null ? MISSING : value);
        return value;
    }

    /** Omniconfig.IntParameter#getValue() → int。 */
    private static int paramInt(String owner, String field, int fallback) {
        Object parameter = param(owner, field);
        if (parameter == null) {
            return fallback;
        }
        try {
            Method getValue = parameter.getClass().getMethod("getValue");
            Object value = getValue.invoke(parameter);
            return value instanceof Number n ? n.intValue() : fallback;
        }
        catch (Throwable ignored) {
            return fallback;
        }
    }

    /** Omniconfig.PerhapsParameter#getValue() → Perhaps#asPercentage() → int(%)。 */
    private static int paramPercent(String owner, String field, int fallback) {
        Object parameter = param(owner, field);
        if (parameter == null) {
            return fallback;
        }
        try {
            Object perhaps = parameter.getClass().getMethod("getValue").invoke(parameter);
            Object percent = perhaps.getClass().getMethod("asPercentage").invoke(perhaps);
            return percent instanceof Number n ? n.intValue() : fallback;
        }
        catch (Throwable ignored) {
            return fallback;
        }
    }

    /** Omniconfig.DoubleParameter#getValue() → double,按 ×100 显示成百分比。 */
    private static int paramPercentOfDouble(String owner, String field, double fallback) {
        Object parameter = param(owner, field);
        double value = fallback;
        if (parameter != null) {
            try {
                Object raw = parameter.getClass().getMethod("getValue").invoke(parameter);
                if (raw instanceof Number n) {
                    value = n.doubleValue();
                }
            }
            catch (Throwable ignored) {
                value = fallback;
            }
        }
        return (int) Math.round(value * 100.0D);
    }

    /** 诡厄 {@code Config.BOOKS.<field>} 的 ForgeConfigSpec.DoubleValue#get()。 */
    private static double goetic(String field, double fallback) {
        Object spec = goeticValue(field);
        if (spec == null) {
            return fallback;
        }
        try {
            Object raw = spec.getClass().getMethod("get").invoke(spec);
            return raw instanceof Number n ? n.doubleValue() : fallback;
        }
        catch (Throwable ignored) {
            return fallback; // 配置还没加载:用代码默认值
        }
    }

    private static int goeticInt(String field, int fallback) {
        Object spec = goeticValue(field);
        if (spec == null) {
            return fallback;
        }
        try {
            Object raw = spec.getClass().getMethod("get").invoke(spec);
            return raw instanceof Number n ? n.intValue() : fallback;
        }
        catch (Throwable ignored) {
            return fallback;
        }
    }

    private static Object goeticBooks;
    private static boolean goeticResolved;

    private static Object goeticValue(String field) {
        if (!goeticResolved) {
            goeticResolved = true;
            try {
                Class<?> config = Class.forName(GOETIC_CONFIG, false, BookEffects.class.getClassLoader());
                goeticBooks = config.getField("BOOKS").get(null);
            }
            catch (Throwable ignored) {
                goeticBooks = null;
            }
        }
        if (goeticBooks == null) {
            return null;
        }
        try {
            return goeticBooks.getClass().getField(field).get(goeticBooks);
        }
        catch (Throwable ignored) {
            return null;
        }
    }
}
