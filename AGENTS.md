# SayIt Watch Transport project instructions

## Authority and current goal

- Read `HANDOFF.md`, `PROJECT_PROGRESS.md`, and the active delivery task before changing files.
- The only unlocked product slice is Delivery 1A: Galaxy Watch 7 records a genuine 16 kHz, 16-bit, mono WAV and uploads it over debug-only HTTP to the SayIt Windows receiver, which atomically saves `received_watch.wav`.
- `HANDOVER.md` documents the inherited security-hardened SayIt source. Preserve those fixes.
- `PROJECT_PROGRESS.md` is the only authoritative progress table for this project.

## Hard boundaries

- Do not modify the existing AudioRelay path, ASR implementations, Provider implementations, `RecorderOrchestrator`, History, Paste, target tracking, VAD, AI, update, backup, storage-security, or server code during Delivery 1A.
- Do not implement Delivery 1B before PM records manual playback acceptance for Delivery 1A.
- Do not add streaming, WebSockets, Opus, discovery, mDNS, QR codes, formal pairing, background recording, wake words, double-Home behavior, or a phone companion app.
- HTTP receiver code must be debug-only, bind one explicit RFC1918 LAN IPv4, and fail closed when any required environment variable is missing or invalid.
- Never commit tokens, `.env` files, Android local paths, keystores, APK/AAB files, device serial numbers, received audio, models, installers, or build caches.

## Git and evidence

- Delivery 1A work belongs only on `codex/review-watch-transport`.
- Do not merge, tag, release, force-push, or push to `crosswk/SayIt`.
- Before claiming completion, return the commit SHA, changed-file list, commands with exit codes, test/build output, APK path and SHA-256, and unresolved risks.
- Real Galaxy Watch installation, permissions, Wi-Fi transfer, and playback evidence are production/device work and must be reported separately from source/build results.

