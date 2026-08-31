# 0.2.0-dev.3-candidate.2 minimal Watch UI specification

This candidate supersedes candidate.1's visible upload feedback. It exposes
only Config, Ready and Recording and does not add a queue or a new transport
protocol.

| Runtime event/state | Visible Watch UI | Internal rule |
| --- | --- | --- |
| Config | Scrollable dark field cards, masked token and Save & Apply | Unchanged connection setup. |
| Ready | SayIt, transport availability, refresh/settings, blue microphone | No pending, retry or retained-recording affordance. |
| Recording | Red indicator, sample-derived `mm:ss`, stop and cancel/discard | Start and stop retain simple haptics. |
| Stop → HTTP upload | Immediately Ready; no upload indicator or result copy | `isUploading` blocks Record while the same session is in flight. |
| HTTP success or failure | No UI transition, error or haptic | Reset WAV/session, clear the internal latch and restore recording. |

## Explicit exclusions

- No Uploading, Uploaded, Failed, Retry, Later, Pending or Discard UI/state.
- No retained WAV or retry after an HTTP result.
- No ASR, transcription, Paste, queue or transport-success claim on the Watch.
- No success/failure vibration.

## Concurrency and source evidence

- `WatchUiStateMachine.uploadStarted()` changes only the visible screen to
  Ready and sets internal `isUploading`; `canStartRecording()` is false until
  `uploadFinished()`.
- `RecordingViewModel.startRecording()` checks that latch before calling
  `prepare()`, preventing a new capture from mutating `RecordingSession` while
  an HTTP coroutine owns it.
- `resetSessionAfterSilentUpload()` clears the WAV for either result before
  `uploadFinished()` restores recordability.
- Runtime paths under review:
  - `watch/app/src/main/java/com/sayit/watch/ui/RecordingScreen.kt`
  - `watch/app/src/main/java/com/sayit/watch/ui/RecordingViewModel.kt`
  - `watch/app/src/main/res/values/strings.xml`
  - `watch/app/src/test/java/com/sayit/watch/ui/WatchUiStateMachineTest.kt`
  - `watch/app/src/test/java/com/sayit/watch/ui/WatchUiMetricsTest.kt`
