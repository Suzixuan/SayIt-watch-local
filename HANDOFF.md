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
- No Watch application or receiver implementation has been accepted yet.
- The current machine did not expose Java, Android Studio, Android SDK, ADB, Gradle, or CMake on the checked paths. Employee D owns toolchain installation and device-facing work.
- Delivery 1B remains locked.

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
