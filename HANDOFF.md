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
- Accepted Delivery 1A branch: `codex/review-watch-transport`
- Accepted PM commit: `fe096d7c41c335155db8c8300a89f64b47f75fe1`
- Delivery 1B review branch for colleague Z: `codex/review-watch-pipeline`
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
- Delivery 1A was repaired, independently verified, and PM-accepted on `codex/review-watch-transport`.
- The user confirmed the real received WAV by human playback.
- Delivery 1B Z1 Provider contract gate has been executed by colleague Z on `codex/review-watch-pipeline` (base `5cc14b4`). The contract document is submitted and awaits PM review; no implementation is authorized yet.

## Colleague Z quick start

- Repository: `https://github.com/Suzixuan/SayIt-watch-local` (private; obtain access from the user, never exchange credentials in project files or chat evidence).
- Read first: `AGENTS.md`, `HANDOFF.md`, `PROJECT_PROGRESS.md`, `HANDOVER.md`, then `docs/DELIVERY-1B-Z-CONTRACT-TASK.md`.
- Working branch: `codex/review-watch-pipeline`, based on accepted Delivery 1A.
- Z1 output is documentation only. Do not modify product code until PM accepts the Provider contract.
- Z must push the requested evidence and stop; no merge, tag, release, installer, or public-upstream push.

## Delivery 1B Z1 contract evidence (Colleague Z, 2026-08-29)

- Deliverable: `docs/DELIVERY-1B-PROVIDER-CONTRACT.md` (documentation only; base commit `5cc14b4c8658920cc9ca9b21ab1dcaf878f1a136` on `codex/review-watch-pipeline`). No product code, tests, dependencies, or build configuration changed.
- Conclusion: **MATCH**. All six proposed audio-contract clauses are confirmed from source evidence on both sides — capture (`client/src/services/audio.ts`: 16 kHz target, mono, Int16 conversion, `bytes/2/16000` duration) and receiver validation (`client/src-tauri/src/watch_receiver/wav.rs`: PCM format 1, mono, 16,000 Hz, 16-bit, block-align/byte-rate checks).
- Contract freeze: `TranscriptionProvider` real call order is `connect → isReady → start → sendAudio* → stop → (onASR? → onFinal → onDone?) | onError` with runId generation guards (`RecorderOrchestrator.ts:126-135, 258-275, 900-1154`); a normal final reaches History first (`addHistory` in `processFinalResult`) then Paste with the probe snapshot captured exactly once at run start; `aiEnabled` is the AI-cleanup setting and an external run can freeze `disableAi: true` per-run via existing `StartOptions`/`StopOptions` fields without deleting AI features.
- `409` admission boundary design: a Rust `AdmissionGate` (`Idle`/`Reserved{requestId}`) shared between the blocking receiver thread and three new debug-only Tauri commands; the WebView performs a fully synchronous check-and-reserve (`tryReserveExternalRun`) inside one JS macrotask, so a PTT run cannot start between the busy/ready check and the reservation (and vice versa via one added guard clause in `startRecording`). Admission precedes the durable save, so a `409` never overwrites `received_watch.wav`; `201` is still returned only after durable save plus acceptance; PCM reaches the Orchestrator as raw header-stripped bytes via binary IPC (no base64); every terminal path releases the gate through the existing `finishRun` hook.
- All five task stop-conditions evaluated as not triggered; minimal implementation file list, four ordered event sequences, and nine risks/open questions are recorded in the contract document for the PM's decision.

## Delivery 1B Z1 PM review (2026-08-29) — NO-GO

Scope control passed: Z changed only `docs/DELIVERY-1B-PROVIDER-CONTRACT.md`, `HANDOFF.md`, and `PROJECT_PROGRESS.md`; no product code changed.

The contract is not frozen yet:

- It states `connect -> isReady -> start`, while the real run first checks readiness and then reconnects the existing guarded callbacks before `start`. The external sequence omits explicit successful `provider.connect(buildProviderCallbacks())` and `provider.start(...)` before PCM.
- It labels `tryReserveExternalRun` fully synchronous while also claiming it completes `bridge.getRecordingContext(false)`, which is asynchronous.
- Durable-save and other Rust-side aborts release only the Rust gate, leaving the JS reservation/run allocated.
- The proposed 30-second JS watchdog is shorter than valid processing plus late-final lifetimes and cannot execute after a WebView reload.
- The design explicitly permits a lost post-201 handoff, leaving the Watch showing success when no ASR run exists.
- It does not freeze `recordedChunks`, `audioSentSamples`, sample-derived duration, context fields, or a non-mic stop path required by existing History and timeout behavior.
- Raw binary Tauri IPC remains an unverified dependency despite the unconditional `MATCH` verdict.

Decision: Delivery 1B implementation remains locked. Colleague Z receives the documentation-only repair package `docs/DELIVERY-1B-Z-CONTRACT-REPAIR-1.md` and must stop after resubmission.

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

Debug APK: `watch/app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `81B14FECBC5119300FE98433526CF848E28178878B925F0E85A15DB6FBF06944` (not committed to Git).

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

Manual playback acceptance: on 2026-08-29 the user explicitly confirmed the human playback gate. Together with the independently checked file parameters and hash, Delivery 1A stage 5 is accepted. This acceptance covers the received audio only; it does not waive the source findings below or unlock Delivery 1B.

Upload HTTP response (from run-1-style PC self-test and run 2 device upload): `201 Created` with `requestId`, `bytes`, `sampleCount`, `audioDurationMs`, and lowercase hex `sha256` matching the saved file.

## PM review (2026-08-29) — NO-GO

Fresh PM checks confirmed the submitted APK and received WAV hashes match the handoff. The received file independently parses as PCM s16le, 16,000 Hz, mono, 14.88 seconds, 238,080 samples, 476,204 bytes; an automated scan detected no silence interval of 0.5 seconds or longer. The user subsequently confirmed the required human playback acceptance.

The source cannot be accepted yet:

- Watch destination and token rows are plain `Text`; the supplied `onChange` callback is never called, so the required settings cannot be entered through the Watch UI.
- Live duration is not sample-derived in the UI: `elapsedSec` is unused and `_sampleCount` is synchronized only after recording completes.
- A 48-hex-character token represents only 24 decoded bytes; current validators merely check string length and do not enforce the required 256-bit representation.
- Watch sends `X-Request-Id`, but the receiver discards it and creates another UUID, preventing reliable Watch-to-PC trace correlation.
- WAV chunk parsing walks to the physical file end even when the declared RIFF extent is shorter.
- `client/src-tauri/rust-toolchain.toml` changed outside the authorized path without necessity evidence and must return to the baseline declaration.

PM independently reran client Vitest (339 passed) and the client production build successfully. Android verification could not be independently reproduced in the PM shell because Java/JAVA_HOME is absent; Rust tests could not be reproduced there because CMake is absent. These environment blockers do not invalidate D's logs, but those logs are not independent PM proof.

Decision: do not merge, do not mark Delivery 1A accepted, and do not begin Delivery 1B. Colleague D receives only `docs/DELIVERY-1A-D-REPAIR-1.md`.

## Delivery 1A Repair 1 (Colleague D, 2026-08-29) — PM accepted

All six frozen discrepancies were repaired on `codex/review-watch-transport` (commit pushed; see `PROJECT_PROGRESS.md`). Focused diff per item:

1. **On-Watch editable settings**: `RecordingScreen.SettingsField` now opens a Wear dialog with a `BasicTextField` (IME `Done` action); values are committed on confirm. Proven on-device without ADB-pre-seeded prefs: PC IP `192.168.12.142`, port `18099`, and a 64-hex dev token were all typed through the real Wear keyboard and persisted (`watch-ev-*.png` screenshots).
2. **Live sample-derived duration**: `AudioCapture.record` now invokes `onProgress(cumulativeSamples)` after every read; `RecordingViewModel` publishes it to `_sampleCount`, and the status line renders `Recording <ms> ms` while recording (observed on-device increasing through ~3520 ms during a run that finished at 238,080 samples). Wall-clock time is never used as the source of truth.
3. **Frozen 64-hex Dev Token**: new `DevTokenValidator` (Watch) and `watch_receiver::config::validate_token` (Windows) both require exactly 64 hexadecimal chars after trimming (32 decoded bytes / 256 bits). Positive and negative tests cover 63/65 chars, non-hex, surrounding whitespace, and valid 64-char tokens on both sides.
4. **X-Request-Id end to end**: receiver validates `X-Request-Id` as a UUID (missing/invalid -> 400), echoes the same UUID in the 201 JSON, and includes it in the non-secret success log. Watch `TransportClient.verifySuccessResponse` accepts 201 only when the echoed `requestId` equals the sent UUID (mismatch/missing -> failure). Tests for mismatch/missing/invalid headers added.
5. **Strict RIFF bounds**: `wav::parse` now requires the declared RIFF extent to match the file extent (one valid container-pad byte tolerated) and bounds all chunk walking to the declared extent; a valid `fmt`/`data` beyond a shortened declaration is rejected (test added).
6. **Toolchain declaration restored**: `client/src-tauri/rust-toolchain.toml` reverted to `channel = "stable-x86_64-pc-windows-msvc"`. Reproducible evidence the baseline works: with `stable-x86_64-pc-windows-msvc` installed via rustup, `cargo --version` resolves 1.98.0 and `cargo check --tests`/`cargo test`/`cargo build --release` all succeed under the baseline declaration (the earlier failure was caused by the MSVC toolchain not being installed yet, not by the declaration).

Fresh verification for Repair 1:

- `watch`: `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug` — BUILD SUCCESSFUL (exit 0), 45 unit tests / 0 failures, lint clean.
- `client`: `npm test` — 339 passed (exit 0); `npm run build` — exit 0.
- `client/src-tauri`: `cargo test` — 145 tests, 141 passed / 0 failed / 4 ignored (exit 0); receiver module tests 28 passed.
- Release checks: `cargo build --release` binary contains none of `watch-receiver`, `SAYIT_WATCH_BIND_IP`, `received_watch.wav`, `X-Request-Id`, `api/watch/audio`; Watch release APK has no `usesCleartextTraffic`, debug APK has it.
- Debug APK (rebuilt): `watch/app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `1275C49D508A8E03AFD7E9D84F7EC668BE12332DE3E479152DDA38C20873E8D6` (not committed).

Repair-1 real-device evidence (D evidence): a new speech/ambient recording was captured on the Watch (238,080 samples, 14.88 s) and uploaded with a valid `X-Request-Id`; receiver logged `requestId=bb327d88-96a3-4af5-b700-c7eeeaeee942 bytes=476204 samples=238080 durationMs=14880` and durably saved `received_watch.wav` (SHA-256 `2929585a641a1f6d55d3aa715d8f21d153f67941742c5eb299fa135cd3de02d2`); desktop copy `received_watch_D-repair-voice.wav` for PM playback. PC-side self-tests confirmed 201 echoes the sent UUID and missing/invalid `X-Request-Id` return 400.

## Next executable step

The user personally transfers `docs/DELIVERY-1B-Z-CONTRACT-TASK.md` to colleague Z. Z freezes the real Provider/Orchestrator/History/Paste contract on `codex/review-watch-pipeline` and stops. PM reviews that document before issuing any implementation slice.

## PM acceptance (2026-08-29)

PM reviewed the Repair 1 diff against all six frozen findings and independently ran the available verification:

- Watch: unit tests forced to rerun, 45 passed / 0 failed / 0 skipped; lint, Debug APK and Release APK builds succeeded.
- Client: Vitest 29 files / 339 tests passed; TypeScript and Vite production build succeeded.
- Rust: 145 tests total, 141 passed / 0 failed / 4 pre-existing ignored; release build succeeded.
- Release safety: none of the five receiver markers (`watch-receiver`, `SAYIT_WATCH_BIND_IP`, `received_watch.wav`, `X-Request-Id`, `api/watch/audio`) occur in the release executable. Debug merged manifest enables cleartext; Release merged manifest does not enable it.
- Artifact evidence: the submitted repair APK hash was matched before the PM rebuild. A clean PM rebuild produced a new debug-artifact hash, as expected for a regenerated debug APK; it does not replace D's installed artifact identity.
- Device transport: the locally retained Repair 1 WAV hash matches D's report and independently parses as PCM s16le, 16,000 Hz, mono, 14.88 seconds, 238,080 samples, with no detected silence interval of 0.5 seconds or longer. The earlier human playback gate remains accepted.

D reported that IP, port and token were typed with the real Wear keyboard and that live sample-derived duration increased on device. The referenced screenshot files were not included in Git, so this portion remains explicitly classified as D production evidence rather than a PM-replayed attachment. It is consistent with the inspected source and the new device upload.

Decision: Delivery 1A **ACCEPTED**. Delivery 1B is unlocked but not started. No merge, release, installer, or Delivery 1B work was performed by this acceptance.

## Delivery 1A acceptance

- Debug Watch APK builds successfully and release configuration rejects cleartext transport.
- On a real Galaxy Watch 7, `AudioRecord` initializes at exactly 16 kHz, PCM 16-bit, mono.
- Authenticated upload reaches the explicitly configured Windows LAN IPv4.
- The receiver validates the WAV and atomically saves `%LOCALAPPDATA%\com.sayit.app\watch-receiver\received_watch.wav`.
- Response is `201 Created` with request ID, bytes, sample count, duration, and SHA-256.
- PM manually plays the file and records: no truncation, no abnormal gaps, correct speed/pitch, correct duration, and usable speech.

## Delivery 1B guardrail

Delivery 1B may modify the external-audio ingress only after the Delivery 1A acceptance gate is recorded here. It must reuse `RecorderOrchestrator`, the active `TranscriptionProvider`, existing callbacks, History, and Paste; it must not create a second ASR path.

## Security

- Development tokens are generated outside the repository and supplied through environment configuration.
- Tokens, received audio, local SDK paths, signing material, and device identifiers must not enter Git, logs, screenshots, or handoff documents.
