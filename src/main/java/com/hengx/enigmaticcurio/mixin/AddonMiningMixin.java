package com.hengx.enigmaticcurio.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.hengx.enigmaticcurio.CurioConfig;
import com.hengx.enigmaticcurio.DebugLog;
import com.hengx.enigmaticcurio.Superposition;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.entity.player.PlayerEvent;

/**
 * 取消冒险者/绝望者徽章的「无法挖掘方块」负面。
 *
 * <p><b>根因(为什么之前那版无效)</b>:扩展压制挖掘**不是**把破坏速度置 0,而是直接
 * {@code PlayerEvent$BreakSpeed#setCanceled(true)}。项目里原来那个 handler 只在
 * {@code event.getNewSpeed() == 0} 时才还原速度 —— 而事件被取消时 {@code getNewSpeed()}
 * 仍然是正常值(根本没被改过),所以那个分支永远进不去;就算进去了,把速度改回去也没用,
 * 因为事件已被取消,原版压根不会去算破坏进度。</p>
 *
 * <p><b>反编译依据</b>(enigmaticaddons 1.2.5 实例版与 1.2.6 libs 版都核对过,
 * 两版 {@code AddonEventHandler#miningStuff} 结构一致):</p>
 * <pre>
 * 1.2.5   1.2.6
 *   48     64   SuperpositionHandler.hasCurio(player, ADVENTURE_CHARM)
 *   72     88   PlayerEvent$BreakSpeed.setCanceled(true)     // 第 1 处(冒险者徽章)
 *   79     95   SuperpositionHandler.hasCurio(player, DESPAIR_INSIGNIA)
 *   87    103   PlayerEvent$BreakSpeed.setCanceled(true)     // 第 2 处(绝望者徽章)
 * </pre>
 * 用 {@code @Redirect} + {@code ordinal=0/1} 只包住这两条 {@code setCanceled} 调用:
 * <ul>
 *   <li>开关打开且对应徽章戴在身上 → 不调用 {@code setCanceled} = 允许挖掘;</li>
 *   <li>开关关闭 / 两件都没戴 → 原样 {@code setCanceled(cancel)} 透传(保留"想留负面"的能力);</li>
 *   <li>{@code EarthPromise} 的 {@code setNewSpeed} 加成在方法前段,完全没碰;</li>
 *   <li>只包住扩展方法体内的这两条调用,<b>不会</b>解掉其它模组对同一事件的取消
 *       (第三方取消发生在方法外,我们的回调永远不会调用 {@code setCanceled(false)})。</li>
 * </ul>
 * 所以效果是"按住 Shift / 其它模组取消"依旧照旧,只有扩展自己那两处被放行。
 */
@Mixin(targets = "auviotre.enigmatic.addon.handlers.AddonEventHandler")
public class AddonMiningMixin {

    private static final String SET_CANCELED =
        "Lnet/minecraftforge/event/entity/player/PlayerEvent$BreakSpeed;setCanceled(Z)V";

    private static final String ADVENTURE_CHARM_ID = "enigmaticaddons:adventure_charm";
    private static final String DESPAIR_INSIGNIA_ID = "enigmaticaddons:despair_insignia";

    /** 第 1 处:冒险者徽章。 */
    @Redirect(method = "miningStuff(Lnet/minecraftforge/event/entity/player/PlayerEvent$BreakSpeed;)V",
        at = @At(value = "INVOKE", target = SET_CANCELED, ordinal = 0), remap = false)
    private void enigmaticcurio$adventureCharmMining(PlayerEvent.BreakSpeed event, boolean cancel) {
        if (cancel && allowed(event, ADVENTURE_CHARM_ID, "冒险者徽章")) {
            return;   // 不取消 = 允许挖掘
        }
        event.setCanceled(cancel);
    }

    /** 第 2 处:绝望者徽章。 */
    @Redirect(method = "miningStuff(Lnet/minecraftforge/event/entity/player/PlayerEvent$BreakSpeed;)V",
        at = @At(value = "INVOKE", target = SET_CANCELED, ordinal = 1), remap = false)
    private void enigmaticcurio$despairInsigniaMining(PlayerEvent.BreakSpeed event, boolean cancel) {
        if (cancel && allowed(event, DESPAIR_INSIGNIA_ID, "绝望者徽章")) {
            return;
        }
        event.setCanceled(cancel);
    }

    /** 开关打开 + 身上戴着这件徽章 ⇒ 放行。任何异常都退回"按扩展原逻辑取消"。 */
    private static boolean allowed(PlayerEvent.BreakSpeed event, String itemId, String displayName) {
        try {
            if (!CurioConfig.ADVENTURE_CHARM_MINING.get()) {
                return false;
            }
            Player player = event.getEntity();
            if (player == null) {
                return false;
            }
            Item item = Superposition.item(itemId);
            if (item == null || !Superposition.hasCurio(player, item)) {
                return false;
            }
            DebugLog.info("[挖掘放行] {} 佩戴{},未取消 BreakSpeed", player.getName().getString(), displayName);
            return true;
        }
        catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
