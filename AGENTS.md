# SayIt Watch Transport project instructions

## Authority and current goal

- Read `HANDOFF.md`, `PROJECT_PROGRESS.md`, and the active delivery task before changing files.
- Delivery 1A and Delivery 1B stage-7 source/UI gates are PM-accepted. The only unlocked slice is the Galaxy Watch 7 device/ten-run acceptance in `docs/DELIVERY-1B-D-DEVICE-ACCEPTANCE.md`; Delivery 1B is not VERIFIED until that task passes.
- `HANDOVER.md` documents the inherited security-hardened SayIt source. Preserve those fixes.
- `PROJECT_PROGRESS.md` is the only authoritative progress table for this project.

## Hard boundaries

- During device acceptance, do not modify product source or frozen design/baseline directories. Preserve the existing AudioRelay path, ASR/Provider implementations, History, Paste, AI, target tracking, VAD, update, backup, storage-security, release HTTP behavior, and accepted dependency locks.
- Execute only the real Galaxy Watch visual/interaction smoke and ten consecutive Watch-to-Paste runs. Any source defect stops the task and returns to PM; do not repair inline.
- Do not add streaming, WebSockets, Opus, discovery, mDNS, QR codes, formal pairing, background recording, wake words, double-Home behavior, or a phone companion app.
- HTTP receiver code must be debug-only, bind one explicit RFC1918 LAN IPv4, and fail closed when any required environment variable is missing or invalid.
- Never commit tokens, `.env` files, Android local paths, keystores, APK/AAB files, device serial numbers, received audio, models, installers, or build caches.

## Git and evidence

- Accepted Delivery 1A work is on `codex/review-watch-transport`. Delivery 1B work belongs only on `codex/review-watch-pipeline`, based on the accepted Delivery 1A state.
- Do not merge, tag, release, force-push, or push to `crosswk/SayIt`.
- Before claiming completion, return the commit SHA, changed-file list, commands with exit codes, test/build output, APK path and SHA-256, and unresolved risks.
- Real Galaxy Watch installation, permissions, Wi-Fi transfer, and playback evidence are production/device work and must be reported separately from source/build results.
