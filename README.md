# 神秘遗物系饰品增强 / Enigmatic Curio

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-green.svg)](https://www.minecraft.net/)
[![Forge](https://img.shields.io/badge/Forge-47.2.20%2B-orange.svg)](https://files.minecraftforge.net/)
[![Version](https://img.shields.io/badge/Version-1.1.9-blue.svg)](#)

对 **神秘遗物 / 神秘遗物扩展 / 神秘佳肴** 的**现有饰品**做的增强，不新增任何物品。

**必需前置只有两个：神秘遗物 + Curios。**

其余模组（神秘遗物扩展、神化、古物重铸、神秘佳肴）全部为**可选联动** ——
装上对应模组才会启用相关功能，没装时静默跳过，不影响启动。

## 功能

| 来源 | 内容 |
|---|---|
| 神秘遗物 | 附魔师珍珠兼容神化附魔台与重铸台；七咒之戒末影箱扩容到 54 格；书籍可锁定不打开 |
| 神秘遗物扩展 | 血战沙场之证锁 1 血；古旧书袋效果显示重构（列出袋内全部书：有效果的写具体数值，没效果的标「仅可存放，无加成」）；取消失落引擎雷击；救赎之戒永不破损且可与七咒之戒同戴；冒险者徽章可挖掘并死亡转化；锻造者宝石铁砧直升满级；万钧之护雷击开关与加成配置；第四诅咒修正；无知诅咒卷轴经验上限 |
| 神秘佳肴 | 无止暴食之证饱食锁 0 / 饱和锁 1 |
| 其它 | 磁吸戒指 36 格；复苏之叶飞行开关 |

## 安装

放入 `mods/`。需要 Minecraft 1.20.1 / Forge 47.2.20+。

### 前置

| 类型 | 模组 | 说明 |
|---|---|---|
| **必需** | 神秘遗物 (Enigmatic Legacy) | 2.0+，缺失时 Forge 拒绝加载 |
| **必需** | Curios | 5.x，缺失时 Forge 拒绝加载 |
| **可选** | 神秘遗物扩展 (Enigmatic Addons) | 1.2+，启用扩展饰品相关增强 |
| **可选** | 神化 (Apotheosis) | 7.x，启用附魔台 / 重铸台 / 镶嵌相关兼容 |
| **可选** | 古物重铸 (Ancient Reforging) | 1.0+ |
| **可选** | 神秘佳肴 (Enigmatic Delicacy) | 1.0+ |

**可选模组一律通过独立的 `enigmaticcurio.optional.mixins.json` 注入**：

- 装了 → 对应 mixin 正常注入，功能生效
- 没装 → 整份配置静默跳过，连警告都不打

因此**只装神秘遗物 + Curios 也能正常运行**，不会因为缺扩展/神化而报错。

## 构建

```bash
export JAVA_HOME=<JDK 17+>
export GRADLE_USER_HOME=<gradle home>
./gradlew build --offline
```

## 协议

MIT

## 更新记录

### 1.1.9

* **前置收窄为「神秘遗物 + Curios」**：`mods.toml` 里 `enigmaticlegacy` / `curios` 改为
  强制依赖，其余全部可选；补上 `ancientreforging` 的可选声明。
* **移除 rs_integration 联动**：RS 集成那个「JEI 库存同步包带空堆栈导致客户端掉线」的问题
  交由该模组作者处理，本模组不再注入其网络包。相关两个 mixin 与记账类已删除、
  `rs_integration` 依赖声明一并去掉，`optional.mixins.json` 从 15 项回到 13 项。
* **mixin 配置拆分**：主配置从 17 个瘦身到 5 个（只留原版 + 神秘遗物本体）；
  8 个扩展、3 个神化、1 个古物重铸的 mixin 全部移入
  `enigmaticcurio.optional.mixins.json`，没装对应模组时只留一条
  `@Mixin target ... was not found` 的 WARN，不会注入失败。
* **修正日志占位符**：项目里混用了 slf4j 的 `{}` 和 `String.format` 的 `%s`，导致部分日志
  原样打出 `{}` 而参数全丢（例如 `注入目标存在:{}#{}`）。`DebugLog` 现在统一把 `{}` 归一为 `%s`。
* **修复「万钧之护」雷击开关失效**：扩展的雷击分两段 —— `onEntityHurt` 累积电荷、
  `onTick` 电荷过 1200 才真正劈雷。以前只拦了第一段，所以目标身上残留的电荷
  （关开关前打出来的、别的路径写进去的、旧存档带过来的）照样会落雷。
  现在落雷那一刻会再核一次开关，关掉即清零电荷、绝不落雷；被拦下的攻击也会顺手
  清掉目标身上的旧电荷。另加一层不依赖 mixin 的兜底（`LivingHurtEvent` LOWEST）。
* 配置 `enigmaticcurio.addons.scaleLightning` / `scaleEnabled` **改完立即生效**，不用重启；
  启动与重载时各打一条当前值（`debugLog=true` 时可见）。
* 新增启动自检：扩展的注入目标（`onEntityHurt` / `onTick`）缺失时会在日志里明确报出来。
* 修正日志格式：全项目的 `%s` 此前被原样打印（slf4j 只认 `{}`），现在会正常替换成实际值。
* 卷轴 tooltip：配置里关掉雷击时明确提示「点开关不会恢复」。
* **古旧书袋效果显示重构**（与 `enigmatic-enhance 4.8.4` 对齐）：
  * 移植 `BookEffects` 注册表：**17 本书、30 条效果文本**（神秘遗物本体 5 + 扩展 4 + 诡厄遗物 8），
    数值在服务端读本体/扩展/诡厄的真实配置（反射，缺模组用代码默认值）；
    本体/扩展/诡厄共 22 本可入袋书籍，未登记文案的走兜底「仅可存放，无加成」。
    主模组自建的 7 件物品（睡前读物 / 历史迷雾 / 灵界之光 / 冥河之水 / 战争迷雾 / 瓶装太阳 / 渔获瓶）
    本模组不新增物品，未收录。
  * **袋内枚举改为遍历 `AntiqueBagCapability.INVENTORY`**：不再按 9 个白名单 id 逐个问
    「在不在袋里」，而是把袋里每本书都列出来 —— 白名单只决定文案；同一本书放两本只算一条。
  * 显示优化 5 项：① 属性名本地化（`Attribute#getDescriptionId`）② 乘法修饰符转百分比
    （`+0.1` → `+10%`）③ 剩余时长 ≥20 tick 转秒（保留一位小数）④ 「有效果 → 未收录 → 无加成」
    三段排序 + 按书 id 去重 ⑤ 超过 12 条折成「… 还有 N 条（无加成）」。
  * 新增自检：`-Denigmaticcurio.selftest=bag` 启动服务端后自动用假玩家走一遍
    取书袋 capability → 写入样本 → 枚举 → 排序/去重 → 签名/协议往返，结果以 `[书袋自检]` 打进日志。