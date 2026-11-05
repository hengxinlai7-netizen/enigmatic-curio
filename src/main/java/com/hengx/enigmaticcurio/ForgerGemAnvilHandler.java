package com.hengx.enigmaticcurio;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraftforge.event.AnvilUpdateEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Map;

/**
 * 神秘遗物扩展:增强「锻造者宝石」。
 *
 * <p>原本铁砧把两本"同级同附魔"的书合成只会 +1 级。现在携带锻造者宝石时,
 * 直接升到该附魔的<b>最高等级</b>。</p>
 */
public final class ForgerGemAnvilHandler {

    private static final String GEM_ID = "enigmaticaddons:forger_gem";

    private static Item gem;

    private ForgerGemAnvilHandler() {
    }

    @SubscribeEvent
    public static void onAnvil(AnvilUpdateEvent event) {
        try {
            if (!CurioConfig.FORGER_GEM_MAX.get()) {
                return;
            }
            Player player = event.getPlayer();
            if (player == null) {
                return;
            }
            ItemStack left = event.getLeft();
            ItemStack right = event.getRight();
            if (!left.is(Items.ENCHANTED_BOOK) || !right.is(Items.ENCHANTED_BOOK)) {
                return;
            }
            if (!carrying(player)) {
                return;
            }
            Map<Enchantment, Integer> a = EnchantmentHelper.getEnchantments(left);
            Map<Enchantment, Integer> b = EnchantmentHelper.getEnchantments(right);
            if (a.size() != 1 || b.size() != 1) {
                return;
            }
            Map.Entry<Enchantment, Integer> ea = a.entrySet().iterator().next();
            Map.Entry<Enchantment, Integer> eb = b.entrySet().iterator().next();
            if (!ea.getKey().equals(eb.getKey()) || !ea.getValue().equals(eb.getValue())) {
                return;   // 必须同一附魔、同一等级
            }
            int max = ea.getKey().getMaxLevel();
            if (ea.getValue() >= max) {
                return;   // 已经满级
            }
            ItemStack out = new ItemStack(Items.ENCHANTED_BOOK);
            out.enchant(ea.getKey(), max);
            event.setOutput(out);
            event.setCost(Math.max(1, max));
            DebugLog.info("[锻造者宝石] {} 直升 {} 级", ea.getKey().getDescriptionId(), max);
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    private static boolean carrying(Player player) {
        if (gem == null) {
            gem = Superposition.item(GEM_ID);
        }
        if (gem == null) {
            return false;
        }
        if (Superposition.hasCurio(player, gem)) {
            return true;
        }
        for (ItemStack s : player.getInventory().items) {
            if (!s.isEmpty() && s.is(gem)) {
                return true;
            }
        }
        return false;
    }
}