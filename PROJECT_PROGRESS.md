# SayIt Watch Transport project progress

Overall: Delivery 1A accepted by PM on 2026-08-29. Delivery 1B is unlocked but not started.

| Group | # | Task | Acceptance | Status | Owner | Dependency / evidence |
|---|---:|---|:---:|---|---|---|
| Foundation | 1 | Isolated private baseline and PM authority files | ☑ | 🟢 Completed | PM | Private `main` baseline import `18400ad82d7f5a7009a47622248643022472650f` |
| Delivery 1A | 2 | Watch debug recorder and WAV writer | ☑ | 🟢 Accepted | PM, Colleague D | PM reviewed editable Wear fields and live sample-derived duration; Android 45/45 tests, lint, Debug and Release builds independently passed |
| Delivery 1A | 3 | Debug-only Windows LAN receiver | ☑ | 🟢 Accepted | PM, Colleague D | PM reviewed 64-hex token, request-ID preservation and strict RIFF bounds; Rust 141 passed / 0 failed / 4 ignored |
| Delivery 1A | 4 | Android and Windows source/build verification | ☑ | 🟢 Accepted | PM | Fresh PM runs: Android 45/45 plus lint/Debug/Release builds; Vitest 339/339 and client build; Rust 141/141 non-ignored tests; release receiver-marker scan clean |
| Delivery 1A | 5 | Galaxy Watch 7 transport and manual WAV playback gate | ☑ | 🟢 Accepted | User, PM | PM independently parsed 14.88 s / 238,080-sample 16 kHz s16le mono WAV and matched SHA-256; user confirmed human playback acceptance on 2026-08-29 |
| Delivery 1B | 6 | Freeze existing Provider input contract | ☐ | 🔵 Repair required | Colleague Z | PM review NO-GO on submission `525f0b2`; documentation-only repair: `docs/DELIVERY-1B-Z-CONTRACT-REPAIR-1.md` |
| Delivery 1B | 7 | External WAV ingress through existing History/Paste | ☐ | ⚪ Locked | Colleague Z | Unlocks only after PM accepts the Z1 contract; must reuse active Provider and existing callbacks |
| Acceptance | 8 | Ten consecutive real end-to-end runs | ☐ | ⚪ Locked | User, PM | Record five stages, Stop-to-Paste latency, median and P95 |

Current external slice: Delivery 1B Z1 contract Repair 1, waiting for the user to transfer the repair package to colleague Z. No implementation is authorized.

Z1 PM review (2026-08-29): scope control passed (only the three allowed documentation files changed), but the contract decision is NO-GO. The submitted design omits the required per-run Provider `connect(callbacks)` and explicit successful `start` before PCM, treats asynchronous native context capture as synchronous, leaks the JS reservation on Rust-side aborts, uses an unsafe 30-second JS watchdog, permits a lost post-201 handoff, and does not freeze the audio accounting required by History/timeouts. Repair instructions: `docs/DELIVERY-1B-Z-CONTRACT-REPAIR-1.md`.

PM review decision (2026-08-29): **NO-GO** on the original submission. Repair instructions were frozen in `docs/DELIVERY-1A-D-REPAIR-1.md`; all six items are now repaired and pushed.

Repair 1 verification (Colleague D, 2026-08-29, branch `codex/review-watch-transport`):

- `watch`: `.\\gradlew.bat testDebugUnitTest lintDebug assembleDebug` — BUILD SUCCESSFUL (exit 0), 45 unit tests / 0 failures (added DevTokenValidator tests: 63/65 chars, non-hex, whitespace, valid 64; TransportStatus requestId mismatch/missing tests), lint clean.
- `client`: `npm test` — 29 files / 339 tests passed; `npm run build` — exit 0.
- `client/src-tauri`: `cargo test` — 145 tests, 141 passed / 0 failed / 4 ignored (exit 0); added receiver X-Request-Id (missing/invalid -> 400, echo on 201) and strict RIFF extent tests.
- Release safety: `cargo build --release` binary contains none of `watch-receiver`, `SAYIT_WATCH_BIND_IP`, `received_watch.wav`, `X-Request-Id`, `api/watch/audio`; Watch release APK manifest denies cleartext, debug APK permits it.
- `rust-toolchain.toml` restored to baseline `channel = "stable-x86_64-pc-windows-msvc"`; builds/tests verified under it (earlier failure was missing MSVC toolchain, now installed via rustup).
- Debug APK (rebuilt): SHA-256 `1275C49D508A8E03AFD7E9D84F7EC668BE12332DE3E479152DDA38C20873E8D6` (path `watch/app/build/outputs/apk/debug/app-debug.apk`, not committed).
- Repair-1 real-device evidence: three settings entered via real Wear keyboard (IP `192.168.12.142`, port `18099`, 64-hex token) with screenshots; live `Recording 3520 ms` observed mid-capture; new recording uploaded with valid X-Request-Id — receiver logged `requestId=bb327d88-96a3-4af5-b700-c7eeeaeee942 bytes=476204 samples=238080 durationMs=14880`, saved `received_watch.wav` SHA-256 `2929585a641a1f6d55d3aa715d8f21d153f67941742c5eb299fa135cd3de02d2`; desktop copy `received_watch_D-repair-voice.wav` for PM playback.

PM acceptance evidence (2026-08-29): Android unit tests rerun with `--rerun-tasks` (45 passed, 0 failed), lint plus Debug/Release APK builds succeeded; client Vitest 339 passed and production build succeeded; Rust tests reported 141 passed, 0 failed, 4 ignored; Rust release build succeeded and the five debug-receiver markers were absent. The repair APK and received Watch WAV hashes were independently checked. The received repair WAV parses as PCM s16le, 16 kHz, mono, 14.88 seconds and 238,080 samples. Delivery 1A is accepted. Stop here until Delivery 1B is explicitly dispatched.

Z1 submission (Colleague Z, 2026-08-29, branch `codex/review-watch-pipeline`, base `5cc14b4c8658920cc9ca9b21ab1dcaf878f1a136`): `docs/DELIVERY-1B-PROVIDER-CONTRACT.md` submitted, awaiting PM review. Verdict MATCH — the six proposed audio clauses are confirmed from source evidence on both the capture side (`audio.ts` 16 kHz/mono/Int16, `bytes/2/16000` duration) and the receiver side (`wav.rs` PCM/mono/16 kHz/16-bit enforcement). The document freezes the real `TranscriptionProvider` call order and runId guards, the History-first-then-Paste flow with the one-shot insertion probe, and the `aiEnabled`/`disableAi` per-run freeze mechanism, and designs the race-safe `409` admission boundary (Rust `AdmissionGate` + synchronous WebView check-and-reserve in a single JS macrotask, admission before durable save, binary-IPC PCM handoff, `finishRun` gate release). All five stop-conditions evaluated as not triggered; only the three allowed documentation files changed.
