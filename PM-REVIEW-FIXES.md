# SayIt 本地自用版 — PM 复审问题修复报告（第二轮）

> 本轮只修 PM 列出的 8 项安全遗留，未改动任何其他原始功能（AI / ASR / 云 API / 本地识别 / 服务器模式全部保留）。
> 按 PM 要求：**未生成 / 未覆盖新的安装程序**（候选包 `SayIt_0.1.8_x64-setup-local.exe` 保持上一轮产物，时间戳 2026-08-24 17:31 未变）。

---

## 一、逐项修复与改动

### 1. 服务器 Token 仅发送到同源服务器；官方回退绝不携带（含跨域回退测试）
- `client/src/services/serverAuth.ts`：新增 `shouldAttachServerToken(url)` —— 仅当目标 URL 与「用户配置的服务器地址」同源（scheme+host+port）才附加 `Authorization: Bearer <token>`；跨域一律不带。
- `client/src/features/update/updateChecker.ts`：`fetchManifest` 增加 `{ auth }` 参数。configured（用户配置服务器）走 `serverFetch`（可能带令牌）；**官方更新回退走无鉴权的普通 `fetch`，无论何种域名都绝不含令牌**。
- 测试：`client/src/services/__tests__/serverAuth.test.ts`（同源真/假、跨域不携带、未配置令牌不附加）；`client/src/features/update/__tests__/updateCheckerAuth.test.ts`（configured 走 serverFetch、官方回退断言无 Authorization 头、configured 有版本时不回退）。

### 2. DPAPI 覆盖凭据容器 + 迁移明文 + 禁止静默降级
- `client/src-tauri/src/storage/mod.rs`：
  - `is_sensitive_key` 扩展：`*.profiles`（`cloudAi.profiles` / `cloudAsr.profiles` 等凭据容器）以及 `consolekey/accesskey/authkey`（覆盖豆包新版控制台 APP Key 等实际凭据）。
  - `maybe_encrypt_value` 改为返回 `Result`：**DPAPI 失败返回 Err，写入方中止，绝不落明文**；非 Windows 平台敏感 key 直接拒绝写入。
  - `maybe_decrypt_value` 改为返回 `Result`：解密失败记日志并返回不可用（不再把密文当明文返回，杜绝静默降级）。
  - 新增 `migrate_plaintext_secrets()`，`Storage::new` 启动时执行：把已存在的明文敏感记录迁移为 `dpapi:` 加密（幂等，DPAPI 失败仅记日志跳过）。
  - `get()` 增加懒迁移：读取到敏感 key 的明文值时尝试加密写回。
  - `set()` / `apply_config_transaction()`：加密失败返回 Err（事务回滚）。
- Rust 测试（`storage/mod.rs` tests）：敏感 key 覆盖 profiles 容器；DPAPI 加解密 roundtrip；敏感 key 不落明文（成功必带 `dpapi:` 前缀或 Err）；**明文记录启动迁移后落盘为 `dpapi:`**。

### 3. WebSocket 用 Token 鉴权，但日志/诊断完全遮蔽 token
- `client/src/services/websocket.ts`：新增 `maskWsUrl(url)`，`Connecting` / 超时日志一律记录遮蔽后的 URL（`token=***`）。
- `client/src/lib/sanitize.ts`：敏感 key 名单加入 `token`（及 `consolekey/accesskey/authkey`），诊断包导出时 `serverToken` 等一律脱敏。
- `server/backend/entrypoint.sh`：uvicorn 增加 `--no-access-log`，关闭访问日志，避免握手 query 中的 `?token=` 被记录。
- 测试：`websocketPrivacy.test.ts`（maskWsUrl 遮蔽 token、无 token 不变）；`sanitize.test.ts`（serverToken 被脱敏）。

### 4. XFF/X-Real-IP 仅在直连地址命中显式受信代理名单时采信
- `server/backend/app/net.py` 重写：`trusted_client_ip(direct, forwarded, trusted_proxies)`。
  - 默认 `trusted_proxies=()`：**任何情况下都不采信代理头**（私网/回环地址不再自动视为可信代理）。
  - 仅当直连地址命中名单（IP 或 CIDR）时，从右往左取第一个非私网条目；全私网则返回最右（受信代理自身，让内网客户端共享配额，防伪装绕过）。
- `server/backend/app/config.py`：`ServerConfig.trusted_proxies`（env `SAYIT_TRUSTED_PROXIES` 或 yaml `server.trusted_proxies`）。
- 接入点：`main.py`（WS per-IP 计数/配额）、`ratelimit.py`（中间件，构造时注入）、`asr_corrections.py`、`diagnostics.py`（从 `app.state.config` 读取）。
- 测试：`server/backend/tests/test_net_trusted_proxies.py`（公网直连忽略伪造 XFF、私网直连未配置名单忽略 XFF、命中名单取右起首个非私网、CIDR 命中、全私网返回最右、X-Real-IP 同规则）。

### 5. 本地自用版彻底关闭更新（非仅改默认值）
- `client/src/features/update/autoUpdate.ts`：`UPDATE_ENABLED = false`。`startUpdateService()` 直接返回（不恢复 pending、不下载）；`checkForUpdateNow()` 返回 null；`installPendingUpdate()` 空操作；`discardPendingForChannelSwitch()` 保留用于清理陈旧 pending 记录。
- `client/src-tauri/src/commands/system.rs`（Rust 侧，杜绝任何直连入口）：
  - `install_pending_update_on_exit` → 空实现（退出静默安装彻底关闭）；
  - `download_update` → 返回 Err（不下载）；
  - `install_downloaded_update` → 返回 Err（不启动安装程序）；
  - `verify_update_package` → 恒返回 false（不认可任何已下载包）。
  - 相关更新辅助函数标记 `#[allow(dead_code)]` 保留编译。
- AI / ASR / 云 API / 本地识别完全不受影响。

### 6. 全量配置导出：默认排除密钥；含密钥需显式确认
- `client/src-tauri/src/commands/backup.rs`：
  - `build_config_value(storage, include_secrets)` —— 默认（`include_secrets=false`）剔除全部敏感设置 key（`CONFIG_EXCLUDE` + 所有 `is_sensitive_key` 命中的 key）；
  - `ConfigExportSelection` 增加 `include_keys`（默认 false）。
- `client/src/services/backup.ts`：`{ mode: 'full'; includeKeys?: boolean }`。
- `client/src/features/settings/ConfigTransferDialogs.tsx`：完整配置导出新增「包含密钥（明文）」勾选（默认不勾）+ 显式二次确认「我已知晓导出文件包含明文密钥，不得直接分享」；未确认则导出按钮禁用；默认导出显示「不含密钥」并提示可安全分享。
- i18n：`configTransfer.includeKeys / includeKeysMeta / keysConfirm / noKeys / noKeysNotice / singleJsonWithKeys`。
- Rust 测试（`backup.rs` tests）：默认导出剔除 `cloudAi.apiKey`、`cloudAsr.profiles`；显式包含时以明文导出。

### 7. Server 模式诊断元数据默认关闭 + 用户授权开关
- `client/src/services/defaults.ts`：`serverShareMetadata: false`（默认关闭）。
- `client/src/services/serverAuth.ts`：`getServerShareMetadata()`（缓存 + `invalidateServerShareMetadata()`）。
- `client/src/services/websocket.ts`：
  - `serverMetadataPayload(clientMeta, appContext)` 纯函数（生成 client_meta / app_context）；
  - `sendStart` 仅在 `shareServerMetadata` 为 true 时才附带元数据；connect() 时读取开关刷新缓存。
- `client/src/features/settings/ServerSection.tsx`：新增「向服务器上报诊断元数据」Switch（默认关），开启时持久化 + 失效缓存 + 重连。
- i18n：`server.shareMetadata / server.shareMetadataDesc`。
- 测试：`websocketPrivacy.test.ts`（元数据负载生成/为空）、`serverPrivacyDefaults.test.ts`（`serverShareMetadata` 默认 false）。

### 8. 针对性测试 + 全量回归
- 新增客户端测试 4 个文件（16 例）：serverAuth / updateCheckerAuth / websocketPrivacy / serverPrivacyDefaults；sanitize 增加 token 脱敏用例。
- 新增服务端测试 1 个文件（7 例）：test_net_trusted_proxies.py。
- 新增 Rust 测试（storage 4 例 + backup 1 例）。

---

## 二、测试输出

| 套件 | 结果 | 说明 |
|---|---|---|
| 客户端 `vitest` | **29 文件 / 339 测试全部通过** | 较上轮新增 16 例针对性测试 |
| 客户端 `tsc + vite build` | 通过 | 无类型错误 |
| 服务端 `pytest` | **58 通过 / 1 失败** | 唯一失败为上游自带 `test_docs_hotword_support.py`（期望中文 README 含「本地 Qwen3-ASR」等文案，与上游英文 README 不符），**与本次改动无关**，属上游既有问题 |
| Rust `cargo test --release` | **108 通过 / 0 失败**（4 ignored，为 GPU 实机模型测试） | 含 DPAPI 容器覆盖、迁移、导出排除密钥等新测试 |
| Python `py_compile` + `node --check` | 全部通过 | 语法校验 |
| 配置默认值验证 | `api_token=''`, `trusted_proxies=[]`, `web_demo.enabled=False`, `admin.password=''`, `host=127.0.0.1` | 安全默认值确认 |

---

## 三、未解决项 / 说明

1. **服务端 `test_docs_hotword_support.py` 持续失败（上游遗留）**：断言 `server/README.md`（英文）包含「本地 Qwen3-ASR」「本地 SenseVoice」「后处理纠错」等中文文案。该测试与上游当前英文 README 不一致，属上游仓库自身问题，非本轮改动引入；未改动 README 以避免为迁就测试而篡改文档。
2. **`npm audit` 遗留 2 个 moderate（react-router 6.x）**：open-redirect 与 SSR 反序列化问题；升级 v7 为破坏性变更，且桌面应用无外部导航场景，风险低，未升级（同上一轮说明）。
3. **未做 GUI 运行测试**：改动均通过单元/类型/编译级验证；安装程序未重建，未做安装后人工回归（等待 PM 复审通过后再生成）。
4. **服务端重型依赖未安装**：`requirements.txt` 中 `torch/qwen-asr/funasr` 属 GPU 推理栈，本机测试 venv 只装了轻量依赖（fastapi/uvicorn/httpx/numpy/PyYAML/pytest）用于跑不依赖 ASR 引擎的测试；完整服务端运行需按 `docker compose` 或 `pip install -r requirements.txt` 部署。
5. **候选安装包未覆盖**：`SayIt_0.1.8_x64-setup-local.exe`（17:31:48, 8.32 MB）仍为上轮产物，本轮代码改动未打包进任何安装程序。

## 四、改动统计

43 个文件，+1397 / -400 行（含测试）。核心改动文件：
- 客户端：`serverAuth.ts`、`websocket.ts`、`autoUpdate.ts`、`updateChecker.ts`、`sanitize.ts`、`defaults.ts`、`ServerSection.tsx`、`ConfigTransferDialogs.tsx`、`backup.ts`、`src-tauri/src/storage/mod.rs`、`src-tauri/src/commands/system.rs`、`src-tauri/src/commands/backup.rs`
- 服务端：`net.py`、`config.py`、`main.py`、`ratelimit.py`、`asr_corrections.py`、`diagnostics.py`、`entrypoint.sh`、`config.example.yaml`
- 测试：`serverAuth.test.ts`、`updateCheckerAuth.test.ts`、`websocketPrivacy.test.ts`、`serverPrivacyDefaults.test.ts`、`sanitize.test.ts`、`test_net_trusted_proxies.py`、storage/backup Rust 测试