# t18 文档设备验证状态同步（前后对照）

任务：t18。执行：qa-flash（attempt `a0ce7e82-ffe8-48e4-aacc-96fe4d869080`）。日期：2026-09-21。
目的：把 t17 报告第 6 节列出的“无设备/未复测”过时表述，改成“已在 API29 模拟器完成哪些场景”，同时保留 Root/LSPosed、API30+ 可见性、API35+ Insets、真机、CI 的未测边界。

## 1. 证据来源（唯一依据）

- [docs/verification.md](../../verification.md)（t17 第二版报告，SHA-256 `8cff33884b5880b741de60583af30f0a346ee03053ba0a8d7a6f34ad2b99ded7`，本任务**未改动**）；
- `docs/qa-evidence/t17-bea37e8/`：`run-manifest.json`、`r2-device-commands-and-outcomes.md`、`r2-positive-rounds.txt`、`r2-negative-rounds.txt`、`r2-close-conditions.txt`、`r2-qa03-*.png`、`r2-qa04-*.json`、`r2-qa01-*.png|txt`、`r2-app-instrumentation-*.txt`、`r2-post-reboot-system.txt`；
- 环境事实：`docs/emulator-environment.md` 的“t17 修复版复测的设备复用与清理”一节。

未测边界（所有改动后仍然成立，逐条保留在文档中）：Root/LSPosed 注入与作用域、`RegistrationHoldTest`（H01 A1 同次注册保持）、API30+ 包可见性、API35+ Insets/强制边到边、普通与 Root 真机（模拟器传感器为模拟来源）、GitHub CI 实际运行。

## 2. 逐处前后对照

### 2.1 install.md（原第 6–7 行，现第 6–7 行）

- 原文：`本页描述的操作步骤已在代码与单一来源合同中定义，但尚未在真机/模拟器上逐条执行（当前开发环境无设备）。设备实测结论以 QA 的执行记录为准，见 docs/qa-plan.md。`
- 现文：步骤**已在项目 API29 模拟器（AVD `AntiAds_QA_API29`，serial `emulator-5580`）按序列实际执行**（安装、系统 UI 授权与撤权、应用选择与每应用开关、probe 采样/暂停/返回、Provider 真实 UID 读取），链接改为 [独立 QA 报告](verification.md) 与 `docs/qa-evidence/t17-bea37e8/`；并新增“**仍未在真机、Root/LSPosed 设备与 API30+ 上逐条执行**”。
- 依据：`r2-qa01-*.png|txt`、`r2-close-conditions.txt`、`r2-fixture-policy-read-result.png`、`r2-probe-*.png`。

### 2.2 probe.md（第 4 行；原第 97 行标题与第 99 行）

- 第 4 行原文：`设备实验（含 instrumentation 会话）未执行（本环境无设备/模拟器）`；现文：**非 Root 侧设备实验已在 API29 模拟器执行**（采样/暂停/返回/显式重启、真实注册轮次、`files/probe-diag.json` 快照与 ≤1 次/秒节流），并保留“`RegistrationHoldTest`、Root/LSPosed 条件与真机仍未执行”。
- 第 99 行原文：`无设备/模拟器：所有真机项…未测`；现文拆成两条：**已在 API29 模拟器完成**（传感器存在性/注册返回值/回调计数/HOME 返回不自动重启/显式重开新注册/样例页坐标与自动点击联动/快照与节流）与**仍未测**（真机传感器、Root/LSPosed、`RegistrationHoldTest`、无传感器/注册被拒/息屏/后台限制等不可判定条件）；小节标题由“未测与限制”改为“设备验证范围、未测项与限制”。
- 新增（按 captain 要求保留为“未复现的观察”）：冷启动首个 `ad_positive` 窗口 7.4s 才 `Displayed`、6 秒观察窗内未点击，重跑 3.07s 点击、其后 4 轮全部点击——未复现、机制未定位，不记缺陷也不记通过，建议加测。
- 依据：`r2-qa03-returned.png`、`r2-qa03-restarted.png`、`r2-qa04-throttle-summary.json`、`r2-qa04-paused.json`、`r2-positive-rounds.txt`、`r2-neg-*.png`。

### 2.3 accessibility.md（原第 142 行标题与第 144 行，现第 142 行标题与第 144 行起）

- 原文：`无设备/模拟器：本机没有 adb 设备或 AVD，AccessibilityService 的真实连接、系统授权、真实广告页点击均未在设备上验证`；现文：**已在 AVD `AntiAds_QA_API29` 完成**真实连接与系统 UI 授权/撤权状态、正例 4 轮自动点击、3 个关键反例各 12 秒不点击、三层独立关闭与关闭期间的 12 秒观察；并新增“**未执行**：真机与 OEM ROM、Root/LSPosed、API30+ 包可见性、API35+ Insets”和同一条“未复现的时序观察”。
- 依据：`r2-qa01-regranted-home.png`、`r2-qa01-revoked-home.png`、`r2-positive-rounds.txt`、`r2-negative-rounds.txt`、`r2-close-conditions.txt`。

### 2.4 ui.md（原第 107、141、142、143 行）

| 位置 | 原文要点 | 现文要点 |
| --- | --- | --- |
| 107 | `ServiceOwnershipTest` “尚未在设备上执行” | **已在 API29 模拟器执行并通过 1 例**（`OK (1 test)`，t17） |
| 141 | 交互与视觉“**未做**（本任务环境无设备/模拟器）” | **部分完成（API29 模拟器）**：点击/渲染、授权·撤权与系统设置返回刷新、开关落盘核验；**WindowInsets 视觉效果、包可见性列表与真机仍未做** |
| 142 | Provider 真实身份与跨进程成功读“**未做**” | **已完成（API29 模拟器）**：probe UID 10117 与夹具 UID 10120 成功、shell UID 2000 被 `UNAUTHORIZED` 拒绝 |
| 143 | “Android 15 边到边 / API29 最低版本行为 **未做**（无设备）” | **API29 已确认；API35+ 未做**（Insets 仍为静态 API 分支处理） |

- 说明：第 142 行是同一张“自检记录与限制”表内同类的设备状态行，一并同步；除此之外 ui.md 其余文字（含 t3 的 ConfigToggleTest 记录）未改。
- 依据：`r2-app-instrumentation-ownership.txt`、`r2-qa01-final-revoked-home.png`、`r2-close2-perapp-off-run.png`、`r2-fixture-policy-read-result.png`、`r2-probe-policy-selfread.png`。

### 2.5 hook-probe-observability.md（第 22、23 行与表后段落）

- A2 行原文 `已由 B 提供，未在设备执行` → **已在 API29 模拟器执行：`OK (1 test)`、`SAVED`、`revision 32→33`**，并注明“同次注册保持/5 秒恢复仍需 Root 或框架条件”。
- 策略自查行原文 `已实现，未在设备执行` → **已在 API29 模拟器执行：probe 自读 `OK`、`revision=32`、`hookEnabled=true`、`blockedTypes=[1]`**。
- **A1 行（`RegistrationHoldTest`）保持 `未在设备执行`**——该结论仍为真，故意未改。
- 表后段落原文 `以上全部仍是待验证方案…` → 改为“**非 Root 侧已有设备结论**（probe 生命周期与快照、A2 开关、策略自查、独立夹具真实 UID 读取）；**H01 A1、H03 与全部 Hook 注入/作用域结论仍无设备证据**”。
- 依据：`r2-app-instrumentation-configtoggle.txt`、`r2-probe-policy-selfread.png`、`r2-qa04-throttle-summary.json`、`r2-fixture-policy-read-result.png`。

### 2.6 build.md（原第 71、196、199、204 行；5.3 增补两条）

| 位置 | 原文要点 | 现文要点 |
| --- | --- | --- |
| 71 | “设备相关（当前无设备，见第 5 节）” | “当前无**真机、无 Root/LSPosed 设备**；项目 AVD 按需启停”，并链接模拟器环境记录 |
| 5.1 表 | 只有 t3 复用与 t3 清理 | 新增 **t17 复测**行（自有 job `bash-33`、安装 `bea37e8` 五个 APK、完成 QA-01～04 与回归）；**当前实例**行改为 t17 结束时 `emu kill`（同时保留 t3 清理记录指向其小节） |
| 199 标题 | “（t3，源码 b19f67f）” | “（**t3 首轮 `b19f67f`；t17 复测 `bea37e8`**）”——t3 的 4 条 bullet 原文未动 |
| 204 | “修复版尚未在设备上复测” | **修复版已由独立 QA 在 API29 模拟器复测通过**（QA-01/03/04 + 正例4轮/反例3例/三层关闭/真实 UID/两个 instrumentation），并明确“该通过只限 API29 模拟器范围” |
| 5.3 | — | 新增两条：**未复现的时序观察（建议加测）**与**撤权后的系统侧残留（产品 UI 即时正确，系统标记需重启归零）** |

## 3. 保持原样的内容（未改写的历史与证据）

- 首轮 t3 报告 `docs/qa-evidence/history/t3-b19f67f-verification.md`（= 替换前 `docs/verification.md`，SHA-256 `3fc901f168bd…`）与证据目录 `docs/qa-evidence/t3-b19f67f/`（132 文件）、ZIP（`c2679c212887…`）均未触碰；
- `docs/verification.md` 的 **t17 正文（含第 6 节原始行号清单与全部结论）未改动**：冻结副本 `docs/qa-evidence/t17-bea37e8/t17-verification.md` 的 SHA-256 仍为 `8cff33884b5880b741de60583af30f0a346ee03053ba0a8d7a6f34ad2b99ded7`；
- 该文件随后有**两处纯追加**：第三轮 t3（architect-flash）的“第三轮复测入口”一节，以及 t18 本次追加的“文档设备验证状态同步”一节。追加后 `docs/verification.md` 为 276 行、SHA-256 `2fc9af7816cbf959525885277b51840082103a52cc0e680d7c11c180cd2954e7`（与冻结副本的差异仅这两节，见 `doc-status-sync-verification-append.diff`）。第 6 节的行号为**同步前**文本行号，以本文件第 2 节为准；
- 各文档中标注为历史阶段的段落（build.md 5.4“历史记录”、`repair-qa01-04.md` 的 t16 对照、t2/t11/t15/t3 的原始描述）保持原样；
- 未改动任何产品源码、测试、构建脚本或其他模块实现。

## 5. 追加核查：F2（probe 诊断 JSON 字段枚举）

captain 在 t18 结束后转来 t3 第三轮的 **F2（低）**：`docs/probe.md` 第 3 节的诊断 JSON 字段枚举漏列 `tag`/`seq`。核查结果如下（QA 只核查、未重复修改）：

- **该修订已经存在**：工作树中的 `docs/probe.md` 第 44 行与第 46 行已改为 19 项完整枚举（`tag/seq/sessionId/registrationSeq/host/activityResumed/pid/elapsedMs/type/exists/selected/registerResult/samplingState/callbacks/callbacksSinceRegister/lastCallbackElapsedMs/gapSinceLastMs/shakes/control`），并有“19 个，顺序固定”“须与 `DiagJsonTest…` 断言集合恰好相等”的表述；该改动出现在本轮 QA 的两次读取之间，**由其他成员在同一工作树上完成**，QA 未重复编辑（避免冲突）。
- **机械核对通过**（脚本 `.tooling/t17-qa/f2-check.py`，输出存档 [`f2-field-enumeration-check.json`](f2-field-enumeration-check.json)）：
  - `docs/probe.md` 第 44 行枚举（19）== 第 46 行“行字段”枚举（19）== `DiagJsonTest.snapshotIsParseableAndCarriesOnlyDiagFields` 的行断言集合（19）== 设备真实文件 `r2-qa04-paused.json` 的行键（19）；差集为空。
  - 顶层字段两处一致：文档 `{schemaVersion, kind, tag, sessionId, registrationSeq, host, pid, activityResumed, samplingRunning, elapsedMs, writtenAtElapsedMs, controlType, rows}` == 设备文件顶层键（13 项），差集为空。
- **仍未一致的一处（新发现，未改，待 owner/captain 决定）**：`docs/hook-probe-observability.md` 第 39 行字段表与第 62 行示例 JSON 使用 `lastElapsedMs` 并含 `pinned`，但实现与设备输出是 `lastCallbackElapsedMs`（`probe/src/main/kotlin/com/antiads/probe/diag/DiagJson.kt:44`）且**没有** `pinned`（`grep -rn pinned probe/src/main/kotlin/` 无命中）。建议最小修订：第 39/62 行 `lastElapsedMs` → `lastCallbackElapsedMs`，并在示例中删除 `pinned`；因该文件可能有并发编辑者，QA 未擅自改动。


## 4. 收尾核对命令与结果

```bash
git status --porcelain
# M docs/accessibility.md / docs/build.md / docs/emulator-environment.md / docs/hook-probe-observability.md
# M docs/install.md / docs/probe.md / docs/ui.md  ＋ 未跟踪 docs/qa-evidence/、docs/verification.md
git diff --stat
# accessibility.md 6、build.md 11、emulator-environment.md 30（t17 遗留）、hook-probe-observability.md 6、
# install.md 4、probe.md 8、ui.md 8 —— 7 files changed, 53 insertions(+), 20 deletions(-)
# 无 app/ core/ accessibility/ hook/ probe/ gradle/ qa/fixtures/ 变更
grep -n -E "无设备|未复测|当前开发环境无设备|尚未在设备上执行" docs/{install,probe,accessibility,ui,hook-probe-observability,build}.md
# 仅剩 hook-probe-observability.md:21 的 A1 RegistrationHoldTest“未在设备执行”——该结论仍为真，故意保留
```

完整 diff 另存为 [`doc-status-sync-diff.txt`](doc-status-sync-diff.txt)（6 份已跟踪文档的 `git diff`）；`docs/verification.md` 未跟踪，其两节追加的差异另存为 [`doc-status-sync-verification-append.diff`](doc-status-sync-verification-append.diff)（对照冻结副本 `t17-verification.md`）。
