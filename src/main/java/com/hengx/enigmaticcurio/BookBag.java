package com.hengx.enigmaticcurio;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 只读地查询「古旧书袋」({@code enigmaticaddons:antique_bag})里有没有某本书。
 *
 * <p>反编译依据(enigmaticaddons 1.2.5/1.2.6 一致):</p>
 * <ul>
 *   <li>书袋本身是普通物品 {@code auviotre.enigmatic.addon.contents.items.AntiqueBag}(extends {@code ItemBase},
 *       <b>不是</b> curio),右键开一个 12 格 GUI;真正的内容物不在物品 NBT 里,而是挂在**玩家(实体)**上的
 *       capability {@code AntiqueBagCapability.INVENTORY}({@code IAntiqueBagHandler},内部 {@code ItemStackHandler(12)}),
 *       随玩家存档保存、Clone 时用 {@code writeTag/readTag} 复制。<b>这个 capability 没有任何网络同步</b>
 *       —— 这就是"客户端 tooltip 看不到袋内效果"的根本原因(见 {@link BagHudSync});</li>
 *   <li>扩展自己提供了现成的只读查询
 *       {@code auviotre.enigmatic.addon.handlers.SuperAddonHandler#findBookInBag(Player, Item)}:
 *       它先要求玩家身上有古旧书袋,再查 capability,命中就返回那本书的 ItemStack,否则返回空。</li>
 * </ul>
 * 这里**直接复用扩展这个 API**(反射调用,缺扩展时安全返回空),因此判定口径与扩展自己完全一致,
 * 也不会对书袋/书做任何写入。
 */
public final class BookBag {

    /** 古旧书袋的注册 id。 */
    public static final String BAG_ID = "enigmaticaddons:antique_bag";

    private static Method findBookInBag;
    private static boolean resolved;

    private BookBag() {
    }

    /** 返回袋里那本书的 ItemStack(没有/书袋不在身上 → 空)。 */
    public static ItemStack findBook(Player player, ResourceLocation itemId) {
        if (player == null || itemId == null) {
            return ItemStack.EMPTY;
        }
        Item item = ForgeRegistries.ITEMS.getValue(itemId);
        if (item == null || item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        return findBookInBag(player, item);
    }

    /** 该物品是否在玩家的古旧书袋里。 */
    public static boolean hasBook(Player player, ResourceLocation itemId) {
        return !findBook(player, itemId).isEmpty();
    }

    /** 反射调用扩展的 {@code SuperAddonHandler.findBookInBag}。 */
    private static ItemStack findBookInBag(Player player, Item item) {
        try {
            Method method = method();
            if (method == null) {
                return ItemStack.EMPTY;
            }
            Object result = method.invoke(null, player, item);
            return result instanceof ItemStack stack ? stack : ItemStack.EMPTY;
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return ItemStack.EMPTY;
        }
    }

    private static synchronized Method method() {
        if (resolved) {
            return findBookInBag;
        }
        resolved = true;
        try {
            Class<?> handler = Class.forName("auviotre.enigmatic.addon.handlers.SuperAddonHandler",
                false, BookBag.class.getClassLoader());
            findBookInBag = handler.getMethod("findBookInBag", Player.class, Item.class);
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            findBookInBag = null;
        }
        return findBookInBag;
    }

    // ------------------------------------------------------------------ 诊断:capability 现状

    /**
     * 诊断用的"书袋 capability 现状"。
     *
     * <p>反编译依据({@code AntiqueBagCapability}, 1.2.5):capability 挂在**玩家实体**上
     * ({@code AddonEventHandler#attachEntitiesCapabilities} → {@code createProvider(LivingEntity)},
     * 只对 {@code Player} 挂),**不是**挂在物品栈上;取法就是原版的
     * {@code player.getCapability(AntiqueBagCapability.INVENTORY)}
     * (扩展自己的 {@code SuperAddonHandler.getCapability(Player, Capability)} 也只是转调它)。
     * 内容读法:{@code IAntiqueBagHandler#getSlots()} / {@code #getBook(int)} /
     * {@code #getInventory()} / {@code #findBook(Item)}。</p>
     *
     * @param present 是否取到了 capability
     * @param slots   槽位数(取不到时为 0)
     * @param books   袋内非空物品(取不到时为空表)
     */
    public record BagState(boolean present, int slots, List<ItemStack> books) {

        /** 一行诊断文本,如 {@code 有(12格,2件:the_bless,artificial_flower)}。 */
        public String describe() {
            if (!present) {
                return "无(取不到 capability)";
            }
            StringBuilder sb = new StringBuilder("有(").append(this.slots).append("格,")
                .append(this.books.size()).append("件");
            for (int i = 0; i < this.books.size(); i++) {
                sb.append(i == 0 ? ':' : ',');
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(this.books.get(i).getItem());
                sb.append(id == null ? "?" : id.getPath());
            }
            return sb.append(')').toString();
        }
    }

    /** 只读地取一次书袋 capability 的现状(缺扩展/取不到 → {@code present=false})。 */
    public static BagState contents(Player player) {
        if (player == null) {
            return new BagState(false, 0, List.of());
        }
        try {
            Object capabilityInstance = capability();
            if (capabilityInstance == null) {
                return new BagState(false, 0, List.of());
            }
            Method get = Class.forName("net.minecraftforge.common.capabilities.ICapabilityProvider",
                false, BookBag.class.getClassLoader()).getMethod("getCapability", Capability.class);
            Object lazy = get.invoke(player, capabilityInstance);
            if (!(lazy instanceof LazyOptional<?> optional)) {
                return new BagState(false, 0, List.of());
            }
            Object handler = optional.resolve().orElse(null);
            if (handler == null) {
                return new BagState(false, 0, List.of());
            }
            Class<?> itf = Class.forName(
                "auviotre.enigmatic.addon.contents.objects.bookbag.IAntiqueBagHandler",
                false, BookBag.class.getClassLoader());
            int slots = (Integer) itf.getMethod("getSlots").invoke(handler);
            Method getBook = itf.getMethod("getBook", int.class);
            List<ItemStack> books = new ArrayList<>(slots);
            for (int i = 0; i < slots; i++) {
                Object stack = getBook.invoke(handler, i);
                if (stack instanceof ItemStack itemStack && !itemStack.isEmpty()) {
                    books.add(itemStack);
                }
            }
            return new BagState(true, slots, books);
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return new BagState(false, 0, List.of());
        }
    }

    private static Object capabilityField;
    private static boolean capResolved;

    private static synchronized Object capability() {
        if (capResolved) {
            return capabilityField;
        }
        capResolved = true;
        try {
            Class<?> cls = Class.forName(
                "auviotre.enigmatic.addon.contents.objects.bookbag.AntiqueBagCapability",
                false, BookBag.class.getClassLoader());
            capabilityField = cls.getField("INVENTORY").get(null);
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            capabilityField = null;
        }
        return capabilityField;
    }
}
