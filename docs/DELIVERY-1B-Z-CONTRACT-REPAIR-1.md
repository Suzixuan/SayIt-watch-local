# Delivery 1B — Z1 Contract Repair 1

Status: PM contract review NO-GO. Documentation repair only; product implementation remains locked.

Branch: `codex/review-watch-pipeline`

Review base: Z submission `525f0b2d947f0d57abea820ab6b611971ddea72d`

## Goal

Correct the implementation contract before any product code is written. Update `docs/DELIVERY-1B-PROVIDER-CONTRACT.md` and the two authority documents only.

## Required corrections

### 1. Correct the real per-run Provider lifecycle

The submitted contract freezes the order as `connect -> isReady -> start`, but the current mic run checks `provider.isReady()` first, then calls `provider.connect(buildProviderCallbacks())` during setup, then calls `provider.start(...)`.

The proposed external sequence currently jumps from reservation to `sendAudio* -> stop` and does not explicitly perform both of these indispensable steps:

- `await provider.connect(buildProviderCallbacks())` so the current run receives the existing guarded callbacks;
- `provider.start(StartOptions)` and verification of its boolean result before any PCM is sent.

Revise every lifecycle table and sequence diagram. Freeze the external order, including rechecks after each await:

1. synchronous reservation and runId creation;
2. asynchronous context/probe preparation and `provider.connect(existing callbacks)`;
3. verify the same reservation/run is still current and the Provider is still ready;
4. `provider.start({ runId, disableAi: true, systemPrompt: undefined, ...existing metadata })`;
5. only after successful start: transition/feed `sendAudio*`;
6. `provider.stop({ disableAi: true, ... })`;
7. existing callbacks, History and Paste complete the run.

Specify rollback for context capture, connect, start, PCM read, send, and stop failures. No PCM may reach a Provider before `start` succeeds.

### 2. Separate synchronous reservation from asynchronous native focus/context capture

`bridge.getRecordingContext(false)` returns a Promise. It cannot be completed inside a fully synchronous `tryReserveExternalRun(...): boolean` as the submitted design claims.

Freeze a truthful two-phase API:

- Phase A is synchronous and atomic: check idle/locks/provider-ready, allocate runId, set `externalReservation`, and synchronously block PTT through the existing start guard.
- Phase B is asynchronous while that reservation remains held: invoke the existing one-shot target/context capture exactly once and store its resolved probe/app context in the reserved run.

Choose and document the HTTP result when native capture fails or times out. Do not silently fall back to a later live probe, because that would violate the upload-arrival focus snapshot.

The Rust admission response must not be accepted until Phase B has either completed successfully or failed closed.

### 3. Close both sides of every abort path

The submitted save-failure path releases only the Rust gate. The JS `externalReservation` and active runId would remain allocated, permanently blocking PTT.

Define one correlated abort operation/event that clears both:

- Rust `AdmissionGate` reservation;
- JS `externalReservation`;
- active runId and any pending context/PCM promise;
- overlay/escape state if it was touched;
- Provider session if `connect` or `start` had already occurred.

Cover at least: admission timeout, context failure, durable-save failure, PCM-read failure, provider-connect failure, provider-start false/throw, provider-stop false/throw, WebView reload, and stale/mismatched request ID.

Every release/abort must be requestId + runId conditional so a stale cleanup cannot clear a newer run.

### 4. Remove the unsafe 30-second JS watchdog

Thirty seconds is not "well above" the existing processing lifetime: current processing timeouts can exceed 30 seconds and late-final handling adds another 15-second grace window. A watchdog owned by the WebView is also lost when the WebView itself reloads.

Replace it with a Rust-owned, correlated lease/recovery rule that cannot release a healthy current run. State the upper bound and derive it from the existing maximum recording/processing/grace behavior, or use explicit stage acknowledgements plus a conservative fail-safe lease.

The design must explain recovery after WebView reload without assuming a dead JS timer will run.

### 5. Do not return 201 before a reliable post-save handoff

The submitted contract admits a "lost post-201 handoff" in which the Watch displays success but no ASR run exists. That is incompatible with this delivery's reliable closed-loop goal.

After durable save, require a bounded correlated acknowledgement from the WebView that:

- the saved PCM was obtained and validated for the reserved request;
- Provider callbacks were connected;
- `provider.start` succeeded and the external run owns the reservation.

Only then may the receiver return `201`. A timeout/failure returns a non-2xx response so the Watch retains its WAV for retry, and both sides abort the reservation. `201` still means transport/admission success, not transcription completion.

### 6. Freeze the audio accounting needed by existing History and timeout logic

The external path must explicitly populate the same run fields used downstream:

- `recordedChunks` with raw PCM copies used by `saveRecordingAudio`;
- `audioSentSamples` from exact i16 sample counts;
- `wallTimeAtStopSec`/external duration using sample-derived duration rather than a fake wall clock;
- audio duration and processing timeout inputs;
- current prompt/app/probe context fields required by `buildProviderCallbacks()` and `processFinalResult`.

Do not claim History audio reuse until these assignments are specified. Do not call mic-only `stopCapture()` for an external run. Identify the smallest shared finalization helper or additive external stop path without duplicating ASR, History, Paste, or Provider implementations.

### 7. Resolve the binary IPC decision before declaring unconditional MATCH

The submitted design depends on a Tauri v2 raw binary response but lists it as unverified. Freeze one of these outcomes:

- source/API evidence plus a minimal isolated implementation test proving the pinned Tauri version returns an `ArrayBuffer` without base64; or
- mark the contract `CONDITIONAL MATCH` and make the binary IPC spike the first stop/go gate of the implementation slice.

Do not authorize an undocumented base64 fallback for the full 10 MiB body.

## Required updated sequences

Update the accepted, busy, abort, reload, and successful-final sequences. Each must show:

- Rust gate state;
- JS reservation + runId state;
- context capture;
- Provider `connect/start/sendAudio/stop` order;
- durable-save and post-save acknowledgement;
- exact HTTP response timing;
- correlated cleanup owner.

## Allowed files

- `docs/DELIVERY-1B-PROVIDER-CONTRACT.md`
- `HANDOFF.md`
- `PROJECT_PROGRESS.md`

No product source, tests, dependencies, generated files, build artifacts, WAVs, APKs, tokens, or logs.

## Return package

- New commit SHA on `codex/review-watch-pipeline`.
- Changed-file list proving only the three allowed documentation files changed.
- Revised verdict: `MATCH`, `CONDITIONAL MATCH`, or `MISMATCH`, supported by the corrected evidence.
- A checklist mapping all seven repairs above to exact contract sections.

Push and stop. Delivery 1B implementation remains unauthorized until PM accepts the revised contract.
