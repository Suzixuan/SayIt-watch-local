# Delivery 1B — Z3 PC External WAV Ingress Task

## Status and authority

- Owner: colleague Z, after the user personally transfers this package.
- Branch: `codex/review-watch-pipeline`.
- Review base: the PM acceptance commit that adds this task.
- Authority: `docs/DELIVERY-1B-PROVIDER-CONTRACT.md` is frozen as **MATCH**. If source reality conflicts with that contract, stop and report the exact conflict; do not invent a second path.
- Goal: connect the already validated debug Watch receiver's WAV to the existing `RecorderOrchestrator` and active Provider lifecycle, then reuse existing callbacks into History and Paste.
- This is a **PC-only implementation slice**. Do not modify the Watch app or its UI.

## Required outcome

For one accepted debug HTTP upload:

`validated Watch WAV → header-stripped raw PCM → existing RecorderOrchestrator run → active Provider connect/start/sendAudio/stop → existing onASR/onFinal → existing History → existing Paste`

`201 Created` may be returned only after durable save, successful admission/preparation, raw PCM retrieval/validation, Provider callback connection, and successful Provider `start`. It remains transport/run-start acknowledgement, never transcription-complete status.

## Allowed files and surfaces

1. New `client/src-tauri/src/watch_receiver/admission.rs`.
2. Modify `client/src-tauri/src/watch_receiver/server.rs`.
3. Modify debug-only registration/wiring in `client/src-tauri/src/main.rs`.
4. New `client/src/services/watchIngress.ts`.
5. Additive modification to `client/src/services/recorder/RecorderOrchestrator.ts`.
6. Thin delegation only in `client/src/services/recorder.ts`.
7. Focused Rust and Vitest tests for these surfaces.
8. Authority documents only for factual result/evidence updates.

Any additional file requires a written necessity finding before modification. Do not change dependency versions or lockfiles.

## Frozen implementation contract

Implement the sequence and failure semantics in Provider Contract §§B–C exactly:

1. Rust admission gate is `Idle` or `Reserved { requestId, reservedAt }`, with request-correlated oneshots, a Rust-owned 300 s lazy lease, stale-ID rejection, idempotent conditional release, and boot/reload reconciliation through `watch_gate_state()`.
2. Admission is two-phase: synchronous Phase A `tryReserveExternalRun(requestId)` performs all busy/ready checks, allocates the normal run ID, reserves it, and captures the current insertion target exactly once; asynchronous Phase B `prepareExternalRun(requestId)` performs the bounded native recording-context capture. Failure is fail-closed and releases both sides.
3. Admission occurs before replacing `received_watch.wav`; a `409` or preparation failure must not overwrite the last accepted file.
4. Use the Z2-proven raw Tauri binary response for `watch_read_reserved_pcm(requestId)`. Strip the WAV container exactly once in Rust. No base64, JSON number array, WAV header in `sendAudio`, or alternate Provider.
5. Use the existing active Provider per run: preflight `isReady()` then `connect(buildProviderCallbacks()) → checked start() → sendAudio* → stop()`. No PCM may be sent before `start()` succeeds.
6. Feed exact even-length PCM slices (4096 samples is the recommended target). For every actual slice: `chunkSamples = chunk.byteLength / 2`; append that exact copied slice to `recordedChunks`; add the exact `chunkSamples` to `audioSentSamples`.
7. Before `provider.stop()`, assert `audioSentSamples === admission.sampleCount` and total recorded chunk bytes equal the raw PCM byte length. Mismatch takes the correlated `sample_accounting_mismatch` abort and must never reach `stop`, processing, History, or Paste.
8. Derive `wallTimeAtStopSec`, `pttHoldMs`, processing-timeout input, context duration, and History duration only from final exact `audioSentSamples / 16000`.
9. Extract the smallest shared `finalizeRecording` tail from the existing microphone `stopRecording`; preserve microphone behavior. External audio must not call mic-only `stopCapture()`.
10. Force AI cleanup off only for this external run (`disableAi: true`, no system prompt) without changing or persisting the user's AI setting.
11. Use the existing `onASR/onFinal` callbacks, History, and Paste path exactly once. Do not call `local_transcribe` directly.
12. One correlated `abortExternalRun` operation must close both Rust and JS halves for every Provider Contract §B.6 trigger: Phase-B failure/timeout, durable-save failure, PCM read/metadata failure, connect failure/timeout, start false/throw/timeout, start-ack timeout, sample-accounting mismatch, cancel/error during the run, and WebView reload/reconciliation. Correlation is conditional on both request ID and run ID.

## Required HTTP behavior

- Busy Orchestrator, held reservation, or Provider not ready: `409`; Watch retains WAV for retry.
- Context-capture/start preparation failure: non-2xx (`500` per the frozen contract); both sides abort.
- `201`: only after `watch_run_started(requestId)` acknowledgement proves the Provider session owns the run and `start()` succeeded.
- Never report “transcribed” or “pasted” in the HTTP response.

## Explicitly forbidden

- Watch app/UI work, including `docs/WATCH-UI-Z-HANDOFF.md` implementation.
- New or copied ASR/Whisper/Provider, direct `local_transcribe`, Provider implementation changes.
- History/store, PasteService, text-insertion implementation, AI feature/settings changes.
- Target Manager, Focus Tracking, Target Lock, VAD changes, Streaming, WebSocket, Opus, discovery, pairing, mDNS, QR, background recording, wake words, Home-button behavior.
- Update, backup, storage-security or release-HTTP changes.
- Broad `RecorderOrchestrator` refactor, compatibility fallback, new dependency, or accepted-lock drift.
- Real Watch ten-run claims in this slice.

## Automated acceptance evidence

Return commands, exit codes, counts, and concise outputs for:

1. Focused Rust tests: gate accept/reject, exact correlation, double resolve/abort, stale IDs, 300 s lease reclamation, reload state, admission-before-save, `409` non-overwrite, post-save ack before `201`, ack timeout/non-2xx, malicious/truncated WAV regression, and every Provider Contract §B.6 abort row.
2. Focused Vitest: synchronous reservation atomicity against PTT, Provider ready/busy, Phase-B failure, lifecycle order, no PCM before successful start, exact final short chunk, byte-for-byte `recordedChunks`, mismatch abort before stop, context/focus snapshot once, AI forced off per run, History and Paste exactly once, cancellation/error cleanup, and microphone-path behavior after finalization extraction.
3. Full `client` Vitest suite and TypeScript/Vite build.
4. Full `client/src-tauri` Rust tests.
5. Rust release build plus receiver-marker scan proving Watch receiver/admission/commands remain debug-only.
6. `git diff --check`, changed-file list, and a statement that no token, WAV, executable, build cache, model, APK, or local path was committed.

The Z2 harness need not be rewritten. It may be rerun as a regression check; its accepted pins and raw-binary path must remain unchanged.

## Stop conditions

Stop and report to PM without expanding scope if:

- any frozen Provider lifecycle/callback claim differs from current source;
- the required existing callback path cannot reach both History and Paste without editing forbidden files;
- raw IPC no longer resolves to identical `ArrayBuffer` bytes on the accepted pins;
- the two-phase reservation cannot exclude microphone PTT races;
- any failure can return `201` before successful Provider start or can leave one side reserved;
- exact sample/byte accounting cannot be asserted before `provider.stop()`;
- a required change falls outside the allowed paths.

## Completion boundary

Submit source, tests, build evidence, commit SHA, changed-file list, and unresolved risks, then stop. Do not implement Watch UI and do not claim Delivery 1B VERIFIED. PM must independently review this slice before unlocking the real Galaxy Watch → ASR → History → Paste ten-run acceptance.
