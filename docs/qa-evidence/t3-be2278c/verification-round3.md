# Anti-ads 独立 QA 验证报告（第三轮 / t3）

- Run：`t3-be2278c`；QA：architect-flash（独立验证者，非实现者）；日期：2026-09-21。
- 验证对象：源码提交 **`bea37e8086276dd5475524b81bddf55ab17149e5`**（文档提交），其代码提交为 **`be2278cc31a4ab6683df5affa8dc967bba3fd063`**（t16：修复 QA-01/03/04 的实现 + QA-02 文档）。
- 上游依据：首轮门禁报告 [verification.md](../../verification.md)（run `t3-b19f67f`，结论失败/需修订，4 项发现）、修复对照 [repair-qa01-04.md](../../repair-qa01-04.md)、QA 计划 [qa-plan.md](../../qa-plan.md)、环境记录 [emulator-environment.md](../../emulator-environment.md)。
- 证据目录：[docs/qa-evidence/t3-be2278c](.)（本文件即在该目录内）。
- 本轮 QA 未修改任何产品代码、测试期望或 QA 证据；只新增本轮证据与报告。

> **设备证据来源声明**：本轮首轮/复测的设备会话（API29 AVD）由同队 QA（qa-flash，任务 t17）在同一 AVD 上执行，
> 其安装在设备上的 APK 与本轮独立重建的 APK **字节相同**（见第 1 节哈希对照），因此其设备证据与本报告对象是同一份二进制。
> 归因清楚的引用在本文中一律标注为“来源：t17 设备证据（`docs/qa-evidence/t17-bea37e8/`）”；本轮自己的独立设备复测（若有）单独标注为“本轮 t3 设备复测”。
> 两者都不等于真机、Root/LSPosed 或 OEM ROM 的验证。

## 0. 门禁结论

**通过（限定范围）**：QA-01～04 四项发现全部关闭——QA-01/03/04 由源码修复 + 新增回归用例 + API29 设备复测三重证据确认，QA-02 的文档改写在人工核对与环境独立复核下成立。本对象的构建、测试、lint、组装、夹具与最终 APK 声明全部按合同命令复跑通过（详见第 2、3、4 节），最终 APK 与设备实际安装件字节相同（第 1.2 节），修复版设备行为由两轮独立采集（t17 原始证据 + 本轮 t3 自跑）交叉印证（第 7 节）。

**但本报告同时给出 3 项非产品缺陷的发现（不影响构建与设备结论，需在对外交付材料定稿前修正）**：

- **F1（中）**：`docs/repair-qa01-04.md` 第 3 节“修复版产物”哈希与可复现构建不一致（第 10.1 节）。
- **F3（中）**：`docs/install.md`、`docs/probe.md`、`docs/accessibility.md`、`docs/ui.md`、`docs/hook-probe-observability.md`、`docs/build.md` 曾写“无设备/未在设备执行/修复版尚未复测”，与我方核对的两轮 API29 实测证据冲突（第 10.3 节；收尾时工作树中已出现并行修复改动，未提交，最终判定由该修复任务与 t12 确认）。
- **F2（低）**：`docs/probe.md` 第 3 节的诊断行字段枚举漏列 `tag`/`seq`（第 10.2 节）。

**范围声明**：以上“通过”只覆盖 API29 官方模拟器、非 Root 的免 Root 模式，以及静态/JVM 层面的增强模式逻辑。Root/LSPosed 注入、作用域、同次注册（H01）、API30+ 包可见性、API35+ Insets、真机与 OEM ROM、GitHub CI 实际运行**一律未测**，不得由本报告的通过结论外推（第 9、11 节）。

**本报告不是审查结论**：后续独立审查（t12）是另一道门禁，独立给出 verdict。

## 1. 对象固定与可复现性

### 1.1 提交与工作树

| 检查 | 命令 | 结果 |
| --- | --- | --- |
| 仓库根 | `git rev-parse --show-toplevel` | `/d/test/Anti-ads`（独立仓库，非父目录 `D:/test`） |
| HEAD | `git rev-parse HEAD` | `bea37e8086276dd5475524b81bddf55ab17149e5` |
| 分支 | `git rev-parse --abbrev-ref HEAD` | `main` |
| 工作树 | `git status --porcelain` | 本轮开始时为 ` M docs/emulator-environment.md`、`?? docs/qa-evidence/`、`?? docs/verification.md`（均属 QA 文档/证据，非产品代码） |
| 跟踪文件数 | `git ls-files \| wc -l` | 183 |

产品/测试代码在 `be2278c` 之后未再变化：`git show --name-only bea37e8` 只有 `docs/build.md`、`docs/repair-qa01-04.md`。
t16 修复只触及 `app/`、`probe/` 源码与文档；`core/`、`accessibility/`、`hook/`、`gradle/`、`settings.gradle.kts`、`qa/fixtures/` 未改（`git show --name-only --format='' be2278c` 全量核对）。

### 1.2 APK 哈希：独立重建与设备安装件一致

本轮用**强制全量重跑**（`--rerun-tasks --no-build-cache`，246/246 任务实际执行）重建全部产物，并在重建前后分别计算哈希：

| 产物 | 字节数 | SHA-256（本轮重建） | 与设备安装件对照 |
| --- | ---: | --- | --- |
| `app-debug.apk` | 3,392,024 | `a08a04b94462a09ec740f26988732362a40a20b9b69d50bb8797ce368857f8f5` | 相同（来源：t17 `installed-apks.json` 的 `installedSha256`） |
| `probe-debug.apk` | 3,169,147 | `0a75edd07c17ade8cd86baef87f27adb74e6211706074ae202a26bad95e73896` | 相同 |
| `app-debug-androidTest.apk` | 2,174,433 | `9b72ae61121fa0fe400d13e7b57f04da32217c2866ec86a061aab4a6e9e8c153` | 相同 |
| `probe-debug-androidTest.apk` | 2,172,101 | `a351fc57caa93bac8c399e848034d5fb28121c23199653457c8c51e9b5730680` | 相同 |
| `noqueries-debug.apk`（夹具） | 29,369 | `533cbe72ffa12dd1c276aad1f43aafd9f71d086005859be7b16a96d5479e611a` | 相同 |

- 重建前后哈希逐字节相同（`hash-before-rerun.txt` vs `apk-sha256.txt`），说明在**同一源码 + 同一工具链下构建是可复现的**；设备会话安装的正是这一组字节。
- 全部 5 个 APK 由 Android Debug 证书签名（v2 方案），证书 SHA-256 `a73c75c1db48e2900068074e0f35abee57ea065e18be226e41ffb232a0859f01`（见 `apk-signatures.txt`、`fixture-manifest.txt`）。
- ⚠️ **发现 F1（中）**：`docs/repair-qa01-04.md` 第 3 节给出的 app/probe/app-androidTest 三个哈希与字节数（`afa9a786…` 3,447,031 / `abfa1014…` 3,200,278 / `3a0b2c94…` 2,186,638）**与本轮可复现构建不一致**，磁盘上也不存在任何匹配该字节数的 APK；以它们为“修复版产物”会指向一份无人验证过的二进制。详见第 10 节。

## 2. 构建与检查清单（命令、退出码、关键日志）

证据文件均在 `docs/qa-evidence/t3-be2278c/`。

| # | 检查 | 命令 | 退出码 | 关键日志/结果 | 证据文件 |
| --- | --- | --- | ---: | --- | --- |
| B1 | 质量合同命令（原样） | `bash ./gradlew --no-daemon :core:test :app:testDebugUnitTest :accessibility:testDebugUnitTest :hook:testDebugUnitTest :probe:testDebugUnitTest :app:lintDebug :accessibility:lintDebug :hook:lintDebug :probe:lintDebug :app:assembleDebug :probe:assembleDebug :app:assembleDebugAndroidTest :probe:assembleDebugAndroidTest` | 0 | `BUILD SUCCESSFUL in 1m 57s`；`246 actionable tasks: 4 executed, 242 up-to-date` | `gradle-full.txt` |
| B2 | 强制全量重跑（拒绝用增量结果充当测试证据） | 同 B1 + `--rerun-tasks --no-build-cache --max-workers=2` | 0 | `BUILD SUCCESSFUL in 8m 33s`；`246 actionable tasks: 246 executed` | `gradle-rerun.txt` |
| B3 | 独立夹具构建与 lint（不接主 settings） | `bash ./gradlew --no-daemon -p qa/fixtures/noqueries assembleDebug lintDebug --rerun-tasks --no-build-cache --max-workers=2` | 0 | `BUILD SUCCESSFUL in 2m 47s`；`43 actionable tasks: 43 executed`；该模块 `warningsAsErrors=true`，通过即 0 warning | `fixture-build.txt` |
| B4 | 五个 APK 哈希（重建后） | `stat -c %s` + `sha256sum` | 0 | 见第 1.2 节 | `apk-sha256.txt` |
| B5 | 签名核验（4 个 APK + 夹具） | `apksigner verify --verbose --print-certs` | 0 | 均 `Verifies`，v2 方案，Debug 证书 | `apk-signatures.txt`、`fixture-manifest.txt` |
| B6 | app APK Manifest/权限/入口 | `apkanalyzer manifest print/permissions/min-sdk/target-sdk`、`files cat --file assets/xposed_init` | 0 | 见第 4 节 | `apk-app-manifest.txt`、`apk-app-probe-detail.txt` |
| B7 | probe APK Manifest/权限 | `apkanalyzer manifest print/permissions` | 0 | 见第 4 节 | `apk-dex-and-probe.txt` |
| B8 | DEX 内框架类定义检查 | `apkanalyzer dex packages --defined-only … \| grep '^(C\|P) ' \| grep -i robv` | 0 | app/probe 均 `NONE`（无 Xposed 框架类/包定义）；引用其类型的方法签名存在不代表打包（`compileOnly`） | `apk-dex-and-probe.txt` |
| B9 | APK 内资源核验 | `aapt2 dump resources`、`aapt2 dump xmltree --file res/xml/accessibility_service_config.xml` | 0 | scope 数组 size=1 仅 `com.antiads.probe`；服务 XML `canPerformGestures=false` 等 | `apk-resource-checks.txt` |
| B10 | JVM 用例统计（本轮 XML） | 解析 `*/build/test-results/**/TEST-*.xml` | 0 | **298 例，0 失败 0 错误 0 跳过**（38 个套件） | `jvm-results.json`、`jvm-xml/**`、`testcase-index.json` |
| B11 | lint 报告 | `*/build/reports/lint-results-debug.xml` | 0 | app 1 warning、hook 1 warning、accessibility/probe 0；**0 error** | `lint/*.xml` |
| B12 | 环境事实核查 | `emulator -version`、`emulator -list-avds`、`emulator -accel-check`、`adb version` | 0 | emulator 37.1.11.0（build 15917651）；AVD `AntiAds_QA_API29` 存在；WHPX 可用；adb 37.0.1 | 本报告第 7 节 |

**如实说明（不计为通过）**：

1. B1 的合同命令在本机首次执行时几乎全部 `UP-TO-DATE`（4 executed / 242 up-to-date），因此**不能**用它证明测试真的跑过；B2 才是本轮用于“测试与源码对应”的证据（每一条测试任务的 XML 时间戳都落在本轮运行窗口内）。
2. `NO-SOURCE` 任务共 34 个（`gradle-rerun.txt`）：它们表示无对应输入（例如无 Java 源码的 `compileDebugJavaWithJavac`），**不计作测试通过**。
3. 仪器测试 APK 在本轮只是被**组装**；其真实执行证据来自设备会话（第 7 节）。编译成功不等于执行过。
4. 五模块 JVM 用例数与首轮报告的差异（285 → 298）来自 t16 新增回归用例（app +2、probe +11）；这是新增测试，不是首轮数据被改写。

### 2.1 五模块 JVM 用例明细（本轮强制重跑）

| 模块 | 用例 | 套件 | failures/errors/skipped |
| --- | ---: | ---: | --- |
| core | 67 | 6 | 0/0/0 |
| app | 46 | 6 | 0/0/0 |
| accessibility | 103 | 9 | 0/0/0 |
| hook | 46 | 10 | 0/0/0 |
| probe | 36 | 7 | 0/0/0 |
| **合计** | **298** | **38** | **0/0/0** |

用例方法名索引（含 QA-01/03/04 回归用例）见 `testcase-index.json`。t16 声称的“298 例”在独立重跑中成立。

## 3. 独立夹具（无 queries 的跨应用目标）

- 构建：B3，exit 0，43/43 任务实际执行；`lint { abortOnError = true; warningsAsErrors = true }` → lint 通过即无 warning。
- 哈希：`533cbe72…`（与设备安装件一致，见 1.2）。
- Manifest 实测（`fixture-manifest.txt`）：`com.antiads.fixture.target`、minSdk 29 / targetSdk 35、**无 `<queries>`**、**0 条 `uses-permission`**、`allowBackup=false`、`fullBackupContent=false`、单 Activity `MainActivity`（LAUNCHER，exported）。
- 与其他目标的对照关系（本轮口径）：**有 queries 的 probe**（`<queries><provider authorities="com.antiads.app.config"/></queries>`）、**无 queries 的夹具**（T 目标）、**shell（UID 2000）只作为未授权负例**——三者分列，不混为一类证据。

## 4. 最终 APK 的声明、权限与入口核验

### 4.1 app-debug.apk（`a08a04b9…`）

| 检查点 | 实测结果 | 证据 |
| --- | --- | --- |
| 包名/SDK | `com.antiads.app`、minSdk 29、targetSdk 35、versionName 0.1.0、`debuggable=true` | `apk-app-manifest.txt` |
| 权限 | **0 条 `uses-permission`**（无 INTERNET、无 QUERY_ALL_PACKAGES） | 同上（`manifest permissions` 输出为空） |
| 包可见性 | `<queries>`：MAIN/LAUNCHER intent + `com.antiads.probe`（用于应用列表与诊断入口），**不是** QUERY_ALL_PACKAGES | 同上 |
| Provider | `com.antiads.app.config`，`exported=true`、`grantUriPermissions=false`、`directBootAware=false`、**无 signature 读权限** | 同上 |
| 无障碍服务 | `com.antiads.accessibility.AdSkipService`，`exported=true` + `android.permission.BIND_ACCESSIBILITY_SERVICE`，meta-data 指向服务 XML | 同上 |
| 服务 XML | `accessibilityEventTypes=0x820`（windowStateChanged+windowContentChanged）、`notificationTimeout=100`、`canRetrieveWindowContent=true`、`canRequestTouchExplorationMode=false`、`canRequestFilterKeyEvents=false`、`canPerformGestures=false` | `apk-resource-checks.txt` |
| 备份 | `allowBackup=false`、`fullBackupContent`/`dataExtractionRules` 均已合入（t11/t16 补齐的旧系统资源缺口已关闭） | `apk-app-manifest.txt` |
| Xposed 入口 | `assets/xposed_init` = 单行 `com.antiads.hook.AntiAdsHookEntry`；四条元数据（`xposedmodule=true`、`xposeddescription`、`xposedminversion=82`、`xposedscope`）齐全 | 同上 |
| scope 资源 | 数组 size=1，元素仅 `com.antiads.probe`（建议作用域最小化） | `apk-resource-checks.txt` |
| 框架类打包 | 定义类中 **无** `de.robv.*`；入口类 `com.antiads.hook.AntiAdsHookEntry` 已定义（app 内 54 个 `com.antiads.hook` 类） | `apk-dex-and-probe.txt` |
| Application | `com.antiads.app.AntiAdsApplication` | `apk-app-manifest.txt` |

### 4.2 probe-debug.apk（`0a75edd0…`）

| 检查点 | 实测结果 | 证据 |
| --- | --- | --- |
| 包名/SDK | `com.antiads.probe`、minSdk 29 / targetSdk 35、0.1.0、debuggable | `apk-dex-and-probe.txt` |
| 权限 | **0 条 `uses-permission`**（无 INTERNET） | 同上 |
| 包可见性 | 仅 `<queries><provider authorities="com.antiads.app.config"/></queries>`（准确指向宿主配置 Provider，是 probe 自读策略所需，不得外推给普通第三方目标） | 同上 |
| 组件 | `MainActivity`、`AdFixtureActivity`（均 exported；后者是 QA 用广告样例页） | 同上 |
| 独立性 | 定义类中 `com.antiads.hook` 计数为 **0**，无 Xposed 元数据 → 不是框架模块、不依赖 :hook | 同上 |
| 备份/图标 | `allowBackup=false`、`fullBackupContent` + `dataExtractionRules`、`ic_probe`（t16 补齐的 lint 缺口已关闭） | 同上 |
| 模块声明 | 无 `de.robv.*` 定义 | `apk-dex-and-probe.txt` |

### 4.3 两条保留 warning 的理由与验证（未加任何抑制）

- 仓库内**没有** `lint.xml`、`lint-baseline.xml` 或任何 baseline（`find` 结果为空）；除夹具的 `lint { abortOnError/warningsAsErrors }`（收紧而非放宽）外没有 `lint {}` 配置。
- `:app` `ExportedContentProvider`：Provider 必须 `exported=true` 才能让**目标应用自己**读取自己的最小策略；边界由每次 `call` 内 `Binder.getCallingUid()` 现场鉴权承担，且**没有**用 signature 读权限阻断（那会同时阻断授权目标读取，属于被明确禁止的消警告手段）。实现见 `app/src/main/kotlin/com/antiads/app/config/ConfigProvider.kt`（只在 `call()` 暴露 `get_policy_v1`/`report_hook_v1`；`query/getType` 返回 null，`insert/update/delete/bulkInsert/openFile/openAssetFile` 一律拒绝）。
- `:hook` `PrivateApi`：拦截点 `SystemSensorManager$SensorEventQueue#dispatchSensorEvent` 是非公开框架 API，只能反射定位；形状不符即 `UNSUPPORTED` 并保持原始调用（见 `HookRuntime`、`SensorDispatchTarget`），因此保留 warning 并单独验收。
- 这两条 warning 的具体验证证据在设备与 JVM 两侧分别给出（第 5、7 节）；**JVM/编译通过不证明设备允许该反射**。

## 5. QA-01～04 关闭核对（逐条：源码 → 回归用例 → 证据）

| 编号 | 修复点（源码） | 回归用例（本轮实跑） | 关闭判定 |
| --- | --- | --- | --- |
| QA-01（高）授权真值错报 | `StatusFacts.matchesAdSkipService(hostPackageName, servicePackageName, serviceClassName)` 要求归属包 == **宿主包名**且类名完整精确匹配；`MainActivity.isServiceEnabledInSystemSettings()` 传 `packageName`（= applicationId）；删除误导常量 `AD_SKIP_SERVICE_PACKAGE` | `StatusFactsTest.libraryNamespaceIsNotTheServiceOwningPackage`、`serviceIdentityUsesHostPackageAndExactServiceClass`、`serviceIdentityRejectsWrongOrMissingIdentifiers`（本轮 46 例中实跑通过）；设备级 `ServiceOwnershipTest.adSkipServiceComponentBelongsToHostPackage`（查真实 `PackageManager`） | JVM 侧关闭；设备侧以 `ServiceOwnershipTest` + 系统设置真实授权后的首页状态为准（第 7 节） |
| QA-03（中）返回后无法显式重启采样 | 新增纯逻辑 `ProbeUiStateRules.of(samplingRunning, activityResumed)`：`startEnabled=!running`、`stopEnabled=running`、`periodicRefresh=resumed`、`startSampling` **恒 false**；`MainActivity` 以 `applyUiState()` 为唯一同步点，在 `onCreate/onResume/onPause/start/stop` 后调用，并重建 ticker | `ProbeUiStateRulesTest`（4 例，含 `autoStartIsNeverRequestedInAnyCombination`） | JVM 侧关闭；设备侧需观察“开始可用/停止禁用+显式点击后重新注册”（第 7 节） |
| QA-04（中）诊断文件未生成 | `DiagJson.snapshot()` 固定字段单行 JSON；`DiagFileThrottle` ≤1 次/秒 + `force` 立即写并重置窗口；`SensorDiagSession.dumpFile(now, force)` **仅 debuggable** 进入，异常吞掉返回 null；`MainActivity.writeDiagSnapshot()` 在每秒刷新（force=false）与开始/停止/onPause/onResume（force=true）调用，成功输出 `kind=snapshotFile` 行 | `DiagFileThrottleTest`（5 例）、`DiagJsonTest.snapshotIsParseableAndCarriesOnlyDiagFields`（解析并断言顶层与行字段集合**恰好**等于约定集合，不含界面文本/输入内容/读数）、`snapshotFileLineRecordsPathAndForce` | JVM 侧关闭；设备侧需 `run-as … cat files/probe-diag.json` 成功且节流可核对（第 7 节） |
| QA-02（中）构建文档环境现状不一致 | `docs/build.md` 第 5 节按时间重写为 5.1 环境现状（组件已安装/AVD 已创建/曾启动/当前实例已清理）、5.2 首轮实际设备验证与失败结论、5.3 仍未验证、5.4 历史存档 | 人工核对：全文不再出现“未安装 emulator/镜像”“无 AVD”“工具链分离”的**现状性**表述；历史段明确标注“仅存档、只对当时成立”；环境事实由本轮 B12 独立复核（emulator 37.1.11.0 存在、AVD 存在、WHPX 可用） | 关闭（文档类）。注意 5.2 的失败结论严格限定在 `b19f67f` |

补充核对（防止“修复把验收期望改软”）：

- t16 **未**修改 `docs/verification.md`、`docs/qa-evidence/**`、`qa/fixtures/**`（提交文件清单与工作树一致）；首轮 4 项发现的原文仍在，未被改写。
- 回归用例确实是**新断言**而非放宽旧断言：`StatusFactsTest` 旧用例改为按宿主包语义断言并新增 2 个拒绝用例；`ProbeUiStateRulesTest`/`DiagFileThrottleTest`/`DiagJsonTest.snapshot*` 为新增文件/新增方法（`testcase-index.json` 可对照方法名）。
- 首轮 285 例 → 本轮 298 例全部通过，说明旧用例没有被删除换取“全绿”。

## 6. 目标限制、误触发、配置通信、传感器恢复、权限与模块声明检查

（第 6 节的设备部分在第 7 节完成后补齐设备列。）

### 6.1 检查项与证据

| 领域 | 检查项 | 证据（类型） | 结论 |
| --- | --- | --- | --- |
| 目标限制 | 只做 LAUNCHER 列表 + 手工包名，不申请 QUERY_ALL_PACKAGES | APK Manifest（S） | 通过 |
| 目标限制 | 无 queries 的第三方目标在 API30+ 不能靠 <queries> 可见；应用把有/无 queries 分开取证，不外推 | 夹具 Manifest（S）+ 设备（E，第 7 节） | 通过/设备部分见 7 |
| 目标限制 | 宿主/系统 UI/`android` 包与敏感包（支付、输入法等）不处理 | `CoreRuleToExecutionIntegrationTest`、`SensitivePackageRulesTest`、`ExecutionGuardsTest`（J） | 通过（JVM） |
| 误触发 | 窗口类型/锁屏/可编辑节点/敏感文案（购买·登录·授权）/不可点击/遍历不完整/快照过期/revison 变化 → 拒绝点击 | `ExecutionGuardsTest` 37 例、`SkipGateTest` 18 例（J） | 通过（JVM） |
| 误触发 | 同一 epoch 只尝试一次点击 + 同包冷却 + 300 节点/深度 20/8ms 预算 | `SkipGateTest`/`TraversalBudgetTest`（J） | 通过（JVM） |
| 配置通信 | Provider 只有两个只读 call；`query/getType` 返回 null；写方法拒绝 | 源码（S）+ `ProviderEntryPolicyTest`/`ProviderCallRouterTest`（J）+ 设备 shell 负例（E） | 通过 |
| 配置通信 | 调用方身份来自 `Binder.getCallingUid/Pid`，同用户 + 应用 UID + 单包==arg；shared UID/isolated 拒绝；每 UID 限流；报告 PID 服务端覆盖 | `ProviderCallRouterTest` 14 例、`RateLimiterTest`（J）+ 设备真实 UID 读（E） | 通过（JVM + 设备列见 7） |
| 配置通信 | 没有导出写接口；shell 只作为未授权负例 | 源码（S）+ 设备（E） | 通过 |
| 传感器恢复 | 关闭/排除目标时 `hookEnabled=false` + 空类型集合，原始回调不变；策略到期（now ≥ expires）必须 ALLOW；无策略/坏响应/时钟回退一律放行 | `FailOpenDecisionTest`、`HookPolicyLeaseTest`（含 `publishesLeaseFromRequestStartAndRespectsExpiryBoundary`、`decisionsIgnoreWallClockChangesAndKeepDroppingWhileLeaseValid`）、`HookStuckTransportTest`（J） | 通过（JVM）；设备/真机未测 |
| 传感器恢复 | 停用无障碍后排队点击立即取消；执行前复核 revision/连接/窗口 | `SkipGateTest`/`ExecutionGuardsTest`（J） | 通过（JVM） |
| 权限 | 两个应用 APK 均 0 条 uses-permission；服务需系统 `BIND_ACCESSIBILITY_SERVICE`；Provider 不设 signature 读权限 | APK Manifest（S） | 通过 |
| 模块声明 | `assets/xposed_init` 单行入口 + 四条元数据 + scope 只含 probe；无 `de.robv.*` 打包 | APK（S，B6–B9） | 通过 |
| 模块声明 | 无障碍服务/服务 XML/Provider/authority 与 contracts 一致 | APK（S） | 通过 |
| 组件导出面 | 导出组件仅：MainActivity（LAUNCHER）、Provider（只读 call）、AdSkipService（系统绑定）、AdFixtureActivity | APK（S） | 通过 |

### 6.2 本轮新增问题（详见第 10 节）

- **F1（中）**：`docs/repair-qa01-04.md` 第 3 节的“修复版产物”哈希不可复现、与设备实际安装件不同 → 交付材料若引用会指向未验证二进制。
- **F2（低）**：`docs/probe.md` 第 3 节 rows[]/行字段枚举未列出实际存在的 `tag`、`seq`（`DiagJsonTest` 断言的精确集合含这两项）→ 文档精度问题。

## 7. 设备证据（API29 模拟器）

### 7.1 环境与对象

| 项目 | 实测值 | 证据 |
| --- | --- | --- |
| 主机 | Windows 10 Pro 19045（WHPX 可用：`emulator -accel-check` → `WHPX(10.0.19045) is installed and usable`） | 本轮 B12 |
| AVD | 项目专用 `AntiAds_QA_API29`（`ANDROID_AVD_HOME=.tooling/emulator-qa/avd`，Pixel 1080×1920，swiftshader，无窗口） | `emulator -list-avds` → `AntiAds_QA_API29` |
| serial / 系统 | `emulator-5580`；Android 10（API 29）、x86_64、指纹 `Android/sdk_phone_x86_64/generic_x86_64:10/QSR1.210820.001/7663313:userdebug/test-keys`、补丁 2019-09-05 | 来源：t17 设备证据 `device-ready.txt` |
| 安装的 APK | 5 个 APK 全部 `Success`，且设备内 `base.apk` 的 SHA-256 与本地文件逐字节相同（`installedSha256`） | 来源：t17 `install-four.txt`/`install-fixture.txt`/`installed-apks.json`；本地重建一致性见 1.2 |
| 设备侧身份 | `shell` UID 2000（仅作未授权负例）；probe UID 10117；夹具 UID 10120；宿主 UID 10116 | 来源：t17 `installed-apks.json`/`provider-shell-negative`（首轮）/本轮静态复核 |

**身份绑定**：本轮独立重建的 APK 与 t17 设备会话实际安装的 APK 哈希相同（第 1.2 节），所以下面引用的设备行为与本报告验证的对象是同一份二进制；但**设备会话由 t17 执行**，本报告逐项采用其**原始证据文件**（截图/logcat/JSON），而不是它的文字结论。

### 7.2 QA-01 设备复测（授权真值）

| 观测点 | 实测结果 | 证据（原始文件） |
| --- | --- | --- |
| 系统侧授权事实 | `accessibility_enabled=1`；`Enabled services: {com.antiads.app/com.antiads.accessibility.AdSkipService}`；`Bound services` 存在（label `Anti-ads 无障碍跳过`，eventTypes=windowStateChanged+contentChanged，notificationTimeout=100） | `authorized-system-facts.txt`、`r2-qa01-regrant-secure.txt` |
| 首页显示（真实授权后） | **“系统设置授权：是” + “服务实际已连接：是”**，运行阶段“已连接，当前没有合格目标”；两者分列显示；同时显示“最近一次跳过动作：com.antiads.probe（354 秒前，系统已接受点击动作（不代表广告已消失））” | `r2-qa01-regranted-home.png`（本人直接读图核对） |
| 撤权后回落 | 撤权后首页显示未授权（授权=否） | `r2-qa01-revoked-home.png` |
| 设备级回归用例 | `ServiceOwnershipTest.adSkipServiceComponentBelongsToHostPackage` 与 `ConfigToggleTest` 同次 instrument 运行 `OK (2 tests)`；查询真实 `PackageManager` 断言组件归属宿主包 | `app-instrumentation.txt`、`r2-app-instrumentation.txt` |

判定：**QA-01 关闭**（源码 + JVM + 设备三级证据一致）。

### 7.3 QA-03 / QA-04 设备复测（probe 生命周期与诊断文件）

| 观测点 | 实测结果 | 证据（原始文件） |
| --- | --- | --- |
| 运行中 | 快照 `samplingRunning=true`、`registrationSeq=6`、五个类型 `REGISTERED` 且回调非零、对照类型 2 `control=true` | `probe-running.json`、`probe-running.png` |
| 按 HOME 后 | 同一 sessionId、`samplingRunning=false`、各行 `UNREGISTERED`、计数保留 | `probe-paused.json`、`r2-qa04-paused.json` |
| 返回页面 | 提示“需要重新点开始采样（不会自动重启）”，且**开始可用、停止禁用**；点击开始后显式重新注册（registrationSeq 6→12，PID 与 session 变化按新会话处理） | `probe-returned.png`、`r2-qa03-returned.png`、`r2-qa03-restarted.png`、`probe-restarted.json` |
| 诊断文件 | `run-as com.antiads.probe cat files/probe-diag.json` 返回**可解析单行 JSON**（kind=`probeDiagSnapshot`，字段与 `docs/probe.md` 一致）；logcat 有 `kind=snapshotFile` 行（含 `force`、`path`） | `.tooling/t17-qa/probe-diag-pulled.json`（本人核对字段）、`r2-qa04-snapshotfile-lines.txt` |
| 节流 | 96.5 秒窗口内 95 条 snapshotFile 行：89 次非强制 + 6 次强制（force），**最小间隔 1003ms、无一次 <1000ms** | `r2-qa04-throttle-summary.json` |

判定：**QA-03 关闭**（设备上按钮状态与显式重启符合预期，且有“不自动开始”的 JVM 断言兜底）；**QA-04 关闭**（文件真实生成、可解析、节流为实测而非仅单测）。

### 7.4 广告正反例与三层关闭（修复版 APK 上）

| 检查 | 实测结果 | 证据 |
| --- | --- | --- |
| ad_positive 正例 3 轮 | 三个独立进程（3481/3524/3565）各出现且仅出现一次 `fixture_skip_clicked`（view=Button，886,84），open→click 分别约 3.4s/3.6s/2.5s；无人工点击按钮制造正例 | `r2-positive-rounds.txt`、`r2-pos-ad_positive.png`、`r2-pos2-ad_positive.png` |
| 反例 | `bottom_button`（中心 Y 比 0.9532）、`editable_window`（含 EditText）、`no_ad_label`（无广告上下文）各等待 12 秒：**零 `fixture_skip_clicked`** | `r2-negative-rounds.txt`、`r2-neg-*.png` |
| 关闭条件 1：全局无障碍关（revision 27） | ad_positive 12 秒无点击 | `r2-close-conditions.txt`、`r2-close1-global_a11y_off.png` |
| 关闭条件 2：本包无障碍关（revision 29） | ad_positive 12 秒无点击 | `r2-close2-perapp-off.png` |
| 关闭条件 3：总开关关 | ad_positive 12 秒无点击，首页显示总关 | `r2-close3-master-off-run.png`、`r2-home-master-off.png` |
| 真实目标 UID 读 | 夹具（无 queries，UID 10120）用自己的 UID 调 `get_policy_v1` 成功返回本包最小策略；probe（有 queries）自查读取成功 | `r2-fixture-policy-read-result.png`、`r2-fixture-policy-read.png`、`r2-probe-policy-selfread.png` |
| 收尾 | 恢复专用测试配置并撤权（首页回落、secure 值 0/服务集合空） | `r2-restore-control.txt`、`r2-qa01-revoked-home.png` |

**未在设备上执行（不得当作通过）**：`non_clickable` 反例（修复版这一轮只跑了 3 个反例；首轮在旧 APK 上跑过 4 反例 + 未知值）、`RegistrationHoldTest`（H01 同次注册）、任何 Root/LSPosed 注入与作用域、API30+ 包可见性、API35+ Insets、真机。

### 7.5 未做的设备项（一律未测）

- Root/LSPosed/Magisk：无 Root 设备 → H01 A1/A2、注入成功/失败、作用域生效、框架停用的恢复**全部未测**；`docs/hook*` 中的设备矩阵仍为空。
- 5 秒租约的设备观测：需要真实注入后观察“截止后仍是否丢弃”，未测；本轮只有 JVM 边界用例（`now ≥ expires ⇒ ALLOW`）与源码核对。
- 真机/OEM ROM/工作资料/多用户/shared-UID/isolated-UID：未测。

### 7.6 本轮 t3 自己的设备复跑（同一 AVD、同一 APK，2026-09-21 02:31–02:42Z）

以下全部为本轮 QA 亲自执行（job `bash-36`），原始输出/截图在 `docs/qa-evidence/t3-be2278c/`。

| 项目 | 实测结果 | 证据 |
| --- | --- | --- |
| 启动与就绪 | 以受管 job 启动同一 AVD（serial `emulator-5580`）；两次独立确认 `get-state=device`、`sys.boot_completed=1`、`emu avd name=AntiAds_QA_API29`；Android 10 / API29 / 指纹 `Android/sdk_phone_x86_64/generic_x86_64:10/QSR1.210820.001/7663313:userdebug/test-keys` | `device-ready.txt` |
| 未起第二实例 | 启动前确认无 qemu 进程、5580/5581 无监听、`adb devices` 为空，且 t17 已记录 `r2-devices-after-exit.txt`；本轮未触碰 t17 会话 | 本报告 7.5、`t3-cleanup.txt` |
| 安装 + 字节一致 | 5 个 APK `install -r` 全部 `Success`；设备内 `base.apk` 的 SHA-256 与本轮重建**逐字节相同**（app `a08a04b9…`、probe `0a75edd0…`、夹具 `533cbe72…`） | `t3-install.txt`、`t3-installed-hashes.txt` |
| 冷启动 + 中文界面 | `am start -W` 首次返回 `Status: timeout`（慢模拟器上的 -W 等待），但 `dumpsys activity` 显示 `mResumedActivity: com.antiads.app/.MainActivity` 且 `pidof` 非空；随后一次为 `LaunchState: COLD, TotalTime 2910ms`。首页为全中文，显示“系统设置授权：否 / 服务实际已连接：否 / 运行阶段：未连接（系统未绑定服务）”与“配置版本 33 / 配置文件状态：正常 / 已配置应用 2 个”——此处在未授权状态下**不冒充成功** | `t3-home-initial.png`、`t3-config-persistence.txt` |
| QA-01 我本人复跑 | 走系统设置 UI → 服务详情页 → 打开开关 → 系统 “ALLOW” 对话框确认真实授权（非 adb 写 secure setting）；授权后 `accessibility_enabled=1`、`enabled_accessibility_services=com.antiads.app/com.antiads.accessibility.AdSkipService`、dumpsys 有 Bound services；返回首页显示**“系统设置授权：是”+“服务实际已连接：是”** | `t3-qa01-device.txt`、`t3-a11y-settings-list.png`、`t3-a11y-service-detail.png`、`t3-a11y-grant-dialog.png`、`t3-home-authorized.png` |
| QA-03 我本人复跑 | probe 起始 `sessionId=2f978bd1`、`registrationSeq=0`、对照类型=磁场（类型 2）；点“开始采样”后 `registrationSeq=6`、五类型 REGISTERED、回调非零；HOME 后**同一 session**、`samplingRunning=false`、各行 UNREGISTERED；返回（`LaunchState: HOT`）后点击“开始采样”得到 `registrationSeq=12` | `t3-probe-qa03-qa04.txt`、`t3-probe-running.png`、`t3-probe-after-home.png`、`t3-probe-returned.png`、`t3-probe-restarted.png` |
| QA-04 我本人复跑 | `run-as com.antiads.probe cat files/probe-diag.json` 返回**可解析单行 JSON**（`kind=probeDiagSnapshot`，字段与 `docs/probe.md` 一致，行内包含 `tag`/`seq`——见 F2）；27 秒窗口内 25 条 `snapshotFile` 行（21 非强制 + 4 强制）：**所有非强制写入间隔 ≥1003ms、无一次 <1000ms**；唯一的两次 <1000ms 相邻写入，其后者 `force=true`（状态变化立即写，符合设计） | `t3-snapshotfile-lines.txt`、`t3-probe-qa03-qa04.txt` |
| 广告正例（我本人复跑） | `ad_positive` 两轮（独立进程 3780/3826）各出现且仅出现一次 `fixture_skip_clicked`（`view=Button center=886,84`；layout→click 约 1.2s / 0.1s）；截图显示页面底部“已跳过” | `t3-ad-positive-run.txt`、`t3-ad-rounds.txt`、`t3-ad-positive.png` |
| 广告反例（我本人复跑） | `non_clickable`（`clickable=false`，补上 t17 修复版轮次未跑的这一个）与 `unknown_case`（实际回退 `no_ad_label`，`ad_context_visible=false`）各等 12 秒，**零 `fixture_skip_clicked`** | `t3-ad-rounds.txt`、`t3-ad-neg-nonclickable.png`、`t3-ad-neg-unknown.png` |
| Provider 鉴权（我本人复跑） | shell UID 2000：`get_policy_v1` → `ok=false, error=UNAUTHORIZED`；换 arg 仍 UNAUTHORIZED；`write_config` → `INVALID_REQUEST`；`content query` → `No result found.`；`content insert` → `UnsupportedOperationException: 只读 Provider：INSERT 被拒绝`；宿主进程未被强停、配置未被改写 | `t3-provider-shell-negatives.txt` |
| 无 queries 夹具真实 UID 读（我本人复跑） | 夹具 `com.antiads.fixture.target`（UID 10120、PID 4034、Manifest 无 queries）以自身 UID 读取：`ok=true`，`duration_ms=38`，payload **只含本包** `{"schemaVersion":1,"revision":34,"packageName":"com.antiads.fixture.target","hookEnabled":true,"blockedSensorTypes":[1]}` | `t3-fixture-policy-read-result.png`、`t3-fixture-initial.png` |
| 配置保存与重启 | UI 切换总开关（revision 33→34）→ `am force-stop` + 冷启动 → 仍为 revision 34 且总开关为开 | `t3-config-persistence.txt` |
| 平台行为（非产品缺陷） | `am force-stop com.antiads.app` 之后，**系统自身**把 `accessibility_enabled` 置 0、清空 `enabled_accessibility_services`、Bound/Enabled 集合为空；因此同一时刻首页显示“授权=否/连接=否”是**真实状态**，不是 QA-01 复发。复核授权真值必须先授权、期间不要 force-stop 宿主 | `t3-force-stop-a11y-state.txt` |
| 收尾 | 把总开关恢复到 t17 留下的状态（**revision 35、masterEnabled=false**），确认 `accessibility_enabled=0`、服务集合 `null`；核对 AVD 名后仅对本 serial 执行 `emu kill`（exit 0），job bash-36 以 exit 0 收束，随后 `adb devices` 为空；未停止全局 adb、未动用户 AVD/真实设备 | `t3-cleanup.txt`、`t3-final-state.txt` |
| qa-flash 报告固定 | 独立复核 t17 报告文件：`docs/verification.md` 与 `docs/qa-evidence/t17-bea37e8/t17-verification.md` 均为 `8cff3388…b99ded7`（与 qa-flash 自报一致） | `t3-final-state.txt` |

**方法学诚实说明**：第一次核对安装件哈希时用 `adb exec-out cat <设备路径> | sha256sum`，三个包全部 MISMATCH；原因是 Git Bash 的 MSYS 路径转换把 `/data/app/...` 改写成 `C:/Program Files/Git/data/app/...`，并非安装异常。改用 `MSYS_NO_PATHCONV=1` 并在设备内执行 `sha256sum` 后全部 MATCH。该失败过程与修正后的结果都保留在同一证据文件里（未用“重试成功”覆盖失败记录）。

## 8. 46 项验收场景的边界（修复版对象）

图例：**S**=源码/最终包静态检查，**J**=本轮强制重跑的 JVM 用例，**E**=API29 模拟器（本轮修复版；来源已在第 7 节标注），**D/R**=普通/Root 真机（均未连接）。
“J 通过”只指本轮同次运行中实际存在的相关套件（`jvm-results.json`/`testcase-index.json`），不扩大到设备或未跑的夹具分支。

| 场景 | 已执行范围与结果 | 尚缺证据 |
| --- | --- | --- |
| C01 | E 通过：修复版冷启动（本轮 t3 亲自复跑，`LaunchState: COLD 2910ms`）在**未授权**状态下正常显示真实状态、不冒充成功；S/J 默认值用例通过 | 普通真机、全新安装（本轮与 t17 都是 `install -r`，配置被保留） |
| C02 | J 通过；E 通过无障碍全开与 3 个独立关闭条件（全局/本包/总开关，第 7.4 节） | 两模式 8 组合的设备全覆盖；Root 模式 |
| C03 | J 的 PolicyResolver/Validator 用例通过 | 设备上删包、清空类型、清空 ruleIds 的全流程未实测 |
| C04 | J 通过（坏数据/上限集）；E 通过 `INVALID_JSON` 冷读与精确恢复（首轮，`b19f67f`；该路径的代码未被 t16 修改） | 修复版未重复注入坏配置；其他损坏/上限数据的设备注入未实测 |
| C05 | J 仓储冲突/revision 边界用例通过 | 无设备并发结论 |
| C06 | J 仓储错误路径通过；E 正常落盘/重启通过（首轮） | Android 不可写存储、落盘中途 I/O 故障未注入 |
| C07 | J observe/listener/close 用例通过 | 不追加设备竞态结论 |
| R01 | E 通过：本轮 t3 亲自复跑——UI 改总开关（33→34）后 force-stop + 冷启动仍是 revision 34 且开关为开；首轮另有重启保留证据 | 普通真机/OEM 后台策略；顺带发现 API29 平台会在 force-stop 后清除无障碍授权（见 7.6，属平台行为） |
| R02 | E 通过：三层关闭后正例各 12 秒零点击；撤权后回落（第 7.4 节） | **停用框架/卸载后的传感器恢复未实测**（无 Root） |
| A01 | E 通过：修复版共 5 轮（t17 3 轮 + 本轮 t3 2 轮），每轮独立进程各出现且仅出现一次自动点击；首轮另有 3 轮 + 重启后 1 轮 | 真实第三方广告、各 ROM |
| A02 | E 通过（修复版合并两轮）：t17 跑底部/可编辑/无广告上下文 3 反例，本轮 t3 补跑 `non_clickable` 与未知值（回退 no_ad_label），全部 12 秒零点击；首轮另有 4 反例 + 未知值 | 更广真实应用负例、真机 |
| A03 | J 匹配器用例通过 | 不标设备全文案矩阵通过 |
| A04 | J 节点安全用例通过；E 不可点击/无广告/底部/可编辑反例（跨两轮）通过 | 真机锁屏、密码节点、隐藏/禁用等分支未全跑 |
| A05 | J 几何与候选边界用例通过 | 无真实分屏/挖孔外推 |
| A06 | J 前台窗口/时钟边界用例通过 | 设备上精确毫秒竞态未实测 |
| A07 | J 排队动作/新鲜度/执行前复核用例通过 | 设备上“排队后撤权”等竞态未穷尽 |
| A08 | J 预算/节流用例通过；E 每轮正例仅一次点击 | 真实大树/高频压力与性能基准未实测 |
| A09 | E 通过（修复版）：授权=是/连接=是分列，撤权回落；设备级 `ServiceOwnershipTest` 通过 | 各 OEM 权限管理器差异未实测 |
| A10 | S/J 过滤用例通过；E 权限页停留未观察到误动作 | 各 OEM 权限控制器、系统包负例未穷尽 |
| H01 | **R 未实测**；设备仅基线（probe 注册/回调/生命周期，`registrationSeq` 变化已记录） | 有框架的“同一注册 baseline→开→关”与独立对照 |
| H02 | J 决策用例通过 | 五类型真实拦截、关闭/空集合放行未实测 |
| H03 | E 无框架启动、传感器回调非零、界面显示“未观察到注入/尚未报告” | Root 真机上未激活/未选作用域对照 |
| H04 | **R 未实测** | 作用域修改/升级后旧进程与重启新进程 |
| H05 | J 租约与时间边界用例通过（含 `now ≥ expires ⇒ ALLOW`） | 设备上 ≤5 秒放行的真实决策与同次注册回调恢复 |
| H06 | J 时钟/worker 用例通过 | 不冒称设备休眠/真实 Binder 卡死实验 |
| H07 | J 客户端坏响应/超时路径通过；E 损坏配置下安全放行（首轮） | 已拦截情况下真实 Provider 不可达后的放行未实测 |
| H08 | S/J 方法形状/未知 handle 用例通过；`PrivateApi` warning 保留并说明 | 各系统真实安装/失败降级未实测 |
| H09 | S/J 工作线程/节流用例通过 | Hook 高频事件、30 秒无流量、耗电/线程上限未实测 |
| H10 | **R 未实测** | 第二进程、isolated、shared UID、工作资料与分身 |
| H11 | **R 未实测** | 框架停模块而目标进程仍驻留时的恢复 |
| H12 | S 核对：只覆盖 Java 分发路径 | Native/JNI/Direct Channel/厂商路径不在保证范围，未实测 |
| S01 | J 通过；E 真实目标 UID 读取成功（夹具 10120 本轮 t3 亲自复跑，probe 10117 由 t17 复跑）；shell UID 2000 直接调用与改 arg 均为 `UNAUTHORIZED`（本轮 t3 亲自复跑） | 改 arg 冒充**他包真实 UID**（需另一应用伪造）未做；多用户/shared/isolated 未实测 |
| S02 | J 通过；E 本轮 t3 亲自复跑：`write_config`→`INVALID_REQUEST`、`content query`→`No result found.`、`content insert`→`UnsupportedOperationException: 只读 Provider：INSERT 被拒绝`，且配置未被改写；首轮另有 `OPEN_FILE` 拒绝 | `update/delete/bulkInsert/openAssetFile/getType` 的设备矩阵未全跑 |
| S03 | J 调用方验证用例通过 | Android 真实多用户/isolated/shared UID 未实测 |
| S04 | J 报告验证用例通过 | 真实目标进程恶意 report/框架上报未实测 |
| S05 | J 限流/LRU 用例通过 | 多 UID 真实 Binder 压力未实测 |
| S06 | API29 只补充 P/T 真实 UID 对照、最终 queries 与基线证据（第 3、7.4 节） | 规定的高版本（API30+）可见性矩阵与 Root 传输/租约组合未实测 |
| S07 | S + E 通过：目标拉起死宿主 Provider 并成功读策略（首轮；本轮夹具自有 UID 读取同样依赖该路径） | 没有额外 Provider/Application 打点或 Root 运行 |
| S08 | S + E 检查最终包权限/身份/外部不可写边界 | 无生产隐私审计或全系统权限证明 |
| P01 | E 通过（修复版）：返回后可显式开始、停止禁用、诊断文件存在且节流实测 | 无硬件/注册失败的真实设备、修复版长时稳定性 |
| P02 | E 通过：独立广告页与未知值回退（首轮） | 不扩大到第三方广告覆盖 |
| U01 | E 通过：保存/未观察到注入文案真实；“授权=是/连接=是”在修复版正确显示 | Installed/Unsupported/Failed、dropped 等框架状态设备验证（需 Root） |
| U02 | J 过期/报告存储用例通过 | 真实报告停止 15 秒后的 UI 与进程实例切换未实测 |
| U03 | E 通过：应用列表、手工添加两个合法测试包、权限入口（首轮） | 非法包/包不可见/缺失管理器等入口矩阵未全跑 |
| U04 | API35+ 未实测；E 仅 API29 竖屏三键导航 | Insets、横屏、手势、挖孔、分屏、大字体 |
| U05 | 未实测；本轮只 API29 adb 侧载 | API33+ 受限设置及对应授权路径 |

## 9. Android 版本与运行模式矩阵

通用 debug APK 在 compile/targetSdk 35、minSdk 29 条件下已构建并通过本轮强制重跑；“未实测”不因通用 APK 构建通过而改变。
本表区分**已构建 / 已模拟（API29 模拟器）/ 已实测（真机） / 未实测**；本仓库没有任何普通真机或 Root 真机证据。

| Android/API | 已构建 | 非 Root 模式（无障碍） | Root/LSPosed 模式（传感器拦截） |
| --- | --- | --- | --- |
| 10 / 29 | 是（debug APK + 2 个测试 APK + 夹具 APK；本轮字节级复现） | **已模拟（两轮独立会话）**：C01/C02/A01/A02/A09/P01/P02/S01/S02、T 与 P 的真实 UID 读取、probe 生命周期与诊断文件节流、两个 instrumentation 用例；QA-01/03/04 修复经设备双向复测 | **未实测**：无 Root/LSPosed 设备；仅 S/J 与非拦截基线；H01 同次注册未做 |
| 11 / 30 | 是（同一通用 APK） | 未实测（包可见性 P/T 对照未做） | 未实测 |
| 12 / 31 | 是 | 未实测 | 未实测 |
| 12L / 32 | 是 | 未实测 | 未实测 |
| 13 / 33 | 是 | 未实测（受限设置/安装来源未验证） | 未实测 |
| 14 / 34 | 是 | 未实测 | 未实测 |
| 15 / 35 | 是（compile/target 35） | 未实测（强制 edge-to-edge/Insets 未验证） | 需该版本可用的框架维护分支，不能默认原版 LSPosed 支持 |
| 16 / 36 | 未构建（compileSdk 35） | 未实测 | 未实测 |
| 37+ | 未构建 | 未实测、不承诺 | 未实测、不承诺 |

补充（避免过度解读）：

- 模拟器是 **userdebug 官方镜像**，不是 Root 设备，也不等于任何 OEM ROM；其传感器为模拟来源。
- 无障碍模式**不能**阻止摇动传感器、原生点击行为或没有可访问节点的广告；增强模式仅覆盖受支持的 Java 传感器分发路径，需要 Root/框架实际注入与用户作用域。
- “安装成功、保存策略、进程自报、Provider 读取成功、零回调”都不能单独证明真实拦截。
- 本报告不承诺“所有应用、所有系统版本”的覆盖。

## 10. 发现清单

### 10.1 F1（中）修复版产物哈希与可复现构建/设备安装件不一致

- 现象：`docs/repair-qa01-04.md` 第 3 节列出 `afa9a786…`（3,447,031 B）/ `abfa1014…`（3,200,278 B）/ `3a0b2c94…`（2,186,638 B）/ `a351fc57…`（2,172,101 B）；本轮以同源码、同工具链强制全量重建得到 `a08a04b9…`（3,392,024 B）/ `0a75edd0…`（3,169,147 B）/ `9b72ae61…`（2,174,433 B）/ `a351fc57…`（一致）。
- 影响：只有 probe 的仪器测试 APK 一项两者相同；其余三项不一致，且磁盘上不存在与 t16 字节数匹配的 APK。任何按该表核对哈希的复核者会得到“不一致”，或者去安装一份 QA 从未验证过的二进制。
- 已证实的关联事实：t17 设备会话安装的 5 个 APK 哈希（`installed-apks.json`）与本轮重建**逐字节相同**，因此“设备证据 ↔ 本轮产物”的绑定成立；不成立的是 t16 文档里那三个哈希。
- 处置建议（不由 QA 执行）：把第 3 节替换为本轮可复现哈希，并注明“本轮 QA 已用 `--rerun-tasks --no-build-cache` 复现”；或在表头标注“t16 本地构建快照，未被独立复现，不得作为安装依据”。同时检查 `docs/build.md` 第 4.1 节（t11 历史哈希，已标注为历史，无冲突）与 `docs/repair-qa01-04.md` 第 2 节的“298 例”等表述是否引用了同一批旧产物。

### 10.2 F2（低）probe 诊断快照的行字段文档不完整

- 现象：`docs/probe.md` 第 3 节的 `rows[]` 字段枚举（`type/exists/…/control`）与“行字段：`sessionId … control`”段落都没有列出实际存在的 `tag`、`seq` 两项；`DiagJson.row()` 与 `DiagJsonTest.snapshotIsParseableAndCarriesOnlyDiagFields` 断言的精确集合都包含它们，设备上拉取的真实 `probe-diag.json` 也含这两项（`t3-probe-qa03-qa04.txt`）。
- 影响：做字段核对的人可能误判为“多出字段”。不影响功能与隐私约束（仍不含界面文本/输入内容/读数）。
- 处置建议：把两处枚举补成“含 `tag`、`seq` 与下列字段”，或直接引用 `DiagJsonTest` 的精确集合。

### 10.3 F3（中）六份文档仍声称“无设备 / 未在设备执行 / 修复版尚未复测”

- 现象（本轮逐行核对；qa-flash 的 t17 报告第 6 节亦列同一批）：`docs/install.md:6`、`docs/probe.md:4`、`docs/probe.md:99`、`docs/accessibility.md:144`、`docs/ui.md:107`、`docs/ui.md:141`、`docs/ui.md:143`、`docs/hook-probe-observability.md:22`、`docs/hook-probe-observability.md:23`、`docs/build.md:71`、`docs/build.md:204` 仍写“当前开发环境无设备”“未在真机上执行”“无设备/模拟器：…未测”“修复版尚未在设备上复测”。
- 与事实的冲突：项目 SDK 内已装 emulator 37.1.11.0 与 API29 官方镜像、项目 AVD 已存在，并已完成两轮真实 API29 设备会话（首轮 `t3-b19f67f`、修复版 `t17-bea37e8` 与本轮 t3）：系统授权/撤权、无障碍正反例、三层关闭、probe 生命周期与诊断文件、真实 UID 读取、两个 instrumentation 用例（`ServiceOwnershipTest`、`ConfigToggleTest`）都在设备上执行过。
- 影响：这些句子会把已完成的验证说成“未做”，使交付材料对“已实测/未实测”的标注错误（团队目标要求准确区分），也容易被误读为“回归测试没跑过”而重复劳动。它不是产品缺陷，属于证据/文档一致性问题。
- 处置建议：由文档所有者按时间线改写（“曾无设备（t2/t8/t9/t10 时期）”→“t3 首轮设备实测”→“t16 修复”→“t17 修复版复测”+“仍未验证”清单），保留历史陈述但明确标注时效；真机、Root/LSPosed、API30+、API35+ 仍必须标注为未测。
- **状态更新（本报告定稿时的观察）**：发现上述问题时（2026-09-21T02:2xZ 读取）工作树里这六份文档确实仍是旧表述；到本轮收尾时，工作树已出现**并行修复的改动**（`docs/install.md`、`docs/probe.md`、`docs/build.md`、`docs/ui.md`、`docs/accessibility.md`、`docs/hook-probe-observability.md` 均被修改，尚未提交）。抽查其方向正确：改为“已在 API29 模拟器执行 + 真机/Root/API30+ 仍未测”，并保留了 `RegistrationHoldTest`/真机未做的说明与一条“未复现的时序观察”。本报告不代替该修复任务的完成判定；最终文本与是否提交由该任务与后续审查（t12）确认。

## 11. 本轮不做的事 / 不得外推的结论

- 不把 JVM/编译/lint/打包成功表述为“设备可用”或“拦截生效”。
- 无 Root、无 LSPosed/Magisk 设备：注入、作用域、关闭恢复（H01 A1/A2）、5 秒租约的设备观测**全部未测**；JVM 用例只覆盖决策与生命周期逻辑。
- 未连接真机；模拟器传感器为模拟来源，不能冒充真机结论。
- 未在 API30～37 上运行；未验证高版本包可见性、API35+ Insets、OEM ROM、工作资料/多用户、shared/isolated UID。
- 本报告不承诺“所有应用/所有系统版本”的覆盖；单测数量不代表真实广告页覆盖。
