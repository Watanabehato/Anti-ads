# Provider 可见性独立夹具与验证记录

任务 t14，qa-release。用于 [QA计划 S06](qa-plan.md) 的 T 目标：`com.antiads.fixture.target`。它是`qa/fixtures/noqueries/`下的独立Gradle根项目，主工程仍五模块，既有app/probe包名和输出路径不变。源代码、构建方法和UI字段见 [夹具README](../qa/fixtures/noqueries/README.md)。

## 范围与判断边界

| 目标 | 包名 | Manifest可见性前提 | 用途 |
| --- | --- | --- | --- |
| P | com.antiads.probe | 产品诊断应用声明com.antiads.app.config authority queries，最终APK仍由t3核查 | 有声明的对照 |
| T | com.antiads.fixture.target | 本夹具无queries、无QUERY_ALL_PACKAGES、无INTERNET、无产品模块依赖 | 未声明目标，检查真实平台处理 |

没有queries不必然代表不可见；自动可见性、安装关系、系统/OEM、其他用户环境都可能影响。P读成功不能外推T；T成功/失败都据实记录。不能给T增加queries、QUERY_ALL_PACKAGES、修改目标APK或用shell成功结果代替真实目标UID调用。

夹具仅观察。真实registerListener结果、onSensorChanged计数与时间独立于读取结果；没有策略时夹具自身不会停用传感器。产品Hook是否在无策略时确实放行，仍需Root设备执行，不能由源码设计推出。

## 准备、构建与安装

固定JDK17／Gradle8.9／AGP8.7.2／compile-target35／min29／BuildTools35.0.0；无Kotlin、AndroidX、core、Xposed依赖。复用主wrapper，不复制wrapper/修改主settings。独立项目的local.properties须单独设置，不能误以为会继承主项目。在仓库根执行：

```bash
# Git for Windows；其他系统把sdk.dir设为真实SDK绝对路径
printf 'sdk.dir=../../../.tooling/android-sdk\n' > qa/fixtures/noqueries/local.properties
bash ./gradlew --no-daemon -p qa/fixtures/noqueries assembleDebug lintDebug
sha256sum qa/fixtures/noqueries/build/outputs/apk/debug/noqueries-debug.apk
```

工具路径取自 [build.md](build.md)。local.properties/build/.gradle受夹具自身.gitignore忽略；不提交本机SDK路径或签名私钥。lint对warning也失败。输出APK为Debug签名，用于测试，不是生产发布。

```bash
QA_SERIAL=SERIAL
.tooling/android-sdk/platform-tools/adb.exe devices -l
.tooling/android-sdk/platform-tools/adb.exe -s "$QA_SERIAL" install -r qa/fixtures/noqueries/build/outputs/apk/debug/noqueries-debug.apk
.tooling/android-sdk/platform-tools/adb.exe -s "$QA_SERIAL" shell am start -n com.antiads.fixture.target/.MainActivity
.tooling/android-sdk/platform-tools/adb.exe -s "$QA_SERIAL" logcat -s NoQueriesFixture:I
```

## 设备步骤与预期

| 步骤 | 操作 | 观察与预期 |
| --- | --- | --- |
| V01 基线 | 不启用本包防护，保持T前台，点开始并运动≥10秒 | 按类型记录exists、真实注册返回、计数差值/最近elapsed。无硬件/false/零基线记不可判定，不称成功 |
| V02 无宿主读取 | 在未安装HOST的专用设备点一次读取 | 如实显示异常或null；期间计数继续。调用异步、无自动重试；没有设备则未实测 |
| V03 普通安装读取 | 安装HOST，不把HOST打开/查询作为T的先决条件；分别在P/T自身进程触发只读get_policy_v1，arg各自真实包名 | 记录原始ok/error/payload、缺字段、异常、UID/PID、耗时与配置revision。不把Bundle ok当Hook成功；shell只可作未授权负例 |
| V04 框架/作用域 | 手工激活兼容LSPosed并把P/T分别选入作用域，停止并重开两目标；产品总开关先关 | 重启后新PID/会话；各自建立非零基线。没有新鲜安装报告不可写已注入 |
| V05 已配策略 | HOST明确添加T包名，开总/全局Hook/本包Hook，只选实际有基线的类型；保留实际可持续刺激的未选对照 | T读通时记录真实策略与传感器行为；读不通时无策略应放行，T独立计数应继续。不得因P成功推断T成功 |
| V06 关闭/失联 | 同次注册中关闭真实配置；另在专用设备使Provider确实不可达并记录失败 | 已确认读失败立即清缓存，卡住请求按原租约≤5秒失效，之后未来回调应ALLOW；硬件下一事件可因采样周期晚于5秒，不能单靠t1-t0判租约 |
| V07 生命周期 | 显式停止、HOME后返回、旋转、重启 | 停止/onPause注销，回前台不自动采样；每次显式开始计数清零，注册轮次递增；Activity重建会话UUID变化 |
| V08 布局/兼容 | API29/30+/35及可用新系统上旋转、导航方式、字体、分屏 | 关键按钮、状态和数据可见，WindowInsets不遮挡。实际设备缺失行保留未测 |

T会观测类型1/4/9/10/11及5/8。类型5/8多为变化上报，需要实际改变光照/遮挡；不得固定缺失或静止类型作“持续对照”。H01/V06若普通切到HOST，T的onPause会注销，零事件不能解释成Hook。必须多Resume分屏且RESUMED/注册轮次/注销次数不变，或已接线宿主同UIDandroidTest切配置。缺任何必要证据则同次恢复未测。

手动读取只有一个进程级后台线程和一个在途调用，堵塞时不替换线程；onResume的500ms刷新只重画内存，不访问Provider。诊断结果显示原始协议三字段，字符串>8192字符明确截断，无产品策略解释或写配置路径。生命周期日志只含测试会话/PID/轮次/时间，不含传感器读数。异常不会被改写成成功。

## 证据模板

每行都记录：commit/工作区差异、P/T APK SHA-256/版本/targetSdk/UID、Manifest queries及其他可见性条件、设备/API/QPR/补丁/ABI/用户、框架完整版本/作用域、进程PID/会话、配置revision、独立基线/开启/关闭计数、原始读取结果、installState/transportState/observed/dropped（注明自报）、页面文案、原始日志/截图、预期/实际/清理结果。

`Vxx | P或T | STATIC/BUILD/EMULATOR/DEVICE | 环境 | 步骤 | 预期 | 实际 | 通过/失败/未实测 | 证据位置`

报告缺失不等于读取失败：分别保存T手动调用和产品Hook自报，前者不能替代Hook内部身份/可见性证据。Provider失败后的放行是产品验收点，本任务不实现防护逻辑。

## 本轮验证

执行日期：2026-09-20，Git for Windows／Windows 10.0.19045。主机JDK为Temurin17.0.20.1+1，adb1.0.41/platform-tools37.0.1。环境盘点时仓库HEAD=`893f3c154caeffb96cf28b8f7bf39d8dd1260c60`；本夹具当时为新增未提交文件，不能把该HEAD冒充含夹具的提交。夹具实际构建输入由 [源码SHA-256清单](../qa/fixtures/noqueries/evidence/source-sha256.txt)固定，后续集成提交应另记。

| 检查 | 实际结果 | 证据 |
| --- | --- | --- |
| 首轮规定构建/lint | exit=1；APK组装成功，lint共5 errors | [首轮日志](../qa/fixtures/noqueries/evidence/build-first.txt)、[首轮lint](../qa/fixtures/noqueries/evidence/lint-first.txt) |
| 修复后同一命令 | exit=0；BUILD SUCCESSFUL，1m58s，43 tasks（22执行/21 up-to-date） | [最终日志](../qa/fixtures/noqueries/evidence/build-final.txt) |
| 严格lint | 0 issue，未设置baseline/disable/ignore；warningsAsErrors=true | [最终lint XML](../qa/fixtures/noqueries/evidence/lint-final.xml) |
| 合并Manifest | 包名正确、min29/target35；无queries/uses-permission/provider/service | [合并XML](../qa/fixtures/noqueries/evidence/merged-manifest.xml) |
| APK内Manifest与元数据 | aapt2 xmltree/badging各exit=0；compile/target35、min29、debuggable=true；无queries/权限 | [包内Manifest](../qa/fixtures/noqueries/evidence/apk-manifest-tree.txt)、[badging](../qa/fixtures/noqueries/evidence/apk-badging.txt) |
| 签名 | apksigner verify exit=0，v2验证通过，Android Debug证书 | [签名结果](../qa/fixtures/noqueries/evidence/apk-signature.txt) |
| 忽略规则 | git check-ignore exit=0，local.properties/build APK/.gradle已忽略 | 只提交可重建源码/文档/文本证据；APK单独交付 |
| 设备 | adb devices -l exit=0，列表为空；SDK未发现emulator.exe | [环境记录](../qa/fixtures/noqueries/evidence/environment.txt) |

首轮5项为同一local.properties盘符转义问题的3次诊断、PluralsCandidate文本诊断、DataExtractionRules缺失。修复为夹具根相对SDK路径（无盘符）、显式换行/中文字段、禁止备份和设备迁移的规则；未降低lint严格度。仅修改夹具目录与本文，无主工程/产品代码变更。

最终APK：`qa/fixtures/noqueries/build/outputs/apk/debug/noqueries-debug.apk`，包`com.antiads.fixture.target`，versionCode1／versionName1.0-qa，Debug签名。

SHA-256：`acbbf5bda0b4bc67d8f3b6656ffc287fbf49e5472274eefe39c8cfb24386fecc`；原始校验见 [APK SHA-256](../qa/fixtures/noqueries/evidence/apk-sha256.txt)。换机重新Debug签名或更改源码可能改变哈希，必须对实际安装APK重新记录。

**未验证：** 未安装运行APK、未执行模拟器/真机/UI/传感器/真实Binder/LSPosed测试；没有API30+设备、Root设备和可用模拟器。V01–V08全部保留设备未实测。只完成源码可测性检查、真实构建/lint、合并及包内Manifest、签名和哈希检查。未编写或执行单元/设备测试；Gradle的lint模型/NO-SOURCE不计测试通过。t3仍需实际设备执行本文件步骤，本夹具不是经过独立审查的产品实现。

