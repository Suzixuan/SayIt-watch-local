# Delivery 1B — Z3 Repair 2（Watch UI 行为与证据修复）

## 给同事 Z 的执行指令

继续在私有仓库 `https://github.com/Suzixuan/SayIt-watch-local` 的
`codex/review-watch-pipeline` 分支工作，从包含本任务文件的 PM 提交开始。

- 被复验提交：`b8daef527acbdced401e73ec9be6eaee39373389`
- 冻结 UI 方向：`docs/WATCH-UI-Z-HANDOFF.md`
- 本轮裁决：**PC 端三项 Repair 保留；Watch UI NO-GO / Repair 2 required**
- 不重新设计或重写已经通过源码复验的 Rust/Tauri admission、对象事件 payload、
  Provider external-error teardown。
- 不做真机闭环，不声称 Delivery 1B VERIFIED。

## 必修 1 — Cancel 必须稳定回到 Ready，禁止迟到收尾写成 Failure

当前 `RecordingViewModel.cancelRecording()` 立即 `session.reset()` / `prepare()`，
但录音 I/O 协程随后仍会执行 `session.recordingCompleted(...)`。此时 Session 已是
`READY`，`recordingCompleted` 的状态断言会抛错，catch 又把 Session 写成
`FAILURE`。结果违背冻结语义：Cancel 应当停止采集、丢弃本次录音、不上传、回到
Ready。

要求：

1. 用最小的 request/session generation 或明确的 cancel latch 让录音协程知道本代
   已被取消；迟到的 capture 完成不得调用正常 `recordingCompleted`、不得触发上传、
   不得写 Failure。
2. Cancel 的最终状态必须是 `READY`，`wavBytes == null`，无待上传数据。
3. 不把 Cancel 复用成 Stop；Stop 仍自动整段上传。
4. 增加确定性测试，模拟 Cancel 后 capture 才返回：最终 Ready、无 WAV、上传调用
   0 次、无失败状态。

## 必修 2 — Later 之后必须仍有可达的 Retry

当前 `Later` 隐藏失败 overlay，只在 Ready 显示不可点击的 `Pending upload` badge；
Retry 只存在于已经消失的失败 overlay。用户因此无法重试保留的 WAV，只能保留或
丢弃，违反“所有非 2xx 保留 WAV并可重试”。此外，当前 `retryPressed()` 只接受
`UPLOAD_FAILED` overlay，不能从 Pending-ready 进入 `UPLOADING`。

要求：

1. Ready 的 Pending-upload 状态必须提供明显、可触达的 Retry 操作（可点击 badge
   或紧邻的次级 action，不新增页面）。
2. Pending-ready → Retry → Uploading 必须成立。
3. Retry 继续发送 byte-for-byte 相同的 retained WAV，不重新录音。
4. 保留“开始新录音前明确丢弃旧 WAV”的现有保护。
5. 增加端到端 ViewModel/状态机测试：Failure → Later → Ready/Pending → Retry →
   Uploading；并断言上传字节与原 WAV 完全一致。

## 必修 3 — 运行时控件必须与候选预览一致且可点击

候选 SVG 把 `Save & Apply`、`Check / Refresh`、`Retry`、`Later` 等画成宽胶囊按钮，
但运行时代码全部使用 Wear Material `Button` 且未指定尺寸/shape。当前依赖
`androidx.wear.compose:compose-material:1.4.1` 的默认 Button 是 52 dp 圆形控件；
长文案会与预览不一致，并存在裁切/难点按风险。配置字段和 Show/Hide 也只是小字号
Text clickable，未形成可靠触控目标。

要求：

1. 文本型操作使用现有依赖内适合 Wear OS 的 `Chip` / `CompactChip`，或显式实现
   与候选一致的宽度、圆角和至少 48 dp 可点击高度；不得新增依赖。
2. `Save & Apply`、`Check / Refresh`、Pending Retry、失败页 Retry/Later、配置行与
   Token Show/Hide 都必须在 480×480 圆屏安全区内可用。
3. Record/Stop 可保留圆形主操作，但实际控件和冻结预览必须一致。
4. 增加可自动检查的 semantics/布局约束测试；真实圆屏触控与裁切仍留给后续真机门。

## 必修 4 — 修正文案与版本化视觉证据

1. 删除 `Uploading` 的 `Keep the watch on.`。应用已经通过
   `FLAG_KEEP_SCREEN_ON` 自动亮屏，不应再把保持亮屏表述成用户责任。可只保留
   `Sending the recording to the PC.`，或明确写 `The screen stays awake automatically.`。
2. 不覆盖 `design/watch-ui/0.2.0-dev.2-candidate.1/`；新预览冻结到
   `design/watch-ui/0.2.0-dev.2-candidate.2/`，状态 `candidate`，版本仍为
   `0.2.0-dev.2` / `versionCode = 2`。
3. 现有 `0.2.0-dev.1-baseline/SHA256SUMS` 不是父提交的真实哈希：至少
   `watch/app/build.gradle.kts` 的记录值与当前 dev.2 文件一致，而父提交是 dev.1。
   不得覆盖这份错误证据。新建
   `design/watch-ui/0.2.0-dev.1-baseline-recovery.1/`，从父提交
   `5da1a32279b372810d83504aca2021b0c8146763` 的 Git blobs 重新生成准确 manifest，
   README 明确原 baseline 被 PM 判为 invalid/rejected evidence。
4. `candidate.2` 必须包含与实际 Compose primitive/文案一致的 480×480 各状态预览、
   README、SHA256SUMS。若仍不是 emulator/真机截图，必须继续如实声明，不得称作运行时
   截图或视觉验收。

## 允许修改

- `watch/app/src/main/java/com/sayit/watch/MainActivity.kt`（仅确有必要时）
- `watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt`
- `watch/app/src/main/java/com/sayit/watch/ui/RecordingViewModel.kt`
- `watch/app/src/main/res/values/strings.xml`
- 与上述行为直接对应的 Watch 单元/Compose 测试
- `design/watch-ui/0.2.0-dev.1-baseline-recovery.1/**`
- `design/watch-ui/0.2.0-dev.2-candidate.2/**`
- `HANDOFF.md`、`PROJECT_PROGRESS.md` 仅记录真实结果

不得修改 PC 端 Rust/TS 产品代码、依赖、lockfile、父 baseline、candidate.1、ASR、
History、Paste 或任何本轮禁止功能。如确需越界，先停止并说明。

## 必跑验证

1. `git diff --check`
2. `watch`: `.\gradlew.bat testDebugUnitTest --rerun-tasks`
3. `watch`: `.\gradlew.bat lintDebug assembleDebug assembleRelease`
4. `client`: `npm test -- --run`
5. `client`: `npm run build`
6. `client/src-tauri`: `cargo test`
7. `client/src-tauri`: `cargo build --release`
8. 对 Release exe 复扫十一项 Watch Receiver/admission marker，必须全部不存在。
9. 逐项重算 baseline-recovery 与 candidate.2 的 SHA256SUMS。
10. 返回 Debug APK 路径和 SHA-256；不得提交 APK。

## 回传与停止条件

回传 commit SHA、review base、changed-file list、每条命令及 exit code、测试数量、四项
Repair 对应证据、candidate.2 全部预览路径与 manifest、未解决风险。push 到
`origin/codex/review-watch-pipeline` 后立即停止，等待 PM 复验；不得 merge、tag、
release、安装到设备或开始十次闭环。
