# Watch UI baseline — 0.2.0-dev.1 (accepted-parent)

- Status: `accepted-parent`
- Parent version: Watch `0.2.0-dev.1`, `versionCode = 1` (Delivery 1A accepted state)
- Parent Git SHA: `5da1a32279b372810d83504aca2021b0c8146763`
  (branch `codex/review-watch-pipeline`; the watch sources are unchanged since the
  Delivery 1A acceptance)
- Recorded before any 0.2.0-dev.2 UI modification, per the Z3 Repair 1 Part B rules.

## Key resource SHA-256

See `SHA256SUMS` (computed over the pre-modification sources at the parent SHA):
`watch/app/build.gradle.kts`, `AndroidManifest.xml`, `MainActivity.kt`,
`RecordingScreen.kt`, `RecordingViewModel.kt`, `strings.xml`.

## Baseline screenshots

Not capturable in this environment: there is no Android emulator or physical
Galaxy Watch attached to the machine that produces this commit, and no
emulator system image is installed. The baseline identity is therefore
established by the parent Git SHA plus the per-file SHA-256 manifest above —
both pin the exact 0.2.0-dev.1 sources this candidate evolves. Real-device /
emulator screenshots of the baseline remain available on demand: checking out
the parent SHA and building the 0.2.0-dev.1 debug APK reproduces the accepted
parent exactly.

## What 0.2.0-dev.1 looks like (functional summary)

A single scrollable screen combining developer configuration (PC IP / Port /
Dev Token rows opening Wear keyboard dialogs), a Start/Stop/Send button group,
and a status line. State names shown: Idle / Ready — 16 kHz verified /
Recording (ms) / Recorded / Uploading… / Uploaded / transport verified /
failure text. Send is a manual button; the token is shown in clear text in the
settings row.
