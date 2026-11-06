package com.hengx.enigmaticcurio;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
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
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 古旧书袋里"会提供加成"的物品清单 + 每条的加成文本。
 *
 * <p>移植自主模组 {@code BagHudEffects}(1.1.19/1.2.6e 版),只去掉主模组**自己新增的瓶子**
 * (瓶装历史迷雾 / 灵界之光 / 冥河之水 / 战争迷雾 / 太阳 / 渔获瓶 / 睡前读物)——
 * 本模组不新增物品,那些条目在这里没有意义。</p>
 *
 * <h2>清单依据(逐条反编译核对)</h2>
 * 两类判定方式:
 * <ul>
 *   <li><b>显式读袋</b>:{@code SuperAddonHandler#findBookInBag(player, item)} —— 直接扫袋子里的书;</li>
 *   <li><b>隐式读袋</b>:{@code SuperpositionHandler#hasItem(player, item)} —— 扩展给这个方法打了
 *       {@code MixinSuperpositionHandler#hasItemMix}:对 {@code AntiqueBag.isBook(...)} 认的物品,
 *       袋里有也算"持有"。所以凡是"用 hasItem 判定某本手册"的效果,书在袋里同样生效。</li>
 * </ul>
 *
 * <h2>1.1.19:魔法石英花的随机属性</h2>
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

    private static final String LEGACY = "enigmaticlegacy:";
    private static final String ADDONS = "enigmaticaddons:";

    /** 所有条目翻译键的前缀。 */
    public static final String KEY_PREFIX = "tooltip.enigmaticcurio.bag.";

    /** 魔法石英花的物品 id。 */
    public static final String FLOWER_ID = ADDONS + "artificial_flower";

    /** 石英花"没有激活属性"那一行。 */
    public static final String FLOWER_KEY = KEY_PREFIX + "effect.artificial_flower";

    /** 石英花"某一条激活属性"那一行(参数:属性名、数值、类型、剩余 tick)。 */
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

    private static final List<String> BOOKS = List.of(
        LEGACY + "the_twist",
        LEGACY + "the_infinitum",
        ADDONS + "the_bless",
        LEGACY + "the_acknowledgment",
        ADDONS + "ode_to_living",
        ADDONS + "false_justice",
        FLOWER_ID,
        LEGACY + "hunter_guidebook",
        ADDONS + "sanguinary_handbook");

    /** 清单里的物品 id。 */
    public static List<String> bookIds() {
        return BOOKS;
    }

    private BagHudEffects() {
    }

    // ------------------------------------------------------------------ 服务端:收集

    /** 服务端:算出这名玩家书袋里所有"会提供加成"的条目。 */
    public static List<Entry> collect(Player player) {
        List<Entry> out = new ArrayList<>();
        if (player == null) {
            return out;
        }
        for (String id : BOOKS) {
            try {
                if (BookBag.findBook(player, new ResourceLocation(id)).isEmpty()) {
                    continue;
                }
                switch (id) {
                    case "enigmaticlegacy:the_twist" -> out.add(new Entry(id, key("twist"), "10%",
                        flag(player, "isTheCursedOne")));
                    case "enigmaticlegacy:the_infinitum" -> {
                        boolean worthy = flag(player, "isTheWorthyOne");
                        out.add(new Entry(id, key("infinitum"), "20%", worthy));
                        out.add(new Entry(id, key("infinitum_lifesteal"), "10%", worthy));
                    }
                    case "enigmaticaddons:the_bless" -> out.add(new Entry(id, key("bless"),
                        String.valueOf(blessInvulnerableTime()), true));
                    case FLOWER_ID -> out.addAll(artificialFlower(player));
                    case "enigmaticlegacy:the_acknowledgment" -> out.add(new Entry(id,
                        key("the_acknowledgment"), "", true));
                    case "enigmaticaddons:ode_to_living" -> out.add(new Entry(id, key("ode_to_living"), "",
                        true));
                    case "enigmaticaddons:false_justice" -> out.add(new Entry(id, key("false_justice"), "",
                        true));
                    case "enigmaticlegacy:hunter_guidebook" -> out.add(new Entry(id, key("hunter_guidebook"),
                        "", true));
                    case "enigmaticaddons:sanguinary_handbook" -> out.add(new Entry(id,
                        key("sanguinary_handbook"), "", true));
                    default -> out.add(new Entry(id, key(id.substring(id.indexOf(':') + 1)), "", true));
                }
            }
            catch (RuntimeException | LinkageError e) {
                // 单本书算失败绝不拖垮整份清单(否则整条链路会静默罢工)
                DebugLog.warn("[书袋] 统计 {} 失败,已跳过: {}", id, e.toString());
            }
        }
        // 本模组的"三书齐修正第四重咒":不属于任何一本物品,单独一条
        if (FourthCurseFix.active(player)) {
            out.add(new Entry("enigmaticcurio:fourth_curse", FOURTH_CURSE_KEY, "", true));
        }
        return out;
    }

    private static String key(String name) {
        return KEY_PREFIX + "effect." + name;
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
                                amountText(value.getAmount()) + "|"
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
        Field field = flowerField();
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

    private static Field flowerField;
    private static boolean flowerResolved;
    private static boolean flowerReadFailed;

    private static synchronized Field flowerField() {
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
            String[] parts = entry.detail().split("\\|", 3);
            String amount = parts.length > 0 ? parts[0] : "";
            String operation = parts.length > 1 ? parts[1] : "";
            String remaining = parts.length > 2 ? parts[2] : "";
            Component name = Component.literal(entry.value());
            try {
                Attribute attribute = ForgeRegistries.ATTRIBUTES.getValue(new ResourceLocation(entry.value()));
                if (attribute != null) {
                    name = Component.translatable(attribute.getDescriptionId());
                }
            }
            catch (RuntimeException ignored) {
                // 保留字面量
            }
            return Component.translatable(FLOWER_ATTR_KEY, name, amount,
                Component.translatable(FLOWER_OP_KEY + operation), remaining);
        }
        if (entry.value() == null || entry.value().isEmpty()) {
            return Component.translatable(entry.effectKey());
        }
        return Component.translatable(entry.effectKey(), entry.value());
    }

    /** 数值文本:整数不带小数点、正数带 +;如 +20 / -0.5 / +0.125。 */
    static String amountText(double amount) {
        String number;
        if (amount == Math.rint(amount) && Math.abs(amount) < 1.0E9D) {
            number = String.valueOf((long) amount);
        }
        else {
            number = String.format(Locale.ROOT, "%.4f", amount);
            int end = number.length();
            while (end > 1 && number.charAt(end - 1) == '0') {
                end--;
            }
            if (end > 1 && number.charAt(end - 1) == '.') {
                end--;
            }
            number = number.substring(0, end);
        }
        return (amount >= 0.0D ? "+" : "") + number;
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

    // ------------------------------------------------------------------ 读取本体/扩展的真实数值(反射,缺模组安全返回)

    /** 本体/扩展的布尔判定,如 isTheCursedOne / isTheWorthyOne。 */
    private static boolean flag(Player player, String method) {
        for (String owner : new String[] {
            "com.aizistral.enigmaticlegacy.handlers.SuperpositionHandler",
            "auviotre.enigmatic.addon.handlers.SuperAddonHandler" }) {
            try {
                Class<?> cls = Class.forName(owner, false, BagHudEffects.class.getClassLoader());
                Object value = cls.getMethod(method, Player.class).invoke(null, player);
                if (value instanceof Boolean b) {
                    return b;
                }
            }
            catch (Throwable ignored) {
                // 该方法不在这个类里 / 模组缺失:试下一个
            }
        }
        return false;
    }

    /** 恩惠之典在袋时的受击无敌帧(取扩展真实配置值)。 */
    private static int blessInvulnerableTime() {
        try {
            Class<?> cls = Class.forName("auviotre.enigmatic.addon.contents.items.TheBless",
                false, BagHudEffects.class.getClassLoader());
            Object param = cls.getField("invulnerableTime").get(null);
            Object value = param.getClass().getMethod("getValue").invoke(param);
            return value instanceof Integer i ? i : parseInt(String.valueOf(value));
        }
        catch (Throwable t) {
            return 0;
        }
    }

    private static int parseInt(String text) {
        try {
            return (int) Double.parseDouble(text.replace("%", "").toLowerCase(Locale.ROOT));
        }
        catch (Throwable t) {
            return 0;
        }
    }
}
