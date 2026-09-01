# SayIt Watch — 0.2.0-dev.3-final 冻结

> 冻结的最终 dev.3 candidate：圆形表盘录音界面（MIC READY / RECORDING）。
> 分支：`codex/wip-watch-dial-ui`；与代码/截图/哈希一致。

## Candidate 信息

- 版本：`0.2.0-dev.3-final`（versionCode 3）
- 提交基线：
  - `714ac2f` feat(watch): restore low-key Settings entry + shorten Cancel label
  - `d874395` test: harden watch transport status and startup order
  - `41a79d4` wip(watch): preserve white dial UI experiment
- 构建时间：`2026-08-31 22:43:15`
- APK：`watch/app/build/outputs/apk/debug/app-debug.apk`
- **APK SHA-256**：
  `D90B56957BAE546DA94520E0343BF97B349890AD8E36F58258343AAF47535DC6`

## 冻结的候选特性

- **圆形表盘**（Galaxy Watch 7，SM-L310，480×480 px @ density 340 → 约 226dp 直径）：
  - 白色表盘底 `#F7F8FA`；60 根**灰色分钟刻度**（`#9AA3AE`，贴近表圈，长度为蓝色主刻度一半）；
  - 12/3/6/9 四个**蓝色主刻度**（更长更粗，`#1976E9`）；
  - 标题 `MIC READY` / `RECORDING`：灰色 `#9AA3AE`、小字号（9sp）、置于圆心上方；
  - Ready：**线性麦克风**（深灰 72dp，居中），麦克风下方一个**低调 8sp 灰色「设置」入口**（首次配置进 Config 用）；
  - Recording：**对称 11 根细蓝波形** + 计时（20sp），底部动作文案为「**取消**」；
  - **无 Wi-Fi / 电池图标**，无按钮/状态栏/多余元素。

## 本轮修复（已并入基线）

1. **上传失败 → transportAvailable=false**：`RecordingViewModel.send()` 的发送失败（cleartext 不可用 / upload Failure）分支调用
   `uiEvent { it.healthChecked(false) }`，避免 Ready 状态仍显示"可用"。
   （Commit `d874395`）
2. **event sink 顺序回归测试**：新增 `watch_receiver::tests::event_sink_registered_before_receiver_start`，
   断言 `main.rs` 中 `set_event_sink` 先于 `watch_receiver::start`，防止 bridge_timeout 复发。
   （Commit `d874395`）

## 验证结果

- `cargo test`（client/src-tauri）：**159 passed, 0 failed**（含新回归测试）
- `gradlew :app:testDebugUnitTest`：**BUILD SUCCESSFUL**
- `gradlew :app:assembleDebug`：**BUILD SUCCESSFUL**

## 产品范围（保持）

- Config 仅用于首次配置 / 配置失效 / 手动设置；正常启动直接 Ready。
- 只保留 **Ready**、**Recording**。
- Stop 后**静默上传并回 Ready**；不做上传成功/失败、Retry、Pending、录音保留。

## 真机截图

- `ready.png` — MIC READY 真实手表截图
- `recording.png` — RECORDING 真实手表截图
