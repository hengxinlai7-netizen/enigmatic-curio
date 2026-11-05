package com.hengx.enigmaticcurio;

import java.util.List;
import java.util.Locale;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 给被本模组增强过的**现有物品**追加说明。
 *
 * <ul>
 *   <li>每行统一前缀 {@code · [饰品增强] }(英文 {@code · [Curio Enhance] }),一眼看出是本模组加的;</li>
 *   <li>只写"改了什么",不写原因/机制;关键操作(Ctrl + 左键 等)单独一行,行尾带实时状态;</li>
 *   <li>全部走 {@code Component.translatable},文案在
 *       {@code assets/enigmaticcurio/lang/zh_cn.json} / {@code en_us.json} 里,
 *       代码里不写死任何中文字符串;</li>
 *   <li>按注册 id 判定物品,不引用任何目标模组的类,缺模组时自然什么都不加。</li>
 * </ul>
 */
public final class ItemTooltips {

    private static final ChatFormatting BODY = ChatFormatting.GRAY;

    // ------------------------------------------------------------------ 物品 id

    private static final String REVIVAL_LEAF = "enigmaticaddons:revival_leaf";
    private static final String BERSERK_CHARM = "enigmaticlegacy:berserk_charm";
    private static final String ADVENTURE_CHARM = "enigmaticaddons:adventure_charm";
    private static final String DESPAIR_INSIGNIA = "enigmaticaddons:despair_insignia";
    private static final String LOST_ENGINE = "enigmaticaddons:lost_engine";
    private static final String FORGER_GEM = "enigmaticaddons:forger_gem";
    private static final String CURSED_XP_SCROLL = "enigmaticaddons:cursed_xp_scroll";
    private static final String GLUTTONY_CHARM = "enigmaticdelicacy:gluttony_charm";
    private static final String ENDER_RING = "enigmaticlegacy:ender_ring";
    private static final String ENDER_CHEST = "minecraft:ender_chest";
    private static final String BLESS_RING = "enigmaticaddons:bless_ring";
    private static final String CURSED_RING = "enigmaticlegacy:cursed_ring";
    private static final String THUNDER_SCROLL = "enigmaticaddons:thunder_scroll";
    private static final String ENCHANTER_PEARL = "enigmaticlegacy:enchanter_pearl";
    private static final String ANTIQUE_BAG = "enigmaticaddons:antique_bag";

    private ItemTooltips() {
    }

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        try {
            ItemStack stack = event.getItemStack();
            if (stack == null || stack.isEmpty()) {
                return;
            }
            String id = idOf(stack.getItem());
            if (id == null) {
                return;
            }
            List<Component> lines = event.getToolTip();
            switch (id) {
                case REVIVAL_LEAF -> {
                    if (CurioConfig.REVIVAL_LEAF_TOGGLE.get()) {
                        add(lines, line("revival_leaf.effect"));
                        add(lines, body("revival_leaf.key",
                            state(!RevivalLeafHandler.isDisabled(stack))));
                    }
                }
                case BERSERK_CHARM -> {
                    if (CurioConfig.BLOODBATH_ENABLED.get()) {
                        add(lines, body("berserk.effect", trim(BerserkCharmHandler.lockValue())));
                        add(lines, body("berserk.key",
                            state(!BerserkCharmHandler.isLockOff(stack))));
                    }
                }
                case ADVENTURE_CHARM -> {
                    if (CurioConfig.ADVENTURE_CHARM_MINING.get()) {
                        add(lines, line("adventure.effect"));
                    }
                    if (CurioConfig.ADVENTURE_CHARM_CONVERT.get()) {
                        add(lines, line("adventure.convert"));
                    }
                }
                case DESPAIR_INSIGNIA -> {
                    if (CurioConfig.ADVENTURE_CHARM_MINING.get()) {
                        add(lines, line("despair.effect"));
                    }
                }
                case LOST_ENGINE -> {
                    if (CurioConfig.ENGINE_GUARD.get()) {
                        add(lines, line("engine.effect"));
                    }
                }
                case FORGER_GEM -> {
                    if (CurioConfig.FORGER_GEM_MAX.get()) {
                        add(lines, line("forger_gem.effect"));
                    }
                }
                case CURSED_XP_SCROLL -> {
                    if (CurioConfig.XP_SCROLL_CAP.get()) {
                        add(lines, body("xp_scroll.effect",
                            String.valueOf(CurioConfig.XP_SCROLL_CAP_VALUE.get())));
                    }
                }
                case GLUTTONY_CHARM -> {
                    if (CurioConfig.GLUTTONY_LOCK.get()) {
                        add(lines, line("gluttony.effect"));
                    }
                }
                case ENDER_RING, ENDER_CHEST -> add(lines, line("ender.effect"));
                case BLESS_RING -> {
                    if (CurioConfig.RING_ENHANCED.get()) {
                        add(lines, line("bless_ring.effect"));
                        add(lines, line("bless_ring.pair"));
                    }
                }
                case CURSED_RING -> {
                    if (CurioConfig.FOURTH_CURSE_FIX.get()) {
                        add(lines, line("cursed_ring.fourth"));
                    }
                }
                case THUNDER_SCROLL -> {
                    if (CurioConfig.SCALE_ENABLED.get()) {
                        // 扩展那句"横扫攻击造成 100% 伤害"已经被本模组改掉了,先从提示里去掉,免得自相矛盾。
                        removeStaleThunderLine(lines);
                        if (!CurioConfig.SCALE_RESTORE_SWEEP.get()) {
                            add(lines, body("thunder.effect", percent(CurioConfig.SCALE_ATTACK_DAMAGE_PERCENT.get())));
                        }
                        // 逐件状态:全局雷击开关 且 这一件卷轴自己的开关,两者都开才算"开启"
                        boolean on = CurioConfig.SCALE_LIGHTNING.get() && !ScrollHandler.isThunderOff(stack);
                        add(lines, body("thunder.lightning", state(on)));
                    }
                }
                case ENCHANTER_PEARL -> {
                    if (CurioConfig.PEARL_FIX_ENCHANTING.get()) {
                        add(lines, line("pearl.enchant"));
                    }
                    if (CurioConfig.PEARL_FIX_REFORGE.get()) {
                        add(lines, line("pearl.reforge"));
                    }
                    if (CurioConfig.PEARL_FIX_ENCHANTING.get()) {
                        add(lines, line("pearl.condition"));
                    }
                }
                case ANTIQUE_BAG -> {
                    if (CurioConfig.BAG_TOOLTIP_ENABLED.get()) {
                        addBagLines(lines, stack);
                    }
                }
                default -> {
                    if (BookLockHandler.isTargetBook(stack) && CurioConfig.BOOKS_ENABLED.get()) {
                        add(lines, line(BookLockHandler.isLocked(stack) ? "books.locked" : "books.unlocked"));
                        add(lines, line("books.key"));
                    }
                }
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 描述失败绝不能影响游戏
        }
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 古旧书袋:把袋内"会生效的效果"逐条列出来。
     *
     * <p>数据来自书袋物品自己的 NBT({@link BagHudEffects#NBT_KEY}),由 {@link BagHudSync} 在服务端
     * 算好后写进去(书袋内容原本只在服务端 capability 里,客户端拿不到,原版也没有同步它)。</p>
     */
    private static void addBagLines(List<Component> lines, ItemStack stack) {
        if (!BagHudEffects.hasCache(stack)) {
            // 服务器还没算过这一件(例如刚拿到手、或服务端没开这个功能)
            add(lines, line("bag.unsynced"));
            return;
        }
        List<BagHudEffects.Entry> entries = BagHudEffects.read(stack);
        if (entries.isEmpty()) {
            add(lines, line("bag.empty"));
            return;
        }
        add(lines, line("bag.header"));
        for (BagHudEffects.Entry entry : entries) {
            add(lines, BagHudEffects.display(entry));
        }
    }

    /** 物品的注册 id(拿不到时 null)。 */
    private static String idOf(Item item) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
        return id == null ? null : id.toString();
    }

    /** 追加一行 {@code · [饰品增强] …}(空内容直接跳过,绝不产生空行)。 */
    private static void add(List<Component> lines, Component body) {
        if (body == null) {
            return;
        }
        String text = body.getString();
        if (text == null || text.isBlank()) {
            return;
        }
        lines.add(Component.translatable("tooltip.enigmaticcurio.added", body).withStyle(BODY));
    }

    /** 一条"改了什么"的正文(不带前缀)。 */
    private static Component line(String key) {
        return Component.translatable("tooltip.enigmaticcurio." + key);
    }

    /** 带参数的正文。 */
    private static Component body(String key, Object... args) {
        return Component.translatable("tooltip.enigmaticcurio." + key, args);
    }

    /** 逐件开关/全局开关的实时状态。 */
    private static Component state(boolean on) {
        return Component.translatable(on
            ? "tooltip.enigmaticcurio.state.on"
            : "tooltip.enigmaticcurio.state.off");
    }

    /** 去掉扩展自己那句已经过时的横扫加成说明。 */
    private static void removeStaleThunderLine(List<Component> lines) {
        try {
            String stale = Component.translatable("tooltip.enigmaticaddons.thunderScroll1").getString();
            if (stale != null && !stale.isBlank()) {
                lines.removeIf(line -> stale.equals(line.getString()));
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 没有这条文案(语言文件缺失)就跳过
        }
    }

    /** 百分比短文本(100.0 → "100",12.5 → "12.5")。 */
    private static String percent(double value) {
        if (value == Math.floor(value)) {
            return String.format(Locale.ROOT, "%d", (long) value);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** 生命值短文本(1.0 → "1",1.5 → "1.5")。 */
    private static String trim(float value) {
        if (value == Math.floor(value)) {
            return String.format(Locale.ROOT, "%d", (long) value);
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }
}
