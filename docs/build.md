# 构建、工具链与复用命令（骨架阶段 t2）

本文件记录**本机实际使用**的工具链、来源校验方式、可复用命令，以及骨架阶段**已经实现**与**明确未实现**的范围。
所有命令都在独立仓库根目录 `/d/test/Anti-ads` 执行；Windows 下可用 `gradlew.bat` 替代 `./gradlew`。

## 1. 实际工具链（绝对路径 + 版本 + 来源校验）

| 组件 | 本机实际值 | 来源与校验 |
| --- | --- | --- |
| JDK | `/d/test/Anti-ads/.tooling/jdk17/jdk-17.0.20.1+1`（Eclipse Temurin 17.0.20.1+1+1，Windows x64） | Adoptium API 官方元数据；zip SHA-256 `e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0` 与官方 `checksum` 字段一致 |
| Gradle | Wrapper **8.9**（`gradle/wrapper/gradle-wrapper.jar` + 脚本入库） | `distributionUrl=https://services.gradle.org/distributions/gradle-8.9-bin.zip`，`distributionSha256Sum=d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab`（与官方 `.sha256` 文件一致，且与本机下载副本哈希一致） |
| Android SDK root | `/d/test/Anti-ads/.tooling/android-sdk` | commandline-tools 12.0（`commandlinetools-win-11076708_latest.zip`；Google 官方仓库索引记录 size=153583359，与本机下载字节数一致） |
| build-tools | `35.0.0` | `sdkmanager` 从官方源安装 |
| platform | `platforms;android-35`（rev 2，`android.jar` 已就位） | 同上 |
| platform-tools | `37.0.1`（`adb` 1.0.41） | 同上 |
| AGP / Kotlin / kotlinx-serialization-json | `8.7.2` / `2.1.10` / `1.7.3` | 固定于 `gradle/libs.versions.toml` |
| JUnit | `4.13.2` | 同上 |
| Xposed API | `de.robv.android.xposed:api:82`（仅 `compileOnly`） | 官方仓库 `https://api.xposed.info/`（已抓取 POM 校验存在，Apache-2.0） |

工具链二进制全部位于 `.tooling/`（已在 `.gitignore` 忽略），仓库本体不携带 JDK/SDK/Gradle 发行版。

常用可执行文件：

- JDK：`/d/test/Anti-ads/.tooling/jdk17/jdk-17.0.20.1+1/bin/java`
- Gradle wrapper：`/d/test/Anti-ads/gradlew`（`bash ./gradlew --version` 输出 Gradle 8.9 + Launcher JVM 17.0.20.1）
- sdkmanager：`/d/test/Anti-ads/.tooling/android-sdk/cmdline-tools/latest/bin/sdkmanager.bat`
- adb：`/d/test/Anti-ads/.tooling/android-sdk/platform-tools/adb.exe`

### 为什么直接跑 `bash ./gradlew ...` 不用手工设置环境变量

`gradlew` / `gradlew.bat` 顶部有一段**明确的工具链引导**（骨架任务 t2 添加，逻辑独立于官方 wrapper）：
当 `JAVA_HOME` 未设置时，自动使用仓库内 `.tooling/jdk17/jdk-*` 下第一个可用 JDK；机器上已配置 JDK 17 时该段不产生任何影响。
Android SDK 位置写在 `local.properties`（本机文件，不入库）。

重建 `local.properties`（换机或清理后）：

```bash
cd /d/test/Anti-ads
printf 'sdk.dir=%s\n' "$(pwd -W)/.tooling/android-sdk" > local.properties   # MSYS/Git Bash 下 pwd -W 给出 Windows 路径
```

重新安装 SDK 组件（缺包时）：

```bash
export JAVA_HOME=/d/test/Anti-ads/.tooling/jdk17/jdk-17.0.20.1+1
SDK=/d/test/Anti-ads/.tooling/android-sdk
yes | "$SDK/cmdline-tools/latest/bin/sdkmanager.bat" --sdk_root="$SDK" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

## 2. 可复用命令（各模块所有者通用）

```bash
# 骨架/全部模块可解析与打包（质量合同验证命令，原样执行）
bash ./gradlew --no-daemon :core:compileKotlin :accessibility:assembleDebug :hook:assembleDebug :app:assembleDebug :probe:assembleDebug

# 模块级
bash ./gradlew --no-daemon :core:test
bash ./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
bash ./gradlew --no-daemon :accessibility:testDebugUnitTest :accessibility:lintDebug :accessibility:assembleDebug
bash ./gradlew --no-daemon :hook:testDebugUnitTest :hook:lintDebug :hook:assembleDebug
bash ./gradlew --no-daemon :probe:testDebugUnitTest :probe:lintDebug :probe:assembleDebug

# 完整集成（顺序集成 t11 使用）
bash ./gradlew --no-daemon :core:test :app:testDebugUnitTest :accessibility:testDebugUnitTest :hook:testDebugUnitTest :probe:testDebugUnitTest :app:lintDebug :accessibility:lintDebug :hook:lintDebug :probe:lintDebug :app:assembleDebug :probe:assembleDebug

# 产物
#   app/build/outputs/apk/debug/app-debug.apk
#   probe/build/outputs/apk/debug/probe-debug.apk
```

设备相关（当前无真机、无 Root/LSPosed 设备；项目 AVD 按需启停，见第 5 节与[模拟器环境记录](emulator-environment.md)）：

```bash
/d/test/Anti-ads/.tooling/android-sdk/platform-tools/adb.exe devices -l
```

## 3. 当前实现状态（t11 集成后）

五条并行成果已接线为同一个应用，**没有残留在产品路径上的占位实现**：

| 模块 | 现状 |
| --- | --- |
| `:core` | 配置模型/校验/JSON 编解码、开关求交、传感器策略、保守广告规则 v1（唯一判定实现，26 例边界测试） |
| `:app` | 中文界面（首页事实分列、应用列表、应用详情）、`AppConfigRepository`（AtomicFile + revision CAS + health 四态 + observe）、`ConfigProvider`（两个只读 call、逐次 UID 鉴权）、`RuntimeReportStore`、`AntiAdsApplication` 注入 `AccessibilityDependencies` |
| `:accessibility` | `AdSkipService`（事件合并/epoch/遍历预算）、`SkipGate`+`ExecutionGuards` 执行前复核、`AccessibilityRuntime` 真实状态、服务 XML/Manifest/中文资源 |
| `:hook` | `AntiAdsHookEntry` + `assets/xposed_init` + 四条 xposed 元数据（minversion 82、建议作用域仅 probe）、单线程策略客户端、Java 分发 Hook |
| `:probe` | 真实传感器计数、摇动计数、5 个广告样例场景、诊断行与快照文件（无 INTERNET） |

跨模块接线要点（与 `docs/contracts.md` 第 9 节核对清单对应）：

- `:app` 的 `AntiAdsApplication.onCreate` 调用 `AccessibilityDependencies.install(AppConfigRepository.get(this))`；Provider 通过同一个 `get(context)` 惰性初始化，兼容 Provider 先于 Application 启动。
- 候选 → 执行前复核请求的映射集中在 `accessibility` 的 `SkipRequestBuilder`（t11 提取），服务与集成测试共用同一份逻辑。
- 最终 APK 内已核验（不是只看 AAR）：`assets/xposed_init` 为一行 `com.antiads.hook.AntiAdsHookEntry`；四条 xposed 元数据齐全且 `xposedminversion=82`；APK 内**不含** `de/robv/android/xposed` 类；`AdSkipService` + `BIND_ACCESSIBILITY_SERVICE` + 服务 XML 已合入；Provider authority 为 `com.antiads.app.config`；两个 APK 均无任何 `uses-permission`（因此无 INTERNET）。

### 3.1 历史：骨架阶段（t2，仅存档）

骨架阶段（提交 `e027e19`）曾明确列出以下**占位**，它们在 t7/t8/t9/t10 已被真实实现替换，仅作历史记录：
`ConservativeAdRuleEngine` 返回空候选；`AccessibilityDependencies`/`AccessibilityRuntime` 只保存引用并永远返回 `DISCONNECTED`+`NOT_IMPLEMENTED`；
`app`/`probe` 为占位界面；`hook` 无入口类与 `xposed_init`；`ConfigProvider` 与无障碍服务声明尚未加入。
**这些描述不代表当前代码状态**，旧的行为证据（构建日志、哈希）也仅对应当时的产物。

## 4. 验证结果（骨架合同命令）

执行时间、命令与退出码见本节；完整日志保留在 `.tooling/`（不入库）。

### 4.1 全量集成验证（t11 集成口径；QA-01～04 修复后的产物见 [修复对照](repair-qa01-04.md)）

```bash
$ cd /d/test/Anti-ads
$ bash ./gradlew --no-daemon :core:test :app:testDebugUnitTest :accessibility:testDebugUnitTest :hook:testDebugUnitTest :probe:testDebugUnitTest \
    :app:lintDebug :accessibility:lintDebug :hook:lintDebug :probe:lintDebug \
    :app:assembleDebug :probe:assembleDebug :app:assembleDebugAndroidTest :probe:assembleDebugAndroidTest
BUILD SUCCESSFUL in 2m 21s
246 actionable tasks: 32 executed, 214 up-to-date
EXIT=0
```

明细（完整日志 `.tooling/t11-verify-final.log`，不入库）：

| 项目 | 结果 |
| --- | --- |
| 五模块单元测试 | **285 例，0 失败 0 错误**：`:core` 67、`:app` 44、`:accessibility` 103（含跨模块链 11 例）、`:hook` 46、`:probe` 25 |
| 四个模块 lint | `:app` 0 error / 1 warning、`:hook` 0 error / 1 warning、`:accessibility` 与 `:probe` **No issues found** |
| 两个应用 APK | `app-debug.apk`、`probe-debug.apk`（versionName 0.1.0，minSdk 29 / targetSdk 35，无任何 `uses-permission`） |
| 两个仪器测试 APK | `app-debug-androidTest.apk`（t8 `ConfigToggleTest`）、`probe-debug-androidTest.apk`（t10 `RegistrationHoldTest`） |

产物哈希（**对应本次构建，重新构建即变化**；真机安装请重新计算并在证据中记录）：

| 产物 | 字节数 | SHA-256 |
| --- | --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 3,391,976 | `c79b04a4d001b4c6037945ae7e06dfe657288f98bf153f3b4db7d85a33233138` |
| `probe/build/outputs/apk/debug/probe-debug.apk` | 3,162,423 | `35af8de90197df511d0be8b22530f9bc59558b6fda620743e8a7f7bbb3b7ff5c` |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 2,173,141 | `d0d205ba051a62c7c838229770f0a0618ec1fbe17d005aa9975308f8c562a66d` |
| `probe/build/outputs/apk/androidTest/debug/probe-debug-androidTest.apk` | 2,172,101 | `a351fc57caa93bac8c399e848034d5fb28121c23199653457c8c51e9b5730680` |

**产物级核验（直接读最终 APK，不是只看 AAR 或中间产物）**：

- `assets/xposed_init` 内容为一行 `com.antiads.hook.AntiAdsHookEntry`；
- 四条 xposed 元数据齐全：`xposedmodule=true`、`xposeddescription`、**`xposedminversion=82`**、`xposedscope`（资源数组只列 `com.antiads.probe`）；
- APK 内**不含** `de/robv/android/xposed` 类（`compileOnly` 未被打包）；
- `com.antiads.accessibility.AdSkipService` + `BIND_ACCESSIBILITY_SERVICE` + `android.accessibilityservice` meta-data + 服务 XML 已合入；服务 XML 为 `typeWindowStateChanged|typeWindowContentChanged`、`canRetrieveWindowContent=true`、`canPerformGestures=false`、`notificationTimeout=100`、`flagRetrieveInteractiveWindows|flagReportViewIds`；
- Provider authority 为 `com.antiads.app.config`，`exported=true`、`grantUriPermissions=false`、`directBootAware=false`；
- 两个 APK 的合并 Manifest 中 `uses-permission` 条数为 **0**（即不含 INTERNET），组件计数：3 activity / 1 service / 1 provider / 5 meta-data。

两条**保留**的 lint warning 与理由（未添加任何 lint 抑制或 baseline）：

- `:app` `ExportedContentProvider`：Provider 必须 `exported=true` 才能让目标应用读取**自己**的最小策略；安全边界由每次 `call` 内的 `Binder.getCallingUid()` 现场鉴权承担（无任何写接口、不设 signature 读权限），见 `docs/contracts.md` 第 6 节。
- `:hook` `PrivateApi`：目标是非公开框架类，只能通过反射定位；形状不符即 `UNSUPPORTED` 并保持原始调用，见 `docs/hook.md` 的专门说明。

### 4.2 骨架阶段历史（t2，仅存档）

```bash
$ cd /d/test/Anti-ads
$ bash ./gradlew --no-daemon :core:compileKotlin :accessibility:assembleDebug :hook:assembleDebug :app:assembleDebug :probe:assembleDebug
BUILD SUCCESSFUL in 33s
124 actionable tasks: 124 up-to-date
EXIT=0
```

首次执行需要下载依赖：因本机到 Maven 仓库的间歇性 TLS 失败，前 3 次尝试分别在不同插件/依赖处失败，
第 4 次完整通过（`BUILD SUCCESSFUL in 3m 58s`，124 actionable tasks: 113 executed, 11 from cache）。
日志保留在 `.tooling/`（不入库）：`build-attempt-1..3.log`、`build-verify.log`（= 成功那一次）、`build-verify-clean.log`（缓存后复跑）。
**依赖缓存已就绪，此后同一条命令可稳定通过（复跑 33s，全部 up-to-date）。**

### 4.3 骨架阶段附加验证（历史，不替代合同命令）

- `bash ./gradlew --no-daemon :core:test` → `BUILD SUCCESSFUL`，EXIT=0；共 **34 个单测全部通过**
  （`ConfigCodecTest` 11、`ConfigValidatorTest` 9、`PolicyResolverTest` 5、`SensorPolicyEngineTest` 9；日志 `.tooling/core-test.log`）。
  覆盖：schema 缺失/未知、坏 JSON、字段类型错误、非法包名与拒止键、revision 0/MAX_REVISION/-1/Long.MAX_VALUE 边界、
  三层开关交集、最小策略不含其他包、租约 4999/5000/5001ms、`now=5999` 丢弃与 `now=6000` 放行、时钟回退与负数时间戳。
- 本次骨架构建的 APK（哈希仅对应这一次产物，重新构建会变化）：

  | 产物 | 字节数 | SHA-256 |
  | --- | --- | --- |
  | `app/build/outputs/apk/debug/app-debug.apk` | 3,060,865 | `7ce891f848e338b177ba7a075116d02b130185a77e519c31af75746bcb755d2b` |
  | `probe/build/outputs/apk/debug/probe-debug.apk` | 3,057,945 | `0782f183df04e1b61a5fdbb5e239919fb780f55d408af01bc279f7e19069ab4e` |

- `aapt2 dump badging`：两个 APK 均为 `minSdkVersion:'29'` / `targetSdkVersion:'35'`；合并 Manifest 中**没有任何
  `uses-permission` 条目**（因此不含 INTERNET），与需求第 1 节一致。
- 注意：以上只证明“工程可编译、接口可解析、产物可生成”。AccessibilityService 是否被系统绑定、LSPosed 是否注入、
  规则是否真的点到广告，均**未**验证。

## 5. 环境现状、实际验证范围与已知限制

本节按**时间顺序**区分四种状态，避免把不同阶段的结论混用。环境细节以 QA 的 [模拟器环境记录](emulator-environment.md) 与 [独立 QA 报告](verification.md) 为准。

### 5.1 工具与设备环境（截至 t16 修复提交）

| 项目 | 现状 |
| --- | --- |
| 项目工具链 | 仓库内 `.tooling/`：Temurin JDK 17.0.20.1+1、Gradle wrapper 8.9、Android SDK（cmdline-tools 12.0、platform-tools 37.0.1、platforms;android-35 rev2、build-tools 35.0.0） |
| 模拟器组件 | **已安装**（本项目 SDK 内、官方源）：`emulator 37.1.11.0` 与 `system-images;android-29;default;x86_64`（rev 8）；未下载任何第三方镜像 |
| AVD | **已创建**项目专用 AVD `AntiAds_QA_API29`（`ANDROID_AVD_HOME=.tooling/emulator-qa/avd`，Pixel 模板 1080×1920、swiftshader 无窗口）；未改动或删除用户 AVD |
| 曾启动 | t15 记录一次启动成功：serial `emulator-5580`、`sys.boot_completed=1`、`ro.build.version.sdk=29`（**那是当时的记录，不等于当前在线**） |
| t3 复用 | QA 以自有受管进程重新启动同一 AVD，安装固定 `b19f67f` 的五个 APK 并执行有限场景；运行区间与命令见环境记录“t3实际复用与清理”一节 |
| t17 复测 | 独立 QA（qa-flash）以自有受管 job（`bash-33`）重启同一 AVD，安装修复版 `bea37e8` 的五个 APK，完成 QA-01～04 与受影响路径回归；运行区间、异常早退的首个实例与清理见环境记录“t17 修复版复测的设备复用与清理”一节 |
| 当前实例 | **已清理**：t17 结束时按 serial 执行 `emu kill`（exit 0），随后 `adb devices` 为空（t3 的清理过程见其小节）；SDK、官方镜像、AVD 与测试 APK **保留**供后续复测 |
| 重新启动 | 按 [模拟器环境记录](emulator-environment.md) 的启动命令以受管后台任务启动，先确认 AVD 名称与 `sys.boot_completed` 再安装 APK；不要在同一端口启动第二实例 |

### 5.2 已执行的实际设备验证与结论（t3 首轮 `b19f67f`；t17 复测 `bea37e8`）

- app instrumentation **1 例通过**（`ConfigToggleTest`）；probe 的 `RegistrationHoldTest` **未执行**。
- API29 上完成有限场景：无障碍正例/反例、策略读取、故障回退取证；同时记录到**授权真值错报（QA-01）**、probe 生命周期（QA-03）与诊断快照缺失（QA-04）。
- 门禁结论为**失败／需修订**，该结论只针对 `b19f67f` 构建，**不得移用**到修复后的版本。
- 三个缺陷已由 t16 修复（见 [修复对照](repair-qa01-04.md)），且**修复版已由独立 QA 在 API29 模拟器复测通过**：QA-01 授权真值双向、QA-03 返回页按钮与“无自动重注册”、QA-04 Activity 诊断快照与 1 次/秒节流；另完成无障碍正例 4 轮/反例 3 例、三层独立关闭、真实 UID 读取与两个 instrumentation（`ConfigToggleTest`、`ServiceOwnershipTest`）。完整证据见 [独立 QA 报告（第二版）](verification.md) 与 `docs/qa-evidence/t17-bea37e8/`。**该“通过”只限 API29 模拟器范围**，不改变 5.3 的未测边界。

### 5.3 仍未验证

- **Root / LSPosed**：无 Root 设备，注入、作用域、关闭恢复（H01 A1/A2）全部未执行。
- **API30+ 包可见性、API35+ WindowInsets、Android 15/16 行为**：未测。
- **真机**：未连接任何真机；模拟器传感器是**模拟来源**，不能冒充真机传感器结论。
- **GitHub CI**：`.github/workflows/build.yml` 已覆盖五模块单测 + 四模块 lint + 两个 APK + 两个测试 APK，但**尚未在 GitHub 上执行过**（仓库尚未推送）。
- **构建成功 ≠ 服务已连接/框架已注入**：编译、单测与 lint 通过只说明工程与接口可解析。
- **未复现的时序观察（建议加测）**：开机后第一个 `ad_positive` 夹具窗口 7.4s 才 `Displayed`、6 秒观察窗内未点击；重跑即 3.07s 内点击、其后 4 轮全部点击。未复现、机制未定位——不记为缺陷也不记为通过（`docs/qa-evidence/t17-bea37e8/r2-positive-rounds.txt`）。
- **撤权后的系统侧残留（环境现象）**：产品 UI 在撤权后立即显示“未授权/未连接”，但模拟器上系统 secure `accessibility_enabled` 与 Binding 中的 DEAD 连接需重启系统才归零（`docs/qa-evidence/t17-bea37e8/r2-post-reboot-system.txt`）。
- 跨模块“纯逻辑链”（真实 core 候选 → 快照 → `SkipRequestBuilder` → `SkipGate`/`ExecutionGuards`）由 JVM 用例覆盖，但**不能**替代系统授权与真实页面实验。
- 本机到 Maven 仓库存在**间歇性 TLS 失败**（实测约 20% 请求失败），已在 `gradle.properties` 提高传输层重试与超时；依赖缓存就绪后可稳定复现。

### 5.4 历史记录（仅存档，勿当作现状）

- **t2 骨架期**：当时本机确实没有 JDK/SDK/模拟器（`adb devices -l` 为空、无 AVD），因此“未安装 emulator/镜像、无 AVD”只对**那一刻**成立；t2 的构建日志与产物哈希同样只描述当时的骨架产物。
- **t11 集成期**：当时没有可用设备，故写下“仪器测试未执行、设备项全部未测”；该结论已被 t3 的 API29 有限场景部分推进（见 5.2），但**修复后的版本仍未设备复测**。
- 5.1 中“曾启动”与“当前实例已清理”是两件事：启动成功不等于持续在线，实例清理也不等于环境被删除。
