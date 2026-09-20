# 无 queries 的独立 Android 测试目标

本项目是 Anti-ads 的可审计 QA 资产，不是主工程第六模块，也不是防护实现。固定包名 `com.antiads.fixture.target`；纯 Java／原生 Activity，无第三方依赖。源代码 Manifest 不含 queries、INTERNET、QUERY_ALL_PACKAGES 或其他权限，不复制产品策略。系统是否允许目标解析 Provider 必须实测，不能由“没写queries”直接推定失败。

## 构建与安装

在 Anti-ads 仓库根执行，复用其 Gradle8.9 wrapper。工具链：JDK17、AGP8.7.2、compile/target35、min29、BuildTools35.0.0。先按 [主机工具说明](../../../docs/build.md)准备SDK。主工程的local.properties不会自动供独立根项目使用；为本夹具写一个忽略的本机文件，或设置ANDROID_HOME。

Git for Windows示例（仓库根）：

```bash
printf 'sdk.dir=../../../.tooling/android-sdk\n' > qa/fixtures/noqueries/local.properties
bash ./gradlew --no-daemon -p qa/fixtures/noqueries assembleDebug lintDebug
```

Linux/macOS将sdk.dir设为实际SDK绝对路径，并配置JDK17。Windows也可用gradlew.bat。禁止把个人local.properties、build/.gradle、签名私钥提交。lint将warning也视作失败。构建不依赖Kotlin、AndroidX、core或Xposed；不会改主settings。

产物：`qa/fixtures/noqueries/build/outputs/apk/debug/noqueries-debug.apk`（Debug签名）。从仓库根执行，adb须来自SDK且SERIAL换成实际设备：

```bash
QA_SERIAL=SERIAL
adb -s "$QA_SERIAL" install -r qa/fixtures/noqueries/build/outputs/apk/debug/noqueries-debug.apk
adb -s "$QA_SERIAL" shell am start -n com.antiads.fixture.target/.MainActivity
adb -s "$QA_SERIAL" logcat -s NoQueriesFixture:I
```

## 页面行为和数据定义

- 显示自身包名、真实UID/PID、API、Activity会话UUID、生命周期、RESUMED、注册轮次及注销调用次数。旋转/重建即新会话；进程重启按PID/UUID分段。
- “开始新一轮采样”清零各类型计数并调用真实registerListener(SENSOR_DELAY_NORMAL)。观测类型1/4/9/10/11，以及常见未选对照5/8；缺传感器显示exists=false，未调用注册显示NOT_ATTEMPTED。
- 每类型显示实际硬件名、注册返回true/false或异常类型、状态、数据回调数、最近接收elapsedRealtime毫秒、注册成功/注销调用时刻。数值0表示尚未记录，不是时间为0的真实回调。onAccuracyChanged不算数据事件，不保存传感器读数。
- “停止采样”、onPause、onDestroy注销；返回前台不自动重新注册。开始时前台保持屏幕常亮，停止/暂停撤销。对照类型5/8可能只在光照/遮挡变化时回调；选有非零基线且可持续刺激的对照，不能把静止当拦截。
- 只有用户点“以自身UID读取一次策略”才调用一次ContentResolver.call，URI=content://com.antiads.app.config，method=get_policy_v1，arg由getPackageName取得。后台单线程处理，进程最多一个在途请求；读取卡住时按钮保持禁用，不创建替代线程。页面重建仍共享在途状态。
- 结果显示请求/完成elapsed时间、耗时、实际ok/error/payload值、字段缺失/null/异常类型。只显示协议的三个字段，字符串超8192字符明确标TRUNCATED，不解析策略、不据结果启停传感器，不写配置。读取成功不等于Hook安装，读取失败也不等于传感器消失。
- UI每500ms重画内存，不轮询Provider；日志只在生命周期/注册启停记录会话信息，不逐事件打日志，不输出传感器读数。

## 最小观察流程

1. 未安装HOST或产品开关关闭时，保持本页RESUMED，显式开始采样≥10秒并轻微运动，记录真实存在、注册true、非零计数的类型。无基线的类型只能写不可判定。
2. 点一次读取；记录异常/null/Bundle原文，确认此次读取不会影响采样计数。安装HOST并配置本包后再次手动读取，记录结果；不要为读通增加queries/权限或改目标APK。
3. Root/兼容框架设备上手工激活模块并选本包作用域，停止并重开本目标。先保持总开关关闭建立新会话基线，再在HOST里配置本包Hook开关/类型，进行开关对照。
4. 本目标onPause注销。普通全屏切HOST后的计数停止不能证明拦截。关闭恢复测试需多Resume分屏保持本页RESUMED、注册轮次/注销次数不变，或使用已接线的宿主androidTest切真实配置；否则只记录重新注册恢复。
5. 与有Provider queries的`com.antiads.probe`分别记录API/版本/UID/作用域/实际查询/报告/独立计数，不从probe成功推断本目标成功。没有有效策略时产品应放行；夹具本身从不执行丢弃，仍需设备证据证明Hook的放行。

进一步步骤、证据和本轮构建结果见 [Provider可见性验收文档](../../../docs/provider-visibility-fixture.md)。本轮规定命令已执行exit=0，严格lint 0 issue，合并及APK内Manifest确认无queries/权限，Debug签名验证通过。APK SHA-256：acbbf5bda0b4bc67d8f3b6656ffc287fbf49e5472274eefe39c8cfb24386fecc。设备列表为空，尚未安装运行；构建成功不等于API30+可见性、实际Binder身份或Root Hook已验证。
