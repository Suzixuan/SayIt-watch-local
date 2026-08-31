# Watch UI candidate — 0.2.0-dev.2-candidate.4

- Status: `candidate` (fourth freeze; **supersedes candidate.3 — candidate.3 is
  rejected**: full-size re-review found Pending-page bottom clipping, a missing
  runtime TimeText on Recording, and three upload overlays whose translucent
  dim showed the underlying Ready text, so glyphs overlapped and "Upload
  failed" was barely readable. candidate.1/2/3 are preserved untouched as audit
  records of the Repair 2/3/4 findings.)
- Candidate version: Watch `0.2.0-dev.2`, `versionCode = 2` (unchanged — a
  behavior/visual repair of the same candidate, not a version bump)
- Parent: `0.2.0-dev.2-candidate.3` (rejected this round); inherited from it —
  responsive fraction/weight layout, long-copy Chip actions, baseline recovery,
  Pending Retry, discard protection, and the Repair-3 atomic coordinator. The
  PM-verified baseline manifest `../0.2.0-dev.1-baseline-recovery.1/` and all
  earlier candidates remain untouched.
- Product boundary: a Wear OS app (not a watch face). The Watch records one
  whole WAV and uploads it; SayIt on Windows performs ASR/History/Paste.

## What changed since candidate.3 (PM Repair 4 findings → fixes)

### 必修 1 — completion gate supports consecutive generations, atomic with Cancel

- `RecordingRequestLatch` now models the whole lifecycle in ONE atomic state:
  `Idle -> Active(generation) -> Settled | Cancelled`. `begin`, `cancel` and
  `settle` all CAS that single state, so Cancel and completion are one atomic
  transition — no interleaving where `settle` reads `cancelled=false`, Cancel
  writes `true`, and `settle` still succeeds.
- `begin()` always creates a fresh generation that can settle independently;
  `settledGeneration` no longer sticks at an old value (the Repair-3 bug: after
  gen1 settled, gen2's `compareAndSet(-1, gen2)` always failed and the second
  recording was silently dropped). gen1 success then gen2 success is tested.
- I/O no longer touches the `RecordingSession`: the `Dispatchers.IO` block only
  produces a `RecordingOutcome` (Completed/Failed); back on the main coroutine
  the settle gate decides before any session/UI/vibration/upload write. Cancel
  vs completion product-state writes can no longer race across threads.
- Tests drive the SAME `RecordingOutcomeCoordinator.applyOutcome` the ViewModel
  uses (not bare Atomics): gen1→gen2 consecutive settle, gen1 late can't occupy
  gen2, cancel-vs-completion single terminal state (cancel wins → no WAV /
  Failure / vibration / upload), and two consecutive normal rounds each
  complete and upload exactly once — including real cross-thread races.

### 必修 2 — candidate.4 is readable at full size: no overlapping or clipped glyphs

- **Opaque overlays**: `OverlayScaffold` now uses a fully opaque background
  plus a solid rounded content panel, so Uploading / Upload failed / Uploaded
  never show the underlying Ready text, controls, or TimeText. No overlapping
  glyphs at any size; "Upload failed" is fully readable.
- **One merged Pending action**: the former `Pending upload` badge + duplicate
  `Retry upload` chip are replaced by one obvious, clickable, ≥48 dp
  `Pending upload — Retry` chip. The retained WAV and the explicit discard
  protection are unchanged.
- **Runtime TimeText everywhere it exists**: Config, Ready, and Recording each
  render their own `TimeText` (the global one was removed from the root Box, so
  previews and runtime agree element for element); overlays hide everything
  underneath with the opaque panel, as the previews show.
- **Same first-screen/scroll semantics**: Ready (incl. Pending variant) and
  Recording scroll; the previews render the same un-scrolled first screen, with
  Record / Retry / status fully inside the round safe area — nothing half-cut.
- candidate.1/2/3 are not modified.

## Deviations declared (unchanged)

- The previews remain hand-authored layout renders (SVG), NOT photographic
  screenshots: no emulator or physical watch is attached to this machine.
  Real-device visual/interaction acceptance is deferred to the PM/user device
  session.
- Mapping note: the previews draw the logical dp layout on a nominal
  1 dp = 1 px canvas. That is a reference scale, not the Galaxy Watch 7's
  physical pixel grid — widths are fractions of the screen, so they scale with
  the real density; the previews are layout-faithful, not pixel-identical to a
  device photo.

## Integrity

`SHA256SUMS` covers every file in this directory. No real token, IP, port, or
device information appears anywhere — placeholders only (`192.168.x.x`,
`<PORT>`, `A1B2••••••••7890`).
