# Delivery 1B — D 接力：dev.3 极简 Watch UI 收口

## 当前现场

- 分支：`codex/review-watch-pipeline`
- 当前提交基线：`178ab81e5da735d2284205f6ce4ca7e0a5819c05`
- 工作树**故意未提交且有改动**；这些改动来自已停止的内部 Employee A。
- 禁止 `reset`、`checkout --`、`clean`、stash 覆盖或重新生成现有工作。
- 已存在：
  - dev.3 大蓝色 Mic、`mm:ss`、红色 Stop、紧凑 Cancel；
  - `0.2.0-dev.3-candidate.1`（用户否决为过度设计，保留）；
  - `0.2.0-dev.3-candidate.2`（当前极简方向）；
  - 三屏状态机与静默上传的未验证实现。

## 用户最终决定

Watch 只保留：

1. Config
2. Ready
3. Recording

明确搁置：Uploading、Uploaded、Failed、Retry、Later、Pending、WAV 保留、Discard、
队列、并发上传策略、新容错、配对、发现、Streaming、VAD、AI。不要换一种方式重做这些
功能。

Stop 后只调用现有单次整段上传并立即显示 Ready。成功或失败都不显示、不振动、不保留
WAV；用户只看电脑输入框是否出现文字。上传进行中，Record 静默 no-op；请求结束后恢复。

## Config 最终规则

- 保存的 IP/Port/Token 有效：启动直接进入 Ready，不经过 Config。
- 首次安装或配置缺失/非法：启动进入 Config。
- Ready 保留小型 Settings 入口，用户主动修改时进入 Config。
- 健康检查或上传失败：不弹页面、不保留 WAV、不强制跳 Config；回 Ready，并将
  `transportAvailable=false`。用户需要时自行点 Settings。
- Config 只保留 IP、Port、Token、Save & Apply；保存有效后立即 Ready。
- 不新增自动发现、正式配对或二维码。

## 必须先审查当前未提交实现

当前实现已大幅修改 `RecordingViewModel.kt` 与测试，但在 Config 启动规则完成前被中断。
D 先读完整 diff，确认：

- `WatchUiState` 只有 `CONFIG/READY/RECORDING` 与内部 `isUploading`；
- 所有旧 overlay/pending/retry/discard 可见路径已删除；
- silent upload 无 session 竞态，成功/失败均清 WAV并恢复 recordability；
- 只有 start/stop haptic；
- `RecordingScreen` 不引用已删除的旧状态。

如发现当前改动扩大到 TransportClient、Receiver、PC、ASR、Provider、History、Paste，立即
停止回报，不继续。

## 允许修改

- `watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt`
- `watch/app/src/main/java/com/sayit/watch/ui/RecordingViewModel.kt`
- `watch/app/src/main/res/values/strings.xml`
- 直接对应的 Watch UI/状态机测试
- `watch/app/build.gradle.kts`（保持 versionCode 3 / 0.2.0-dev.3）
- `design/watch-ui/0.2.0-dev.3-candidate.2/**`
- 本任务完成后的 `HANDOFF.md`、`PROJECT_PROGRESS.md`

不得修改依赖、lockfile、录音采样/WAV、TransportClient、Receiver、PC bridge、ASR、
Provider、History、Paste、现有 frozen candidate 或 D 已提交的 NOT VERIFIED 报告。

## 自动验证

必须新增/保留测试：

- valid saved config 启动 → Ready；invalid/missing → Config；
- manual Settings → Config；Save valid → Ready；
- Stop → visible Ready + internal uploading；
- 上传期间 Record no-op；结束后恢复；
- HTTP success/failure 都清 WAV、无可见状态、无 outcome haptic；失败令 transport unavailable；
- 没有 retry/pending/retained/discard 可达状态；
- sample-derived `mm:ss` 继续实时更新；
- Ready/Recording 真机主操作无需滚动。

执行：

1. `.\gradlew.bat testDebugUnitTest --rerun-tasks`
2. `.\gradlew.bat lintDebug assembleDebug assembleRelease`
3. `git diff --check`
4. 重算 candidate.2 SHA256SUMS
5. 返回 Debug APK 路径与 SHA-256

## 真机与交付

- 安装到 Galaxy Watch 7，真实截图只需 Config（缺配置时）、Ready、Recording。
- Ready 必须有大蓝 Mic；Recording 必须有实时 `mm:ss`、红色文字 Stop、独立 Cancel。
- 不得出现任何上传/成功/失败/Pending/Retry 页面或 `●/■` 调试按钮。
- 真实截图通过后，提交并 push 到 `origin/codex/review-watch-pipeline`，不得 merge/tag/release。
- 回传 commit SHA、changed files、测试/构建 exit code、APK hash、三张真机截图路径和风险，
  然后停止。PC `bridge_timeout` 留给后续独立员工任务。
