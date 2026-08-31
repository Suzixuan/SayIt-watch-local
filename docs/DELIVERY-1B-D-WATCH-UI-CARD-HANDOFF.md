# Delivery 1B — D 接力：Watch 圆形卡片 UI 适配（表盘 MIC READY / RECORDING）

## 当前现场

- 分支：`codex/review-watch-pipeline`
- 当前提交基线：`412f1da`（PC Bridge 修复，已推送）
- 工作树**有未提交改动**（见下），另有若干临时截图文件（`watch/card*.png`，gitignore 未覆盖，属临时产物）。
- 本次只动 Watch **UI 演示层** + 字符串；**未改** TransportClient、Receiver、PC、ASR、Provider、History、Paste，也未改录音/传输/状态机核心逻辑。
- 需求来源：用户提供两张圆表盘设计图（见下），希望把已有 Ready / Recording 全屏界面适配成**圆形表盘（卡片）**样式，并明确「字小一点、计时数字小一点、图标适当小一点」。

## 目标（用户给的两张设计图）

1. **MIC READY（待命）**：圆形表盘 = 白色圆面 + 外圈一圈刻度（12/3/6/9 四个主刻度蓝色且更长更粗，其余为短黑刻度）+ 顶部左右两个图标（Wi-Fi 信号 + 电池）+ 中央黑色麦克风图标 + 小号标题「MIC READY」。
2. **RECORDING（录音中）**：同样的圆形表盘 + 顶部图标，中央换成蓝色动态波形 + 小号标题「RECORDING」+ 下方计时数字。

配色/字号要求：标题、计时数字、中央图标都比上一版更小、更克制，整体白色圆面配深色内容 + 蓝色强调。

## 已完成改动（工作树未提交）

| 文件 | 改动 |
|---|---|
| `watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt` | 新增表盘呈现层；重写 Ready 和 Recording 两屏；新增 Wi-Fi/电池图标、外圈刻度绘制、动态波形 |
| `watch/app/src/main/res/values/strings.xml` | 新增 `dial_title_ready`（MIC READY）、`dial_title_recording`（RECORDING） |

### RecordingScreen.kt 实现要点

- 新增常量：`DialFace`(`0xFFF6F7F9` 白)、`DialTick`(`0xFF1C1E22` 黑)、`DialIcon`(深)、`DialMuted`(灰)。
- `WatchDial(available, content)`：一个可复用表盘外壳——白色圆面 + 外圈刻度 + 顶部 Wi-Fi/电池状态行，内容由调用方填充。
  - 外圈刻度：24 个（每 15°），`i % 6 == 0`（对应 3/6/9/12 点）为主刻度 → 蓝色、更长更粗；其余为黑色短刻度。
  - 顶部状态行：`BoxWithConstraints`，`padding(top = minOf(maxW,maxH)*0.08f)`，`Arrangement.SpaceEvenly`，两个 22dp 图标（Wi-Fi 左、电池右）。Wi-Fi 颜色按 `available`（true 蓝 / false 红 / null 深），电池蓝。
- `RecordingWaveform`：`rememberInfiniteTransition` 驱动 9 根竖条动态高度（中间高、两边低的包络 + 正弦相位抖动），蓝色。
- `ReadyScreen`：白色表盘 + 小号「MIC READY」标题 + 中央黑色麦克风图标（52dp 触摸区，点按 `recordButtonPressed()`）+ 底部一行极小「设置」入口（`openConfig()`）。
- `RecordingActiveScreen`：白色表盘 + 小号「RECORDING」标题 + 蓝色波形 + 计时（26sp）+ 底部极小「取消并丢弃」入口。整个中央区域 `clickable { stopRecording() }`，底部取消入口 `clickable { cancelRecording() }`。
- 新增 `IconType.WIFI` / `IconType.BATTERY` 及 `drawIcon` 分支。

## 验证情况

- **编译**：`gradlew.bat :app:compileDebugKotlin` → `BUILD SUCCESSFUL`；`:app:assembleDebug` → `BUILD SUCCESSFUL`。
- **部署**：`adb install -r app-debug.apk` → `Success`；`am start -n com.sayit.watch.debug/com.sayit.watch.MainActivity` 正常启动（`com.sayit.watch.debug` 进程运行；无 SayIt 相关崩溃，唯一的 `AndroidRuntime` 报错来自无关的 `com.suzix.tokenmonitor`）。
- **Ready 屏**（`watch/card2.png`，像素分析）：中心 100×100 @(240,240) 出现黑色图标像素（`28,30,34`），其余为白色（`246,247,249`）= DialFace；外圈有蓝色像素分布 → 白色圆面 + 中央麦克风 + 蓝色主刻度，符合设计。
- **Recording 屏**：点击 (240,240) 后（`watch/card3.png`），中心 100×100 区蓝色像素明显增多（blue≈1247）→ 蓝色波形出现，点击开始有效。
- 表盘整体尺寸 480×480（Galaxy Watch 7，SM-L310）。

> 说明：本次验证依赖像素统计（助手环境无法直接查看截图），能确认「白色圆面 + 蓝色存在 + 中央有图标」，**无法**逐像素判断图标朝向 / 布局是否与设计图完全一致。

## 已知问题 — 需接手者处理（重要）

1. **用户反馈「图标都是反的」**：这是本次交接的核心遗留。设计图是用户提供的（两张 PNG），我无法直接查看图像内容，只能按常见构图实现，**朝向可能有误**。请拿着设计图逐项核对：
   - 微波（麦克风的头/柄朝上还是朝下）；
   - 电池（电池外壳的凸起端子在哪一侧、电量格方向）；
   - Wi-Fi（三层弧的张开方向、扇形朝向、底部圆点位置）；
   - 外圈主刻度（12/3/6/9 方位是否与设计图一致）；
   - 波形条（是否应为对称包络、条数、方向）。
   建议直接对照设计图源文件修正对应 `drawIcon` / 刻度 / 波形参数。
2. **手动点按停止录音未验证生效**：进入 Recording 后再次 `input tap 240 240`，截图仍显示波形（blue≈1240），**没有回到 Ready**。可能原因：`Column fillMaxSize().clickable` 的事件传播 / 命中，或点按坐标落在波形/文字子元素上，或 `stopRecording()` 未被触发。需要排查。（注：15 秒自动停止路径因任务中止未测到。）
3. **White-dial 与旧深色主题的取舍**：旧版同屏为黑底主题，现改为白色圆面。确认用户接受。Config 屏仍沿用旧布局，未做表盘化。
4. **回归**：UI 改动不影响手表录音→上传→PC 链路，但请在交付前用本机私密配置完成一次真实录音上传回归。不得把 Receiver 地址、Token 或设备端口写进仓库。
5. **临时截图**：`watch/card.png`、`card2.png`、`card3.png`、`card4.png`、`card5.png`（card5 因任务中止未生成/未测）为验证临时产物，建议清理或移入设计参考目录，不要提交。

## 构建 / 部署 / 验证命令

```powershell
# 编译（watch 目录）
.\gradlew.bat :app:compileDebugKotlin --console=plain
.\gradlew.bat :app:assembleDebug --console=plain

# 部署（从手表无线调试主页面取得本次临时 Connect endpoint）
$adb = "<ADB_PATH>"
$watchEndpoint = "<WATCH_ADB_ENDPOINT>"
& $adb -s $watchEndpoint install -r app\build\outputs\apk\debug\app-debug.apk
& $adb -s $watchEndpoint shell "am start -n com.sayit.watch.debug/com.sayit.watch.MainActivity"

# 截图 + 拉取
& $adb -s $watchEndpoint shell "input keyevent 224"   # 唤醒
& $adb -s $watchEndpoint shell "screencap -p /sdcard/c.png"
& $adb -s $watchEndpoint pull /sdcard/c.png .\c.png
```

## 允许修改范围（延续既有边界）

- `watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt`
- `watch/app/src/main/res/values/strings.xml`
- 对应的 Watch UI / 状态机测试
- `watch/app/build.gradle.kts`（保持 versionCode 3 / 0.2.0-dev.3）

不要扩大改动到 TransportClient、Receiver、PC、ASR、Provider、History、Paste；不 merge/tag/release。

## 建议下一步（接手者）

1. 拿到两张设计图原图，对照逐一修正图标朝向（首当其冲是用户点名的「反了」）。
2. 修好 Recording 停止交互（点按停止要能回 Ready；确认自动停止 15s 行为）。
3. 回归真实手表录音 → 上传 → PC 插入文本链路。
4. 处理临时截图文件，确认提交范围后提交到 `codex/review-watch-pipeline`。
