# SayIt Watch Transport project instructions

## Authority and current goal

- Read `HANDOFF.md`, `PROJECT_PROGRESS.md`, and the active delivery task before changing files.
- Delivery 1A is PM-accepted. The Delivery 1B Provider contract is MATCH after Z2. Z3 PC-side fixes, responsive width work, and recovered parent manifest are PM-retained; Watch UI candidate.3 is NO-GO. The only unlocked slice is Watch UI Repair 4 in `docs/DELIVERY-1B-Z3-REPAIR-4.md`.
- `HANDOVER.md` documents the inherited security-hardened SayIt source. Preserve those fixes.
- `PROJECT_PROGRESS.md` is the only authoritative progress table for this project.

## Hard boundaries

- During Z3 Repair 4, change only the paths and surfaces listed in `docs/DELIVERY-1B-Z3-REPAIR-4.md`. Do not rewrite PM-retained PC admission/ingress or responsive-layout work, and do not modify frozen design/baseline directories. Preserve the existing AudioRelay path, ASR/Provider implementations, History, Paste, AI, target tracking, VAD, update, backup, storage-security, release HTTP behavior, and accepted dependency locks.
- Fix and test the multi-generation completion gate and final readable Watch UI candidate only. Do not begin real-device or ten-run closure before PM accepts Repair 4.
- Do not add streaming, WebSockets, Opus, discovery, mDNS, QR codes, formal pairing, background recording, wake words, double-Home behavior, or a phone companion app.
- HTTP receiver code must be debug-only, bind one explicit RFC1918 LAN IPv4, and fail closed when any required environment variable is missing or invalid.
- Never commit tokens, `.env` files, Android local paths, keystores, APK/AAB files, device serial numbers, received audio, models, installers, or build caches.

## Git and evidence

- Accepted Delivery 1A work is on `codex/review-watch-transport`. Delivery 1B work belongs only on `codex/review-watch-pipeline`, based on the accepted Delivery 1A state.
- Do not merge, tag, release, force-push, or push to `crosswk/SayIt`.
- Before claiming completion, return the commit SHA, changed-file list, commands with exit codes, test/build output, APK path and SHA-256, and unresolved risks.
- Real Galaxy Watch installation, permissions, Wi-Fi transfer, and playback evidence are production/device work and must be reported separately from source/build results.
