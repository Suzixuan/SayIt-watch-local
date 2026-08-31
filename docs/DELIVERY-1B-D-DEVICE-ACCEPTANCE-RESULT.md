# Delivery 1B 真机闭环验收报告 — NOT VERIFIED

## 一、准备与只读证据

| 项 | 值 |
|---|---|
| Git SHA（基线） | `ffa960fdc1df4544d442b2051b592af108a5527e`（`codex/review-watch-pipeline`，PM 验收文档提交，含 Repair 4 `5de4d98`） |
| Debug APK | `watch/app/build/outputs/apk/debug/app-debug.apk` |
| APK SHA-256 | `8F58722B018890AEF3BD0DF428AC31AB6F793952DC4515586D655062E21CB8EF` |
| APK 版本 | versionCode 2 / versionName `0.2.0-dev.2` |
| Watch 型号 | Galaxy Watch 7，`SM-L310`（fresh7blue） |
| Wear OS / API | Android 16 / API 36，480×480（逻辑 226dp @340dpi） |
| PC LAN IPv4 | `192.168.12.144`（DHCP，验收中途由 .142 变为 .144） |
| Debug Receiver | `192.168.12.144:18099`（PID 30792，tauri dev 启动），单 RFC1918 IPv4 绑定 |
| 前端运行方式 | `npm run tauri -- dev`（vite 1420 + cargo run） |
| ASR Provider | 本地 SenseVoice GGUF（`sensevoice-small-gguf`，CPU），日志 `GGUF ASR ready in ~340ms`，max_audio_ms=30000 |
| AI 整理 | 未配置（本次未走到该阶段） |

## 二、Watch UI 真机检查（验收第 3 项）

- Config 页：标题/IP/Port/Token 行/Save & Apply 均渲染；**Token 默认遮罩 `3f8a••••••••e4d3`**（非明文），Show 可显式揭示 ✓
- Ready 页：标题/Transport 状态/Check·Refresh chip/Settings/Record 圆钮/状态行均可见 ✓
- **可滚动语义**：Config/Ready 内容超过 226dp 圆屏高度，滚动可达所有元素（PM Repair 4 验收的滚动语义）✓
- Upload failed overlay：**完全可读、无叠字**（candidate.4 不透明面板生效）✓
- 无裁切、无不可达动作（滚动后全可达）✓

## 三、预备烟测与 10 次闭环 — 阻断

五阶段链路（Watch 录音 → 网络发送 → PC 接收 → ASR → Paste）**未能完成**：

- Watch 录音：✓（Ready → Recording，样本时长显示）
- 网络发送：✓（每次 upload receiver 都收到新 requestId）
- **PC 接收：✗ — `409 bridge_timeout`**

### 阻断详情（可复现）

1. 每次 Watch upload → receiver 发出 `watch://admission-request` → **5 秒内前端 WebView 无响应** → `bridge_timeout` → Watch 收到 `HTTP 409 (requestId=...)`。
2. Watch UI 正确显示 Upload failed overlay（保留 WAV + Retry/Later），Retry 重新提交同一 WAV（新 requestId，不重录）——**409 保留/Retry 机制验证通过**。
3. 根因：PC 端 `RecorderOrchestrator.init()` 里的 `initWatchIngress`（注册 `listen('watch://admission-request')`）在 WebView 中**未生效**。验证过程：
   - 直接跑 debug exe（devUrl 1420）→ 主窗口 WebView 空白白页（截图纯白）→ 前端未加载 → bridge_timeout。
   - 改 `npm run tauri -- dev` → 主窗口渲染真实 React UI（紫色主题，截图确认）→ **但 bridge_timeout 依旧**。
   - Rust 侧 emit 事件名 `watch://admission-request` 与前端 listen 一致；sink 在 setup 注册；ASR ready；dev server 200。
   - 结论：WebView 中 `listen('watch://admission-request')` 未能收到 Rust 的 `app_handle.emit` 事件，属于 **PC 端前端桥接的运行时问题**（非网络/录音/环境问题）。

### 复现步骤

```
1. cd client && set SAYIT_WATCH_BIND_IP=192.168.12.144, SAYIT_WATCH_PORT=18099,
   SAYIT_WATCH_DEV_TOKEN=<64hex> && npm run tauri -- dev
2. Watch 录音 → Stop（自动上传）
3. sayit.log: "watch admission: bridge timeout for request ... rejected (bridge_timeout)"
4. Watch UI: Upload failed / HTTP 409 (requestId=...)
5. Retry → 新 requestId 再次 bridge_timeout
```

## 四、结论

**最终判定：NOT VERIFIED。** Delivery 1B 真机闭环因 PC 端 `watchIngress` 前端桥接未在真实 SayIt WebView 中生效而阻断。Watch 端全部就绪（录音/发送/409 保留/Retry/UI 可读）；ASR 引擎就绪；但 PC 前端未能响应 admission 事件，ASR→History→Paste 阶段无法到达。

按任务硬边界未改任何产品源码。需 PM/开发定位 `listen('watch://admission-request')` 在真实 WebView 中未收到 `app_handle.emit` 的原因（候选方向：事件名含 `://` 的 Tauri 2 兼容性、dev 模式 IPC 初始化时序、或 `RecorderOrchestrator.init()` 未被实际调用）。

**失败附录（保留，未删除）**：所有 bridge_timeout 请求及 requestId 见 sayit.log（22:14–22:35，多次）。409 Retry 机制验证通过，未隐瞒任何失败。
