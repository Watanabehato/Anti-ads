# QA-01～04 修复对照（t16）

基准报告：[独立 QA 验证报告](verification.md)（run `t3-b19f67f`，门禁结论**失败／需修订**，源码 `b19f67f`）。
本修复源码提交：**`be2278c`**（本文件为其后续文档提交）。修复范围：`app/`、`probe/`、`docs/build.md`、`docs/probe.md`、`docs/ui.md`、本文件。

**未改动**：`docs/verification.md`、`docs/qa-evidence/**`（原报告与证据保持原样，不覆盖、不改写）；`core/`、`accessibility/`、`hook/`、`gradle/`、`settings.gradle.kts`、`build.gradle.kts`、`qa/fixtures/noqueries/`。验收期望未被修改——本修复只改实现与文档。

> **本修复版尚未做设备复测。** 下表只声明“源码修复 + JVM/静态回归证据 + 新 APK 已构建”，
> 授权真值、probe 返回页与快照文件的设备行为必须由独立 QA 在保留的 API29 AVD 上重新执行（见第 4 节）。

## 1. 修复对照

| 编号 | QA 现象与根因（原文见 verification.md） | 修复内容 | 回归用例 |
| --- | --- | --- | --- |
| **QA-01**（高） | 系统已授权、服务已连接，但首页“系统设置授权”恒为“否”。根因：`StatusFacts` 把 **:accessibility 库 namespace** `com.antiads.accessibility` 当作服务归属包，而最终 APK 的真实组件是 `com.antiads.app/com.antiads.accessibility.AdSkipService` | `StatusFacts.matchesAdSkipService(hostPackageName, servicePackageName, serviceClassName)`：归属包改为**宿主自身包名**（`MainActivity` 传 `Context.packageName` = applicationId），**类名仍要求完整精确匹配**；删除会误导的 `AD_SKIP_SERVICE_PACKAGE` 常量 | `StatusFactsTest`：新增 `libraryNamespaceIsNotTheServiceOwningPackage`（库 namespace 不算归属包）、`serviceIdentityRejectsWrongOrMissingIdentifiers`（缺失/他包/同后缀类/空宿主全部拒绝），并把原用例改为按宿主包语义断言；新增设备级 `ServiceOwnershipTest`（真实 `PackageManager` 断言组件归属宿主包，且库 namespace 解析不到该组件） |
| **QA-03**（中） | 按 HOME 返回后提示可以重新开始，但“开始采样”仍禁用、“停止”仍可用，周期刷新未重建。根因：`onPause` 停止会话并移除 ticker 后没有同步按钮状态，`onResume` 只刷新一次 | 新增纯逻辑 `ProbeUiStateRules.of(samplingRunning, activityResumed)`：`startEnabled = !samplingRunning`、`stopEnabled = samplingRunning`、`periodicRefresh = activityResumed`、`startSampling` **恒为 false**；`MainActivity` 用唯一同步点 `applyUiState()` 统一设置按钮/提示/ticker，并在 `onPause`/`onResume`/开始/停止后调用。**不自动重注册**：恢复采样只能由用户点击“开始采样” | `ProbeUiStateRulesTest`（4 例）：暂停后开始可用/停止禁用、返回后开始可用且周期刷新重建、运行中开始禁用、**任意组合都不请求自动开始** |
| **QA-04**（中） | 文档承诺 debuggable 变体写 `files/probe-diag.json`，实际文件不存在（`dumpFile()` 有定义无调用） | `DiagJson.snapshot()` 生成固定字段的单行 JSON；`DiagFileThrottle` 实现 ≤1 次/秒 + `force` 立即写；`SensorDiagSession.dumpFile(nowElapsedMs, force)` **只在 debuggable 生效**（非 debuggable 首行返回 null，不进入该路径）；`MainActivity` 在每秒刷新（`force=false`）与开始/停止/onPause/onResume（`force=true`）时写入，成功时输出一行 `kind=snapshotFile` 诊断 | `DiagFileThrottleTest`（5 例：首次可写、1 秒内拒绝、恰好 1000ms 可写、force 立即并重置窗口、自定义间隔）；`DiagJsonTest.snapshotIsParseableAndCarriesOnlyDiagFields`（kotlinx-serialization 解析 + 断言顶层与行字段集合**恰好**等于约定集合，确保不含界面文本/输入内容/传感器读数）、`snapshotFileLineRecordsPathAndForce` |
| **QA-02**（中） | `docs/build.md` 仍称未安装 emulator/镜像、无 AVD、环境与仓库工具链分离 | 第 5 节按时间顺序重写：5.1 项目 SDK 内**已安装** `emulator 37.1.11.0` 与 `system-images;android-29;default;x86_64`(rev8)、**已创建**项目专用 AVD `AntiAds_QA_API29`、**曾启动成功**（`boot_completed=1`，serial `emulator-5580`）、t3 复用记录、**当前实例已 emu kill 清理**（`adb devices` 为空）且 SDK/镜像/AVD/测试 APK 保留待复测、重启方法；5.2 实际 QA 场景与门禁结论（仅针对 `b19f67f`）；5.3 仍未验证（Root/LSPosed、API30+ 可见性、API35+ Insets、真机、GitHub CI）；5.4 历史存档（t2/t11 的“无设备”表述只对当时成立） | 人工核对：`docs/build.md` 不再出现“未安装 emulator/镜像”“无 AVD”“工具链分离”的现状性表述；历史段明确标注仅存档 |

## 2. 验证证据（本机，源码 `be2278c`）

命令（与质量合同一致，原样执行）：

```bash
bash ./gradlew --no-daemon :core:test :app:testDebugUnitTest :accessibility:testDebugUnitTest :hook:testDebugUnitTest :probe:testDebugUnitTest \
  :app:lintDebug :accessibility:lintDebug :hook:lintDebug :probe:lintDebug \
  :app:assembleDebug :probe:assembleDebug :app:assembleDebugAndroidTest :probe:assembleDebugAndroidTest
# BUILD SUCCESSFUL in 52s；246 actionable tasks: 21 executed, 225 up-to-date；EXIT=0
```

| 项目 | 结果 |
| --- | --- |
| 五模块 JVM 用例 | **298 例，0 失败 0 错误**：`:core` 67、`:app` 46（+2）、`:accessibility` 103、`:hook` 46、`:probe` 36（+11） |
| lint | `:app` 0 error/1 warning（`ExportedContentProvider`＝合同要求导出且只读）、`:hook` 0 error/1 warning（`PrivateApi`＝反射定位隐藏类）、`:accessibility` 与 `:probe` **No issues found** |
| 组装 | 两个应用 APK 与两个仪器测试 APK 全部成功（测试 APK 含 t8 `ConfigToggleTest`、t16 `ServiceOwnershipTest`、t10 `RegistrationHoldTest`） |

**变异检查（证明回归用例真的抓得住原缺陷）**：临时注入与 QA 报告对应缺陷（归属包改回库 namespace、停止后不启用开始按钮且不重建刷新、完全取消节流）后重跑，恰好 **9 个**新用例失败
（QA-01：`libraryNamespaceIsNotTheServiceOwningPackage`、`serviceIdentityUsesHostPackageAndExactServiceClass`；QA-03：`afterPauseStopsSamplingStartIsEnabledAndStopIsDisabled`、`returningToPageReEnablesExplicitStartAndPeriodicRefresh`、`runningSamplingKeepsStartDisabled`；QA-04：`writesWithinOneSecondAreThrottled`、`forceWriteIsImmediateAndResetsWindow`、`resetClearsThrottleWindow`、`customIntervalIsRespected`），恢复后重新全绿。

## 3. 修复版产物（哈希表：测量时点、可复现性与安装依据）

> **先读结论**：下面 3.1 的表是 t16 当时的**本机测量记录**，**未被独立复现**，也**不是设备实际安装的那一组**，
> 因此**不得作为安装依据或交付证据**；修复版的安装与复测请使用 3.2 中经设备逐字节核对的一组。

### 3.1 t16 当时的本机测量记录（历史记录，**该组未被独立复现、不得作为安装依据**）

| 产物 | 字节数 | SHA-256 |
| --- | --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 3,447,031 | `afa9a786a0b3f119462e1a79d443755d6b32bb81ed41707e8a6938e24eaef9ed` |
| `probe/build/outputs/apk/debug/probe-debug.apk` | 3,200,278 | `abfa101454ae47849de191e9120d2b7293915542f5a7589e067af0d56bf9344a` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 2,186,638 | `3a0b2c940ef39cc28173fc03f7f38feab6476d6e66b9321de9c9ce2d46c44d4d` |
| `probe/build/outputs/apk/androidTest/debug/probe-debug-androidTest.apk` | 2,172,101 | `a351fc57caa93bac8c399e848034d5fb28121c23199653457c8c51e9b5730680` |

字节数与 SHA-256 均在 `be2278c` 提交后由本机重新测量（`stat -c %s` 与 `sha256sum` 同时读取同一次构建产物）。
哈希对应**t16 当时的那一次构建**（versionName 0.1.0、minSdk 29 / targetSdk 35）。

**该组的局限（独立验证发现，QA-R3-01(F1)）**：用同一源码重新构建**无法复现**上表的字节数与哈希，磁盘上也不存在匹配这些
字节数的 APK 文件；也就是说该表既不可复现、也不对应 QA 实际安装过的那组产物。保留它只为记录当时的测量结果，
**不得**据此安装、比对或宣称设备验证结论。

QA 首轮报告中的 `c79b04a4…`（app）与 `35af8de9…`（probe）属于 `b19f67f`，同样**不得移用**到修复版结论。

### 3.2 可复现且经设备逐字节核对的一组（**安装依据**）

| 产物 | 字节数 | SHA-256 | 来源与核对方式 |
| --- | --- | --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 3,392,024 | `a08a04b94462a09ec740f26988732362a40a20b9b69d50bb8797ce368857f8f5` | `docs/qa-evidence/t17-bea37e8/installed-apks.json`：`sha256` 与设备内 `installedSha256` 完全相同；同哈希见 `docs/qa-evidence/t3-be2278c/apk-sha256.txt` |
| `probe/build/outputs/apk/debug/probe-debug.apk` | 3,169,147 | `0a75edd07c17ade8cd86baef87f27adb74e6211706074ae202a26bad95e73896` | 同上（t17 installed+本地逐字节一致；t3 亦记录同哈希与字节数） |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 2,174,433 | `9b72ae61121fa0fe400d13e7b57f04da32217c2866ec86a061aab4a6e9e8c153` | `docs/qa-evidence/t3-be2278c/apk-sha256.txt`（t17 `apk-sha256.txt` 记录同一哈希） |
| `probe/build/outputs/apk/androidTest/debug/probe-debug-androidTest.apk` | 2,172,101 | `a351fc57caa93bac8c399e848034d5fb28121c23199653457c8c51e9b5730680` | 同上 |
| `qa/fixtures/noqueries/build/outputs/apk/debug/noqueries-debug.apk` | 见来源文件 | `533cbe72ffa12dd1c276aad1f43aafd9f71d086005859be7b16a96d5479e611a` | `docs/qa-evidence/t17-bea37e8/apk-sha256.txt` 与 `docs/qa-evidence/t3-be2278c/fixture-hash-before.txt` |

- 来源目录：`docs/qa-evidence/t17-bea37e8/`（第二轮独立复测，含设备内 installed 哈希核对）与 `docs/qa-evidence/t3-be2278c/`（第三轮独立验证的强制重跑记录）。
- 这一组在独立 QA 的“重建 → 安装”之间**逐字节一致**（`sha256 == installedSha256`），因此可作为修复版的安装依据。
- 跨环境或再次重新构建仍**可能**改变字节；任何新的安装都应重新计算哈希并在对应 run 证据里记录，不要长期引用本表。

## 4. 交给独立 QA 的复测项（本任务未执行）

1. **QA-01 设备复测**：安装修复版 APK → 通过产品入口打开系统无障碍设置 → 启用服务 → 返回首页，确认“系统设置授权：**是**”且与“服务实际已连接”分列；再撤销授权确认回落为“否”。可另行运行 `ServiceOwnershipTest`（`adb shell am instrument -w -e class com.antiads.app.ServiceOwnershipTest com.antiads.app.test/androidx.test.runner.AndroidJUnitRunner`）。
2. **QA-03 设备复测**：probe 开始采样 → 按 HOME → 以原任务返回，确认“开始采样”可用、“停止”禁用、状态行显示停止，且点击开始后能显式重新注册（不得自动重注册）。
3. **QA-04 设备复测**：同样流程后 `adb exec-out run-as com.antiads.probe cat files/probe-diag.json` 应返回可解析 JSON（字段集合见 `docs/probe.md` 第 3 节）；同时确认 logcat 有 `kind=snapshotFile` 行，且 1 秒内不会重复写（节流生效）。
4. **环境**：QA 保留的项目 AVD `AntiAds_QA_API29` 与官方镜像可复用；按 [模拟器环境记录](emulator-environment.md) 以受管任务重启并确认 `sys.boot_completed=1` 后再安装，**不要**在同一端口启动第二实例。
5. **仍未测**：Root/LSPosed 注入与作用域、API30+ 包可见性、API35+ WindowInsets、真机传感器（模拟器传感器为模拟来源）、GitHub CI 实际运行。

## 5. 未做的事（避免误解）

- 没有在设备上复测本修复版；没有把 JVM 结果当作设备结论。
- 没有修改 QA 的验收期望、测试场景矩阵或任何 QA 证据文件。
- 没有为了“让测试通过”而修改其他模块的接口或行为；`core/`、`accessibility/`、`hook/` 未改动。
