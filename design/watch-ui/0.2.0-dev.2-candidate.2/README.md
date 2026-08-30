# Watch UI candidate — 0.2.0-dev.2-candidate.2

- Status: `candidate` (second freeze; supersedes `candidate.1` for review —
  candidate.1 is preserved untouched as the audit record of Repair 2 findings)
- Candidate version: Watch `0.2.0-dev.2`, `versionCode = 2` (unchanged — this is
  a behavior/evidence repair of the same candidate, not a version bump)
- Parent: `0.2.0-dev.1` baseline — the PM-invalidated manifest in
  `../0.2.0-dev.1-baseline/` is superseded by
  `../0.2.0-dev.1-baseline-recovery.1/` (regenerated from the parent Git blobs
  of `5da1a32279b372810d83504aca2021b0c8146763`)
- Product boundary: a Wear OS app (not a watch face). The Watch records one
  whole WAV and uploads it; SayIt on Windows performs ASR/History/Paste. AI
  cleanup is disabled for Delivery 1B.

## What changed since candidate.1 (PM Repair 2 findings → fixes)

1. **Cancel race**: the recording I/O coroutine is now gated by a
   `RecordingRequestLatch` generation — after Cancel, a late capture completion
   is dropped entirely (no `recordingCompleted`, no auto-upload, no FAILURE
   write). Cancel always ends READY with `wavBytes == null`.
2. **Retry reachable after Later**: `retryPressed()` now accepts the
   Pending-ready state; the Pending badge is tappable AND a wide
   "Retry upload" chip sits next to it on Ready. Pending-ready → Retry →
   Uploading with the SAME retained bytes (no re-record, no silent overwrite —
   the discard prompt is unchanged).
3. **Runtime controls match the previews**: every text action is now a Wear
   `Chip` — wide pills are 340×52 dp (`Save & Apply`, `Check / Refresh`,
   `Retry upload`), the failure actions are two 160×52 dp chips (Retry/Later),
   all ≥48 dp touch height. Record/Stop remain 52 dp circular buttons with ●/■
   glyphs and captions. Config rows and the token Show/Hide have ≥48 dp touch
   heights. The previews below are generated from the same `WatchUiMetrics`
   numbers the Compose code uses, and `WatchUiMetricsTest` pins the invariants
   automatically (≥48 dp heights, widths inside the 480×480 circular safe
   area). Real round-screen touch/clip verification stays with the later
   device gate.
4. **Copy + evidence fixes**: the Uploading screen no longer tells the user to
   keep the watch on — it states "The screen stays awake automatically." The
   invalid parent baseline manifest is superseded by the recovery directory
   (see above); this candidate.2 directory is new and candidate.1 is untouched.

## Deviations declared (unchanged from candidate.1)

- The preview images are hand-authored layout renders (SVG, 480×480, circular
  clip, generated from `WatchUiMetrics`), NOT photographic screenshots: no
  Android emulator or physical watch is attached to this machine. Real-device
  visual/interaction acceptance is deferred to the PM/user device session.
- Baseline screenshots have the same limitation; the baseline is pinned by the
  parent Git SHA plus the recovered per-file manifest.

## Integrity

`SHA256SUMS` covers every file in this directory (previews + this README after
final write). No real token, IP, port, or device information appears anywhere —
placeholders only (`192.168.x.x`, `<PORT>`, `A1B2••••••••7890`).
