# Watch UI candidate — 0.2.0-dev.3-candidate.1

- Status: `candidate`; this is a source/build candidate, **not** a Galaxy Watch
  visual or interaction acceptance.
- Runtime version: `0.2.0-dev.3`, `versionCode = 3`.
- Parent: `0.2.0-dev.2-candidate.4` (preserved untouched). The parent remains
  audit evidence; this candidate replaces its debug-shaped visual direction.
- Formal runtime files changed only after the user explicitly authorized this UI
  redo. No recording, upload, state-machine, receiver, provider, ASR, Paste,
  network, dependency, or lockfile code was changed.

## Frozen direction

The design specification is [DESIGN-SPEC.md](DESIGN-SPEC.md). It records the
source-level state-to-UI mapping and responsive constraints implemented in
`RecordingScreen.kt`:

- Ready has SayIt branding, transport state, refresh/settings, and an 88–96 dp
  blue microphone control with a Canvas microphone icon.
- Recording shows a red recording indicator, sample-derived `mm:ss`, red text
  Stop action, and a separate cancel/discard action.
- Configuration uses dark field cards, a masked token and blue Save & Apply.
- Uploading, failure and success are opaque panels with semantic Canvas icons;
  Pending is an amber retry card and preserves the microphone path.

## Integrity and review gate

`SHA256SUMS` records the two frozen design documents (the manifest is excluded
from its own checksum list, matching the existing candidate convention). The
project runtime source is intentionally not copied here; its exact changed
paths are listed in the specification. No real token, address, port, device
serial, WAV, or APK is present.

The next gate is a real Galaxy Watch 7 install and state-by-state screenshot
review. SVG mockups and source review are not a substitute for that device gate.
