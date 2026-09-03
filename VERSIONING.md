# SayIt 构建产物命名约定

为避免多个形态的构建互相混淆（开发版 / 自包含版 / 发布版），所有可分发产物统一命名：

```
SayIt-<版本>-<形态>-<日期>.<扩展名>
```

- **版本**：来自 `client/package.json` 与 `client/src-tauri/tauri.conf.json` 的 `version`（当前 `0.1.8`）。功能变更上升一版（`0.1.9` …）。
- **形态**：
  - `dev` — 开发版（前端走 vite / devUrl，调试用，**需启动 vite**）。
  - `standalone` — 自包含稳定版（前端打包进 exe，经 `tauri build --debug --no-bundle` 产出，**不依赖 vite**，debug 构建、**含 Watch Receiver**）。
  - `release` — 正式发布版（不含 Watch Receiver，安全设计）。
- **日期**：构建日期 `YYYYMMDD`。

## 当前产物清单

| 版本/形态 | 路径 / 说明 |
|---|---|
| `SayIt-0.1.8-standalone-20260902.zip` | 桌面 & Downloads；GitHub Release `v0.1.8-watchportable` 附件（即此包） |
| 桌面 `SayIt\`（自包含） | `sayit.exe` 自包含稳定版；`SayIt.lnk` 快捷方式指向它 |
| `0.1.8-dev` | `client\src-tauri\target\debug\sayit.exe`（**依赖 vite**，勿日常使用） |

## 构建自包含版（FTK1011 前提）

```powershell
# 关键：单线程，绕开 MSBuild FileTracker 的 FTK1011（并行 `--parallel 32` 触发）
$env:CARGO_TARGET_DIR="$env:LOCALAPPDATA\SayItBuild\ftk-serial"
$env:CARGO_BUILD_JOBS="1"
npm.cmd run tauri -- build --debug --no-bundle
# 产物：%LOCALAPPDATA%\SayItBuild\ftk-serial\debug\sayit.exe （前端自包含）
```

- 复制 `sayit.exe` + 同目录 `*.dll` 即为一套可运行的便携目录。
- 压缩时**不含** `sayit.pdb`（调试符号，体积大）；用 `Compress-Archive` 打包 `sayit.exe` + 全部 `*.dll`。
