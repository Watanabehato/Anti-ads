# Android 10 模拟器环境准备记录

任务 t15，qa-release。下方原始盘点和启动结果是t15交接时的历史记录；当前状态以文末“t3实际复用与清理”“t17 修复版复测的设备复用与清理”两节及[独立QA报告](verification.md)为准。t15仅准备环境，不将准备结果当产品验证；t3随后使用完整集成APK。范围为本项目工具和文档，不修改系统功能、驱动、BIOS、真实手机、用户AVD、产品或主构建。

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

**t15交接时唯一保留运行的本任务实例（历史状态）：** 管理job `bash-6`（qa-release会话所有），AVD `AntiAds_QA_API29`，adb serial `emulator-5580`，控制台5580／adb5581。此job是长时运行进程，不填“exit=0”；最后检查为running。安装/list jobs bash-3/4/5都已结束exit=0，额外adb诊断job bash-8已结束exit=127，全部已收集。

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

## t3实际复用与清理（2026-09-20）

- t3接手时旧bash-6已经不可查询，adb列表空、5580/5581无监听、无emulator/qemu进程。未声称t15到t3持续在线；复用本项目SDK、镜像和AVD，以自有bash-11重新启动。
- bash-11实际运行自2026-09-20T09:19:00.225Z至10:20:52.452Z；AVD仍为AntiAds_QA_API29，serial仍为emulator-5580。启动后两次独立确认device、boot=1和AVD名称，再安装固定b19f67f的五个完整APK。没有单独下载另一套工具链。
- 额外adb服务器bash-12因5037已占用exit127，已收集；继续使用原有服务器。重复的127.0.0.1:5581运输连接已disconnect，所有实际设备操作都限定emulator-5580。
- app instrumentation导致宿主被强停，系统一度保留DEAD/Binding连接；那段时间“不点击”不计关闭测试通过。重启本AVD后Bound恢复，再取得正例和三层关闭控制。一次等待shell片段引号错误不作为boot证据；后续直接get-state/sys.boot_completed/AVD名称通过。
- 实际运行结果见[verification.md](verification.md)：JVM285例通过，app instrumentation1例通过；API29正例、反例、策略读取及故障回退取证，同时记录授权真值与probe缺陷。没有Root/LSPosed注入或真机实测。
- 收尾逐字节恢复专用测试配置revision23、master=false，并通过系统UI撤销无障碍授权，secure=0、enabled=null，Bound/Enabled/Binding为空。
- 核对AVD名称后仅执行本serial的emu kill（exit0），bash-11正常完成exit0。立即kill后的旧transport短暂残留已如实保留；job退出后的再次adb devices为空。所有相关后台job都已结束收集，没有停全局adb服务器。
- SDK、官方镜像、项目AVD和测试APK保留供修复后复测；没有删除用户AVD或真实设备。后续应以受管理job重新启动并确认就绪，不把本文历史截图当当前在线状态。

公开必要证据位于`docs/qa-evidence/t3-b19f67f/`；初始完整环境记录仍保留于被忽略的`.tooling/emulator-qa/`，本轮诊断中间文件在`.tooling/t3-qa/`。产品测试结论以固定版本独立报告为准。

## t17 修复版复测的设备复用与清理（2026-09-21）

- 起始状态：本次 QA 接手时 `adb devices -l` 为空、无 emulator/qemu 进程、5580/5581 无监听（与 t3 收尾一致）；复用同一项目 SDK、官方镜像与 AVD `AntiAds_QA_API29`，未新建、未删除任何 AVD。
- 第一次启动（job `bash-31`，2026-09-21T02:03:48.846Z 起）在约 15 秒后收到“正常关闭”请求而退出：日志为 `Wait for emulator ... shutdown gracefully` 与 `Saving snapshot 'default_boot'`，job exit code 0，原始日志 `.tooling/t17-qa/emulator-r2.log`。原因未定位，本文只记录事实、不据此下产品结论；同期另一位成员 architect-flash 书面确认未启动任何 emulator、未对 5580 执行 `emu kill`（只做过只读 adb/tasklist 查询）。
- 第二次启动（job `bash-33`，2026-09-21T02:06:54.995Z 至 02:28:50.603Z，exit code 0）成功。参数与 t3 相同，仅将 `-no-snapshot` 改为 `-no-snapshot-save`，端口仍为 5580，日志 `.tooling/t17-qa/emulator-r3.log`。
- 就绪核验（安装前）：`adb -s emulator-5580 get-state`=device、`sys.boot_completed`=1、`emu avd name`=`AntiAds_QA_API29`、fingerprint=`Android/sdk_phone_x86_64/generic_x86_64:10/QSR1.210820.001/7663313:userdebug/test-keys`、shell 身份 uid=2000；未设置全局 `ANDROID_SERIAL`，全部设备操作限定 `-s emulator-5580`。
- 安装与固定：五个 APK 均为修复版固定 SHA-256（`docs/qa-evidence/t17-bea37e8/apk-sha256.txt`、`installed-apks.json`），安装后逐一比对“本机构建产物哈希 == 从设备拉取的实际 `base.apk` 哈希”。
- 本轮设备操作范围：真实系统 UI 授权与撤权、无障碍正例 4 轮（含关闭后恢复的对照轮）、反例 3 例、三层独立关闭条件、probe 生命周期与诊断快照、真实 UID 策略读取（probe UID 10117 与夹具 UID 10120 成功、shell UID 2000 被拒）、app instrumentation 两个测试类。清单、截图与原始输出见 `docs/qa-evidence/t17-bea37e8/`。
- 收尾：经产品 UI 将总开关关闭（配置 revision 33，`masterEnabled=false`）；经系统 UI 撤销无障碍授权后 `enabled_accessibility_services` 为空、`Enabled services`/`Bound services` 为空；撤权后 secure `accessibility_enabled` 仍残留 1 且 Binding 列表残留一条 DEAD 连接，重启本 AVD 后归零（`0`/null/三者全空），见 `r2-post-reboot-system.txt`。该残留与 t3 记录的“instrumentation 强停宿主后连接残留”一致，按系统侧记账副作用记录，不当作产品缺陷。
- 定向退出：核对 AVD 名称后仅对本 serial 执行 `emu kill`（exit 0），随后 `adb devices -l` 为空；job `bash-31`/`bash-33` 均已结束并收集，未执行全机 `adb kill-server`，未操作任何用户 AVD 或真机。
- 保留：项目 SDK、官方镜像、AVD 与五个测试 APK 全部保留供后续复测；本轮中间文件在 `.tooling/t17-qa/`。

## t3 第三轮（修复版）设备复用与清理（2026-09-21，architect-flash）

- 起始状态：t17 已按流程 `emu kill` 并记录 `r2-devices-after-exit.txt`；本轮启动前再次确认无 emulator/qemu 进程、`adb devices` 为空，**未与 t17 并发**（t17 工作期间本轮只做过只读 adb/tasklist 查询，未启动或杀死任何实例）。
- 启动：受管 job `bash-36`（2026-09-21T02:31Z 起）使用与前面各轮相同的 SDK/镜像/AVD/端口：`AntiAds_QA_API29`、`-port 5580`、`-no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader -memory 2048 -cores 2 -accel on`，日志 `.tooling/t3-qa3/emulator.log`。
- 就绪核验：`get-state=device`、`sys.boot_completed=1`、`emu avd name=AntiAds_QA_API29`、SDK 29 / Android 10 / fingerprint 同前；证据 `docs/qa-evidence/t3-be2278c/device-ready.txt`。
- 安装与字节核对：五个 APK `install -r` 全部 `Success`，设备内 `base.apk` 的 SHA-256 与本轮重建逐字节相同（app `a08a04b9…`、probe `0a75edd0…`、夹具 `533cbe72…`）。第一次用 `adb exec-out cat <设备路径> | sha256sum` 得到假 MISMATCH，原因是 Git Bash 的 MSYS 路径改写；改为 `MSYS_NO_PATHCONV=1` + 设备内 `sha256sum` 后全部 MATCH（过程与修正都保留在证据文件里）。
- 本轮设备操作范围：系统 UI 真实授权（含系统 ALLOW 对话框）、授权后首页“授权=是/连接=是”、广告正例 2 轮 + 反例 `non_clickable`/`unknown_case`（补上 t17 本轮未跑的一项）、probe 生命周期（HOME 注销→返回→显式重注册，`registrationSeq` 6→12）、诊断快照文件与节流（非强制写入间隔 ≥1003ms）、配置保存与 force-stop 冷启动保留、Provider 的 shell 未授权负例与无 queries 夹具真实 UID 读取。清单与截图见 `docs/qa-evidence/t3-be2278c/`。
- 平台行为记录：`am force-stop com.antiads.app` 后系统自行把 `accessibility_enabled` 置 0 并清空服务集合，因此首页随即显示“授权=否”是真实状态，不是 QA-01 复发（证据 `t3-force-stop-a11y-state.txt`）。
- 收尾：把设备内配置恢复到 t17 留下的状态（**revision 35、masterEnabled=false**），`accessibility_enabled=0`、服务集合 null；核对 AVD 名后仅对本 serial 执行 `emu kill`（exit 0），job `bash-36` 以 exit 0 收束，随后 `adb devices` 为空；未执行全机 `adb kill-server`，未操作任何用户 AVD 或真机。证据 `t3-cleanup.txt`、`t3-final-state.txt`。
- 本节的追加未改动上文任何内容（追加前本文件 SHA-256：`ce33e693c0a2729b91a17661121fb97779f9d9e8b8af08e65f8a940a18432321`）。




