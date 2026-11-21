package com.hengx.enigmaticcurio;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 古旧书袋里"袋内每一本书 + 它的加成文本"(**服务端计算**,客户端只显示)。
 *
 * <p>1.1.9 起与主模组 {@code enigmatic-enhance 4.8.4} 的书袋显示重构对齐:</p>
 * <ol>
 *   <li><b>枚举袋内全部书</b>({@link BookBag#booksInBag(Player)}),白名单只决定文案 ——
 *       能放进袋子但没效果的书也会被列出来,标「仅可存放,无加成」;</li>
 *   <li>书 → 效果文本的注册表在 {@link BookEffects}(只收录本模组运行环境里真实存在的
 *       本体 / 扩展 / 诡厄书,主模组自建的 7 件物品不收录);</li>
 *   <li>排序:有效果的在前 → 未收录其次 → 无加成最后,段内按书 id 稳定排序;同一 id 只算一条;</li>
 *   <li>显示:属性名本地化、乘法转百分比、tick 转秒、超 {@link #MAX_LINES} 条截断。</li>
 * </ol>
 *
 * <h2>魔法石英花</h2>
 * 石英花的随机属性**不在物品 NBT 里结算**,而是由扩展把"玩家 → 当前修饰符集合 → 剩余 tick"登记在
 * {@code auviotre.enigmatic.addon.contents.items.ArtificialFlower.PLAYER_ATTRIBUTE_MAP} 里
 * (onTick 每 tick 把 Integer 减 1,≤1 时从玩家属性表移除)。这里在**服务端**用反射只读它,
 * 把每条修饰符拆成一行。
 *
 * <p><b>本类只在服务端算</b>({@link #collect(Player)});送到客户端的路径有两条(见
 * {@link BagHudSync}):①服务端把结果写进书袋物品 NBT({@link #NBT_KEY}),客户端用
 * {@link #read(ItemStack)} 读;②服务端再推一份 S2C 包,客户端存进 {@link BagHudClient}。
 * 两条解析共用 {@link #toLines}/{@link #parseLines},格式不会跑偏。</p>
 */
public final class BagHudEffects {

    /** 一条数据:哪本书、什么效果(翻译键)、数值(可空)、当前是否满足前置条件、细分内容。 */
    public record Entry(String bookId, String effectKey, String value, boolean active, String detail) {

        public Entry(String bookId, String effectKey, String value, boolean active) {
            this(bookId, effectKey, value, active, "");
        }
    }

    private static final String ADDONS = "enigmaticaddons:";

    /** 所有条目翻译键的前缀(书本身的文案走 {@code baghud.book.*},见 {@link BookEffects})。 */
    public static final String KEY_PREFIX = "tooltip.enigmaticcurio.bag.";

    /** 魔法石英花的物品 id。 */
    public static final String FLOWER_ID = ADDONS + "artificial_flower";

    /** 石英花"没有激活属性"那一行。 */
    public static final String FLOWER_KEY = KEY_PREFIX + "effect.artificial_flower";

    /** 石英花"某一条激活属性"那一行(参数:属性名、数值、类型、剩余时长)。 */
    public static final String FLOWER_ATTR_KEY = KEY_PREFIX + "effect.artificial_flower_attr";

    /** 属性修饰符类型 → 语言键(后缀是 operation.name() 的小写)。 */
    private static final String FLOWER_OP_KEY = KEY_PREFIX + "flower_op.";

    /** 三书齐:第四重咒中和。 */
    public static final String FOURTH_CURSE_KEY = KEY_PREFIX + "effect.fourth_curse";

    /** 扩展里登记"玩家当前石英花属性"的类 + 字段名(反编译确认)。 */
    private static final String FLOWER_CLASS = "auviotre.enigmatic.addon.contents.items.ArtificialFlower";
    private static final String FLOWER_FIELD = "PLAYER_ATTRIBUTE_MAP";

    /** 书袋物品自身 NBT:本模组写进去的效果缓存键。 */
    public static final String NBT_KEY = "enigmaticcurio:BagEffects";

    /** 参数分隔符(Unit Separator):不会出现在任何 id/数字里,客户端按它切回参数数组。 */
    public static final String ARG_SEPARATOR = "\u001F";

    /** 1.1.9:tooltip 里最多显示多少条(多出来的折成"还有 N 条")。 */
    public static final int MAX_LINES = 12;

    /** 诡厄遗物的 modid(只用于 {@code ModList.isLoaded},不 import 它的任何类)。 */
    public static final String GOETIC_MODID = "goeticlegacy";

    /** 空结果(无书袋的玩家每个 tick 都走这里,不再每次 new 一个 ArrayList)。 */
    private static final List<Entry> NO_ENTRIES = List.of();

    /** 清单里的物品 id(给报告/自检用;书袋枚举本身不依赖它)。 */
    public static List<String> bookIds() {
        return BookEffects.knownIds();
    }

    private BagHudEffects() {
    }

    // ------------------------------------------------------------------ 服务端:收集

    /** 服务端:算出这名玩家书袋里**所有书**的条目(有效果的写效果,没效果的写「无加成」)。 */
    public static List<Entry> collect(Player player) {
        if (player == null) {
            return NO_ENTRIES;
        }
        // 书袋不在(身上 + 末影箱)时,能力里可能还留着旧内容 —— 与扩展 findBookInBag 的前置判定一致。
        if (!BookBag.hasBag(player)) {
            return NO_ENTRIES;
        }
        List<Entry> out = new ArrayList<>(16);

        // 1.1.9:直接枚举袋子里的每个物品(而不是按 9 个 id 逐个问"在不在袋里")。
        // 这样"能放进去但没效果"的书也能被列出来(见 BookEffects 的判定依据)。
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (ItemStack stack : BookBag.booksInBag(player)) {
            if (stack.isEmpty()) {
                continue;
            }
            Item item = stack.getItem();
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id == null) {
                continue;
            }
            String bookId = id.toString();
            if (!seen.add(bookId)) {
                continue; // 同一个 id 放了两本:只算一条(去重)
            }
            try {
                collectOne(player, bookId, stack, out);
            }
            catch (RuntimeException | LinkageError e) {
                // 单本书算失败绝不拖垮整份清单(否则整条链路会静默罢工)
                DebugLog.warn("[书袋] 统计 {} 失败,已跳过: {}", bookId, e.toString());
            }
        }

        // 本模组的"三书齐修正第四重咒":不属于任何一本物品,单独一条
        if (FourthCurseFix.active(player)) {
            out.add(new Entry("enigmaticcurio:fourth_curse", FOURTH_CURSE_KEY, "", true));
        }
        if (out.isEmpty()) {
            return NO_ENTRIES;
        }
        sortEntries(out);
        return out;
    }

    /** 单本书 → 若干条目(1.1.9 抽出,方便阅读与自检)。 */
    private static void collectOne(Player player, String bookId, ItemStack stack, List<Entry> out) {
        switch (bookId) {
            case FLOWER_ID -> out.addAll(artificialFlower(player));
            default -> {
                List<BookEffects.Line> lines = BookEffects.linesFor(player, bookId);
                if (lines != null && !lines.isEmpty()) {
                    for (BookEffects.Line line : lines) {
                        out.add(new Entry(bookId, line.key(), joinArgs(line.args()), true));
                    }
                }
                else if (lines != null) {
                    // 已登记但当前条件下没有可显示的内容:按"无加成"处理,避免出现空行
                    out.add(new Entry(bookId, BookEffects.KEY_NO_BONUS, "", true));
                }
                else if (isGoeticPassiveBook(stack)) {
                    // 诡厄的书、但我们没登记文案:如实说"未收录",而不是谎称没效果
                    out.add(new Entry(bookId, BookEffects.KEY_UNKNOWN, "", true));
                }
                else {
                    out.add(new Entry(bookId, BookEffects.KEY_NO_BONUS, "", true));
                }
            }
        }
    }

    /** 参数用 Unit Separator 拼进 {@code value}(客户端按它切回数组)。 */
    private static String joinArgs(String[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        return String.join(ARG_SEPARATOR, args);
    }

    /** 条目排序:有效果的在前、未收录其次、无加成最后;组内按书 id 稳定排序。 */
    private static void sortEntries(List<Entry> entries) {
        entries.sort(Comparator
            .comparingInt((Entry e) -> rankOf(e.effectKey()))
            .thenComparing(Entry::bookId));
    }

    private static int rankOf(String effectKey) {
        if (BookEffects.KEY_NO_BONUS.equals(effectKey)) {
            return 2;
        }
        if (BookEffects.KEY_UNKNOWN.equals(effectKey)) {
            return 1;
        }
        return 0;
    }

    /**
     * 1.1.9:超过 {@link #MAX_LINES} 条时,"还有 N 条"那一行用哪个语言键。
     * <p>剩下的全是「无加成」→ {@code tooltip.enigmaticcurio.bag.more.no_bonus};
     * 只要还剩一条有效果的,就用通用的 {@code ...bag.more}。
     * 抽成纯函数是为了能在服务端自检里直接断言(客户端 tooltip 只在客户端跑)。</p>
     */
    public static String moreKey(List<Entry> entries, int shown) {
        if (entries == null) {
            return KEY_PREFIX + "more";
        }
        for (int i = Math.max(0, shown); i < entries.size(); i++) {
            if (!BookEffects.KEY_NO_BONUS.equals(entries.get(i).effectKey())) {
                return KEY_PREFIX + "more";
            }
        }
        return KEY_PREFIX + "more.no_bonus";
    }

    /** 诡厄的被动书(反射判断,缺模组时恒 false)。 */
    public static boolean isGoeticPassiveBook(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Class<?> api = goeticPassiveApi();
        if (api == null) {
            return false;
        }
        try {
            return api.isInstance(stack.getItem());
        }
        catch (Throwable t) {
            return false;
        }
    }

    private static Class<?> goeticPassiveApi;
    private static boolean goeticResolved;

    private static synchronized Class<?> goeticPassiveApi() {
        if (goeticResolved) {
            return goeticPassiveApi;
        }
        goeticResolved = true;
        if (!ModList.get().isLoaded(GOETIC_MODID)) {
            return null;
        }
        try {
            goeticPassiveApi = Class.forName("com.qoocies.goeticlegacy.api.passive.IPassiveBook",
                false, BagHudEffects.class.getClassLoader());
        }
        catch (Throwable t) {
            goeticPassiveApi = null;
        }
        return goeticPassiveApi;
    }

    // ------------------------------------------------------------------ 魔法石英花

    /**
     * 读扩展的 {@code ArtificialFlower.PLAYER_ATTRIBUTE_MAP},把该玩家当前生效的属性修饰符拆成条目。
     * <p>只在服务端调用。反射缺类/缺字段/非 ServerPlayer ⇒ 返回"无激活属性"那一行,绝不抛异常。</p>
     */
    public static List<Entry> artificialFlower(Player player) {
        List<Entry> out = new ArrayList<>();
        if (player instanceof ServerPlayer serverPlayer) {
            try {
                Map<?, ?> perPlayer = playerAttributeMap(serverPlayer);
                if (perPlayer != null) {
                    for (Map.Entry<?, ?> entry : perPlayer.entrySet()) {
                        if (!(entry.getKey() instanceof com.google.common.collect.Multimap<?, ?> modifiers)) {
                            continue;
                        }
                        int remaining = entry.getValue() instanceof Integer integer ? integer : 0;
                        for (Map.Entry<?, ?> modifier : modifiers.entries()) {
                            if (!(modifier.getKey() instanceof Attribute attribute)
                                || !(modifier.getValue() instanceof AttributeModifier value)) {
                                continue;
                            }
                            ResourceLocation id = ForgeRegistries.ATTRIBUTES.getKey(attribute);
                            out.add(new Entry(FLOWER_ID, FLOWER_ATTR_KEY,
                                id == null ? "unknown" : id.toString(), true,
                                flowerAmountText(value.getAmount(), value.getOperation()) + "|"
                                    + value.getOperation().name().toLowerCase(Locale.ROOT) + "|" + remaining));
                        }
                    }
                }
            }
            catch (Throwable t) {
                if (!flowerReadFailed) {
                    flowerReadFailed = true;
                    DebugLog.warn("[书袋] 读 {}#{} 失败(石英花属性将显示为无): {}", FLOWER_CLASS, FLOWER_FIELD,
                        t.toString());
                }
            }
        }
        if (out.isEmpty()) {
            return List.of(new Entry(FLOWER_ID, FLOWER_KEY, "", false));
        }
        // Map 的迭代顺序不稳定(HashMap):排一次序,保证签名逐 tick 一致。
        out.sort(Comparator.comparing(Entry::value).thenComparing(Entry::detail));
        return out;
    }

    private static Map<?, ?> playerAttributeMap(ServerPlayer player) throws ReflectiveOperationException {
        java.lang.reflect.Field field = flowerField();
        if (field == null) {
            return null;
        }
        Object map = field.get(null);
        if (!(map instanceof Map<?, ?> all)) {
            return null;
        }
        Object perPlayer = all.get(player);
        return perPlayer instanceof Map<?, ?> entries ? entries : null;
    }

    private static java.lang.reflect.Field flowerField;
    private static boolean flowerResolved;
    private static boolean flowerReadFailed;

    private static synchronized java.lang.reflect.Field flowerField() {
        if (flowerResolved) {
            return flowerField;
        }
        flowerResolved = true;
        try {
            flowerField = Class.forName(FLOWER_CLASS, false, BagHudEffects.class.getClassLoader())
                .getField(FLOWER_FIELD);
        }
        catch (Throwable t) {
            flowerField = null;   // 扩展缺失:石英花也不可能在袋里,显示"无激活属性"
        }
        return flowerField;
    }

    // ------------------------------------------------------------------ 显示

    /** 一条条目的显示形式(客户端 tooltip 用)。 */
    public static MutableComponent display(Entry entry) {
        if (FLOWER_ATTR_KEY.equals(entry.effectKey())) {
            // detail = "数值|operation|剩余tick";4.6.0 起手写扫描(不再每帧编译正则)
            String detail = entry.detail();
            int first = detail.indexOf('|');
            int second = first < 0 ? -1 : detail.indexOf('|', first + 1);
            String amount = first < 0 ? detail : detail.substring(0, first);
            String operation = first < 0 ? ""
                : (second < 0 ? detail.substring(first + 1) : detail.substring(first + 1, second));
            String remaining = second < 0 ? "" : detail.substring(second + 1);
            // 1.1.9:属性名本地化(原来是原始 id,如 goety:spell_potency)
            return Component.translatable(FLOWER_ATTR_KEY, attributeName(entry.value()), amount,
                Component.translatable(FLOWER_OP_KEY + operation), duration(remaining));
        }

        // 1.1.9:统一的书本效果行(baghud.book.<ns>.<path>.lineN),参数放在 value 里
        if (entry.effectKey().startsWith("baghud.book.")) {
            if (BookEffects.KEY_NO_BONUS.equals(entry.effectKey())
                || BookEffects.KEY_UNKNOWN.equals(entry.effectKey())) {
                return Component.translatable(entry.effectKey(), bookName(entry.bookId()));
            }
            String[] args = splitArgs(entry.value());
            Object[] formatted = new Object[args.length];
            System.arraycopy(args, 0, formatted, 0, args.length);
            return Component.translatable(entry.effectKey(), formatted);
        }

        if (entry.value() == null || entry.value().isEmpty()) {
            return Component.translatable(entry.effectKey());
        }
        return Component.translatable(entry.effectKey(), entry.value());
    }

    /** 属性 id → 本地化名称;取不到就退回原始 id(绝不抛)。 */
    public static Component attributeName(String attributeId) {
        try {
            Attribute attribute = ForgeRegistries.ATTRIBUTES.getValue(new ResourceLocation(attributeId));
            if (attribute != null) {
                return Component.translatable(attribute.getDescriptionId());
            }
        }
        catch (RuntimeException ignored) {
            // 保留字面量
        }
        return Component.literal(attributeId);
    }

    /** 书 id → 物品显示名;取不到退回 id。 */
    public static Component bookName(String bookId) {
        try {
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(bookId));
            if (item != null && item != net.minecraft.world.item.Items.AIR) {
                return item.getDescription();
            }
        }
        catch (RuntimeException ignored) {
            // 保留字面量
        }
        return Component.literal(bookId);
    }

    /** {@code "a\u001Fb\u001Fc"} → 参数数组(空串 → 空数组)。 */
    static String[] splitArgs(String value) {
        if (value == null || value.isEmpty()) {
            return EMPTY_ARGS;
        }
        return value.split(ARG_SEPARATOR, -1);
    }

    private static final String[] EMPTY_ARGS = new String[0];

    /**
     * 剩余时长:不足 20 tick 打 tick,≥20 tick 打秒(一位小数)。
     * <p>1.1.9:原来一律打 "N tick",20 秒会显示成 "400 tick"。文本走 lang,数字在这边算。</p>
     */
    public static Component duration(String remainingText) {
        int ticks;
        try {
            ticks = (int) Double.parseDouble(remainingText.trim());
        }
        catch (RuntimeException ignored) {
            return Component.literal(remainingText);
        }
        if (ticks < 20) {
            return Component.translatable("baghud.time.ticks", String.valueOf(ticks));
        }
        String seconds = String.format(Locale.ROOT, "%.1f", ticks / 20.0D);
        return Component.translatable("baghud.time.seconds", seconds);
    }

    /**
     * 1.1.9:石英花修饰符数值的显示形式。
     * <ul>
     * <li>{@code MULTIPLY_BASE}/{@code MULTIPLY_TOTAL}:0.1 表示 +10% ⇒ 打成 {@code +10%};</li>
     * <li>{@code ADDITION}:保留两位小数并带符号 ⇒ {@code +20.00} / {@code -0.50}。</li>
     * </ul>
     */
    static String flowerAmountText(double amount, AttributeModifier.Operation operation) {
        if (operation == AttributeModifier.Operation.MULTIPLY_BASE
            || operation == AttributeModifier.Operation.MULTIPLY_TOTAL) {
            double percent = amount * 100.0D;
            String text = BookEffects.number(percent);
            return (percent >= 0.0D ? "+" : "") + text + "%";
        }
        return String.format(Locale.ROOT, "%+.2f", amount);
    }

    // ------------------------------------------------------------------ 签名

    /**
     * 内容签名:物品 + 效果 + 数值 + 生效状态(变化才同步)。
     * 石英花属性行额外并入 amount + operation,但<b>剩余 tick 故意不进签名</b>
     * —— 该值每 tick 都变,放进来会导致每秒都往物品 NBT 里写一次。
     */
    public static String signature(List<Entry> entries) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            sb.append(e.bookId()).append('|').append(e.effectKey()).append('|')
                .append(e.value()).append('|').append(e.active());
            if (FLOWER_ATTR_KEY.equals(e.effectKey())) {
                String[] parts = e.detail().split("\\|", 3);
                sb.append('|').append(parts.length > 0 ? parts[0] : "")
                    .append('|').append(parts.length > 1 ? parts[1] : "");
            }
            sb.append(';');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 物品 NBT 缓存(替代主模组的网络包)

    /** 条目 → 字符串列表(每条 5 段用 | 分隔,detail 里允许再带 |)。NBT 与网络包共用同一份格式。 */
    public static java.util.List<String> toLines(List<Entry> entries) {
        java.util.List<String> lines = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            lines.add(e.bookId() + "|" + e.effectKey() + "|" + e.value() + "|" + e.active() + "|"
                + e.detail());
        }
        return lines;
    }

    /** 字符串列表 → 条目(坏的条目直接跳过,绝不抛异常)。 */
    public static List<Entry> parseLines(java.util.List<String> lines) {
        List<Entry> out = new ArrayList<>();
        if (lines == null) {
            return out;
        }
        for (String line : lines) {
            try {
                if (line == null) {
                    continue;
                }
                String[] parts = line.split("\\|", 5);
                if (parts.length < 4) {
                    continue;
                }
                out.add(new Entry(parts[0], parts[1], parts[2], Boolean.parseBoolean(parts[3]),
                    parts.length > 4 ? parts[4] : ""));
            }
            catch (RuntimeException ignored) {
                // 单条坏了就跳过
            }
        }
        return out;
    }

    /** 条目 → NBT 列表(每条一个字符串,5 段用 | 分隔,detail 里允许再带 |)。 */
    public static ListTag write(List<Entry> entries) {
        ListTag list = new ListTag();
        for (String line : toLines(entries)) {
            list.add(StringTag.valueOf(line));
        }
        return list;
    }

    /** 这叠书袋上有没有本模组写过的缓存(没有 = 服务器还没同步过)。 */
    public static boolean hasCache(ItemStack bagStack) {
        try {
            if (bagStack == null || bagStack.isEmpty() || !bagStack.hasTag()) {
                return false;
            }
            CompoundTag tag = bagStack.getTag();
            return tag != null && tag.contains(NBT_KEY, Tag.TAG_LIST);
        }
        catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /** 从书袋物品的 NBT 里读回条目(客户端 tooltip 用;没有缓存 → 空表)。 */
    public static List<Entry> read(ItemStack bagStack) {
        List<Entry> out = new ArrayList<>();
        try {
            if (bagStack == null || bagStack.isEmpty() || !bagStack.hasTag()) {
                return out;
            }
            CompoundTag tag = bagStack.getTag();
            if (tag == null || !tag.contains(NBT_KEY, Tag.TAG_LIST)) {
                return out;
            }
            ListTag list = tag.getList(NBT_KEY, Tag.TAG_STRING);
            java.util.List<String> lines = new ArrayList<>(list.size());
            for (int i = 0; i < list.size(); i++) {
                lines.add(list.getString(i));
            }
            out.addAll(parseLines(lines));
        }
        catch (RuntimeException | LinkageError ignored) {
            // 缓存坏了就当没有
        }
        return out;
    }

    /**
     * 写进书袋物品的 NBT;内容没变就不碰(避免无谓的同步)。
     *
     * <p>注意:「内容没变」必须是**键存在且值相同**才算 —— 之前只看值,导致<b>空效果列表</b>
     * 永远不会被写进物品(空 ListTag 与「没有这个键」取出来都是空列表),客户端于是永远认为
     * 「服务器没同步过」。这里补上 {@code contains} 判断。</p>
     */
    public static boolean store(ItemStack bagStack, List<Entry> entries) {
        try {
            if (bagStack == null || bagStack.isEmpty()) {
                return false;
            }
            ListTag list = write(entries);
            CompoundTag tag = bagStack.getOrCreateTag();
            boolean present = tag.contains(NBT_KEY, Tag.TAG_LIST);
            if (present && list.equals(tag.getList(NBT_KEY, Tag.TAG_STRING))) {
                return false;
            }
            tag.put(NBT_KEY, list);
            return true;
        }
        catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }
}
