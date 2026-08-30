# Watch UI candidate — 0.2.0-dev.2-candidate.3

- Status: `candidate` (third freeze; supersedes `candidate.2` for review —
  candidate.1 and candidate.2 are preserved untouched as audit records of the
  Repair 2 / Repair 3 findings)
- Candidate version: Watch `0.2.0-dev.2`, `versionCode = 2` (unchanged — a
  behavior/evidence repair of the same candidate, not a version bump)
- Parent: `0.2.0-dev.1` — the PM-invalidated manifest in
  `../0.2.0-dev.1-baseline/` stays preserved and superseded; the PM-verified
  correct manifest lives in `../0.2.0-dev.1-baseline-recovery.1/` (re-verified
  against the parent Git blobs this round, see below)
- Product boundary: a Wear OS app (not a watch face). The Watch records one
  whole WAV and uploads it; SayIt on Windows performs ASR/History/Paste.

## What changed since candidate.2 (PM Repair 3 findings → fixes)

1. **Cancel is fail-closed for both late outcomes**: the completion decision is
   now the atomic `RecordingRequestLatch.settle(generation)` — the single
   coordinator the ViewModel's success AND catch branches call (exactly-once
   per generation; atomic fields give real cross-thread visibility, and
   `recordingActive` is `@Volatile`). A Cancel drops a late normal return and
   a late exception alike: the session stays READY, no WAV, no upload, no
   FAILURE, no stop vibration. Tests drive `settle` itself — including two
   real cross-thread tests (a canceller thread's visibility via latch-join,
   and concurrent settles from two threads claiming exactly once).
2. **Responsive layout, no px/dp conflation**: `WatchUiMetrics` no longer
   contains any 480 dp width assumption. Wide chips fill the padded parent
   (`fillMaxWidth(1f)`), Retry/Later are two `weight(1f)` chips with a dp gap,
   heights/padding/main action stay in dp. The Ready column scrolls when
   content exceeds the logical height. Long-copy actions left the circular
   buttons: Grant Mic, dialog OK/Cancel, and Keep-it/Discard-&-record are now
   proper chips. `WatchUiMetricsTest` pins the responsive policy (no absolute
   screen-width arithmetic).
3. **candidate.3 previews are element-for-element faithful**: every screen now
   shows ALL runtime elements in runtime order — Ready (and the Pending
   variant) includes the transport line, the `Check / Refresh` chip, the
   Settings link, the Pending badge + `Retry upload` chip (when pending), the
   Record circle with caption, and the bottom `StatusLine`; Config, Recording
   and the three overlays match likewise, with the overlays drawing the dimmed
   Ready content underneath exactly as the Compose overlay does. The
   generator asserts programmatically that no text exceeds its container
   (monospace advance estimate) — the candidate.2 badge overflow is gone.

## Deviations declared (unchanged, and one mapping note)

- The previews remain hand-authored layout renders (SVG), NOT photographic
  screenshots: no emulator or physical watch is attached to this machine.
  Real-device visual/interaction acceptance is deferred to the PM/user device
  session.
- Mapping note (Repair 3 finding): the previews draw the logical dp layout on
  a nominal 1 dp = 1 px canvas. That is a reference scale, not the Galaxy
  Watch 7's physical pixel grid — widths are fractions of the screen, so they
  scale with the real density; the previews are layout-faithful, not
  pixel-identical to a device photo.

## Integrity

`SHA256SUMS` covers every file in this directory. No real token, IP, port, or
device information appears anywhere — placeholders only (`192.168.x.x`,
`<PORT>`, `A1B2••••••••7890`).
