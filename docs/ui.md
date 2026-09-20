# Anti-ads 管理界面与配置服务（:app，t8 交付）

本文件描述 `app/` 的界面结构、状态呈现规则、配置持久化、跨进程配置服务，以及本任务**实际做过**和**没有做过**的验证。
接口以 `docs/contracts.md` 为准，本文件不引入新的公共接口。

## 1. 文件与职责

| 文件 | 职责 |
| --- | --- |
| `app/src/main/kotlin/com/antiads/app/AntiAdsApplication.kt` | `AccessibilityDependencies.install(AppConfigRepository.get(this))`；Provider 先于 Application 启动时仍用同一惰性单例 |
| `app/src/main/kotlin/com/antiads/app/ManagedActivity.kt` | 管理界面基类：共享仓储单例；落盘写入放到后台单线程，主线程只渲染快照 |
| `app/src/main/kotlin/com/antiads/app/MainActivity.kt` | 中文首页：总开关、两种模式开关、状态事实、权限入口、边界与停用恢复说明 |
| `app/src/main/kotlin/com/antiads/app/ui/PackageListActivity.kt` | 应用选择：已配置应用、可启动应用（LAUNCHER 查询）、按包名手工添加、包可见性说明 |
| `app/src/main/kotlin/com/antiads/app/ui/PackageDetailActivity.kt` | 每应用配置：免 Root 跳过开关、增强模式开关、5 种传感器类型、影响提示、移除配置 |
| `app/src/main/kotlin/com/antiads/app/ui/StatusFacts.kt` | 纯逻辑事实映射（15 秒过期判定、报告排序、授权/连接分列），可被 JVM 单测直接验证 |
| `app/src/main/kotlin/com/antiads/app/ui/UiInsets.kt` | targetSdk 35 系统栏适配（API30+ 自行处理 systemBars；API29 交系统默认行为） |
| `app/src/main/kotlin/com/antiads/app/config/ConfigStore.kt` | internal 落盘抽象 + `AtomicFileConfigStore`（`filesDir/protection-config-v1.json`） |
| `app/src/main/kotlin/com/antiads/app/config/AppConfigRepository.kt` | `ConfigRepository` 实现：revision 比较、AtomicFile 原子替换、health、observe |
| `app/src/main/kotlin/com/antiads/app/config/ConfigProvider.kt` | 受控跨进程配置服务（只读 `call`；非 call 入口一律拒绝） |
| `app/src/main/kotlin/com/antiads/app/config/ProviderCallRouter.kt` | 鉴权与协议判定（纯逻辑 + 依赖注入，可 JVM 单测） |
| `app/src/main/kotlin/com/antiads/app/config/RateLimiter.kt` | 每 UID 滑动窗口限流（策略读取 ≤5 次/秒，报告 ≤1 次/秒） |
| `app/src/main/kotlin/com/antiads/app/config/RuntimeReportStore.kt` | 报告内存存储（每包 8 进程、全局 128 条、LRU，重启不还原“在线”） |
| `app/src/main/res/values/strings.xml` | 全部中文文案（界面无硬编码文本） |
| `app/src/androidTest/kotlin/com/antiads/app/ConfigToggleTest.kt` | 宿主 UID 真实写路径仪器测试（QA H01 备用路径 A2 的外部驱动） |

## 2. 状态呈现规则（不合成“已全面保护”）

首页把下列事实分开显示，任何一项都不由其他项推导：

| 事实 | 证据来源 | 文案要点 |
| --- | --- | --- |
| 配置已保存 | `ConfigWriteResult.Saved` + revision | “已保存配置，版本 N” |
| 配置文件状态 | `ConfigRepository.health()` 四态 | 正常 / 尚无文件 / 损坏回退 / 读取失败（附稳定错误码） |
| 系统设置已授权无障碍 | `AccessibilityManager.getEnabledAccessibilityServiceList()` 中匹配 `com.antiads.accessibility.AdSkipService` | “系统设置授权：是/否” |
| 服务实际已连接 | `AccessibilityRuntime.state().connected` | “服务实际已连接：是/否”（与上一行独立） |
| 运行阶段 | `state().phase` | 未连接 / 无目标 / 正在观察 / 已连接但防护关闭 / 错误 |
| 最近一次跳过 | `state().lastAction` | “系统已接受点击动作（不代表广告已消失）” |
| 增强模式报告 | `RuntimeReportStore.snapshot()` | 逐条标注“进程自报”；>15000ms 显示“报告已过期” |
| 无报告 | 报告列表为空 | “未观察到注入/尚未报告：可能未激活模块、未加入作用域、目标进程未重启…” |

- 界面不会因为开关打开、模块已安装或报告存在就显示“已拦截/已保护”。
- “报告已过期”只表示该进程自报记录超过 15 秒未更新，不解释为“一定没有注入”（目标可能本来没有传感器回调）。
- 报告的 installState/transportState/计数均为目标进程自报，既不是授权依据，也不是“框架已生效”的证明。
- `onPause` 停止 1 秒定时器，`onStop` 关闭订阅；`onResume` 重新核查系统授权并刷新全部事实（覆盖“从系统设置返回后刷新”）。
- 无障碍的 `lastErrorCode` 原样显示稳定错误码，不做“成功”之类的改写；该字段由 :accessibility 的真实服务状态产生（骨架期的占位常量已不存在，界面不再依赖它）。

## 3. 应用选择与每应用配置

- 默认查询 LAUNCHER 应用（依赖 Manifest 的 `<queries><intent MAIN/LAUNCHER></queries>`），不申请 `QUERY_ALL_PACKAGES`。
- 列表可能不完整时给出中文解释并提供“按包名添加”；包名合法性用 core 的 `ConfigValidator.isValidPackageName` 校验，`android`/`com.android.systemui`/`com.antiads.app` 一律拒绝。
- 新建的每应用配置保持两个模式开关为 false，传感器集合使用 core 默认集合，规则 ID 为 `builtin.conservative.v1`。
- 每包页面列出 5 种可控类型（加速度计、陀螺仪、重力、线性加速度、旋转矢量）与影响提示（运动/导航/游戏/计步可能受影响；全部取消=不拦截）。
- 页面显式提示“保存成功只代表配置已持久化，不代表已经拦截”，并在总开关/全局开关未开启时提示该配置尚未生效。
- 移除某应用配置后，目标进程在租约到期内回到放行（无配置项仍返回 `hookEnabled=false` 的最小策略）。

## 4. 权限与入口引导

- 打开系统无障碍设置 → `Settings.ACTION_ACCESSIBILITY_SETTINGS`；入口缺失时给中文说明，不假装应用内可一键授权。
- 打开框架管理器 → 依次尝试 `org.lsposed.manager`、`de.robv.android.xposed.installer` 的启动入口；都没有时提示在管理器内激活模块并勾选作用域。
- 打开诊断应用 → `com.antiads.probe` 的启动入口；未安装时提示安装 probe-debug.apk。
- 应用不申请 INTERNET、不申请无关权限，未加入任何广告/统计 SDK。

## 5. 配置持久化（AppConfigRepository）

- 单例：`AppConfigRepository.get(context)`，与 Application、Provider 共用（Provider 先启动也能正确初始化）。
- `snapshot()/health()` 线程安全、非阻塞、纯内存（`@Volatile` 字段 + 写锁内发布）。
- `write(config, expectedRevision)` 在串行锁内依次判定：
  1. `expectedRevision != 当前 revision` → `Rejected(CONFLICT)`
  2. 输入 `config.revision != expectedRevision` → `Rejected(INVALID)`
  3. `expectedRevision == MAX_REVISION`（Long.MAX_VALUE-1）→ `Rejected(REVISION_EXHAUSTED)`
  4. core 校验失败 → `Rejected(INVALID)`
  5. AtomicFile 写入失败 → `Rejected(IO_ERROR)`
  只有第 6 步落盘成功才发布新快照（`revision = expectedRevision + 1`）并返回 `Saved`；失败时内存保持上一次成功配置，不假称已保存。
- 坏文件/未知版本/读取失败 → 全部关闭 + `RECOVERED_CORRUPT`/`IO_ERROR`，不沿用损坏配置的任何 enable 值；错误码只保留稳定英文码形式，不把文件内容带进界面或日志。显式保存可替换坏文件并回到 `READY`。
- `observe`：注册立即回调一次当前快照；`close` 幂等；锁外回调；listener 抛错被隔离，不影响已落盘事务。
- 构造器为 `internal`，仅供同模块单测注入内存 `ConfigStore`；模块外仍只有 `get(context)` 入口（不新增导出测试接口）。

## 6. 跨进程配置服务（ConfigProvider）

- authority `com.antiads.app.config`，`exported=true`、`grantUriPermissions=false`、`directBootAware=false`，不设置会阻断目标读取的 signature 读权限——鉴权在每次 `call` 内执行。
- `call` 入口先捕获 `Binder.getCallingUid()/getCallingPid()`；实现中不存在 `clearCallingIdentity`，授权始终基于捕获到的调用身份。
- 鉴权顺序：同用户 → 应用 UID → `getPackagesForUid` 枚举真实包集合 → 单一包且与 arg 完全相等 → 合法且非拒绝包名；失败码 `UNAUTHORIZED`、`SHARED_UID_UNSUPPORTED`、`ISOLATED_OR_UNKNOWN_UID`。
- isolated UID 只用公开 API 处理：`UserHandle.isIsolated` 不是公开接口、`Process.isIsolatedUid` 是隐藏接口，因此生产路径由 `getPackagesForUid` 空集合得到 `ISOLATED_OR_UNKNOWN_UID`；`ProviderCallRouter` 仍保留可注入的 isolated 判定，供单测覆盖该分支。
- 方法白名单只有 `get_policy_v1` 与 `report_hook_v1`；其余（含 query/insert/update/delete/openFile/bulkInsert 这类名字）一律 `INVALID_REQUEST`，没有 `get_all_config`/`write_config` 等远程写接口。
- `get_policy_v1` 只返回该已验证包的最小 `PackagePolicy`（无其他包信息），JSON ≤4096B；`report_hook_v1` 校验包名、用捕获 pid 覆盖自报 pid，只写内存报告存储，不写配置、不改授权。
- 限流：每 UID 策略读取 ≤5 次/秒、报告 ≤1 次/秒，超限返回 `RATE_LIMITED`；报告存储每包 8 进程、全局 128 条，LRU 淘汰，只存内存。
- 非 call 入口：`query`/`getType` 返回 null（不返回数据），insert/update/delete/bulkInsert/openFile/openAssetFile 抛错拒绝（策略集中在可单测的 `ProviderEntryPolicy`）。

## 7. 测试与验证记录

### 7.1 纯 JVM 单测（`:app:testDebugUnitTest`）

| 测试类 | 覆盖点 |
| --- | --- |
| `AppConfigRepositoryTest` | 无文件默认全关；坏 JSON/未知 schema 回退且不沿用 enable 值；读取失败 IO_ERROR；写入 Saved+revision+1+磁盘 JSON 可解码；CONFLICT / INVALID（revision 不符、拒绝包名、越界传感器类型）/ IO_ERROR / REVISION_EXHAUSTED；显式保存替换坏文件；observe 首帧 + close 幂等 + listener 异常隔离；包配置快照不被调用方后续修改影响 |
| `ProviderCallRouterTest` | 单包匹配返回本包最小策略且不泄漏他包；未配置包返回关闭策略；arg 伪造、shared UID 多包、无包/isolated、跨用户、非应用 UID、拒绝包名全部拒绝；未知方法与全部禁用方法名 INVALID_REQUEST；每 UID 限流（策略 5/秒、报告 1/秒）；报告包名不符 UNAUTHORIZED；报告 pid 被捕获 pid 覆盖且时间戳用服务端时钟；超长 payload、未知 schema、计数非法、非正 pid 均拒绝 |
| `RateLimiterTest` | 窗口边界（999ms 仍限流、1000ms 放行）、UID 隔离、时钟回退保守限流、跟踪表有界 |
| `RuntimeReportStoreTest` | 每包 8 进程上限、全局 128 条上限、按接收时间从新到旧、同 token 替换 |
| `ProviderEntryPolicyTest` | 只有 query/getType 返回 null，其余入口全部抛错；call 方法白名单只有两个只读方法 |
| `StatusFactsTest` | 15 秒过期边界（15000/15001）、报告排序、系统授权与“实际已连接”独立成事实、最近动作年龄、服务身份精确匹配 |

### 7.2 仪器测试（需设备；test APK 由 t11 组装）

`app/src/androidTest/kotlin/com/antiads/app/ConfigToggleTest.kt`：宿主 UID、真实仓储写路径，不启动 Activity、不使用 exported 写接口。

    adb shell am instrument -w -e action master_off com.antiads.app.test/androidx.test.runner.AndroidJUnitRunner
    adb shell am instrument -w -e class com.antiads.app.ConfigToggleTest -e action master_on com.antiads.app.test/androidx.test.runner.AndroidJUnitRunner

判读：logcat/状态输出 `antiads_config_toggle action=… result=SAVED|ALREADY_AT_TARGET|REJECTED_<reason> revision_before=… revision_after=… flipped=… elapsed_ms=… app_uid=… elapsed_realtime_ms=…`；只有 `SAVED` 且 `flipped=true` 才能作为 H01 A2 的真实状态变化证据。

### 7.3 本任务实际执行结果

合同命令：`bash ./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → **exit 0（BUILD SUCCESSFUL）**

| 项目 | 实测结果 |
| --- | --- |
| 单元测试 | 44 个用例，0 失败 / 0 错误（AppConfigRepositoryTest 15、ProviderCallRouterTest 14、StatusFactsTest 5、RateLimiterTest 4、RuntimeReportStoreTest 4、ProviderEntryPolicyTest 2） |
| lint | 0 errors，2 warnings：`ExportedContentProvider`（合同第 6 节要求 Provider 导出且只读，鉴权在 call 内逐次执行）、`DataExtractionRules`（已同时提供 allowBackup=false 与 dataExtractionRules，仅提示可再补 fullBackupContent） |
| 组装 | `app/build/outputs/apk/debug/app-debug.apk`（3.4 MB，SHA-256 b80380f06d0f61550fc32d62e0cdd0f9184f5e6f624d9b63c88d504d22e97352） |
| androidTest 编译 | 附加执行 `:app:assembleDebugAndroidTest` 通过，生成 app-debug-androidTest.apk（SHA-256 d0d205ba051a62c7c838229770f0a0618ec1fbe17d005aa9975308f8c562a66d），证明 ConfigToggleTest 对真实实现签名编译通过 |
| APK Manifest 核对 | aapt2 dump：包名 com.antiads.app；**无任何 uses-permission**（无 INTERNET）；Provider `com.antiads.app.config` exported=true；MainActivity exported=true；PackageListActivity / PackageDetailActivity exported=false |

集成期失败与修复（均在本任务范围内处理）：

1. 首次构建被 `:hook:compileDebugKotlin` 阻断（`AndroidAppHelper` 未解析）。核对 Xposed API 82 jar 后确认该类位于 `android.app`，已定位并通知 hook-engineer 修复，其模块随后编译通过与 t8 无关。
2. 本模块 4 个编译错误：`AccessibilityServiceInfo` 包名应为 `android.accessibilityservice`；骨架期常量 `AccessibilityRuntime.ERROR_NOT_IMPLEMENTED` 已被 t9 实现移除（界面改为直接展示稳定错误码）；`UserHandle.isIsolated` 不是公开 API（改为由 `getPackagesForUid` 空集合判定 isolated/未知 UID，并保留可注入判定供单测）。
3. 3 个单测断言假设错误：IO_ERROR 用例漏传 `revision`（被判定 INVALID）、报告坏 payload 用例未推进限流时钟（被判定 RATE_LIMITED）、报告存储全局上限用例每包只放 1 条才能触发 128 条上限。

## 8. 自检记录与限制

| 项目 | 结论 | 依据 |
| --- | --- | --- |
| 中文界面具备总开关、两种模式、应用选择、权限/状态引导、停用恢复说明 | 通过（静态 + 构建） | MainActivity / PackageListActivity / PackageDetailActivity + strings.xml 全量中文文案；lint 与 assembleDebug 结果见 7.3 |
| 导出组件禁止未授权写入与不必要数据暴露 | 通过 | ConfigProvider 只读 call + 非 call 入口全部拒绝；ProviderCallRouterTest / ProviderEntryPolicyTest |
| 配置可持久保存并被其他模块使用 | 通过 | Application 安装仓储到 AccessibilityDependencies；Provider 从同一单例生成最小策略 |
| 系统设置返回后刷新服务状态 | 通过（静态） | onResume 重新核查 enabledAccessibilityServiceList、刷新事实并重建订阅 |
| 包可见性（Android 11+） | 通过（静态） | 只用 LAUNCHER queries + probe 明确包查询；列表不完整时中文解释 + 手工添加入口 |
| 交互与视觉真机验证 | **未做** | 本任务环境无设备/模拟器：未做点击、渲染、无障碍授权流程、系统设置返回、WindowInsets 视觉效果、包可见性列表实测 |
| Provider 真实身份捕获与跨进程成功读 | **未做** | 需设备上由真实包 UID 发起：`adb shell content call` 只能证明 shell UID 被拒；同 UID 成功读需 probe 进程（t10/t13）自己调用 |
| Android 15 边到边 / API29 最低版本行为 | **未做** | 无设备；UiInsets 只做 API 分支处理，未在真机确认 |

## 9. 交接与集成检查点（给 t11 及后续）

1. 共享构建文件已由骨架补齐 testInstrumentationRunner 与 androidTest runner1.6.2/ext:junit1.2.1；本模块未修改 gradle/、根 build.gradle.kts、settings.gradle.kts。
2. 集成需核查：Provider 与 AntiAdsApplication 初始化时序；无障碍服务声明由 :accessibility 合入后首页“系统授权”匹配的类名（t9 落地后复核 `com.antiads.accessibility.AdSkipService`）；:hook 入口资产合入后 app APK 内模块元数据存在。
3. QA 设备证据：H01 A2 用 ConfigToggleTest 制造真实状态变化；策略读取用 probe 进程调用 get_policy_v1；负例用 `adb shell content call`（shell UID 必须被拒）。
4. 未做真机交互实测的部分必须原样披露，不得用本文件的静态检查替代设备结论。
