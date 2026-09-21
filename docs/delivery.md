# 交付说明：Anti-ads（摇一摇广告防护，Android 10 / API 29+）

本文件是审查通过版本的交付材料索引：固定提交、构建与测试真实结果、APK 校验和与安装方式、真实验证范围与未测边界。
**产品代码自审查基线之后未再改动**；本轮只做文档与证据入库。

## 1. 提交与基线

| 内容 | 提交 |
| --- | --- |
| 统一集成（四模块接线、CI、中文文档） | `f1208c6` |
| QA-01/03/04 修复（产品代码） | `be2278c` |
| **产品代码基线（独立审查对象）** | **`bea37e8`**（相对 `f1208c6` 仅 docs/build.md 一行文档变化）；其后产品代码零改动 |
| 哈希表可复现性修正（QA-R3-01(F1)） | `4209bdd` |
| 设备验证状态同步（t18） | `5919a87` |
| 诊断字段/键序对齐（t18+t20+t21） | `57d92c0` |
| 第三轮验证证据与报告（t3/t17） | `0add2d3` |
| 本交付说明 | 本文件所在提交（`git log -1 --format=%H -- docs/delivery.md`） |

独立审查（t12，审查者未参与任何产品代码实现）结论：**verdict = pass**，限定范围见其结论；其中要求“t18/t20 的 docs 提交必须在组装/推送前落地”，已由 `5919a87`、`57d92c0` 满足。

## 2. 构建与测试（真实退出码）

工具链（仓库内 `.tooling/`，不入库）：Temurin JDK 17.0.20.1+1、Gradle wrapper 8.9、Android SDK（build-tools 35.0.0、platforms;android-35、platform-tools 37.0.1）、AGP 8.7.2 / Kotlin 2.1.10、compileSdk/targetSdk 35、minSdk 29。

```bash
# 全量约定验证（独立 QA 以 --rerun-tasks --no-build-cache 强制重跑）
bash ./gradlew --no-daemon :core:test :app:testDebugUnitTest :accessibility:testDebugUnitTest \
  :hook:testDebugUnitTest :probe:testDebugUnitTest \
  :app:lintDebug :accessibility:lintDebug :hook:lintDebug :probe:lintDebug \
  :app:assembleDebug :probe:assembleDebug :app:assembleDebugAndroidTest :probe:assembleDebugAndroidTest \
  --rerun-tasks --no-build-cache --max-workers=2
```

| 项目 | 实际结果 |
| --- | --- |
| 全量命令 | **退出码 0**（t17：BUILD SUCCESSFUL，7m16s，246 tasks 全部 executed；t3 第三轮同样 exit 0） |
| 五模块 JVM 单测 | **298 例，0 失败 0 错误**（core 67、app 46、accessibility 103、hook 46、probe 36） |
| lint | **0 error**：`:app` 1 warning（`ExportedContentProvider`＝合同要求导出且逐次鉴权）、`:hook` 1 warning（`PrivateApi`＝反射定位隐藏类，形状不符即 UNSUPPORTED 放行）、`:accessibility`/`:probe` No issues found；无 lint 抑制/baseline |
| 夹具（QA 提供） | `bash ./gradlew --no-daemon -p qa/fixtures/noqueries assembleDebug lintDebug` → **退出码 0**、strict lint 0 issue |
| 仪器测试 APK | 两个测试 APK 组装成功；设备上已执行 2 例（`ConfigToggleTest`、`ServiceOwnershipTest`） |

日志与原始统计：`docs/qa-evidence/t17-bea37e8/`（`r2-gradle-full.txt`、`r2-jvm-results.json`、`r2-lint-summary.json`）与 `docs/qa-evidence/t3-be2278c/`（`gradle-rerun.txt`、`jvm-results.json`、`lint/`）。

## 3. 交付 APK（本地文件，未入库）

| 产物 | 字节数 | SHA-256 | 说明 |
| --- | --- | --- | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 3,392,024 | `a08a04b94462a09ec740f26988732362a40a20b9b69d50bb8797ce368857f8f5` | 管理端，同时是 LSPosed 模块（index 见 `docs/install.md`/README） |
| `probe/build/outputs/apk/debug/probe-debug.apk` | 3,169,147 | `0a75edd07c17ade8cd86baef87f27adb74e6211706074ae202a26bad95e73896` | 独立诊断 APK（无 INTERNET） |
| `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk` | 2,174,433 | `9b72ae61121fa0fe400d13e7b57f04da32217c2866ec86a061aab4a6e9e8c153` | 仪器测试 APK |
| `probe/build/outputs/apk/androidTest/debug/probe-debug-androidTest.apk` | 2,172,101 | `a351fc57caa93bac8c399e848034d5fb28121c23199653457c8c51e9b5730680` | 仪器测试 APK |
| `qa/fixtures/noqueries/build/outputs/apk/debug/noqueries-debug.apk` | 见来源 | `533cbe72ffa12dd1c276aad1f43aafd9f71d086005859be7b16a96d5479e611a` | QA 无 queries 夹具（跨应用读取负例） |

- **可复现且经设备逐字节核对**：来源 `docs/qa-evidence/t17-bea37e8/installed-apks.json`（`sha256` 与设备内 `installedSha256` 相同）与 `docs/qa-evidence/t3-be2278c/apk-sha256.txt`。
- 两个应用 APK 均为 **debug 签名**，不是生产发布包；**重新构建会改变字节**，任何新安装都应重新计算并记录哈希（见 `docs/repair-qa01-04.md` §3 的不可复现说明）。
- 安装：`adb install -r app/build/outputs/apk/debug/app-debug.apk`、`adb install -r probe/build/outputs/apk/debug/probe-debug.apk`（分别执行）。

## 4. 安装 / 开启 / 关闭 / 恢复 / 卸载

完整步骤与常见问题见 **[docs/install.md](install.md)**，隐私说明见 **[docs/privacy.md](privacy.md)**。要点：

- **免 Root 模式**：系统设置中手动启用本应用的无障碍服务；首页分列“系统设置授权”与“服务实际已连接”两个事实，只有后者为真才会尝试点击；每应用需单独打开开关，默认全关。
- **增强模式（Root/LSPosed）**：需已有兼容框架 → 激活模块 → 勾选作用域（建议先只勾 `com.antiads.probe`）→ **停止并重新打开目标应用**；仅安装/仅授权/仅开开关都不等于生效，管理端以“进程自报”状态展示并可显示 `UNSUPPORTED`。
- **关闭与恢复**：总开关/模式开关/每包开关关闭后，无障碍侧立即取消排队并在点击前重读配置；Hook 侧约 2–3 秒（2s 刷新 + 1s 读取期限）生效，**最迟 5 秒租约到期后一律放行未来回调**；撤权立即断开；完全移除注入需重启目标进程（卸载同理）。
- **卸载**：先关闭开关，再卸载；配置随私有目录删除，注入随目标进程重启消失。

## 5. 真实验证范围（不得外推）

**已在项目 API29 模拟器上实测**（AVD `AntiAds_QA_API29`／serial `emulator-5580`，Android 10、API 29、x86_64、安全补丁 2019-09-05；**模拟器传感器是模拟来源**）：

- 安装与冷启动、中文界面、配置保存与重启恢复、损坏配置回退；
- 通过系统 UI 授权 → 返回首页显示“已授权且已连接” → **撤权**后准确回落（QA-01 双向）；
- probe 开始采样 → HOME → 返回后按钮/状态正确、显式再次开始产生**新的注册**（无自动重注册）、诊断快照 `files/probe-diag.json` 可解析且节流最小间隔约 1s（QA-03/04）；
- 无障碍正例多轮（含重启后）与关键反例（无广告上下文、不可点击、按钮在下部、含输入框）零误触；
- 三层开关（总开关/模式/每包）**各自独立**关闭均停止动作；
- Provider 真实 UID 只读策略读取成功、shell(UID 2000) 全被拒绝、无任何写入口；
- 仪器测试 2 例通过；夹具（无 queries）跨应用读取按预期失败与放行。

**未测（不得由本交付推断）**：Root/LSPosed 的真实注入、作用域、首次注入与关闭恢复（H01 A1/A2）；Hook 侧 5 秒租约的**设备观测**（仅 JVM 边界已覆盖）；API 30+/33+/35+（包可见性、受限设置、WindowInsets）；真机与 OEM 机型；GitHub Actions 实际运行；native/`SensorDirectChannel`、共享 UID、isolated 进程、工作资料。

**明确不承诺**：覆盖所有应用、所有系统版本或所有广告形式；也不承诺一定能在广告跳转前处理。上述限制同时写在 README、`docs/requirements.md` 与 `docs/verification.md`。

## 6. 独立仓库包含与排除

- **包含**：五模块源码（`app/`、`core/`、`accessibility/`、`hook/`、`probe/`）、`gradle/` 目录与 `gradlew`/`gradlew.bat`（含官方 wrapper JAR）、`settings.gradle.kts`/`build.gradle.kts`/`gradle.properties`、`.github/workflows/build.yml`、全部中文文档（`docs/`，含 `docs/qa-evidence/` 设备证据）、QA 夹具 `qa/fixtures/noqueries/`、`README.md`、`LICENSE`(MIT)、`.gitignore`/`.gitattributes`。
- **排除（`.gitignore`）**：`.tooling/`（JDK/SDK/模拟器/缓存）、`.agent-teams/`（团队状态）、`local.properties`（本机 SDK 路径）、签名密钥与 `keystore.properties`、`build/` 与 APK、Gradle/Kotlin 缓存；父目录 `D:/test` 的任何内容都不在仓库内。
- 远端仓库与推送**尚未执行**（本任务只准备本地交付材料，由后续任务用 `gh` 建仓并统一推送）。

## 7. 本地可下载的交付文件

- APK：`app/build/outputs/apk/debug/app-debug.apk`、`probe/build/outputs/apk/debug/probe-debug.apk`（校验和见第 3 节）
- 主要文档：`README.md`、`docs/install.md`、`docs/privacy.md`、`docs/verification.md`、`docs/delivery.md`（本文件）、`docs/repair-qa01-04.md`、`docs/build.md`
