package com.hengx.enigmaticcurio;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 对 Curios 的**纯反射**薄封装(缺 Curios 时全部安全返回空 / false,不产生任何类加载失败)。
 *
 * <h2>为什么要它</h2>
 * 书袋想写进 NBT 给客户端 tooltip 用,就必须覆盖"戴在饰品栏"这种位置 —— 那不属于
 * {@code player.getInventory()},原版容器同步也管不到。这里按 Curios 5.14.1 的真实 API
 * (javap 核对)反射遍历所有饰品槽:
 * <ul>
 *   <li>{@code CuriosApi#getCuriosInventory(LivingEntity)} → {@code LazyOptional<ICuriosItemHandler>};</li>
 *   <li>{@code ICuriosItemHandler#getCurios()} → {@code Map<String, ICurioStacksHandler>}(键就是槽位类型,
 *       如 {@code ring} / {@code back} / {@code my_bag});</li>
 *   <li>{@code ICurioStacksHandler#getStacks()} → {@code IDynamicStackHandler}(即 {@code IItemHandler}),
 *       用 {@code IItemHandler#getSlots()} + {@code #getStackInSlot(int)} 读每一格。</li>
 * </ul>
 *
 * <h2>同步</h2>
 * Curios 自己每 tick 拿 {@code getStackInSlot(i)} 与 {@code getPreviousStackInSlot(i)} 比
 * ({@code ItemStack.matches},含 NBT),不等就发 {@code SPacketSyncStack} —— 也就是说
 * <b>原地改槽位里那叠物品的 NBT 会被 Curios 自己的轮询发现并同步</b>(javap 反编译
 * {@code CuriosEventHandler} 确认)。这里在此基础上再做一次显式发包兜底:
 * {@code NetworkHandler.INSTANCE}(public static SimpleChannel)+ {@code SPacketSyncStack}
 * (6 参构造:int entityId, String curioId, int slotId, ItemStack, HandlerType, CompoundTag)。
 * 任何一步反射失败都只记一次日志,然后安静退化为"依赖 Curios 自身同步"。
 */
public final class CuriosCompat {

    /** 一格饰品槽。 */
    public record CurioSlot(String slotId, int index, ItemStack stack) {

        public String describe() {
            return "curios:" + this.slotId + "#" + this.index;
        }
    }

    private static boolean resolved;
    private static Method getCuriosInventory;
    private static Method getCurios;
    private static Method getStacks;
    private static Method getSlots;
    private static Method getStackInSlot;

    private static boolean syncResolved;
    private static Object channel;
    private static Constructor<?> syncCtor;
    private static Object equipmentType;
    private static Method getCurio;
    private static boolean syncBrokenLogged;

    private CuriosCompat() {
    }

    /** Curios 是否可用(缺 Curios → false)。 */
    public static boolean available() {
        return resolve();
    }

    /** 该实体身上所有非空饰品(缺 Curios / 任何异常 → 空表)。 */
    public static List<CurioSlot> equipped(LivingEntity entity) {
        List<CurioSlot> out = new ArrayList<>();
        if (entity == null || !resolve()) {
            return out;
        }
        try {
            Object lazy = getCuriosInventory.invoke(null, entity);
            if (!(lazy instanceof LazyOptional<?> optional)) {
                return out;
            }
            Object handler = optional.resolve().orElse(null);
            if (handler == null) {
                return out;
            }
            Object map = getCurios.invoke(handler);
            if (!(map instanceof Map<?, ?> curios)) {
                return out;
            }
            for (Map.Entry<?, ?> entry : curios.entrySet()) {
                String slotId = String.valueOf(entry.getKey());
                Object stacksHandler = entry.getValue();
                if (stacksHandler == null) {
                    continue;
                }
                try {
                    Object stacks = getStacks.invoke(stacksHandler);
                    if (stacks == null) {
                        continue;
                    }
                    int size = (Integer) getSlots.invoke(stacks);
                    for (int i = 0; i < size; i++) {
                        Object stack = getStackInSlot.invoke(stacks, i);
                        if (stack instanceof ItemStack itemStack && !itemStack.isEmpty()) {
                            out.add(new CurioSlot(slotId, i, itemStack));
                        }
                    }
                }
                catch (ReflectiveOperationException | RuntimeException ignored) {
                    // 单个槽位类型读失败不影响其它槽位
                }
            }
        }
        catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return out;
        }
        return out;
    }

    /**
     * 显式给客户端推一次这一格饰品(读不到/不是 curio/Curios 缺 → false,调用方忽略即可)。
     */
    public static boolean sync(LivingEntity entity, CurioSlot slot) {
        if (entity == null || slot == null || slot.stack() == null || slot.stack().isEmpty()) {
            return false;
        }
        if (!resolveSync() || !isCurio(slot.stack())) {
            // 不是 Curios 认的物品:Curios 客户端也不会把它当饰品处理,没必要发包
            return false;
        }
        try {
            Object packet = syncCtor.newInstance(entity.getId(), slot.slotId(), slot.index(),
                slot.stack().copy(), equipmentType, new CompoundTag());
            ((SimpleChannel) channel).send(
                PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> entity), packet);
            return true;
        }
        catch (Throwable t) {
            if (!syncBrokenLogged) {
                syncBrokenLogged = true;
                DebugLog.infoAlways(
                    "[书袋诊断] Curios 显式同步包发送失败,退化为依赖 Curios 自身轮询: {}", t.toString());
            }
            return false;
        }
    }

    private static boolean isCurio(ItemStack stack) {
        try {
            Object lazy = getCurio.invoke(null, stack);
            return lazy instanceof LazyOptional<?> optional && optional.isPresent();
        }
        catch (Throwable t) {
            return false;
        }
    }

    private static synchronized boolean resolve() {
        if (resolved) {
            return getCuriosInventory != null;
        }
        resolved = true;
        try {
            ClassLoader cl = CuriosCompat.class.getClassLoader();
            Class<?> api = Class.forName("top.theillusivec4.curios.api.CuriosApi", false, cl);
            getCuriosInventory = api.getMethod("getCuriosInventory", LivingEntity.class);
            getCurios = Class.forName(
                "top.theillusivec4.curios.api.type.capability.ICuriosItemHandler", false, cl)
                .getMethod("getCurios");
            Class<?> stacksItf = Class.forName(
                "top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler", false, cl);
            getStacks = stacksItf.getMethod("getStacks");
            // IDynamicStackHandler 继承自 IItemHandler,用公开接口的方法反射调用最稳
            Class<?> itemHandler = Class.forName("net.minecraftforge.items.IItemHandler", false, cl);
            getSlots = itemHandler.getMethod("getSlots");
            getStackInSlot = itemHandler.getMethod("getStackInSlot", int.class);
        }
        catch (Throwable t) {
            getCuriosInventory = null;
            getCurios = null;
            getStacks = null;
            getSlots = null;
            getStackInSlot = null;
        }
        return getCuriosInventory != null;
    }

    private static synchronized boolean resolveSync() {
        if (syncResolved) {
            return channel != null && syncCtor != null && equipmentType != null;
        }
        syncResolved = true;
        try {
            ClassLoader cl = CuriosCompat.class.getClassLoader();
            Class<?> network = Class.forName(
                "top.theillusivec4.curios.common.network.NetworkHandler", false, cl);
            channel = network.getField("INSTANCE").get(null);
            Class<?> type = Class.forName(
                "top.theillusivec4.curios.common.network.server.sync.SPacketSyncStack$HandlerType",
                false, cl);
            equipmentType = type.getField("EQUIPMENT").get(null);
            Class<?> packet = Class.forName(
                "top.theillusivec4.curios.common.network.server.sync.SPacketSyncStack", false, cl);
            syncCtor = packet.getConstructor(int.class, String.class, int.class, ItemStack.class,
                type, CompoundTag.class);
            getCurio = Class.forName("top.theillusivec4.curios.api.CuriosApi", false, cl)
                .getMethod("getCurio", ItemStack.class);
        }
        catch (Throwable t) {
            channel = null;
            syncCtor = null;
            equipmentType = null;
            getCurio = null;
        }
        return channel != null && syncCtor != null && equipmentType != null;
    }
}
