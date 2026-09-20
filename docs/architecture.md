# Anti-ads 五模块架构与协作边界

本文件冻结工程布局与实现所有权，数据与方法以 `contracts.md` 为准。应用兼容目标、构建 SDK 和增强模式框架兼容性是不同维度：应用目标为 API29+；本次固定 compileSdk/targetSdk 35；LSPosed 原始官方范围与维护分支需分别列证据；实际测试范围只按 QA 的真实设备记录填写。35 是选定构建基线，不称“当前最新”。

## 1. 工具链锁定

| 项目 | 固定值 | 说明 |
| --- | --- | --- |
| JDK | 17 LTS，源码/字节码目标 17 | 使用官方发行版；记录具体 vendor 与 patch |
| Gradle Wrapper | 8.9 | wrapper JAR、脚本入库；distributionSha256Sum 核验官方分发校验值 |
| Android Gradle Plugin | 8.7.2 | 与 Gradle8.9/JDK17/API35 搭配，不自动升级 |
| Kotlin JVM/Android/serialization 插件 | 2.1.10 | 三处版本一致；未采用 KMP |
| kotlinx-serialization-json | 1.7.3 | core 纯 Kotlin JSON，其他模块沿用，不各自实现协议 |
| compileSdk / targetSdk | 35 / 35 | app、probe 的 target 一致；Android 库使用 compile35/min29 |
| minSdk | 29 | 所有 Android 模块一致 |
| Android Build Tools | 35.0.0 | 由 SDK Manager 从官方源安装；骨架记录安装版本 |
| Xposed API | de.robv.android.xposed:api:82 | 仅 hook 的 compileOnly，仓库 https://api.xposed.info/；不把框架类打包进 APK |
| JVM 测试 | JUnit 4.13.2 | core 纯 JVM 单测，不引入 Android |
| UI | 原生 Android View + Activity | 避免 UI 构建插件耦合；需处理 target35 WindowInsets |

官方参考为 AGP 8.7 发布说明 https://developer.android.com/build/releases/past-releases/agp-8-7-0-release-notes 与 Kotlin 兼容表 https://kotlinlang.org/docs/gradle-configure-project.html 。本环境抓取这些 URL、LSPosed README 和 Android 文档返回 `resolves to a non-public IP address`，部分搜索请求返回 `TinyFish ... fetch failed`；已有 Kotlin 官方索引说明 2.1.0～2.1.10 支持 Gradle8.7～8.10（KMP 特定例外不涉及本工程），已有 LSPosed 官方索引说明 Android8.1～14 与 Magisk 安装前提。未获得这些页面全文，不能将版本兼容性标为“本机已验证”。上述固定组合由骨架阶段以真实依赖解析和编译完成工程验证；依赖不可取得时报告阻塞，不下载来源不明的替代框架包、不静默改版本。

初始 PATH 未发现 Java/Gradle/adb/SDK，相关环境变量为空。开发 A 准备工具链与 `local.properties`，此文件不入库。仓库不携带完整 JDK/SDK/Gradle 缓存；构建脚本不得依赖个人绝对路径。

## 2. 模块依赖与产物

```text
:app (com.antiads.app, 管理 APK / LSPosed 模块 APK)
  ├── implementation(:core)
  ├── implementation(:accessibility) ── implementation(:core)
  └── implementation(:hook) ────────── implementation(:core)
                                     compileOnly(Xposed API82)
:probe (com.antiads.probe, 独立诊断 APK) ── implementation(:core)
:core (com.antiads.core, Kotlin/JVM) ── serialization-json
```

| 模块 | namespace/代码包根 | 职责与产物 |
| --- | --- | --- |
| core | com.antiads.core | 配置、JSON、验证、纯函数规则、传感器策略、共享接口；JAR |
| app | com.antiads.app | 中文界面、应用列表、原子配置存储、只读策略 Provider、权限引导、状态聚合；app-debug.apk |
| accessibility | com.antiads.accessibility | 无障碍服务、窗口/节点快照、保守点击执行、服务状态；AAR 合入 app |
| hook | com.antiads.hook | Legacy Xposed 入口、Java 事件分发 Hook、异步配置客户端、进程诊断；AAR/入口资产合入 app |
| probe | com.antiads.probe | 无联网传感器计数、广告正反例测试页面、可视恢复验证；probe-debug.apk |

app 与 probe applicationId 固定，无 debug applicationIdSuffix；Provider authority 因而稳定为 `com.antiads.app.config`。probe 不依赖 app/accessibility/hook，不作为框架模块。hook 的 Xposed 入口只由框架加载；普通 app 进程不得直接构造或静态触碰依赖 Xposed 类的类型。

## 3. 数据流与生命周期

1. `AntiAdsApplication.onCreate()` 创建 AppConfigRepository，加载 app 私有配置，向 AccessibilityDependencies 安装 ConfigRepository。Provider 可能先于 Application.onCreate 初始化，故 Repository 采用应用 Context 的惰性单例，不依赖 Activity/已执行的 Application 回调。
2. UI 在后台提交版本检查的配置写入，持久化成功后发布不可变快照与 revision。无障碍订阅同一实例；UI 在主线程渲染真实快照。
3. 无障碍服务仅在系统连接后监听相关窗口事件，有限遍历生成 core 快照；core 返回可审计候选；执行前复核包/窗口/配置/节点。服务不引用 app 类。
4. LSPosed loadPackage 阶段只定位已知方法并注册 Hook。没有 Context 时策略为未初始化/放行；在 `Application.attach(Context)` 的 after-hook 取得目标 Context 后启动配置读取。进程已有 Application 时可使用经过空值检查的当前实例作为补充；不得假设所有进程都会 attach。
5. Hook 工作线程以目标 UID 访问 app Provider，仅获取本包最小策略并上报自报诊断。Sensor 回调仅使用内存不可变快照、单调时钟、原子计数；绝不执行 Binder、磁盘或网络访问。
6. probe 用原生 SensorManager 订阅低频回调，并显示注册结果、事件计数/时间与生命周期。其广告测试页为单独 Activity，避免传感器控制按钮或输入框影响规则。

不使用 root shell 作为运行时依赖；LSPosed 自身负责注入。app 和 probe 不申请 INTERNET。无障碍与 Provider 运行于 app 默认进程，不使用多进程 SharedPreferences。配置只持久化在管理端；Hook 缓存仅存在内存，重启后不能复用过期租约。

## 4. 所有权与并行开发

| 阶段/成员 | 唯一可写范围 | 交付约束 |
| --- | --- | --- |
| Plan/architect t1 | docs/requirements.md、docs/architecture.md、docs/contracts.md | 仅规划，不写产品代码 |
| 开发 A/android-engineer 骨架 t2 | 根构建、wrapper、settings、各模块 build.gradle.kts、最小 Manifest/占位资源、core 公共声明、.gitignore、CI 基础 | 先初始化本目录独立 Git；提供可解析的五模块和精确合同声明；骨架完成后停止改其他成员目录 |
| 开发 A 并行 t7 | core/** | 实现核心策略/配置/规则及必要纯 JVM 单测；公共签名已冻结 |
| 开发 B/ui-engineer t8 | app/** | UI、AppConfigRepository、ConfigProvider、RuntimeReportStore 与宿主 Application |
| 开发 C/accessibility-engineer t9 | accessibility/** | 服务、依赖入口、状态、服务 XML/Manifest、执行逻辑 |
| 开发 D/hook-engineer t10 | hook/**、probe/** | Hook 与 probe 的代码/资源/各自 Manifest |
| QA/qa-release | 独立验收计划、测试结果与证据文件（具体范围由其任务给定） | 不把修复混入独立验证，不修改产品代码 |
| 开发 A 顺序集成 t11 | 需要接线的跨模块/共享构建文件 | 汇集变更申请，统一 Manifest 合并、入口资产和依赖；解决交叉问题 |
| Plan 独立审查 t12 | 审查结论与发现，不修改产品代码 | 审查最终集成成果及 QA 证据；不以自己实现自审通过 |
| 开发 A 交付/仓库阶段 | 授权任务内的交付文档、APK、CI、gh 仓库操作 | 按 QA/审查结论披露真实验证范围 |

并行阶段不得修改根 settings/build/wrapper/版本目录；本模块新增依赖也先提出申请，由顺序集成统一变更共享配置。接口调整先发给 Plan 与所有受影响所有者，明确新版签名与迁移点，再由顺序集成实施跨目录修改。成员不得自行到他人模块补接口。骨架不要用永久返回 true/空成功模拟功能；未实现声明必须显式失败或全关放行，并不能作为产品完成证据。

## 5. 构建与验证命令

统一在独立仓库根目录执行，Windows 可用 `gradlew.bat` 替代 `./gradlew`。初次完成工具链设置后记录 `java -version`、`./gradlew --version`、SDK 安装清单。建议 CI 以 JDK17 和相同 SDK 运行。以下是后续任务的验证合同，本规划任务并未执行编译。

| 所有者/目的 | 命令 |
| --- | --- |
| 骨架/全部模块可解析 | `./gradlew projects :core:compileKotlin :app:assembleDebug :probe:assembleDebug` |
| core | `./gradlew :core:test` |
| app | `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` |
| accessibility | `./gradlew :accessibility:testDebugUnitTest :accessibility:lintDebug :accessibility:assembleDebug` |
| hook | `./gradlew :hook:testDebugUnitTest :hook:lintDebug :hook:assembleDebug` |
| probe | `./gradlew :probe:testDebugUnitTest :probe:lintDebug :probe:assembleDebug` |
| 完整集成 | `./gradlew :core:test :app:testDebugUnitTest :accessibility:testDebugUnitTest :hook:testDebugUnitTest :probe:testDebugUnitTest :app:lintDebug :accessibility:lintDebug :hook:lintDebug :probe:lintDebug :app:assembleDebug :probe:assembleDebug` |
| 设备清单 | `adb devices -l` |
| 安装管理/诊断 | `adb install -r app/build/outputs/apk/debug/app-debug.apk`；`adb install -r probe/build/outputs/apk/debug/probe-debug.apk`（分别执行） |
| 启动管理/诊断 | `adb shell am start -n com.antiads.app/.MainActivity`；`adb shell am start -n com.antiads.probe/.MainActivity`（分别执行） |

Gradle 的 NO-SOURCE 不计作已存在测试通过；单元测试重点覆盖规则误触边界、租约/开关优先级、Provider 身份检查与持久化失败。设备验证按 requirements 中编号执行，手动操作亦需记录步骤和实际结果。构建成功不能证明 AccessibilityService 已连接或 LSPosed 已注入。

产物路径固定为 app/probe 的 debug 输出。检查合并 Manifest、APK 权限与 assets/xposed_init：app 必须含服务、Provider、模块元数据；probe 必须独立且不含模块入口；两 APK 均无 INTERNET。Release 压缩在 v1 默认关闭，避免反射入口被移除；后续开启 R8 必须另加 keep 规则与复测，不共用调试成功证据。
