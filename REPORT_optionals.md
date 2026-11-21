# enigmatic-curio 可选依赖改造报告

产物：`C:\Users\hengx\Downloads\[神秘遗物系饰品增强] enigmaticcurio-1.1.9.jar`
大小 **170,036 字节**，sha256 **5D52A44C70604707C043E128CD513D0E58E7664CE34F67EAF48D05C578BD25F0**
版本号未变（1.1.9），entries 87。

---

## 1. mods.toml 改动前后

### 改动前

| modId | mandatory | versionRange |
|---|---|---|
| forge | true | [47,) |
| minecraft | true | [1.20.1,1.21) |
| **enigmaticlegacy** | **false** | [2.0,) |
| enigmaticaddons | false | [1.2,) |
| enigmaticdelicacy | false | [1.0,) |
| **curios** | **false** | [5,) |
| apotheosis | false | [7,) |

### 改动后（即当前 jar 内的实际内容）

| modId | mandatory | versionRange | 说明 |
|---|---|---|---|
| forge | true | [47,) | 不变 |
| minecraft | true | [1.20.1,1.21) | 不变 |
| **enigmaticlegacy** | **true** | [2.0,) | ← 改为必需 |
| enigmaticaddons | false | [1.2,) | 不变 |
| enigmaticdelicacy | false | [1.0,) | 不变 |
| **curios** | **true** | [5,) | ← 改为必需 |
| apotheosis | false | [7,) | 不变 |
| **ancientreforging** | false | [1.0,) | ← 新增可选声明 |
| **rs_integration** | false | [1.0,) | ← 新增可选声明 |

实际 modid 取自各 jar 的 `META-INF/mods.toml`（不是猜的）：

```
ancientreforging-1.3.jar      -> modId = "ancientreforging"
rs_integration-1.5.2.3.jar    -> modId = "rs_integration"
```

与 `enigmatic-enhance` 对照，必需前置已一致：

```
enigmatic-enhance:  enigmaticlegacy=true [2.0.0,)   curios=true [5.0.0,)
enigmatic-curio  :  enigmaticlegacy=true [2.0,)     curios=true [5,)
```

> 注：`ancientreforging` 自身强制依赖 `placebo` + `apotheosis`，`rs_integration` 对 `refinedstorage` 是 `mandatory=false`，所以本模组只把这两个声明成可选、不连带声明它们的依赖。

---

## 2. mixin 配置拆分前后

### 改动前

`enigmaticcurio.mixins.json`（required:false，defaultRequire:0）—— **17 个**

```
RevivalLeafFlightMixin        EnderChestExpansionMixin    EnderChestBlockMenuMixin
EnderRingLargeMenuMixin       BlessRingMixin              BlessRingBarMixin
BlessRingHelperMixin          BlessRingTickMixin          AddonThunderLightningMixin
AddonMiningMixin              EnchantmentHelperSweepMixin FourthCurseMixin
FourthCurseAddonMixin         PearlApothEnchantMixin      PearlReforgeMixin
AncientReforgeMixin           AugmentingSigilMixin
```

`enigmaticcurio.optional.mixins.json` —— **3 个**

```
ApothSocketingRecipeMixin   RsiJeiInventoryEntryMixin   RsiJeiInventoryPacketMixin
```

### 改动后

`enigmaticcurio.mixins.json` —— **5 个（只依赖原版 / 神秘遗物本体）**

| Mixin | target | 归属 |
|---|---|---|
| EnderChestBlockMenuMixin | `net.minecraft.world.level.block.EnderChestBlock` | 原版 |
| EnderChestExpansionMixin | `net.minecraft.world.SimpleContainer` | 原版 |
| EnchantmentHelperSweepMixin | `net.minecraft.world.item.enchantment.EnchantmentHelper` | 原版 |
| EnderRingLargeMenuMixin | `com.aizistral.enigmaticlegacy.packets.server.PacketEnderRingKey` | 神秘遗物本体 |
| FourthCurseMixin | `com.aizistral.enigmaticlegacy.handlers.EnigmaticEventHandler` | 神秘遗物本体 |

`enigmaticcurio.optional.mixins.json` —— **15 个**

| Mixin | target | 归属模组 |
|---|---|---|
| RevivalLeafFlightMixin | `auviotre...contents.items.RevivalLeaf` | 神秘遗物扩展 |
| BlessRingMixin | `auviotre...contents.items.BlessRing` | 神秘遗物扩展 |
| BlessRingBarMixin | `auviotre...contents.items.BlessRing` | 神秘遗物扩展 |
| BlessRingHelperMixin | `auviotre...contents.items.BlessRing$Helper` | 神秘遗物扩展 |
| BlessRingTickMixin | `auviotre...contents.items.BlessRing` | 神秘遗物扩展 |
| AddonThunderLightningMixin | `auviotre...handlers.AddonEventHandler` | 神秘遗物扩展 |
| AddonMiningMixin | `auviotre...handlers.AddonEventHandler` | 神秘遗物扩展 |
| FourthCurseAddonMixin | `auviotre...handlers.AddonEventHandler` | 神秘遗物扩展 |
| PearlApothEnchantMixin | `dev.shadowsoffire...ench.table.ApothEnchantmentMenu` | 神化 |
| PearlReforgeMixin | `dev.shadowsoffire...affix.reforging.ReforgingMenu` | 神化 |
| AugmentingSigilMixin | `dev.shadowsoffire...affix.augmenting.AugmentingMenu` | 神化 |
| ApothSocketingRecipeMixin | `dev.shadowsoffire...adventure.socket.SocketingRecipe` | 神化 |
| AncientReforgeMixin | `com.ianm1647.ancientreforging.screen.AncientReforgingMenu` | 古物重铸 |
| RsiJeiInventoryEntryMixin | `com.huanghuang...JeiNetworkInventoryPacket$Entry` | rs_integration |
| RsiJeiInventoryPacketMixin | `com.huanghuang...JeiNetworkInventoryPacket` | rs_integration |

两个配置都保持 `"required": false` + `"injectors": {"defaultRequire": 0}`，所以 target 缺失时 Mixin 打一条 WARN 就跳过。
MANIFEST 的 `MixinConfigs` 仍是两个配置（因 72 列折行，展开后为 `enigmaticcurio.mixins.json,enigmaticcurio.optional.mixins.json`）。

---

## 3. 代码守卫审计（全量扫描 `src/main/java`，56 个 .java）

### 方法

1. 全部 `import` 根包统计
2. 对**编译产物**（71 个 class）逐个解析常量池，找出对 `auviotre.` / `dev.shadowsoffire.` / `com.ianm1647.` / `com.huanghuang.` / `top.theillusivec4.` / `com.aizistral.` 的**真实类型引用**（Class 常量、Fieldref/Methodref owner、注解里的类型描述符）

### 结果

| 第三方命名空间 | 引用它的类 | 其中非 mixin |
|---|---|---|
| `auviotre/`（神秘遗物扩展） | 7 个，全是 mixin | **0** |
| `dev/shadowsoffire/`（神化） | 3 个，全是 mixin | **0** |
| `com/huanghuang/`（rs_integration） | 2 个，全是 mixin | **0** |
| `com/ianm1647/`（古物重铸） | **0 个**（`AncientReforgeMixin` 只用 `@Mixin(targets="...")` 字符串，连类型描述符都没有） | **0** |
| `top/theillusivec4/`（curios） | 2 个 mixin | **0**（且 curios 已是必需） |
| `com/aizistral/`（神秘遗物本体） | 4 个 | **1**：`FourthCurseNeutral` → `com.aizistral.omniconfig...`（omniconfig 随神秘遗物本体一起打包，本体已是必需，**不动**） |

**结论：除 mixin 外没有任何代码直接引用第三方模组类，无需新增 `isLoaded` 守卫。**

已有的守卫方式（全部为 `Class.forName("字面量")` + `catch (ReflectiveOperationException/Throwable | LinkageError | RuntimeException)`，且都在**方法体内**而非 static 初始化块，所以类加载期不会解析）：

| 文件 | 触碰方式 | 守卫 |
|---|---|---|
| `ForgerGemAnvilHandler` | `Class.forName(APOTH_*)` | `ModList.get().isLoaded("apotheosis")` + try/catch（已有，够用） |
| `PearlHandler` | `probeClass("dev.shadowsoffire...")` 等 7 处 | 全部 `Class.forName(..., false, cl)` + `catch` |
| `BookBag` | `SuperAddonHandler` / `IAntiqueBagHandler` / `AntiqueBagCapability` | `Class.forName` + `catch (ReflectiveOperationException \| RuntimeException \| LinkageError)` |
| `BagHudEffects` | `ArtificialFlower` / `TheBless` / `SuperAddonHandler` | 同上，`catch (Throwable)` |
| `ScrollHandler` | `AddonEventHandler` | `catch (ClassNotFoundException)` 单独分支 |
| `FourthCurseFix` | `SuperAddonHandler` | 同上 |
| `CuriosCompat` | 纯反射（`Constructor`/`Method`） | 无第三方 import |

`EnigmaticCurio`（`@Mod` 入口）只做事件总线注册 / 配置 / 网络通道，**没有任何 `DeferredRegister` / `RegistryObject`**，所以"注册期触碰第三方"这一条根本不存在。

### ④ lang 与注册表

* 本模组**不注册任何物品/方块/配方**（全项目 0 处 `DeferredRegister`/`RegistryObject`），所以没有"缺模组时注册失败"的风险。
* `assets/enigmaticcurio/lang/*.json` 只是本模组的提示文本，缺第三方时对应提示不出现，不会报错。

---

## 4. 启动验证

测试服用独立目录（`_curio_min`、`_curio_full`），`libraries` 以 junction 指向已有的 Forge `1.20.1-47.2.20` 服务端库，未触碰 `D:\Minecraft` 与用户实例。

### ① 最小环境（forge 47.2.20 + curios + Patchouli + caelus + Enigmaticlegacy + enigmaticcurio）

> Patchouli 和 caelus 是 Enigmaticlegacy 自己声明的**必需**前置（`mandatory=true`），所以最小环境必须带它们，否则是 EL 起不来而不是本模组的问题。

```
[20:25:02] [main/INFO] [mixin/]: Mixing EnderChestBlockMenuMixin from enigmaticcurio.mixins.json into net.minecraft.world.level.block.EnderChestBlock
[20:25:07] [main/INFO] [mixin/]: Mixing EnderChestExpansionMixin from enigmaticcurio.mixins.json into net.minecraft.world.SimpleContainer
[20:25:09] [modloading-worker-0/INFO] [mixin/]: Mixing FourthCurseMixin from enigmaticcurio.mixins.json into com.aizistral.enigmaticlegacy.handlers.EnigmaticEventHandler
[20:25:10] [modloading-worker-0/INFO] [mixin/]: Mixing EnderRingLargeMenuMixin from enigmaticcurio.mixins.json into com.aizistral.enigmaticlegacy.packets.server.PacketEnderRingKey

[20:25:02] [main/WARN] [mixin/]: @Mixin target auviotre.enigmatic.addon.contents.items.RevivalLeaf was not found enigmaticcurio.optional.mixins.json:RevivalLeafFlightMixin
[20:25:02] [main/WARN] [mixin/]: @Mixin target auviotre.enigmatic.addon.contents.items.BlessRing was not found enigmaticcurio.optional.mixins.json:BlessRingMixin
...（共 15 条，全部是 optional 配置的第三方 target 缺失）

[20:26:11] [Server thread/INFO] [minecraft/DedicatedServer]: Done (5.654s)! For help, type "help"
```

错误计数（`min_run3.log`）：

```
InvalidInjectionException             0
MixinApplyError                       0
NoClassDefFoundError                  0
NoSuchMethodError                     0
ExceptionInInitializerError           0
[mixin/ERROR]                          0
Missing or unsupported mandatory      0
```

说明：日志里会出现 18 条 `[mixin/] Error loading class: auviotre/... (java.lang.ClassNotFoundException)` —— 这是 **Mixin 自己探测 target 时打的**，紧跟着就是 `target ... was not found` 跳过，属于任务允许的正常现象；本模组代码自身没有任何 CNFE/NCDE/NSME。

### ② 全环境（再加 enigmaticaddons + Apotheosis + Placebo + ApothicAttributes + ancientreforging + rs_integration）

```
[20:22:47] Mixing RevivalLeafFlightMixin from enigmaticcurio.optional.mixins.json into auviotre.enigmatic.addon.contents.items.RevivalLeaf
[20:22:47] Mixing BlessRingMixin from enigmaticcurio.optional.mixins.json into auviotre.enigmatic.addon.contents.items.BlessRing
[20:22:47] Mixing BlessRingBarMixin from enigmaticcurio.optional.mixins.json into auviotre.enigmatic.addon.contents.items.BlessRing
[20:22:47] Mixing BlessRingTickMixin from enigmaticcurio.optional.mixins.json into auviotre.enigmatic.addon.contents.items.BlessRing
[20:22:47] Mixing AddonThunderLightningMixin from enigmaticcurio.optional.mixins.json into auviotre.enigmatic.addon.handlers.AddonEventHandler
[20:22:47] Mixing AddonMiningMixin from enigmaticcurio.optional.mixins.json into auviotre.enigmatic.addon.handlers.AddonEventHandler
[20:22:47] Mixing FourthCurseAddonMixin from enigmaticcurio.optional.mixins.json into auviotre.enigmatic.addon.handlers.AddonEventHandler
[20:22:48] Mixing PearlApothEnchantMixin from enigmaticcurio.optional.mixins.json into dev.shadowsoffire.apotheosis.ench.table.ApothEnchantmentMenu
[20:22:48] Mixing AncientReforgeMixin from enigmaticcurio.optional.mixins.json into com.ianm1647.ancientreforging.screen.AncientReforgingMenu
[20:22:48] Mixing PearlReforgeMixin from enigmaticcurio.optional.mixins.json into dev.shadowsoffire.apotheosis.adventure.affix.reforging.ReforgingMenu
[20:22:48] Mixing AugmentingSigilMixin from enigmaticcurio.optional.mixins.json into dev.shadowsoffire.apotheosis.adventure.affix.augmenting.AugmentingMenu
[20:22:50] Mixing RsiJeiInventoryPacketMixin from enigmaticcurio.optional.mixins.json into com.huanghuang.rsintegration.network.packet.JeiNetworkInventoryPacket
[20:23:17] Mixing ApothSocketingRecipeMixin from enigmaticcurio.optional.mixins.json into dev.shadowsoffire.apotheosis.adventure.socket.SocketingRecipe

[20:23:23] [Server thread/INFO] [minecraft/DedicatedServer]: Done (4.455s)! For help, type "help"
```

* 可选 mixin **13/15 已在启动过程中注入成功**，0 个注入失败。
* 剩下 2 个（`BlessRingHelperMixin` → `BlessRing$Helper`、`RsiJeiInventoryEntryMixin` → `JeiNetworkInventoryPacket$Entry`）以及主配置的 `EnchantmentHelperSweepMixin`（→ `EnchantmentHelper`）在**这次 4 秒无头启动里目标类没被加载**，所以 Mixin 还没触发；这是惰性应用的正常状态，不是失败（它们既没有 `target was not found`，也没有任何注入异常）。
* 全环境里 4 条 `target ... was not found` 全部来自 **rs_integration 自己的配置**（sophisticatedcore / moonstone / malum / wizard_terra_cuiros，本次没装），与本模组无关。

---

## 5. 静态校验

| 项 | 结果 |
|---|---|
| 主 mixin 配置里是否还有第三方 target | **没有**（5 个全是原版或神秘遗物本体） |
| mods.toml enigmaticlegacy / curios | 均 `mandatory=true` |
| 编译产物对第三方模组的类型引用 | 仅存在于 mixin 类（`FourthCurseNeutral` 用的是神秘遗物本体自带的 omniconfig，本体必需） |
| `javap` 抽查 | 对 71 个 class 做了常量池级扫描（比 javap 抽查更全），结论同上 |

---

## 6. 两点需要知道的

1. **jar 内没有 `enigmaticcurio.refmap.json`**，启动时会各打一条
   `Reference map 'enigmaticcurio.refmap.json' ... could not be read` 的 WARN。
   **这是改动前就有的现象**（旧的 1.1.9 成品 jar 里同样没有 refmap），不影响功能：本模组所有 `@Inject` 都写了 `remap = false` 且直接使用 SRG 名（如 `m_6227_`、`m_44821_`）或第三方方法名，不需要 refmap 重映射。本次未改变这一点。
2. 改造后**必需前置 = 神秘遗物 + Curios（+ 它们自己的前置 Patchouli / caelus）**；缺神秘遗物或 Curios 时 Forge 会直接报 `Missing or unsupported mandatory dependencies` 拒绝加载——这是本次改动的预期效果，与 `enigmatic-enhance` 一致。

---

## 7. 复现命令

```powershell
# 构建
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-21.0.8.9-hotspot'
$env:GRADLE_USER_HOME="$ws\.gradle-home"
gradle --offline --console=plain --no-daemon build     # BUILD SUCCESSFUL in 28s

# 两个测试服（_curio_min / _curio_full）
powershell -NoProfile -ExecutionPolicy Bypass -File _curio_test\setup.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File _curio_test\run.ps1 `
    -ServerDir ..._curio_min -LogName min_run3.log -ExtraJvm '-Dmixin.debug.verbose=true'
```

产物日志：`_curio_min\min_run3.log`、`_curio_full\full_run1.log`。
