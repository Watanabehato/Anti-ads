# :hook 增强模式（LSPosed / Xposed Legacy API 82）实现说明

所有者：D（hook-engineer，t10）。配套文档：`docs/probe.md`、`docs/hook-probe-observability.md`。
**验证状态：源码与 JVM 单测在本机通过；真机 Root/LSPosed 注入、兼容性与设备实验一律未测**（本环境无 Root 设备、无 adb 设备）。任何"已生效"结论必须来自真机证据，不能引用本文当作设备验证。

## 1. 职责与产物

- `:hook` 是 Android Library（`com.antiads.hook`），**不生成可独立安装的模块 APK**；其代码、Manifest 元数据与 `assets/xposed_init` 由 library manifest/assets 合并进 `:app`，由管理端 APK 同时充当框架模块。
- 仅依赖 `:core`；Xposed API 是 `compileOnly`，不打包进 APK，也不在普通代码路径静态触碰框架类。
- 模块只做一件事：在**已注入的目标进程内**，按宿主下发的只读最小策略丢弃所选传感器类型的 Java 分发回调。不改写传感器数据、不伪造注册结果、不注销原监听器、不改系统/框架状态。

## 2. 入口与元数据

| 内容 | 位置 | 值 |
| --- | --- | --- |
| 入口类 | `AntiAdsHookEntry` | `com.antiads.hook.AntiAdsHookEntry` |
| 入口资产 | `hook/src/main/assets/xposed_init` | 一行该类名 |
| 模块声明 | `hook/src/main/AndroidManifest.xml` | `xposedmodule=true`、`xposeddescription`（中文，含限制说明）、`xposedminversion=82` |
| 建议作用域 | `hook/src/main/res/values/arrays.xml` | 只列 `com.antiads.probe`；其余由用户自行勾选 |

跳过规则（`HookSkipRules`，纯函数）：空包名、`android`、`com.android.systemui`、宿主 `com.antiads.app`、进程名 `system`（system_server）一律不安装。不要求用户把系统框架加入作用域。

## 3. 目标定位与形状验证

目标：`android.hardware.SystemSensorManager$SensorEventQueue.dispatchSensorEvent(int, float[], int, long)`，实例方法。

`SensorDispatchTarget.analyze()` 在安装前验证：类可加载、方法名与参数列表精确匹配、方法非静态非抽象、且存在可用的 handle→Sensor 路径。任一不满足 → `UNSUPPORTED`（UI 必须显示"读取成功但拦截不支持"），不假装已生效。

`SensorTypeResolver` 只通过反射读取进程内已存在的对象，**不假设 handle == sensor type**，也不依赖尚未初始化的 `SensorEvent.sensor`：

1. 队列实例自身的 handle→Sensor 映射（`mSensors`）；
2. 队列 → `mManager` → 其 handle→Sensor 映射（`mHandleToSensor`）。

解析出的 type 必须落在 1..40；任一步失败都返回"未解析"，此时**放行并累计受限计数**（客户端诊断行中的 type_resolve 失败计数；`HookProcessReport` 无可放字段，故不上报该项，这是已知限制）。

## 4. 决策、租约与线程（与 contracts 第 7 节一致）

- 全部计时使用 `SystemClock.elapsedRealtime()` 数值（含休眠时间），不使用墙钟。
- 回调快路径只做：计数 + `SensorPolicyEngine.decide` + 原子 CAS 节流 + 提交后台任务。**不做 Binder/磁盘/网络访问，不等待 Future。**
- 只有一个后台 worker（`SingleThreadWorker`，daemon 线程），最多一个 in-flight 读取；卡住的调用**绝不新建替代线程、绝不堆积请求**；刷新由传感器回调驱动（≥2000ms 节流），空闲进程不做任何轮询。
- 取得 Context 后发起一次初始读取；`requestStartedAtElapsedMs` 在请求开始前记录；回复合法且耗时 ≤1000ms 才发布，`expiresAt = requestStarted + 5000`（饱和加法防溢出，租约上限 5000ms）。
- 迟到回复（>1000ms）丢弃，旧缓存只活到自己的 `expires`；错误/拒绝/坏 JSON/包名不符/未知 schema/超长 payload 一律**立即清空缓存**。
- 关闭/排除目标/Provider 失联：最迟在原请求起点后 5000ms 全部放行；恢复只影响未来回调，不补发历史事件。
- 报告与刷新共用同一 worker，合并至 ≤1 条/5s；报告只用于展示"进程自报"，不是授权依据。

代码位置：状态机 `hook/src/main/kotlin/com/antiads/hook/internal/policy/HookPolicyClientCore.kt`；接线 `HookRuntime.kt`；传输 `ProviderPolicyTransport.kt`；上报 `ProviderHookReportSender.kt`。

## 5. 诊断与证据抓取

| 目的 | 命令 |
| --- | --- |
| 模块日志（状态变化、安装结果） | `adb logcat -s AntiAdsHook:V` |
| 机器可读报告行（≤1 条/5s，含计数/状态/revision） | `adb logcat -s AntiAdsHook.Diag:V` |
| 框架日志（入口级失败） | 框架管理器的日志页；入口只记录异常类名 |

诊断行字段：`package process token pid api install transport policy_revision observed dropped error delivered elapsed_realtime_ms`。
失败归类由 `transportState` 表达：`UNAVAILABLE`（找不到 Provider/传输异常/限流/内部错误）、`UNAUTHORIZED`（身份不可枚举或验证失败，含共享 UID/isolated 的 `lastErrorCode`）、`INVALID`（schema/字段/请求不合法）、`EXPIRED`（无新鲜结果，含迟到回复被丢弃）、`NEVER_READ`、`OK`。

## 6. 安装与生效步骤（用户/QA）

1. 设备已 Root 且装有兼容的 LSPosed 框架（版本范围见 `docs/requirements.md`；本项目未在真机验证任何组合）。
2. 安装管理端 APK（同 `com.antiads.app`），在框架中**激活模块**并勾选目标应用作用域（建议先只勾 `com.antiads.probe`）。
3. **重启目标应用**（首次安装 APK、激活模块、修改作用域、升级 Hook APK 之后都必须重启；必要时按框架说明重启设备）。管理端不会自动 force-stop 其他应用。
4. 打开产品三层开关（总开关 → 全局增强模式 → 该包增强模式）并选择传感器类型；目标进程会在 ≥2000ms 节流下异步读取。
5. 判定"是否生效"只看事实组合（contracts 第 7 节表格）：配置已保存 revision、目标读取成功（transport=OK）、installState、observedCallbacks>0、droppedCallbacks>0，以及 probe 侧独立对照实验；缺任一项都不能显示"已全面保护"。

## 7. 覆盖限制（必须如实披露）

不覆盖（不得声称已拦截）：NDK `ASensorManager`/自建读取、`SensorDirectChannel` 共享内存、厂商私有接口、其他进程/远端推断、非 `SystemSensorManager` 实现；隐藏类/方法与字段随 Android/OEM 变化，形状不符时只能 `UNSUPPORTED`。共享 UID 多包 UID 与 isolated 进程按 contracts 第 6 节拒绝读取（本模式对它们不生效并显示原因）。工作资料/分身、多用户需要单独验证。Android 15+/API35+ 需明确支持该版本的维护版框架，未测即未验证。

## 8. 验证命令与测试矩阵

```bash
bash ./gradlew --no-daemon :hook:testDebugUnitTest :hook:lintDebug :hook:assembleDebug
```

| 用例 | 测试类 | 断言要点 |
| --- | --- | --- |
| M1 租约边界 | `HookPolicyLeaseTest` | start=1000/reply=1500 → expires=6000；5999 丢弃、6000 放行 |
| M2 迟到回复 | `HookPolicyLeaseTest` | >1000ms 不发布且不延长旧租约 |
| M3 卡住请求 | `HookStuckTransportTest` | in-flight 恒 1、线程数 1、回调路径 <500ms、无策略全放行 |
| M4 刷新节流 | `HookRefreshThrottleTest` | t=0/1/999/1999/2000/2001 → 仅 2 次读取 |
| M5 到期即放行 | `HookPolicyLeaseTest` | now≥expires 必 ALLOW（含边界） |
| M6 错误清缓存 | `HookPolicyLeaseTest` | 拒绝/不可达立即清空并放行 |
| M7 无流量不轮询 | `HookRefreshThrottleTest` | 30s 无回调 → 访问次数不变 |
| M8 墙钟无关 | `HookPolicyLeaseTest` | 墙钟前进一年不影响判定；时钟回退不丢弃 |
| M9 形状/定位失败 | `SensorDispatchTargetTest`、`SensorTypeResolverTest` | 形状不符/无路径 → UNSUPPORTED；type 不可解析 → 放行 |
| M10 报告节流 | `HookReportSchedulingTest` | 首次立即、≤1 条/5s、编码可通过 Provider 校验 |
| M11 不做多余动作 | `SingleThreadWorkerTest` + 代码审查 | 只改 result 终止丢弃；不改 values/不伪造 Sensor/不注销 listener |

`:hook:lintDebug` 与 `:hook:assembleDebug` 属本模块验证：lint 0 error；唯一的 warning 是 `SensorDispatchHookInstaller` 的 `PrivateApi`。

**PrivateApi 警告的设计理由（t11 记录，未添加任何全局抑制）**：目标是 `android.hardware.SystemSensorManager$SensorEventQueue` 这一**非公开框架类**，Android 没有公开 API 能在目标进程内定位它；本方案刻意使用反射，并把一切不确定性显式化——
类/方法/字段形状不符即 `UNSUPPORTED`（界面必须显示“读取成功但拦截不支持”），type 解析失败即放行并计入受限计数，任何异常都不改变原始调用。
保留该 warning 而不加 `@SuppressLint`/lint baseline，是为了让审查者与后续维护者始终看到这一取舍；若未来 Android 提供公开替代路径，应优先改用公开 API。
**Xposed 注册本身、真机注入、冻结/恢复行为只能由设备实验证明**，本环境未执行。
