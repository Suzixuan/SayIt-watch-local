# Delivery 1B — Galaxy Watch 7 真机闭环与 10 次验收

## 给同事 D 的执行指令

本任务只做生产设备验收，不改产品源码。基线为
`codex/review-watch-pipeline` 上 PM 已接受的 Repair 4 提交
`5de4d989e3af215fa77ae9abebbf64facf2e0307` 及其后续 PM 验收文档提交。

目标只有一个：

`Galaxy Watch 7 -> Wi-Fi -> Windows SayIt -> existing ASR -> History -> Paste -> 用户预先选中的输入框`

## 硬边界

- 使用 Debug Watch APK和 Debug Watch Receiver；Release HTTP 仍禁止。
- AI 整理关闭。
- 用户自行提前点击 ChatGPT/Codex 等目标输入框。
- 不改 Target Manager、Focus Tracking、Target Lock、VAD、ASR、Provider、History、
  Paste、Streaming、配对、发现、后台录音或任何产品功能。
- 不提交 APK、WAV、Token、设备序列号、IP、日志或截图中的敏感值。
- 如果发现必须修改源码，立即停止，只报告复现步骤；不得边测边修。

## 准备与只读证据

1. 记录当前 Git SHA、Debug APK 路径与 SHA-256。
2. 在 Galaxy Watch 7 安装/升级该 APK；记录 Watch 型号、Wear OS/API 版本，但不记录
   设备序列号。
3. 在 Watch 上确认 Config/Ready/Recording/Pending/Uploading/Failed/Uploaded 状态：
   无裁切、无叠字、主要动作可点、滚动可达、Token 默认遮罩。
4. Windows 使用单个 RFC1918 LAN IPv4 启动 Debug Receiver，Token 仅通过本地环境变量
   与 Watch 设置输入，不写日志/文档。
5. SayIt 选择现有可用 Provider，确认 ready；关闭 AI 整理。

## 预备烟测

在正式计数前完成一次短句：

`你好，这是 Galaxy Watch 语音测试。`

确认五阶段均成功：Watch 录音、网络发送、PC 接收、ASR、Paste。若失败，保留 WAV、
记录固定非敏感原因并停止；不要进入十次计数。

## 正式 10 次连续闭环

至少覆盖：

1. `你好，这是 Galaxy Watch 语音测试。`
2. `帮我分析一下 NVIDIA 今天为什么跌。`
3. `Codex 帮我重新检查一下这个项目。`
4. 一段连续 15–20 秒中文。

其余轮次可重复这些语句，但必须连续完成 10 次真实流程。每轮记录：

| Run | Watch record | Network | PC receive | ASR | Paste | Stop→Paste ms | Retry | Failure reason |
|---:|---|---|---|---|---|---:|---|---|

- 总延迟从 Watch 点击 Stop 到目标输入框完整出现文字。
- 记录每轮延迟，并计算 median 与 P95；P95 使用 10 个样本的 nearest-rank：排序后第
  `ceil(0.95 * 10) = 10` 个值。
- 任一轮失败都如实记录。为了证明“连续 10 次”，失败后修复外部条件并重新从 Run 1
  开始计数；旧失败记录仍保留在附录，不得删除。
- 409/网络失败必须验证原 WAV仍可 Retry，不重新录音；但正式 10 次成功序列不得靠
  隐瞒失败完成。

## 通过标准

- 10 次连续流程五阶段全部成功。
- 至少一次 15–20 秒连续中文成功。
- 每次文字真实进入用户预先选中的 Windows 输入框。
- 无 Watch UI 裁切/叠字/不可达动作。
- 返回 10 个 Stop→Paste 延迟、median、P95、任何失败/重试记录。

## 回传并立即停止

返回：Git SHA、APK SHA-256、设备型号/系统版本、Provider 类型、UI 真机检查、预备烟测、
10 次表格、median/P95、失败附录、最终 VERIFIED/NOT VERIFIED。不要 merge、tag、
release、打包安装器或继续开发功能。
