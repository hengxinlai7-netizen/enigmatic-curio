package com.hengx.enigmaticcurio.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.hengx.enigmaticcurio.CurioConfig;
import com.hengx.enigmaticcurio.DebugLog;
import com.hengx.enigmaticcurio.PearlHandler;

import dev.shadowsoffire.apotheosis.ench.table.ApothEnchantmentMenu;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 「附魔师的珍珠」在神化附魔台上的修复(神化那一侧,不动神化本体逻辑)。
 *
 * <p>目标 {@code ApothEnchantmentMenu#clickMenuButton(Player,int)}。字节码已核对
 * (Apotheosis 1.20.1-7.4.8,{@code javap -c}):方法名是 SRG 的 {@code m_6366_},
 * 里面就是原版那套"青金石够不够 → {@code access.execute(lambda)} 真附魔",
 * 没有额外的自定义网络包绕过它。</p>
 *
 * <ul>
 *   <li>HEAD:珍珠在场时,把青金石槽临时补成 64,绕过神化自己的"青金石数量"检查
 *       —— 等价于神秘遗物给原版 {@code getGoldCount()} 返回 64 的效果,
 *       同时记下输入栈快照与威力;</li>
 *   <li>RETURN:先把青金石槽恢复原样(所以神化 lambda 里的 {@code lapis.shrink(j)} 实际扣的是临时栈,
 *       净效果等于"附魔不再消耗青金石"),再按神秘遗物原版公式补一次 {@code min(cost+7,40)} 的附魔并合并。</li>
 * </ul>
 *
 * <p>神化自己的附魔选择(eterna/quanta/arcana、infusion 等)完全不变 —— 只在其结果上追加珍珠那部分。</p>
 *
 * <p><b>诊断</b>:上一版"mixin 已 apply 但游戏内无效果",所以这里每个关键分支都打
 * {@link DebugLog#warn}(不受配置门控、5 秒节流),一眼能看出是"没进方法 / 配置关 / 珍珠判定否 /
 * 返回 false / 物品没变化"中的哪一种。</p>
 */
@Mixin(ApothEnchantmentMenu.class)
public abstract class PearlApothEnchantMixin {

    @Unique
    private ItemStack enigmaticcurio$savedLapis;

    @Unique
    private ItemStack enigmaticcurio$inputSnapshot;

    @Unique
    private int enigmaticcurio$cost;

    @Inject(method = "m_6366_(Lnet/minecraft/world/entity/player/Player;I)Z",
        at = @At("HEAD"), remap = false)
    private void enigmaticcurio$beforeClick(Player player, int id, CallbackInfoReturnable<Boolean> cir) {
        try {
            this.enigmaticcurio$savedLapis = null;
            this.enigmaticcurio$inputSnapshot = null;
            DebugLog.warn("[附魔珍珠] 进入 ApothEnchantmentMenu#clickMenuButton id={}", id);
            if (!CurioConfig.PEARL_FIX_ENCHANTING.get()) {
                PearlHandler.warnThrottled("cfg-off", "[附魔珍珠] 配置 pearlFixEnchanting=false,跳过");
                return;
            }
            if (id < 0 || id > 2) {
                return;
            }
            if (!PearlHandler.pearlPresent(player)) {
                return;
            }
            EnchantmentMenu self = (EnchantmentMenu) (Object) this;
            this.enigmaticcurio$cost = self.costs[id];
            this.enigmaticcurio$inputSnapshot = self.getSlot(0).getItem().copy();
            this.enigmaticcurio$savedLapis = self.getSlot(1).getItem().copy();
            self.getSlot(1).set(new ItemStack(Items.LAPIS_LAZULI, 64));
            DebugLog.warn("[附魔珍珠] 已临时补满青金石槽(原青金石 {} 个),cost[{}]={}",
                this.enigmaticcurio$savedLapis.getCount(), id, this.enigmaticcurio$cost);
        }
        catch (RuntimeException | LinkageError e) {
            DebugLog.warn("[附魔珍珠] HEAD 注入异常({}),按神化原逻辑附魔", e.toString());
        }
    }

    @Inject(method = "m_6366_(Lnet/minecraft/world/entity/player/Player;I)Z",
        at = @At("RETURN"), remap = false)
    private void enigmaticcurio$afterClick(Player player, int id, CallbackInfoReturnable<Boolean> cir) {
        try {
            ItemStack saved = this.enigmaticcurio$savedLapis;
            ItemStack before = this.enigmaticcurio$inputSnapshot;
            int cost = this.enigmaticcurio$cost;
            this.enigmaticcurio$savedLapis = null;
            this.enigmaticcurio$inputSnapshot = null;
            if (saved == null) {
                return;
            }
            EnchantmentMenu self = (EnchantmentMenu) (Object) this;
            // 复原青金石槽:神化 lambda 扣掉的是我们的临时栈,所以真实青金石一点没少。
            self.getSlot(1).set(saved);
            if (!Boolean.TRUE.equals(cir.getReturnValue()) || before == null || before.isEmpty()) {
                DebugLog.warn("[附魔珍珠] 这次点击没真的附魔(return={},输入为空={}),不补掷",
                    cir.getReturnValue(), before == null || before.isEmpty());
                return;
            }
            ItemStack now = self.getSlot(0).getItem();
            if (now.isEmpty() || ItemStack.matches(before, now)) {
                // 这次点击没能真的附魔(例如经验不足),不补掷。
                DebugLog.warn("[附魔珍珠] 输入栈没有变化(now空={}),不补掷", now.isEmpty());
                return;
            }
            self.getSlot(0).set(PearlHandler.applyExtraEnchant(player, now, before, cost));
        }
        catch (RuntimeException | LinkageError e) {
            DebugLog.warn("[附魔珍珠] RETURN 注入异常({})", e.toString());
        }
    }
}
