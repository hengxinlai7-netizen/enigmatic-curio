package com.hengx.enigmaticcurio.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hengx.enigmaticcurio.CurioConfig;
import com.hengx.enigmaticcurio.DebugLog;
import com.hengx.enigmaticcurio.PearlHandler;

import net.minecraft.world.entity.player.Player;

/**
 * 「附魔师的珍珠」→ <b>远古重铸台</b>(独立模组 {@code ancientreforging})不消耗新生符石 / 珍宝材料。
 *
 * <h2>为什么需要它(根因)</h2>
 * <p>整合包里同时装了 Apotheosis 的重铸台和这个独立的远古重铸台,玩家实际用的是后者
 * ({@code com.ianm1647.ancientreforging.screen.AncientReforgingMenu})。主模组能工作是因为它
 * <b>两个都写了 mixin</b>({@code PearlReforgeMixin} 管 Apotheosis、{@code AncientReforgeMixin} 管远古),
 * 而本模组最初只移植了 Apotheosis 那一份 —— 于是远古重铸台一条注入都不会 fire,
 * 表现就是"重铸台不生效(材料/符石照扣)",日志里连一行附魔珍珠的重铸记录都没有。</p>
 *
 * <h2>注入点(javap 核对 ancientreforging-1.3.jar)</h2>
 * <ul>
 *   <li>{@code AncientReforgingMenu#getSigilCost(I)I} —— 新生符石消耗,唯一来源就是它
 *       ({@code costs[0] * (slot+1)}),结果槽的 {@code onTake} 与 {@code mayPickup} 都用它;</li>
 *   <li>{@code AncientReforgingMenu#getMatCost(I)I} / {@code getMatCount()I} —— 珍宝材料;</li>
 *   <li>字段 {@code player} 是模组自己的字段(不在混淆表里),所以 {@code remap = false}。</li>
 * </ul>
 *
 * <h2>诊断(为什么每次都打日志)</h2>
 * <p>进方法就打一条(<b>不受 {@code debugLog} 门控</b>、5 秒节流),命中归零再打一条。
 * 这样下次看日志就能立刻区分"根本没进这个类(选错菜单)"和"进了但判断没通过"。</p>
 */
@Mixin(targets = "com.ianm1647.ancientreforging.screen.AncientReforgingMenu")
public abstract class AncientReforgeMixin {

    /** {@code AncientReforgingMenu#player}(模组自己的字段名,remap = false)。 */
    @Shadow(remap = false)
    protected Player player;

    /** 新生符石归零。 */
    @Inject(method = "getSigilCost(I)I", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void enigmaticcurio$freeAncientSigil(int slot, CallbackInfoReturnable<Integer> cir) {
        try {
            PearlHandler.warnThrottled("enter-ancient-sigil",
                "[附魔珍珠-远古重铸] 进入 getSigilCost(slot={}) 玩家={}", slot, playerName());
            if (gate("getSigilCost")) {
                cir.setReturnValue(0);
            }
        }
        catch (RuntimeException | LinkageError e) {
            DebugLog.warn("[附魔珍珠-远古重铸] getSigilCost 注入异常({}),按原样扣符石", e.toString());
        }
    }

    /** 珍宝材料归零。 */
    @Inject(method = "getMatCost(I)I", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void enigmaticcurio$freeAncientMat(int slot, CallbackInfoReturnable<Integer> cir) {
        try {
            PearlHandler.warnThrottled("enter-ancient-mat",
                "[附魔珍珠-远古重铸] 进入 getMatCost(slot={}) 玩家={}", slot, playerName());
            if (gate("getMatCost")) {
                cir.setReturnValue(0);
            }
        }
        catch (RuntimeException | LinkageError e) {
            DebugLog.warn("[附魔珍珠-远古重铸] getMatCost 注入异常({}),按原样扣材料", e.toString());
        }
    }

    /** 材料数量(无参的那个)归零。 */
    @Inject(method = "getMatCount()I", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void enigmaticcurio$freeAncientMatCount(CallbackInfoReturnable<Integer> cir) {
        try {
            PearlHandler.warnThrottled("enter-ancient-matcount",
                "[附魔珍珠-远古重铸] 进入 getMatCount() 玩家={}", playerName());
            if (gate("getMatCount")) {
                cir.setReturnValue(0);
            }
        }
        catch (RuntimeException | LinkageError e) {
            DebugLog.warn("[附魔珍珠-远古重铸] getMatCount 注入异常({}),按原样扣材料", e.toString());
        }
    }

    /** 是否该把消耗归零(配置 + 珍珠在场)。 */
    private boolean gate(String method) {
        if (!CurioConfig.PEARL_FIX_REFORGE.get()) {
            PearlHandler.warnThrottled("ancient-cfg-off",
                "[附魔珍珠-远古重铸] 配置 pearlFixReforge=false,远古重铸台不免费");
            return false;
        }
        if (this.player == null || !PearlHandler.pearlPresent(this.player)) {
            return false;
        }
        PearlHandler.warnThrottled("ancient-hit-" + method,
            "[附魔珍珠-远古重铸] {} 归零(珍珠在场)", method);
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
