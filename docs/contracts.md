# Anti-ads v1 跨模块接口合同

状态：供骨架与四路并行开发使用的 v1 冻结合同。文件限定产品接口，不包含产品实现。本文代码块是公开类型/签名合同；省略方法体的声明由骨架提供可编译声明，正式逻辑由所有者实现。任何改名、类型/默认值/协议调整须先协调相关所有者，由顺序集成落地跨模块修改。

## 1. 固定标识、入口与依赖

| 内容 | 固定值 | 所有者 |
| --- | --- | --- |
| 管理 APK applicationId / namespace | com.antiads.app | B |
| 管理 Application / 首页 | com.antiads.app.AntiAdsApplication / com.antiads.app.MainActivity | B |
| core 包根 | com.antiads.core | A |
| 无障碍包根/服务 | com.antiads.accessibility / com.antiads.accessibility.AdSkipService | C |
| Hook 包根/框架入口 | com.antiads.hook / com.antiads.hook.AntiAdsHookEntry | D |
| probe APK / 首页 | com.antiads.probe / com.antiads.probe.MainActivity | D |
| probe 广告样例 Activity | com.antiads.probe.AdFixtureActivity | D |
| 配置 Provider 类 / authority | com.antiads.app.config.ConfigProvider / com.antiads.app.config | B |
| 配置 URI | content://com.antiads.app.config | core 常量，B/D 使用 |
| app 私有配置文件 | filesDir/protection-config-v1.json | B |
| 内置规则 ID | builtin.conservative.v1 | A/C/D 共用 |
| Hook 入口资产 | hook/src/main/assets/xposed_init，一行 com.antiads.hook.AntiAdsHookEntry | D；集成核验合并 |

:core 不可依赖 Android、Context、Binder、Xposed 或其他工程模块。:accessibility/:hook 仅依赖 :core；:app 依赖这三者；:probe 仅依赖 :core。app 不在常规代码中引用 AntiAdsHookEntry，以免无框架时类加载失败。无 debug applicationIdSuffix。

Manifest 责任：C 在 accessibility Manifest 声明服务、BIND_ACCESSIBILITY_SERVICE、exported=true 与 service XML；只由系统绑定。XML 设置 canRetrieveWindowContent=true、canPerformGestures=false、typeWindowStateChanged|typeWindowContentChanged、feedbackGeneric、notificationTimeout=100；需要窗口类型/viewId 时声明 flagRetrieveInteractiveWindows|flagReportViewIds，不开启触摸探索/按键过滤。B 声明 Provider exported=true、grantUriPermissions=false、directBootAware=false，**不设置会阻断目标读取的 signature 读权限**，鉴权在 call 内逐次执行；不暴露任何外部配置写接口。D 在 hook Manifest 提供 legacy xposedmodule=true、xposeddescription、xposedminversion=82 元数据，经 library manifest 合入 app；xposed_init 经 library assets 合入。建议作用域只列 probe；其余由用户主动在框架选择，不默认全选。app/probe 均不得申请 INTERNET，app 不申请 QUERY_ALL_PACKAGES。B 用 LAUNCHER intent queries 与 probe/可用管理器的明确包查询；probe 查询 Provider authority，实际未知第三方目标若受包可见性阻挡则读取失败并放行，不能靠修改目标 APK 绕过。

## 2. core 配置与序列化类型（A）

以下类型在 `com.antiads.core.config`；所有跨进程/持久化模型使用 kotlinx.serialization，枚举编码为下列大写名字，字段名与 JSON 键一致。不得序列化 Android 类型。默认列表/集合/map 为不可变快照；提交后不允许原地修改。

```kotlin
@Serializable
data class ProtectionConfig(
    val schemaVersion: Int = 1,
    val revision: Long = 0,
    val masterEnabled: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val hookEnabled: Boolean = false,
    val packages: Map<String, PackageConfig> = emptyMap()
)

@Serializable
data class PackageConfig(
    val accessibilityEnabled: Boolean = false,
    val hookEnabled: Boolean = false,
    val blockedSensorTypes: Set<Int> = setOf(1, 4, 9, 10, 11),
    val ruleIds: Set<String> = setOf("builtin.conservative.v1")
)

@Serializable
data class PackagePolicy(
    val schemaVersion: Int = 1,
    val revision: Long,
    val packageName: String,
    val hookEnabled: Boolean,
    val blockedSensorTypes: Set<Int>
)

data class CachedPackagePolicy(
    val policy: PackagePolicy,
    val requestStartedAtElapsedMs: Long,
    val expiresAtElapsedMs: Long
)

data class ValidationResult(val errors: List<String>) {
    val isValid: Boolean get() = errors.isEmpty()
}

object ConfigValidator {
    fun validate(config: ProtectionConfig): ValidationResult
    fun isValidPackageName(packageName: String): Boolean
}

object ConfigCodec {
    fun encodeConfig(value: ProtectionConfig): String
    fun decodeConfig(json: String): ProtectionConfig
    fun encodePolicy(value: PackagePolicy): String
    fun decodePolicy(json: String): PackagePolicy
    fun encodeHookReport(value: HookProcessReport): String
    fun decodeHookReport(json: String): HookProcessReport
}

object PolicyResolver {
    fun accessibilityEnabled(config: ProtectionConfig, packageName: String): Boolean
    fun packagePolicy(config: ProtectionConfig, packageName: String): PackagePolicy
}
```

HookProcessReport 定义于第 7 节，ConfigCodec 导入它。ConfigCodec 使用 encodeDefaults=true、ignoreUnknownKeys=true；未知 schema/未知枚举、错误字段类型、越界数据、无效包名抛 IllegalArgumentException，调用边界必须捕获并放行。无 schemaVersion 的外部 JSON 必须拒绝，不可利用构造默认值静默解释成 v1；骨架/实现先检查 JSON 对象中的版本键。持久化和 wire 均使用 UTF-8。

验证上限：config JSON 256 KiB、packages ≤500、包名 ≤255 字符且至少两个点分标识段（正则 `^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$`）；拒绝空白、通配符、斜杠。revision 为 0～Long.MAX_VALUE-1，递增溢出明确拒绝。blockedSensorTypes 只接受集合 {1,4,9,10,11} 的子集，空集合合法且不拦截；ruleIds 只接受内置 ID 或空集合，空集合不匹配。禁止 package key 为 android、com.android.systemui、com.antiads.app；用户不能通过手工 JSON 为宿主/系统 UI 开启操作。当前输入法、默认 HOME、权限管理/安装确认等敏感包在无障碍运行时另加保护，见第 5 节。

PolicyResolver 的结果：只有 masterEnabled && 全局对应模式 && 当前包对应模式才为 true；包不存在视为全关。关闭 Hook 时返回 hookEnabled=false 且 blockedSensorTypes=emptySet()，保留真实 config.revision。PackagePolicy 无完整应用名单、无无障碍规则、无全局配置、无其他包信息。

### 持久化示例

```json
{
  "schemaVersion": 1,
  "revision": 7,
  "masterEnabled": true,
  "accessibilityEnabled": true,
  "hookEnabled": true,
  "packages": {
    "com.antiads.probe": {
      "accessibilityEnabled": true,
      "hookEnabled": true,
      "blockedSensorTypes": [1, 4, 9, 10, 11],
      "ruleIds": ["builtin.conservative.v1"]
    }
  }
}
```

此为用户明确启用后的样例，不是出厂默认值。初始配置是 schemaVersion=1/revision=0/所有开关 false/packages={}。UI 新建包配置也保持两个模式开关 false。

## 3. 配置存储、订阅与宿主接线（A 声明、B 实现、C 消费）

```kotlin
// package com.antiads.core.config
fun interface Subscription { fun close() }

enum class ConfigStorageState { READY, DEFAULTS_NO_FILE, RECOVERED_CORRUPT, IO_ERROR }
data class ConfigHealth(val state: ConfigStorageState, val errorCode: String? = null)

sealed interface ConfigWriteResult {
    data class Saved(val config: ProtectionConfig) : ConfigWriteResult
    data class Rejected(val reason: String) : ConfigWriteResult
}

interface ConfigRepository {
    fun snapshot(): ProtectionConfig
    fun health(): ConfigHealth
    fun write(config: ProtectionConfig, expectedRevision: Long): ConfigWriteResult
    fun observe(listener: (ProtectionConfig) -> Unit): Subscription
}

// package com.antiads.app.config, B 实现
class AppConfigRepository private constructor(/* implementation owned by B */) : ConfigRepository {
    companion object { fun get(context: Context): AppConfigRepository }
}

// package com.antiads.accessibility, C 实现
object AccessibilityDependencies {
    fun install(repository: ConfigRepository)
}
```

snapshot()/health() 线程安全、非阻塞、纯内存；Repository 初次初始化加载可发生于 Application/Provider 创建阶段，但 UI 的持续写入绝不阻塞主线程。write 只允许 app 内部调用：串行锁内比较 expectedRevision == 当前 revision 且输入 config.revision == expectedRevision，验证后以 Android AtomicFile 或等效原子临时文件替换提交 nextRevision=expectedRevision+1；落盘成功才发布新快照并返回 Saved。失败返回 Rejected（CONFLICT、INVALID、IO_ERROR、REVISION_EXHAUSTED），内存仍保持此前成功配置，不假称已保存。UI 冲突时读新快照并提示重新操作，不自动覆盖另一更改。

默认无文件返回全关 + DEFAULTS_NO_FILE；坏 JSON、未知版本或读取失败返回全关 + RECOVERED_CORRUPT/IO_ERROR，并在 UI 展示中文原因，不沿用损坏配置的任何 enable 值。失败不得输出原始配置/界面文本到日志。明确保存可替换坏文件，保存成功后 health=READY；无需隐式自动迁移未知版本。

observe 注册后立即回调一次当前快照，后续按成功 revision 顺序通知；回调可能来自写线程，消费者自行投递主线程，不能在持锁状态调用 listener。close 幂等、取消后不再开始新回调；消费者还需 lifecycle/generation 检查处理已投递消息。listener 抛错应隔离，不能使已落盘事务失败。

`AntiAdsApplication.onCreate()` 调用 `AccessibilityDependencies.install(AppConfigRepository.get(this))`。Provider 通过相同 get(context) 自行惰性初始化，处理 Provider 在 Application.onCreate 之前启动的正常时序。不得通过 Activity 静态引用提供依赖；service 未注入依赖时处于 ERROR/全关，禁止空依赖返回假成功。

## 4. 传感器策略接口（A）

```kotlin
// package com.antiads.core.policy
enum class SensorAction { ALLOW, DROP_CALLBACK }
data class SensorDecision(val action: SensorAction, val reason: String)

object SensorPolicyEngine {
    fun decide(
        packageName: String,
        sensorType: Int,
        cachedPolicy: CachedPackagePolicy?,
        nowElapsedMs: Long
    ): SensorDecision
}
```

返回 DROP_CALLBACK 的必要条件同时满足：有快照、schemaVersion=1、快照包名与当前 loadPackage 包名一致、hookEnabled=true、类型属于 blockedSensorTypes、requestStartedAtElapsedMs ≤ nowElapsedMs < expiresAtElapsedMs、租约时长在 1..5000ms。其他情况 ALLOW。未知传感器类型、不可靠类型解析结果、负数/未来时间戳/时钟回退、未知 schema 全部 ALLOW。reason 是稳定英文码（DISABLED、NO_POLICY、EXPIRED、PACKAGE_MISMATCH、TYPE_NOT_SELECTED、INVALID_POLICY、BLOCK_SELECTED_TYPE），仅用于可解释状态与测试，不依赖异常文本。

core 不读取系统时钟，调用者显式传入 Android SystemClock.elapsedRealtime()；单测使用数值时钟精确检查 4999/5000ms 边界。不要用 wall clock/currentTimeMillis 控制租约、节流或窗口年龄。

## 5. 无障碍规则输入/输出与执行（A/C）

```kotlin
// package com.antiads.core.rules
data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)
data class UiNode(
    val nodeId: Int,
    val text: String,
    val contentDescription: String,
    val viewId: String?,
    val clickable: Boolean,
    val enabled: Boolean,
    val visible: Boolean,
    val editable: Boolean,
    val password: Boolean,
    val bounds: Bounds
)
data class AdWindowSnapshot(
    val packageName: String,
    val windowId: Int,
    val capturedAtElapsedMs: Long,
    val foregroundSinceElapsedMs: Long,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val isApplicationWindow: Boolean,
    val keyguardLocked: Boolean,
    val sensitivePackage: Boolean,
    val traversalComplete: Boolean,
    val nodes: List<UiNode>
)
data class SkipCandidate(val nodeId: Int, val ruleId: String, val reason: String)
interface AdRuleEngine {
    fun evaluate(config: ProtectionConfig, window: AdWindowSnapshot): List<SkipCandidate>
}
class ConservativeAdRuleEngine : AdRuleEngine
```

nodeId 仅是当前快照内从 0 开始的唯一索引，不是持久 view ID，不允许跨快照复用。只有服务在同一次遍历中保存短时 nodeId→AccessibilityNodeInfo 映射。纯 core 只返回候选，不执行动作，不读取 Android 节点。

内置规则 v1 的全部必要条件：

- PolicyResolver.accessibilityEnabled 为 true，ruleIds 包含内置规则，包名合法且非固定拒绝包；window 为当前 TYPE_APPLICATION、非锁屏、非敏感包、尺寸正常、遍历完整。
- capturedAtElapsedMs - foregroundSinceElapsedMs 在 0..10000ms；仅处理应用/窗口进入后的短窗口，持续 content changed 不能延长该窗口。
- 任意节点 editable/password 为 true 则整个窗口不处理，包括未被选择的输入节点。
- 存在独立且可见的广告上下文节点：文本或 description 经 trim/小写规范化后严格等于“广告”“ad”“advertisement”之一。仅有“跳过”不足以判断广告；“摇一摇”单独不构成 v1 自动点击授权。
- 候选节点本身 visible/enabled/clickable，文案（text 或 description 任一）完整匹配下列正则之一；不向父容器递归点击。
- 中文正则：`^跳过(?:\s*[0-9]{1,2}\s*(?:秒|s)?)?$` 或 `^[0-9]{1,2}\s*(?:秒|s)?\s*跳过$`；英文忽略大小写：`^skip(?:\s+ads?)?(?:\s+[0-9]{1,2}\s*s)?$`。限制文案 ≤40 字符，先 trim；“点击领取”“继续”“允许”“跳过并购买”等均不匹配。
- bounds 完全位于屏幕内且有正面积；中心 X≥屏幕宽 65%、中心 Y≤屏幕高 25%，节点面积≤屏幕面积 12%。广告上下文必须来自与候选不同的 nodeId。乘法用 Long 防溢出。
- 按 top 升序、right 降序、nodeId 升序选一个；返回列表最多 1 项，reason 固定 EXPLICIT_AD_SKIP。

C 的执行约束：

1. 只监听 TYPE_WINDOW_STATE_CHANGED/TYPE_WINDOW_CONTENT_CHANGED。前台包或 windowId 变化时新建 foreground epoch，并取消旧任务；相同窗口反复 content/state 事件不得重置 epoch。首次连接时可把当前窗口作为新 epoch，但仍遵守全部过滤。
2. KeyguardManager 实时检查锁屏。sensitivePackage 至少含宿主、android、SystemUI、当前启用输入法包、默认 HOME、系统权限控制器及安装器；能用 PackageManager 得到的角色/resolve 结果优先，无法确定窗口身份则跳过。无需扩大 Package Visibility 权限。
3. 在服务主线程延迟合并事件，至少间隔 250ms 扫描；单次最多 300 节点、深度 20，遍历耗时预算 8ms，达到任一上限令 traversalComplete=false，本次不点击。不得只扫描按钮后忽略未遍历的密码/编辑节点。文本快照每字段上限 256 字符，不存储界面全文。
4. 候选执行前重新核验依赖已安装、服务连接、开关/配置 revision 未变化、当前 active root 包/窗口与快照一致、锁屏状态、节点 refresh 仍可见/可点击/同文案。快照超过 500ms 丢弃，重新匹配；不要缓存 AccessibilityNodeInfo 跨事件长期使用。
5. 同 foreground epoch 只尝试一次 ACTION_CLICK，失败也不紧密重试；同包两次动作至少间隔 2000ms。performAction(true) 只表示系统接收动作，不等于广告真正消失，UI 文案为“已发送跳过点击”；探针页面自己的状态才证明页面变化。
6. 禁止 dispatchGesture、GLOBAL_ACTION_BACK/HOME、自动点击父节点、模糊包含匹配和广泛扫屏。停用/撤权立即取消所有延迟动作，执行前再次核验。
7. onServiceConnected 设置连接事实并订阅配置；onUnbind/onDestroy 清除 connected、取消任务并 close 订阅；onInterrupt 取消待执行任务并记中断，不把它误当永久断开。节点引用在一次处理结束释放，旧 Android 版本适当 recycle；严禁 Activity/Context 泄漏。

### 无障碍状态（core 定义、C 发布、B 读取）

```kotlin
// package com.antiads.core.status
enum class AccessibilityPhase { DISCONNECTED, IDLE, WATCHING, PAUSED, ERROR }
data class SkipActionRecord(
    val packageName: String,
    val ruleId: String,
    val occurredAtElapsedMs: Long,
    val actionAccepted: Boolean
)
data class AccessibilityRuntimeState(
    val connected: Boolean = false,
    val phase: AccessibilityPhase = AccessibilityPhase.DISCONNECTED,
    val activePackage: String? = null,
    val lastAction: SkipActionRecord? = null,
    val lastErrorCode: String? = null
)

// package com.antiads.accessibility
object AccessibilityRuntime {
    fun state(): AccessibilityRuntimeState
    fun observe(listener: (AccessibilityRuntimeState) -> Unit): Subscription
}
```

状态线程安全；observe 的初次通知/取消语义同 ConfigRepository，UI 投递主线程。B 每次 onResume 通过 AccessibilityManager.enabledAccessibilityServiceList 核查 AdSkipService 是否在系统启用列表；“设置已启用”与 state.connected 分开显示。连接但总开关关闭为 PAUSED；已连接无目标为 IDLE；正在合格目标观察为 WATCHING。纯配置不能令 connected=true。只保留最近一次规则/包/时间/动作返回值，不记录屏幕文本、输入值或节点完整树。

## 6. 跨进程 Provider 协议与鉴权（core 常量、B 服务端、D 客户端）

```kotlin
// package com.antiads.core.protocol
object ConfigProtocol {
    const val AUTHORITY = "com.antiads.app.config"
    const val URI = "content://com.antiads.app.config"
    const val GET_POLICY = "get_policy_v1"
    const val REPORT_HOOK = "report_hook_v1"
    const val KEY_OK = "ok"
    const val KEY_ERROR = "error"
    const val KEY_PAYLOAD = "payload"
    const val REFRESH_INTERVAL_MS = 2000L
    const val LEASE_MS = 5000L
    const val READ_DEADLINE_MS = 1000L
    const val REPORT_INTERVAL_MS = 5000L
    const val REPORT_STALE_MS = 15000L
}
```

调用形式为 `ContentResolver.call(Uri.parse(ConfigProtocol.URI), method, packageName, extras)`。arg 是请求身份提示，**不是身份依据**。Bundle 只使用 primitive/String，不传 Serializable/Parcelable 自定义对象。成功返回 ok=true、payload=JSON（report 接收成功无需 payload）；失败返回 ok=false、error=固定错误码。unknown method、query/insert/update/delete/openFile/bulkInsert 均拒绝；getType 可返回 null，不返回数据。没有 get_all_config、write_config、更新某包等远程接口，连宿主写入也走本地 Repository，不走 exported Provider。

鉴权顺序不可调换：

1. 在每次 call 入口捕获 `Binder.getCallingUid()` 与 `Binder.getCallingPid()`；在验证前不能 clearCallingIdentity，也不能信任 getCallingPackage/arg/payload 自报值。
2. 用 PackageManager.getPackagesForUid(capturedUid) 查真实包集合并 distinct；通过 `UserHandle.getUserHandleForUid(capturedUid) == Process.myUserHandle()` 判断同用户（API29 可用的公开 API；不能调用隐藏 getUserId），拒绝系统/无包/隔离 UID。异常视为拒绝。
3. v1 **只支持真实包集合恰好一个**的 UID，且它与 arg 完全相等、为合法非拒绝包名。共享 UID 多包一律返回 SHARED_UID_UNSUPPORTED；不从 arg 随选一个，不把多个包的允许规则合并。无法枚举/包可见性缺失则 UNAUTHORIZED，不猜测身份。应用内自定义多进程共用单包 UID 可以读取；isolated UID 没有稳定包归属则 ISOLATED_OR_UNKNOWN_UID。
4. 验证完成后如需 clearCallingIdentity 必须 finally restoreCallingIdentity；后续只使用已捕获的 UID/PID/验证包名。不得在身份清除后重新取得调用 UID 作为授权依据。
5. GET_POLICY 用服务器本地当前 ConfigRepository.snapshot() 计算**仅此已验证包**的最小 PackagePolicy。无配置项仍返回 hookEnabled=false 的策略，方便关闭/删除后恢复。不得返回其他包存在性/名单。
6. REPORT_HOOK 对 JSON packageName 再与已验证包比对，pid 由服务端强制覆盖为 capturedPid；报告不写配置、不改变授权、不改系统/框架状态。只接受正 pid、进程名≤160 字符、processToken 为 36 字符 UUID、合理非负计数、schema=1。

同 UID 是 Android 的安全边界，无法对同 UID 内真实 Hook 与应用自己发起的 call 做可靠鉴别；报告因此只能称“目标进程自报”。本产品不提供与恶意同 UID 对手隔离的秘密，也不把报告作为授权依据。LSPosed 注入不授予目标进程宿主 UID。

GET_POLICY 回复 JSON ≤4096 bytes，REPORT 请求 ≤4096 bytes；先限制长度再解码。Provider 不执行 su、不进行网络、不遍历磁盘目录。每 UID GET ≤5 次/秒，报告≤1次/秒；每包最多保留8个进程、全局最多128条报告，LRU 淘汰，内存存储；限流返回 RATE_LIMITED，客户端不立即重试。读取策略直接使用线程安全内存快照，不等待 UI。Provider 可由 Android 在目标进程访问时启动宿主；用户强制停止/OEM 限制/工作资料等不可达时由客户端放行。

示例请求/回复：

```text
call(URI, "get_policy_v1", "com.antiads.probe", null)
=> Bundle(ok=true, payload=<下列 UTF-8 JSON 字符串>)
```

```json
{"schemaVersion":1,"revision":7,"packageName":"com.antiads.probe","hookEnabled":true,"blockedSensorTypes":[1,4,9,10,11]}
```

规范错误码：UNAUTHORIZED、SHARED_UID_UNSUPPORTED、ISOLATED_OR_UNKNOWN_UID、INVALID_REQUEST、UNSUPPORTED_VERSION、RATE_LIMITED、INTERNAL_ERROR。失败无 payload；Binder 抛 SecurityException、找不到 Provider、null Bundle、未知 ok/error 组合、解码异常都视为读取失败。report 不接受未知字段造成额外动作；未知 schema 直接拒绝。

## 7. Hook 安装、配置租约与真实状态（D）

使用 Legacy `IXposedHookLoadPackage`/API82。入口明确跳过宿主、android/SystemUI 与 system_server；不要求用户把系统框架加入作用域。模块在 app APK，:hook 本身不生成可安装独立模块 APK。

### 安装与初始化

- 在 loadPackage 保存框架提供的 packageName/processName，禁止以自报 arg 代替。只定位支持的 `android.hardware.SystemSensorManager$SensorEventQueue.dispatchSensorEvent(int, float[], int, long)` 实例方法，在 before-hook 根据 handle 解析实际 Sensor.type；典型解析是 thisObject 的 mManager.mHandleToSensor。必须验证类/字段/类型/方法形状，不假设 handle==sensor type，不依赖尚未初始化的 SensorEvent.sensor。
- 能定位并成功注册 before-hook 才记录 INSTALLED；失败记 UNSUPPORTED/ERROR。局部异常原调用继续。不能为追求覆盖同时修改系统全局服务或钩所有返回值。
- 只有 SensorPolicyEngine 返回 DROP_CALLBACK 时才通过 Xposed 终止该 void 分发调用；其余完全交还原实现。不修改 values 数组、不构造假 Sensor、不强行返回 registerListener=true/false、不注销原 listener。
- loadPackage 常无 Application Context。通过 Application.attach(Context) after-hook 获取目标应用 Context，确认包身份后建立客户端与工作线程；尚无 Context 时所有回调放行，状态 WAITING_CONTEXT。若无 attach/当前 Application 都不可用则保持放行，不跨进程借宿主 Context。
- 首次安装 APK、在框架激活模块/改变作用域/升级 Hook APK 后，需要用户从系统停止并重新打开目标应用；必要时按框架说明重启设备。管理端不自动 force-stop 其他包，也不据配置保存推断已重启/注入。
- 单进程只安装一组 Hook 与一个客户端，用原子 guard 防重复。多进程分别读取、报告，processToken 每次进程实例生成新 UUID。共享 UID、多用户与 isolated 进程的限制按第6节执行。

### 租约、线程与关闭传播

1. 缓存初始为 null，重启不从磁盘恢复。所有计时统一用 SystemClock.elapsedRealtime()，包含休眠经过的时间；不能用 uptimeMillis/墙上时钟延长租约。
2. 取得 Context 后发起一次异步 GET；活跃 sensor callback 每次只做内存检查，距上次发起请求≥2000ms 时用 compareAndSet 调度一个后台请求。无需监听传感器的闲置进程不轮询；新回调到来时唤醒刷新。
3. 只有一个串行后台读取线程、最多一个 in-flight 请求，队列最多一个；sensor callback 不等待 Future、不 await、不同步 Binder/磁盘/网络，不在全局锁里操作 Provider。解析 Sensor.type 仅访问已验证的进程内对象，定位失败当次放行并记录受限计数。
4. 在后台请求**开始前**记录 requestStartedAtElapsedMs。仅当回复授权/结构/包名/schema 全部合法，且返回时耗时≤1000ms 时发布 CachedPackagePolicy；expiresAtElapsedMs = requestStartedAtElapsedMs + 5000。不能使用回复到达时间重新起算，否则延迟旧回复会延长关闭时间。防止加法溢出。
5. Android Binder call 无可保证取消的通用超时。逻辑期限是1000ms，迟到回复丢弃；若调用卡住，绝不新建无限替代线程/无限堆积请求。现有租约在回调路径独立过期；等待中的请求不能阻止放行。错误/拒绝/坏JSON一旦确认立即清空缓存；未完成请求的旧有效缓存仅能存活到原租约期限。
6. 从成功保存“关闭/删除包/空集合”时刻算，任何此前快照最多在原请求起点后5000ms有效，因此所有后续 Java 回调最迟在关闭后5000ms变为 ALLOW（边界时刻 now>=expires 必须放行）；活跃/通畅的正常刷新约2000ms接收关闭，读取耗时还应计入，正常上界约3000ms。没有回调时没有事件被丢弃。
7. 关闭恢复未来 Java 回调，不补发历史事件，也不保证应用因缺失事件自行停止注册后无需重启。卸载宿主/Provider 失联同样至多5秒租约后放行；完全移除注入要目标重启。禁用 LSPosed 模块但不改本产品开关时，已注入进程中的代码可能继续工作直到进程重启，UI 必须提示先关产品开关。
8. 报告与刷新共用有界后台调度，报告合并至最多每5秒一条，首次能发送时立即报告。不得每个 sensor callback 打日志/调用Provider。进程终止由系统清理；可显式关闭的客户端 shutdown executor，不能持有 Activity、服务或静态 target UI 节点。

### 状态模型

```kotlin
// package com.antiads.core.status
@Serializable
enum class HookInstallState { WAITING_CONTEXT, INSTALLED, UNSUPPORTED, ERROR }
@Serializable
enum class ConfigTransportState { NEVER_READ, OK, UNAVAILABLE, UNAUTHORIZED, INVALID, EXPIRED }

@Serializable
data class HookProcessReport(
    val schemaVersion: Int = 1,
    val packageName: String,
    val processName: String,
    val processToken: String,
    val pid: Int,
    val apiLevel: Int,
    val installState: HookInstallState,
    val transportState: ConfigTransportState,
    val policyRevision: Long?,
    val observedCallbacks: Long,
    val droppedCallbacks: Long,
    val lastErrorCode: String? = null
)
data class ReceivedHookReport(
    val report: HookProcessReport,
    val receivedAtElapsedMs: Long
)

// package com.antiads.app.config, B 实现，UI 消费
object RuntimeReportStore {
    fun snapshot(): List<ReceivedHookReport>
}
```

RuntimeReportStore 由 Provider 验证后内部接收，snapshot 线程安全；只保留内存、不在重启后还原“在线”。receivedAtElapsedMs 由服务端自己的 elapsedRealtime 戳记，不信任请求时间。B 在页面可见期间每秒刷新显示即可，离开页面停止定时器；记录超过15000ms显示“报告已过期”，不能解释为一定没注入（可能没有传感器流量）。counter 非负且 droppedCallbacks≤observedCallbacks，不接受完整日志/传感器读数/堆栈；每次进程实例计数独立，进程标识至少用已验证包+捕获pid+token。

必须分别显示以下事实，禁止合成一个没有证据的绿色“已全面保护”：

| 事实 | 证据 | 可显示文案 |
| --- | --- | --- |
| 配置已保存 | ConfigWriteResult.Saved + revision | 已保存配置，版本 N |
| 目标读取成功 | 新鲜 report.transportState=OK + policyRevision | 目标已读取配置 N（进程自报） |
| Hook 已安装 | 新鲜 report.installState=INSTALLED | Java 回调 Hook 已安装（进程自报） |
| 事件经过 Hook | observedCallbacks >0 | 已观察到 N 次 Java 回调 |
| 实际执行丢弃 | droppedCallbacks >0 | 累计丢弃 N 次回调（进程自报） |
| probe 实验成功 | 独立计数基线/开启/关闭对照与设备记录 | 此设备/类型的 Java 路径已实测 |

没有报告的状态是“未观察到目标注入/尚未报告”，不是 active=true；有读取但 installState=UNSUPPORTED 必须显示读取成功且拦截不支持。Root 是否存在、框架管理器是否安装、作用域是否配置无可靠 API 时显示“未知/请在框架确认”；不能以这些推导有效拦截。API35+ 管理/无障碍兼容目标仍成立，增强模式显示“需兼容框架；本组合未验证”直到获得版本证据。

## 8. probe 诊断接口与可重复场景（D/QA）

MainActivity 使用实际 SensorManager、SensorEventListener；每类型列出是否存在、registerListener 返回值、累计回调数、最近回调 elapsed 时间与当前启停状态。默认采样 SENSOR_DELAY_NORMAL，开始/停止为显式按钮，onPause 注销所有监听并停止 UI timer；onResume 不偷偷重启采样。一次实验显示最多5种配置支持类型，并提供至少一种设备可用的未选类型作对照；如果设备无该类型就明确无对照，不伪造0为成功。

AdFixtureActivity 支持 Intent String extra `scenario`：`ad_positive`、`no_ad_label`、`non_clickable`、`bottom_button`、`editable_window`；未知值默认 no_ad_label。页面公开给 QA adb 启动，只产生本地测试内容，无外部写配置/敏感动作。正例有独立“广告”标签、右上角可点击“跳过 5 秒”、点击后标签变“已跳过”，位置按可用窗口的 WindowInsets 布置且符合 core 的屏幕坐标过滤；其他场景分别缺上下文/不可点击/按钮在下部/有 EditText。测试页面不注册传感器，主传感器页不假装广告。

示例设备步骤（命令逐条执行）：

```bash
adb shell am start -n com.antiads.probe/.AdFixtureActivity --es scenario ad_positive
adb shell am start -n com.antiads.probe/.AdFixtureActivity --es scenario no_ad_label
adb shell am start -n com.antiads.probe/.AdFixtureActivity --es scenario editable_window
```

QA 无障碍正例先打开总开关/全局无障碍/每包无障碍/系统授权；每次重新进入独立 Activity 窗口并记录按钮实际变化。反例不得出现 actionAccepted=true。Hook 实验先不启用防护采样≥10秒获得非零基线，再激活框架与 probe 作用域、重启 probe、打开产品三层开关与类型选择；采样≥10秒观察所选/未选类型，再关闭总开关，保留同一次注册等待5秒后观察未来回调恢复。对无回调基线、无设备传感器、app自行注销必须标为不可判定。

Hook mock 单测必须涵盖 requestStart=1000、reply=1500、expires=6000；now=5999 可丢弃、6000 必须放行；reply=2001 超过1000ms期限不得发布；wall clock 改变不影响结果；损坏JSON/宿主不可达立即清空或按既有租约到期放行。Provider 测试覆盖单包匹配、arg伪造、shared UID 多包拒绝、无包/isolated拒绝、clearCallingIdentity 前授权、外部 mutation 拒绝与限流。测试不能把 Provider 报告里的自报计数当独立真实性证明。

## 9. 文件所有者与集成清单

- A/core：config 下模型/ConfigCodec/ConfigValidator/PolicyResolver/ConfigRepository；policy 下 SensorPolicyEngine；rules 下 DTO/ConservativeAdRuleEngine；protocol 下 ConfigProtocol；status 下两种模式状态。骨架先给出全部公共声明与 serialization 插件，后续 A 只在 core/ 实现。
- B/app：AntiAdsApplication、MainActivity、config/AppConfigRepository、ConfigProvider、RuntimeReportStore；权限/应用列表/中文资源与本模块 Manifest。B 不定义另一份 ConfigCodec，不在 app 重写核心规则。
- C/accessibility：AdSkipService、AccessibilityDependencies、AccessibilityRuntime、服务配置 XML/Manifest；通过 core DTO 对接，不依赖 app 存储实现。
- D/hook：AntiAdsHookEntry、内部异步配置客户端、已验证 Java 分发 Hook、入口资产/元数据；D/probe：MainActivity、AdFixtureActivity、传感器计数与样例页面。内部 helper 命名可自行决定。
- 根 settings/build.gradle.kts、gradle.properties、wrapper、各模块 build.gradle.kts 的初始版本及 CI 由骨架 A 统一建立；并行期间共享文件变更先登记，顺序集成 A 落地。

顺序集成必须核查：app Application 注入服务依赖；Provider 启动先后次序；合并服务声明/权限/queries；module metadata 与 xposed_init 的最终 APK 位置；compileOnly Xposed 类未打包；app/probe applicationId 与 authority；中文状态不冒充成功；两个 APK 的权限；关闭与租约边界；完整 Gradle 命令。不得为了让依赖编译成功静默改接口或把真实状态降为占位常量。验证命令、所有权、版本矩阵分别引用 architecture 与 requirements；任何未测设备组合保留未测试标签。
