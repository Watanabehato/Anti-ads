# Anti-ads 独立 QA 验证报告

Run：t3-b19f67f；QA：qa-release；日期：2026-09-20。固定源码 `b19f67f2262dff74d08fddca2ce457c28f857afe`，相对 t11 `f1208c6` 仅 docs/build.md 一行文档变化。证据目录 [qa-evidence/t3-b19f67f](qa-evidence/t3-b19f67f/)。

门禁结论：**失败／需修订**。完整构建、285项JVM及1项Android instrumentation通过，lint无error；API29已完成有限场景的独立运行，发现1项高优先级和3项中优先级问题。Root/LSPosed、API30+可见性、API35+Insets及真机未实测。设备测试已结束并完成自建环境清理；没有修改产品、测试实现或期望。

## 已确认发现

### QA-01：系统授权状态始终显示“否”（高）

环境为 API29 默认x86_64镜像；app APK SHA-256 `c79b04a4d001b4c6037945ae7e06dfe657288f98bf153f3b4db7d85a33233138`。通过产品入口打开Android设置，启用Anti-ads无障碍服务并接受系统对话框，返回首页，等待超过一分钟仍显示“系统设置授权：否”，同时“服务实际已连接：是”。

证据：[返回首页截图](qa-evidence/t3-b19f67f/home-authorized.png)、[稍后截图](qa-evidence/t3-b19f67f/home-authorized-later.png)、[只读系统设置值](qa-evidence/t3-b19f67f/system-authorization-readonly.txt)、[dumpsys accessibility](qa-evidence/t3-b19f67f/accessibility-authorized.txt)。系统值为 accessibility_enabled=1，enabled service为 `com.antiads.app/com.antiads.accessibility.AdSkipService`；Bound services存在。

根因定位：app/src/main/kotlin/com/antiads/app/ui/StatusFacts.kt:49 将 `AD_SKIP_SERVICE_PACKAGE` 硬编码为库命名空间 `com.antiads.accessibility`，:99–100 要求 serviceInfo.packageName 与之相等，而最终APK与实际系统服务归属均为 `com.antiads.app`。需使用宿主applicationId识别服务所属包，同时保持类名精确匹配；添加针对最终宿主归属的有效验证，并在API29重新执行授权/撤权/返回刷新。QA不修改实现或测试预期。

### QA-02：构建文档中的环境现状仍不一致（中）

docs/build.md:185/191仍称未安装emulator/镜像、无AVD，:192称环境与仓库工具链分离；实际t15用本项目SDK安装官方组件并创建项目内AVD。需区分历史盘点、组件已安装、曾启动、当前连接和实际产品验证。QA只修订授权范围内的环境交接文档，其他产品文档交Plan安排。

### QA-03：probe离开再返回后无法按提示直接重启采样（中）

步骤：打开probe，点击开始采样，观察非零计数；按HOME，再以原任务返回。实际同一sessionId显示前缀=7bd4cb55，registrationSeq=6，计数已停止且类型显示UNREGISTERED；提示“需要重新点开始采样（不会自动重启）”，但开始按钮禁用，停止按钮仍可用。证据：[返回后截图](qa-evidence/t3-b19f67f/probe-after-home.png)、[生命周期日志](qa-evidence/t3-b19f67f/probe-lifecycle-runtime.txt)。MainActivity.kt:63–76在onPause停止session并移除ticker，但未恢复按钮状态；onResume仅refresh一次，未重建周期刷新。需同步按钮与运行状态、确保返回后的显式开始与策略自查可继续；不能以自动重注册替代显式开始。

### QA-04：probe文档承诺的Activity诊断文件未生成（中）

持续采样并暂停后，debuggable probe私有files/probe-diag.json均不存在。文件读取尝试实际返回“tar: files/probe-diag.json: No such file or directory”，所以没有捏造JSON快照；使用真实logcat和截图作证。docs/probe.md:27–28承诺debuggable变体按≤1次/秒和状态变化写文件；SensorDiagSession.dumpFile()只有定义，main源码无调用（androidTest的显式dump不代表Activity会写）。需由实现负责人接入debuggable下承诺的节流与状态变化写文件路径，复测可解析快照和生命周期；本轮按失败记录，不以改动验收期望替代修复。缺失文件的独立exit1证据见[probe-missing-snapshot.txt](qa-evidence/t3-b19f67f/probe-missing-snapshot.txt)。

## 构建与可核查产物

独立执行命令（使用仓库JDK17.0.20.1+1、Gradle8.9、AGP8.7.2、Kotlin2.1.10、compile/targetSdk35、minSdk29）：

```bash
bash ./gradlew --no-daemon :core:test :app:testDebugUnitTest :accessibility:testDebugUnitTest :hook:testDebugUnitTest :probe:testDebugUnitTest :app:lintDebug :accessibility:lintDebug :hook:lintDebug :probe:lintDebug :app:assembleDebug :probe:assembleDebug :app:assembleDebugAndroidTest :probe:assembleDebugAndroidTest --rerun-tasks --no-build-cache --max-workers=2
bash ./gradlew --no-daemon -p qa/fixtures/noqueries assembleDebug lintDebug --rerun-tasks --no-build-cache --max-workers=2
```

两条命令exit=0，分别3m10s/246项全部executed、1m42s/43项全部executed。完整日志为[gradle-full.txt](qa-evidence/t3-b19f67f/gradle-full.txt)和[fixture-build.txt](qa-evidence/t3-b19f67f/fixture-build.txt)。没有用UP-TO-DATE/构建成功替代测试证据。夹具无JVM测试，不计入285例。

| 模块 | 实际JVM用例 | 测试套件 | failures/errors/skipped |
| --- | ---: | ---: | --- |
| core | 67 | 6 | 0/0/0 |
| app | 44 | 6 | 0/0/0 |
| accessibility | 103 | 9 | 0/0/0 |
| hook | 46 | 10 | 0/0/0 |
| probe | 25 | 5 | 0/0/0 |
| 合计 | 285 | 36 | 0/0/0 |

[解析汇总](qa-evidence/t3-b19f67f/jvm-results.json)包含同次运行XML时间戳；XML结果位于`qa-evidence/t3-b19f67f/jvm-xml/<module>/`；公开副本仅将hostname脱敏为qa-windows-host，测试名、结果、数量、耗时和时间戳不变。未脱敏原件仍在各模块被忽略的build/test-results目录。设备系统日志也仅保留与测试包/服务相关的必要行。`NO-SOURCE`属于无对应输入的任务，未被算作测试通过。

设备还实际执行了一次现有`com.antiads.app.ConfigToggleTest`：

```bash
.tooling/android-sdk/platform-tools/adb.exe -s emulator-5580 shell am instrument -w -r -e class com.antiads.app.ConfigToggleTest -e action master_off com.antiads.app.test/androidx.test.runner.AndroidJUnitRunner
```

[原始输出](qa-evidence/t3-b19f67f/instrument-master-off.txt)：exit=0、OK(1 test)、SAVED、flipped=true、revision16→17、真实宿主仓储写入耗时40ms，并由测试读取落盘文件核对。该单例不重复计入JVM285例，不是H01传感器恢复测试。probe的RegistrationHoldTest仅组装并安装，未执行；缺少Root/LSPosed条件，不能给出Hook通过结论。

五个APK的完整SHA-256见[apk-sha256.txt](qa-evidence/t3-b19f67f/apk-sha256.txt)，分别为app、probe、两套androidTest和独立noqueries夹具。五个APK均已安装成功，签名验证均通过；使用Android Debug RSA2048/v2签名，不是发布签名。证书SHA256为`a73c75c1db48e2900068074e0f35abee57ea065e18be226e41ffb232a0859f01`。测试结果只对应这些APK。

## APK声明、权限与lint

四个Android模块lint无error；app保留1个`ExportedContentProvider`，hook保留1个`PrivateApi`，accessibility/probe无issue；独立夹具lint无issue。原始XML和[汇总](qa-evidence/t3-b19f67f/lint-summary.json)已保存，没有新增baseline、全局抑制或签名读权限来消除警告。

- 最终app APK包含`assets/xposed_init`，入口为`com.antiads.hook.AntiAdsHookEntry`。四个Xposed metadata完整，minversion=82，scope资源大小1，仅`com.antiads.probe`。`dex-inspection.json`检查实际DEX class_def：app存在Hook入口，app/probe都没有Xposed框架实现类定义；保留必要引用不等于打包框架。
- app的Provider authority=`com.antiads.app.config`，exported=true、grantUriPermissions=false。没有signature读权限；源码在任何身份清除前捕获真实Binder UID/PID，按同用户、应用UID、真实包集合及arg精确匹配验证。API29分别以probe UID10117、无queries夹具UID10120成功读取本包最小策略；shell UID2000伪装probe被UNAUTHORIZED拒绝。
- `write_config`返回INVALID_REQUEST；INSERT和OPEN_FILE实际抛只读Provider的UnsupportedOperationException。CLI在这些预期拒绝中仍可exit=0，判据是具体返回/异常与配置未被改写，不是退出码。shared/isolated UID、其他用户、PID覆盖、限流及容量边界只由当前JVM用例/源码支撑，未伪称真实Android多用户实测。
- 最终app/probe均无INTERNET，app无QUERY_ALL_PACKAGES；probe具有authority queries，独立夹具Manifest无queries、无uses-permission。API29无API30+相同包可见性限制，P/T均成功不能外推高版本。
- 无障碍service导出且要求系统BIND_ACCESSIBILITY_SERVICE；事件仅WINDOW_STATE_CHANGED/CONTENT_CHANGED，notificationTimeout=100ms，可读节点，canPerformGestures=false、触摸探索/键事件请求=false；对应打包XML已保存。backup关闭，备份/数据迁移排除规则也按最终资源检查。
- PrivateApi来自反射`SystemSensorManager$SensorEventQueue`。源码按目标方法形状检查，类/方法缺失为Unsupported，安装异常为Failed，仅成功注册Hook才报告Installed；坏/过期策略放行。JVM通过不证明设备允许反射或Hook真正安装，警告仍保留并要求按框架、ROM单独验收。

## 环境与不计为通过的尝试

Windows10 Pro19045；自建官方API29 default x86_64 rev8镜像，Android10，指纹`Android/sdk_phone_x86_64/generic_x86_64:10/QSR1.210820.001/7663313:userdebug/test-keys`，补丁2019-09-05；emulator37.1.11.0/build15917651，WHPX，SwiftShader，2048MiB/2CPU，1080×1920三键导航。只使用用户0、adb侧载。未安装LSPosed或Root方案，shell身份为UID2000；userdebug镜像不构成Root模式验证。

t15确实完成过启动，但t3接手时旧bash-6已不可查询、adb无设备、5580/5581无监听且无emulator/qemu进程。t3在同一项目SDK与AVD下以自有bash-11重新启动，两次独立确认device/boot=1/AVD名称后才安装APK；未把历史在线当作持续在线。具体环境修订见[emulator-environment.md](emulator-environment.md)。

其他已保留、未包装成成功的尝试：

- 额外`adb nodaemon server`进程bash-12因已有5037监听而exit127，已收集；继续用现有adb服务器，没有全局kill-server。重复127.0.0.1:5581运输连接已主动disconnect，仅使用emulator-5580。
- 初期`uiautomator dump`出现could not get idle state/null root，未获得有效XML。随后使用实际截图核对和adb输入，未借UiAutomation挂起无障碍服务后误判负例。一个含多次操作的30s命令超时，按现有截图/落盘状态续做，没有盲目重放开关。该次home-configured-unconnected截屏只留下8字节PNG头，已标为.png.partial原始失败片段，不计有效截图；报告使用后续完整截图和配置文件。
- 试图按probe文档取`probe-diag.json`失败；exec-out的原始流包含tar找不到文件错误，导致本地tar解析checksum错误。后续独立ls exit1确认文件缺失，见QA-04；没有把该流当JSON。
- app instrumentation开始/结束都强停宿主，系统随后残留DEAD连接与Binding、无Bound service。通过系统UI撤权/重新授权仍未恢复，期间两次关闭后“不点击”观察均不可判定，不计通过。保留`service-binding-*.txt`及相关截图。重启该自建AVD后Bound恢复，重新执行关闭控制。不得把这个现象当作已定位到产品的崩溃；本轮crash buffer只有安装前的系统Launcher3 SIGSEGV，没有本产品异常栈。
- 一次boot等待shell片段发生主机getprop命令替换错误，虽整体exit0仍不作为启动证据；重启后另行直接读get-state、sys.boot_completed和AVD名称确认。重启前后guest墙钟不同、elapsed/PID重置，证据按两个boot会话分别解释，不跨boot拼接H01。

## API29设备观察与关键证据

本节是模拟器上的实际Android运行，不是真机实测。所有广告页使用独立AdFixtureActivity；没有点击页面的跳过按钮来制造正例。每个窗口以force-stop probe后重新启动获得独立进程/窗口，负例等待12秒后截图。典型命令：

```bash
.tooling/android-sdk/platform-tools/adb.exe -s emulator-5580 shell am force-stop com.antiads.probe
.tooling/android-sdk/platform-tools/adb.exe -s emulator-5580 shell am start -W -n com.antiads.probe/.AdFixtureActivity --es scenario ad_positive
# 等待12秒，保留原始屏幕与fixture_open/fixture_skip_clicked日志
.tooling/android-sdk/platform-tools/adb.exe -s emulator-5580 exec-out screencap -p
.tooling/android-sdk/platform-tools/adb.exe -s emulator-5580 logcat -d -v threadtime -s AntiAdsProbe.Fixture:I
```

| 检查 | 实际结果 | 证据（本目录下均对应同一APK） |
| --- | --- | --- |
| 默认与持久化 | 默认三个全局开关关闭、包列表空；添加probe并开启两模式、仅类型1，revision10；force-stop/重开后保留 | `home-default.png`、`config-before-restart.json`、`home-after-restart.png` |
| 正例3轮 | 三个独立PID3810/4008/4045均自动变“已跳过”，每窗仅1条click；open→click为1800/978/899ms | [完整记录](qa-evidence/t3-b19f67f/ad-all-rounds-runtime.txt)、`ad-positive.png`、`positive-round-2.png`、`positive-round-3.png`。后两PNG因同一分钟和同一UI而哈希相同，独立进程和elapsed记录证明分轮 |
| 四反例＋未知 | no_ad_label/non_clickable/bottom_button/editable_window及未知值各12s均“等待”，无click；未知值实际退到no_ad_label | `ad-*.png`、`unknown-scenario.png`及上述完整记录 |
| 系统授权事实 | 系统secure值1、实际组件com.antiads.app/...、dumpsys Bound；主界面却“授权否/连接是”，重启后仍可复现 | QA-01的授权截图与系统只读结果；这是失败，不用无障碍正例通过抵消 |
| 各层关闭 | 重启恢复Bound后，分别只关全局无障碍（revision19）、只关probe无障碍（21）、只关总开关（23）；对应正例各12s保持等待，无click | `reboot-mode-off-positive.png`、`per-app-off-positive.png`、`reboot-master-off-positive.png`；相应bound.txt证明服务仍连接 |
| 重启后正对照 | revision22恢复全部无障碍条件，PID3589再次自动“已跳过”；565166→566133ms，967ms | `reboot-positive-control.png`、[重启后日志](qa-evidence/t3-b19f67f/post-reboot-ad-runtime.txt)。没有把服务断线当开关生效 |
| P真实UID读 | probe UID10117，开启自查后OK/revision10/hook=true/types=[1] | [P成功截图](qa-evidence/t3-b19f67f/probe-policy-success.png)、`provider-shell-negative.txt`中的UID映射 |
| T未选／已选 | noqueries夹具UID10120、PID4281。未选时rev10、本包hook=false/types=[]；UI选择后rev16、本包true/[1]，均ok=true且无他包配置 | [未选](qa-evidence/t3-b19f67f/fixture-unselected-policy.png)、[已选](qa-evidence/t3-b19f67f/fixture-selected-policy-full.png)、`fixture-configured.png`；每次都是按钮触发的新Binder调用，非沿用旧文本 |
| 关闭后的最小策略与冷启动 | 总开关关闭rev23；宿主PID2607被force-stop且pidof无结果，不打开宿主Activity。T UID10120/PID3717手动调用拉起宿主PID3800，返回本包rev23 false/[] | [成功响应](qa-evidence/t3-b19f67f/provider-cold-success.png)、`provider-cold-pid-*.txt`、`provider-cold-system-log.txt`明确启动原因content provider。没有新增生命周期打点，内部Provider/Application先后由平台顺序和源码静态核对，未假称精确trace |
| 坏配置回退 | 备份专用合成配置，停止宿主后以debug run-as把文件截断到1字节“{”；先由T读取，返回rev0 false/[]；随后宿主UI三个全关、0个包、INVALID_JSON | [Provider](qa-evidence/t3-b19f67f/corrupt-provider-fail-closed.png)、[UI](qa-evidence/t3-b19f67f/corrupt-config-home.png)、`corrupt-file-size.txt`。仅测试数据受影响；最后恢复备份并逐字节比对成功 |
| probe传感器基线 | 类型1/4/9/10/11和未选对照2均实际注册并产生非零回调；HOME后停止，回到页面显示UNREGISTERED；开始按钮状态错误 | `probe-sampling.png`、`probe-policy-success.png`、`probe-after-home.png`、`probe-lifecycle-runtime.txt`。后者是采样日志，停止后的状态主要由截图和onPause源码证明；见QA-03/04 |
| T传感器基线 | 类型1/4/9/10/11/5/8可用，注册返回true、计数非零；切换管理器后状态明确UNREGISTERED:ON_PAUSE | `fixture-sampling.png`、`fixture-policy-before.png`、`fixture-unselected-policy.png`、`fixture-selected-policy-full.png`。不是同次注册的启停实验 |
| 最终撤权 | Android设置中STOP确认；secure accessibility_enabled=0，enabled services=null，Bound/Enabled/Binding均空 | `final-service-off.png`、[系统事实](qa-evidence/t3-b19f67f/final-authorization-revoked.txt) |

坏配置注入和恢复的实际命令为`am force-stop com.antiads.app`，随后`run-as com.antiads.app cp files/protection-config-v1.json files/qa-t3-config-backup.json`、`truncate -s 1 files/protection-config-v1.json`；取证后再次force-stop，再把backup复制回原路径并移除测试backup。`config-before-corrupt.json`与`config-restored.json`逐字节相同，revision23、master=false。run-as是专用debug测试身份，不是产品暴露的写入口，也不表示普通目标包可写宿主文件。

## 46项验收场景逐项边界

S=源码/最终包静态检查，J=本次JVM测试，E=API29模拟器，D/R=普通/Root真机。表中的“J通过”仅指同次运行中实际存在的相关测试套件，不扩大到设备或未实现的夹具分支；完整方法名、数量和结果以XML为准。未列为E通过的计划子项均未在设备执行。

| 场景 | 已执行范围与结果 | 尚缺证据 |
| --- | --- | --- |
| C01 | E通过：默认全关、无框架、无授权可冷启动 | 普通真机 |
| C02 | J通过；E通过无障碍全开与三个独立关闭条件 | 两模式全部8组合的设备覆盖、Root |
| C03 | J的PolicyResolver/Validator等通过 | 实际删包、空类型、空ruleIds的全流程未实测 |
| C04 | J通过；E通过INVALID_JSON冷读、UI回退与精确恢复 | 其他损坏/上限数据的设备注入未实测 |
| C05 | J仓储冲突/revision边界测试通过 | 无设备并发结论 |
| C06 | J仓储错误路径通过；E正常落盘/重启通过 | Android不可写存储/中途I/O故障未注入 |
| C07 | J观察者/状态相关用例通过 | 不追加设备竞态通过声明 |
| R01 | E通过force-stop重开和系统重启配置保留 | 普通真机/OEM后台策略 |
| R02 | E通过三层关闭、实际撤权；不合格断线尝试排除 | 停用框架/卸载后传感器恢复未实测 |
| A01 | E通过3轮＋重启后1轮，真实页面与日志 | 真实第三方广告、各ROM |
| A02 | E通过四反例＋未知值，每例12s | 更广真实应用负例 |
| A03 | J匹配器用例通过 | 不标设备全文案矩阵通过 |
| A04 | J节点安全用例通过；E不可点击/无广告/底部/可编辑反例通过 | 真机锁屏、密码、隐藏/禁用等设备分支未全跑 |
| A05 | J几何与候选相关测试通过 | 无真实分屏/挖孔外推 |
| A06 | J前台窗口/时钟边界用例通过 | 设备上排定精确毫秒竞态未实测 |
| A07 | J排队动作/新鲜度/执行前复核用例通过 | 设备上排队后撤权等竞态未穷尽 |
| A08 | J预算/节流用例通过；E每个正例仅一次click | 真实大树/高频压力和性能基准未实测 |
| A09 | E失败：授权真值错报，QA-01；撤权系统事实可核查 | 修复后全生命周期复测 |
| A10 | S/J过滤用例通过；E权限页未观察到误动作 | 各OEM权限管理器、系统包负例未穷尽 |
| H01 | R未实测；E仅基线，离开页面会注销 | 有框架同一注册baseline→enable→disable及独立对照 |
| H02 | J决策用例通过 | 五类型真实拦截及关闭/空集合放行未实测 |
| H03 | E无框架启动、非零传感器和“未观察到注入” | 真Root设备上未激活/未选作用域对照 |
| H04 | R未实测 | 作用域修改/升级后旧进程与重启新进程 |
| H05 | J租约与时间边界用例通过 | 实际≤5s放行决策和同次注册回调恢复 |
| H06 | J时钟/worker相关用例通过 | 不冒充设备休眠和真实Binder卡死实验 |
| H07 | J客户端坏响应/超时路径通过；E损坏配置安全策略 | 已拦截情况下真实Provider不可达后的放行 |
| H08 | S/J方法形状/未知handle用例通过，PrivateApi警告保留 | 各系统真正安装/失败降级 |
| H09 | S/J工作线程/节流相关用例通过 | Hook注入高频事件、30秒无流量、耗电/线程上限实测 |
| H10 | R未实测 | 第二进程、isolated、shared UID、工作资料和分身 |
| H11 | R未实测 | 框架停模块、目标进程仍驻留时恢复 |
| H12 | S核对覆盖仅Java分发路径 | Native/JNI/Direct Channel/厂商路径不在保证范围，未设备实测 |
| S01 | J通过；E两个真实UID仅本包响应，shell伪造拒绝 | 真实目标UID改arg冒充他包未新增入口测试 |
| S02 | J通过；E write_config/INSERT/OPEN_FILE拒绝 | 其余拒绝方法设备矩阵未全跑 |
| S03 | J调用方验证用例通过 | Android真实多用户/isolated/shared UID未实测 |
| S04 | J报告验证用例通过 | 真实目标进程恶意report/框架上报未实测 |
| S05 | J限流/LRU用例通过 | 多UID真实Binder压力未实测 |
| S06 | API29仅补充P/T真实UID对照、最终queries和基线证据 | 规定的API30+及Root传输/租约组合未实测 |
| S07 | S＋E通过目标启动死宿主Provider且成功读策略 | 没有额外Provider/Application打点或Root运行 |
| S08 | S＋E检查通过上述最终包权限/身份/外部不可写边界 | 无生产隐私审计或全系统权限证明 |
| P01 | E失败：返回页无法按提示直接开始、文件缺失，QA-03/04；基线与暂停取证有效 | 无硬件/注册失败的真实设备、修复后生命周期 |
| P02 | E通过独立广告页面与未知值回退 | 不扩大到第三方广告覆盖 |
| U01 | E保存与无框架未观察到注入文案真实；授权部分失败见QA-01 | Installed/Unsupported/Failed、dropped等框架状态设备验证 |
| U02 | J过期/报告存储用例通过 | 真实报告停止15s后的UI与进程实例切换未实测 |
| U03 | E通过应用列表、手工添加两个合法测试包、权限入口 | 非法包/包不可见/缺失管理器等入口矩阵未全跑 |
| U04 | API35+未实测；E仅API29竖屏三键导航 | Insets、横屏、手势、挖孔、分屏、大字体 |
| U05 | 未实测；本轮只API29 adb安装 | API33+普通侧载受限设置及对应授权路径 |

## Android版本与运行模式矩阵

通用debug APK在compile/target35、min29条件下已构建。下表的“未实测”不因通用APK构建通过而改变。真机、OEM和框架未验证不是通过，也不等于所有设备都会失败。

| Android/API | 构建/模拟证据 | 非Root模式 | Root/LSPosed模式 |
| --- | --- | --- | --- |
| 10／29 | 已构建、已模拟；官方x86_64/WHPX | 仅本报告E场景；有QA-01/03/04失败；无真机 | 未注入、未实测；只有静态/JVM和非拦截基线 |
| 11／30 | 通用APK已构建；未模拟/未实测 | 包可见性P/T未验证 | 未实测，需独立框架与作用域记录 |
| 12／31 | 通用APK已构建；未模拟/未实测 | 未实测 | 未实测 |
| 12L／32 | 通用APK已构建；未模拟/未实测 | 未实测 | 未实测 |
| 13／33 | 通用APK已构建；未模拟/未实测 | 受限设置/安装来源未验证 | 未实测 |
| 14／34 | 通用APK已构建；未模拟/未实测 | 未实测 | 未实测 |
| 15／35 | compile/target35构建；未模拟/未实测 | 强制edge-to-edge/Insets未验证 | 需适配该版本的维护分支，不能默认原版LSPosed支持 |
| 16／36 | 不是compileSdk36验证；无运行环境 | 未实测 | 未实测，不推断框架兼容 |
| 37及以后 | 高于本轮compileSdk35；须另行验证，无运行证据 | 未实测，不承诺 | 未实测，不承诺 |

本轮全部系统均无普通真机、Root真机、OEM ROM、工作资料/克隆、shared/isolated UID运行证据。无障碍不能阻止摇动传感器、原生点击行为或无可访问节点广告；增强模式仅覆盖受支持Java传感器分发方法，需要Root/框架实际注入及用户作用域；安装模块、保存策略、进程自报、Provider读成功、零回调都不能单独证明真实拦截。5秒是旧租约到期后不得继续DROP的决策上限，不保证物理事件在5秒内到来。

## 收束与复测入口

所有设备场景后恢复revision23原始测试配置，master=false；通过Android设置撤销无障碍授权（0/null、服务集合为空）。核对AVD名称后仅对emulator-5580执行emu kill，命令exit0，自有bash-11完成exit0；所有本轮后台job已收集、无running。立即kill后的devices列表尚见旧transport是异步清理瞬间；[job退出后的再次查询](qa-evidence/t3-b19f67f/devices-after-emulator-exit.txt)为空，未拿早期列表替代终态。保留项目SDK、镜像、AVD及APK供后续复测；没有删除用户设备、停止全局adb服务器或改产品实现。

本次门禁为**失败／需修订**。修复QA-01～04后必须记录新commit及APK哈希，重测授权真值、probe返回/快照与受影响集成路径，不能移用这份b19f67f的APK结论。Root、API30+可见性、API35+Insets等缺口在获得对应环境与证据前保持未验证。本报告不会将仅能构建、单测通过或有限夹具成功表述为所有应用/系统全面防护。

