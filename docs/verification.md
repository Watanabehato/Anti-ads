# Anti-ads 独立 QA 验证报告（第二版：修复后复测）

Run：`t17-bea37e8`；QA：qa-flash（接替因外部 API 错误中断的 QA 会话，同一 t17 任务）；日期：2026-09-21。
固定源码：`bea37e8086276dd5475524b81bddf55ab17149e5`（= 修复提交 `be2278c` + 仅文档提交 `bea37e8`，差异只有 `docs/build.md` 与 `docs/repair-qa01-04.md`）。
被复测对象：首轮报告 [QA-01～04](qa-evidence/history/t3-b19f67f-verification.md)（对象 `b19f67f`，门禁**失败／需修订**）与 [修复对照](repair-qa01-04.md)（`be2278c`）。
证据目录：[docs/qa-evidence/t17-bea37e8/](qa-evidence/t17-bea37e8/)；机器可读清单：[run-manifest.json](qa-evidence/t17-bea37e8/run-manifest.json)；设备命令与结果：[r2-device-commands-and-outcomes.md](qa-evidence/t17-bea37e8/r2-device-commands-and-outcomes.md)。

**门禁结论：通过（限定范围）。** 在固定修复版 APK 上的 API29 模拟器实测中，QA-01（授权真值）、QA-03（返回页按钮/不自动重注册）、QA-04（Activity 写诊断快照与节流）三项均在设备上复现为**已修复**；QA-02 属文档问题，`docs/build.md` 第 5 节已按其要求重写，本轮另发现**其他文档仍有过期“无设备”表述**（见第 6 节，越界未改）。受影响路径（无障碍正例/反例、三层独立关闭、真实 UID 只读、app instrumentation）本轮全部回归通过。
未测项（不得当作通过）：Root/LSPosed 注入与作用域、API30+ 包可见性、API35+ WindowInsets、普通/Root 真机（模拟器传感器为模拟来源）、GitHub CI 实际运行、probe 的 `RegistrationHoldTest`（H01 A1 同次注册恢复）。另有一条**未复现的冷启动时序观察**与一条系统侧残留现象，均如实记录、不包装成通过，交 Plan 判断是否加测（见 4.6）。

## 0. 本报告与前一份报告的关系（原证据未被改写）

- 首轮报告（`b19f67f`，qa-release）原件已作为历史副本保留在 `docs/qa-evidence/history/t3-b19f67f-verification.md`，与替换前的 `docs/verification.md` **逐字节相同**（两者 SHA-256 均为 `3fc901f168bd3d9f8fad5ac1efd3145509a89a197d1b8a3df4373a9881ce937f`，记录于 `history-baseline.txt`）。本文件是同一路径上的**新版报告**，不覆盖、不改写首轮内容。
- 首轮证据目录 `docs/qa-evidence/t3-b19f67f/`（132 个文件）与其 ZIP 均未改写：ZIP 现测 SHA-256 `c2679c212887965bd4be4b43a74d3f2d79665ca9a675004d9466e72f51f6861c`，与 `history-baseline.txt` 记录一致。
- 首轮环境文档副本保留在 `docs/qa-evidence/history/t3-emulator-environment.md`；现行 `docs/emulator-environment.md` 只追加 t17 一节（t3 一节保持原文）。
- **不复用 `b19f67f` 的任何设备通过结论**：本轮结论只对应下文列出的修复版 commit 与 APK 哈希。首轮报告中的 `c79b04a4…`（app）与 `35af8de9…`（probe）不适用于本版。

## 1. 结论摘要

| 编号 | 首轮现象 | 本轮状态 | 关键证据 |
| --- | --- | --- | --- |
| QA-01（高） | 系统已授权、服务已连接，但首页“系统设置授权”恒为“否” | **通过（设备实测，双向）**：授权后返回显示“是/是”，撤权后返回显示“否/否”，与系统只读值一致 | `r2-qa01-regranted-home.png`、`r2-qa01-revoked-home.png`、`r2-qa01-regrant-secure.txt`、`r2-qa01-final-revoked-system.txt` |
| QA-02（中） | `docs/build.md` 现状表述与实际环境不符 | **文档项：build.md 已修正**；本轮复查发现**其余文档仍有过期“无设备”表述**，已在第 6 节逐条列出并交 Plan（越界未改） | 第 6 节清单 |
| QA-03（中） | HOME 返回后开始按钮禁用、周期刷新未重建 | **通过（设备实测）**：返回后“开始采样”可用、“停止采样”禁用、提示“不会自动重启”；显式点击才开始且为**新注册**（registrationSeq 6→12，同 sessionId） | `r2-qa03-returned.png`、`r2-qa03-restarted.png` |
| QA-04（中） | debuggable 变体不写 `files/probe-diag.json` | **通过（设备实测）**：Activity 路径实际写入可解析快照；节流最小间隔 1003ms、无一次 <1000ms；暂停态有 force 写入；schema 字段与 `docs/probe.md` 一致 | `r2-qa04-throttle-summary.json`、`r2-qa04-paused.json`、`probe-diag-pulled.json` |

## 2. 固定版本与可追溯的构建/测试/lint 证据

### 2.1 源码与工作树

- `git rev-parse HEAD` = `bea37e8086276dd5475524b81bddf55ab17149e5`；`git status --porcelain` 只有 ` M docs/emulator-environment.md`、`?? docs/qa-evidence/`、`?? docs/verification.md`；**未改任何产品/测试代码**（`app/`、`core/`、`accessibility/`、`hook/`、`probe/`、`qa/fixtures/noqueries/`、`gradle/`、`settings.gradle.kts`、`build.gradle.kts` 均未改动）。

### 2.2 完整命令、真实退出码与统计

```bash
bash ./gradlew --no-daemon :core:test :app:testDebugUnitTest :accessibility:testDebugUnitTest :hook:testDebugUnitTest :probe:testDebugUnitTest \
  :app:lintDebug :accessibility:lintDebug :hook:lintDebug :probe:lintDebug \
  :app:assembleDebug :probe:assembleDebug :app:assembleDebugAndroidTest :probe:assembleDebugAndroidTest \
  --rerun-tasks --no-build-cache --max-workers=2
# BUILD SUCCESSFUL in 7m 16s；246 actionable tasks: 246 executed；GRADLE_EXIT=0

bash ./gradlew --no-daemon -p qa/fixtures/noqueries assembleDebug lintDebug --rerun-tasks --no-build-cache --max-workers=2
# BUILD SUCCESSFUL in 54s；43 actionable tasks: 43 executed；GRADLE_EXIT=0
```

两条命令均由本轮 QA 原样执行并显式记录 `$?`：完整日志 [r2-gradle-full.txt](qa-evidence/t17-bea37e8/r2-gradle-full.txt)（末尾 `GRADLE_EXIT=0`）与 [r2-fixture-build.txt](qa-evidence/t17-bea37e8/r2-fixture-build.txt)。没有用 `UP-TO-DATE`/缓存命中替代测试证据（`--rerun-tasks --no-build-cache`，两项均为全部 executed）。

| 模块 | 实际执行用例 | 测试套件 | failures/errors/skipped |
| --- | ---: | ---: | --- |
| core | 67 | 6 | 0/0/0 |
| app | 46 | 6 | 0/0/0 |
| accessibility | 103 | 9 | 0/0/0 |
| hook | 46 | 10 | 0/0/0 |
| probe | 36 | 7 | 0/0/0 |
| **合计** | **298** | **38** | **0/0/0** |

统计由同次运行的 JUnit XML 直接解析：[r2-jvm-results.json](qa-evidence/t17-bea37e8/r2-jvm-results.json)，XML 副本在 `r2-jvm-xml/<module>/`。`NO-SOURCE` 任务不计为测试通过。

lint（同次运行，无 error；未新增 baseline、未禁用检查、未放宽 suppression）：

| 模块 | error | warning | 具体告警 |
| --- | ---: | ---: | --- |
| app | 0 | 1 | `ExportedContentProvider`（合同要求导出且只读的 Provider） |
| accessibility | 0 | 0 | — |
| hook | 0 | 1 | `PrivateApi`（反射定位隐藏分发方法，保留并已在源码记录理由） |
| probe | 0 | 0 | — |
| 独立夹具 | 0 | 0 | — |

原始 XML：[r2-lint-summary.json](qa-evidence/t17-bea37e8/r2-lint-summary.json) 与 `r2-lint-{app,accessibility,hook,probe,fixture}.xml`。

### 2.3 产物哈希与“构建 = 安装”一致性

| 产物 | 字节 | SHA-256（本机构建产物） | 设备内实际 `base.apk` SHA-256 |
| --- | ---: | --- | --- |
| `app-debug.apk` | 3,392,024 | `a08a04b94462a09ec740f26988732362a40a20b9b69d50bb8797ce368857f8f5` | 相同 |
| `probe-debug.apk` | 3,169,147 | `0a75edd07c17ade8cd86baef87f27adb74e6211706074ae202a26bad95e73896` | 相同 |
| `app-debug-androidTest.apk` | 2,174,433 | `9b72ae61121fa0fe400d13e7b57f04da32217c2866ec86a061aab4a6e9e8c153` | 相同 |
| `probe-debug-androidTest.apk` | 2,172,101 | `a351fc57caa93bac8c399e848034d5fb28121c23199653457c8c51e9b5730680` | 相同 |
| `noqueries-debug.apk` | 29,369 | `533cbe72ffa12dd1c276aad1f43aafd9f71d086005859be7b16a96d5479e611a` | 相同 |

五个 APK 全部经 `adb install`（app/probe/androidTest 各一枚 + 独立夹具）安装成功；安装后从一个真实设备路径拉取 `base.apk` 重新计算哈希，与本机构建产物一致（完整对照见 `installed-apks.json`、`apk-sha256.txt`）。**本报告全部设备结论只对应上表哈希**。

### 2.4 构建可复现性（本轮新增事实）

本轮以同一命令、同一机器、同一工具链再执行一次 `--rerun-tasks --no-build-cache --max-workers=2` 构建后，四个主/测试 APK 与夹具 APK 的哈希**逐字节不变**（见上表）。因此 `docs/repair-qa01-04.md` 第 3 节给出的 `afa9a786…`（app）/`abfa1014…`（probe）/`3a0b2c94…`（app 测试 APK）**不属于本轮安装产物**，两组哈希不能互换引用；这属于“同一源码的不同构建调用产生不同字节”的构建事实，由 QA 并列记录、不下产品结论，供 Plan 与实现方核对。

## 3. QA-01～04 逐条复测

### 3.1 QA-01（高）：授权真值两个方向都正确

方法：安装修复版 APK 后，从**产品首页的“打开系统无障碍设置”入口**进入系统设置，在服务详情页用系统开关授权（系统确认框 “Allow … full control of your device?” → ALLOW），返回产品首页并等待刷新；随后同样路径撤权（“Stop …?” → STOP）再次观察。

| 观察点 | 授权后 | 撤权后 |
| --- | --- | --- |
| 首页“系统设置授权” | 是 | 否 |
| 首页“服务实际已连接” | 是（分列显示） | 否 |
| 首页“运行阶段” | 已连接，当前没有合格目标 | 未连接（系统未绑定服务） |
| 系统只读值 | `accessibility_enabled=1`；`enabled_accessibility_services=com.antiads.app/com.antiads.accessibility.AdSkipService`；`Bound services` 非空 | `enabled_accessibility_services` 为空；`Enabled services`/`Bound services` 为空 |

证据：`r2-qa01-grant-settings-list.png`、`r2-qa01-grant-dialog.png`、`r2-qa01-regranted-home.png`、`r2-qa01-regranted-home-later.png`、`r2-qa01-regrant-secure.txt`、`r2-qa01-stop-dialog.png`、`r2-qa01-revoked-home.png`、`r2-qa01-final-revoked-system.txt`。设备级组件归属另由 `ServiceOwnershipTest` 在真机包管理器上通过（见 4.5）。

判定：**通过**（API29 模拟器；系统值、Bound 情况与截图均已保留）。仍未测：授权在 API30+ 的“受限设置”路径、各 OEM 设置页差异、真机。

### 3.2 QA-02（中）：文档环境表述

- `docs/build.md` 第 5 节按 t16 修复重写，复查后不再出现“未安装 emulator/镜像”“无 AVD”“工具链分离”的**现状性**表述，历史段落已标注仅存档。该修复对本文档成立。
- 本轮额外复查了安装与模块文档，发现同一类过期表述仍存在于其他文件（`docs/install.md`、`docs/probe.md`、`docs/accessibility.md`、`docs/ui.md`、`docs/hook-probe-observability.md`）与 `docs/build.md` 的两处行内表述，逐条见第 6 节。这些文件不在 t17 范围内，QA 未修改，交 Plan 安排。

判定：**build.md 部分通过；其余文档的不一致仍待修订**（不属本轮复测对象，且不影响设备结论）。

### 3.3 QA-03（中）：返回页按钮状态与“显式开始”

方法：probe 冷启动 → 点击“开始采样” → 按 HOME → 用 `am start -n com.antiads.probe/.MainActivity` 以原任务返回（`LaunchState: HOT`）→ 再显式点击“开始采样”。

| 阶段 | 屏幕状态 | 设备内快照 |
| --- | --- | --- |
| 冷启动 | “开始采样”可用、“停止采样”禁用；`registrationSeq=0` | `r2-qa03-initial.png` |
| 采样中 | 计数增长、周期刷新运行 | `r2-qa03-running.png`；`samplingRunning=true`，各类型 `REGISTERED` |
| HOME 后返回 | **开始可用、停止禁用**，提示“需要重新点开始采样（不会自动重启）”；全部类型 `UNREGISTERED` | `r2-qa03-returned.png`；`registrationSeq` 仍为 6、`samplingRunning=false`（**无自动重注册**） |
| 显式再次开始 | 计数重新增长 | `r2-qa03-restarted.png`；同 `sessionId`、`registrationSeq=12`（**新注册**） |

判定：**通过**。“同 session/新 registration”与生命周期事实已按 UI + 设备内 JSON 双证据记录。

### 3.4 QA-04（中）：debuggable Activity 的诊断快照与节流

- 文件存在且可解析：从设备拉取 `files/probe-diag.json` 后用 JSON 解析成功；顶层与行字段集合与 `docs/probe.md` 第 3 节一致（未用 instrumentation 专门 dump 代替 Activity 路径）。
- 写入来自 Activity 路径：logcat 中 `AntiAdsProbe.Diag` 的 `kind=snapshotFile` 行携带 `path=/data/user/0/com.antiads.probe/files/probe-diag.json`。
- 节流：95 行 / 96.5 秒，其中未强制 89 次，**最小间隔 1003 ms**（没有一次 <1000ms），强制 6 次（开始/停止/暂停/恢复）；最大间隔 6138ms 出现在 Activity 暂停期间（周期刷新停止），符合文档描述。
- 暂停后的快照：暂停瞬间的文件内容为 `activityResumed=false`、`samplingRunning=false`、`UNREGISTERED`，`writtenAtElapsedMs` 与一条 `force=true` 的 `snapshotFile` 日志完全对应。

证据：`r2-qa04-throttle-summary.json`、`r2-qa04-snapshotfile-lines.txt`、`r2-qa04-paused.json`、`r2-qa04-stopped.json`、`r2-qa04-stopped.png`。

判定：**通过**（debuggable 变体；未在非 debuggable 变体上验证“不写文件”的负向行为，该负向由 JVM 用例覆盖）。
## 4. 受影响路径回归（均为固定修复版 APK 上的 API29 实测）

### 4.1 无障碍正例与关键反例

夹具与判据：`am force-stop com.antiads.probe` 后 `am start -n com.antiads.probe/.AdFixtureActivity --es scenario <名称>`；判据为夹具自身在按钮被系统接受点击时写出的 `fixture_skip_clicked` 行（不点击页面按钮制造正例）。

| 场景 | 结果 | 证据 |
| --- | --- | --- |
| `ad_positive` × 3 轮（热机、独立进程） | 3/3 出现 `fixture_skip_clicked`，open→click = 3.45s / 3.64s / 3.29s | `r2-positive-rounds.txt`、`r2-positive-round1..3.png` |
| 关闭条件恢复后的正对照 | open→click = 1.06s | `r2-restore-control.txt` |
| `bottom_button`（跳过按钮在屏幕下方） | 12s 内无点击 | `r2-negative-rounds.txt`、`r2-neg-bottom_button.png` |
| `editable_window`（窗口含输入框） | 12s 内无点击 | 同上、`r2-neg-editable_window.png` |
| `no_ad_label`（缺少独立“广告”上下文） | 12s 内无点击 | 同上、`r2-neg-no_ad_label.png` |

### 4.2 三个独立关闭条件

三次都由**产品 UI 点击**完成，随后读取落盘配置核对（每段证据中直接附带当时的完整 JSON）：

| 关闭项 | 落盘配置 | 正例结果 | 证据 |
| --- | --- | --- | --- |
| 仅全局“免 Root 模式”关 | `revision=27`、`accessibilityEnabled=false` | 12s 无点击 | `r2-close1-global-off.png`、`r2-close-conditions.txt` |
| 仅 probe 的每包“免 Root”关 | `revision=29`、`packages["com.antiads.probe"].accessibilityEnabled=false` | 12s 无点击 | `r2-close2-perapp-off.png`、`r2-close2-perapp-off-run.png` |
| 仅总开关关（全局与每包仍开） | `revision=31`、`masterEnabled=false` | 12s 无点击；首页显示“已连接，但防护开关已关闭” | `r2-close3-master-off-run.png`、`r2-home-master-off.png` |
| 全部恢复 | `revision=32` | 1.06s 后点击 | `r2-restore-control.txt` |

三个条件都能**单独**抑制点击，且不依赖服务断线（`Bound` 保持、首页“服务实际已连接：是”）。

### 4.3 probe 传感器生命周期

见 3.3：开始采样实际注册 5 个选中类型 + 1 个对照类型（回调均非零）、HOME 后全部 `UNREGISTERED`、返回后**不自动重注册**、显式开始产生新的 registration。这同时是 QA-03 的设备证据。

### 4.4 真实 UID 策略读取与只读边界

| 调用方 | 操作 | 结果 | 证据 |
| --- | --- | --- | --- |
| probe（真实 UID 10117） | probe 页“读取本机策略（probe 自读）” | `状态=OK · revision=32 · hookEnabled=true · blockedTypes=[1]` | `r2-probe-policy-selfread.png` |
| 无 queries 夹具（真实 UID 10120） | 夹具页“以自身 UID 读取一次策略” | `ok=true`、payload 为本包策略、`duration_ms=34` | `r2-fixture-policy-read-result.png` |
| shell（UID 2000，伪造 probe 包名） | `content call --uri content://com.antiads.app.config --method get_policy_v1 --arg com.antiads.probe` | `Bundle[{ok=false, error=UNAUTHORIZED}]` | `r2-device-commands-and-outcomes.md` 第 6 节 |

注：`adb shell content` 的退出码固定为 0，判据是返回 Bundle 的具体内容与配置未被改写，这一点与首轮一致。

### 4.5 测试工具链（app instrumentation）

| 命令 | 结果 |
| --- | --- |
| `am instrument -w -r -e class com.antiads.app.ConfigToggleTest -e action master_off com.antiads.app.test/androidx.test.runner.AndroidJUnitRunner` | `OK (1 test)`；`anti_ads_result=SAVED`、`flipped=true`、`revision 32 → 33`，宿主仓储真实落盘 |
| `am instrument -w -r -e class com.antiads.app.ServiceOwnershipTest com.antiads.app.test/androidx.test.runner.AndroidJUnitRunner` | `OK (1 test)`（真实 `PackageManager` 断言组件归属宿主包） |

日志：`r2-app-instrumentation-configtoggle.txt`、`r2-app-instrumentation-ownership.txt`。`probe` 的 `RegistrationHoldTest` 仍**未执行**（需要有框架/受控注册条件，属 H01 A1，保持未测）。

### 4.6 本轮新观察（如实记录，不计为通过）

1. **一次未复现的冷启动时序观察（低置信）。** 开机后第一次启动 `ad_positive` 夹具时，Activity 自身日志显示 `fixture_resume window=0x0`、窗口 7.4s 后才 `Displayed`，6 秒观察窗内没有出现 `fixture_skip_clicked`；把该夹具 `force-stop` 后重跑立刻在 3.07s 内点击，随后 3 轮热机与恢复对照轮（1.06s）全部点击。证据：[首轮截图](qa-evidence/t17-bea37e8/r2-pos-ad_positive.png) 与 `r2-positive-rounds.txt`。**未复现、机制未定位**，不写成产品缺陷、也不写成通过；建议在更接近真机帧率的设备或增加轮次后再判。
2. **撤权后系统侧残留（环境现象）。** 撤权后 `enabled_accessibility_services` 立即为空、`Enabled services`/`Bound services` 为空，但 secure `accessibility_enabled` 仍为 1，且 `Binding services` 残留一条 DEAD 连接；重启本 AVD 后变为 `0`/null/三者全空。与首轮记录的“instrumentation 强停宿主后连接残留”同源，属系统记账副作用；产品 UI 在撤权后立即正确显示“否/否”。证据：`r2-qa01-final-revoked-system.txt`、`r2-post-reboot-system.txt`。
3. **instrumentation 会强停宿主（既有事实，本轮再确认）。** 运行 app instrumentation 后宿主进程不再存在、无障碍连接进入 Binding（DEAD）状态；因此 QA 把所有“关闭后不点击”的实验放在 instrumentation **之前**完成，且不以该时段的观察作为关闭测试证据。
4. **QA 自己的调用错误（记录以免误判）。** 第一次把两个测试类用逗号一起传给 `-e class` 且未传 `-e action`，`ConfigToggleTest` 按设计断言失败（`必须通过 -e action 传入 master_on 或 master_off`，原始输出 `r2-app-instrumentation.txt`）；这是 QA 调用方式问题，不是产品缺陷，随后按其自带参数重跑通过。

## 5. 46 项场景矩阵的增量（只列本轮发生变化/新增证据的行）

标记含义与首轮一致：S=静态/源码核对，J=本轮 JVM 用例，E=API29 模拟器实测，R/D=Root 或普通真机（本轮仍未实测）。

| 场景 | 首轮结论 | 本轮更新 |
| --- | --- | --- |
| A01/A02（无障碍正例/反例） | E 通过（`b19f67f`） | E 通过（`bea37e8` 固定 APK，4 正 + 3 反 + 1 对照；附冷启动观察） |
| A09（授权真值与显示） | **E 失败**（QA-01） | **E 通过**：授权/撤权双向正确，系统值与 Bound 并列取证 |
| C02（开关作用范围） | J 通过；E 通过三层关闭 | E 通过（三层关闭在同一固定 APK 上重做，逐项附落盘 revision） |
| P01（probe 生命周期与诊断文件） | **E 失败**（QA-03/04） | **E 通过**：按钮状态、不自动重注册、新注册、快照文件与节流全部实测 |
| S01/S02（Provider 身份与只读） | J 通过；E 真实 UID 对照 | E 通过：probe UID / 夹具 UID 成功、shell UID 被拒 |
| U01/U02（状态文案与过期） | E 部分通过 | E 通过：授权/连接分列、“已连接但防护开关已关闭”“未连接（系统未绑定服务）”三种状态均实测到 |
| U05、H01～H12、API30+ 相关行 | 未实测 | **仍未实测**，不因本轮通过而改变 |

## 6. 文档复查（安装 / 环境 / 未测范围）

`docs/build.md` 第 5 节与 `docs/emulator-environment.md` 的 t3 一节与本轮事实核对一致（后者已在 t17 一节补充本轮）。以下清单是**同一类过期表述的其他位置**，均属“超出 t17 授权范围”的文件，QA 未修改，交 Plan 处理：

| 文件:行 | 现文（摘要） | 与实测不符之处 |
| --- | --- | --- |
| `docs/install.md:6` | “尚未在真机/模拟器上逐条执行（当前开发环境无设备）”，并链接 `qa-plan.md` | 安装、无障碍授权/撤权、应用选择、probe 生命周期等步骤已在 API29 模拟器逐条执行（首轮 + 本轮）；应改为指向 `verification.md` 的实测记录，并把“未执行”限定为真机/OEM/高版本 |
| `docs/probe.md:4`、`docs/probe.md:99` | “设备实验（含 instrumentation 会话）未执行（本环境无设备/模拟器）”；“所有真机项…未测” | 设备实验已在 API29 执行（本轮含采样、暂停、恢复、快照文件与节流）；真机项仍未测 |
| `docs/accessibility.md:144` | “本机没有 adb 设备或 AVD…未在设备上验证” | 已在 API29 上验证正例/反例/关闭条件；应改为“真机与高版本未测”，并注明历史阶段 |
| `docs/ui.md:107` | “`ServiceOwnershipTest` 尚未在设备上执行，由修复后的独立 QA 复测运行” | 本轮已执行并通过 |
| `docs/ui.md:141`、`docs/ui.md:143` | “本任务环境无设备/模拟器：未做点击、渲染、无障碍授权流程…”，API35 Insets 未确认 | 点击/渲染/授权流程已在 API29 实测；API35 Insets 仍未确认（保留） |
| `docs/hook-probe-observability.md:22-23` | A2 宿主切换开关、策略自查“已实现，未在设备执行” | 策略自查已在设备执行（probe 自读 rev 32 OK）；A1 `RegistrationHoldTest` 仍未执行 |
| `docs/build.md:71` | “设备相关（当前无设备，见第 5 节）” | “当前无设备”已过期（本轮有可用 AVD 且已实测），应改成“无真机/无 Root 设备” |
| `docs/build.md:204` | “修复版尚未在设备上复测，复测由独立 QA 执行” | 本轮已完成复测，应更新为指向本报告 |

## 7. 设备、版本与模式矩阵（未测项不变）

| Android/API | 构建证据 | 非 Root 模式 | Root/LSPosed 模式 |
| --- | --- | --- | --- |
| 10 / 29 | 已构建（本轮重建哈希一致）；官方 x86_64 镜像 + WHPX 模拟 | **本轮 E 通过**：QA-01/03/04 修复、正例/反例/三层关闭/真实 UID/instrumentation | 未注入、未实测 |
| 11 / 30 – 15 / 35 | 通用 APK 已构建（targetSdk 35） | 未模拟、未实测（包可见性/Insets 未验证） | 未实测 |
| 16 / 36+ | 无运行环境（> compileSdk 35） | 未实测、不承诺 | 未实测、不承诺 |

所有系统均**无普通真机、无 Root 真机、无 OEM ROM**。模拟器传感器是模拟来源，不能替代真机传感器结论；无障碍不能阻止摇动传感器，增强模式需要 Root/框架实际注入与用户作用域。

## 8. 收尾与环境清理

- 测试配置收尾：经产品 UI 关闭总开关，落盘 `revision=33`、`masterEnabled=false`（`r2-final-state-before-exit.txt`）；probe 采样处于停止状态。
- 授权收尾：经系统 UI 撤销无障碍授权，`enabled_accessibility_services` 为空、`Enabled services`/`Bound services` 为空；重启后 `accessibility_enabled=0`、Binding 清空（`r2-post-reboot-system.txt`）。
- 设备退出：核对 `emu avd name = AntiAds_QA_API29` 后仅对 `emulator-5580` 执行 `emu kill`（exit 0），`adb devices -l` 为空（`r2-devices-after-exit.txt`）；未执行全机 `adb kill-server`，未操作任何用户 AVD 或真机。
- 后台 job：`bash-31`（首次启动，异常早退，exit 0）、`bash-33`（本轮实例，exit 0）、`bash-37`（构建，exit 0）全部结束并已收集，无遗留运行任务。
- 保留（供后续复测）：项目 SDK、官方 API29 镜像、AVD `AntiAds_QA_API29` 与五个测试 APK；本轮中间文件在 `.tooling/t17-qa/`。
- 本轮未提交任何产品/测试代码；`docs/verification.md`、`docs/qa-evidence/`、`docs/emulator-environment.md` 为 QA 产出，是否提交由 Plan/集成决定。

## 9. 复测入口与仍未验证项

复现本轮结论的最小步骤（同一 AVD 与同一哈希）：

```bash
ANDROID_HOME="D:/test/Anti-ads/.tooling/android-sdk" ANDROID_USER_HOME="D:/test/Anti-ads/.tooling/emulator-qa/android-user" \
  ANDROID_AVD_HOME="D:/test/Anti-ads/.tooling/emulator-qa/avd" .tooling/android-sdk/emulator/emulator.exe \
  -avd AntiAds_QA_API29 -port 5580 -no-window -no-audio -no-boot-anim -no-snapshot-save -gpu swiftshader -memory 1536 -cores 2 -accel on -camera-front none
.tooling/android-sdk/platform-tools/adb.exe -s emulator-5580 wait-for-device
# 确认 get-state=device、sys.boot_completed=1、emu avd name=AntiAds_QA_API29 后安装五个 APK 并核对 SHA-256
```

仍未验证（保持未测，不得外推）：

- Root / LSPosed：注入、作用域、关闭恢复（H01 A1/A2）、同次注册 5 秒恢复；
- API30+ 包可见性差异、API33+ 受限设置安装路径、API35+ 强制 edge-to-edge/Insets；
- 普通真机与 OEM ROM：真实传感器、后台限制、厂商权限管理器、工作资料/克隆、shared/isolated UID；
- GitHub CI 实际运行（工作流已在仓库中，但未在 GitHub 执行）；
- 3.4 与 4.6 中标注的“未复现观察”，需在更强条件下加测后再判定。

---

## 附：第三轮（t3 / architect-flash）独立验证报告入口

- 对象：源码 `bea37e8`（含 t16 修复提交 `be2278c`），与本报告实际安装并复测的是**同一组字节**：app `a08a04b9…`、probe `0a75edd0…`、测试 APK `9b72ae61…`/`a351fc57…`、夹具 `533cbe72…`。
- 报告全文与证据：[docs/qa-evidence/t3-be2278c/verification-round3.md](qa-evidence/t3-be2278c/verification-round3.md)（证据目录 `docs/qa-evidence/t3-be2278c/`）。
- 本节为**纯追加**：追加前本文 SHA-256 为 `8cff33884b5880b741de60583af30f0a346ee03053ba0a8d7a6f34ad2b99ded7`，原有正文与结论未作任何修改。
- 第三轮结论摘要：QA-01～04 全部关闭（源码 + JVM + 设备三重证据）；合同命令与强制全量重跑 exit=0（298 JVM 用例 0 失败、四模块 lint 0 error、五个 APK 组装 + 签名核验）；最终 APK 与 AVD 实际安装件字节相同；另报 3 项**文档/证据一致性**发现（哈希表、六份文档的“无设备”表述、probe 行字段枚举），均不改变产品与设备结论。
- 仍未验证（与本文一致，不得外推）：Root/LSPosed 注入与作用域、H01 同次注册、5 秒租约的设备观测、API30+ 包可见性、API35+ Insets、真机/OEM ROM、GitHub CI 实际运行、`non_clickable` 以外的更广真实应用负例。

---

## 附：t18 文档设备验证状态同步（纯追加）

- 本节由 t18（qa-flash）追加，**只追加、未改动本文任何原有正文与结论**；上一节（第三轮 t3 报告入口）也保持原样。
- 内容：把本文第 6 节列出的“无设备/未复测”过时表述，逐处同步为“已在 API29 模拟器完成哪些场景”，并保留 Root/LSPosed、API30+ 可见性、API35+ Insets、真机、CI 的未测边界。涉及 `docs/install.md`、`docs/probe.md`、`docs/accessibility.md`、`docs/ui.md`、`docs/hook-probe-observability.md`、`docs/build.md` 共 6 份文档、11 处（+1 处同类表行），未改产品源码/测试/构建脚本。
- 前后对照与证据来源：[docs/qa-evidence/t17-bea37e8/doc-status-sync.md](qa-evidence/t17-bea37e8/doc-status-sync.md)；完整 diff：[doc-status-sync-diff.txt](qa-evidence/t17-bea37e8/doc-status-sync-diff.txt)。
- 注意：本文第 6 节列出的行号是**同步前**的文本行号，行号与措辞以 `doc-status-sync.md` 第 2 节为准；这些文档现已不再声称“本机无设备”，但仍明确标注真机/高版本未测。
