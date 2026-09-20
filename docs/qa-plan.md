# Anti-ads QA 验收计划与兼容性矩阵

基线日期：2026-09-20（本机 UTC+08:00）。责任人：qa-release；任务 t13。依据 [需求](requirements.md)、[架构](architecture.md)、[接口合同](contracts.md)制定，供统一集成版本的独立 QA 执行。本轮只编写验收计划，不修改产品／公共接口，不创建 smoke-test 脚本。

**当前结论：完成验收设计与主机环境盘点；产品测试、构建、lint、模拟器及 Root 真机均尚未执行。** 计划完成不表示产品通过。工程骨架并行进行中，最终 QA 必须重新盘点工具与设备。

## 1. 证据等级、记录与通过门槛

| 等级 | 可以证明 | 不能证明 |
| --- | --- | --- |
| PLAN／未实测 | 数据、步骤和预期已定义 | 实现满足约束 |
| STATIC／静态检查 | 源码、Manifest、APK 和说明符合已检查项 | 服务已连接、真实 Binder 身份、Hook 已注入 |
| JVM／本地执行 | 指定 JVM 的实际测试结果，包括模拟时钟和依赖替身 | Android 生命周期、跨 UID 行为和传感器正常 |
| BUILD／构建与 lint | 指定提交可编译／打包，lint 结果可审查 | 任意设备均可安装运行 |
| EMULATOR／模拟器 | 指定镜像、ABI 的安装、页面、无障碍测试 | OEM、真实传感器、Root 真机兼容性 |
| DEVICE／真机 | 指定设备、系统、框架、目标和类型的重复结果 | 其他组合或 Native／Direct Channel 覆盖 |

结果只填写：通过、失败、未实测（附环境／夹具缺口）、不适用（附可核查原因）。无传感器、注册失败、无非零基线写“未实测／不可判定”，不能把零事件计为拦截成功。Gradle `NO-SOURCE`、零测试数、无测试 XML 写“未包含可执行测试”，不能计为已有测试通过。缓存／UP-TO-DATE 需追溯同提交证据，首次独立验证用 `--rerun-tasks`。

每次运行保存：

- Run ID、时间、测试人、场景 ID、证据等级、commit／工作区差异、两个 APK 的 SHA-256／包名／版本／签名；不能换 APK 后沿用旧结果。
- 主机 OS、JDK vendor/patch、Gradle/AGP/Kotlin/SDK、命令全文／退出码、测试数／失败／跳过数、lint 和原始日志路径。
- 设备代号（对外脱敏）、型号、Android/API/QPR/构建号/补丁/ABI、用户或工作资料、安装来源；模拟器镜像与模拟传感器方式。
- Root 方案／版本、框架完整名称／版本／来源、模块版本、作用域截图、目标包／版本／UID／进程／PID；未知就写未知。
- 配置 revision、三层开关、类型集合、单调时钟时间线、操作、预期／实际、截图或短录屏、计数差值、清理步骤与遗留风险。

失败模板：`ID | 严重度 | commit/APK | 环境 | 最小步骤 | 预期 | 实际 | 原始证据 | 影响 | 责任模块 | 重测状态`。阻断项包括不可构建、配置越权、误触敏感动作、关闭失效、假生效状态；高优先级包括必要功能在声明支持组合失败。QA 将发现交 Plan，不修改实现来掩盖失败。

可给出“源码／构建通过，设备未实测”的分层交付结论；没有 Root 真机证据，增强模式保持未验证。存在阻断问题不得给产品通过结论。缺设备与产品失败分开记录，不删除未测行。

## 2. 当前工具与设备（实际探测）

盘点从 2026-09-20 15:49 开始，Git for Windows bash，工作目录 `/d/test/Anti-ads`。以下仅为环境观察。

| 项目 | 观察 | 证据边界 |
| --- | --- | --- |
| java/javac/gradle | PATH 未发现 | command -v；不能断言整台主机未安装 |
| adb/emulator/sdkmanager | PATH 未发现 | 尚不能执行 adb devices -l／emulator -list-avds |
| JAVA_HOME/ANDROID_HOME/ANDROID_SDK_ROOT | 均未设置 | 只查这三个变量，不输出全量环境或凭据 |
| 常用 SDK 目录 | `/c/Users/Admin/AppData/Local/Android` 不存在 | glob 目录不存在，exit 2；不是已安装但无设备 |
| 工程内工具 | 探测时无 adb.exe、gradlew*、source.properties | 骨架并行建设，最终需复查 |
| Node/Python | v24.19.0／3.12.10 | 版本命令退出码0 |
| Git/gh | 2.55.0.windows.3／2.97.0 | 版本命令退出码0；QA不建仓发布 |
| Git 根 | 初次 git status 显示父目录条目 | 当时独立仓库尚未确认；不在父仓库提交 |
| 模拟器／USB／Root 真机 | 未确认可用设备 | 无adb，不能将“无法枚举”写成“已确认零设备” |
| 网络资料 | 官方搜索摘要可取得，全文抓取失败 | 不是SDK下载或产品兼容成功证据 |

`pwd && git status --short && date -Iseconds`、PATH/指定环境变量盘点、四项工具版本命令退出码均为0。未执行 Java/Gradle/ADB，不为不存在的运行填退出码。已向开发A索取最终工具路径、版本、SDK包、AVD、设备清单；QA不重复安装工具链。

## 3. API29 至已确定最新稳定主版本矩阵

本轮官方搜索索引确认最新已发布的稳定主版本为 **Android 17／API 37**：[Android 17 is here（2026-06-16）](https://android-developers.googleblog.com/2026/06/Android-17.html)、[Google 发布说明](https://blog.google/products-and-platforms/platforms/android/android-17-features/)、[官方行为变更的 API37 对应关系](https://developer.android.com/about/versions/17/behavior-changes-all)。摘要分别显示“Today we’re releasing Android 17”“rolling out now to Pixel devices”“API level 37”。没有把 Beta 的 Platform Stability 当稳定发布。

证据限制：首轮搜索报 TinyFish fetch failed，后续取得上述索引；web_fetch 对 developer.android.com、android-developers.googleblog.com、blog.google 报 `resolves to a non-public IP address`，未取得全文。[Android16 官方索引](https://developer.android.com/about/versions/16/behavior-changes-all)确认 API36；[Android17 概览](https://developer.android.com/about/versions/17)索引的 API37.1/QPR2 Beta 不纳入已确认稳定范围。最终 QA 复核具体 QPR/minor API 和补丁；搜索摘要不能证明项目支持或实测。

工具链维持合同：AGP8.7.2／Gradle8.9／Kotlin2.1.10／JDK17／compileSdk=targetSdk35／BuildTools35.0.0／minSdk29。API36/37 是运行验证对象，不据此升级编译SDK。JVM测试不按设备API重复冒充覆盖。

| Android | API | 安装／免 Root 重点 | 模拟器 | Root 真机／框架 |
| --- | --- | --- | --- | --- |
| 10 | 29 | 最低版本安装、默认全关、广告正反例、撤权恢复 | 未实测 | 原始LSPosed候选；Java定位／注入／恢复未实测 |
| 11 | 30 | 包可见性、应用列表／手工包名、S06 queries 对照 | 未实测 | Provider读取、进程重启未实测 |
| 12 | 31 | 服务重连、配置恢复、采样率／后台限制区分 | 未实测 | 非零基线、类型选择／租约未实测 |
| 12L | 32 | 分屏、窗口坐标、旋转；不复用API31结论 | 未实测 | 分屏同次注册恢复／多进程未实测 |
| 13 | 33 | 按侧载来源核查受限设置、中文引导、撤权 | 未实测 | 激活／作用域／进程重启未实测 |
| 14 | 34 | 授权与连接事实、停用、误触、报告过期 | 未实测 | 原始LSPosed范围上界候选；具体组合未实测 |
| 15 | 35 | target35 WindowInsets、状态栏／导航栏／挖孔 | 未实测 | 需支持API35的维护版；未实测 |
| 16 | 36 | 向前运行、分屏、授权、可见性，记录QPR/minor | 未实测 | 维护版与隐藏签名单独验证；未实测 |
| 17 | 37 | 已确认最新稳定主版本；布局、生命周期、权限回归 | 未实测 | 维护版／Java路径／具体目标未实测 |

每行的免Root真机状态也均为未实测。目标为API29必测，API30～32至少一档、API33～34至少一档；API35/36/37逐项记录。缺环境保留未测。宣称增强模式实测通过必须给Root真机证据，Root模拟器结果另列，不替代真实传感器。

[原始 LSPosed](https://github.com/LSPosed/LSPosed) Android8.1～14 范围来自需求基线，API29～34只是候选。OEM、ABI、工作资料／分身／共享UID／isolated进程、第三方目标版本、Native/Direct Channel均另设维度，不外推覆盖。

## 4. 固定测试数据与诊断前提

### 4.1 配置、包与类型

HOST=`com.antiads.app`（禁止成为自动操作/Hook目标）；PROBE=`com.antiads.probe`（独立APK，声明Provider queries）。CONTROL=`com.example.qa.control`只作逻辑单测包名；设备用真实已安装且未选中的可控应用替换记录，不假称仓库提供它。NO_QUERY_TARGET为授权可控的实际第三方目标，Manifest未声明 `com.antiads.app.config` authority；检查其其他queries和自动可见性条件，未声明不必然等于不可见。

D0（缺文件默认）：

```json
{"schemaVersion":1,"revision":0,"masterEnabled":false,"accessibilityEnabled":false,"hookEnabled":false,"packages":{}}
```

D1（双模式测试输入；设备用Saved的实际revision，不硬写为7）：

```json
{"schemaVersion":1,"revision":7,"masterEnabled":true,"accessibilityEnabled":true,"hookEnabled":true,"packages":{"com.antiads.probe":{"accessibilityEnabled":true,"hookEnabled":true,"blockedSensorTypes":[1],"ruleIds":["builtin.conservative.v1"]}}}
```

D2（对应最小策略）：

```json
{"schemaVersion":1,"revision":7,"packageName":"com.antiads.probe","hookEnabled":true,"blockedSensorTypes":[1]}
```

每种模式覆盖总／全局模式／本包模式的2×2×2共8个组合。另一模式开关不能替代或阻止本模式；另测试删包、空类型、空规则。允许类型仅{1,4,9,10,11}；优先阻断1、保留4作对照，缺硬件则换存在且会产生事件的未选类型并记录。

坏配置集：截断 `{`、缺schema、schema=2、revision=-1/Long.MAX_VALUE、布尔字段为字符串、非法包名（空格/通配符/斜杠/>255字符）、固定拒绝包、501包、257KiB UTF-8、非法类型5/8/19、未知ruleId；测试边界256KiB/500包/合法包名和空集合。未知普通键按ignoreUnknownKeys忽略，未知schema/枚举必须拒绝。只操作专用测试数据。

### 4.2 五个固定广告夹具

AdFixtureActivity独立窗口、不注册传感器；主传感器页不伪装广告。未知scenario回落no_ad_label。

| scenario | 数据 | 预期 |
| --- | --- | --- |
| ad_positive | 独立可见“广告”、右上角可见/启用/可点击“跳过5秒”、无输入节点 | 前提全满足时最多一次ACTION_CLICK，显示“已跳过” |
| no_ad_label | 普通“跳过5秒”按钮，无独立广告标签 | 零自动点击，普通跳过不当广告 |
| non_clickable | 有广告标签，目标本身不可点击 | 零自动点击，不代点父容器 |
| bottom_button | 有广告标签，按钮在屏幕下部 | 零自动点击 |
| editable_window | 广告正例加任一EditText | 整窗零自动点击，未聚焦也禁止 |

纯规则基准：屏幕1000×2000；PROBE；application=true、锁屏/敏感=false、traversalComplete=true；foregroundSince=1000、capturedAt=1500；独立广告nodeId=0；按钮nodeId=1、bounds=(700,100,950,200)、text=“跳过 5 秒”、visible/enabled/clickable=true、editable/password=false。逐一变更属性。设备按真实WindowInsets布置，保存实际bounds和屏幕尺寸，不直接套用单测像素。

### 4.3 传感器与同次注册恢复

先激活框架与作用域并重启PROBE，确认注入后保持产品总开关关闭，再显式开始同一注册会话。前台SENSOR_DELAY_NORMAL，在相同轻微运动下采样≥10秒。每个类型先满足exists、registerListener=true、回调差值>0；记录类型/ID、N0/N1、最近elapsed和状态。开启稳定后所选类型观察10秒差值为0，未选对照仍>0；过渡期另记。“显著减少”不自动等于全部所选Java回调已拦截。关闭只观察未来事件，不期待补发。

**probe onPause会注销，onResume不自动重启。普通全屏切HOST关开关不能证明同次注册恢复。** 优先Root真机多Resume分屏保持probe全程RESUMED；记录生命周期、registrationSeq/sessionId或等价注册/注销次数，确认未注销/重注册。也可用经实现并验证的宿主同UID测试驱动关闭真实Repository。普通切页只证明重新注册可恢复。

若对照类型也停止，可能是后台限制／息屏／省电，该轮不可判定。缺生命周期或注册次数证据，H01同次恢复写未实测。D已提出内部持注册instrumentation方案，B负责宿主同UID配置切换测试；尚未独立验证构建与运行，不能把设计命令当可用证据。Root诊断Service方案已取消。本计划不新增依赖、模块或导出写配置接口。Plan已确认此澄清，无公共签名调整。

## 5. 验收场景

以下全部初始为PLAN／未实测。J=JVM/模块测试，S=静态，E=模拟器，D=非Root真机，R=Root真机，各等级结果分列。精确竞态、时钟和I/O故障用开发所属模块已有可控测试；没有夹具标缺口，手动碰巧通过不能替代。

### 5.1 配置、总开关与恢复

| ID／级别 | 可重复步骤 | 预期／证据 |
| --- | --- | --- |
| C01／J,E,D | 专用全新测试数据，启动HOST/PROBE，不授权服务、不设作用域 | 默认全关、空名单、DEFAULTS_NO_FILE；不点击；有基线时传感器正常；不显示已保护 |
| C02／J,E,R | 对D1每种模式跑8种开关组合；CONTROL不选；逐次重进夹具或观察采样 | 仅总/全局/本包全开有效；按包隔离、两模式独立；光线/距离/步数不默认阻断 |
| C03／J,E,R | 启用后分别删包、清空类型、清空ruleIds；记Saved revision | 删包关闭两模式；空类型不拦截、空规则不点击；旧Hook策略至多5秒失效 |
| C04／J,E | Codec/Validator执行坏数据与上限数据集；专用Debug安装冷启动前破坏私有文件 | 无效结构明确拒绝；坏持久化配置全关并显示RECOVERED_CORRUPT/IO_ERROR中文原因；不泄露原始配置 |
| C05／J | 两writer同时expectedRevision=r；输入revision不符；revision接近Long.MAX_VALUE-1 | 最多一次成功，另一次CONFLICT；溢出拒绝REVISION_EXHAUSTED，不能变负数或覆盖另一更改 |
| C06／J,E | 落盘中途失败/不可写存储时保存开关；模拟重启读盘 | Rejected(IO_ERROR)，内存保留此前成功快照；AtomicFile得到完整旧/新数据，无半文件生效或假保存 |
| C07／J | observe首次回调、连续两次写、listener抛错、close后再写及UI旧generation | 成功revision有序；回调不在锁内；异常不污染落盘；close幂等，取消后不开始新回调，UI不应用旧任务 |
| R01／E,D | 保存后force-stop/重启HOST，模拟进程重建 | 配置恢复；报告不持久化为在线，连接事实重新获取 |
| R02／E,D,R | 分别关闭总/模式/包，撤销无障碍；先关产品再停框架/卸载测试HOST | 无障碍取消排队；Hook正常约3秒更新、5秒旧租约硬上界；未来回调放行；完全移除注入需重启目标 |

### 5.2 无障碍正反例、误触与生命周期

设备前提：系统手动授权，总开关/全局无障碍/PROBE无障碍打开，内置ruleIds非空，只选PROBE。每次退出夹具到首页再重进，确认新windowId/epoch；同包动作间隔≥2秒。重复am start可能复用窗口，不能假定已换epoch。

| ID／级别 | 可重复步骤 | 预期／证据 |
| --- | --- | --- |
| A01／J,E,D | ad_positive观察≥10秒，触发多次内容事件，独立新窗口重复3轮 | 每epoch最多一次尝试，页面变“已跳过”；lastAction返回true仅指已发送点击，不证明页面变化 |
| A02／J,E,D | 其他四个scenario与未知值，各观察12秒 | 零自动点击，无新增true记录；普通跳过/不可点击/底部/输入窗口均不误触 |
| A03／J | 基准逐一改text/description：跳过、5秒跳过、SKIP AD 5 s；再用跳过并购买、继续、允许、点击领取、>40字符 | 仅合同完整正则通过；独立可见广告节点；单独“摇一摇”、包含广告字样的长句、不可见标签不授权 |
| A04／J,E | 逐一设visible/enabled/clickable=false、editable/password=true、bounds越界/零面积、锁屏/敏感/非application/遍历不完整 | 任一禁止条件均无候选；密码/编辑节点即使未选中也禁止整窗；不代点父节点 |
| A05／J | 宽1000高2000，中心X649/650、中心Y500/501、面积240000/240001、大整数与多候选 | X≥65%、Y≤25%、面积≤12%边界准确，Long不溢出；top升/right降/nodeId升最多一项；几何变化隔离其他条件 |
| A06／J,E | capturedAt-foregroundSince=0/10000/10001/负数；同窗连续content/state事件超过10秒 | 0/10000按其他条件匹配，10001/负数拒绝；内容事件不重置epoch或延长观察窗口 |
| A07／J,E | 排队后改revision/关开关/换包窗/锁屏/撤权；refresh=false/文案改变；快照500/501ms | 执行前复核全部条件，>500ms丢弃重匹配；旧动作不落在新窗口；精确竞态需可控测试，手动切换仅补充 |
| A08／J,E | 密集事件，300/301节点、20/21层、8ms预算；动作失败与同包1999/2000ms再次进入 | 扫描间隔≥250ms；达到任一遍历上限不完整不点击；epoch失败也只尝试一次；同包动作≥2000ms；无ANR/持续扫屏 |
| A09／E,D | 不启用服务先开产品；手工授权、撤权、interrupt、重建进程 | 系统启用和实际connected分列；未连接不称可用；连接且全关PAUSED；interrupt取消任务而非误作永久断连 |
| A10／S,J,E,D | 核查宿主/android/SystemUI/当前输入法/HOME/权限控制器/安装器过滤，停留授权页 | 敏感窗口无动作；无手势/坐标/返回/首页/父节点代点；不自动授权、安装、支付 |

### 5.3 LSPosed、类型选择、关闭与失联

每项记录框架激活、作用域、PID与安装状态。激活/作用域/APK变更后先重启目标，必要时按框架要求重启设备；保存配置不等于注入。H01先注入并重启，再保持产品总开关关闭建立同一会话基线，后续通过真实Repository切换。

| ID／级别 | 可重复步骤 | 预期／证据 |
| --- | --- | --- |
| H01／R | 按4.3先注入/重启；同一注册下基线≥10秒→开启选中类型并观察≥10秒→关总开关；动态选未选对照 | 所选类型稳定后计数不增、对照继续；记录Saved t0、策略expires/首次ALLOW证据和首个事件t1分别列；旧策略5秒内到期，之后所有未来回调ALLOW；t1可能受硬件采样影响晚于5秒。sessionId/PID/registrationSeq或等价证据不变，否则同次恢复未测 |
| H02／J,R | 总/全局Hook/包Hook分别关闭、空集合、CONTROL不选；轮换类型1/4/9/10/11 | 只有AND条件成立才丢对应类型；其他类型/包正常；缺硬件类型逐项不可判定，不默认阻断全部类型 |
| H03／R | 仅装APK模块未激活；激活但不选PROBE作用域；每种状态重启目标 | 有基线则持续事件；无报告显示未观察到注入，不能据Root/管理器存在显示生效 |
| H04／R | 改作用域前保留旧进程，再force-stop重开；升级APK后重复 | 不承诺旧进程热注入；新processToken/PID另分会话；缓存null起步，不复用磁盘租约，不重复装Hook |
| H05／J,R | 活跃时关闭/删包；模拟start1000/reply1500/expires6000，now5999/6000；reply2000/2001 | 2000ms刷新+有效读取≤1000ms，正常约3秒；5999可DROP、6000必须ALLOW；1000ms有效、1001ms迟到丢弃；租约从请求开始算 |
| H06／J | 卡住Binder且继续回调；wall clock变化/休眠/时钟回退/未来或负时间/>5秒租约/错误包schema | callback不等待；最多一个in-flight、队列≤1，不无限建线程；elapsedRealtime控制过期；确认错误立即清缓存，坏策略放行 |
| H07／J,R | 已有拦截后Provider确实不可达（专用HOST卸载或测试故障），另测nullBundle/拒绝/坏JSON/未知schema/超时 | 已确认失败清缓存，卡住请求按原租约到期≤5秒放行；目标不崩；仅force-stop可能会被重新启动，不算已证明不可达 |
| H08／S,J,R | 隐藏类/方法/字段缺失、参数不匹配、未知handle；实际设备定位检查 | UNSUPPORTED/ERROR或受限诊断，原调用继续；handle不等于type；不改values、Sensor、注册返回、监听关系 |
| H09／S,J,R | 高频回调查线程；首次初始化后无传感器流量30秒；启停注册 | 回调无Binder/文件/网络/等待；闲置不轮询；报告≤每5秒一条；报告过期不等于确定未注入 |
| H10／R | 同包第二进程分别注册；具备资产时测isolated/共享UID/工作资料/分身 | 普通单包多进程独立策略与报告；特殊UID按合同拒绝并放行；缺资产/环境未测，不从单进程外推 |
| H11／R | 产品开关仍开时框架停模块但不重启；再先关产品后重启目标 | 已注入代码可能继续直到重启，说明准确；产品关闭租约仍适用；框架停用不误称立即撤销 |
| H12／S,R | 审核并在有目标时对照Native ASensorManager/JNI、Direct Channel/厂商路径 | 明确不在v1保证；继续有事件不当Java测试失败，零事件也不反推覆盖，不扩大到系统服务修改 |

### 5.4 跨进程认证、可见性与Provider初始化

真实成功读必须由目标自身UID的ContentResolver.call发起；shell/Root仅作未授权反例。模拟Binder/PackageManager的单测只能记JVM；报告里的自报计数不作独立拦截成功证明。

| ID／级别 | 可重复步骤 | 预期／证据 |
| --- | --- | --- |
| S01／J,E,R | PROBE真实UID get_policy_v1(arg=PROBE)；改arg=HOST/CONTROL；shell再请求PROBE | 本包成功仅最小策略；伪造包与系统shell拒绝、失败无payload；无完整配置/他包名单/存在性泄露 |
| S02／J,E | unknown method/get_all_config/write_config/query/insert/update/delete/bulkInsert/openFile | 全拒绝，revision/磁盘不变；getType可null；extras不能触发越权；无外部配置写入口 |
| S03／J,E,R | 包集合null/空/重复同包/两包、isolated、其他用户、PackageManager异常 | distinct唯一匹配才可读；shared UID拒绝；isolated/未知拒绝；跨用户异常拒绝；clearCallingIdentity前捕获和验证UID/PID，finally恢复 |
| S04／J,E,R | 报告伪造别包/pid、非法UUID、负数/dropped>observed、过长进程名、缺/未知版本/未知枚举、>4096bytes | 无效/越权拒绝；合法报告pid由真实Binder值覆盖；不改配置/授权，仅标进程自报 |
| S05／J,E | 同UID每秒GET6次/report2次；每包>8进程/全局>128报告；另一UID对照 | GET≤5/s、report≤1/s，超额RATE_LIMITED、不立即重试；LRU有界，只保留内存 |
| S06／E,D,R | API30+分列P=PROBE有authority queries与T=NO_QUERY_TARGET无authority queries，先确认Manifest/其他可见性与非零基线，再按同配置/作用域读策略 | 分别记录能否解析/transportState/revision/计数/实际传感器/UI。未声明不必然失败；失败清缓存或原租约≤5秒放行；无报告仍显示未观察到注入/旧报告过期。P成功不推定T覆盖 |
| S07／S,J,E,R | HOST进程已死且不打开Activity，由真实目标先启动Provider，记录Provider/Application初始化顺序 | 先Provider后Application不崩，惰性同一Repository可用，不依赖Activity静态引用；force-stop若不可达另记失联，不能算通过此项 |
| S08／S,E | 解包查权限/queries/Provider导出、文件暴露与日志字段 | 两APK无INTERNET，app无QUERY_ALL_PACKAGES；grantUriPermissions=false；不泄露完整配置/屏幕/输入/读数；无signature读权限误挡目标 |

### 5.5 诊断页、中文界面与真实状态

| ID／级别 | 可重复步骤 | 预期／证据 |
| --- | --- | --- |
| P01／E,D,R | 各类型显式开始/停止；无硬件/注册失败；HOME/广告页后返回 | 存在性/注册返回/计数/最近elapsed/状态可见；onPause注销，onResume不自启；无基线不称拦截成功 |
| P02／E,D | 主传感器页与广告页分别进入，未知scenario | 广告页不采样，控制UI不干扰正例；未知安全反例，无外部敏感动作 |
| U01／S,E,D,R | 保存配置、无报告、WAITING_CONTEXT、INSTALLED+NEVER_READ、OK+UNSUPPORTED、dropped>0分别观察 | 已配置/读取/安装自报/丢弃自报/独立实测分开；无“全面保护”假状态；无框架可启动，不错误加载Xposed类 |
| U02／J,E,R | 记录报告后停止流量，在14999/15000/15001ms观察；重启HOST，换目标PID/token | >15000ms标过期；不恢复在线；进程实例计数分开，不信任报告自带时间 |
| U03／E,D | 列表选择取消、手工合法/非法包名；包不可见/管理器或PROBE未装；返回权限页 | 不完整列表有解释；保存真实；入口失败中文指引；不自动授权/提权 |
| U04／E,D | API35+横竖屏、手势/三键导航、挖孔、分屏、放大字体 | WindowInsets正确，关键开关和按钮可见可点；正例真实bounds仍符合规则；不放宽误触阈值掩盖布局失败 |
| U05／E,D | API33+分列adb与普通下载/文件管理器侧载授权路径 | 出现受限设置时手动引导；未触发只写本安装源未触发，不假称已覆盖；未授权不connected |

## 6. 最终 QA 执行清单与命令

以下为统一集成提交的后续执行步骤，**本轮未运行**。前置工具、设备和测试数据齐备后逐条执行并记退出码。Git Bash可用下列写法；Windows可换gradlew.bat。RUN_ID、SERIAL替换实际值；证据文件属于最终QA任务，本轮不生成。失败保留原文，不能改实现让检查变绿。

### 6.1 固定提交、工具与构建

```bash
pwd
git rev-parse --show-toplevel
git rev-parse HEAD
git status --short
java -version
./gradlew --version
sdkmanager --list_installed
adb version
adb devices -l
emulator -list-avds
```

- [ ] Git根确为Anti-ads而非父目录；记录工作区差异、实际工具版本，使用开发A交付路径。
- [ ] wrapper8.9含官方distributionSha256Sum校验记录；AGP/Kotlin/SDK固定，local.properties/签名不入库。
- [ ] 独立执行全部测试/lint/双APK构建并留退出码（set -o pipefail防tee吞掉Gradle失败）：

```bash
mkdir -p qa-evidence/RUN_ID
set -o pipefail
./gradlew --no-daemon --console=plain --rerun-tasks :core:test :app:testDebugUnitTest :accessibility:testDebugUnitTest :hook:testDebugUnitTest :probe:testDebugUnitTest :app:lintDebug :accessibility:lintDebug :hook:lintDebug :probe:lintDebug :app:assembleDebug :probe:assembleDebug 2>&1 | tee qa-evidence/RUN_ID/gradle.log
printf 'gradle_pipeline_exit=%s\n' "$?"
```

- [ ] 读取core/build/test-results/test及各Android模块build/test-results/testDebugUnitTest，记录实际测试数/失败/跳过；NO-SOURCE和零测试单列。
- [ ] 读取各模块lint-results-debug.html或实际报告位置，错误必须解决，warning解释影响；没有报告不能推定无问题。
- [ ] 把C/A/H/S的JVM行映射到实际测试类/方法，未覆盖标未实测；不以编译代替断言。

### 6.2 APK静态检查

```bash
sha256sum app/build/outputs/apk/debug/app-debug.apk probe/build/outputs/apk/debug/probe-debug.apk
apkanalyzer manifest print app/build/outputs/apk/debug/app-debug.apk
apkanalyzer manifest print probe/build/outputs/apk/debug/probe-debug.apk
apkanalyzer files list app/build/outputs/apk/debug/app-debug.apk
apkanalyzer files list probe/build/outputs/apk/debug/probe-debug.apk
apksigner verify --verbose --print-certs app/build/outputs/apk/debug/app-debug.apk
apksigner verify --verbose --print-certs probe/build/outputs/apk/debug/probe-debug.apk
```

apkanalyzer来自SDK command-line tools，apksigner来自Build Tools；未上PATH用实际绝对路径，不假填执行结果。

- [ ] 包名固定、min29/target35；app的Application、服务、BIND_ACCESSIBILITY_SERVICE、XML、Provider/authority/queries正确；canPerformGestures=false。
- [ ] app含legacy模块metadata、assets/xposed_init及精确入口；compileOnly框架API不打包，检查DEX类定义，类引用不等于打包。
- [ ] probe独立、无模块入口/产品Hook依赖；两个APK无INTERNET，app无QUERY_ALL_PACKAGES；Provider拒绝外部写入。
- [ ] 签名验证成功、明确Debug签名、SHA-256绑定本轮源码与实测APK，重构建后不能挪用旧哈希。

### 6.3 设备、授权与广告夹具

```bash
QA_SERIAL=SERIAL
adb -s "$QA_SERIAL" shell getprop ro.product.model
adb -s "$QA_SERIAL" shell getprop ro.build.version.release
adb -s "$QA_SERIAL" shell getprop ro.build.version.sdk
adb -s "$QA_SERIAL" shell getprop ro.build.version.security_patch
adb -s "$QA_SERIAL" shell getprop ro.build.fingerprint
adb -s "$QA_SERIAL" shell getprop ro.product.cpu.abilist
adb -s "$QA_SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$QA_SERIAL" install -r probe/build/outputs/apk/debug/probe-debug.apk
adb -s "$QA_SERIAL" shell am start -n com.antiads.app/.MainActivity
adb -s "$QA_SERIAL" shell am start -n com.antiads.probe/.MainActivity
adb -s "$QA_SERIAL" shell dumpsys accessibility
adb -s "$QA_SERIAL" shell dumpsys activity activities
```

- [ ] install -r会保留配置，不能直接当C01全新安装；用专用测试数据，清除前确认无用户数据需要保留。
- [ ] 系统授权、受限设置、框架激活/作用域由用户手工操作并记录；不使用ADB写secure setting替代授权流程。
- [ ] 三层无障碍开关和服务连接确认后，逐项启动下列夹具，每次独立重进窗口，再做A07/A09停用和撤权：

```bash
adb -s "$QA_SERIAL" shell am start -n com.antiads.probe/.AdFixtureActivity --es scenario ad_positive
adb -s "$QA_SERIAL" shell am start -n com.antiads.probe/.AdFixtureActivity --es scenario no_ad_label
adb -s "$QA_SERIAL" shell am start -n com.antiads.probe/.AdFixtureActivity --es scenario non_clickable
adb -s "$QA_SERIAL" shell am start -n com.antiads.probe/.AdFixtureActivity --es scenario bottom_button
adb -s "$QA_SERIAL" shell am start -n com.antiads.probe/.AdFixtureActivity --es scenario editable_window
adb -s "$QA_SERIAL" shell am start -n com.antiads.probe/.AdFixtureActivity --es scenario unknown_case
```

- [ ] API35+同步U04，记录真实bounds和短录屏，不能人工点过后声称自动跳过。
- [ ] Root真机先完成激活/作用域/重启，后按同会话基线→开→关实验；无Root就不宣称Hook实测。
- [ ] S06分列有/无queries两个目标；目标未完成或无非零基线则第三方覆盖未实测。

### 6.4 H01持注册和真实配置切换

队长已安排：app/probe测试runner统一AndroidX runner1.6.2/ext:junit1.2.1；D提供probe持注册观测，B提供只在androidTest的宿主同UID配置切换，A在t11统一接线并构建测试APK。**这些是开发交付前提，不是本轮已验证可用。** 安装实际生成并经签名核验的测试APK，再选择：

1. probe instrumentation持注册并分屏人工切管理端；必须证明生命周期/注册不变、未选对照持续。
2. probe保持前台，app instrumentation调用真实Repository切总开关，不依赖导出写接口。只有实际runner/测试类存在且构建完成才执行：

```bash
adb -s "$QA_SERIAL" shell am instrument -w -e class com.antiads.app.ConfigToggleTest -e action master_on com.antiads.app.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$QA_SERIAL" shell am instrument -w -e class com.antiads.app.ConfigToggleTest -e action master_off com.antiads.app.test/androidx.test.runner.AndroidJUnitRunner
```

两条命令不能紧接着跑：先按H01在关闭状态取得≥10秒基线，再执行master_on、等待稳定并测≥10秒，再master_off，保存分段计数与时间。记录测试报告的result/revision_before/revision_after/flipped/elapsed_realtime_ms；SAVED且flipped=true才是实际切换，ALREADY_AT_TARGET不能作为改变证据。此测试不代替真实目标UID成功读取Provider的证据。若启动instrumentation影响probe生命周期或传感器对照，该轮不可判定。

5秒是旧策略截止及之后回调必须ALLOW的约束。JVM用expires-1/expires精确判定；设备记录t0和首回调t1及采样条件，**t1-t0不能独自证明租约是否超时**。只有对照持续且已观测到截止后仍在丢弃目标回调，才可据此认定放行违规；观测不足记精确时限未实测。

### 6.5 故障与Provider反例

C04仅限专用Debug安装：先保存良好配置，force-stop HOST，run-as备份私有文件，写入坏JSON后冷启动观察，再停止并恢复备份。例子如下；Git Bash使用MSYS_NO_PATHCONV=1防止设备路径被转换：

```bash
adb -s "$QA_SERIAL" shell am force-stop com.antiads.app
MSYS_NO_PATHCONV=1 adb -s "$QA_SERIAL" shell run-as com.antiads.app cp files/protection-config-v1.json files/qa-config-backup.json
MSYS_NO_PATHCONV=1 adb -s "$QA_SERIAL" shell 'run-as com.antiads.app sh -c "printf x > files/protection-config-v1.json"'
adb -s "$QA_SERIAL" shell am start -n com.antiads.app/.MainActivity
```

核查全关/health/中文原因后恢复：

```bash
adb -s "$QA_SERIAL" shell am force-stop com.antiads.app
MSYS_NO_PATHCONV=1 adb -s "$QA_SERIAL" shell run-as com.antiads.app cp files/qa-config-backup.json files/protection-config-v1.json
MSYS_NO_PATHCONV=1 adb -s "$QA_SERIAL" shell run-as com.antiads.app rm files/qa-config-backup.json
adb -s "$QA_SERIAL" shell am start -n com.antiads.app/.MainActivity
```

备份失败、run-as拒绝、AtomicFile残留恢复了旧文件时停止注入并记前提失败，不能把未注入成功当坏配置恢复通过。其他写失败、Binder卡住等用开发已有可控测试，不新增产品后门。

shell的S01/S02拒绝反例：

```bash
adb -s "$QA_SERIAL" shell content call --uri content://com.antiads.app.config --method get_policy_v1 --arg com.antiads.probe
adb -s "$QA_SERIAL" shell content call --uri content://com.antiads.app.config --method write_config --arg com.antiads.probe
adb -s "$QA_SERIAL" shell content query --uri content://com.antiads.app.config
```

返回拒绝可能是非零退出或Bundle错误，按实际记录。这不覆盖真实目标成功读、共享UID、跨用户。设备日志只保留必要测试包和字段，对外移除设备标识/无关应用/输入内容。

## 7. 协作落实、未具备资产与最终收束

| 所有者 | 已同步需求／后续交付 | 当前证据边界 |
| --- | --- | --- |
| A／工具链与core | 实际工具/SDK/AVD/设备清单；开关/序列化/几何/时间/租约单测 | 待最终独立复跑，口头设计不计通过 |
| B／app | Repository health/revision/错误、Provider身份/拒绝/限流、初始化先后、状态事实；ConfigToggleTest | B已报告测试源码设计/交付，尚无本轮构建设备证据 |
| C／无障碍 | 可控时钟/调度/快照/节点/performAction替身覆盖竞态与节流；页面变化仍独立观察 | 已确认模块内JVM测试方案，不新增公共接口；尚未独立运行 |
| D／Hook/probe | 五scenario、真实注册/计数/时间、生命周期/注册次数；慢/坏/卡住回复、有界线程；持注册instrumentation | 已确认设计，需t11接线/测试APK及真机；无Root诊断服务兜底 |
| QA后续t14 | 独立资产`qa/fixtures/noqueries`，包`com.antiads.fixture.target`，不声明Provider authority queries | 队长已排任务；本轮不实现，主工程仍五模块；t14前T目标未具备，t3前复核实际Manifest |
| Plan／集成 | API29–37待测矩阵、compile/target35不变、同次注册/5秒语义澄清 | Plan已确认不改公共签名；合同实验步骤由后续集成同步 |

S06至少分别保存P/T的applicationId、版本/targetSdk/UID、Manifest queries和其他可见性条件、API/机型、框架版本/作用域、installState/transportState/policyRevision、observed/dropped、目标独立计数/UI文案。无策略时不得DROP；T读取成功或因平台可见性失败都据实记录，失败后的放行和状态是真正判据。不得给T加queries/QUERY_ALL_PACKAGES或修改第三方APK绕过测试条件。

最终统一版本的签收检查：

- [ ] 固定commit/APK，独立完整Gradle命令、测试数、lint和失败原文齐备；NO-SOURCE不冒充测试通过。
- [ ] 双APK入口/权限/Provider/签名/SHA-256符合合同，无占位成功或可见性假覆盖。
- [ ] C01–C07、R01–R02逐项结果：默认全关、坏配置、并发与关闭边界齐全。
- [ ] A01–A10逐项结果：正例页面变化、普通跳过等反例无误触、撤权/竞态与生命周期。
- [ ] H01–H12逐项结果：每设备/框架/类型分列；同次/重新注册、框架/产品停用、Java/Native分清。
- [ ] S01–S08逐项结果：真实UID读取、外部不可写、Provider先初始化与有/无queries对照。
- [ ] P01–P02、U01–U05逐项结果：硬件缺失/注册失败/过期/未连接均无假成功，API35 Insets有证据。
- [ ] API29–37每行保留实际/未实测；模拟器、非Root真机、Root真机、OEM和特殊UID分别写清。
- [ ] 中文安装、权限、作用域、重启、停用卸载文档与行为一致；默认风险提示与Native/Direct Channel边界没有扩大承诺。
- [ ] 阻断/高优先级问题交Plan；修复后按新提交重测受影响项与必要集成，不移用旧APK结果。
- [ ] 输出独立QA报告及缺口表；不把本计划自检称产品审查通过，QA不建仓发布。

