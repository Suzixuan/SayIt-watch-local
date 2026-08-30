# Delivery 1B — Z3 Repair 3（Cancel 并发安全 + 响应式 Wear UI + candidate.3）

## 给同事 Z 的执行指令

继续在私有仓库 `https://github.com/Suzixuan/SayIt-watch-local` 的
`codex/review-watch-pipeline` 分支工作，从包含本任务文件的 PM 提交开始。

- 被复验提交：`d04b7c3b3fb93cb7ea01e742556fb0ae302dc9b0`
- 上一任务：`docs/DELIVERY-1B-Z3-REPAIR-2.md`
- 本轮裁决：**PC 端 Repair 与 baseline-recovery.1 保留；Watch UI NO-GO / Repair 3 required**
- 不修改或重跑设计方向，不重写现有 ASR、Receiver、Provider、History、Paste。
- 不做真机闭环，不声称 Delivery 1B VERIFIED。

## 必修 1 — Cancel 必须对成功与异常两种迟到结果都 fail-closed

当前 generation latch 只在正常 capture 返回后检查 `isCurrent()`；catch 分支仍无条件
执行 `session.recordingFailed(...)`。如果 Cancel 与 `AudioRecord.read`/WAV 构建异常
竞态，迟到异常仍会把已经恢复的 READY 覆盖成 FAILURE。

此外，`RecordingRequestLatch.generation` / `cancelledGeneration` 和
`recordingActive` 在主线程与 `Dispatchers.IO` 间读写，但都是普通字段；现有单线程
JVM 测试不能证明跨线程可见性。

要求：

1. 使用最小的线程安全 generation/cancel token（例如原子值或明确同步），让成功和异常
   两种 completion 都先验证本代仍有效。
2. Cancel 之后，本代任何迟到成功或异常都不得修改 Session/UI、不得振动 stop、不得上传。
3. `recordingActive` 的跨线程停止信号必须具有明确可见性；不要继续依赖普通 Boolean 数据竞态。
4. 增加确定性测试：
   - Cancel 后迟到正常返回 → READY、无 WAV、upload 0 次；
   - Cancel 后迟到异常 → READY、无 failure、upload 0 次；
   - 至少一个真实跨线程/并发可见性测试，而不只是同线程调用 pure latch。
5. 测试必须覆盖 ViewModel 的 completion 决策或等价的单一 coordinator；不得只在测试中
   手写一份 `if (latch.isCurrent)` 来重复生产代码。

## 必修 2 — 不能把 480 px 当成 480 dp

当前 `WatchUiMetrics` 将 480×480 物理像素画布直接写成 Compose 的 `480.dp`，并固定
`WideChipWidthDp = 340.dp`、`HalfChipWidthDp = 160.dp`。Compose `dp` 是逻辑尺寸，
不等于设备物理像素；现有测试只证明 `340.dp <= 480.dp - 68.dp` 这个自造等式，不能
证明真实 Galaxy Watch 7 上不裁切。Pending 页面还叠加了 Check/Refresh、Settings、
badge、Retry、Record 和 Status，固定高度与宽度风险更明显。

要求：

1. 去掉以 `480.dp` 为运行时屏幕宽度的假设；宽 Chip 使用父容器约束
   （`fillMaxWidth` / `weight` / `BoxWithConstraints` 等）响应式布局。
2. Retry/Later 使用可伸缩权重与间距，不固定两个 `160.dp`。
3. 页面内容超出逻辑可用高度时必须使用 Wear 可滚动容器/列表；所有主要动作仍在圆屏
   安全区可达。
4. 所有长文本 action 一并修正，包括 `Grant Mic`、Discard 对话框的
   `Discard & record`、以及输入对话框操作；不能继续留在默认 52 dp 圆 Button 内。
5. 自动测试不得再以 `480.dp` 假装真实约束。至少验证响应式 modifier/布局策略；真实
   480×480 px 设备裁切仍留给后续真机门。

## 必修 3 — candidate.3 必须忠实对应运行时且无可见溢出

`candidate.2/previews/03-ready-pending.svg` 已有可见问题：

- `Pending upload · tap to retry` 文字超出绿色 badge；
- 预览省略了运行时代码无条件显示的 `Check / Refresh` 与底部 StatusLine；
- SVG 用 340 px 模拟 340 dp，因此“同一 metrics 自动生成”的声明不成立。

要求：

1. 不覆盖 `candidate.1` 或 `candidate.2`；新建
   `design/watch-ui/0.2.0-dev.2-candidate.3/`，版本仍为 `0.2.0-dev.2` /
   `versionCode = 2`。
2. candidate.3 的每个状态必须逐项对应实际 Compose 可见元素、顺序和文案；如运行时
   与预览选择不同，先统一源代码，再生成预览。
3. 全尺寸人工检查每张预览：无文字超出背景/圆屏、无控件碰边、无遗漏或虚构状态。
4. README 明确 candidate.2 被 PM 判为 rejected，并记录本轮差异、父版本、继承锁、
   非真机截图偏差与状态 `candidate`。
5. 生成并重算完整 `SHA256SUMS`。保留并复验已经正确的
   `0.2.0-dev.1-baseline-recovery.1`，不得再新建或修改 baseline。

## 允许修改

- `watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt`
- `watch/app/src/main/java/com/sayit/watch/ui/RecordingViewModel.kt`
- `watch/app/src/main/res/values/strings.xml`（仅必要文案）
- 与三项修复直接对应的 Watch 测试
- `design/watch-ui/0.2.0-dev.2-candidate.3/**`
- `HANDOFF.md`、`PROJECT_PROGRESS.md` 仅记录真实结果

不得修改 PC Rust/TS 产品代码、依赖、lockfile、MainActivity、任何既有 frozen design
目录、baseline recovery、ASR、History、Paste 或其他禁止功能。确需越界先停止说明。

## 必跑验证

1. `git diff --check`
2. `watch`: `.\gradlew.bat testDebugUnitTest --rerun-tasks`
3. `watch`: `.\gradlew.bat lintDebug assembleDebug assembleRelease`
4. `client`: `npm test -- --run`
5. `client`: `npm run build`
6. `client/src-tauri`: `cargo test`
7. `client/src-tauri`: `cargo build --release`
8. Release exe 十一项 Watch/admission marker 全部不存在。
9. 以原始 Git blob 字节复验 baseline-recovery.1；重算 candidate.3 manifest。
10. 返回 Debug APK 路径和 SHA-256；不得提交 APK。

## 回传并停止

回传 commit SHA、review base、changed-file list、每条命令与 exit code、测试数、三项
修复证据、candidate.3 全部预览路径/manifest、未解决风险。push 到
`origin/codex/review-watch-pipeline` 后立即停止；不得 merge、tag、release、安装设备
或开始十次闭环。
