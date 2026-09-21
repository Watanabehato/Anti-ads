# t17 第二轮设备复测：命令与结果（API29 模拟器）

固定对象：HEAD `bea37e8086276dd5475524b81bddf55ab17149e5`（= 修复 `be2278c` + 仅文档提交 `bea37e8`），实际安装 APK 见 `apk-sha256.txt`/`installed-apks.json`。
设备：AVD `AntiAds_QA_API29`，serial `emulator-5580`，Android 10 / API29，fingerprint `Android/sdk_phone_x86_64/generic_x86_64:10/QSR1.210820.001/7663313:userdebug/test-keys`，shell uid=2000。
所有命令均带 `-s emulator-5580`；未设置全局 `ANDROID_SERIAL`，未操作任何其它设备。

## 1. 启动与就绪

| 命令 | 结果 | 证据 |
| --- | --- | --- |
| `adb -s emulator-5580 emu avd name` | `AntiAds_QA_API29` / `OK` | `device-ready.txt`（首轮）、`r2-final-state-before-exit.txt`（收尾） |
| `adb -s emulator-5580 get-state` / `shell getprop sys.boot_completed` | `device` / `1` | 同上 |
| `adb -s emulator-5580 shell getprop ro.build.fingerprint` | Android 10 userdebug test-keys | `device-ready.txt` |

## 2. 安装与哈希一致

- 五个 APK 全部安装成功（`install-four.txt` + 夹具安装记录）；安装后逐一比对：构建产物 SHA-256 == `adb exec-out cat /data/app/<pkg>/base.apk` 拉取后 SHA-256（`installed-apks.json`）。
- 本轮（t17 第二轮）安装的 app / probe 主 APK 哈希：`a08a04b9b4…f8f5`（app，3,392,024 B）、`0a75edd07c…3896`（probe，3,169,147 B）。

## 3. QA-01：授权真值（两个方向）

| 步骤 | 命令要点 | 观察 | 证据 |
| --- | --- | --- | --- |
| 授权前进入系统设置 | 产品首页按钮 “打开系统无障碍设置” | 设置页显示 Anti-ads 无障碍跳过 On | `r2-qa01-grant-settings-list.png` |
| 授权 | 服务详情页开关 → 系统 “Allow … full control?” → ALLOW | 系统写入 secure=1，Bound services 出现服务 | `r2-qa01-grant-dialog.png`、`r2-qa01-regrant-secure.txt` |
| 返回首页 | `am start -n com.antiads.app/.MainActivity`，等待刷新 | 显示“系统设置授权：是”“服务实际已连接：是”“运行阶段：已连接，当前没有合格目标” | `r2-qa01-regranted-home.png`、`r2-qa01-regranted-home-later.png` |
| 撤权 | 服务详情页开关 → 系统 “Stop …?” → STOP | 返回后显示“系统设置授权：否”“服务实际已连接：否”“运行阶段：未连接（系统未绑定服务）” | `r2-qa01-stop-dialog.png`、`r2-qa01-revoked-home.png` |

系统侧只读值：授权时 `accessibility_enabled=1` + `enabled_accessibility_services=com.antiads.app/com.antiads.accessibility.AdSkipService` + `Bound services` 非空；撤权后 `enabled_accessibility_services` 为空、`Enabled services`/`Bound services` 为空（`r2-qa01-regrant-secure.txt`、`r2-qa01-final-revoked-system.txt`）。

## 4. 无障碍正例 / 反例 / 三层独立关闭

夹具：`am start -n com.antiads.probe/.AdFixtureActivity --es scenario <名称>`；每次先 `am force-stop com.antiads.probe` 以获得独立进程与窗口；判据为 `AntiAdsProbe.Fixture` 的 `fixture_skip_clicked` 行（点击由系统接受时由夹具自身记录）。

| 场景 | 观察 | 证据 |
| --- | --- | --- |
| `ad_positive` × 3 轮（热机） | 每轮均出现 `fixture_skip_clicked`，open→click = 3.45s / 3.64s / 3.29s | `r2-positive-rounds.txt`、`r2-positive-round1..3.png` |
| `ad_positive` 第 1 次（冷启动，开机后首个夹具窗口） | 窗口 7.4s 才 Displayed（`fixture_resume window=0x0`），6s 观察窗内未出现点击；随后热机重跑即点击。如实记录为一次未复现的冷启动时序观察 | `r2-pos-ad_positive.png`（首轮）、`r2-pos2-ad_positive.png`（重跑成功） |
| `bottom_button`（按钮在下方） | 12s 内无 `fixture_skip_clicked` | `r2-negative-rounds.txt`、`r2-neg-bottom_button.png` |
| `editable_window`（窗口含输入框） | 12s 内无点击 | 同上、`r2-neg-editable_window.png` |
| `no_ad_label`（无独立广告标签） | 12s 内无点击 | 同上、`r2-neg-no_ad_label.png` |
| 关闭条件 1：全局“免 Root 模式”关（revision 27，`accessibilityEnabled=false`） | 正例 12s 无点击 | `r2-close-conditions.txt`、`r2-close1-global-off.png`、`r2-close1-global_a11y_off.png` |
| 关闭条件 2：仅 probe 每包“免 Root”关（revision 29） | 正例 12s 无点击 | `r2-close-conditions.txt`、`r2-close2-perapp-off.png`、`r2-close2-perapp-off-run.png` |
| 关闭条件 3：仅总开关关（revision 31，其余两项仍开） | 正例 12s 无点击；首页显示“运行阶段：已连接，但防护开关已关闭” | `r2-close-conditions.txt`、`r2-close3-master-off-run.png`、`r2-home-master-off.png` |
| 恢复对照（revision 32 全部恢复） | 正例 1.06s 内点击 | `r2-restore-control.txt`、`r2-restore-control.png` |

注：三次关闭条件均通过**产品 UI 点击**完成，并用 `run-as com.antiads.app cat files/protection-config-v1.json` 读取落盘配置核对 revision 与字段（`r2-close-conditions.txt` 每段均附带当时的完整配置）。

## 5. QA-03 / QA-04：probe 生命周期与诊断快照

| 步骤 | 观察 | 证据 |
| --- | --- | --- |
| 冷启动 probe | `采样状态：已停止 · registrationSeq=0 · activityResumed=是`；“开始采样”可用、“停止采样”禁用 | `r2-qa03-initial.png`、`probe-idle.json` 结构一致 |
| 点击“开始采样” | `samplingRunning=true`、`registrationSeq=6`、各类型 `REGISTERED` 且回调增长 | `r2-qa03-running.png`、设备内 `probe-diag.json` |
| HOME → 原任务返回（`LaunchState: HOT`） | 仍是同一 `sessionId`，`registrationSeq` 保持 6，`samplingRunning=false`，全部类型 `UNREGISTERED`；“不做自动重注册”提示与按钮状态一致（开始可用/停止禁用） | `r2-qa03-returned.png`、返回后读取的 `probe-diag.json` |
| 显式再次点击“开始采样” | 同 sessionId 下 `registrationSeq=12`（新注册），`samplingRunning=true`、回调重新计数 | `r2-qa03-restarted.png` |
| 再次 HOME（暂停态快照） | 文件中 `activityResumed=false`、`samplingRunning=false`、`UNREGISTERED`，`writtenAtElapsedMs` 与一条 `force=true` 的 `kind=snapshotFile` 日志一致 | `r2-qa04-paused.json` |
| 再次返回页面 | `registrationSeq` 仍为 18，未自动重启 | 控制台输出（本文件第 5 节表格对应命令） |

节流统计（`logcat -s AntiAdsProbe.Diag:I` 中 `kind=snapshotFile` 行）：95 行 / 96.5s，其中未强制 89 次、最小间隔 **1003 ms**（无一次 <1000ms）、强制 6 次（开始/停止/暂停/恢复）；最大间隔 6138ms 出现在 Activity 暂停期间（ticker 停止），符合文档描述。汇总见 `r2-qa04-throttle-summary.json`，原始行 `r2-qa04-snapshotfile-lines.txt`。

文件 schema 校验：拉取设备内 `files/probe-diag.json` 后用 JSON 解析，顶层字段为 `activityResumed, controlType, elapsedMs, host, kind, pid, registrationSeq, rows, samplingRunning, schemaVersion, sessionId, tag, writtenAtElapsedMs`，行字段为 `activityResumed, callbacks, callbacksSinceRegister, control, elapsedMs, exists, gapSinceLastMs, host, lastCallbackElapsedMs, pid, registerResult, registrationSeq, samplingState, selected, seq, sessionId, shakes, tag, type`（与 `docs/probe.md` 第 3 节一致）。没有用 instrumentation 的专门 dump 代替 Activity 写入路径。

## 6. 真实 UID 策略读取与测试工具链

| 调用方 | 命令/操作 | 结果 | 证据 |
| --- | --- | --- | --- |
| probe（UID 10117） | probe 首页勾选“读取本机策略（probe 自读）” | `状态=OK · revision=32 · hookEnabled=true · blockedTypes=[1]` | `r2-probe-policy-selfread.png` |
| 无 queries 夹具（UID 10120） | 夹具页“以自身 UID 读取一次策略” | `ok=true`、`payload` 与独立配置一致、`duration_ms=34`、`uid=10120 pid=4237` | `r2-fixture-policy-read-result.png` |
| shell（UID 2000，伪造 probe 包名） | `content call --uri content://com.antiads.app.config --method get_policy_v1 --arg com.antiads.probe` | `Bundle[{ok=false, error=UNAUTHORIZED}]` | 本文件第 6 节命令输出 |
| app instrumentation | `am instrument -e class com.antiads.app.ConfigToggleTest -e action master_off` | `OK (1 test)`，`revision_before=32 → revision_after=33`，`anti_ads_result=SAVED`，宿主仓储真实写入 | `r2-app-instrumentation-configtoggle.txt` |
| app instrumentation | `am instrument -e class com.antiads.app.ServiceOwnershipTest` | `OK (1 test)` | `r2-app-instrumentation-ownership.txt` |

诚实记录：第一次误用 `-e class com.antiads.app.ServiceOwnershipTest,com.antiads.app.ConfigToggleTest`（两个类、且**未**传 `-e action`）时，`ConfigToggleTest` 因缺少参数按设计失败（`必须通过 -e action 传入 master_on 或 master_off`，见 `r2-app-instrumentation.txt`），`ServiceOwnershipTest` 通过；该失败由 QA 调用方式引起，不是产品缺陷，随后按测试自身要求的参数重跑通过。

## 7. 收尾

- 总开关经产品 UI 关闭：配置 `revision=33`、`masterEnabled=false`（`r2-final-state-before-exit.txt`）。
- 系统 UI 撤销无障碍授权：`enabled_accessibility_services` 空、`Enabled services`/`Bound services` 空（`r2-qa01-final-revoked-system.txt`）；此后 `accessibility_enabled` 残留 1、Binding 残留一条 DEAD 连接，重启本 AVD 后归零（`r2-post-reboot-system.txt`）。
- 核对 AVD 名称后 `adb -s emulator-5580 emu kill`（exit 0），`adb devices -l` 为空（`r2-devices-after-exit.txt`）。
