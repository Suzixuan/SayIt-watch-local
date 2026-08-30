# SayIt Watch Transport handoff

## Goal

Verify the smallest real transport path first:

`Galaxy Watch 7 -> 16 kHz PCM/WAV -> debug HTTP over Wi-Fi -> Windows SayIt -> received_watch.wav`

Only after PM manually accepts the received audio may the project unlock Delivery 1B and reuse the existing SayIt Provider/History/Paste pipeline.

## Source authority

- Baseline archive: `SayIt-local-src-2026-08-29.zip`
- Baseline SHA-256: `E492F71CF073E2012E0CA61AFD865F9A773B4E321A8C9853D29C4FCD62E889B3`
- Inherited project handoff: `HANDOVER.md`
- Upstream public repository: `crosswk/SayIt` at inherited commit `bad38da`
- Private working repository: `https://github.com/Suzixuan/SayIt-watch-local`
- Baseline import commit: `18400ad82d7f5a7009a47622248643022472650f`
- Large installer and build caches were deliberately excluded from Git history.

## Confirmed decisions

- Delivery 1A and 1B are strictly sequential.
- Delivery 1A uses a manually supplied random development token; formal six-digit pairing is deferred.
- Cleartext HTTP is permitted only in Android debug builds. Release builds must deny it, and the Windows HTTP receiver must not start in release builds.
- The Windows receiver binds exactly one configured RFC1918 LAN IPv4, never `0.0.0.0`.
- Galaxy Watch 7 must prove native 16,000 Hz initialization. Delivery 1A must fail visibly rather than resample or fall back to 44.1/48 kHz.
- A successful upload means only durable transport. The receiver returns `201 Created`; it must not claim transcription success.

## Current state

- The user reports the existing AudioRelay -> SayIt -> ASR -> text-output chain as VERIFIED. It is out of scope for re-test or refactor.
- The isolated source baseline and PM documents are prepared and pushed to the private `main` branch.
- Delivery 1A implementation is complete on `codex/review-watch-transport` (source, tests, builds). See `PROJECT_PROGRESS.md` and the delivery branch for full evidence.
- Real-device transport evidence (Watch 7 recording -> receiver -> received_watch.wav) is pending device pairing and PM acceptance; it is separate from source/build results.
- Delivery 1B remains locked.

## Delivery 1A source/build evidence (Colleague D, 2026-08-29)

Toolchain used for the delivery-1A verification runs:

- JDK: Eclipse Temurin 17.0.20.1 (x64)
- Gradle: 8.10.2 (watch project wrapper)
- Android SDK: platform-tools 37.0.1, platforms;android-34, build-tools;34.0.0 (command-line tools installed outside the repository; no local paths committed)
- Kotlin 2.0.21, AGP 8.7.3, Wear Compose 1.4.1, compileSdk 34 / targetSdk 34 / minSdk 30
- Rust: repo toolchain pinned to stable x86_64-pc-windows-msvc (rustc 1.98.0 via rustup); Cargo.lock updated for `tiny_http = "0.12"`
- Node.js v24.16.0 (npm test / build unchanged)

Fresh verification runs (exit codes):

1. `cd watch; .\gradlew.bat testDebugUnitTest lintDebug assembleDebug` — BUILD SUCCESSFUL (exit 0). 35 unit tests, 0 failures. Lint clean. Debug APK produced.
2. `cd client; npm test` — 29 files / 339 tests passed (exit 0).
3. `cd client; npm run build` — tsc + vite build OK (exit 0).
4. `cd src-tauri; cargo test` — 142 tests, 138 passed, 0 failed, 4 ignored (exit 0). Includes watch_receiver module tests (config, WAV parsing, HTTP server, atomic save).
5. Release checks: `cargo build --release` succeeds; the release binary contains none of the receiver markers (`watch-receiver`, `SAYIT_WATCH_BIND_IP`, `received_watch.wav`, `tiny_http`, `api/watch/audio`). Watch release APK manifest has no `usesCleartextTraffic`; debug APK manifest has it.

Debug APK: `watch/app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `8596C5E2E976508894561CBB686B32BA0463E212C21B9A9A0063389441D69515` (not committed to Git).

Receiver contract implemented in `client/src-tauri/src/watch_receiver/` (debug-only, `#[cfg(debug_assertions)]` gated in `main.rs`): env config fail-closed, `GET /api/health`, `POST /api/watch/audio` with exact Bearer auth, audio/wav content type, 10 MiB cap, RIFF validation, atomic `MoveFileExW` write-through replacement under `%LOCALAPPDATA%\com.sayit.app\watch-receiver\received_watch.wav`.

Watch app implemented under `watch/` (Kotlin + Compose for Wear OS): 16 kHz native verification, dedicated I/O recording, canonical WAV writer, RFC1918-only destination validation, cleartext debug-only, `201` = transport success only.

## Delivery 1A real-device evidence (Colleague D, 2026-08-29) — D evidence pending PM acceptance

Device: Galaxy Watch 7 (SM-L310, Wear OS Android 16 / API 36, 480x480 round), connected via Wi-Fi ADB wireless debugging (no serial recorded; no tokens/APKs committed).

Watch app debug APK installed on device with RECORD_AUDIO granted; destination configured to the PC's LAN RFC1918 IPv4 `192.168.12.142:18099` with a 48-hex-char dev token (supplied via environment, not committed).

Receiver run: debug SayIt started with `SAYIT_WATCH_BIND_IP=192.168.12.142`, `SAYIT_WATCH_PORT=18099`, `SAYIT_WATCH_DEV_TOKEN=<48-char token>`; log line: `watch receiver listening on 192.168.12.142:18099 (dev token present: true)`. Windows firewall rule for TCP 18099 on the Private profile was created.

Observed on the watch (UI state machine): `Ready — 16 kHz verified` (AudioRecord min buffer > 0, STATE_INITIALIZED, actual sampleRate 16000) -> `Recording` -> `Recorded (N samples)` -> `Uploaded / transport verified`. `201 Created` mapped to transport success only; UI showed "Uploaded / transport verified", never "Transcribed".

Two real recordings were transported and durably saved:

| Run | Captured samples | WAV bytes | Duration | Received SHA-256 | Note |
|---|---:|---:|---:|---:|---|
| 1 | 223,360 | 44,764 header+PCM = 446,764 | 13.96 s | `700f4daa17357506167a35d6e84f68d473c21f56c0b7dd576ed202636665f6c9` | Quiet environment audio |
| 2 | 238,080 | 476,204 | 14.88 s | `39a80c7cfa210cbfe4bcac1aca8117e1096e5d3b3f6b9ca8195cd7f4b2b57077` | Speech (0 dBFS peaks; per-second RMS -56.8..-66.3 dBFS) |

Both files validated as PCM format 1, 1 channel, 16,000 Hz, 16-bit, block align 2, byte rate 32,000, even non-empty data, no truncation. `%LOCALAPPDATA%\com.sayit.app\watch-receiver\received_watch.wav` is the durable accepted sample; no temp files remain after each upload. Desktop copies for PM playback: `received_watch_D-evidence.wav` (run 1) and `received_watch_D-evidence-voice.wav` (run 2).

Manual playback observation (D evidence, pending PM acceptance): run 1 is quiet room/ambient audio (peak -11.6 dBFS, RMS ≈ -59 dBFS, no speech); run 2 contains speech with full-scale peaks (0 dBFS) and strong per-second energy variation, consistent with the wearer speaking. PM must perform the final listen: no truncation, no abnormal gaps, correct speed/pitch, correct duration, usable speech.

Upload HTTP response (from run-1-style PC self-test and run 2 device upload): `201 Created` with `requestId`, `bytes`, `sampleCount`, `audioDurationMs`, and lowercase hex `sha256` matching the saved file.

## Next executable step

The user sends `docs/DELIVERY-1A-D-TASK.md` to colleague D. D creates `codex/review-watch-transport`, implements only Delivery 1A, pushes the branch, and returns the required evidence package. PM then reviews the actual diff and build evidence before any real-device acceptance.

## Delivery 1A acceptance

- Debug Watch APK builds successfully and release configuration rejects cleartext transport.
- On a real Galaxy Watch 7, `AudioRecord` initializes at exactly 16 kHz, PCM 16-bit, mono.
- Authenticated upload reaches the explicitly configured Windows LAN IPv4.
- The receiver validates the WAV and atomically saves `%LOCALAPPDATA%\com.sayit.app\watch-receiver\received_watch.wav`.
- Response is `201 Created` with request ID, bytes, sample count, duration, and SHA-256.
- PM manually plays the file and records: no truncation, no abnormal gaps, correct speed/pitch, correct duration, and usable speech.

## Locked follow-up

Delivery 1B may modify the external-audio ingress only after the Delivery 1A acceptance gate is recorded here. It must reuse `RecorderOrchestrator`, the active `TranscriptionProvider`, existing callbacks, History, and Paste; it must not create a second ASR path.

## Security

- Development tokens are generated outside the repository and supplied through environment configuration.
- Tokens, received audio, local SDK paths, signing material, and device identifiers must not enter Git, logs, screenshots, or handoff documents.
