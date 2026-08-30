# SayIt Watch Transport project progress

Overall: Delivery 1A Repair 1 implemented with fresh source/build/device evidence; awaiting PM re-review. Delivery 1B remains locked.

| Group | # | Task | Acceptance | Status | Owner | Dependency / evidence |
|---|---:|---|:---:|---|---|---|
| Foundation | 1 | Isolated private baseline and PM authority files | ☑ | 🟢 Completed | PM | Private `main` baseline import `18400ad82d7f5a7009a47622248643022472650f` |
| Delivery 1A | 2 | Watch debug recorder and WAV writer | ☐ | 🔵 Repair 1 done, awaiting PM re-review | Colleague D | Settings now on-device editable (Wear dialog + keyboard); live sample-derived duration rendered; 45 unit tests pass |
| Delivery 1A | 3 | Debug-only Windows LAN receiver | ☐ | 🔵 Repair 1 done, awaiting PM re-review | Colleague D | 64-hex token rule, X-Request-Id UUID validate/echo/log, strict RIFF declared-extent bounds; receiver tests 28 pass |
| Delivery 1A | 4 | Android and Windows source/build verification | ☐ | 🔵 Fresh evidence, awaiting PM reproduction | PM, Colleague D | gradle 45 tests/lint/assemble exit 0; npm 339 + build exit 0; cargo 141 pass / 0 fail / 4 ignored exit 0; release binary + APK safety re-verified |
| Delivery 1A | 5 | Galaxy Watch 7 transport and manual WAV playback gate | ☑ | 🟢 Accepted | User, PM | PM independently parsed 14.88 s / 238,080-sample 16 kHz s16le mono WAV and matched SHA-256; user confirmed human playback acceptance on 2026-08-29 |
| Delivery 1B | 6 | Freeze existing Provider input contract | ☐ | ⚪ Locked | PM, Colleague D | Unlocks only after all Delivery 1A stages 2–5 are accepted |
| Delivery 1B | 7 | External WAV ingress through existing History/Paste | ☐ | ⚪ Locked | Colleague D | Must reuse active Provider and existing callbacks |
| Acceptance | 8 | Ten consecutive real end-to-end runs | ☐ | ⚪ Locked | User, PM | Record five stages, Stop-to-Paste latency, median and P95 |

Current external slice: Delivery 1A Repair 1 re-review. Delivery 1B is locked.

PM review decision (2026-08-29): **NO-GO** on the original submission. Repair instructions were frozen in `docs/DELIVERY-1A-D-REPAIR-1.md`; all six items are now repaired and pushed.

Repair 1 verification (Colleague D, 2026-08-29, branch `codex/review-watch-transport`):

- `watch`: `.\\gradlew.bat testDebugUnitTest lintDebug assembleDebug` — BUILD SUCCESSFUL (exit 0), 45 unit tests / 0 failures (added DevTokenValidator tests: 63/65 chars, non-hex, whitespace, valid 64; TransportStatus requestId mismatch/missing tests), lint clean.
- `client`: `npm test` — 29 files / 339 tests passed; `npm run build` — exit 0.
- `client/src-tauri`: `cargo test` — 145 tests, 141 passed / 0 failed / 4 ignored (exit 0); added receiver X-Request-Id (missing/invalid -> 400, echo on 201) and strict RIFF extent tests.
- Release safety: `cargo build --release` binary contains none of `watch-receiver`, `SAYIT_WATCH_BIND_IP`, `received_watch.wav`, `X-Request-Id`, `api/watch/audio`; Watch release APK manifest denies cleartext, debug APK permits it.
- `rust-toolchain.toml` restored to baseline `channel = "stable-x86_64-pc-windows-msvc"`; builds/tests verified under it (earlier failure was missing MSVC toolchain, now installed via rustup).
- Debug APK (rebuilt): SHA-256 `1275C49D508A8E03AFD7E9D84F7EC668BE12332DE3E479152DDA38C20873E8D6` (path `watch/app/build/outputs/apk/debug/app-debug.apk`, not committed).
- Repair-1 real-device evidence: three settings entered via real Wear keyboard (IP `192.168.12.142`, port `18099`, 64-hex token) with screenshots; live `Recording 3520 ms` observed mid-capture; new recording uploaded with valid X-Request-Id — receiver logged `requestId=bb327d88-96a3-4af5-b700-c7eeeaeee942 bytes=476204 samples=238080 durationMs=14880`, saved `received_watch.wav` SHA-256 `2929585a641a1f6d55d3aa715d8f21d153f67941742c5eb299fa135cd3de02d2`; desktop copy `received_watch_D-repair-voice.wav` for PM playback.

Next unlocked item: PM re-reviews the Repair 1 diff and evidence, then re-runs what the PM shell can (Vitest/build; Android/Rust blocked by missing Java/CMake in the PM shell) and records Delivery 1A acceptance or further repair.
