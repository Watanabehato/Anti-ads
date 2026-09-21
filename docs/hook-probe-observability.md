# probe / Hook 可观测性设计（D 侧，t10 落地）

状态：**待验证方案**。本文全部内容尚未在任何真机/模拟器执行过，任何条目在取得设备证据前只能称“待验证”，不得引用为已验证结论。路径 C（Root 诊断服务）已按 captain 决策取消，不实现。

本文不改 contracts 第 6/8 节公共协议：probe 公共面仍是冻结的 Activity/UI 行为；本文只新增**进程内诊断面**（logcat 行、可选文件快照、instrumentation 承载），不新增跨进程协议、不新增配置写入口、不新增权限、不新增生产导出写端点。

决策摘要（captain 已定，本文据此修正）：

1. 跨应用夹具由 QA 新任务 t14 在 `qa/fixtures/noqueries` 独立 Gradle 根项目构建 `com.antiads.fixture.target`，不入主 settings、不新增 flavor；t11/t3 依赖该交付。
2. AndroidX runner（`androidx.test:runner:1.6.2` + `androidx.test.ext:junit:1.2.1`）由 t11 统一为 app/probe 配置并产出 `assembleDebugAndroidTest`；D 只写 `probe/src/androidTest` 源码，B 提供宿主同 UID 的 `ConfigToggleTest`。
3. H01 观测只走 A1/A2 两条路径，不实现 Root 诊断服务。

## 0. 实现状态（t10 已落地，设备执行待 QA）

| 设计条目 | 实现位置 | 状态 |
| --- | --- | --- |
| probe 逐类型字段与 5 个 scenario | `probe/src/main/kotlin/com/antiads/probe/MainActivity.kt`、`AdFixtureActivity.kt` | 已实现，真机未测 |
| 诊断行 tag=`AntiAdsProbe.Diag`（单行 JSON） | `probe/src/main/kotlin/com/antiads/probe/diag/DiagJson.kt`、`SensorDiagSession.kt` | 已实现，JVM 单测覆盖格式 |
| 同一注册判据 sessionId/registrationSeq/pid/host | `diag/DiagTracker.kt` | 已实现，单测覆盖 |
| 静默窗口（silenceWindow 行） | `diag/SilenceWindowDetector.kt` | 已实现，单测覆盖 |
| A1 instrumentation 承载 | `probe/src/androidTest/kotlin/com/antiads/probe/diag/RegistrationHoldTest.kt` | 已实现，未在设备执行 |
| A2 宿主切换开关 | `app/src/androidTest/.../ConfigToggleTest.kt`（B 提供）+ probe 前台 | 已在 API29 模拟器执行：`OK (1 test)`、`SAVED`、`revision 32→33`（t17，[docs/verification.md](verification.md) 4.5 节）；“同次注册保持/5 秒恢复”仍需 Root 或框架条件 |
| 策略自查（可选，默认关闭） | `probe/src/main/kotlin/com/antiads/probe/PolicySelfCheck.kt` | 已在 API29 模拟器执行：probe 自读 `OK`、`revision=32`、`hookEnabled=true`、`blockedTypes=[1]`（t17） |
| Hook 侧租约/节流/报告 | `hook/src/main/kotlin/com/antiads/hook/internal/policy/HookPolicyClientCore.kt` | 已实现，M1–M10 单测 |
| 目标点形状验证与 type 解析 | `hook/src/main/kotlin/com/antiads/hook/internal/SensorDispatchTarget.kt`、`SensorTypeResolver.kt` | 已实现，单测覆盖 |
| 路径 C（Root 诊断服务） | —— | 按 captain 决策取消 |

上表中**非 Root 侧已有设备结论**（API29 模拟器 t17：probe 生命周期与诊断快照、A2 开关、策略自查、独立夹具以真实 UID 读取策略，见 [docs/verification.md](verification.md) 与 `docs/qa-evidence/t17-bea37e8/`）；**H01 A1（`RegistrationHoldTest` 同次注册保持）、H03、以及全部 Hook 注入/作用域结论仍无设备证据**——本环境无 Root/LSPosed 设备，JVM 单测只能证明决策/格式逻辑，不能证明注入或传感器实际行为。相关命令与证据字段见 `docs/hook.md`、`docs/probe.md`。

## 1. probe 公共面（不改，对应 contracts 第 8 节）

MainActivity：真实 SensorManager + SensorEventListener。每个实验类型一行显示：

| 字段 | 语义 | 来源 |
| --- | --- | --- |
| `exists` | 设备是否存在该类型 | `getDefaultSensor(type)` |
| `registerResult` | `registerListener` 实际返回值 | 真实调用返回 |
| `callbacks` | 累计 Java 回调数 | 监听器计数 |
| `lastCallbackElapsedMs` | 最近一次回调的 `SystemClock.elapsedRealtime()` | 回调时戳 |
| `samplingState` | IDLE / REGISTERED / REGISTER_FAILED / UNREGISTERED | 注册动作结果 |

- 默认 `SENSOR_DELAY_NORMAL`，开始/停止为显式按钮；`onPause` 注销所有监听并停 UI timer；`onResume` 不偷跑（这正是 QA 指出的冲突来源）。
- 实验页显示最多 5 种配置支持类型，并提供至少一种**设备实际可用且未被选入拦截集合**的类型作对照；对照类型按设备实选取（不得固定用类型 3 —— 类型 3 已废弃且多数设备不上报）。无可用对照类型时显式写“无对照”，该次实验不可判定，不把 0 当成功。
- probe 不申请 INTERNET；广告样例页 5 个 scenario 不变：`ad_positive` / `no_ad_label` / `non_clickable` / `bottom_button` / `editable_window`（未知值回落 `no_ad_label`）。

## 2. 内部诊断面（不改公共协议）

同一份计数核心 `SensorDiagSession` 可被两种承载复用，承载方式不改变 SensorManager 调用语义：

```text
SensorDiagSession(types, delay, host)   // host = ACTIVITY | INSTRUMENTATION
  registerAll()  → 每种类型一次 registerListener，registrationSeq++，sessionId = UUID
  unregisterAll()→ 相应降级为 UNREGISTERED
  rows()         → 每个类型一行计数字段 + 全局字段
```

行字段（比公共 UI 多，仅诊断用）：

```json
{"tag":"AntiAdsProbe.Diag","seq":3,"sessionId":"<UUID>","registrationSeq":4,"host":"INSTRUMENTATION",
 "activityResumed":false,"pid":12345,"elapsedMs":40250,"type":1,"exists":true,"selected":true,
 "registerResult":true,"samplingState":"REGISTERED","callbacks":812,"callbacksSinceRegister":812,
 "lastCallbackElapsedMs":40213,"gapSinceLastMs":37,"shakes":3,"control":false}
```

输出通道：

1. **logcat（始终可用）**：tag `AntiAdsProbe.Diag`，一行一条 JSON。注册/注销/失败/状态变化立即输出；其余节流至 ≤1 行/秒/类型。命令：`adb logcat -s AntiAdsProbe.Diag:V`。
2. **文件快照（debuggable 变体）**：`files/probe-diag.json`，同一批行的数组；`adb exec-out run-as com.antiads.probe cat files/probe-diag.json`。
3. **可选策略自查行（默认关闭，诊断面板显式打开）**：probe 以自身 UID 调 `get_policy_v1`，≥1s 最多一次，只记录 `revision/hookEnabled/blockedSensorTypes`，与界面同屏。用于区分“Hook 租约未过期”与“Provider 已发新 revision”；明确标注“probe 自读，不是拦截证据”，不改变第 6 节协议。

**同一注册的客观判据**：同一进程实例（`pid` + 进程启动时刻）内 `sessionId` 与 `registrationSeq` 不变，且 `host`/`activityResumed` 有记录。**进程重启后 `registrationSeq` 不保证等于旧值 +1**（每进程实例独立计数，重启即新会话），因此跨重启一律视为新会话、分段记录；用 `sessionId` + `pid` 区分会话，不用序号推算。缺三元组证据记**未验证**。

## 3. H01 关闭恢复：承载路径（仅 A1/A2）

QA 原方案（分屏切到管理端）在多数 API29 手机上会让 probe 进入 PAUSED → 公共面 `onPause` 注销 → 无法证明“同一注册恢复”。两条可行路径都由 `SensorDiagSession` 承载并输出上面三元组。

### A1（推荐，无跨模块依赖）
probe 侧 instrumentation 会话持有一条注册（`host=INSTRUMENTATION`，不随 Activity pause 注销），probe 可分屏可见，人工在另一半屏操作管理端开关。

```bash
adb shell am instrument -w \
  -e class com.antiads.probe.diag.RegistrationHoldTest \
  -e durationMs 90000 \
  -e types 1,4,9,10,11 \
  -e control <CONTROL_TYPE_设备实选取值> \
  com.antiads.probe.test/androidx.test.runner.AndroidJUnitRunner
```

- `types` 为本次拦截集合（默认候选 1,4,9,10,11）；`control` 必须是**该设备真实可用且不在 `types` 内**的类型（常见可用对照是 2 磁场；若设备只有按变化上报的类型（5/6/8/12/13），先实测其回调是否稳定，否则以实测为准重选并记录类型号）。
- 有效性闸门：对照类型必须在基线与“关闭”窗口内持续有回调。若对照在承载方式下也停（API29+ 后台连续传感器限制、息屏、省电策略），该次实验标**不可判定**，改走 A2。分屏仅在设备 `activityResumed` 全程为 true（multi-resume 机型/大屏）时才有意义；A1 的 instrumentation 会话不依赖该条件，也不实现 Root 诊断服务。

### A2（需要 B 提供测试工具）
probe Activity 保持前台 RESUMED（无后台传感器限制、无重注册），配置切换由 app 侧 instrumentation 走真实 `AppConfigRepository.write` 完成：

```bash
adb shell am instrument -w -e class com.antiads.app.ConfigToggleTest -e action master_off \
  com.antiads.app.test/androidx.test.runner.AndroidJUnitRunner
```

- 参数形式为 `-e action master_off`（键与值分开，不写 `-e action=master_off`）；取值为 `master_off` / `master_on`。
- 该写入是真实原子写 + revision 递增，等价 UI 保存路径，不是 mock；**不新增任何生产导出写端点**，`ConfigToggleTest` 只在 androidTest 源集存在。

## 4. H01 实验时序（修正：注入/重启在前，再走同一注册三段）

**顺序必须是：先完成注入与目标重启 → 再在同一注册内做 基线 → 开启 → 关闭。** 重启会毁掉注册关系，不能把“开启”放在重启之前再去要同一注册。

| 步骤 | 操作 | 记录 |
| --- | --- | --- |
| 0 先决 | 框架激活模块 + probe 作用域；**重启 probe**（必要时按框架说明重启设备）；确认注入完成 | 记录 `installState`、进程实例（`pid`）、`sessionId` 起点 |
| 1 基线 | 三层开关保持关闭，warmup ≥10s 后开始计数（A1：instrumentation 会话；A2：probe 前台按钮） | 本段为新进程新会话：`sessionId`=`S`、`registrationSeq`=`R`；所选与对照类型 `callbacks` 均持续增长 |
| 2 开启 | 打开产品三层开关 + 类型选择（不重启、不重注册） | 所选类型 `callbacks` 停止增长（`gapSinceLastMs` 持续增大），对照类型继续增长；`sessionId/registrationSeq` 不变 |
| 3 关闭 | 关总开关（A1 人工点；A2 instrumentation），记关闭时刻 `t0` 与 Provider revision | 对照类型在“关闭”窗口内仍持续有回调 |
| 4 恢复 | 保持同一注册，观察未来事件 | 首个新回调时刻 `t1`；`sessionId/registrationSeq` 仍不变；只对所选类型记录 `t1-t0` |

必须同时给出：`sessionId/registrationSeq/pid` 不变、`host`、`activityResumed`、对照类型全程有回调、`t1-t0` 实测值。任一项缺失 → 标“未验证/不可判定”并写清原因。

## 5. 时间边界必须分列（逻辑截止 ≠ 首个真实事件）

- **逻辑截止（可严格判定）**：旧策略自“最后一次成功请求起点”起最多 5000ms 有效，`now ≥ expiresAtElapsedMs` 必须 ALLOW；此条由 JVM 单测严格测（M1/M5，含 `expires-1` 与 `expires` 边界），不依赖设备。
- **实测首个事件时刻 `t1`**：真实传感器事件到达还受采样周期/延迟常数、去重与回调派发影响，可能晚于逻辑截止；记录时把两者分开，不得用 `t1-t0` 反推租约是否失效，也不得因 `t1-t0` 偏大就判失败。
- 参考值只作解释：活跃刷新路径期望典型 ≤3000ms（≥2000ms 节流 + ≤1000ms 逻辑读取期限），硬上界为逻辑截止 5000ms；具体采样间隔按设备实测记录。
- 次级证据（非真值）：管理端 `RuntimeReportStore` 的 `observedCallbacks/droppedCallbacks/policyRevision`（进程自报），仅用于与 probe 观测对齐。

## 6. Hook 单测矩阵（JVM，fake 时钟 + fake 传输 + 计数 ThreadFactory）

| # | 场景 | 期望 |
| --- | --- | --- |
| M1 | start=1000, reply=1500 | 发布策略；expires=6000；now=5999 丢弃、now=6000 放行 |
| M2 | reply 迟到（>1000ms） | 不发布；旧有效缓存只活到原 expires；无旧缓存即放行 |
| M3 | 传输卡住不返回 | in-flight 恒 1、队列 ≤1、创建的线程数 =1；回调路径不 await/不阻塞 |
| M4 | 刷新节流 | 回调 t=0,1,999,1999,2000,2001 → 仅 t=0、t=2000 发起读取（CAS） |
| M5 | 到期即放行 | 自最后一次成功请求起点起 5000ms 为逻辑截止；now≥expires 必 ALLOW（含边界） |
| M6 | 错误/拒绝/坏 JSON | 立即清缓存，下一回调放行；不沿用旧 enable 值 |
| M7 | 无回调不轮询 | 30s 无 sensor 事件 → 除首次 Context 读取外访问计数不变 |
| M8 | 时钟回退/墙钟抖动 | 只依赖注入的 elapsedRealtime；wall clock 大幅改变不影响租约 |
| M9 | 定位失败/形状不符 | installState=UNSUPPORTED/ERROR，永不 DROP，原调用继续 |
| M10 | 计数与报告 | 计数单调；报告合并 ≤1 条/5s，首次可发即发，不占用回调线程 |
| M11 | 不做多余动作 | 不改 values、不伪造 Sensor、不改 registerListener 返回值、不注销原 listener |

M1/M5/M11 同时覆盖 core 的 `SensorPolicyEngine` 边界；D 侧额外跑客户端集成路径（协议解码 → 缓存发布 → 决策），确保“core 正确”不等于“客户端正确”。

## 7. 构建与测试接线（按 captain 决策）

- AndroidX runner `1.6.2` + `androidx.test.ext:junit:1.2.1` 由 **t11 统一**为 app/probe 配置，并执行 `assembleDebugAndroidTest`；D 只在 `probe/src/androidTest` 写源码与测试类，不改共享构建文件（如确需 `testInstrumentationRunner` 之外的改动，按架构第 4 节提申请）。
- `com.antiads.app.ConfigToggleTest`（宿主同 UID，`-e action master_off|master_on`）由 **B** 提供；不被生产代码引用，不新增导出写端点。
- D 侧测试类：`com.antiads.probe.diag.RegistrationHoldTest`（A1 承载，输出 diag 行与 t1-t0）。

## 8. 跨应用（API30+ 包可见性）验收：夹具与记录要求

### 8.1 夹具（按 t14 决策）

| 夹具 | 来源 | 说明 |
| --- | --- | --- |
| P = probe | 本仓库 `:probe` | `probe/AndroidManifest.xml` 声明 `<queries><provider android:authorities="com.antiads.app.config"/></queries>`（contracts 第 1 节要求）。它同时是 H01 目标，**不能**充当“无 queries 的普通第三方目标”。 |
| T = 无该 queries 的普通第三方目标 | **t14（QA）** | `qa/fixtures/noqueries` 独立 Gradle 根项目构建 `com.antiads.fixture.target`：最小应用，不声明该 authority，不入主 settings、不新增 flavor；t11/t3 依赖该交付。 |

如果没有安装 T 的设备/APK：该项标 **未测 + 阻塞原因**，禁止用 P 的结果外推。

禁止项：为让测试通过给夹具/第三方加 `queries`、改用 `QUERY_ALL_PACKAGES`、或修改目标 APK 绕过可见性（contracts 第 1 节）。

### 8.2 每个夹具分列记录

applicationId、是否声明 provider queries、API 级别、`installState`、`transportState`、`observedCallbacks`、`droppedCallbacks`、夹具自身传感器是否仍工作、管理端 UI 文案。

失败归类（D 侧客户端映射，均为 contracts 第 7 节既有枚举，无新增协议）：

| 实测情形 | transportState | 行为 |
| --- | --- | --- |
| 未知 authority / 看不见 Provider / Provider 未运行 | UNAVAILABLE | 无缓存立即 ALLOW；曾有有效缓存只活到原 `expires`（自请求起点 ≤5000ms） |
| 身份不可枚举、PID/包验证失败 | UNAUTHORIZED | 立即清缓存，放行，不猜测身份 |
| 坏 JSON / 未知 schema / 字段越界 | INVALID | 立即清缓存，放行 |
| 共享 UID 多包 / isolated | UNAUTHORIZED（`lastErrorCode`=SHARED_UID_UNSUPPORTED / ISOLATED_OR_UNKNOWN_UID） | 放行 |
| 逻辑期限 1000ms 内未回复 | 迟到丢弃，维持原租约到期后放行 | 不新建替代线程 |

核心判据：**没有策略时绝不 DROP** → T 的 `droppedCallbacks` 必须为 0（`observedCallbacks` 可 >0，表示 Hook 已装而上层放行）；目标传感器功能不中断；管理端显示“未观察到目标读取配置”，不得显示“已保护”。P 的 OK 结果不得外推给 T。

### 8.3 待真机核验的不确定点

Android 11+ 包可见性是否阻断 `ContentResolver.call` 到未声明 authority 的 Provider，本环境无法取得官方全文（developer.android.com 返回 `resolves to a non-public IP address`，architecture 第 1 节已记录同类限制）。因此两种结果都合法，必须取真机实测值：

- T 读取成功（`transportState=OK`）：记录“本设备/本 API 下未声明 queries 亦可读取”，并注明 API/机型；
- T 读取失败：记录实际错误与 `transportState`、首次 ALLOW 时延与租约边界值。

无论哪种结果，都不改变“分列 + 不例外推 + 无设备标未测”的要求。
