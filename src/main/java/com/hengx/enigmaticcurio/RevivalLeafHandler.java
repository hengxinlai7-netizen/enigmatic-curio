package com.hengx.enigmaticcurio;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 复苏之叶的飞行开关。
 *
 * <p>扩展的 {@code RevivalLeaf#handleFlight} 在"附近有植物"时自己授予 {@code abilities.mayfly}
 * （字节码见 {@code RevivalLeafFlightMixin} 的类注释）。本类只负责:</p>
 * <ul>
 *   <li>读取 / 写入开关状态(存在物品 NBT {@value #NBT_KEY},<b>键不存在 = 开</b>);</li>
 *   <li>在 {@code handleFlight} 执行期间回答一个唯一的问题:
 *       <b>这一次调用要不要被开关压制?</b>(见 {@link #suppressed()});</li>
 *   <li>给 {@link ItemToggleHandler} 提供"这叠是不是复苏之叶 / 当前是否关闭"的判定。</li>
 * </ul>
 *
 * <h2>为什么改成"压制"而不是"事后撤销"</h2>
 * <p>旧实现是 {@code @Inject(RETURN)} 里把 {@code mayfly} 改回 {@code false}。那是<b>事后</b>撤销,
 * 区别不出来"这次 mayfly 是谁置 true 的":同一个 boolean 被所有来源共用(创造/旁观/龙首/其它飞行模组),
 * 只要别的模组在同一 tick 里也置 true(尤其它每 tick 都置),我们那一脚就会把它踩掉;再顺手写
 * {@code flying = false} / 调 {@code onUpdateAbilities()} 更是把玩家直接踢下飞行。</p>
 *
 * <p>现在 {@code mayfly} 的写入点本身被 {@code @Redirect} 拦下:开关关闭时<b>根本不执行</b>扩展那次
 * 授予,于是不存在"撤销",也就不可能撤到别人的飞行 —— 我们从头到尾没碰过别的来源写下的值。</p>
 *
 * <h2>触发方式:Ctrl + 左键(客户端识手势 → 网络包 → 服务端写 NBT)</h2>
 * <p>以前的「潜行 + 右键」已作废:潜行右键会被 <b>Curios 抢去佩戴饰品</b>,开关根本点不到。
 * 现在由客户端 {@code client.ToggleKeyHandler} 捕获 Ctrl+左键 并发
 * {@link CurioNetwork.SlotTogglePacket} / {@link CurioNetwork.HandTogglePacket},
 * 服务端校验后写 NBT 并同步回客户端。</p>
 *
 * <p>本类**不再监听任何事件**(切换逻辑统一在 {@link ItemToggleHandler}),所以 mod 构造里也不需要注册它。</p>
 */
public final class RevivalLeafHandler {

    public static final ResourceLocation LEAF_ID = new ResourceLocation("enigmaticaddons", "revival_leaf");
    public static final String NBT_KEY = "enigmaticcurio:flightDisabled";

    /**
     * "本次 {@code handleFlight} 调用是否被开关压制"。
     *
     * <p><b>为什么用 ThreadLocal:</b>{@code handleFlight(Player)} 由饰品的 curioTick 在服务端线程上调用,
     * 处理器(HEAD 注入 → 字段写入的 {@code @Redirect} → RETURN 注入)全部在<b>同一次、同一线程的方法调用栈</b>里,
     * 中间不会切线程、也不会递归调用自己,所以读到的必然是本次调用的玩家。</p>
     *
     * <p><b>不会串线的保证:</b></p>
     * <ul>
     *   <li>HEAD 每次调用都 {@code set}(覆盖上一次残留),RETURN 一定 {@code remove};</li>
     *   <li>{@link #suppressed()} 缺省 {@code false} = <b>放行 = 扩展原行为</b>(fail-open):
     *       万一 HEAD 注入因版本差异没应用上,行为完全退回扩展本身,不会误伤任何东西;</li>
     *   <li>顺序上 HEAD 一定先于同一次调用里的字段写入执行(Mixin 注入的是方法最开头)。</li>
     * </ul>
     */
    private static final ThreadLocal<Boolean> SUPPRESS = new ThreadLocal<>();

    private RevivalLeafHandler() {
    }

    public static boolean isLeaf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && LEAF_ID.equals(id);
    }

    /** 这一件术石的飞行是否已被玩家关掉(键不存在 = 开)。 */
    public static boolean isDisabled(ItemStack stack) {
        try {
            CompoundTag tag = stack.getTag();
            return tag != null && tag.getBoolean(NBT_KEY);
        }
        catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    /**
     * 写入 / 清除"飞行已关闭"标记(清除时若 NBT 空了就一并清掉,不留空 CompoundTag)。
     * <p>只由服务端通过 {@link ItemToggleHandler} 调用。</p>
     */
    public static void setFlightDisabled(ItemStack stack, boolean disabled) {
        try {
            if (stack == null || stack.isEmpty()) {
                return;
            }
            if (disabled) {
                stack.getOrCreateTag().putBoolean(NBT_KEY, true);
                return;
            }
            CompoundTag tag = stack.getTag();
            if (tag == null) {
                return;
            }
            tag.remove(NBT_KEY);
            if (tag.isEmpty()) {
                stack.setTag(null);
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    /**
     * 玩家身上这一件术石是否处于"关闭飞行"状态。
     *
     * <p>先查**饰品槽里戴着的那一件**(复苏之叶是术石类 curio,戴着的时候不在
     * {@code player.getInventory()} 里,旧版只扫背包,戴着时开关就"没反应"),再查背包与副手。</p>
     */
    public static boolean flightDisabled(Player player) {
        if (player == null) {
            return false;
        }
        try {
            Item leaf = Superposition.item(LEAF_ID.toString());
            if (leaf != null) {
                ItemStack worn = Superposition.getCurioStack(player, leaf);
                if (!worn.isEmpty() && isDisabled(worn)) {
                    return true;
                }
            }
            for (ItemStack s : player.getInventory().items) {
                if (isLeaf(s) && isDisabled(s)) {
                    return true;
                }
            }
            for (ItemStack s : player.getInventory().offhand) {
                if (isLeaf(s) && isDisabled(s)) {
                    return true;
                }
            }
            return false;
        }
        catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 供 RevivalLeafFlightMixin 使用
    // ------------------------------------------------------------------

    /**
     * {@code handleFlight} 的 HEAD:算一次"这次调用要不要压制",整次调用复用。
     * <p>只算一次而不是每个写入点各扫一遍 NBT,既省开销也保证同一次调用里三次裁决完全一致。</p>
     */
    public static void enterHandleFlight(Player player) {
        try {
            SUPPRESS.set(player != null && flightDisabled(player));
        }
        catch (RuntimeException | LinkageError ignored) {
            SUPPRESS.set(Boolean.FALSE);   // 出错一律放行 = 扩展原行为
        }
    }

    /** {@code handleFlight} 的 RETURN:清掉本次记录。 */
    public static void exitHandleFlight() {
        try {
            SUPPRESS.remove();
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    /**
     * 本次 {@code handleFlight} 是否压制对玩家能力({@code mayfly} / {@code flying} /
     * {@code onUpdateAbilities})的写入。
     *
     * @return {@code true} = 开关关闭,跳过扩展这次写入;{@code false} = 开关打开(或没记录到玩家),原样放行
     */
    public static boolean suppressed() {
        return Boolean.TRUE.equals(SUPPRESS.get());
    }
}
