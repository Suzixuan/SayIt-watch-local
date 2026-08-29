# SayIt 本地自用版 — 交接文档

> 交接日期：2026-08-29
> 上游项目：[crosswk/SayIt](https://github.com/crosswk/SayIt)（AGPL-3.0），基于克隆时刻的 `main` 分支（70 commits，版本 0.1.8）

---

## 一、这是什么

SayIt 是一款 Windows 语音听写工具（Wispr Flow / Superwhisper 替代品）：按住快捷键说话，语音转文字并经 AI 润色后插入到光标处。

本仓库是在上游源码基础上完成**三轮安全审查与修复**后的「本地自用版」：
- 全部 AI 功能保留：本地 GGUF ASR、云端 ASR（豆包/千问/小米 MiMo/Groq）、云端 LLM 整理（DeepSeek/Qwen/Ollama 等）、服务器模式、上下文写作、热词、历史、诊断等。
- 安全策略：通过**鉴权、加密存储、安全默认配置**修复问题，不通过删功能解决。
- 默认形态：本地模式（设备端推理，数据不出本机），不依赖任何外部服务器。

## 二、交付物清单（本包内容）

| 路径 | 说明 |
|---|---|
| `SayIt_0.1.8_x64-setup-local.exe` | 最终安装包（NSIS x64，8.3 MB，含 CPU 版语音引擎与 VC++ 运行库） |
| `client/` | 桌面客户端源码（Tauri 2 + React + Rust） |
| `server/` | 服务端源码（FastAPI + gateway），本地服务器模式可选部署 |
| `docs/` | 上游用户文档 |
| `SECURITY-REVIEW-FIXES.md` | 第一轮安全审查报告与修复说明 |
| `PM-REVIEW-FIXES.md` | PM 复审（第二轮）8 项修复说明 |
| `PM-ROUND3-DIFF.patch` | **对上游的完整精确 diff**（46 文件，+1905/−411，320 KB）——查改动只看这一个文件即可 |
| `HANDOVER.md` | 本文档 |

> 注：源码目录内的 `.git` 历史未打包（19 MB）；如需核对上游基线，重新 `git clone https://github.com/crosswk/SayIt` 后应用 `PM-ROUND3-DIFF.patch` 即可完全重现。

## 三、源码结构

```
SayIt/
├── client/                     # Tauri + React 桌面客户端
│   ├── src/                    #   React 前端（服务、页面、设置、i18n）
│   ├── src-tauri/
│   │   ├── src/
│   │   │   ├── storage/mod.rs  #   SQLite 存储 + DPAPI 密钥加密（fail-closed）
│   │   │   ├── commands/       #   Tauri 命令（备份/导出/系统/诊断等）
│   │   │   ├── providers/      #   云 ASR / AI 供应商实现（reqwest）
│   │   │   ├── models/         #   本地 GGUF ASR 引擎封装
│   │   │   └── main.rs         #   入口（WebView2 参数、托盘、快捷键）
│   │   ├── tauri.conf.json     #   Tauri 配置（含 CSP、NSIS 打包）
│   │   ├── Cargo.toml          #   Rust 依赖（transcribe-cpp CPU-only）
│   │   └── rust-toolchain.toml #   固定 stable-x86_64-pc-windows-msvc
│   ├── package.json / vite.config.ts / tsconfig.json
│   └── local `.env.development` (not included in the private handoff repository)
├── server/
│   ├── backend/app/            # FastAPI：WS 转写、反馈、诊断、遥测、令牌鉴权、net.py
│   ├── gateway/proxy.mjs       # HTTPS 网关（已修路径穿越与日志遮蔽）
│   ├── web/                    # 网页体验版静态页（默认关闭）
│   └── config.example.yaml / docker-compose.yml / README.md
└── docs/
```

## 四、三轮修复汇总（详细说明见三个 Markdown 文件）

**第一轮（初始安全审查）**
1. 移除 WebView2 全局 `--ignore-certificate-errors`，浏览器参数不再接受环境变量注入（`client/src-tauri/src/main.rs`）
2. 默认后端地址改 `http://127.0.0.1:8000`，更新通道不再外联 `sayitapp.site`（`vite.config.ts`、`runtimeConfig.ts`）
3. 新增 CSP（`tauri.conf.json`）
4. 服务端 gateway 路径穿越修复（`server/gateway/proxy.mjs`）
5. 服务端安全默认：web demo 关闭、仅回环监听、移除默认 admin 口令（`config.py`）
6. API 密钥 DPAPI 加密落盘（`storage/mod.rs`）
7. 默认工作模式改为 `local`（完全离线）

**第二轮（PM 复审 8 项）**
1. 服务器访问令牌**仅同源发送**：`serverFetch` 按 origin 判定；官方更新回退用无鉴权 `fetch`，绝不携带令牌（`serverAuth.ts`、`updateChecker.ts` + 跨域回退测试）
2. DPAPI 覆盖 `cloudAi.profiles`/`cloudAsr.profiles` 等凭据容器；启动迁移明文；禁止静默降级（加密/解密失败均不落明文、不返回密文）
3. 日志/诊断完全遮蔽 token：WS URL 日志用 `maskWsUrl`（token=***）、sanitize 增加 token 脱敏、uvicorn 加 `--no-access-log`、gateway 错误日志遮蔽 URL
4. XFF/X-Real-IP 仅在直连地址命中**显式受信代理名单**（`SAYIT_TRUSTED_PROXIES`/`server.trusted_proxies`）时采信；私网地址不再自动视为受信代理（`net.py`）
5. 更新彻底关闭：前端 `UPDATE_ENABLED=false`；Rust `download_update`/`install_downloaded_update` 返回 Err、`verify_update_package` 恒 false、退出静默安装空实现
6. 全量配置导出默认排除全部密钥；勾选「包含密钥」须二次确认（`backup.rs` + 导出弹窗）
7. Server 模式元数据（用户名/主机名/IP/进程/exe 路径）默认关闭，设置页提供授权开关（`serverShareMetadata`）
8. 全套针对性测试（客户端 vitest / 服务端 pytest / Rust cargo test）

**第三轮（PM 复审 2 项）**
1. **DPAPI 真正 fail-closed**（`storage/mod.rs`）：迁移或懒迁移时加密失败**或写库失败** → 删除该明文行并返回 fallback/Null，绝不把明文作为可用值返回；非敏感配置不受影响。测试通过强制失败钩子（`FORCE_DPAPI_FAIL`）与 SQLite 触发器确定性复现失败路径。
2. **全部数据 ZIP 导出旁路修复**（`backup.rs` + UI）：`export_full` 增加 `includeSecrets` 参数且 Rust 层默认 `false`；导出确认框默认不勾「包含密钥」，勾选后强警告 + 二次确认才允许导出；历史与录音完整保留。

## 五、构建方法（如需重新打包）

### 环境要求
- Windows 10/11 x64
- Node.js 18+（本项目在 v24 验证）
- Rust：`stable-x86_64-pc-windows-msvc`（仓库 `client/src-tauri/rust-toolchain.toml` 会自动选择，rustup 会按需安装）
- Visual Studio 2022 Build Tools（MSVC C++ 生成工具，含 VC redist）
- CMake 3.20+（加入 PATH；本地语音引擎 transcribe-cpp 首次构建需用 CMake 编译 C++ 树，约 20 分钟）
- 无需 Vulkan SDK（本版为 CPU-only；需要 GPU 时给 `transcribe-cpp` 加回 `vulkan` feature 并安装 SDK）

### 构建步骤
```powershell
# 1. 前端依赖
cd client
npm install

# 2. 打安装包（会依次执行 tsc + vite build + cargo build --release + NSIS）
npx tauri build
# 产物：client/src-tauri/target/release/bundle/nsis/SayIt_0.1.8_x64-setup.exe
```
- 注意：非英文 Windows 需设 `$env:CL = "/utf-8"`（仓库 `.cargo/config.toml` 已内置）。
- 若 CMake 不在 PATH：`$env:PATH = "C:\Program Files\CMake\bin;$env:PATH"`。

### 测试
```powershell
cd client
npm test                        # vitest：29 文件 / 339 测试
npm run build                   # tsc + vite build

cd ../server
python -m venv .venv
.\.venv\Scripts\pip install fastapi==0.135.2 python-multipart==0.0.22 uvicorn==0.42.0 httpx==0.28.1 python-dotenv==1.2.2 PyYAML==6.0.3 numpy==2.2.6 pytest
.\.venv\Scripts\python -m pytest backend/tests -q
# 期望：58 通过 / 1 失败（上游遗留的 test_docs_hotword_support.py 中文 README 断言）

cd ../client/src-tauri
cargo test --release             # 期望：117 个测试，113 通过 / 0 失败 / 4 忽略
```

## 六、安装与使用

1. 双击 `SayIt_0.1.8_x64-setup-local.exe` 安装（currentUser 免管理员）。
2. **本地模式（默认，完全离线）**：首次使用进入「语音引擎」页下载模型（默认 SenseVoice Small，约几百 MB；国内网络可切换「HF Mirror」源；也支持手动下载放入模型目录）。
3. **云 API 模式**：设置页填入自己的豆包/千问/DeepSeek 等密钥（密钥本机 DPAPI 加密存储）；AI 整理在「AI 服务」页配置。
4. **服务器模式（可选）**：默认指向 `http://127.0.0.1:8000`。自建后端：
   ```bash
   cd server && python -m venv .venv && .venv/Scripts/pip install -r backend/requirements.txt
   cd backend && uvicorn app.main:app --host 127.0.0.1 --port 8000 --no-access-log
   ```
   （requirements 含 torch/qwen-asr/funasr 等重型 GPU 依赖，仅服务器模式需要。）若设置了 `SAYIT_API_TOKEN`，在客户端「服务器」设置里填入相同令牌。
5. 自动更新已彻底关闭；快捷键默认：按住右 Ctrl 说话，松开结束；右 Alt 免提模式（可在设置修改）。

## 七、已知遗留问题（接受范围）

1. 服务端 `test_docs_hotword_support.py` 上游遗留失败：断言 `server/README.md` 含中文文案，与上游英文 README 不符，与本次改动无关。
2. `npm audit` 2 个 moderate（react-router 6.x 的 open-redirect / SSR 反序列化）：升级 v7 为破坏性变更，桌面应用无外部导航场景，风险低，未升级。
3. 本地 ASR 为 CPU-only 构建（未启用 Vulkan GPU 加速）。
4. 未做 GUI 人工回归（安装包做过静默安装文件布局验证；功能层由 339 前端测试 + 117 Rust 测试覆盖）。

## 八、与上游的关系

- License：AGPL-3.0（保留上游 LICENSE）。
- 本仓库不推送、不改上游；所有改动以 `PM-ROUND3-DIFF.patch` 形式可完整复现/审计。
- 如需更新上游：重新克隆上游 → `git apply PM-ROUND3-DIFF.patch`（若有冲突按上文第四节修复说明逐条核对）。
