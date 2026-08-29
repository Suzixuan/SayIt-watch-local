# SayIt 本地自用构建 — 安全修复与改动说明

> **注：本文件为第一轮修复说明，已被《PM-REVIEW-FIXES.md》取代。** PM 复审提出的 8 项安全遗留（令牌同源、DPAPI 容器覆盖与迁移、日志遮蔽、XFF 受信名单、彻底关更新、导出确认、元数据授权开关）的最终状态以 `PM-REVIEW-FIXES.md` 为准。

> 基于上游 [crosswk/SayIt](https://github.com/crosswk/SayIt)（commit: 克隆时刻的 `main`）修复安全审查发现的问题，并构建为「本地自用」安装程序。全部 AI 功能保留（本地 ASR / 云端 ASR / 云端 LLM / 服务器模式 / 上下文写作 / 热词 / 历史等），仅通过**鉴权、加密存储、安全默认值**修复问题，未删除任何功能。

## 产物

- 安装程序：`SayIt_0.1.8_x64-setup-local.exe`（NSIS，x64，8.3 MB，含 CPU 版语音引擎与 VC++ 运行库）
- 源码：`client/`（Tauri + React）、`server/`（FastAPI + gateway）
- 说明：本地模式为默认工作模式（设备端 GGUF 推理，完全离线、数据不出本机）；本地 ASR 引擎按需求仅 CPU 构建（未移除 GPU/Vulkan，后续安装 Vulkan SDK 加回 `vulkan` feature 重新构建即可）。

## 一、客户端（Tauri / Rust + React）

### 1. 移除全局 TLS 证书校验绕过（高危，原审查 #1 一部分）
- 文件：`client/src-tauri/src/main.rs`
- 原状：WebView2 全局参数含 `--ignore-certificate-errors`，且允许环境变量 `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS` 覆盖（可被注入降级参数）。
- 修改：删除该参数；浏览器参数固定为应用内常量，不再读取环境变量覆盖。中间人无法再冒充后端（公告/反馈/更新清单/WebSocket 全部走 WebView 网络栈）。

### 2. 自动更新不再外联官方站点（高危，原审查 #1 供应链链路的另一半）
- 文件：`client/vite.config.ts`、`client/src/services/runtimeConfig.ts`、`client/src/features/update/autoUpdate.ts`、`client/src/services/defaults.ts`
- 原状：默认服务器/更新通道指向 `https://sayitapp.site`，更新清单由 WebView 拉取且安装包无签名（仅 SHA-512 与清单一致），存在「中间人篡改清单 → 投递任意安装包 → 退出时静默安装」的 RCE 链。
- 修改：
  - 默认后端地址改为 `http://127.0.0.1:8000`（本地）；更新通道跟随该地址，`getOfficialUpdateBaseUrl()` 不再指向官方站点，杜绝外联。
  - `autoCheckUpdate` 默认改为 `false`（默认不周期检查更新）；用户如显式配置服务器并放置 manifest，仍可手动/自动更新，功能保留。
- 补充：即使未来启用更新，也建议开启 Tauri updater 的 Ed25519/minisign 签名校验（当前上游未提供签名密钥，暂无法启用）。

### 3. API 密钥等敏感设置落盘 DPAPI 加密（中危，原审查 #5 的存储部分）
- 文件：`client/src-tauri/src/storage/mod.rs`、`client/src-tauri/Cargo.toml`
- 原状：`cloudAsr.apiKey`、`cloudAi.*.apiKey` 等明文写入本地 SQLite。
- 修改：对 key 名匹配 `apikey / accesstoken / secret / password / credential / appid / token` 的设置值，写盘前用 Windows DPAPI（`CryptProtectData`，绑定当前 Windows 用户+本机）加密，格式 `dpapi:<base64>`；读取时透明解密。前端展示、Rust 云厂商调用、配置导入导出全部走 `Storage::get/set`，自动适配。
  - 备份导出仍为明文（跨机迁移必需，DPAPI 绑定单机）；导入时自动重新加密。
  - 解密失败（换机/换用户）原样返回并记日志，不崩溃。

### 4. 新增内容安全策略 CSP（中危，原审查 #6）
- 文件：`client/src-tauri/tauri.conf.json`
- 原状：`"csp": null`。
- 修改：设置 CSP：`default-src 'self'`；`script-src 'self' 'unsafe-inline'`；`style-src 'self' 'unsafe-inline'`；`connect-src http: https: ws: wss:`（兼容用户可配置的后端地址）；`object-src 'none'`；`base-uri 'none'`；`form-action 'none'`。禁止远程脚本/插件/内嵌 iframe。

### 5. 服务器模式鉴权令牌（新增，原审查 #3 的鉴权需求）
- 文件：`client/src/services/serverAuth.ts`（新增）、`client/src/services/websocket.ts`、`api.ts`、`feedback.ts`、`diagnostics.ts`、`notice.ts`、`asrCorrection.ts`、`features/settings/ServerSection.tsx`、`services/defaults.ts`、`i18n/locales/*.json`
- 修改：新增可选的「服务器访问令牌」设置。配置后：
  - 所有 `/api/*` 请求自动附加 `Authorization: Bearer <token>`；
  - WebSocket 连接自动附加 `?token=<token>`。
  - 设置页新增「访问令牌」输入框（密码框、保存后重连）。
  - 令牌按敏感字段走 DPAPI 加密存储。

### 6. 安全默认值
- 文件：`client/src/services/defaults.ts`
- `workMode` 默认由 `server`（连公共试听服务器）改为 `local`（设备端推理，完全离线）。
- `autoCheckUpdate` 默认由 `true` 改为 `false`。

### 7. 其他
- `client/src-tauri/rust-toolchain.toml`（新增）：固定 `stable-x86_64-pc-windows-msvc` 工具链，避免 GNU 目标因缺 `dlltool` 构建失败。
- `client/src-tauri/tauri.conf.json`：bundle targets 由 `nsis, msi` 改为 `nsis`（本地自用只需 NSIS，规避 WiX/MSI 构建脆弱性）。
- `client/src-tauri/Cargo.toml`：移除 `transcribe-cpp` 的 `vulkan` feature（按需求仅 CPU 构建）；为 DPAPI 新增 `windows` crate 的 `Win32_Security_Cryptography` feature。

## 二、服务端（FastAPI + gateway）

### 8. Gateway 静态文件路径穿越（高危，原审查 #2）
- 文件：`server/gateway/proxy.mjs`
- 原状：`path.join(STATIC_DIR, url)` 会把 `/../../config.yaml` 归一化到 web 目录之外，可读取 `config.yaml`（含 API 密钥）、`.env`、TLS 私钥等。
- 修改：新增 `resolveStatic()`——先 `decodeURIComponent` 解码（防 `%2e%2e`），再 `path.normalize`，并校验结果必须位于 `STATIC_DIR` 之下，否则 404。已实测 `GET /../../config.yaml` 返回 404。

### 9. 鉴权令牌（新增，原审查 #3）
- 文件：`server/backend/app/config.py`、`server/backend/app/main.py`
- 修改：
  - `ServerConfig` 新增 `api_token`（来自 `SAYIT_API_TOKEN` 环境变量或 `server.api_token`），默认空 = 不鉴权。
  - 设置了令牌后：HTTP 中间件要求所有 `/api/*` 携带 `Authorization: Bearer <token>`；WebSocket 握手要求 `?token=<token>`（否则 close 4401）。`/healthz` 与静态页不校验。

### 10. 安全默认配置（原审查 #3/#10）
- 文件：`server/backend/app/config.py`、`server/config.example.yaml`
- `web_demo.enabled` 默认 `true` → `false`（匿名网页体验默认关闭）。
- 服务端 `server.host` 示例默认 `0.0.0.0` → `127.0.0.1`（仅回环监听）。
- `AdminConfig` 默认口令 `admin/sayit` 已移除（密码默认空，必须显式设置，否则视为未启用）。

### 11. 客户端 IP 解析不再信任可伪造的 XFF（低危，原审查 #7）
- 文件：`server/backend/app/net.py`（新增）、`main.py`、`ratelimit.py`、`asr_corrections.py`、`diagnostics.py`
- 原状：限流/配额取 `X-Forwarded-For` 最左值，攻击者自造 XFF 头即可绕过 per-IP 限制。
- 修改：新增 `trusted_client_ip()`——直连对端为公网地址时直接用它、忽略 XFF；仅当直连对端为回环/私网（确系背后有代理）时，才从右往左取第一个非私网 XFF 条目。四处调用点统一接入。

## 三、验证情况

- 客户端：`vitest` 323/323 通过；`tsc + vite build` 通过。
- 服务端：`pytest` 52/53 通过；1 个失败为上游自带文档断言（`test_docs_hotword_support.py` 期望中文 README 包含「本地 Qwen3-ASR」等文案，与上游英文 README 不符），与本次改动无关。
- 构建：`cargo build --release` 通过（含 C++ 语音引擎），NSIS 安装程序生成成功，已静默安装验证文件布局完整（sayit.exe + 12 个引擎 DLL + VC++ 运行库 + 卸载器）。
- 未做 GUI 运行测试（Tauri 桌面应用），建议安装后人工验证。

## 四、遗留说明（未在本轮处理）

- `npm audit` 剩 2 个 moderate 告警（`react-router` 6.x 的 open-redirect/SSR 反序列化）：升级到 v7 为破坏性变更，且桌面应用无外部导航场景，风险低，未升级。
- 服务端完整运行依赖较重（torch / qwen-asr / funasr），本地自用默认走客户端「本地模式」可不部署服务端；如需部署，`server/` 下按 `docker compose` 或 `uvicorn app.main:app --host 127.0.0.1 --port 8000` 启动（首次需 `pip install -r backend/requirements.txt`）。