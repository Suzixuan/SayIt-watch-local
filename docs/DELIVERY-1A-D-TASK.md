# Colleague D task package — SayIt Watch Transport Delivery 1A

Status: `⚪ Pending user transfer`

Repository: `https://github.com/Suzixuan/SayIt-watch-local` (private)

Required branch: `codex/review-watch-transport`, created from the current remote `main`.

Candidate version: `0.2.0-dev.1`. This is a development candidate, not a release.

## Objective

Implement and prove only this transport path:

`Galaxy Watch 7 -> AudioRecord 16 kHz/16-bit/mono -> WAV -> debug HTTP POST -> SayIt Windows receiver bound to one LAN IPv4 -> received_watch.wav`

Do not implement ASR integration. Stop after source/build evidence and the real-device transport package are ready for PM review.

## Read first

1. `AGENTS.md`
2. `HANDOFF.md`
3. `PROJECT_PROGRESS.md`
4. `HANDOVER.md`

## Allowed paths

- `watch/**` for the new Wear OS debug application and its tests.
- A new narrowly scoped receiver module under `client/src-tauri/src/`.
- Minimal receiver registration changes in `client/src-tauri/src/main.rs`.
- Receiver-only dependency/feature changes in `client/src-tauri/Cargo.toml` and its lockfile.
- Receiver and Watch documentation/tests.
- `HANDOFF.md` and `PROJECT_PROGRESS.md` for factual evidence updates.

Do not modify `client/src/services/recorder/**`, `client/src/services/transcription/**`, Rust ASR/models/providers, History, Paste/injection, focus/context tracking, VAD, AI, storage security, backup, update, server backend, or inherited security-review fixes.

## Watch implementation contract

- Kotlin and Compose for Wear OS. No phone companion.
- Settings: PC RFC1918 IPv4, port, and Dev Token. Store them only in app-private debug storage.
- Controls/states: Start, Stop, Send; recording duration; ready, recording, recorded, uploading, transport-success, and failure.
- Request `RECORD_AUDIO` at the moment the user starts recording.
- Before recording, require:
  - positive `AudioRecord.getMinBufferSize(16000, CHANNEL_IN_MONO, ENCODING_PCM_16BIT)`;
  - `AudioRecord.STATE_INITIALIZED`;
  - actual `sampleRate == 16000`.
- Fail visibly with `16 kHz unsupported` if any check fails. Do not resample or fall back.
- Record on a dedicated I/O coroutine/thread. Write exactly the count returned by each `AudioRecord.read`; do not write unused buffer bytes.
- Build a canonical little-endian PCM RIFF/WAV. Derive duration from successfully captured sample count.
- Retain the complete WAV after upload failure so Send can be retried without re-recording.
- Add short vibration feedback for recording start/stop and upload success/failure.
- Validate the destination as one RFC1918 IPv4 and port 1-65535. Reject hostnames, loopback, wildcard, link-local, IPv6, and public addresses.
- Send raw WAV bytes to `POST /api/watch/audio` with `Content-Type: audio/wav`, `Authorization: Bearer <token>`, and a random request UUID.
- Treat `201 Created` as transport success only. Display `Uploaded / transport verified`, never `Transcribed`.
- Debug manifest explicitly permits cleartext. Release manifest and release runtime code deny cleartext and must not expose a usable HTTP sender.

## Windows receiver contract

- Implement inside the Tauri/Rust process and compile/start it only under `debug_assertions`.
- A small blocking `tiny_http` listener is sufficient; do not introduce async server architecture or WebSockets.
- Required environment variables:
  - `SAYIT_WATCH_BIND_IP`: one RFC1918 IPv4 only; reject `0.0.0.0`, loopback, hostnames, IPv6, public and link-local addresses.
  - `SAYIT_WATCH_PORT`: integer 1-65535.
  - `SAYIT_WATCH_DEV_TOKEN`: at least 32 bytes of unpredictable token material after decoding/validation.
- Missing or invalid configuration means the receiver does not start. Log only the bind IP/port and a token-present boolean; never the token or Authorization header.
- `GET /api/health` returns minimal JSON identifying a debug receiver. It does not claim ASR readiness.
- `POST /api/watch/audio` requires exact Bearer authentication and `audio/wav` content type; cap the body at 10 MiB before allocation/writing.
- Parse RIFF chunks safely, including even-byte padding and unknown chunks. Require PCM format 1, one channel, 16,000 Hz, 16 bits, non-empty even-length data, consistent block alignment/byte rate, and no truncated chunk.
- Save the validated full WAV under `%LOCALAPPDATA%\com.sayit.app\watch-receiver\received_watch.wav`.
- Write a same-directory unique temporary file, flush and `sync_all`, then use a Windows replace-with-write-through operation so a partial file never becomes the accepted sample. Clean only this request's temporary file on failure.
- Return `201 Created` only after durable replacement. JSON fields: `requestId`, `bytes`, `sampleCount`, `audioDurationMs`, and lowercase hex `sha256`.
- Use explicit JSON error bodies and appropriate `400`, `401`, `404`, `405`, `413`, or `500` responses. Do not return `202`.
- Do not emit events to the frontend and do not call any ASR/Provider/Orchestrator code in Delivery 1A.

## Required automated verification

Watch tests:

- WAV header sizes and sample-derived duration.
- Exact handling of partial AudioRecord reads.
- Recording state transitions and retry without re-recording.
- RFC1918/port validation.
- Debug cleartext allowed and release cleartext denied.
- `201` maps only to transport success.

Receiver tests:

- LAN bind validation and failure on missing configuration.
- Valid/invalid/missing Bearer token without secret leakage.
- Content type and 10 MiB limit.
- Valid WAV plus malformed, truncated, wrong-rate, stereo, odd PCM, inconsistent byte-rate/block-align, padded and unknown chunks.
- Atomic replacement leaves the previous accepted file intact on simulated failure.
- Success response metadata and SHA-256.
- Release build contains no active receiver startup path.

Run and return fresh output with exit codes for:

```powershell
cd watch
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug

cd ..\client
npm test
npm run build

cd src-tauri
cargo test
```

If inherited tests fail, separate the exact pre-existing failure from new failures with evidence. Do not hide or rewrite unrelated failures.

## Device/production evidence

Toolchain installation, Android license acceptance, ADB pairing, Watch installation, permission grants, firewall Private-network approval, and real-device recording are your responsibility. Do not commit local paths, credentials, device serials, tokens, APKs, or received audio.

Return:

- Android Studio/JDK/SDK/CMake versions used.
- Galaxy Watch model and Wear OS version, without device serial.
- Actual AudioRecord min buffer, initialized state, reported sample rate, captured sample count, WAV bytes and duration.
- HTTP status, upload duration, response metadata, and Watch/PC SHA-256 comparison.
- Exact local path of the received WAV and a concise manual playback observation, clearly marked as D evidence pending PM acceptance.
- Debug APK path and SHA-256; do not upload it to Git history.

## Git delivery

- Commit only intended source/docs/tests to `codex/review-watch-transport`.
- Push the branch to the private repository.
- Do not merge, tag, release, force-push, modify `main`, or push anywhere else.
- Return branch, commit SHA, changed-file list, all verification outputs, artifact hashes, and remaining risks.
- Update `HANDOFF.md` and `PROJECT_PROGRESS.md` factually, but do not mark Delivery 1A accepted; only PM can do that after independent review and playback.

