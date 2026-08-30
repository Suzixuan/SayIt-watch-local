# Delivery 1A — D Repair 1

Status: PM source review failed; Delivery 1B remains locked.

Branch: `codex/review-watch-transport`

## Goal

Repair only the discrepancies found during PM review. Do not add features and do not begin Delivery 1B.

## Required repairs

1. Make PC IPv4, port, and Dev Token genuinely editable on the Watch.
   - `RecordingScreen.SettingsField` currently renders `Text` and never invokes `onChange`.
   - Use an actual Wear-compatible text input interaction.
   - Prove all three values can be entered on a real Watch without ADB or pre-seeded preferences.
   - Keep the RFC1918 and port validation already present.

2. Show a live recording duration derived from captured samples.
   - `elapsedSec` is currently unused.
   - `durationMs` is derived from `_sampleCount`, but `_sampleCount` is updated only after capture completes.
   - Publish the actual cumulative read sample count during recording and render it. Do not use wall-clock time as the source of truth.

3. Freeze one Dev Token representation with at least 256 bits of random material.
   - Use exactly 64 hexadecimal characters (32 decoded bytes) for this debug delivery.
   - Enforce the same trimmed, exact-format validation on Watch and Windows.
   - Add positive and negative tests: 63/65 chars, non-hex, surrounding whitespace, and a valid 64-char token.
   - Do not log or commit a real token.

4. Preserve the Watch request UUID end to end.
   - Validate `X-Request-Id` as a UUID in the receiver.
   - Echo that same UUID in the `201` JSON and include it in the non-secret receiver success log.
   - Watch success must use/verify the response request ID rather than returning an unrelated locally generated ID without checking the response.
   - Add mismatch/missing/invalid-header tests according to the frozen behavior.

5. Make RIFF bounds strict.
   - Reject files whose declared RIFF extent does not match the accepted file extent (apart from a valid RIFF padding rule).
   - Bound all chunk walking to that declared extent, not `bytes.len()` after a shorter declaration.
   - Add a test where a valid `fmt`/`data` chunk is placed beyond a deliberately shortened RIFF declaration; it must fail.

6. Restore the baseline Rust toolchain declaration.
   - Revert `client/src-tauri/rust-toolchain.toml` to `channel = "stable-x86_64-pc-windows-msvc"` unless PM is first given reproducible evidence that the baseline declaration prevents this delivery from building.

## Scope limits

- No ASR, Provider, RecorderOrchestrator, History, Paste, Target, AI, VAD, streaming, pairing, discovery, UI redesign, or release feature work.
- Do not change the accepted receiver save path or the `201 Created` transport-only meaning.
- Do not commit APKs, WAVs, tokens, SDK paths, device identifiers, or build caches.

## Required evidence

- Focused diff mapped to each item above.
- Fresh Android unit tests, lint, and debug APK build.
- Fresh client Vitest/build and Rust tests.
- Release cleartext/receiver exclusion checks.
- Real Watch video or screenshots showing all three fields edited on-device, live sample-derived duration increasing, recording retained, and successful retry/upload.
- A new real Watch speech WAV with exact Watch/receiver sample count, duration, SHA-256, and upload latency.

Stop after pushing the repair commit(s). Do not start Delivery 1B.
