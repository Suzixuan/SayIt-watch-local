# SayIt Watch Transport project instructions

## Authority and current goal

- Read `HANDOFF.md`, `PROJECT_PROGRESS.md`, and the active delivery task before changing files.
- Delivery 1A is PM-accepted. The Delivery 1B Provider contract is PM-accepted as MATCH after Z2. The only unlocked slice is the PC-only external WAV ingress in `docs/DELIVERY-1B-Z-EXTERNAL-INGRESS-TASK.md`.
- `HANDOVER.md` documents the inherited security-hardened SayIt source. Preserve those fixes.
- `PROJECT_PROGRESS.md` is the only authoritative progress table for this project.

## Hard boundaries

- During the external-ingress slice, change only the paths and additive surfaces listed in `docs/DELIVERY-1B-Z-EXTERNAL-INGRESS-TASK.md`. Preserve the existing AudioRelay path, ASR/Provider implementations, History, Paste, AI, Watch app/UI, target tracking, VAD, update, backup, storage-security, release HTTP behavior, and accepted dependency locks.
- Do not implement Watch UI changes or begin real-device/ten-run closure before PM accepts the PC external-ingress slice.
- Do not add streaming, WebSockets, Opus, discovery, mDNS, QR codes, formal pairing, background recording, wake words, double-Home behavior, or a phone companion app.
- HTTP receiver code must be debug-only, bind one explicit RFC1918 LAN IPv4, and fail closed when any required environment variable is missing or invalid.
- Never commit tokens, `.env` files, Android local paths, keystores, APK/AAB files, device serial numbers, received audio, models, installers, or build caches.

## Git and evidence

- Accepted Delivery 1A work is on `codex/review-watch-transport`. Delivery 1B work belongs only on `codex/review-watch-pipeline`, based on the accepted Delivery 1A state.
- Do not merge, tag, release, force-push, or push to `crosswk/SayIt`.
- Before claiming completion, return the commit SHA, changed-file list, commands with exit codes, test/build output, APK path and SHA-256, and unresolved risks.
- Real Galaxy Watch installation, permissions, Wi-Fi transfer, and playback evidence are production/device work and must be reported separately from source/build results.
