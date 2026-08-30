# Delivery 1B — Z1 Provider Contract Gate

Status: waiting for the user to transfer this package to colleague Z.

This is the first strictly bounded slice of Delivery 1B. It is an evidence and architecture gate, not implementation authorization.

## Project access

- Private repository: `https://github.com/Suzixuan/SayIt-watch-local`
- Accepted Delivery 1A branch: `codex/review-watch-transport`
- Accepted Delivery 1A PM commit: `fe096d7c41c335155db8c8300a89f64b47f75fe1`
- Z working branch: `codex/review-watch-pipeline`
- The Z branch is created from the accepted Delivery 1A state. Verify the branch ancestry before doing any work.
- Never push to `main`, `crosswk/SayIt`, or a public repository. Never force-push, merge, tag, release, or package an installer.

## Current verified state

Delivery 1A is PM-accepted:

`Galaxy Watch 7 -> native 16 kHz / 16-bit / mono WAV -> authenticated debug HTTP -> Windows receiver -> received_watch.wav`

The existing AudioRelay -> SayIt -> ASR -> History/Paste path is user-reported VERIFIED and must not be retested, rewritten, or refactored.

## Goal of this slice

Read the current source and freeze the real contract required to feed a validated Watch WAV into the existing SayIt pipeline without creating a second ASR path.

Create exactly one deliverable:

`docs/DELIVERY-1B-PROVIDER-CONTRACT.md`

Do not implement the external-audio entry in this slice.

## Required source evidence

Record file paths, symbols, and narrow line references for each conclusion:

1. How the singleton `RecorderOrchestrator` is initialized and how it creates, identifies, finishes, cancels, and times out a run.
2. The exact `TranscriptionProvider` contract and the real order/return semantics of:
   - `connect`
   - `isReady`
   - `start`
   - `sendAudio`
   - `stop`
   - `cancel`
3. Confirm or reject this proposed audio contract from source evidence:
   - raw PCM only, without a WAV header;
   - 16,000 Hz;
   - mono;
   - little-endian signed i16;
   - `ArrayBuffer` chunks;
   - duration derived from `bytes / 2 / 16000`.
4. How `onASR`, `onFinal`, `onDone`, and `onError` are associated with the active run and how stale/canceled results are rejected.
5. How a normal final result reaches existing audio History and `PasteService`, including the current insertion probe/focus snapshot behavior.
6. Exact ready, busy, processing, cancellation, late-final, timeout, and text-insertion states that an external run must respect.
7. Which existing setting disables AI cleanup and how an external Watch run can freeze `disableAi: true` without deleting or modifying AI features.

## Required boundary design

The contract document must propose the smallest race-safe boundary between the blocking Rust Watch receiver and the frontend Orchestrator. Address all of these explicitly:

- `POST /api/watch/audio` must return `409` when the Orchestrator is not idle or the active Provider is not ready.
- Admission must be atomic enough that a PTT run cannot start between a successful busy/ready check and reservation of the external run.
- The receiver must not report `201` until its existing WAV validation and durable save contract succeeds and the external run has been accepted for processing.
- The validated WAV `data` payload must reach the Orchestrator as raw PCM; do not base64 or copy it more than necessary without documenting the cost.
- PC-internal chunking may reuse the current capture chunk size, but Watch upload remains whole-file HTTP and is not streaming.
- The focus/insertion probe is captured exactly once at Watch upload admission. Do not add Target Manager, Focus Tracking, or Target Lock.
- The run must reuse the active Provider plus existing callbacks, History, saved audio behavior, and Paste path.
- A failed admission must leave the Watch WAV retryable. No partial or duplicate run may remain.
- Define how request ID and errors cross Rust/TypeScript without logging transcript text, PCM, tokens, editor text, or secrets.

Include a short sequence diagram or ordered event list for:

1. accepted request;
2. busy/not-ready request;
3. cancellation or failure after admission;
4. successful `onFinal -> History -> Paste` completion.

## Stop conditions

Stop and report to PM without implementation if any of these is true:

- the real Provider input is not raw 16 kHz mono little-endian i16 PCM;
- a normal run cannot be created without bypassing or substantially refactoring `RecorderOrchestrator`;
- `onASR/onFinal` cannot safely reuse the current History/Paste path;
- exact `409` admission requires a new second Provider, direct `local_transcribe`, or duplicated ASR/History/Paste logic;
- the proposed bridge introduces a focus tracker, persistent target lock, Watch streaming, or release HTTP receiver.

## Allowed files

- Read any source needed for contract evidence.
- Write only:
  - `docs/DELIVERY-1B-PROVIDER-CONTRACT.md`
  - `HANDOFF.md` with a concise Z evidence entry
  - `PROJECT_PROGRESS.md` to mark the Z submission as awaiting PM review

No product source, tests, dependencies, lockfiles, build configuration, generated files, APKs, WAVs, logs, tokens, or local paths may change in Z1.

## Required return package

- Branch and commit SHA.
- Changed-file list proving only the three allowed documentation files changed.
- The complete contract conclusion: MATCH or MISMATCH.
- The proposed minimal implementation file list for the next Z slice.
- Risks and unanswered questions.

Push the Z1 documentation commit to `codex/review-watch-pipeline`, then stop. PM must approve the contract before any Delivery 1B implementation begins.
