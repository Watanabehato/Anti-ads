# t3命令与结果索引

固定版本：b19f67f2262dff74d08fddca2ce457c28f857afe；所有设备命令限定emulator-5580。完整测试范围、失败和未验证项见../../verification.md。下面是实际执行命令类别及退出结果；手动UI场景的功能判定依赖截图/系统状态/日志，不能只看adb exit0。

| 实际命令或序列 | exit | 实际证据/结果 |
| --- | --- | --- |
| bash ./gradlew --no-daemon :core:test :app:testDebugUnitTest :accessibility:testDebugUnitTest :hook:testDebugUnitTest :probe:testDebugUnitTest :app:lintDebug :accessibility:lintDebug :hook:lintDebug :probe:lintDebug :app:assembleDebug :probe:assembleDebug :app:assembleDebugAndroidTest :probe:assembleDebugAndroidTest --rerun-tasks --no-build-cache --max-workers=2 | 0 | gradle-full.txt；246 executed；JVM285/0失败 |
| bash ./gradlew --no-daemon -p qa/fixtures/noqueries assembleDebug lintDebug --rerun-tasks --no-build-cache --max-workers=2 | 0 | fixture-build.txt；43 executed；lint无issue |
| adb -s emulator-5580 install -r （app/probe/两androidTest/独立fixture APK路径） | 0 | install-cold-start.txt；五个Success，APK路径/SHA见apk-sha256.txt |
| apksigner.bat verify --verbose --print-certs （各APK路径） | 0 | 五份*-signature.txt均Verifies；v2/Android Debug签名 |
| aapt2.exe dump xmltree --file AndroidManifest.xml （app/probe/fixture APK路径） | 0 | 三份*-manifest.txt |
| aapt2.exe dump xmltree --file res/xml/accessibility_service_config.xml app/build/outputs/apk/debug/app-debug.apk | 0 | accessibility-service-xml.txt |
| aapt2.exe dump resources app/build/outputs/apk/debug/app-debug.apk | 0 | app-resources.txt；scope数组仅probe |
| APK ZIP提取assets/xposed_init、DEX class_def解析 | 0 | xposed_init.txt、dex-inspection.json；没有打包Xposed实现类 |
| adb shell am instrument -w -r -e class com.antiads.app.ConfigToggleTest -e action master_off com.antiads.app.test/androidx.test.runner.AndroidJUnitRunner | 0 | instrument-master-off.txt；OK(1 test)，SAVED/flipped=true/16→17 |
| adb shell am force-stop com.antiads.probe；am start -W -n com.antiads.probe/.AdFixtureActivity --es scenario （场景值）；等待12秒；exec-out screencap -p | 0 | 正例3轮＋重启后1轮已跳过；四反例＋未知值等待；正反例窗口独立；日志含实际PID/elapsed |
| 系统设置授权/撤权；settings get secure；dumpsys accessibility；首页截图 | 0，但功能失败 | QA-01授权真值错误；授权截图与系统事实冲突；最终撤权成功 |
| probe开始采样、HOME、返回并截图 | 0，但功能失败 | QA-03返回后开始按钮仍禁用；UI/source独立证据 |
| adb shell run-as com.antiads.probe ls -l files/probe-diag.json | 1 | probe-missing-snapshot.txt；QA-04文件不存在。此前exec-out tar流错误，不能当有效JSON |
| P开启自查、T按钮手动读取策略 | 0（输入命令） | 自身真实UID分别10117/10120；由响应截图判断成功，非shell代读 |
| adb shell content call --uri content://com.antiads.app.config --method get_policy_v1 --arg com.antiads.probe | 0 | UID2000返回UNAUTHORIZED；负向对照通过 |
| adb shell content call --uri content://com.antiads.app.config --method write_config --arg com.antiads.probe | 0 | INVALID_REQUEST；负向对照通过 |
| adb shell content insert --uri content://com.antiads.app.config --bind revision:l:999；content read --uri content://com.antiads.app.config | 0 | INSERT/OPEN_FILE抛UnsupportedOperationException；负向对照通过 |
| 宿主pidof→force-stop→pidof（预期无进程）→T按钮请求→pidof | 0，停止后pidof=1为预期 | provider-cold-*.txt/png；PID2607→无→3800；系统明确由content provider拉起；rev23 false/[] |
| 停宿主；run-as cp备份；truncate -s 1配置；T读取；打开宿主；再停宿主并cp恢复、移除backup | 0 | corrupt-*.png/txt；INVALID_JSON、全关rev0；恢复字节与原rev23一致 |
| adb emu avd name；adb emu kill；job_output bash-11；adb devices -l | 0 | emulator-cleanup.txt；bash-11正常exit0；devices-after-emulator-exit.txt为空 |
| git diff --name-only b19f67f... -- app core accessibility hook probe qa/fixtures gradle gradlew gradlew.bat build.gradle.kts settings.gradle.kts | 0且无输出 | 未修改产品、夹具、构建实现或测试期望 |

额外失败尝试：bash-12冗余adb服务器exit127（5037占用）；UiAutomator idle/root失败与pull exit1；一次多操作命令30秒超时，按现有状态续接而非重放开关；无效tar解析exit1；bash-18等待片段引号错误，整体exit0不计boot成功。详见主报告环境段和environment-attempts.txt。

没有运行connectedDebugAndroidTest全套，没有执行probe RegistrationHoldTest；其测试APK组装/安装不能当运行通过。无Root/LSPosed环境，H01及平台/OEM缺口保持未实测。
