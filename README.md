# Anti-ads：摇一摇广告防护（Android 10 / API 29+）

本仓库是 Anti-ads 五模块 Android 工程：**免 Root 无障碍跳过** 与 **Root/LSPosed 传感器拦截** 双模式并行，
另含一个独立诊断 APK 用于提供可复现的广告样例与传感器计数基线。

> 当前处于**工程骨架阶段**（任务 t2）。骨架只提供可编译的五模块结构、统一工具链与 core 公共接口基线，
> **不代表任何“已保护/已生效”结论**。真实设备验证尚未进行。

## 平台与能力边界（不承诺全面覆盖）

- Android 不存在“普通应用无条件禁止其他应用读取传感器”的公共接口；本项目只降低被识别的摇一摇触发概率。
- 免 Root 模式：需要用户手动授予无障碍权限，仅对明确启用且可访问的窗口尝试点击文案精确匹配的“跳过”按钮；
  自绘画面、游戏画面、不可访问 WebView、无语义节点等场景不处理。
- Root/LSPosed 模式：需要用户已有兼容框架并激活模块、勾选作用域、重启目标进程；只拦截已定位的
  Java 传感器分发路径，NDK/JNI 自建读取、SensorDirectChannel、厂商私有接口不在 v1 覆盖范围。
- 首批目标为 API 29～34（框架候选范围），API 35+ 需使用明确支持该版本的维护版框架并逐版本验证。
- 两种模式默认全部关闭，应用名单为空；每包默认屏蔽加速度计/陀螺仪/重力/线性加速度/旋转矢量，
  可能影响运动、导航、游戏、计步等正常功能。

## 模块结构

| 模块 | 类型 | 命名空间 | 职责 |
| --- | --- | --- | --- |
| `:core` | Kotlin/JVM 纯库 | com.antiads.core | 配置模型、JSON 编解码、校验、策略求交、传感器决策、规则输入输出 |
| `:app` | 宿主 APK | com.antiads.app | 中文界面、配置存储、只读策略 Provider、状态聚合（同时承载 LSPosed 模块入口） |
| `:accessibility` | Android 库 | com.antiads.accessibility | 无障碍服务、窗口/节点快照、保守点击执行 |
| `:hook` | Android 库 | com.antiads.hook | LSPosed 入口、Java 传感器分发 Hook、异步配置客户端 |
| `:probe` | 独立 APK | com.antiads.probe | 无联网传感器计数与广告正反例样例页 |

依赖方向：`:accessibility`/`:hook` → `:core`；`:app` → 三者；`:probe` → `:core`。
`:core` 不依赖 Android/Context/Binder/Xposed，也不依赖其他工程模块。

## 快速开始（本机已固定工具链）

```bash
# 骨架编译与组装（合同验证命令，原样可复现）
bash ./gradlew --no-daemon :core:compileKotlin :accessibility:assembleDebug :hook:assembleDebug :app:assembleDebug :probe:assembleDebug

# core 纯 JVM 单测
bash ./gradlew --no-daemon :core:test
```

工具链版本、绝对路径、SDK 已装包、重建方法与环境限制见 [docs/build.md](docs/build.md)。

## 文档

- [需求与验收基线](docs/requirements.md)
- [五模块架构与协作边界](docs/architecture.md)
- [跨模块接口合同](docs/contracts.md)
- [构建、工具链与复用命令](docs/build.md)

## 隐私与安全

不申请 INTERNET、不上传包列表/界面内容/传感器数据；不自动提权、不修改 SELinux、不代替用户授予权限。
配置只持久化在管理端应用私有目录；目标进程仅能读取自己所属包的最小策略。

## 许可证

[MIT](LICENSE)
