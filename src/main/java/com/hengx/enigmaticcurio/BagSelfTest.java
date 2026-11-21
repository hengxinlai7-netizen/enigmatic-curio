package com.hengx.enigmaticcurio;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 「古旧书袋效果」的<b>自检</b> —— 只在带了 {@code -Denigmaticcurio.selftest=bag} 启动服务端时才注册,
 * 正式游戏里完全不参与(没带参数时 {@link #register()} 直接返回,一行日志都不打)。
 *
 * <p>为什么需要它:书袋内容在服务端 capability 里、tooltip 是客户端渲染,普通启动验证只能证明
 * "没崩",证明不了"反射路径真的能走通"。这里在服务端启动后用 {@link FakePlayer} 走一遍**生产路径**
 * ({@link BookBag#hasBag} → {@link BookBag#booksInBag} → {@link BagHudEffects#collect} → 签名/协议),</p>
 *
 * <ul>
 *   <li><b>无扩展环境</b>:断言书袋功能整体不生效(空表、无异常),且不产生任何
 *       {@code NoClassDefFoundError};</li>
 *   <li><b>有扩展环境</b>:真的往假玩家的书袋 capability 里塞 5 件(含重复、含无加成书),
 *       断言去重、排序、效果行、兜底文案、签名稳定、NBT/网络协议往返都正确。</li>
 * </ul>
 *
 * <p>结果按 {@code [书袋自检]} 前缀打进服务端日志,最后一行 {@code RESULT=...} 并关闭服务端。</p>
 */
public final class BagSelfTest {

    private static final String PROP = "enigmaticcurio.selftest";

    /** 塞进书袋的样本:重复的《倒转之启》用于验证去重,{@code minecraft:book} 用于验证兜底。 */
    private static final String[] SAMPLE = {
        "enigmaticaddons:the_bless",
        "enigmaticlegacy:the_twist",
        "enigmaticlegacy:hunter_guidebook",
        "enigmaticlegacy:the_twist",
        "minecraft:book"
    };

    private static final String BAG_CAPABILITY =
        "auviotre.enigmatic.addon.contents.objects.bookbag.AntiqueBagCapability";
    private static final String BAG_HANDLER =
        "auviotre.enigmatic.addon.contents.objects.bookbag.IAntiqueBagHandler";
    private static final String ADDON_HANDLER = "auviotre.enigmatic.addon.handlers.SuperAddonHandler";
    private static final String FLOWER = "auviotre.enigmatic.addon.contents.items.ArtificialFlower";

    private static boolean armed;
    private static int pass;
    private static int fail;

    private BagSelfTest() {
    }

    /** 由 {@code EnigmaticCurio} 构造器调用;没带 -D 参数时什么都不做。 */
    public static void register() {
        String value = System.getProperty(PROP);
        if (value == null || !value.contains("bag")) {
            return;
        }
        armed = true;
        MinecraftForge.EVENT_BUS.addListener(BagSelfTest::onServerStarted);
        DebugLog.infoAlways("[书袋自检] 已装载(selftest=bag),服务端启动后自动跑;扩展=%s",
            addonsPresent());
    }

    private static void onServerStarted(ServerStartedEvent event) {
        if (!armed) {
            return;
        }
        armed = false;
        try {
            ServerLevel level = event.getServer().overworld();
            FakePlayer fake = fakePlayer(level);
            boolean addons = addonsPresent();
            if (addons) {
                checkReflection();
                checkFullPath(fake);
            }
            else {
                checkNoAddons(fake);
            }
        }
        catch (RuntimeException | LinkageError e) {
            report("自检未捕获异常", false, e.toString());
        }
        finally {
            DebugLog.infoAlways("[书袋自检] RESULT pass=%s fail=%s %s",
                pass, fail, fail == 0 ? "ALL-PASS" : "HAS-FAIL");
            try {
                event.getServer().halt(false);
            }
            catch (RuntimeException | LinkageError ignored) {
                // 关服失败不影响结论
            }
        }
    }

    // ------------------------------------------------------------------ 无扩展环境

    /** 没装扩展:书袋功能必须整体失效,且不能有类加载错误。 */
    private static void checkNoAddons(FakePlayer fake) {
        DebugLog.infoAlways("[书袋自检] 扩展未装:期望书袋功能整体不生效(空表、零异常)");
        boolean bag = false;
        boolean empty = false;
        try {
            bag = BookBag.hasBag(fake);
            empty = BagHudEffects.collect(fake).isEmpty();
        }
        catch (RuntimeException | LinkageError e) {
            report("无扩展时调用书袋路径", false, e.toString());
            return;
        }
        report("无扩展时 hasBag=false", !bag, "hasBag=" + bag);
        report("无扩展时 collect 为空", empty, "collect.isEmpty=" + empty);
        report("无扩展时 ability 表为空", BookBag.booksInBag(fake).isEmpty(),
            "booksInBag.size=" + BookBag.booksInBag(fake).size());
    }

    // ------------------------------------------------------------------ 反射入口

    /** 扩展的四个反射入口(书袋能力 / 处理器 / findBookInBag / 石英花属性表)。 */
    private static void checkReflection() {
        try {
            Field field = Class.forName(BAG_CAPABILITY).getField("INVENTORY");
            report("反射 AntiqueBagCapability.INVENTORY", field.getType() == Capability.class,
                field.getType().getName());
        }
        catch (Throwable t) {
            report("反射 AntiqueBagCapability.INVENTORY", false, t.toString());
        }
        try {
            Class<?> itf = Class.forName(BAG_HANDLER);
            boolean ok = itf.getMethod("getSlots").getReturnType() == int.class
                && itf.getMethod("getBook", int.class).getReturnType() == ItemStack.class
                && itf.getMethod("setBook", int.class, ItemStack.class).getReturnType() == void.class;
            report("反射 IAntiqueBagHandler getSlots/getBook/setBook", ok, itf.getName());
        }
        catch (Throwable t) {
            report("反射 IAntiqueBagHandler getSlots/getBook/setBook", false, t.toString());
        }
        try {
            Method method = Class.forName(ADDON_HANDLER)
                .getMethod("findBookInBag", net.minecraft.world.entity.player.Player.class, Item.class);
            report("反射 SuperAddonHandler.findBookInBag", method.getReturnType() == ItemStack.class,
                method.toString());
        }
        catch (Throwable t) {
            report("反射 SuperAddonHandler.findBookInBag", false, t.toString());
        }
        try {
            Field field = Class.forName(FLOWER).getField("PLAYER_ATTRIBUTE_MAP");
            report("反射 ArtificialFlower.PLAYER_ATTRIBUTE_MAP", java.util.Map.class.isAssignableFrom(field.getType()),
                field.getType().getName());
        }
        catch (Throwable t) {
            report("反射 ArtificialFlower.PLAYER_ATTRIBUTE_MAP", false, t.toString());
        }
        report("BookEffects 注册表非空", BookEffects.knownIds().size() >= 17,
            "providers=" + BookEffects.knownIds().size() + " " + BookEffects.knownIds());
        report("MAX_LINES=12", BagHudEffects.MAX_LINES == 12, "MAX_LINES=" + BagHudEffects.MAX_LINES);
        checkMoreKey();
    }

    /** 第 5 项显示优化(超 12 条截断)的纯函数断言。 */
    private static void checkMoreKey() {
        try {
            List<BagHudEffects.Entry> mixed = new java.util.ArrayList<>();
            // 13 条有效果的 + 1 条无加成:排序后前 12 条被显示,第 13 条起"还有 2 条"里仍有效果 ⇒ 通用文案
            for (int i = 0; i < 13; i++) {
                mixed.add(new BagHudEffects.Entry("enigmaticlegacy:the_twist",
                    "baghud.book.enigmaticlegacy.the_twist.line1", "10", true));
            }
            mixed.add(new BagHudEffects.Entry("minecraft:book", BookEffects.KEY_NO_BONUS, "", true));
            report("截断:混合剩余走通用文案",
                (BagHudEffects.KEY_PREFIX + "more").equals(BagHudEffects.moreKey(mixed, 12)),
                BagHudEffects.moreKey(mixed, 12));

            List<BagHudEffects.Entry> allNoBonus = new java.util.ArrayList<>();
            for (int i = 0; i < 13; i++) {
                allNoBonus.add(new BagHudEffects.Entry("minecraft:book" + i, BookEffects.KEY_NO_BONUS, "", true));
            }
            report("截断:剩余全无加成走 no_bonus 文案",
                (BagHudEffects.KEY_PREFIX + "more.no_bonus").equals(BagHudEffects.moreKey(allNoBonus, 12)),
                BagHudEffects.moreKey(allNoBonus, 12));
        }
        catch (RuntimeException | LinkageError e) {
            report("截断文案", false, e.toString());
        }
    }

    // ------------------------------------------------------------------ 有扩展环境:完整链路

    private static void checkFullPath(FakePlayer fake) {
        try {
            fake.getInventory().clearContent();
            Item bag = ForgeRegistries.ITEMS.getValue(new ResourceLocation(BookBag.BAG_ID));
            report("古旧书袋物品存在", bag != null && bag != Items.AIR,
                BookBag.BAG_ID + " -> " + bag);
            if (bag == null || bag == Items.AIR) {
                return;
            }
            fake.getInventory().add(new ItemStack(bag));
            report("hasBag(假玩家)", BookBag.hasBag(fake), "书袋已放进背包");

            Object handler = bagHandler(fake);
            report("取到书袋 capability", handler != null, String.valueOf(handler));
            if (handler == null) {
                return;
            }
            Method setBook = Class.forName(BAG_HANDLER).getMethod("setBook", int.class, ItemStack.class);
            int inserted = 0;
            for (int i = 0; i < SAMPLE.length; i++) {
                Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(SAMPLE[i]));
                if (item == null || item == Items.AIR) {
                    DebugLog.infoAlways("[书袋自检] 样本不存在,跳过: %s", SAMPLE[i]);
                    continue;
                }
                setBook.invoke(handler, i, new ItemStack(item));
                inserted++;
            }
            report("写入书袋 capability", inserted >= 4, "写入 " + inserted + " 件");
            report("booksInBag 枚举", BookBag.booksInBag(fake).size() == inserted,
                "枚举到 " + BookBag.booksInBag(fake).size() + " 件");

            List<BagHudEffects.Entry> entries = BagHudEffects.collect(fake);
            dump(entries);
            report("collect 非空", !entries.isEmpty(), "条目=" + entries.size());

            int twist = 0;
            int noBonus = 0;
            boolean blessNumeric = false;
            boolean noBonusLast = true;
            boolean seenNoBonus = false;
            for (BagHudEffects.Entry entry : entries) {
                String key = entry.effectKey();
                if (BookEffects.KEY_NO_BONUS.equals(key)) {
                    noBonus++;
                    seenNoBonus = true;
                }
                else if (seenNoBonus) {
                    noBonusLast = false;
                }
                if (key.startsWith("baghud.book.enigmaticlegacy.the_twist.")) {
                    twist++;
                }
                if (key.equals("baghud.book.enigmaticaddons.the_bless.line2")) {
                    blessNumeric = parseInt(entry.value()) > 0;
                }
            }
            report("同一本书去重(倒转之启只 1 条)", twist == 1, "twist=" + twist);
            report("未收录书走兜底(no_bonus)", noBonus >= 1, "noBonus=" + noBonus);
            report("有效果的排在无加成之前", noBonusLast, "noBonusLast=" + noBonusLast);
            report("恩惠之典无敌帧读到真实配置", blessNumeric, "line2 参数>0");

            // 签名稳定 + 顺序确定(两次 collect 结果必须逐字一致)
            String first = BagHudEffects.signature(entries);
            String second = BagHudEffects.signature(BagHudEffects.collect(fake));
            report("签名逐次稳定", first.equals(second), "len=" + first.length());

            // NBT / 网络协议往返(向后兼容:5 段格式不变)
            List<String> lines = BagHudEffects.toLines(entries);
            report("协议往返条目数一致", BagHudEffects.parseLines(lines).size() == entries.size(),
                "lines=" + lines.size() + " parsed=" + BagHudEffects.parseLines(lines).size());
            List<String> legacy = List.of(
                "enigmaticlegacy:the_twist|tooltip.enigmaticcurio.bag.effect.twist|10%|true|");
            report("旧格式包仍可解析", BagHudEffects.parseLines(legacy).size() == 1, legacy.toString());

            // 每条都能渲染(不抛异常)
            int rendered = 0;
            for (BagHudEffects.Entry entry : entries) {
                try {
                    if (BagHudEffects.display(entry) != null) {
                        rendered++;
                    }
                }
                catch (RuntimeException | LinkageError e) {
                    report("渲染 " + entry.effectKey(), false, e.toString());
                }
            }
            report("全部条目可渲染", rendered == entries.size(), "rendered=" + rendered);

            cleanup(fake, handler, setBook);
        }
        catch (Throwable t) {
            report("完整链路", false, t.toString());
        }
    }

    private static void cleanup(FakePlayer fake, Object handler, Method setBook) {
        try {
            for (int i = 0; i < 12; i++) {
                setBook.invoke(handler, i, ItemStack.EMPTY);
            }
            fake.getInventory().clearContent();
        }
        catch (Throwable ignored) {
            // 清理失败不影响结论
        }
    }

    // ------------------------------------------------------------------ 工具

    private static FakePlayer fakePlayer(ServerLevel level) {
        GameProfile profile = new GameProfile(
            UUID.nameUUIDFromBytes("enigmaticcurio-bag-selftest".getBytes(StandardCharsets.UTF_8)),
            "[curio-bag-selftest]");
        return FakePlayerFactory.get(level, profile);
    }

    /** 反射取假玩家身上的书袋处理器(走生产代码同一条能力)。 */
    private static Object bagHandler(FakePlayer fake) throws ReflectiveOperationException {
        Object capability = Class.forName(BAG_CAPABILITY, false, BagSelfTest.class.getClassLoader())
            .getField("INVENTORY").get(null);
        @SuppressWarnings("unchecked")
        Capability<Object> typed = (Capability<Object>) capability;
        LazyOptional<Object> optional = fake.getCapability(typed);
        return optional.orElse(null);
    }

    private static boolean addonsPresent() {
        try {
            return ModList.get().isLoaded("enigmaticaddons");
        }
        catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private static int parseInt(String text) {
        try {
            return (int) Double.parseDouble(text.trim());
        }
        catch (RuntimeException e) {
            return 0;
        }
    }

    private static void dump(List<BagHudEffects.Entry> entries) {
        for (BagHudEffects.Entry entry : entries) {
            DebugLog.infoAlways("[书袋自检] 条目 %s | %s | %s | active=%s",
                entry.bookId(), entry.effectKey(), entry.value(), entry.active());
        }
    }

    private static void report(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            DebugLog.infoAlways("[书袋自检] PASS %s: %s", name, detail);
        }
        else {
            fail++;
            DebugLog.infoAlways("[书袋自检] FAIL %s: %s", name, detail);
        }
    }
}
