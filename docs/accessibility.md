# 无障碍跳过模块（:accessibility）说明

本文件描述免 Root 无障碍模式的实现、执行约束、可核验状态、测试证据与已知限制。
代码所有者：开发 C（accessibility-engineer，t9）。接口属于 `docs/contracts.md` 冻结范围，本模块不新增对外协议。

## 1. 职责与边界

| 部分 | 负责内容 |
| --- | --- |
| :core（共享判定算法） | 广告上下文节点、跳过文案正则、几何过滤、“广告”上下文与候选必须来自不同节点、窗口期与拒绝条件、返回 `SkipCandidate`（最多 1 项，reason 固定 `EXPLICIT_AD_SKIP`） |
| :accessibility（本模块） | 服务声明、事件过滤、foreground epoch、250ms 事件合并、受限节点遍历与 Android 快照适配、执行前复核、ACTION_CLICK 动作与去重冷却、状态发布 |

本模块**不实现**规则匹配：不读屏幕文本做模糊判断，不在本模块重写广告/跳过文案规则。共享判定算法在 `:core` 的 `ConservativeAdRuleEngine`（**t7 已实现**，含 26 个边界用例），本模块只消费其返回的候选；候选为空时不执行任何点击（fail-closed）。

候选 → 执行前复核请求的映射由 `SkipRequestBuilder` 统一提供（t11 提取）：**服务与跨模块集成测试走同一份映射逻辑**，避免“测试拼一份、线上另一份”的假验证。

明确不承诺：不保证对所有应用、所有系统版本有效；不模拟手势、不使用返回键/Home、不点击父容器、不做模糊包含匹配、不点击广告主体；无法在不 Root 的前提下拦截 Java 传感器回调（那是 Hook 模式的能力）。

## 2. 文件清单

| 文件 | 作用 |
| --- | --- |
| `AdSkipService.kt` | 无障碍服务：事件处理、调度、遍历、复核、执行、状态 |
| `SkipGate.kt` | 纯逻辑状态机：事件过滤、epoch、250ms 合并、一次/epoch、2000ms 冷却、复核编排 |
| `ExecutionGuards.kt` | 纯函数复核：目标选择、窗口/时间边界、候选与 refresh 节点、敏感动作文案 |
| `PhaseCalculator.kt` | 纯函数阶段计算（DISCONNECTED/ERROR/PAUSED/IDLE/WATCHING） |
| `SensitivePackageRules.kt` | 纯函数敏感包判定 |
| `TraversalBudget.kt` | 纯逻辑遍历预算（节点/深度/耗时） |
| `WindowSnapshotCollector.kt` | Android 节点 → core `AdWindowSnapshot` 适配与节点引用释放 |
| `SensitivePackageResolver.kt` | 解析当前输入法/默认 HOME/已知系统包（不扩大包可见性） |
| `AccessibilityDependencies.kt` | 宿主注入 `ConfigRepository` 的唯一入口 |
| `AccessibilityRuntime.kt` | 状态发布与订阅（`state()`/`observe()`） |
| `res/xml/accessibility_service_config.xml`、`AndroidManifest.xml`、`res/values/strings.xml` | 服务声明与中文说明文案 |

## 3. 服务声明与系统配置

- Manifest：`com.antiads.accessibility.AdSkipService`，`android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`、`android:exported="true"`、intent-filter `android.accessibilityservice.AccessibilityService`、meta-data 指向 `@xml/accessibility_service_config`。带该权限的服务**只能由系统绑定**，普通应用无法启动或绑定，也没有其它入口。
- 服务配置：`typeWindowStateChanged|typeWindowContentChanged`、`feedbackGeneric`、`notificationTimeout=100`、`canRetrieveWindowContent=true`、`canPerformGestures=false`、`canRequestTouchExplorationMode=false`、`canRequestFilterKeyEvents=false`、`accessibilityFlags=flagRetrieveInteractiveWindows|flagReportViewIds`。
- 用户必须自己在系统“无障碍/已安装的服务”里授权；应用无法代授权，也不能判断用户是否只是“勾选过”。

## 4. 运行时流程

1. 系统绑定服务 → `onServiceConnected`：标记 connected，注册依赖；未注入依赖 → `ERROR + DEPENDENCY_MISSING`（全关，不动作）。
2. 订阅配置：拿到快照与后续 revision；配置关闭/排除当前包 → 立即取消已排队的扫描。
3. 事件到达（只处理两类窗口事件）→ 包名可确定时：同包同 windowId 沿用 epoch，包或 windowId 变化则新建 epoch 并取消旧排队任务。
4. 事件合并在服务主线程：两次扫描至少间隔 250ms；重复事件不会把扫描无限推后。
5. 扫描：读取 active root，核对与 epoch 一致 → 受限遍历（≤300 节点、深度 ≤20、耗时 <8ms）→ 生成 core 快照 → 调用 core 规则引擎。
6. 有候选时执行前复核：依赖/连接、配置 revision、包名合法且非拒绝包、目标开关与内置规则、active root 包与 windowId、锁屏、敏感包、遍历完整、窗口期 0..10000ms、快照年龄 ≤500ms、候选规则与 reason、节点 refresh 后仍可见/可点击/非输入框且文案与快照完全一致、文案不含购买/登录/授权类词。
7. 全部通过才授权一次 `ACTION_CLICK`：先把“本 epoch 已尝试”与同包时间戳固定下来，再调用 `performAction`；返回 false 也算已尝试，不再紧密重试。
8. 记录状态：包名、规则 ID、elapsed 时间、`actionAccepted`（系统是否接收动作）。

## 5. 执行约束数值（与 contracts 第 5 节一致）

| 约束 | 数值 | 说明 |
| --- | --- | --- |
| 事件合并/最小扫描间隔 | 250 ms | 同一窗口重复事件合并，不重置 epoch |
| 快照最大年龄 | 500 ms | 超过即丢弃重新匹配 |
| 窗口期 | 0..10000 ms | 以 epoch 的 foregroundSince 计算，contentChanged **不延长** |
| 同包动作最小间隔 | 2000 ms | 以“尝试”计数，跨 epoch 生效 |
| 单次遍历节点上限 | 300 | 达到即 traversalComplete=false，本次不点击 |
| 遍历深度上限 | 20（根为 0） | 同上 |
| 遍历耗时预算 | < 8 ms | 同一枚单调时钟计时 |
| 文本快照字段上限 | 256 字符 | 不保存界面全文 |
| 同包冷却表上限 | 32 条 | 避免无限增长 |

所有时间都使用 `SystemClock.elapsedRealtime()` 数值，不使用墙上时钟；时钟回退不会绕过冷却或窗口期（判负即拒绝）。

## 6. 拒绝码（内部诊断，不构成对外协议）

状态里的 `lastErrorCode` 与调试日志使用稳定英文码，全部含义是“本次没有动作”，不代表广告不存在。

| 分组 | 码 |
| --- | --- |
| 连接/依赖 | NOT_CONNECTED、DEPENDENCY_MISSING、CONFIG_UNAVAILABLE |
| 目标选择 | NOT_WATCHING、CONFIG_REVISION_CHANGED、POLICY_DISABLED、REJECTED_PACKAGE、INVALID_PACKAGE、RULE_NOT_ENABLED |
| 事件/窗口 | EVENT_TYPE_FILTERED、WINDOW_IDENTITY_UNKNOWN、WINDOW_CHANGED、NOT_APPLICATION_WINDOW、KEYGUARD_LOCKED、SENSITIVE_PACKAGE、TRAVERSAL_INCOMPLETE、WINDOW_SIZE_INVALID、EDITABLE_NODE_IN_WINDOW、WINDOW_AGE_OUT_OF_RANGE、SNAPSHOT_STALE |
| 去重/冷却 | EPOCH_ALREADY_ATTEMPTED、THROTTLED |
| 候选/节点 | RULE_NOT_BUILTIN、REASON_NOT_EXPLICIT_AD_SKIP、NODE_EDITABLE、NODE_NOT_ACTIONABLE、REFRESH_FAILED、REFRESH_EDITABLE、REFRESH_NOT_ACTIONABLE、NODE_TEXT_CHANGED、SENSITIVE_ACTION |
| 生命周期 | SERVICE_INTERRUPTED、SERVICE_UNBOUND、CONFIG_DISABLED、INTERNAL_ERROR |

## 7. 状态接口（给宿主界面）

```kotlin
AccessibilityRuntime.state(): AccessibilityRuntimeState
AccessibilityRuntime.observe(listener: (AccessibilityRuntimeState) -> Unit): Subscription
```

- `connected`：只有系统真正绑定服务后才为 true；`AccessibilityDependencies.install(...)` **不会**改变它（纯配置不能变成“已保护”）。
- `phase`：DISCONNECTED（未连接）／ERROR（未注入依赖或内部错误）／PAUSED（总开关或全局无障碍关闭）／IDLE（已连接但当前前台不是合格目标）／WATCHING（正在合格目标观察）。
- `activePackage`：仅 WATCHING 时为当前合格目标包名。
- `lastAction.actionAccepted`：`performAction` 的真实返回值，只表示“系统接收了动作”。界面文案应为“已发送跳过点击”，不得写成“广告已跳过/已全面保护”；页面是否真的变化必须由目标页面自身证明。
- `observe` 注册后立即回调当前状态；`close` 幂等，取消后不再开始新回调；回调可能来自服务主线程，界面自行投递 UI 线程；监听器异常被隔离。
- 不记录屏幕文本、输入内容或节点树。

宿主接线（由集成任务落地，本模块不改 `:app`）：`AntiAdsApplication.onCreate()` 调用 `AccessibilityDependencies.install(AppConfigRepository.get(this))`。

## 8. 误触防护清单

1. 只执行 core 返回的候选，且候选规则必须是内置 `builtin.conservative.v1`、reason 必须是 `EXPLICIT_AD_SKIP`；本模块不做任何文本匹配，因此“只看到跳过文本”不会触发动作。
2. 只点击候选节点自身（要求自身 clickable/enabled/visible），不点击父容器，不点击广告主体。
3. refresh 后节点必须仍可见、可点击、非输入框，且 text/contentDescription 规范化后与快照完全一致（倒计时变化即放弃）。
4. 文案含购买/支付/充值/订阅/登录/注册/授权/允许/同意/确认/领取/继续/安装/下载（含常见英文）时拒绝：二次防线。
5. 任意节点 editable/password 的窗口整体不处理（含未被选择的输入节点）。
6. 锁屏、敏感包（宿主自身、android、SystemUI、当前输入法、默认 HOME、权限控制器、安装器、设置、锁屏）不处理；窗口身份无法确定时跳过。
7. 遍历不完整（节点/深度/耗时任一达上限）本次不点击。
8. 窗口超过 10 秒、快照超过 500ms、同一 epoch 已尝试过、同包 2000ms 内一律拒绝。
9. 停用/撤权/解绑/中断：立即取消排队扫描并清空状态；`onInterrupt` 只记中断，不当作永久断开，也不会误报成功。
10. 日志只记录拒绝码与包名，不输出配置原文、界面文本或输入内容。

## 9. 测试与验证

模块验证命令（与 `docs/architecture.md` 第 5 节一致）：

```bash
bash ./gradlew --no-daemon :accessibility:testDebugUnitTest :accessibility:lintDebug :accessibility:assembleDebug
```

JVM 单测（`accessibility/src/test`，JUnit4，纯 JVM，无 Robolectric、无新增依赖；通过 `internal` 接缝注入时间与输入）：

| 用例文件 | 覆盖 |
| --- | --- |
| `ExecutionGuardsTest` | 目标/依赖/revision 复核；窗口身份、锁屏、敏感包、遍历不完整、窗口期 10000/10001、快照 500/501、时钟回退；候选规则/reason、可点击性、refresh 失败/变输入框/变不可见/文案变化、敏感动作文案、规范化与 256 字符上限 |
| `SkipGateTest` | 事件类型过滤、包名缺失、epoch 建立与替换、重复 content 事件不重置 epoch、content 事件不延长 10 秒窗口、250ms 合并与一次性取用、window/package 变化取消旧排队、一次/epoch（返回 false 也算）、2000ms 冷却 1999/2000 边界、时钟回退不绕过冷却、撤权取消排队、reset、冷却表上限 |
| `TraversalBudgetTest` | 节点 300、深度 20/21、耗时 8ms 边界与默认值 |
| `PhaseCalculatorTest` | DISCONNECTED/ERROR/PAUSED/IDLE/WATCHING 全分支 |
| `SensitivePackageRulesTest` | 宿主/android/SystemUI、无法确定即敏感、普通第三方包不敏感 |
| `AccessibilityRuntimeTest` | 初始状态、立即回调、关闭幂等、监听器异常隔离、重复状态不重复通知、动作记录与解绑保留、中断与错误 |
| `AccessibilityDependenciesTest` | install 不制造连接、install/attach 两种时序、detach/close 后不再回调 |
| `ServiceDeclarationTest` | 服务配置 XML 固定属性（事件类型、无手势/触摸探索/按键过滤、两个 flag）、Manifest 只由系统绑定、服务类继承 `AccessibilityService` |

### 9.1 本次 t9 提交前的实际运行结果

命令：`bash ./gradlew --no-daemon :accessibility:testDebugUnitTest :accessibility:lintDebug :accessibility:assembleDebug`（本机，退出码 0）

- `:accessibility:testDebugUnitTest`：8 个测试类、共 92 个用例，failures=0、errors=0、skipped=0
  （AccessibilityDependenciesTest 5、AccessibilityRuntimeTest 12、ExecutionGuardsTest 37、PhaseCalculatorTest 9、SensitivePackageRulesTest 4、ServiceDeclarationTest 3、SkipGateTest 18、TraversalBudgetTest 4）。
- `:accessibility:lintDebug`：`No issues found`（0 error / 0 warning）。
- `:accessibility:assembleDebug`：生成 `accessibility/build/outputs/aar/accessibility-debug.aar`。

这些结果只证明“纯逻辑与静态声明按合同成立 + 模块可组装”，**不**证明设备上能跳过任何广告。

## 10. 设备验证范围、未实测项与已知限制（不得据此宣称已覆盖）

- **已在项目 API29 模拟器（AVD `AntiAds_QA_API29`）完成**：`AccessibilityService` 的真实连接与系统 UI 授权/撤权后的状态、`ad_positive` 正例 4 轮自动点击、3 个关键反例（下方按钮/含输入框/无独立广告上下文）各 12 秒不点击、全局·每包·总开关三层独立关闭、以及关闭期间保持无动作的 12 秒观察。证据见 [docs/verification.md](verification.md) 第 3.1/4.1/4.2 节与 `docs/qa-evidence/t17-bea37e8/`。
- **未执行**：真机与 OEM ROM、Root/LSPosed、API30+ 包可见性、API35+ Insets；模拟器结果不能替代真机结论，单测与 lint/组装通过也不等于设备可用。
- **未复现的时序观察（建议加测）**：开机后第一个正例窗口用了 7.4 秒才 `Displayed`，6 秒观察窗内未发生点击；重跑即 3.07 秒内点击、其后 4 轮全部点击。未复现、机制未定位，不记为缺陷也不记为通过（`r2-positive-rounds.txt`）。
- **依赖 core 规则实现**：`:core` 的 `ConservativeAdRuleEngine` 已在 t7 实现；跨模块链路（真实 core 候选 → 快照 → `SkipRequestBuilder` → `SkipGate`/`ExecutionGuards` 复核）已由 `CoreRuleToExecutionIntegrationTest`（11 例）在 JVM 上覆盖。**这仍是纯逻辑验证**：系统授权、真实无障碍连接、真实页面变化必须在设备上验证，不能用该测试代替。
- **系统/应用差异**：不同 ROM 的窗口类型、`windowId` 复用、包可见性、系统无障碍策略都会影响行为；本模块不承诺覆盖所有应用，对 WebView/自绘/视频贴片/PiP/系统弹窗等场景可能完全不动作。
- **同一 windowId 长时间停留**：超过 10 秒窗口期不再动作（合同要求），需要新的窗口事件建立 epoch。
- **敏感包识别**：无法解析当前输入法/默认 HOME 时只保护已知系统包集合；解析结果缓存 5 秒。
- **文档同步**：`docs/build.md` 已在 t11 更新为当前状态，并把骨架期描述明确标记为历史记录，不再把占位当作当前事实。

## 11. 集成注意事项（t11）

- `:app` 需要调用 `AccessibilityDependencies.install(...)`；未调用时服务保持 ERROR/全关（不会假成功）。
- 合并 Manifest 后核验：服务声明、`BIND_ACCESSIBILITY_SERVICE` 权限、meta-data、`res/xml` 与 `res/values` 资源合入 `:app`。
- 界面文案区分“系统设置里已勾选”“服务已连接（connected）”“已发送跳过点击（actionAccepted）”，不要合成单一的绿色“已全面保护”。
- 本模块不提供任何配置写入或远程控制接口；配置仍只由宿主本地 Repository 写入。
- 候选 → 复核请求的映射集中在 `SkipRequestBuilder`（t11 提取），服务与 `CoreRuleToExecutionIntegrationTest` 共用；如需改字段映射，改这一处即可，不要在两处各写一遍。
