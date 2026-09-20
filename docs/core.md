# core 实现说明（t7）

范围：`:core`（纯 Kotlin/JVM）与本文档。core 不依赖 Android、Context、Binder、Xposed 或其他工程模块，
也不读取系统时钟——所有时间由调用方以 `SystemClock.elapsedRealtime()` 数值传入。

接口签名以 `docs/contracts.md` 为准；本次实现**没有修改任何冻结签名**（见第 6 节）。

## 1. 模块内容

| 文件 | 内容 | 状态 |
| --- | --- | --- |
| `config/ConfigModels.kt` | `ProtectionConfig`/`PackageConfig`/`PackagePolicy`/`CachedPackagePolicy`/`ValidationResult` | 完成 |
| `config/ConfigConstants.kt` | schema 版本、内置规则 ID、默认传感器集合、上限、保留包名 | 完成 |
| `config/ConfigValidator.kt` | 配置与包名校验（返回稳定错误码，不含用户输入内容） | 完成 |
| `config/ConfigCodec.kt` | 配置/策略/报告的 UTF-8 JSON 编解码与输入拒绝 | 完成 |
| `config/PolicyResolver.kt` | 三层开关求交、按包最小策略 | 完成 |
| `config/ConfigRepository.kt` | `Subscription`/`ConfigHealth`/`ConfigWriteResult`/`ConfigRepository` 契约 | 接口（实现属 :app） |
| `policy/SensorPolicyEngine.kt` | 传感器回调决策（租约、时间、包名、schema、类型） | 完成 |
| `rules/AdRules.kt` | `Bounds`/`UiNode`/`AdWindowSnapshot`/`SkipCandidate`/`AdRuleEngine` | 完成 |
| `rules/ConservativeAdRuleEngine.kt` | 内置保守规则 v1 的唯一实现 | 完成（t7） |
| `protocol/ConfigProtocol.kt` | Provider 协议常量与规范错误码 | 完成 |
| `status/*.kt` | 无障碍与 Hook 的真实状态 DTO | 完成 |

## 2. 配置层语义

- **默认值即“全关”**：`ProtectionConfig()` = schemaVersion 1 / revision 0 / 三个开关 false / `packages` 为空。
- **校验（`ConfigValidator`）**：schema 版本、revision ∈ [0, Long.MAX_VALUE-1]、packages ≤ 500、
  包名正则 `^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$` 且 ≤255 字符、拒绝空白/通配符/斜杠、
  包键不得为 `android`/`com.android.systemui`/`com.antiads.app`、
  `blockedSensorTypes` 只能是 {1,4,9,10,11} 的子集（空集合合法）、`ruleIds` 只能是内置 ID 或空集合。
  返回的 errors 是稳定英文码（如 `REJECTED_PACKAGE_KEY`），**不含包名等用户输入**，避免日志泄漏。
- **编解码（`ConfigCodec`）**：`encodeDefaults=true`、`ignoreUnknownKeys=true`；
  解码前先检查 JSON 对象中的 `schemaVersion` 键，缺失或非 1 一律抛 `IllegalArgumentException("UNSUPPORTED_SCHEMA_VERSION")`，
  **不会**用数据类默认值把外部 JSON 静默解释成 v1；坏 JSON、类型错误、未知枚举、非法包名、越界数据、
  payload > 256 KiB 同样拒绝。报告（`HookProcessReport`）额外校验 pid>0、进程名 ≤160、token 为 36 字符 UUID、
  计数非负且 `droppedCallbacks ≤ observedCallbacks`。
- **求交（`PolicyResolver`）**：`masterEnabled ∧ 全局模式 ∧ 每包模式`；包不存在视为全关。
  关闭 Hook 时返回 `hookEnabled=false` 且 `blockedSensorTypes=emptySet()`，并**保留真实 `config.revision`**，
  便于目标进程判断是否已收到最新版本。

## 3. 广告跳过规则 v1（`ConservativeAdRuleEngine`）

输入是**快照**（`AdWindowSnapshot`），输出是**候选**（最多 1 条）。引擎不执行动作、不读 Android 节点。

| 必要条件 | 不满足时 |
| --- | --- |
| `config.schemaVersion == 1` | 返回空列表 |
| 包名合法（正则/长度） | 返回空列表 |
| 包名 ∉ 保留集合（宿主、SystemUI、android） | 返回空列表 |
| 总开关 ∧ 全局无障碍 ∧ 每包无障碍 | 返回空列表 |
| 每包 `ruleIds` 含 `builtin.conservative.v1` | 返回空列表 |
| `isApplicationWindow` | 返回空列表 |
| 非锁屏（`keyguardLocked == false`） | 返回空列表 |
| 非敏感包（`sensitivePackage == false`） | 返回空列表 |
| `traversalComplete`（节点/深度/耗时未超限） | 返回空列表 |
| 屏幕尺寸 0 < W,H ≤ 100000 | 返回空列表 |
| `0 ≤ capturedAt - foregroundSince ≤ 10000ms` | 返回空列表（持续 content changed 不能延长该窗口） |
| 窗口中不存在 `editable`/`password` 节点 | 返回空列表（整窗放弃） |
| 存在**可见**的广告上下文节点：文案或 description 经 trim+小写后严格等于 “广告”/“ad”/“advertisement” | 返回空列表 |
| 候选节点 `visible ∧ enabled ∧ clickable` | 跳过该节点 |
| 候选文案（text 或 description）完整匹配三种正则之一，先 trim 且 ≤40 字符 | 跳过该节点 |
| 广告上下文来自与候选**不同的 nodeId** | 跳过该节点 |
| bounds 完全在屏幕内、正面积、面积 ≤ 屏幕 12%、中心 X ≥ 屏宽 65%、中心 Y ≤ 屏高 25% | 跳过该节点 |

命中后按 `top` 升序、`right` 降序、`nodeId` 升序取一条，返回
`SkipCandidate(nodeId, ruleId = "builtin.conservative.v1", reason = "EXPLICIT_AD_SKIP")`。

文案正则（中文两种语序 + 英文忽略大小写）：

- `^跳过(?:\s*[0-9]{1,2}\s*(?:秒|s)?)?$`
- `^[0-9]{1,2}\s*(?:秒|s)?\s*跳过$`
- `^skip(?:\s+ads?)?(?:\s+[0-9]{1,2}\s*s)?$`

“看到跳过文本就点击”在实现上被显式禁止：没有独立广告上下文节点时一律返回空列表。

### 3.1 几何实现细节（供 t11/t12 核对）

- **全部算术在 Long 域进行**：`left`/`top`/`right`/`bottom` 先 `toLong()`，连 `left + right`、`top + bottom`
  加法也在 Long 域完成，之后才做乘法与比较（`10*(left+right) >= 13*W`、`2*(top+bottom) <= H`、
  `area*100 <= screenArea*12`），因此不存在 Int 加法或乘法溢出导致的误判。
- **判定顺序**：先拒绝异常屏幕尺寸与越界/零面积矩形，再做面积与比例乘法；乘法两端的量级因此被
  `MAX_SCREEN_DIMENSION_PX` 与屏幕尺寸共同限定（见 3.2）。
- **证据**：`ConservativeAdRuleEngineTest.geometryBoundariesAreExact`（中心 X=702 恰好 65% 接受 / 700 拒绝；
  中心 Y 边界；面积 311040 恰好 12% 接受 / 312000 拒绝）、`invalidBoundsAreRejected`、
  `extremeBoundsDoNotOverflow`（Int 级极大/极小坐标与 `left+right` 会溢出 Int 的输入都不崩溃、不命中）、
  `screenDimensionLimitIsExactAndConservative`（大尺寸下比例正确、上界恰好可用、超限保守不匹配）。

### 3.2 附加实现常量 `MAX_SCREEN_DIMENSION_PX`（非原合同数值，需集成与审查确认）

- **值**：`100_000`（像素；宽或高任一超过即视为异常输入）。
- **依据**：`docs/contracts.md` 第 5 节只要求“尺寸正常”，未规定数值；实现需要一个有限上界，使几何乘法
  （`area*100`、`screenArea*12`）在 Long 内必然安全。真实设备屏幕宽度远小于 10 万像素（当前主流为千级像素），
  该上界不会影响任何正常设备与快照。
- **超限行为（保守不匹配）**：`isEligibleWindow` 直接返回 false，规则引擎返回**空列表**——不点击、不抛异常、
  不改变目标应用行为；仅表示本规则不产生候选，不代表“已保护”。
- **证据**：`ConservativeAdRuleEngineTest.screenDimensionLimitIsExactAndConservative`、
  `abnormalScreenDimensionsBlockCandidates`（0、负数、上界+1 均返回空列表）。
- **变更通道**：若 Plan/审查认为应改用其他数值或改为可配置项，属实现细节调整、不涉及任何冻结签名，
  请在 t11 集成前提出，由顺序集成统一落地。

## 4. 传感器策略（`SensorPolicyEngine`）

返回 `DROP_CALLBACK` 需要**同时**满足：有快照、schema=1、快照包名与当前注入包名一致、`hookEnabled=true`、
类型 ∈ `blockedSensorTypes`、`requestStartedAt ≤ now < expiresAt`、租约时长 ∈ [1, 5000] ms。
其余全部 `ALLOW`，原因码为 `NO_POLICY`/`INVALID_POLICY`/`PACKAGE_MISMATCH`/`DISABLED`/`EXPIRED`/`TYPE_NOT_SELECTED`。

- 边界：`now >= expires` 必须放行（`EXPIRED`）；租约 4999/5000 ms 合法，5001 ms 视为非法策略而放行。
- 时间来源必须是调用方的 `elapsedRealtime`；负数、未来时间戳、时钟回退（now < requestStartedAt）一律放行。
- 关闭传播：总开关/模式开关/包开关关闭后，hookEnabled=false 与空类型集合使所有后续回调放行；
  旧快照最晚在原请求起点 + 5000 ms 失效。core 只做判定，不做 Binder/磁盘/网络/线程等待。
- 未配置的应用、未被选中的传感器类型（如光线 5、距离 8、计步 18/19）以及空类型集合一律保持原始行为，
  即运动/导航/游戏类应用的正常使用不受影响（对应测试见 `SensorPolicyScopeTest`）。

## 5. 输入不合法时的保守行为（可测试）

| 输入 | 行为 |
| --- | --- |
| 损坏 JSON、缺 `schemaVersion`、未知版本、未知枚举 | 解码抛 `IllegalArgumentException`；调用边界按“全关”处理，不静默降级为 v1 |
| 非法包名、保留包键、未知传感器类型、revision 越界 | 校验返回稳定错误码，写入被拒绝 |
| 空节点列表 / 空规则集合 / 空类型集合 | 规则引擎返回空候选；传感器策略全部 ALLOW |
| 屏幕尺寸 ≤0 或 >100000、bounds 越界/零面积/溢出 | 规则引擎返回空列表，不抛异常 |
| 时间戳为负、未来或回退 | 规则引擎不点击；传感器策略放行 |
| 快照缺失 / schema 未知 / 包名不符 | 传感器策略放行（`NO_POLICY`/`INVALID_POLICY`/`PACKAGE_MISMATCH`） |

## 6. 接口变更请求

**无签名变更需求。** 仅新增了**附加常量**（不改变任何冻结签名，供实现与测试避免魔法字符串）：

- `ConservativeAdRuleEngine.RULE_ID`、`REASON_EXPLICIT_AD_SKIP`、`MAX_FOREGROUND_AGE_MS`、
  `MAX_LABEL_LENGTH`、`MAX_SCREEN_DIMENSION_PX`、`AD_CONTEXT_LABELS`、`SKIP_LABEL_PATTERNS`
- `ConfigConstants`（骨架已引入）中的 schema 版本、内置规则 ID、默认/允许传感器集合、保留包名、各上限
- `ConfigValidator`/`SensorPolicyEngine` 的错误码与原因码常量

若 Plan 希望这些常量改名或收窄可见性，请在集成前提出，由顺序集成统一落地（不改动签名本身）。

## 7. 测试与证据

命令：`bash ./gradlew --no-daemon :core:test`（合同 verify 命令）。

| 测试类 | 数量 | 关注点 |
| --- | --- | --- |
| `ConservativeAdRuleEngineTest` | 25 | 上下文/文案/几何/时间的精确边界；误触反例；开关关闭与目标排除 |
| `SensorPolicyScopeTest` | 7 | 作用范围、未选类型、运动应用不受影响、停用与租约过期恢复 |
| `SensorPolicyEngineTest` | 9 | 租约 4999/5000/5001、5999/6000 边界、负数与时钟回退 |
| `ConfigCodecTest` | 11 | 缺 schema、坏 JSON、未知枚举、越界、策略与报告字段校验 |
| `ConfigValidatorTest` | 9 | revision 边界、包名语法、保留包键、类型/规则白名单、包数量上限 |
| `PolicyResolverTest` | 5 | 三层开关交集、包不存在、关闭时清空类型但保留 revision |
| **合计** | **66** | 全部通过（`BUILD SUCCESSFUL`，日志 `.tooling/core-test-t7.log`、`.tooling/core-test-restored.log`） |

**变异检查（证明测试能发现缺陷，不只是“跑绿”）**：临时注入 3 处缺陷后重跑，恰好且仅有 3 个用例失败：
几何阈值放大（`geometryBoundariesAreExact`）、取消可编辑/密码节点整窗放弃（`editableOrPasswordNodeBlocksWholeWindow`）、
放宽“广告上下文必须独立 nodeId”（`adContextFromSameNodeOnlyProducesNothing`）；恢复实现后 66 个用例重新全绿。

## 8. 未验证范围（不得当作产品结论）

以上只证明 **core 纯逻辑**正确：不证明无障碍服务已连接、不证明 LSPosed 已注入、不证明真实广告页面的点击结果，
也不证明任何设备/系统版本组合。设备相关结论必须由 QA 在真机/模拟器上给出，且需区分“已配置”“已观察到注入”
“最近发生拦截”“实测通过”四种事实。
