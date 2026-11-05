package com.hengx.enigmaticcurio.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hengx.enigmaticcurio.CurioConfig;
import com.hengx.enigmaticcurio.DebugLog;
import com.hengx.enigmaticcurio.PearlHandler;

import dev.shadowsoffire.apotheosis.adventure.affix.reforging.ReforgingMenu;
import net.minecraft.world.entity.player.Player;

/**
 * 附魔师的珍珠:神化(Apotheosis)**重铸台**不消耗材料/符石。
 *
 * <p>神化(Apotheosis 7.4.8,{@code javap} 已核对)的重铸消耗分三处:</p>
 * <ul>
 *   <li>{@code getMatCount()} —— 珍宝材料数量(无参那个);</li>
 *   <li>{@code getMatCost(int)} —— 珍宝材料;</li>
 *   <li>{@code getSigilCost(int)} —— 新生符石。</li>
 * </ul>
 * <p>珍珠在场时把这三处都返回 0(经验消耗保持原样)。</p>
 *
 * <h2>诊断(1.1.1)</h2>
 * <p>用户反馈"重铸台不生效且日志里一条附魔珍珠的重铸记录都没有"。为了区分
 * <b>"没进这个类(玩家用的是别的台子)"</b> 与 <b>"进了但判断没通过"</b>,
 * 三个注入都在**第一行**(还没做任何判断时)先打一条日志,且**不受 {@code debugLog} 门控**
 * (5 秒节流,因为重铸界面每帧都会读这些值)。</p>
 *
 * <p>配套:如果日志里连这三条都不出现,说明整合包里玩家实际打开的是
 * <b>远古重铸台</b>({@code ancientreforging}),那一份由 {@link AncientReforgeMixin} 负责。</p>
 */
@Mixin(ReforgingMenu.class)
public abstract class PearlReforgeMixin {

    @Shadow(remap = false)
    @Final
    protected Player player;

    /** 材料数量(无参的那个)归零。 */
    @Inject(method = "getMatCount()I", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void enigmaticcurio$freeMatCount(CallbackInfoReturnable<Integer> cir) {
        try {
            PearlHandler.warnThrottled("enter-reforge-matcount",
                "[附魔珍珠-重铸] 进入 ReforgingMenu#getMatCount 玩家={}", playerName());
            if (gate("getMatCount")) {
                cir.setReturnValue(0);
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 失败放行:按神化原本的消耗结算
        }
    }

    /** 珍宝材料归零。 */
    @Inject(method = "getMatCost(I)I", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void enigmaticcurio$freeMatReforge(int slot, CallbackInfoReturnable<Integer> cir) {
        try {
            PearlHandler.warnThrottled("enter-reforge-mat",
                "[附魔珍珠-重铸] 进入 ReforgingMenu#getMatCost(slot={}) 玩家={}", slot, playerName());
            if (gate("getMatCost")) {
                cir.setReturnValue(0);
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 失败放行
        }
    }

    /** 新生符石归零。 */
    @Inject(method = "getSigilCost(I)I", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void enigmaticcurio$freeSigilReforge(int slot, CallbackInfoReturnable<Integer> cir) {
        try {
            PearlHandler.warnThrottled("enter-reforge-sigil",
                "[附魔珍珠-重铸] 进入 ReforgingMenu#getSigilCost(slot={}) 玩家={}", slot, playerName());
            if (gate("getSigilCost")) {
                cir.setReturnValue(0);
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 失败放行
        }
    }

    /** 是否该把消耗归零(配置 + 珍珠在场)。命中时打一条节流诊断,用来在日志里确认注入真的走到了。 */
    private boolean gate(String method) {
        if (!CurioConfig.PEARL_FIX_REFORGE.get()) {
            PearlHandler.warnThrottled("reforge-cfg-off",
                "[附魔珍珠-重铸] 配置 pearlFixReforge=false,重铸台不免费");
            return false;
        }
        if (this.player == null || !PearlHandler.pearlPresent(this.player)) {
            return false;
        }
        PearlHandler.warnThrottled("reforge-" + method,
            "[附魔珍珠-重铸] {} 归零(珍珠在场)", method);
        DebugLog.info("[附魔珍珠-重铸] 重铸台 {} 归零", method);
        return true;
    }

    private String playerName() {
        try {
            return this.player == null ? "null" : this.player.getName().getString();
        }
        catch (RuntimeException | LinkageError e) {
            return "?";
        }
    }
}
