# Android 10 模拟器环境准备记录

任务 t15，qa-release。仅准备最终集成后t3使用的环境，不安装旧骨架APK、不将环境准备当产品运行验证。范围为本项目`.tooling/`与本文；不修改系统功能、驱动、BIOS、真实手机、用户AVD、产品或主构建。

## 当前主机盘点

已阅读 [构建基线](build.md)，复用`.tooling/android-sdk`及Temurin17.0.20.1+1（`.tooling/jdk17/jdk-17.0.20.1+1`）。2026-09-20实际观察：

| 项目 | 实际结果 | 限制 |
| --- | --- | --- |
| adb devices -l | exit=0，设备列表为空 | 不连接或操作用户手机 |
| 主机 | Windows10专业版10.0.19045，x64；i3-10100T，4核8线程 | WMI只读查询 |
| 内存 | 总物理约15.87GiB；记录快照FreePhysicalMemory=3382460KiB，约3.23GiB | 可用量会随并行构建变化，不将总内存当可用量 |
| 磁盘 | D盘828GiB可用，C盘87GiB可用（df -h近似） | 本任务放D盘项目内 |
| HypervisorPresent | true | 不代表Android Emulator能使用 |
| Windows可选功能 | HypervisorPlatform InstallState=2；VirtualMachinePlatform=1；Microsoft-Hyper-V-All=2 | WMI含义1=启用、2=禁用；未更改任何功能 |
| AEHD/HAXM服务 | aehd/gvm/intelhaxm均未查到 | 未安装或启动驱动 |
| CPU虚拟化WMI标志 | VirtualizationFirmwareEnabled/VMMonitorModeExtensions/SLAT均false | 已有hypervisor时可见性可能受影响，不能据此断定BIOS虚拟化关闭 |
| 既有AVD | 默认`C:/Users/Admin/.android/avd`目录不存在；ANDROID_AVD_HOME/ANDROID_USER_HOME/ANDROID_EMULATOR_HOME未设置 | 未改或删除用户AVD；不能推断其他磁盘不存在AVD |
| 初始SDK | Build Tools34.0.0/35.0.0、platform-tools37.0.1、platforms;android-35 rev2 | 初始无emulator或API29镜像；34.0.0已存在，非本任务安装 |

只读主机查询命令全文与JSON保存于`.tooling/emulator-qa/host-command.txt`、`host-capabilities.json`。首次PowerShell中文输出编码不正确，第二次只设置本命令输出为UTF-8重新查询，未修改主机配置。

## 官方组件发现与准备策略

执行（仓库根）：

```bash
JAVA_HOME="D:/test/Anti-ads/.tooling/jdk17/jdk-17.0.20.1+1" .tooling/android-sdk/cmdline-tools/latest/bin/sdkmanager.bat --sdk_root="D:/test/Anti-ads/.tooling/android-sdk" --list
```

exit=0。官方列表实际提供emulator37.1.11、`system-images;android-29;default;x86_64` rev8、google_apis x86_64 rev13、google_apis_playstore x86_64 rev9。后续优先选default x86_64，无需Google登录/Play服务。列出可下载版本不等于已安装镜像。

command-line tools12.0警告“只理解SDK XML至3，遇到4”，但本次列表实际成功，保留原始警告，不伪称工具已升级。列表日志`.tooling/emulator-qa/sdk-list.txt`。

先仅安装官方emulator，再用`emulator -accel-check`决定是否值得下载API29镜像。实际检查exit=0，原文为“WHPX(10.0.19045) is installed and usable.”；因此WMI的可选功能状态不是本机不可用的判据，继续安装API29镜像。本任务未更改系统功能。安装命令仅指定emulator，不包含驱动或系统功能操作。合理重试上限为安装首次加一次同源重试，持续失败则记录URL/错误并停止；不换非官方APK或镜像。

## 实际安装、AVD与启动

| 操作 | 命令核心 | 结果／管理job |
| --- | --- | --- |
| 官方SDK列表 | sdkmanager --sdk_root=本项目SDK --list | exit=0，job `bash-3`已完成并收集 |
| 安装emulator | sdkmanager --sdk_root=本项目SDK --install "emulator" --verbose | exit=0，job `bash-4`已完成并收集；37.1.11.0/build15917651 |
| 加速检查 | emulator.exe -accel-check | exit=0，输出accel:0与WHPX可用 |
| GPU选项检查 | emulator.exe -help-gpu | exit=0，明确支持swiftshader |
| 安装API29 | sdkmanager --sdk_root=本项目SDK --install "system-images;android-29;default;x86_64" --verbose | exit=0，job `bash-5`已完成并收集；revision8/API29/x86_64/default |
| 设备模板 | avdmanager list device | exit=0，pixel模板存在 |
| 建立独立AVD | avdmanager create avd --name AntiAds_QA_API29 --package "system-images;android-29;default;x86_64" --device pixel --path 本项目专用目录 | exit=0，不使用--force，不覆盖既有AVD |
| 启动 | 下列完整命令，由受管理background job启动 | `bash-6`，启动不等于开机完成；结果见下一节 |

所有包由官方sdkmanager默认SDK仓库获取（Google SDK仓库域名`dl.google.com`），未下载第三方APK/镜像。安装日志在`.tooling/emulator-qa/install-emulator.txt`、`install-api29-image.txt`。镜像安装过程出现一次远程manifest连接超时（`java.net.ConnectException: Connection timed out: connect`），该警告未披露具体URL；同次sdkmanager仍完成所选镜像并exit=0，未进行额外重试。不能把未给出的失败URL自行补造。组件包路径和实际revision另由已安装source.properties核实。

专用路径与身份：

- ANDROID_AVD_HOME=`D:/test/Anti-ads/.tooling/emulator-qa/avd`。
- ANDROID_USER_HOME=`D:/test/Anti-ads/.tooling/emulator-qa/android-user`，命令级设置，不覆盖HOME或改全局环境。
- AVD=`AntiAds_QA_API29`；数据目录`.tooling/emulator-qa/avd/AntiAds_QA_API29.avd`。
- 计划/实际绑定控制台端口5580、adb端口5581，serial=`emulator-5580`；启动前Get-NetTCPConnection未发现这两个端口被占用。
- 使用Pixel模板1080×1920；启动请求2核、1536MiB；emulator日志明确将RAM提高到2048MiB，实际按2048MiB记录。使用swiftshader、无窗口/音频/摄像头/快照，不打开真实摄像头。未配置Google账号，无Play Store镜像。

完整启动命令（仓库根）；复用时必须先确认没有本任务的在运行实例及端口冲突，不能再启动第二台：

```bash
ANDROID_HOME="D:/test/Anti-ads/.tooling/android-sdk" ANDROID_USER_HOME="D:/test/Anti-ads/.tooling/emulator-qa/android-user" ANDROID_AVD_HOME="D:/test/Anti-ads/.tooling/emulator-qa/avd" .tooling/android-sdk/emulator/emulator.exe -avd AntiAds_QA_API29 -port 5580 -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader -memory 1536 -cores 2 -accel on -camera-back none -camera-front none > .tooling/emulator-qa/emulator.log 2>&1
```

命令副本`.tooling/emulator-qa/launch-command.txt`；加速输出`accel-check.txt`，版本输出`emulator-version.txt`，AVD建立日志`create-avd.txt`。这些本地大组件/数据/日志均在忽略的.tooling，仓库交付以本文实测摘要为准，不提交完整SDK/镜像。

## 最终结论：API29环境可用，产品尚未运行

已实际验证：

```text
adb devices -l:
emulator-5580  device product:sdk_phone_x86_64 model:Android_SDK_built_for_x86_64 device:generic_x86_64
get-state: device
sys.boot_completed: 1
ro.build.version.release: 10
ro.build.version.sdk: 29
ro.product.cpu.abi: x86_64
ro.build.fingerprint: Android/sdk_phone_x86_64/generic_x86_64:10/QSR1.210820.001/7663313:userdebug/test-keys
ro.build.version.security_patch: 2019-09-05
emu avd name: AntiAds_QA_API29 (OK)
```

检查命令组exit=0，无超时；原始证据`.tooling/emulator-qa/boot-verification.txt`。日志显示`Windows Hypervisor Platform accelerator is operational`、`Boot completed in 62331 ms`。这证明该模拟器已启动并可通过adb操作，不证明Anti-ads安装、无障碍、Provider、LSPosed或传感器拦截已经通过。任务中没有安装或运行任何项目APK。

可用于t3的API29安装、基础UI、系统授权与诊断实验；模拟器传感器必须说明其模拟来源，不能冒充真机传感器。此API29官方镜像实际安全补丁为2019-09-05，不能代表较新OEM或补丁。API30+包可见性、API35+ WindowInsets、真实Root/框架组合仍需其他环境；userdebug/test-keys不等于已安装或验证LSPosed。

### 启动过程中的实际异常

1. 首次adb探测exit=1：默认adb服务启动后仍未找到emulator-5580，不能当开机成功。当时emulator job仍运行，随后日志显示已完成开机。
2. 为诊断连接尝试了额外受管理adb server：job `bash-8`，命令`adb.exe -L tcp:127.0.0.1:5037 nodaemon server`。它exit=127，日志为`could not install *smartsocket* listener: listening on specified hostname currently unsupported`。该job已结束并收集，无后台残留，不把它写成成功的常驻服务。
3. 后续默认adb服务已在127.0.0.1:5037监听（观测PID3728），使用一次`wait-for-device`与getprop组（整组15秒超时上限）完成上述成功验证；未再次重启或创建第二台模拟器，也未继续替换adb服务。无需t3使用不支持的-L写法。
4. emulator启动日志另含旧镜像feature/update-check警告及将RAM提高到2048MiB的提示；已完成启动，不隐去这些日志。随后主机可用内存快照2448600KiB（约2.34GiB），并行构建期间注意资源变化，不能把最初可用内存当固定保证。

## t3交接与后续清理

**唯一保留运行的本任务实例：** 管理job `bash-6`（qa-release会话所有），AVD `AntiAds_QA_API29`，adb serial `emulator-5580`，控制台5580／adb5581。此job是长时运行进程，不填“exit=0”；最后检查为running。安装/list jobs bash-3/4/5都已结束exit=0，额外adb诊断job bash-8已结束exit=127，全部已收集。

后续在同一主机使用：

```bash
.tooling/android-sdk/platform-tools/adb.exe devices -l
.tooling/android-sdk/platform-tools/adb.exe -s emulator-5580 emu avd name
.tooling/android-sdk/platform-tools/adb.exe -s emulator-5580 shell getprop sys.boot_completed
```

确认名称仍为AntiAds_QA_API29、boot标志仍为1后，t3才安装其固定commit/SHA-256的完整集成APK与测试APK；不要拿旧骨架或本任务环境结论当产品测试证据。不设置全局ANDROID_SERIAL，以免误操作其他后来连接的设备。

最终QA结束后由qa-release停止本任务创建的实例；先核对AVD名称，随后仅对该serial执行：

```bash
.tooling/android-sdk/platform-tools/adb.exe -s emulator-5580 emu kill
.tooling/android-sdk/platform-tools/adb.exe devices -l
```

再收集job bash-6的终态；若adb无法关闭，使用受管理`job_kill(job_id="bash-6")`，确认本实例退出，不全局结束qemu/adb或用户进程。不要对全机执行adb kill-server，以免影响后来的其他设备。若需删除可丢弃AVD，在它已停止且证据已保存后，用本任务同一ANDROID_AVD_HOME/ANDROID_USER_HOME执行：

```bash
JAVA_HOME="D:/test/Anti-ads/.tooling/jdk17/jdk-17.0.20.1+1" ANDROID_USER_HOME="D:/test/Anti-ads/.tooling/emulator-qa/android-user" ANDROID_AVD_HOME="D:/test/Anti-ads/.tooling/emulator-qa/avd" .tooling/android-sdk/cmdline-tools/latest/bin/avdmanager.bat delete avd --name AntiAds_QA_API29
```

模拟器/镜像安装包保留在项目SDK以便复用，不在本任务结束时卸载。若跨会话job引用不可用，按serial核对名称后的emu kill仍可清理。若模拟器已经退出，复用本文启动命令应重新通过受管理background job启动并记录新job_id，不能把旧job状态当活跃状态。

### 本地证据索引

`.tooling/emulator-qa/`包含：host-command.txt、host-capabilities.json、sdk-list.txt、install-emulator.txt、install-api29-image.txt、accel-check.txt、emulator-version.txt、device-profiles.txt、create-avd.txt、launch-command.txt、emulator.log、adb-devices-after-start.txt（首次未连上）、adb-server.log（失败诊断）、boot-verification.txt（最终成功）。AVD数据和证据均在本项目内；本文给出可公开的必要结论，无产品/主构建文件改动、无系统驱动或功能变更、无smoke-test脚本。


