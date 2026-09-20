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

设备相关（当前无设备，见第 5 节）：

```bash
/d/test/Anti-ads/.tooling/android-sdk/platform-tools/adb.exe devices -l
```

## 3. 骨架阶段的实现状态（不得当作产品能力证据）

**已按冻结合同实现（core 纯函数，含单测）**

- `ConfigValidator`：schema 版本、revision 边界、包名语法/拒止键、传感器类型子集、规则 ID 白名单、包数量上限。
- `ConfigCodec`：`encodeDefaults=true`/`ignoreUnknownKeys=true`；**缺失或非 1 的 `schemaVersion` 一律拒绝**；
  未知枚举、错误类型、无效包名、越界数据、超过 256 KiB 的 payload 抛 `IllegalArgumentException`。
- `PolicyResolver`：总开关 ∧ 全局模式 ∧ 每包模式 的交集；关闭时 `hookEnabled=false` 且清空类型集合，保留真实 revision。
- `SensorPolicyEngine`：租约 1..5000ms、`now >= expires` 放行、包名/schema/时钟回退一律放行（reason 为稳定英文码）。

**显式占位（未实现，禁止据此宣称已保护）**

- `ConservativeAdRuleEngine`：当前**一律返回空候选**（不点击）；规则实现与几何/时间边界单测属 t7。
- `AccessibilityDependencies` / `AccessibilityRuntime`：只保存引用 / 永远返回 `DISCONNECTED` + `lastErrorCode=NOT_IMPLEMENTED`（t9 实现真实服务状态）。
- `app` 的 `AntiAdsApplication`、`MainActivity` 与 `probe` 的 `MainActivity`：占位界面，明确写明不代表已具备防护能力（t8/t10 实现）。
- `hook` 模块：无入口类、无 `assets/xposed_init`、无 xposed 元数据（t10 实现）；`:app` 仍不引用 `AntiAdsHookEntry`。
- app 的 `ConfigProvider`/`RuntimeReportStore` 与无障碍服务声明尚未加入（分别属 t8、t9）。

## 4. 验证结果（骨架合同命令）

执行时间、命令与退出码见本节；完整日志保留在 `.tooling/`（不入库）。

### 4.1 合同验证命令（原样执行）

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

### 4.2 附加验证（不替代合同命令）

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

## 5. 尚未验证 / 已知限制

- **设备项全部未测**：`adb devices -l` 输出为空（无真机/USB 调试设备）；未安装 `emulator` 包与系统镜像，**无 AVD**。
  安装/启动、无障碍正反例、Hook 注入、传感器对照等所有真机结论必须由具备设备的任务补充，不得用编译或单测结果代替。
- **构建成功 ≠ 服务已连接/框架已注入**：编译通过只说明工程与接口可解析。
- 本机到 Maven 仓库存在**间歇性 TLS 失败**（实测约 20% 请求失败），已在 `gradle.properties` 提高传输层重试与超时；
  首次解析依赖可能需要重跑，依赖成功缓存后 (`~/.gradle/caches`) 可稳定复现。
- `.github/workflows/build.yml` 为骨架 CI 配置，**尚未在 GitHub 上执行过**（仓库未创建）。
- 本地未运行 `lint`/`androidTest`（`assembleDebugAndroidTest` 由顺序集成 t11 统一执行）。
- 无障碍、Hook、probe 的实机行为与 PR 中的任何设备结论，均以 QA 独立证据为准。
