# Delivery 1B — Z3 Repair 4（多代录音状态机 + 最终可读 candidate.4）

## 给同事 Z 的执行指令

继续在私有仓库 `https://github.com/Suzixuan/SayIt-watch-local` 的
`codex/review-watch-pipeline` 分支工作，从包含本任务文件的 PM 提交开始。

- 被复验提交：`332089a510cb06040f4863561301b9b5d5eaef51`
- 上一任务：`docs/DELIVERY-1B-Z3-REPAIR-3.md`
- 本轮裁决：**响应式宽度、长文本 Chip、baseline recovery、Pending Retry 与 PC 端代码均保留；Repair 4 required**
- 只修下面两个阻断，不扩展功能，不做真机闭环。

## 必修 1 — Completion gate 必须支持连续多代且与 Cancel 原子互斥

当前 `settledGeneration` 初始化为 `-1`，但 `begin()` 没有复位。第一轮成功
`settle(gen1)` 后值变成 `gen1`；第二轮 `begin()` 只递增 generation，随后
`compareAndSet(-1, gen2)` 永远失败。因此第二次录音正常结束也会被静默丢弃。

当前 Cancel、generation 与 settled 又分散在三个 Atomic 中：`settle()` 可能先读到
`cancelled=false`，Cancel 再写 true，而 settle 仍 CAS 成功。它们不是一个原子状态转换。

要求：

1. 用一个同步临界区或单一原子状态表示 `Idle/Active(generation)/Cancelled/Settled`，
   `begin`、`cancel`、`settle` 必须对同一状态原子转换。
2. 每次 `begin` 都创建可独立 settle 的新代；旧代完成不得影响新代。
3. 不在 `Dispatchers.IO` 内直接修改 `RecordingSession`。I/O 只产生
   Success/Failure/Canceled outcome；回到 ViewModel 主协程后先通过 gate，再修改
   Session/UI/振动/上传。这样 Cancel 与 completion 的产品状态写入不再跨线程竞态。
4. 增加生产 coordinator 级测试：
   - gen1 成功 settle，gen2 随后也能成功 settle；
   - gen1 迟到不能占用 gen2；
   - Cancel 与 completion 并发只有一个终态，Cancel 胜出时无 WAV/Failure/振动/上传；
   - 连续两次正常录音都各自进入完成/上传一次。
5. 不只测试裸 Atomic；测试必须调用与 ViewModel 相同的 outcome 应用逻辑或抽取出的唯一
   coordinator。

## 必修 2 — candidate.4 与运行时都必须可读，不允许叠字或裁切

candidate.3 全尺寸复验发现：

- Pending 页面底部 `Ready — 16 kHz verified` 被圆屏裁切；
- Recording 预览缺少运行时始终存在的 `TimeText`；
- Uploading、Upload failed、Uploaded 三个 overlay 仍透出 Ready 文本，前景标题/原因与
  背景 Settings/Transport/按钮明显叠字；Upload failed 尤其不可读。

要求：

1. overlay 使用不透字的完整背景或实心内容面板；不得让底层文字与前景文字重叠。
2. 精简 Pending-ready 的重复动作：可将状态与 Retry 合并成一个明显、可点击、至少
   48 dp 的 `Pending upload — Retry` Chip；不必同时保留 badge + 第二个 Retry Chip。
   必须继续保留原 WAV 与显式 discard 保护。
3. Ready/Pending 若需滚动，运行时和预览必须明确采用同一首屏/滚动语义；主要 Record、
   Retry 与状态不得在冻结预览中半截裁切。
4. Recording 及所有非 overlay 页面必须包含运行时真实出现的 TimeText；overlay 如选择
   隐藏底层/TimeText，也必须与代码一致。
5. 不覆盖 candidate.1/2/3；新建
   `design/watch-ui/0.2.0-dev.2-candidate.4/`，README 将 candidate.3 标记为 rejected，
   记录父版本、差异、继承锁、非真机偏差与状态 `candidate`。
6. 全尺寸逐张人工检查：无文字重叠、无截断、无控件碰圆边、无遗漏或虚构元素；生成
   完整 SHA256SUMS。

## 允许修改

- `watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt`
- `watch/app/src/main/java/com/sayit/watch/ui/RecordingViewModel.kt`
- `watch/app/src/main/res/values/strings.xml`（仅必要文案）
- 与上述两项直接对应的 Watch 测试
- `design/watch-ui/0.2.0-dev.2-candidate.4/**`
- `HANDOFF.md`、`PROJECT_PROGRESS.md` 仅记录真实结果

不得修改 PC Rust/TS、依赖、lockfile、MainActivity、任何既有 frozen design/baseline
目录、ASR、Receiver、Provider、History、Paste 或其他禁止功能。

## 必跑验证

1. `git diff --check`
2. `watch`: `.\gradlew.bat testDebugUnitTest --rerun-tasks`
3. `watch`: `.\gradlew.bat lintDebug assembleDebug assembleRelease`
4. `client`: `npm test -- --run`
5. `client`: `npm run build`
6. `client/src-tauri`: `cargo test`
7. `client/src-tauri`: `cargo build --release`
8. Release exe 十一项 Watch/admission marker 全部不存在。
9. 重算 candidate.4 manifest；确认 candidate.1/2/3 与 baseline recovery 未变化。
10. 返回 Debug APK 路径和 SHA-256；不得提交 APK。

## 回传并停止

回传 commit SHA、review base、changed-file list、命令/exit code/测试数、两项修复映射、
candidate.4 全部预览及 manifest、未解决风险。push 后立即停止；不得 merge、tag、
release、安装设备或开始十次闭环。
