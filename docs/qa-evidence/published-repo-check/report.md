# t23 独立复核：已发布公开仓库与 CI 产物

执行：qa-flash（attempt `46366154-d5de-497f-aaa2-9a2d8aec9deb`）。日期：2026-09-21。方式：**只读**核对 GitHub 实际数据与克隆副本，未修改仓库任何内容、未推送、未触发新 CI run。
证据目录：`docs/qa-evidence/published-repo-check/`（原始 JSON/文本在 `raw/`，本文件为报告）。

## 0. 结论

**门禁：通过**（限定为“公网仓库实际发布内容 + 该仓库 CI 产物”的核对范围）。5 条验收全部满足；未发现阻断项；另记录 **4 条非阻断发现**（3 条文档事实滞后、1 条死链清单），均不影响“仓库可公开交付、CI 产物可下载且与文档字节数一致”的结论。
**未把 CI 产物当作设备验证产物**：设备逐字节核对的是本机组（`docs/delivery.md` §3.1），CI artifact 是同一提交的另一次构建；本轮实测再次确认两组哈希不同（详见 §3）。

## 1. 复核方法与可复现命令

```bash
# 远端事实（不依赖本地工作区）
gh api repos/Watanabehato/Anti-ads                 # 元数据（public/默认分支/许可证/topics/描述）
gh api repos/Watanabehato/Anti-ads/topics          # topics 列表
gh api "repos/Watanabehato/Anti-ads/releases?per_page=100"   # 0 条
gh api "repos/Watanabehato/Anti-ads/tags?per_page=100"       # 0 条
gh api "repos/Watanabehato/Anti-ads/branches?per_page=100"   # 只有 main
gh api repos/Watanabehato/Anti-ads/commits/main               # HEAD 提交
gh api "repos/Watanabehato/Anti-ads/git/trees/main?recursive=1"  # 811 条路径（truncated=false）
git ls-remote https://github.com/Watanabehato/Anti-ads          # HEAD 与 refs/heads/main

# 克隆（项目外、git 忽略目录；不提交）
git clone --depth 1 https://github.com/Watanabehato/Anti-ads .tooling/published-repo-check/clone
cd .tooling/published-repo-check/clone && git ls-files | wc -l && cat .gitignore && head -3 LICENSE
# 相对链接检查（27 个 md，120 条相对链接；脚本 .tooling/published-repo-check/link-check.py）
python .tooling/published-repo-check/link-check.py

# CI run 与 artifact
gh api repos/Watanabehato/Anti-ads/actions/runs/35555960362
gh api "repos/Watanabehato/Anti-ads/actions/runs/35555960362/jobs?per_page=100"
gh api repos/Watanabehato/Anti-ads/actions/runs/35555960362/artifacts
gh run download 35555960362 -R Watanabehato/Anti-ads -n debug-apks -D .tooling/published-repo-check/artifact
gh api repos/Watanabehato/Anti-ads/actions/artifacts/10620950392/zip > artifact-download.zip   # 用 API zip 复核 digest
# 同样对首次 run 35555546226 / artifact 10619189887 重复一遍
```

工具：`gh 2.97.0`、`git 2.55.0.windows.3`、本机 Python 3.12。到 GitHub 存在间歇性网络失败（`gh` 报 TLS handshake timeout / blob 存储连接失败各一次），均已重试或改用 API zip 端点完成，原始报错留在 `raw/`。

## 2. 验收 1：仓库元数据（GitHub 实际数据）

| 项目 | GitHub 实际值 | 文档声明 | 一致性 |
| --- | --- | --- | --- |
| 可见性 | `private=false`、`visibility=public` | public | ✅ |
| 默认分支 | `main` | main | ✅ |
| 分支集合 | 仅 `main`（1 条） | 只有 main | ✅ |
| 远端 HEAD | `138d5dd1f9a66b0f56a03a8cfe659ec20825fdfc`（`git ls-remote HEAD` == `refs/heads/main` == `commits/main`） | `docs/delivery.md` §8 写 `bf98279…` | ⚠️ 见发现 N1 |
| 许可证 | `LICENSE`=MIT（GitHub `license.spdx_id=MIT`；克隆内文件首行 `MIT License`） | MIT | ✅ |
| topics | `accessibility-service, adblock, android, lsposed, xposed`（5 个，与 topics API 一致） | 同样 5 个 | ✅ |
| 描述 | `Android 10+ 摇一摇广告防护：免 Root 无障碍保守跳过 + Root/LSPosed 传感器拦截（增强模式，需自备设备与框架）` | 文中只提“public、MIT、topics” | ✅（描述存在且为中文） |
| release / tag | 0 / 0 | 未创建 release、未推送 tag | ✅ |
| 其他 | `fork=false`、`archived=false`、`created_at=2026-09-21T02:51:12Z`、`pushed_at=2026-09-21T02:59:21Z` | — | ✅ |

## 3. 验收 2：远端树卫生与链接

- 递归树：**811 条路径**（`truncated=false`，658 个文件 + 目录）；对以下模式扫描命中 **0**：`.tooling/`、`local.properties`、`.agent-teams/`、`build/`、`.gradle/`、`.idea/`、`*.jks`、`*.keystore`、`*.apk`、`*.aab`、任何 keystore/p12/pem/key 类文件。
- 克隆副本（`git clone --depth 1`）复核：HEAD=`138d5dd…`、分支 `main`、`git ls-files | wc -l` = **658**（与 t5 记录一致）；`git ls-files` 上列模式仍为 0 命中；顶层条目为 `.gitattributes .github .gitignore LICENSE README.md accessibility app build.gradle.kts core docs gradle gradle.properties gradlew gradlew.bat hook probe qa settings.gradle.kts`。
- `LICENSE` 首行 `MIT License`；`.gitignore` 明确忽略 `.tooling/`、`.agent-teams/`、`local.properties`、`*.jks`/`*.keystore`/`keystore.properties`、`build/`、`*.apk`/`*.aab`、IDE/临时文件。
- 相对链接检查（README.md + 全部 `*.md`，共 27 个文件、120 条相对链接、0 条站内绝对路径）：**主文档（README 与 `docs/*.md`）0 条死链**；总共 39 条死链**全部位于 `docs/qa-evidence/` 存档/冻结副本内**，清单见发现 N4。

## 4. 验收 3：CI artifact 下载与哈希对照

两个 run 的 artifact 均已下载、解包、逐文件计算 SHA-256；两组 zip 的 SHA-256 与 GitHub API 返回的 `digest` 字段**完全一致**（独立完整性确认）：

| run / artifact | artifact id | zip 字节 | zip SHA-256（= API digest） |
| --- | --- | ---: | --- |
| 35555960362（HEAD `138d5dd`，**当前**） | 10620950392 | 3,887,554 | `a6253d950d0f12169755457d65f0c70ac5f4ee792b22a4669b2a9abd52d62636` |
| 35555546226（`bf98279`，文档 §8 所列） | 10619189887 | 3,887,556 | `e9459d6d187c54074f060e9d87beba24685c0f180aeb5c56f86545657f8ff95b` |

artifact 内恰好 4 个 APK（路径与仓库结构一致），字节数与文档 `docs/delivery.md` §3.1/§3.2 声称的字节数**完全相同**：

| 产物 | 字节（两组相同，且与文档 §3.1/§3.2 一致） | 设备核对组 §3.1 | CI 组（文档 §3.2） | run 35555546226 实测 | run 35555960362 实测（当前） |
| --- | ---: | --- | --- | --- | --- |
| `app-debug.apk` | 3,392,024 | `a08a04b9…f8f5` | `0a7d37ab…b952` | `0a7d37abe91cf9fdd4b5bb0996539af574cc2490419138591bbb2331ca9b0712` ✅ 与 §3.2 一致 | `b6b33a064df4be97c6af69fcc4ff88d8e0a5f3ed0022c9551a7c3500497d3bf6` |
| `probe-debug.apk` | 3,169,147 | `0a75edd0…3896` | `19ec3900…eb1b` | `19ec39009aea5dbf83780258ce9048a8684f63ab370ddb743551f0b811a6eb1b` ✅ | `617327d71ac15e50c77b6ce6b70838b6b60bfbf517e5404008545ca704ec39c5` |
| `app-debug-androidTest.apk` | 2,174,433 | `9b72ae61…c153` | `ff4ee8d3…b952` | `ff4ee8d311ec9e2df641d6522d2c33572dc7c07ad62994f14e84926cc841b952` ✅ | `500bd3cc1bf74b4e664d9aa0ed7b0483a655846f387b1b0372d5ebfc7c6e0eac` |
| `probe-debug-androidTest.apk` | 2,172,101 | `a351fc57…0680` | `ef8b2fd1…8e63` | `ef8b2fd156907758e801f2ee967f79ad937c2457ec0b47c134f79c49391c8e63` ✅ | `6a27d71d89dd5fe0d9f36fe250b08dacba87122dc0b8b948542879c4be20c505` |

判定：

- 文档 §3.2 声称的“CI artifact 组”哈希与 run `35555546226` / artifact `10619189887` **逐字节一致** ✅；
- 两组（CI 与设备核对组）**字节数相同、SHA-256 不同**，与文档“跨环境不可复现、CI 产物不能当作设备验证组”的说明一致 ✅；
- 当前 HEAD 的另一次构建（run `35555960362`）产生**第三组**哈希，进一步支持“每次构建都不同”的结论；该组数值未写入文档（见发现 N2）。
- 本轮**未**把任何 CI 组哈希用于设备结论；设备结论仍只对应 §3.1 的本机组（其哈希本轮未重新下载核对，因为该组并非 GitHub 上的产物）。

## 5. 验收 4：CI run 结论与步骤覆盖

| 项目 | run 35555960362（当前 HEAD） | run 35555546226（文档 §8 所列） |
| --- | --- | --- |
| URL | https://github.com/Watanabehato/Anti-ads/actions/runs/35555960362 | https://github.com/Watanabehato/Anti-ads/actions/runs/35555546226 |
| head_sha / 事件 | `138d5dd…` / push、attempt 1 | `bf98279…` / push、attempt 1 |
| 结论 | `completed` / **success** | `completed` / **success** |
| 时间 | 02:59:23Z → 03:00:40Z（**77s**） | 02:51:35Z → 02:55:06Z（**211s**，文档写 211s ✅） |
| 步骤 | 13/13 success | 13/13 success |
| artifact | `debug-apks` id 10620950392、3,887,554B、未过期（2026-12-20） | `debug-apks` id 10619189887、3,887,556B、未过期 ✅ 与文档 §8 一致 |

13 个步骤（两 run 相同）：`Set up job` → `Checkout` → `Set up JDK 17 (Temurin)` → `Set up Android SDK (platform 35 / build-tools 35.0.0)` → `Cache Gradle` → **`Unit tests (5 modules)`** → **`Lint (4 Android modules)`** → **`Assemble APKs and instrumentation test APKs`** → **`Upload APKs and test APKs`** → `Post Cache Gradle` → `Post Set up JDK 17` → `Post Checkout` → `Complete job`；与文档“五模块单测 / 四模块 lint / 两个 APK + 两个测试 APK 组装与上传”的声称一致。工作流定义（克隆内 `.github/workflows/build.yml`）实际命令为 `:core:test :app:testDebugUnitTest :accessibility:testDebugUnitTest :hook:testDebugUnitTest :probe:testDebugUnitTest`、`:app:lintDebug :accessibility:lintDebug :hook:lintDebug :probe:lintDebug`、`:app:assembleDebug :probe:assembleDebug :app:assembleDebugAndroidTest :probe:assembleDebugAndroidTest`、`upload-artifact` name=`debug-apks`。

注意：本任务**未触发新的 CI run**（那需要推送），只核对了既有 run 的 GitHub 记录与产物。

## 6. 验收 5：发现（阻断 / 非阻断）

**阻断项：无。** 非阻断发现如下（均属文档事实滞后或死链清理，不影响交付可用性；按“只读核对”要求，QA 未改仓库与文档）：

| 编号 | 级别 | 现象 | 证据 | 建议最小修订 |
| --- | --- | --- | --- | --- |
| **N1** | 非阻断（文档事实滞后） | `docs/delivery.md` §8 写“远端 main HEAD = `bf98279…`（= 本地 HEAD，推送时一致；其后仅本文档修订）”，但远端实际 HEAD 已是 **`138d5dd…`**（即该文档自身的修订提交），且该 HEAD 有自己的成功 run `35555960362`（77s、artifact 10620950392）；§8 表格只列旧 run/artifact | `raw/repo.json`、`raw/ls-remote.txt`、`raw/run-35555960362.json`、`raw/run-35555546226.json` | 把 §8 的 HEAD 更新为 `138d5dd…` 并补一行当前 run/artifact（或标注“以下为某次快照时点”） |
| **N2** | 非阻断（标注不清） | §3.2 的“CI artifact 组”未标注所属 run/artifact id；实测该组对应 run `35555546226` / artifact `10619189887`（一致），而当前 artifact 的真实哈希是第三组：`b6b33a06…`/`617327d7…`/`500bd3cc…`/`6a27d71d…` | 同上 | 在 §3.2 表头/脚注写明“对应 run 35555546226 / artifact 10619189887”，并可附当前组哈希 |
| **N3** | 非阻断（自相矛盾句） | §6 仍写“远端仓库与推送**尚未执行**（本任务只准备本地交付材料，由后续任务用 `gh` 建仓并统一推送）”——这句话现在就印在已发布的公开仓库里 | 克隆内 `docs/delivery.md` §6 | 改为“已完成：见 §8”，或删除该句 |
| **N4** | 低（死链清单） | 39 条相对死链**全部**在 `docs/qa-evidence/` 内：`history/t3-b19f67f-verification.md` 25 条、`history/t3-emulator-environment.md` 3 条、`t17-bea37e8/t17-verification.md` 10 条（这三者是**逐字节归档/冻结副本**，其链接按 `docs/` 相对书写，移入子目录后必然失效，修改会破坏字节一致性）；另有 **1 条可修**：`t17-bea37e8/doc-status-sync.md:19` 的 `verification.md` 应为 `../../verification.md`。主文档（README、`docs/install.md` 等）**0 死链** | `raw/link-check.json`、`raw/link-check-grouped.txt` | 可修那 1 条（+ 可选：在 `docs/qa-evidence/history/` 加一个 README 说明“历史副本链接按 docs/ 书写，仓库内会失效”） |

（N1–N3 都在 `docs/delivery.md`，属交付文档 owner 的范围；QA 未改。N4 中的 1 处属 QA 自己写的 t18 记录文件，但也已进入公开仓库，按本任务“只读、不改仓库”的要求仅记录。）

## 7. 仍未实测 / 不扩大结论

- 本报告只证明：**公网仓库的实际发布形态**（可见性/分支/HEAD/许可证/topics/描述/无 release 与 tag/树卫生/链接）与**该仓库 CI 产物**（两次 run 的成功结论、13 步覆盖、artifact 内容与哈希）与文档声明一致。
- **不构成**以下结论（保持未测）：Root/LSPosed 真实注入与作用域、Hook 5 秒租约的设备观测、API 30+/33+/35+（包可见性、受限设置、WindowInsets）、普通与 Root 真机与 OEM ROM、native/`SensorDirectChannel`、共享 UID/isolated/工作资料、GitHub 仓库设置层面（分支保护、所需评审数、Actions 权限策略）未在本次核对范围。
- CI artifact ≠ 设备验证产物；设备结论仍以 `docs/qa-evidence/t17-bea37e8/` 与 `docs/qa-evidence/t3-be2278c/` 中记录的本机组哈希为准。
- 两次 run 的 artifact 均未过期（到期 2026-12-20），但**产物可被清理**；需要长期留档时应自行保存（本任务已把 zip 与哈希留在项目内 `.tooling/published-repo-check/`（git 忽略））。

## 8. 证据索引

| 文件 | 内容 |
| --- | --- |
| `raw/repo.json` | `gh api repos/...` 原始返回（可见性、默认分支、许可证、topics、描述、时间戳） |
| `raw/topics.json`、`raw/branches.json`、`raw/releases.json`、`raw/tags.json`、`raw/commit-main.json`、`raw/ls-remote.txt` | topics/分支/发布/tag/HEAD 与 git 远端引用 |
| `raw/tree.json`、`raw/tree-scan.json` | 811 条路径的递归树与禁用模式扫描结果 |
| `raw/clone-scan.txt` | 克隆、tracked 文件数、禁用文件扫描、`.gitignore`、LICENSE |
| `raw/link-check.txt`、`raw/link-check.json`、`raw/link-check-grouped.txt` | 链接检查原始输出、逐条死链与分组统计 |
| `raw/runs.json`、`raw/runs-summary.json`、`raw/run-35555960362{,-jobs,-artifacts}.json`、`raw/run-35555546226{,-jobs,-artifacts}.json`、`raw/runs-steps.txt`、`raw/runs-meta.txt` | 两次 run 的元数据、步骤、artifact 列表 |
| `raw/artifact-hash-compare.json`、`raw/checksums.txt` | 两组 artifact 的逐文件哈希/字节数对照、zip digest、以及本项目内所有证据与产物的 SHA-256 |
