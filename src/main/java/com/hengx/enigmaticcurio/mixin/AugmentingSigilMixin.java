package com.hengx.enigmaticcurio.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hengx.enigmaticcurio.CurioConfig;
import com.hengx.enigmaticcurio.DebugLog;
import com.hengx.enigmaticcurio.PearlHandler;
import com.hengx.enigmaticcurio.PearlUtil;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 「附魔师的珍珠」→ 神化<b>强化台</b>不消耗强化符石。
 *
 * <h2>为什么需要它(根因)</h2>
 * <p>用户反馈的"强化台不消耗强化符石"是**另一张台子**:神化的
 * {@code dev.shadowsoffire.apotheosis.adventure.affix.augmenting.AugmentingMenu}
 * (强化 / 重掷,消耗 {@code sigils} —— 强化符石)。主模组当年就有一份 {@code AugmentingSigilMixin}
 * 覆盖它,而本模组最初只移植了重铸台那两份,所以强化台一直照扣符石。</p>
 *
 * <h2>做法(与主模组一致:备份 → 执行 → 还原数量)</h2>
 * <p>不动神化的任何逻辑:点击前记下符石槽数量,点击后如果变少了就写回原数量。
 * 强化结果、附加词条、失败概率、经验消耗全部保持原样,唯一区别是符石没少。</p>
 *
 * <p>目标方法名用 SRG {@code m_6366_}(= {@code clickMenuButton(Player,int)}),{@code javap} 已核对
 * Apotheosis 7.4.8;{@code remap = false} + {@code require = 0},神化换名时静默跳过。</p>
 *
 * <h2>诊断</h2>
 * <p>进方法、退还符石都打一条**不受 {@code debugLog} 门控**的日志(5 秒节流),
 * 下次看日志就能确认"到底进没进这个类"。</p>
 */
@Mixin(targets = "dev.shadowsoffire.apotheosis.adventure.affix.augmenting.AugmentingMenu")
public abstract class AugmentingSigilMixin {

    /** 强化符石槽(神化自己的方法)。 */
    @Shadow(remap = false)
    public abstract ItemStack getSigils();

    @Unique
    private int enigmaticcurio$sigilsBefore = -1;

    @Inject(method = "m_6366_(Lnet/minecraft/world/entity/player/Player;I)Z",
        at = @At("HEAD"), remap = false, require = 0)
    private void enigmaticcurio$beforeClick(Player player, int buttonId, CallbackInfoReturnable<Boolean> cir) {
        try {
            this.enigmaticcurio$sigilsBefore = -1;
            boolean free = freeFor(player);
            PearlHandler.warnThrottled("enter-augmenting",
                "[附魔珍珠-强化台] 进入 AugmentingMenu#clickMenuButton id={} 玩家={} 免符石={}",
                buttonId, player == null ? "null" : player.getName().getString(), free);
            if (!free) {
                return;
            }
            ItemStack sigils = this.getSigils();
            if (sigils != null && !sigils.isEmpty()) {
                this.enigmaticcurio$sigilsBefore = sigils.getCount();
            }
        }
        catch (RuntimeException | LinkageError e) {
            this.enigmaticcurio$sigilsBefore = -1;
            DebugLog.warn("[附魔珍珠-强化台] HEAD 注入异常({}),按原样扣符石", e.toString());
        }
    }

    @Inject(method = "m_6366_(Lnet/minecraft/world/entity/player/Player;I)Z",
        at = @At("RETURN"), remap = false, require = 0)
    private void enigmaticcurio$afterClick(Player player, int buttonId, CallbackInfoReturnable<Boolean> cir) {
        try {
            int before = this.enigmaticcurio$sigilsBefore;
            this.enigmaticcurio$sigilsBefore = -1;
            if (before < 0) {
                return;
            }
            ItemStack sigils = this.getSigils();
            if (sigils != null && !sigils.isEmpty() && sigils.getCount() < before) {
                sigils.setCount(before);   // 退还强化符石
                PearlHandler.warnThrottled("augsigil",
                    "[附魔珍珠-强化台] 已退还强化符石 {} 个(珍珠在场)", before);
            }
        }
        catch (RuntimeException | LinkageError e) {
            DebugLog.warn("[附魔珍珠-强化台] RETURN 注入异常({})", e.toString());
        }
    }

    /** 配置允许 且 玩家携带珍珠(主模组强化台用"携带即算"的口径)。 */
    private static boolean freeFor(Player player) {
        try {
            return CurioConfig.PEARL_FIX_REFORGE.get() && PearlUtil.wearing(player);
        }
        catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
