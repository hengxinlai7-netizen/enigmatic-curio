package com.hengx.enigmaticcurio.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.NonNullList;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;

/**
 * 1.2.20:把**原版末影箱**从 27 格扩到 54 格(等同大箱子)。
 *
 * <p>1.20.1 的末影箱是 {@code PlayerEnderChestContainer extends SimpleContainer},
 * 容量写死在这两个 final 字段上:</p>
 * <pre>
 *   private final int f_19146_;                       // 容量
 *   private final NonNullList&lt;ItemStack&gt; f_19147_;   // 物品表
 * </pre>
 *
 * <p>这里直接在 {@code SimpleContainer} 的构造函数返回时判断"实例是不是末影箱容器",
 * 是就把两个字段换成 54 —— <b>不需要额外的鸭子接口</b>(上一版用接口跨类调用触发了
 * Mixin 的 IllegalClassLoadError,已撤掉)。</p>
 *
 * <p>**容器类型不变**:所有调用 {@code player.getEnderChestInventory()} 的模组自动看到 54 格;
 * 存档仍走原版 {@code EnderItems} 列表(按槽位索引),旧档 27 格照常加载,新格为空。</p>
 */
@Mixin(SimpleContainer.class)
public class EnderChestExpansionMixin {

    @Mutable
    @Shadow
    @Final
    private int f_19146_;

    @Mutable
    @Shadow
    @Final
    private NonNullList<ItemStack> f_19147_;

    @Inject(method = "<init>(I)V", at = @At("RETURN"), remap = false, require = 0)
    private void enigmaticcurio$expandEnderChest(int size, CallbackInfo ci) {
        try {
            if (!(((Object) this) instanceof PlayerEnderChestContainer)) {
                return;
            }
            if (this.f_19146_ == 54) {
                return;
            }
            this.f_19146_ = 54;
            this.f_19147_ = NonNullList.withSize(54, ItemStack.EMPTY);
        }
        catch (Throwable ignored) {
            // 扩容失败 ⇒ 保持原版 27 格
        }
    }
}