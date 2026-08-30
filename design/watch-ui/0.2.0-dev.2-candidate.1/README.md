# Watch UI candidate — 0.2.0-dev.2-candidate.1

- Status: `candidate` (first freeze for PM/user review; visual fixes would go to
  `0.2.0-dev.2-candidate.2` — this directory is never overwritten)
- Candidate version: Watch `0.2.0-dev.2`, `versionCode = 2`
- Parent version: Watch `0.2.0-dev.1`, `versionCode = 1` (baseline recorded in
  `design/watch-ui/0.2.0-dev.1-baseline/`, status `accepted-parent`, parent Git
  SHA `5da1a32279b372810d83504aca2021b0c8146763`)
- Source tree this candidate was built from: branch `codex/review-watch-pipeline`
  (the Z3 Repair 1 + Watch UI working tree; the exact commit SHA is reported in
  `HANDOFF.md` / `PROJECT_PROGRESS.md`)
- Product boundary: a Wear OS app (not a watch face). The Watch records one whole
  WAV and uploads it; SayIt on Windows performs ASR/History/Paste. AI cleanup is
  disabled for Delivery 1B.

## Screens and states (three screens + inline states)

1. **Developer configuration** (`01-config.svg`): PC RFC1918 IPv4, Port,
   64-hex Dev Token, Save & Apply. The token is masked by default
   (`A1B2••••••••7890` style) with an explicit Show/Hide toggle; editing opens
   the Wear keyboard dialog.
2. **Ready** (`02-ready.svg`): explicit Check / Refresh action —
   "Transport available" means only that `GET /api/health` answered, never
   Provider/ASR readiness. One primary Record button and a Settings entry.
3. **Recording** (`04-recording.svg`): sample-derived duration (never a wall
   clock), Stop, and Cancel — Cancel means stop + discard, no upload.

Inline states:

- **Uploading** (`05-uploading.svg`): after Stop the completed WAV uploads
  automatically (no separate Send page). The app keeps the screen awake.
- **Upload failed** (`06-upload-failed.svg`): the WAV is retained; Retry
  resends the SAME bytes (no re-recording); Later returns to Ready.
- **Pending upload** (`03-ready-pending.svg`): obvious badge on Ready; a new
  recording requires the explicit discard prompt — the retained WAV is never
  silently overwritten.
- **Uploaded to PC** (`07-uploaded.svg`): brief transport-only confirmation,
  then back to Ready. Never "Transcribed", "Recognition complete" or
  "Text inserted".

Haptics: exactly four — recording start, recording stop, upload success,
upload failure. Keep-screen-on is app-driven during recording/upload.

## Inherited locks and constraints

- Debug builds allow cleartext HTTP; release builds forbid it (existing
  `CleartextPolicy`/manifest tests unchanged and green).
- No new pages (no mic-sensitivity, About, AI cleanup, pairing), no carousel
  dots, no streaming/Opus/discovery/background recording.
- 480×480 round safe area: primary controls centered within the circle;
  configuration form scrolls (Wear scrolling/rotary).

## Deviations from the handoff, declared

- The preview images in `previews/` are hand-authored layout renders
  (SVG, 480×480, circularly clipped), not photographic screenshots: this
  environment has no Android emulator or physical watch attached. Real-device
  visual/interaction acceptance is explicitly deferred to PM/user, and the
  composition code these previews document builds green (unit tests, lint,
  Debug/Release APK).
- Baseline "现状截图" has the same environment limitation; the baseline is
  pinned by parent Git SHA + per-file SHA-256 manifest instead
  (`../0.2.0-dev.1-baseline/`).

## Integrity

`SHA256SUMS` covers every file in this directory (previews + this README after
final write). No real token, IP, port, or device information appears anywhere
in this directory — placeholders only (`192.168.x.x`, `<PORT>`,
`A1B2••••••••7890`).
