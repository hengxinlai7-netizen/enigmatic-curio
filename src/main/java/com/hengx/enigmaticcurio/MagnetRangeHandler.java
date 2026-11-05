package com.hengx.enigmaticcurio;

import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;

/** 磁吸戒指 / 超级磁吸戒指:吸取范围改为 36 格(纯反射,不改神秘遗物代码)。 */
public final class MagnetRangeHandler {

    public static final int TARGET_RANGE = 36;
    private static final int MIN_MAX = 64;

    private static final String[] CLASSES = {
        "com.aizistral.enigmaticlegacy.items.MagnetRing",
        "com.aizistral.enigmaticlegacy.items.SuperMagnetRing"
    };

    private MagnetRangeHandler() {
    }

    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(MagnetRangeHandler::apply);
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        apply();
    }

    public static void apply() {
        for (String name : CLASSES) {
            try {
                Class<?> type = Class.forName(name, false, MagnetRangeHandler.class.getClassLoader());
                Object param = type.getField("range").get(null);
                if (param == null) {
                    continue;
                }
                int oldMax = (int) param.getClass().getMethod("getMaxValue").invoke(param);
                if (oldMax > 0 && oldMax < TARGET_RANGE) {
                    param.getClass().getMethod("setMaxValue", int.class).invoke(param, MIN_MAX);
                }
                int before = (int) param.getClass().getMethod("getValue").invoke(param);
                param.getClass().getMethod("setValue", int.class).invoke(param, TARGET_RANGE);
                EnigmaticCurio.LOGGER.info("[磁吸] {} 吸取范围 {} -> {}", shortName(name), before, TARGET_RANGE);
            }
            catch (ClassNotFoundException ignored) {
                // 没装神秘遗物
            }
            catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                EnigmaticCurio.LOGGER.warn("[磁吸] 修改 {} 失败: {}", shortName(name), e.toString());
            }
        }
    }

    private static String shortName(String n) {
        int i = n.lastIndexOf('.');
        return i < 0 ? n : n.substring(i + 1);
    }
}