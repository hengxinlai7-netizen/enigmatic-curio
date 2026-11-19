package com.hengx.enigmaticcurio;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;

/**
 * 「万钧之护」雷击开关的<b>自检</b> —— 只在带了
 * {@code -Denigmaticcurio.selftest=lightning} 启动服务端时才注册,正式游戏里完全不参与。
 *
 * <p>自检做的是玩家报告的那个场景本身(不靠人肉测试):</p>
 * <ol>
 *   <li><b>A:开关开着</b> —— 放一只电荷已经过阈值({@code Electric=5000})的僵尸,
 *       期望 {@code onTick} 劈雷(证明"开着时功能没被改坏");</li>
 *   <li><b>B:配置关掉雷击</b>({@code scaleLightning=false}) —— 同样放一只 {@code Electric=5000}
 *       的僵尸,期望<b>一道雷都不劈</b>,并且它的电荷被清掉
 *       (证明 1.1.9 的落雷闸生效,不再是"关了还落雷");</li>
 *   <li><b>C:开关复原</b> —— 确认还能再劈雷(证明开关是双向的,不是把雷击弄死了);</li>
 *   <li><b>D:逐件开关</b> —— 直接核对 {@link ScrollHandler#isThunderOff} / {@link ScrollHandler#setThunderOff}
 *       的 NBT 语义(键不存在 = 开)。</li>
 * </ol>
 *
 * <p>结果按 {@code [万钧自检]} 前缀打进服务端日志,最后一行是 {@code RESULT=...} 汇总。</p>
 */
public final class LightningSelfTest {

    private static final String PROP = "enigmaticcurio.selftest";

    /** 每个阶段给 60 tick(3 秒)让扩展的 onTick 有机会落雷。 */
    private static final int STAGE_TICKS = 60;

    private static boolean armed;
    private static int stage;
    private static int timer;
    private static ServerLevel level;
    private static BlockPos pos;
    private static Zombie mob;
    private static int boltsSeen;
    private static int pass;
    private static int fail;

    private LightningSelfTest() {
    }

    /** 由 {@code EnigmaticCurio} 构造器调用;没带 -D 参数时什么都不做。 */
    public static void register() {
        String value = System.getProperty(PROP);
        if (value == null || !value.contains("lightning")) {
            return;
        }
        armed = true;
        MinecraftForge.EVENT_BUS.addListener(LightningSelfTest::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(LightningSelfTest::onServerTick);
        DebugLog.infoAlways("[万钧自检] 已装载(selftest=lightning),服务端启动后自动跑");
    }

    private static void onServerStarted(ServerStartedEvent event) {
        if (!armed) {
            return;
        }
        try {
            level = event.getServer().overworld();
            if (level == null) {
                finish("没有主世界,自检放弃");
                return;
            }
            pos = level.getSharedSpawnPos();
            level.getChunk(pos);                       // 强制加载出生点区块
            level.setDayTime(18000L);                  // 午夜:僵尸不会白天烧起来
            stage = 1;
            timer = 0;
            spawnMob();
            DebugLog.infoAlways("[万钧自检] A 阶段:开关=开(scaleLightning=%s),僵尸电荷=5000,期望=落雷",
                CurioConfig.SCALE_LIGHTNING.get());
        }
        catch (RuntimeException | LinkageError e) {
            finish("A 阶段准备失败: " + e);
        }
    }

    private static void onServerTick(TickEvent.ServerTickEvent event) {
        if (!armed || event.phase != TickEvent.Phase.END || stage == 0) {
            return;
        }
        try {
            if (stage <= 3) {
                boltsSeen += countBolts();
                if (++timer < STAGE_TICKS) {
                    return;
                }
                advance();
            }
        }
        catch (RuntimeException | LinkageError e) {
            finish("运行中出错: " + e);
        }
    }

    private static void advance() {
        switch (stage) {
            case 1 -> {
                // A:开关开着 → 期望落雷
                report("A 开关=开", boltsSeen > 0, "60 tick 内落雷 " + boltsSeen + " 次,期望 > 0");
                despawn();
                CurioConfig.SCALE_LIGHTNING.set(false);
                CurioConfig.SCALE_LIGHTNING.save();
                stage = 2;
                timer = 0;
                boltsSeen = 0;
                spawnMob();
                DebugLog.infoAlways("[万钧自检] B 阶段:配置 scaleLightning=false,僵尸电荷=5000,期望=不落雷且电荷被清零");
            }
            case 2 -> {
                // B:关掉雷击 → 期望不落雷,且电荷被清
                boolean noBolt = boltsSeen == 0;
                boolean cleared = mob == null || !mob.getPersistentData().contains(ScrollHandler.ELECTRIC);
                report("B 开关=关", noBolt && cleared,
                    "60 tick 内落雷 " + boltsSeen + " 次(期望 0),电荷已清零=" + cleared);
                despawn();
                CurioConfig.SCALE_LIGHTNING.set(true);
                CurioConfig.SCALE_LIGHTNING.save();
                stage = 3;
                timer = 0;
                boltsSeen = 0;
                spawnMob();
                DebugLog.infoAlways("[万钧自检] C 阶段:把 scaleLightning 改回 true,期望重新落雷(确认开关是双向的)");
            }
            case 3 -> {
                report("C 开关=开(复原)", boltsSeen > 0, "60 tick 内落雷 " + boltsSeen + " 次,期望 > 0");
                despawn();
                stage = 4;
                testItemToggle();
                finish(null);
            }
            default -> finish(null);
        }
    }

    /** D:逐件开关的 NBT 语义(键不存在 = 开)。 */
    private static void testItemToggle() {
        try {
            Object item = ScrollHandler.scrollItem();
            if (!(item instanceof net.minecraft.world.item.Item minecraftItem)) {
                DebugLog.infoAlways("[万钧自检] D 逐件开关:扩展物品没找到(未装扩展),跳过");
                return;
            }
            net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(minecraftItem);
            boolean defaultOn = !ScrollHandler.isThunderOff(stack);
            ScrollHandler.setThunderOff(stack, true);
            boolean offWorks = ScrollHandler.isThunderOff(stack);
            ScrollHandler.setThunderOff(stack, false);
            boolean backOn = !ScrollHandler.isThunderOff(stack);
            report("D 逐件 NBT", defaultOn && offWorks && backOn,
                "默认开=" + defaultOn + ",写关后读到关=" + offWorks + ",清掉后回到开=" + backOn);
        }
        catch (RuntimeException | LinkageError e) {
            DebugLog.infoAlways("[万钧自检] D 逐件开关出错: " + e);
        }
    }

    // ------------------------------------------------------------------ 工具

    private static void spawnMob() {
        try {
            Zombie zombie = EntityType.ZOMBIE.create(level);
            if (zombie == null) {
                return;
            }
            zombie.moveTo(pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D, 0.0F, 0.0F);
            zombie.setNoAi(true);
            zombie.setInvulnerable(true);
            zombie.setPersistenceRequired();
            level.addFreshEntity(zombie);
            zombie.getPersistentData().putInt(ScrollHandler.ELECTRIC, 5000);
            mob = zombie;
            boltsSeen = 0;
        }
        catch (RuntimeException | LinkageError e) {
            finish("放僵尸失败: " + e);
        }
    }

    private static void despawn() {
        try {
            if (mob != null) {
                mob.discard();
                mob = null;
            }
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
    }

    /** 数一下僵尸周围 8 格内的闪电实体(扩展那道雷是 HarmlessThunder,存活时间很短,必须每 tick 数)。 */
    private static int countBolts() {
        if (mob == null) {
            return 0;
        }
        try {
            AABB box = new AABB(pos).inflate(8.0D);
            return level.getEntitiesOfClass(LightningBolt.class, box).size();
        }
        catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    private static void report(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            DebugLog.infoAlways("[万钧自检] PASS %s: %s", name, detail);
        }
        else {
            fail++;
            DebugLog.infoAlways("[万钧自检] FAIL %s: %s", name, detail);
        }
    }

    private static void finish(String error) {
        try {
            despawn();
            if (error != null) {
                DebugLog.infoAlways("[万钧自检] 中止: %s", error);
            }
            DebugLog.infoAlways("[万钧自检] RESULT pass=%s fail=%s %s",
                pass, fail, fail == 0 && error == null ? "ALL-PASS" : "HAS-FAIL");
        }
        catch (RuntimeException | LinkageError ignored) {
            // 忽略
        }
        finally {
            armed = false;
            stage = 0;
            // 自检跑完就把服务端关掉,别把 CI/批处理吊在那儿
            try {
                if (level != null && level.getServer() != null) {
                    level.getServer().halt(false);
                }
            }
            catch (RuntimeException | LinkageError ignored) {
                // 忽略
            }
        }
    }
}
