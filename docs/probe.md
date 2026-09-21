# :probe 独立诊断 APK 实现说明

所有者：D（hook-engineer，t10）。配套：`docs/hook.md`、`docs/hook-probe-observability.md`。
**验证状态：源码与 JVM 单测在本机通过；非 Root 侧设备实验已在项目 API29 模拟器（`emulator-5580`）执行**——采样/暂停/返回/显式重启、真实注册轮次、`files/probe-diag.json` 快照与 ≤1 次/秒节流，证据见 [docs/verification.md](verification.md) 第 3.3/3.4 节与 `docs/qa-evidence/t17-bea37e8/`。**`RegistrationHoldTest`（A1 同次注册保持）、Root/LSPosed 条件与真机仍未执行**。

## 1. 产物、权限与可见性

- `com.antiads.probe`，独立 APK，仅依赖 `:core`；**不申请 INTERNET**，不作为框架模块，不依赖 `:app`。
- Manifest 声明 `<queries><provider android:authorities="com.antiads.app.config"/></queries>`：使 probe 在 API30+ 包可见性下仍能读取自身最小策略。**该声明只对 probe 成立，不能外推到普通第三方目标**（见 `docs/hook-probe-observability.md` 第 8 节与 t14 夹具）。
- 组件：`MainActivity`（LAUNCHER，导出）与 `AdFixtureActivity`（导出，供 adb 直接启动；只产生本地测试内容）。
- 首页与样例页都**不联网、不写配置、不做提权**；样例页不注册传感器，首页不假装广告。

## 2. 首页字段与语义（contracts 第 8 节）

每类型一行：`存在`（`getDefaultSensor(type) != null`）、`注册返回`（`registerListener` 真实返回值，未尝试则显示"未尝试"）、`回调`（跨注册累计）、`本次注册`（本次注册后计数）、`最近`（距最近回调的毫秒数）、`状态`（`IDLE/REGISTERED/REGISTER_FAILED/UNREGISTERED`）、`摇动`（该类型触发的摇动计数）。

- 默认 `SENSOR_DELAY_NORMAL`；"开始采样/停止采样"为显式按钮；`onPause` 注销全部监听并停止 UI timer；`onResume` **不**自动重启采样（页面提示"离开页面已自动注销监听"）。
- **生命周期状态一致性（t16 修复 QA-03）**：按钮、提示与周期刷新由单一同步点 `applyUiState()` 统一设置，它使用纯逻辑规则 `ProbeUiStateRules.of(samplingRunning, activityResumed)`：
  - `startEnabled = !samplingRunning`：无论显式停止还是 `onPause` 自动注销，**"开始采样"都必须立即可用**（修复前返回页面时仍禁用、而"停止"仍可用）；
  - `stopEnabled = samplingRunning`；
  - `periodicRefresh = activityResumed`：返回页面必须重建每秒刷新（状态行、策略自查与诊断快照节流都依赖它）；
  - `startSampling` **恒为 false**：生命周期规则永不请求自动开始，恢复采样只能由用户点击"开始采样"，不允许用自动重注册绕过"显式开始"。
  - 回归用例：`ProbeUiStateRulesTest`（4 例，含"任意组合都不自动开始"）。
- 实验页最多 5 种配置支持类型，并自动选择**设备真实可用且未被选入拦截集合**的类型作对照（优先磁场；设备没有可用对照时显示"无对照"，该次实验不可判定）。
- 摇动计数只用于观察运动事件是否随开关变化，**不是拦截成功证据**；无回调也不能自动判为"被拦截"。

## 3. 内部诊断面（不改公共协议）

同一份计数核心可被 Activity 与 instrumentation 复用，输出 tag=`AntiAdsProbe.Diag` 的单行 JSON：

```bash
adb logcat -s AntiAdsProbe.Diag:V
# debuggable 变体还写文件快照（最多 1 次/秒；开始/停止/onPause/onResume 立即写一次）：
adb exec-out run-as com.antiads.probe cat files/probe-diag.json
```

**文件快照（t16 修复 QA-04，之前只定义未接线）**：debuggable 构建下，Activity 在每秒刷新（`force=false`，≤1 次/秒节流）与关键状态变化（开始/停止/onPause/onResume，`force=true`）时写 `files/probe-diag.json`；非 debuggable 构建**不进入该路径**（`dumpFile` 首行返回 null）。内容为单行 JSON 对象，字段固定：

```text
{schemaVersion, kind:"probeDiagSnapshot", tag, sessionId, registrationSeq, host, pid,
 activityResumed, samplingRunning, elapsedMs, writtenAtElapsedMs, controlType, rows[]}
```

键集合与实现一致（13 项）；JSON 键序不影响解析，实现发射顺序为 `tag,kind,schemaVersion,sessionId,registrationSeq,host,pid,activityResumed,samplingRunning,elapsedMs,writtenAtElapsedMs,controlType,rows`。

`rows[]` 复用同一个 `row()` 序列化，字段与 logcat 行**完全一致**（19 个，顺序固定）：`tag/seq/sessionId/registrationSeq/host/activityResumed/pid/elapsedMs/type/exists/selected/registerResult/samplingState/callbacks/callbacksSinceRegister/lastCallbackElapsedMs/gapSinceLastMs/shakes/control`。只含计数、状态与时间戳，**不含界面文本、输入内容、包列表或传感器读数**。写入成功时会额外输出一行 `kind=snapshotFile` 诊断（含 `force` 与 `path`），便于确认文件确实生成；节流、非 debuggable 或写入失败时静默跳过，不影响采样与界面。回归用例：`DiagFileThrottleTest`（5 例：首次可写、1 秒内拒绝、恰好 1000ms 可写、force 立即并重置窗口、自定义间隔）与 `DiagJsonTest.snapshotIsParseableAndCarriesOnlyDiagFields`（用 kotlinx-serialization 解析并断言字段集合恰好等于上表）。

行字段（19 个，顺序固定）：`tag seq sessionId registrationSeq host(ACTIVITY|INSTRUMENTATION) activityResumed pid elapsedMs type exists selected registerResult samplingState callbacks callbacksSinceRegister lastCallbackElapsedMs gapSinceLastMs shakes control`。另有 `kind=summary`（`verdict`/`controlContinuous`）与 `kind=silenceWindow`（静默窗口 `lastCallbackBeforeGapMs`→`resumedAtMs`→`silenceMs`）。

**"同一注册"判据**：同一进程实例内 `sessionId` 与 `registrationSeq` 不变 + `host/pid` 记录。进程重启不保证序号 +1，跨重启一律视为新会话。

可选"策略自查"（默认关闭）：probe 以自身 UID 读 `get_policy_v1`（≤1 次/秒），只显示 `revision/hookEnabled/blockedSensorTypes/error`。**这是 probe 自读结果，不是拦截证据。**

## 4. 广告样例 5 场景（AdFixtureActivity）

| scenario | 内容 | 期望（core 规则） |
| --- | --- | --- |
| `ad_positive` | 独立可见"广告"标签 + 右上角可点击"跳过 5 秒"；点击后状态变"已跳过" | 满足坐标过滤 → 允许一次点击 |
| `no_ad_label`（默认/未知值） | 有"跳过 5 秒"，无广告上下文 | 不处理（缺广告上下文） |
| `non_clickable` | 有"广告"标签，"跳过 5 秒"不可点击 | 不处理（节点不可点击） |
| `bottom_button` | 有"广告"标签，按钮位于窗口下方 | 不处理（中心 Y > 25% 屏高） |
| `editable_window` | 有"广告"标签与可点击按钮，外加一个 EditText | 整窗不处理（含可编辑节点） |

坐标按可用窗口（扣除系统栏）布置；打开时输出 `AntiAdsProbe.Fixture` 日志行，含 `skip_center_x/_y`、`center_x_ratio`、`center_y_ratio`、`area_ratio`、`ad_context_visible`、`clickable`，便于 QA 核对 65%/25%/12% 约束。

```bash
adb shell am start -n com.antiads.probe/.AdFixtureActivity --es scenario ad_positive
adb shell am start -n com.antiads.probe/.AdFixtureActivity --es scenario no_ad_label
adb shell am start -n com.antiads.probe/.AdFixtureActivity --es scenario non_clickable
adb shell am start -n com.antiads.probe/.AdFixtureActivity --es scenario bottom_button
adb shell am start -n com.antiads.probe/.AdFixtureActivity --es scenario editable_window
```

## 5. H01 A1 路径：同一注册保持会话

`probe/src/androidTest/kotlin/com/antiads/probe/diag/RegistrationHoldTest.kt` 用 instrumentation 在 probe 进程内注册一次并保持，不随 Activity pause 注销；test APK 由顺序集成 t11 组装（AndroidX runner 1.6.2 / ext:junit 1.2.1）。

```bash
adb shell am instrument -w \
  -e class com.antiads.probe.diag.RegistrationHoldTest \
  -e durationMs 90000 -e baselineMs 10000 \
  -e types 1,4,9,10,11 -e control 2 \
  com.antiads.probe.test/androidx.test.runner.AndroidJUnitRunner
```

- `types`：本次拦截集合（最多 5 种）；`control`：QA 指定的对照类型，必须**设备真实可用且未选中**；会话会自行检测可用对照并输出 `control_mismatch` 提示。
- 输出 `hold_start/hold_registered/hold_baseline_complete/hold_silence/hold_end` 与逐秒诊断行，全部带设备级 `elapsed_realtime_ms`，可与宿主 instrumentation（`-e action master_off`）的 `elapsed_realtime_ms` 对齐算 t1-t0。
- verdict：`OBSERVED_CONTROL_CONTINUOUS`（可判定）或 `INCONCLUSIVE_*`（无注册/无对照/对照静默）。对照静默（后台传感器限制、息屏、省电）时必须判不可判定，不能当成"拦截成功"。
- probe 建议分屏可见以规避 API29+ 后台连续传感器限制；instrumentation 承载不代表真实用户前台场景，前台路径仍由 Activity 界面覆盖。

## 6. 验证命令

```bash
bash ./gradlew --no-daemon :probe:testDebugUnitTest :probe:lintDebug :probe:assembleDebug
```

JVM 单测：`DiagJsonTest`（单行 JSON/空值/转义/汇总）、`DiagTrackerTest`（计数、注册序号、从未注册类型、对照行）、`SilenceWindowDetectorTest`（静默窗口阈值）、`ShakeCounterTest`（阈值/方向翻转/重置）、`ProbeTypesTest`（对照选择与 5 类型上限）。

## 7. 设备验证范围、未测项与限制

- **已在 API29 模拟器完成**（t17）：传感器存在性与注册返回值、回调计数、HOME 暂停后返回**不自动重启**、显式重新开始产生新注册、样例页坐标与自动点击联动、诊断文件快照与节流。证据见 [docs/verification.md](verification.md) 第 3.3/3.4/4.1 节与 `docs/qa-evidence/t17-bea37e8/`。
- **仍未测**：真机传感器（模拟器为模拟来源）、Root/LSPosed 注入与作用域、`RegistrationHoldTest` 的 instrumentation 会话、无传感器/注册被拒/息屏/后台限制等“不可判定”条件。不得用模拟器结果替代真机结论。
- **未复现的时序观察（建议加测）**：开机后第一个 `ad_positive` 夹具窗口 7.4s 才 `Displayed`，6 秒观察窗内未发生点击；`force-stop` 后重跑即 3.07s 内点击，其后 4 轮（含恢复对照 1.06s）全部点击。未复现、机制未定位——既不记为缺陷也不记为通过，需在真机/更高帧率条件下继续观察（`r2-positive-rounds.txt`）。
- 样例页只验证"标题/按钮/坐标"这一层，不模拟真实广告 SDK 的窗口层级、WebView 或自定义绘制；不处理的情况不代表真实广告一定能处理。
- 无对照类型、无传感器、注册被拒、息屏、后台限制等条件下必须写"不可判定/无对照"，不得记为成功。
