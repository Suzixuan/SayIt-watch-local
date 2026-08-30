# Delivery 1B — Z3 Repair 1 + Watch UI candidate

## 给同事 Z 的执行指令

继续在私有仓库 `https://github.com/Suzixuan/SayIt-watch-local` 的 `codex/review-watch-pipeline` 分支工作。

- 修复基线：`dc1af55137c6048b2f170a7ffa167f83364daef9`
- 原任务：`docs/DELIVERY-1B-Z-EXTERNAL-INGRESS-TASK.md`
- 冻结契约：`docs/DELIVERY-1B-PROVIDER-CONTRACT.md`
- 当前裁决：**NO-GO / Repair 1 required**
- 本任务严格串行：先完成 Part A 三项 Repair 并让自动测试全部通过，再做 Part B Watch UI。不得先做 UI、后掩盖 Repair 未完成。
- 本轮不做真机闭环，不声称 Delivery 1B VERIFIED。

## Repair 1 — 真实 Tauri 事件 payload 必须是对象

当前 `main.rs` 的 event sink 调用 `handle.emit(event, payload)`，其中 `payload` 是已经格式化好的 `String`。Tauri 会把它序列化为 JSON string，因此前端 `event.payload` 的真实类型是字符串；`watchIngress.ts` 却按 `{ requestId, sampleCount }` 对象读取，最终忽略事件并让 Receiver 返回 `409 bridge_timeout`。

要求：

1. Rust 必须向 WebView emit 结构化对象，而不是含 JSON 文本的字符串。
2. 可将 `EventSink` 的 payload 改为 `serde_json::Value`，并使用 `serde_json::json!`；也可使用最小的强类型 `Serialize` payload。禁止在前端增加模糊的双格式兼容兜底来掩盖 Rust 契约错误。
3. `watch://admission-request` 和 `watch://audio-ready` 至少包含对象字段：`requestId`、`bytes`、`sampleCount`、`durationMs`。
4. `watch://run-abort` 至少包含对象字段：`requestId`、`reason`。
5. 不记录 Token、PCM、转录文本或输入框内容。

必须增加测试：

- Rust 侧验证交给 `emit` 的 payload 序列化后是 JSON object，不是 JSON string。
- TS 侧用与真实 Tauri 一致的对象型 payload 验证完整读取；字符串 payload 必须 fail-closed，不能被当作成功。

## Repair 2 — 先注册等待槽，再 emit

当前 `server.rs` 先 emit `watch://admission-request`，随后才调用 `request_admission()` 注册 `pending_admission`。若 WebView 很快响应，`watch_admission_resolve` 会看到没有 waiter，返回 false；随后 Receiver 独自等待并超时。

要求：

1. 调整 AdmissionGate API，使 pending admission waiter 在 emit 之前已经可见。
2. 推荐拆成 `begin_admission(requestId)`（完成互斥检查、lease 回收、建立 waiter）→ emit → `wait_admission(...)`；也可让 gate 接受一个 emit closure，但必须保持清晰的注册顺序。
3. 任意 emit 失败、等待超时、stale ID 或拒绝都必须 fail-closed，并清理当前 request 对应的 waiter/gate；不得清掉更新的 request。
4. 保持 admission 在 durable save 之前；`409` 不得覆盖旧 `received_watch.wav`。

必须增加一个同步响应测试：

- 测试 event sink 在 `emit` 调用栈内立刻调用 `resolve_admission`；该请求必须被正确接收，不能 stale、不能超时。这是对顺序的直接回归测试，不能用轮询等待 pending 的辅助线程替代。

## Repair 3 — 外部运行错误路径不得调用麦克风 stopCapture

现有 Provider `onError` 在 `state === 'recording'` 时无条件执行 `stopCapture()`。外部 Watch run 同样会进入 `recording` 状态，因此若 Provider 在外部 PCM feed 期间报错，会误触麦克风专用采集停止逻辑，违反冻结契约。

要求：

1. 只有真实麦克风 run 才能调用 `stopCapture()`、恢复麦克风/系统静音等 mic-only teardown。
2. 外部 run 在 recording 或 processing 阶段收到 Provider error 时，必须走 requestId/runId 关联的外部清理：Provider 会话取消、JS reservation 清除、Rust gate 通知/释放、overlay/Esc 状态复位。
3. 清理必须恰好一次且幂等；stale error/abort 不得清理后续 run。
4. 保持现有麦克风 error 行为不变。

必须增加测试：

- 外部 run 在 PCM feed/`recording` 阶段触发 `onError`：`stopCapture` 调用次数为 0；Rust release invoke 恰好一次；Provider/JS 状态关闭；不得进入 Paste，不得重复写 History。
- 现有麦克风 run 的 error 测试证明 `stopCapture` 行为未回归。

## Part B — Watch UI（仅在 Part A 通过后开始）

实现 `docs/WATCH-UI-Z-HANDOFF.md` 已冻结的方向。这是 **Wear OS App**，不是表盘。

### 版本与不可覆盖证据

- 当前父版本：Watch `0.2.0-dev.1`。
- 本轮候选版本：`0.2.0-dev.2`，`versionCode = 2`。不得覆盖或复用父版本号。
- UI 修改前，在 `design/watch-ui/0.2.0-dev.1-baseline/` 记录父 Git SHA、现状截图、关键资源 SHA-256 和状态 `accepted-parent`。
- 实验输出放 `design/watch-ui/staging/`；首次给 PM/用户查看前，冻结到 `design/watch-ui/0.2.0-dev.2-candidate.1/`，状态 `candidate`。
- 候选目录必须包含：README（父版本、变更、继承锁、偏差、状态）、480×480 圆屏截图、各关键状态截图、SHA256SUMS。不得把真实 Token/IP/设备信息写进截图或文件。
- 不删除、不覆盖 baseline 或 candidate；如再修视觉问题，递增 `candidate.2`。

### 必须实现的页面与状态

只保留三个页面加内联状态：

1. 开发配置：PC RFC1918 IPv4、Port、64 位十六进制 Dev Token、保存并应用。Token 默认遮罩，只允许明确的临时显示动作。
2. Ready：明确的健康检查/刷新动作；`Transport available` 只代表 `/api/health` 可达；一个主要录音按钮。
3. Recording：采样数推导时长、Stop；如保留 Cancel，其语义只能是停止并丢弃、不上传。

内联状态：

- Stop 后自动整段上传，不出现独立 Send 页面。
- Uploading：保持活动界面亮屏，不要求用户持续点击。
- Upload failed：保留原 WAV，提供 Retry 和 Later。
- Later 返回 Ready 并明显显示 Pending upload；开始新录音前必须让用户明确丢弃旧 WAV，禁止静默覆盖。
- Uploaded to PC：只短暂显示运输/PC 接收成功，再回 Ready；禁止显示 `Transcribed`、`Recognition complete`、`Text inserted`。
- `409`、网络错误与所有非 2xx 都必须保留 WAV并可重试；重试不重新录音。
- 仅保留 start、stop、upload success、upload failure 四种简单震动反馈。

### 圆屏与文案要求

- Galaxy Watch 7 480×480 圆形安全区内不得裁切主要控件；配置表单支持 Wear 滚动/旋钮，触控目标可用。
- 不显示轮播圆点，除非实际实现对应分页导航。
- 文档和截图只用 `192.168.x.x`、`<PORT>`、`A1B2••••••••7890` 等占位/遮罩内容。
- 不新增麦克风灵敏度、About、AI 整理、配对或其他页面。

### UI 自动验收

- ViewModel 状态机覆盖：Ready → Recording → Stop → Uploading → Success/Failure；Retry、Later、Pending upload、显式 discard。
- 失败后的 Retry 发送同一 WAV 字节，不重新录音。
- duration 继续按实际 sample count 推导。
- Debug cleartext 允许、Release 禁止的既有测试保持通过。
- 运行 Watch 单元测试、lint、Debug/Release 构建；返回 Debug APK 路径和 SHA-256，但不得提交 APK。
- 返回冻结候选目录、全部截图路径、SHA-256 manifest。真实 Galaxy Watch 视觉/交互验收留给 PM/用户后续执行。

## 允许修改

只允许修改 Z3 已获准的产品/测试路径：

- `client/src-tauri/src/main.rs`
- `client/src-tauri/src/watch_receiver/admission.rs`
- `client/src-tauri/src/watch_receiver/server.rs`
- `client/src/services/watchIngress.ts`
- `client/src/services/recorder/RecorderOrchestrator.ts`
- 与上述修复直接对应的现有/新增 Rust、Vitest 测试
- `watch/app/src/main/java/com/sayit/watch/MainActivity.kt`
- `watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt`
- `watch/app/src/main/java/com/sayit/watch/ui/RecordingViewModel.kt`
- `watch/app/src/main/res/values/strings.xml`
- 与 UI/状态机直接对应的 Watch 测试
- `watch/app/build.gradle.kts` 仅更新本轮候选版本号
- `design/watch-ui/0.2.0-dev.1-baseline/**`
- `design/watch-ui/0.2.0-dev.2-candidate.1/**`
- `HANDOFF.md`、`PROJECT_PROGRESS.md` 仅记录真实结果

如确实需要新增其他路径，先停止并说明必要性。不得修改依赖或 lockfile。

## 禁止事项

- 不新增/复制 ASR、Whisper、Provider、History、Paste 或 text insertion 实现。
- 不直接调用 `local_transcribe`。
- 不开发 Streaming、WebSocket Watch transport、Opus、正式配对、发现、mDNS、二维码、后台录音、唤醒词、Home 双击。
- 不改 AI 功能、Target Manager、Focus Tracking、Target Lock、VAD、更新、备份、存储安全或 Release HTTP。
- 不提交 Token、WAV、模型、APK、exe、build cache、本机绝对路径或设备信息。

## 必须运行并回传的验证

1. `client`: focused Repair tests。
2. `client`: `npm test`。
3. `client`: `npm run build`。
4. `client/src-tauri`: focused `watch_receiver`/admission tests。
5. `client/src-tauri`: `cargo test`。
6. `client/src-tauri`: `cargo build --release`。
7. 对 Release exe 扫描 Delivery 1A/1B Receiver 与 admission marker，必须全部不存在。
8. `git diff --check`。
9. `watch`: `gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease`，并计算 Debug APK SHA-256。
10. UI candidate manifest/hash 检查，确认 baseline/candidate 均未被覆盖。

回传：

- 修复 commit SHA 和 review base。
- changed-file list。
- 每条命令、exit code、通过/失败数量。
- 三项 Repair 与测试的逐项映射。
- UI 状态机与 `docs/WATCH-UI-Z-HANDOFF.md` 的逐项映射。
- Watch 候选版本、APK 路径/哈希、冻结截图/manifest 路径。
- 未解决风险。
- 明确声明未做真机、未声称 Delivery 1B VERIFIED。

完成后 push 到 `origin/codex/review-watch-pipeline` 并立即停止，等待 PM 复验。不要合并、tag、release 或推送公开上游。
