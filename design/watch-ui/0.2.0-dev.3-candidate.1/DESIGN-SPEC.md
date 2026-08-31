# 0.2.0-dev.3 presentation specification

This candidate changes only the Watch presentation layer. The existing
`WatchUiStateMachine` and `RecordingViewModel` continue to own all transitions:
Ready → Recording → automatic upload → uploaded or retryable failure; a
retained WAV still requires explicit discard before a replacement recording.

| Runtime state | Visible presentation | Guardrail |
| --- | --- | --- |
| Ready | SayIt, transport availability, refresh/settings, blue 88–96 dp microphone, “点击开始录音” | No `●`, `Ready 16k`, samples, or raw milliseconds. |
| Recording | Red dot + “录音中”, sample-derived `mm:ss`, red “结束录音” pill, separate `× 取消并丢弃` | The composable subscribes to `sampleCount` and formats it with `WavWriter.durationMs`; no `■`, Pause, VAD, waveform, or wall-clock timer. |
| Config | Scrollable dark field cards, masked token, blue Save & Apply | Token stays masked until the deliberate reveal action. |
| Uploading | Opaque dark panel, upload icon, “正在传输” | Does not claim ASR, transcription, or Paste. |
| Upload failed | Opaque dark panel, error icon, fixed generic failure copy, retained-recording wording, Retry/Later | Retry keeps the same WAV; technical failure detail stays out of the round-screen layout. |
| Uploaded | Opaque dark panel, success icon, “已传到电脑” | Transport-only confirmation. |
| Pending | Amber retained-recording card with Retry; blue microphone remains reachable | Mic still opens the existing explicit discard decision. |
| Discard | At <230 dp logical height: compact panel with same-row Keep / Discard-and-record actions; otherwise vertical actions | Both actions remain on-screen and reachable; cannot silently overwrite the retained WAV. |

## Responsive implementation evidence

- Ready and Recording use `BoxWithConstraints`; their microphone/timer scale
  from logical dp height, never an assumed 480 dp screen.
- Discard uses the same constraint pattern: compact Watch height swaps the two
  actions into one 52 dp row rather than relying on scroll for access.
- Control widths use `fillMaxWidth` / `weight`; fixed dp values are restricted
  to touch targets, padding and icons.
- Ready and Recording have no scroll container so the primary flow is present
  on first view. Configuration alone is vertically scrollable.
- All status icons are Compose `Canvas` drawings; no bitmap asset or added
  dependency is used.

## Runtime source under review

- `watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt`
- `watch/app/src/main/res/values/strings.xml`
- `watch/app/build.gradle.kts`
- `watch/app/src/test/java/com/sayit/watch/ui/WatchUiMetricsTest.kt`
