package com.hengx.enigmaticcurio;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 神秘遗物扩展:取消「失落引擎」的雷击惩罚。
 *
 * <p>不在 EL 的代码里改,而是在雷击实体进入世界时判断:如果附近有佩戴失落引擎的玩家,
 * 直接丢弃这根雷。这样惩罚不会落地,加成照常。</p>
 */
public final class EngineGuardHandler {

    private static final String ENGINE_ID = "enigmaticaddons:lost_engine";

    private static Item engine;

    private EngineGuardHandler() {
    }

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        try {
            if (event.getLevel().isClientSide()) {
                return;
            }
            Entity entity = event.getEntity();
            if (entity.getType() != EntityType.LIGHTNING_BOLT) {
                return;
            }
            if (!CurioConfig.ENGINE_GUARD.get()) {
                return;
            }
            LightningBolt bolt = (LightningBolt) entity;
            if (bolt.getCause() != null && !(bolt.getCause() instanceof Player)) {
                return;   // 只处理玩家引起的雷(引擎惩罚就是玩家引起的)
            }
            if (engine == null) {
                engine = Superposition.item(ENGINE_ID);
            }
            if (engine == null) {
                return;
            }
            for (Player p : event.getLevel().getEntitiesOfClass(Player.class, bolt.getBoundingBox().inflate(4.0D))) {
                if (Superposition.hasCurio(p, engine)) {
                    event.setCanceled(true);
                    DebugLog.info("[引擎] 已拦下 {} 的雷击惩罚", p.getName().getString());
                    return;
                }
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }
}