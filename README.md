# Anti-ads：摇一摇广告防护（Android 10 / API 29+）

Anti-ads 是一个**本地运行、无联网**的 Android 防护工具，用两种互补方式降低“摇一摇”开屏广告带来的误跳转：

| 模式 | 前提 | 能做什么 | 不能做什么 |
| --- | --- | --- | --- |
| 免 Root（无障碍） | 用户在系统设置中手动启用本应用的无障碍服务 | 在**明确启用**的目标应用窗口里，点击文案精确匹配“跳过”且位于右上角的按钮；执行前后有完整复核 | 不改动/不阻断传感器；自绘画面、游戏、不可访问 WebView、无语义节点、按钮不在右上角时不处理；无法保证比广告跳转更早 |
| Root / LSPosed（增强） | 用户已有兼容框架，已激活模块、勾选作用域并重启目标进程 | 在**被注入的目标进程内**丢弃所选传感器类型的 Java 分发回调 | 不覆盖 NDK/JNI 自建读取、`SensorDirectChannel`、厂商私有接口；不注销原监听器、不改写读数 |

两种模式可以同时使用，也各自独立关闭；**首次安装时全部关闭、应用名单为空**，只有用户显式勾选的应用才会被处理。

> **本项目不承诺覆盖所有应用、所有系统版本或所有广告形式。** 已实现范围、平台边界与未测项在
> [docs/requirements.md](docs/requirements.md)、[docs/install.md](docs/install.md) 与 [docs/qa-plan.md](docs/qa-plan.md) 中原样披露。

## 工程结构（五模块）

| 模块 | 类型 | 职责 |
| --- | --- | --- |
| `:core` | Kotlin/JVM 纯库 | 配置模型与校验、JSON 编解码、开关求交、传感器决策、保守广告规则（**唯一**判定实现） |
| `:app` | 宿主 APK（同时是 LSPosed 模块） | 中文界面、配置持久化、受控只读 Provider、状态事实展示 |
| `:accessibility` | Android 库 | 无障碍服务、窗口/节点快照、执行前复核、保守点击 |
| `:hook` | Android 库 | LSPosed 入口、Java 传感器分发 Hook、异步配置客户端（合入 `:app`） |
| `:probe` | 独立诊断 APK | 真实传感器计数、广告正反例样例页（QA 用，不申请 INTERNET） |

## 构建与测试

```bash
# 全量约定验证（五个模块单测 + 四个模块 lint + 两个 APK + 两个测试 APK）
bash ./gradlew --no-daemon :core:test :app:testDebugUnitTest :accessibility:testDebugUnitTest :hook:testDebugUnitTest :probe:testDebugUnitTest \
  :app:lintDebug :accessibility:lintDebug :hook:lintDebug :probe:lintDebug \
  :app:assembleDebug :probe:assembleDebug :app:assembleDebugAndroidTest :probe:assembleDebugAndroidTest

# 产物
#   app/build/outputs/apk/debug/app-debug.apk      （管理端，同时是 LSPosed 模块）
#   probe/build/outputs/apk/debug/probe-debug.apk  （诊断）
```

工具链版本、工具绝对路径、SDK 已装包与复现方法见 [docs/build.md](docs/build.md)（本仓库不携带 JDK/SDK/工具缓存）。

## 使用与边界

- 安装、授予无障碍权限、启用增强模式（框架作用域）、停用与卸载：[docs/install.md](docs/install.md)
- 隐私说明（不联网、不采集、不外传）：[docs/privacy.md](docs/privacy.md)
- 各模式实现细节：[docs/core.md](docs/core.md)、[docs/accessibility.md](docs/accessibility.md)、[docs/hook.md](docs/hook.md)、[docs/probe.md](docs/probe.md)
- 需求、架构与接口合同：[docs/requirements.md](docs/requirements.md)、[docs/architecture.md](docs/architecture.md)、[docs/contracts.md](docs/contracts.md)
- QA 场景矩阵与设备验证计划：[docs/qa-plan.md](docs/qa-plan.md)

## 隐私与安全设计

- 不申请 `INTERNET`，不上传包名列表、界面文本、传感器数据或使用统计。
- 配置只写入应用私有目录（AtomicFile + revision 比较后提交）；Provider 仅暴露两个只读 `call` 方法，
  每次调用都用 `Binder.getCallingUid()` 现场鉴权，只返回调用方自己那个包的最小策略，**没有任何远程写接口**。
- 无障碍只执行通过全部约束的 `ACTION_CLICK`；不做坐标点击、滑动、返回/Home、自动同意权限、支付或安装确认。
- 关闭总开关/模式开关/每包开关后，排队动作取消、执行前重读配置；Hook 侧最多 5 秒租约后放行未来回调。

## 许可证

[MIT](LICENSE)
