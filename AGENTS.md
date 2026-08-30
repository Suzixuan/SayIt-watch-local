# SayIt Watch Transport project instructions

## Authority and current goal

- Read `HANDOFF.md`, `PROJECT_PROGRESS.md`, and the active delivery task before changing files.
- Delivery 1A is PM-accepted. The Delivery 1B Provider contract revision 3 is PM-accepted as CONDITIONAL MATCH. The only unlocked slice is the isolated binary-IPC runtime gate in `docs/DELIVERY-1B-Z-BINARY-IPC-SPIKE-TASK.md`.
- `HANDOVER.md` documents the inherited security-hardened SayIt source. Preserve those fixes.
- `PROJECT_PROGRESS.md` is the only authoritative progress table for this project.

## Hard boundaries

- During the binary-IPC spike, do not modify the existing AudioRelay path, ASR implementations, Provider implementations, `RecorderOrchestrator`, History, Paste, Watch/receiver product code, target tracking, VAD, AI, update, backup, storage-security, server code, or existing dependency locks. The spike belongs only under `spikes/watch-binary-ipc/` plus its two authority-document updates.
- Do not implement the Delivery 1B external-audio entry before PM accepts the packaged Windows binary-IPC runtime evidence.
- Do not add streaming, WebSockets, Opus, discovery, mDNS, QR codes, formal pairing, background recording, wake words, double-Home behavior, or a phone companion app.
- HTTP receiver code must be debug-only, bind one explicit RFC1918 LAN IPv4, and fail closed when any required environment variable is missing or invalid.
- Never commit tokens, `.env` files, Android local paths, keystores, APK/AAB files, device serial numbers, received audio, models, installers, or build caches.

## Git and evidence

- Accepted Delivery 1A work is on `codex/review-watch-transport`. Delivery 1B work belongs only on `codex/review-watch-pipeline`, based on the accepted Delivery 1A state.
- Do not merge, tag, release, force-push, or push to `crosswk/SayIt`.
- Before claiming completion, return the commit SHA, changed-file list, commands with exit codes, test/build output, APK path and SHA-256, and unresolved risks.
- Real Galaxy Watch installation, permissions, Wi-Fi transfer, and playback evidence are production/device work and must be reported separately from source/build results.
