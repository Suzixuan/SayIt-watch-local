# SayIt Watch Transport handoff

## Goal

Verify the smallest real transport path first:

`Galaxy Watch 7 -> 16 kHz PCM/WAV -> debug HTTP over Wi-Fi -> Windows SayIt -> received_watch.wav`

Only after PM manually accepts the received audio may the project unlock Delivery 1B and reuse the existing SayIt Provider/History/Paste pipeline.

## Source authority

- Baseline archive: `SayIt-local-src-2026-08-29.zip`
- Baseline SHA-256: `E492F71CF073E2012E0CA61AFD865F9A773B4E321A8C9853D29C4FCD62E889B3`
- Inherited project handoff: `HANDOVER.md`
- Upstream public repository: `crosswk/SayIt` at inherited commit `bad38da`
- Private working repository: `https://github.com/Suzixuan/SayIt-watch-local`
- Accepted Delivery 1A branch: `codex/review-watch-transport`
- Accepted PM commit: `fe096d7c41c335155db8c8300a89f64b47f75fe1`
- Delivery 1B review branch for colleague Z: `codex/review-watch-pipeline`
- Baseline import commit: `18400ad82d7f5a7009a47622248643022472650f`
- Large installer and build caches were deliberately excluded from Git history.

## Confirmed decisions

- Delivery 1A and 1B are strictly sequential.
- Delivery 1A uses a manually supplied random development token; formal six-digit pairing is deferred.
- Cleartext HTTP is permitted only in Android debug builds. Release builds must deny it, and the Windows HTTP receiver must not start in release builds.
- The Windows receiver binds exactly one configured RFC1918 LAN IPv4, never `0.0.0.0`.
- Galaxy Watch 7 must prove native 16,000 Hz initialization. Delivery 1A must fail visibly rather than resample or fall back to 44.1/48 kHz.
- A successful upload means only durable transport. The receiver returns `201 Created`; it must not claim transcription success.

## Current state

- The user reports the existing AudioRelay -> SayIt -> ASR -> text-output chain as VERIFIED. It is out of scope for re-test or refactor.
- The isolated source baseline and PM documents are prepared and pushed to the private `main` branch.
- Delivery 1A was repaired, independently verified, and PM-accepted on `codex/review-watch-transport`.
- The user confirmed the real received WAV by human playback.
- Delivery 1B Provider contract is PM-accepted as **MATCH**. Repair 1's seven architecture corrections, Repair 2's exact final-chunk accounting, and the Z2 packaged-Windows raw binary-IPC proof are frozen.
- Z2 (stage 6A) is independently PM-accepted. The next and only unlocked slice is the PC-only external WAV ingress task in `docs/DELIVERY-1B-Z-EXTERNAL-INGRESS-TASK.md`; the real Watch closed loop and ten-run acceptance remain locked.
- The PM-reviewed Watch UI direction is frozen in `docs/WATCH-UI-Z-HANDOFF.md` and is now explicitly unlocked only as Part B of `docs/DELIVERY-1B-Z3-REPAIR-1.md`, after Part A Repair passes.
- Updated user sequencing decision (2026-08-29): Z3 Repair 1 and the frozen Watch UI candidate are one colleague-Z package, strictly ordered Repair first and UI second. Real-device closure is still a later PM gate.

## Colleague Z quick start

- Repository: `https://github.com/Suzixuan/SayIt-watch-local` (private; obtain access from the user, never exchange credentials in project files or chat evidence).
- Read first: `AGENTS.md`, `HANDOFF.md`, `PROJECT_PROGRESS.md`, then `docs/DELIVERY-1B-Z-EXTERNAL-INGRESS-TASK.md`.
- Working branch: `codex/review-watch-pipeline`, based on accepted Delivery 1A.
- Z3 is PC-only external ingress. Change only the task's allowed files; do not modify Watch UI, Provider implementations, History, Paste, AI, target/focus behavior, release HTTP, dependencies, or accepted locks.
- Z must push the requested evidence and stop; no merge, tag, release, installer, or public-upstream push.

## Delivery 1B Z1 contract evidence (Colleague Z, 2026-08-29) — revision 1, superseded by Repair 1

> Superseded: this entry describes the original submission (`525f0b2`), whose MATCH verdict, `connect → isReady → start` order claim, and single-side release design were corrected by PM Repair 1. Retained for audit; the authoritative record is the revision-2 entry below and the PM review in between.

- Deliverable: `docs/DELIVERY-1B-PROVIDER-CONTRACT.md` (documentation only; base commit `5cc14b4c8658920cc9ca9b21ab1dcaf878f1a136` on `codex/review-watch-pipeline`). No product code, tests, dependencies, or build configuration changed.
- Conclusion: **MATCH**. All six proposed audio-contract clauses are confirmed from source evidence on both sides — capture (`client/src/services/audio.ts`: 16 kHz target, mono, Int16 conversion, `bytes/2/16000` duration) and receiver validation (`client/src-tauri/src/watch_receiver/wav.rs`: PCM format 1, mono, 16,000 Hz, 16-bit, block-align/byte-rate checks).
- Contract freeze: `TranscriptionProvider` real call order is `connect → isReady → start → sendAudio* → stop → (onASR? → onFinal → onDone?) | onError` with runId generation guards (`RecorderOrchestrator.ts:126-135, 258-275, 900-1154`); a normal final reaches History first (`addHistory` in `processFinalResult`) then Paste with the probe snapshot captured exactly once at run start; `aiEnabled` is the AI-cleanup setting and an external run can freeze `disableAi: true` per-run via existing `StartOptions`/`StopOptions` fields without deleting AI features.
- `409` admission boundary design: a Rust `AdmissionGate` (`Idle`/`Reserved{requestId}`) shared between the blocking receiver thread and three new debug-only Tauri commands; the WebView performs a fully synchronous check-and-reserve (`tryReserveExternalRun`) inside one JS macrotask, so a PTT run cannot start between the busy/ready check and the reservation (and vice versa via one added guard clause in `startRecording`). Admission precedes the durable save, so a `409` never overwrites `received_watch.wav`; `201` is still returned only after durable save plus acceptance; PCM reaches the Orchestrator as raw header-stripped bytes via binary IPC (no base64); every terminal path releases the gate through the existing `finishRun` hook.
- All five task stop-conditions evaluated as not triggered; minimal implementation file list, four ordered event sequences, and nine risks/open questions are recorded in the contract document for the PM's decision.

## Delivery 1B Z1 PM review (2026-08-29) — NO-GO

Scope control passed: Z changed only `docs/DELIVERY-1B-PROVIDER-CONTRACT.md`, `HANDOFF.md`, and `PROJECT_PROGRESS.md`; no product code changed.

The contract is not frozen yet:

- It states `connect -> isReady -> start`, while the real run first checks readiness and then reconnects the existing guarded callbacks before `start`. The external sequence omits explicit successful `provider.connect(buildProviderCallbacks())` and `provider.start(...)` before PCM.
- It labels `tryReserveExternalRun` fully synchronous while also claiming it completes `bridge.getRecordingContext(false)`, which is asynchronous.
- Durable-save and other Rust-side aborts release only the Rust gate, leaving the JS reservation/run allocated.
- The proposed 30-second JS watchdog is shorter than valid processing plus late-final lifetimes and cannot execute after a WebView reload.
- The design explicitly permits a lost post-201 handoff, leaving the Watch showing success when no ASR run exists.
- It does not freeze `recordedChunks`, `audioSentSamples`, sample-derived duration, context fields, or a non-mic stop path required by existing History and timeout behavior.
- Raw binary Tauri IPC remains an unverified dependency despite the unconditional `MATCH` verdict.

Decision: Delivery 1B implementation remains locked. Colleague Z receives the documentation-only repair package `docs/DELIVERY-1B-Z-CONTRACT-REPAIR-1.md` and must stop after resubmission.

## Delivery 1B Z1 contract revision 2 (Colleague Z, 2026-08-29) — resubmitted after Repair 1

- Deliverable: `docs/DELIVERY-1B-PROVIDER-CONTRACT.md` revision 2 (documentation only; review base `8240d4a`). No product code, tests, dependencies, or build configuration changed. A §G checklist maps all seven repair items to exact contract sections.
- Verdict: **CONDITIONAL MATCH**. The single condition is the Tauri v2 raw binary-IPC spike for the PCM handoff, frozen as the first stop/go gate of the implementation slice (§B.4, §E row 0). Pinned-version source evidence is recorded (`tauri 2.10.3`: `InvokeResponseBody::Raw`, `Response::new`, octet-stream dispatch at `src/ipc/protocol.rs:344`; injected `scripts/ipc-protocol.js:50-53` consumes non-JSON responses via `response.arrayBuffer()`; `@tauri-apps/api 2.10.1` `core.js:201-203` passthrough), including the postMessage-fallback hazard that motivates the spike. A base64 fallback for the full 10 MiB body is explicitly not authorized.
- Repair 1.1: the frozen per-run lifecycle is now pre-flight `isReady()` → per-run `connect(buildProviderCallbacks())` → checked `start()` → `sendAudio*` → `stop()` (§A.2); the external run performs both indispensable steps, re-verifies reservation/run currency after each await, and no PCM moves before `start` succeeds, with per-stage rollback (§B.5).
- Repair 1.2: truthful two-phase API (§B.2) — Phase A fully synchronous (checks + runId + reservation + the genuinely synchronous `captureActiveInsertionTarget`, `textInsertion.ts:248-276, 316-321`); Phase B is the bounded (3 s) asynchronous native capture (`bridge.getRecordingContext`, a Promise, `bridge.ts:127-132`) whose success is required before the admission response is accepted; capture failure → `500 context_capture_failed|context_capture_timeout`, no silent live-probe fallback.
- Repair 1.3: one correlated abort operation, two idempotent requestId(+runId)-conditional halves, clearing the Rust gate, JS reservation/runId, pending promises, overlay/Esc state and the provider session across all nine trigger cases (§B.6 matrix), including durable-save failure and WebView reload.
- Repair 1.4: the 30 s JS watchdog is removed; a Rust-owned lazy lease (`LEASE_MS = 300 s` = frozen `MAX_RECORDING_SEC`, against a derived ≈164 s legitimate worst case) plus boot-time `watch_gate_state()` reconciliation recover a gate orphaned by WebView reload without any JS timer (§B.7).
- Repair 1.5: `201` is returned only after a bounded (10 s) post-save acknowledgement (`watch_run_started`) proving the saved PCM was obtained and validated, callbacks connected, and `provider.start` succeeded; failures answer non-2xx and abort both sides; `201` means transport/admission/session success, never transcription completion (§B.3 step 8).
- Repair 1.6: the external run explicitly populates `recordedChunks`, `audioSentSamples`, sample-derived `wallTimeAtStopSec`, the processing-timeout input and the prompt/app/probe context fields; it never calls mic-only `stopCapture()`; the smallest shared finalize helper (`finalizeRecording`) is extracted from `stopRecording`'s tail for both paths (§B.5).
- All five task stop-conditions remain not triggered (§D); revised sequences cover accepted, busy, abort matrix, reload recovery and successful final (§C); nine risks recorded (§F).

## Delivery 1B Z1 PM re-review (2026-08-29) — Repair 2 required

Scope control passed: compared with PM review base `8240d4a`, Z changed only `docs/DELIVERY-1B-PROVIDER-CONTRACT.md`, `HANDOFF.md`, and `PROJECT_PROGRESS.md`; `git diff --check` is clean. Revision 2 correctly addresses all seven Repair 1 architecture items.

One blocking defect remains in §B.5 step 7: the feed loop increments `audioSentSamples += 4096` for every chunk, although the final slice can contain fewer than 4096 i16 samples. That overcounts sample-derived duration and can affect minimum-duration handling, processing timeouts, History audio metadata, and latency evidence.

Decision: revision 2 is **not yet frozen**. Colleague Z receives the narrow documentation-only package `docs/DELIVERY-1B-Z-CONTRACT-REPAIR-2.md`. It requires exact `chunk.byteLength / 2` accounting, end-of-feed equality checks against admitted `sampleCount`/PCM bytes, correlated abort on mismatch, and one non-divisible-by-4096 test contract. All other Repair 1 decisions remain accepted and must not be redesigned. Product implementation and the binary-IPC spike remain locked until PM accepts Repair 2.

## Delivery 1B Z1 contract revision 3 (Colleague Z, 2026-08-29) — resubmitted after Repair 2

- Deliverable: `docs/DELIVERY-1B-PROVIDER-CONTRACT.md` revision 3 (documentation only; review base `dd7efb2`). No product source, tests, dependencies, generated files, UI files, WAVs, APKs, tokens, or logs changed. `docs/WATCH-UI-Z-HANDOFF.md` was treated strictly as PM reference for a later slice — no UI work performed.
- Scope: exactly the Repair 2 correction — exact final-chunk sample accounting. Changed contract sections: §B.4 (chunk target is not an invariant; the final slice carries the exact remainder, guaranteed even), §B.5 step 7 (per-chunk `chunkSamples = chunk.byteLength / 2` on the exact slice; exact copied chunk into `recordedChunks`; `audioSentSamples += chunkSamples`, never the configured maximum; post-feed assertions `audioSentSamples === admission.sampleCount` and `Σ chunk bytes === pcm.byteLength` **before** `provider.stop`, mismatch → correlated abort with fixed reason `sample_accounting_mismatch`), §B.5 step 9 (`wallTimeAtStopSec`, `pttHoldMs`, timeout input, History duration derived **only** from the exact final `audioSentSamples`), §C.1 (sequence updated with the assertions and abort-before-stop), §E row 7 (explicit non-divisible-by-chunk-target test case plus a mismatch-abort case), §G (new R2 mapping row). Header and revision notes updated; verdict stays **CONDITIONAL MATCH**.
- Confirmation: no other Repair 1 decision was weakened. The per-run lifecycle (§A.2), two-phase reservation (§B.2), correlated abort operation and nine-case matrix (§B.6), Rust-owned 300 s lease and reload recovery (§B.7), post-save acknowledgement before `201` (§B.3 step 8), shared `finalizeRecording` / no-`stopCapture` rule (§B.5 step 9), probe-once rule, reuse guarantees, and the binary-IPC spike gate (§B.4) all carry over from revision 2 verbatim.

## Delivery 1B Z1 PM acceptance (2026-08-29) — CONDITIONAL MATCH accepted

PM compared revision 3 commit `c89c0bc36bccc32281ca3158e1f111511467d39f` against review base `dd7efb233f3ab85a85c740cecbfe644078cc7827`.

- Scope passed: only `docs/DELIVERY-1B-PROVIDER-CONTRACT.md`, `HANDOFF.md`, and `PROJECT_PROGRESS.md` changed; no product source, tests, dependencies, UI, generated files, or artifacts changed. `git diff --check` is clean.
- Repair 2 passed: chunk size is a target rather than an invariant; each exact even-length slice uses `chunk.byteLength / 2`; the copied chunks and `audioSentSamples` totals are asserted against the admitted PCM before `provider.stop`; mismatch takes the correlated `sample_accounting_mismatch` abort; duration/timeout/History inputs derive only from the exact final sample total; the implementation test contract includes a non-divisible final chunk and mismatch-before-stop case.
- Regression review passed: the diff does not weaken the accepted Repair 1 lifecycle, two-phase reservation, two-sided cleanup, Rust lease/reload recovery, pre-`201` acknowledgement, no-`stopCapture` external finalization, probe-once rule, or AI-off/reuse boundaries.

Decision: the Provider contract is frozen and stage 6 is accepted as **CONDITIONAL MATCH**. This is a documentation/architecture acceptance, not Delivery 1B product completion. Only the isolated ≥9 MiB binary-IPC spike is unlocked; task package: `docs/DELIVERY-1B-Z-BINARY-IPC-SPIKE-TASK.md`.

## Delivery 1B Z2 binary-IPC spike (Colleague Z, 2026-08-29) — PASS, awaiting PM review

- Deliverable: `spikes/watch-binary-ipc/**` only (isolated harness; plus this file and `PROJECT_PROGRESS.md`). No SayIt product file — `client/**`, `watch/**`, receiver, Provider, Orchestrator, History, Paste, AI, target/focus, release configuration, or the accepted dependency locks — was touched. `docs/WATCH-UI-Z-HANDOFF.md` was not implemented.
- **Result: PASS** in the packaged Windows debug WebView2 app (not a browser/dev-server page), reproduced across two runs (invoke elapsed 126 ms / 113 ms). Runtime panel (PASS/FAIL, versions, payload length, JS type, SHA-256 match, sentinels, fallback-warning count) was captured as a screenshot retained session-locally outside the repository; the payload is never logged or committed.
- Resolved stack (as pinned and as reported by the harness panel): Rust `tauri =2.10.3` (`Cargo.lock`), `@tauri-apps/api 2.10.1`, `@tauri-apps/cli 2.10.1`, toolchain `stable-x86_64-pc-windows-msvc` (rustc 1.98.0) — the same MSVC toolchain the accepted SayIt stack pins.
- Numeric result: payload 9,438,418 bytes (9 MiB + 1234; even length; deliberately not an 8192-byte/4096-sample chunk multiple) returned by `tauri::ipc::Response::new(Vec<u8>)`; JS received `[object ArrayBuffer]` with `byteLength` exactly 9,438,418; SHA-256 expected === actual: `ebf783be13a56bd212a474803c6b8d6da391b10bc0cfc5721f9422c90c045750` (WebCrypto subtle over the raw buffer, no base64/number-array conversion); sentinels first/middle/last 7/190/86 (Rust) === (JS) with independent formula re-derivation; **fallback-warning count 0** (`console.warn` wrapped programmatically across the whole invoke window and restored on all paths; total warnings 0).
- Explicit statement: `customProtocolIpcFailed` / the postMessage fallback message **did not appear**. The custom-protocol IPC path delivered the raw bytes.
- Commands and exit codes: `npm install` (0), `npm test` — vitest 8/8 passed (0), `npm run build` — versions.json + vite (0), `cargo test` — 4/4 payload/hash unit tests passed (0), `npx tauri build --debug --no-bundle` (0). Packaged debug executable: `spikes/watch-binary-ipc/src-tauri/target/debug/spike-binary-ipc.exe`, SHA-256 `940a58476ac390998b352a73aa4a741f97c08866a799002685d2020e02c0abf7` (not committed).
- Environment findings recorded for the implementation slice: (1) the machine's rustup default is the **GNU** host, under which the `windows-*` crates fail with `dlltool.exe: program not found` — the harness pins `stable-x86_64-pc-windows-msvc` exactly like `client/src-tauri/rust-toolchain.toml`; any new Rust workspace in this repository needs the same pin. (2) A fresh lock resolution drifts past the accepted stack (e.g. `tauri-runtime-wry 2.11.4` breaks `tauri 2.10.3` compilation with an E0308 `new_window_handler` signature change); the harness lock pins the Tauri family to the accepted versions (`tauri-runtime`/`tauri-runtime-wry 2.10.1`, `tauri-utils 2.8.3`, `tauri-build 2.5.6`, `tauri-codegen`/`tauri-macros 2.5.5`, `wry 0.54.4`, `muda 0.17.1`, `tao 0.34.8`).

## Delivery 1B Z2 PM acceptance (2026-08-29) — PASS, contract MATCH

PM reviewed Z commit `d1f7c1fa6943e556d0957ec1bf7c73823193ba81` against accepted base `db988d43a70c6371dade1857c4d379b68737a5fc`.

- Scope passed: only the isolated `spikes/watch-binary-ipc/**` harness and the two submitted authority-document updates changed; no `client/**`, `watch/**`, Provider, Orchestrator, History, Paste, UI, accepted dependency lock, received WAV, token, executable, or build cache was committed. `git diff --check` was clean.
- Independent automation passed: `npm ci` (exit 0, zero vulnerabilities); `npm test` (Vitest 8/8, exit 0); `npm run build` (exit 0); `cargo test` (4/4, exit 0); `npm run spike:build` (exit 0). The two Rust dead-code warnings are confined to test constants and are non-blocking.
- Independent payload check passed: length 9,438,418; sentinels 7/190/86; SHA-256 `ebf783be13a56bd212a474803c6b8d6da391b10bc0cfc5721f9422c90c045750`.
- Independent packaged-native run passed: the freshly rebuilt Windows WebView2 panel reported a 115 ms invoke, `[object ArrayBuffer]`, exact byte length and hash, all sentinels/checks green, and zero total/fallback warnings. PM rebuild executable SHA-256 was `0fdf6bb7789885affca9257c91668a1dfb38f130c9d023c66d2b52706852219f` (not committed; a debug executable is not expected to reproduce Z's build hash bit-for-bit).
- Dependency evidence matched the frozen stack: `tauri 2.10.3`, `tauri-runtime-wry 2.10.1`, `tauri-utils 2.8.3`, `@tauri-apps/api 2.10.1`, and `@tauri-apps/cli 2.10.1`.

Decision: Z2 is **accepted** and the Provider contract upgrades from CONDITIONAL MATCH to **MATCH**. This proves only the PC runtime transport primitive; it does not complete Delivery 1B or the Galaxy Watch closed loop. The next and only unlocked slice is `docs/DELIVERY-1B-Z-EXTERNAL-INGRESS-TASK.md`. Watch UI remains a later slice.

## Delivery 1B Z3 external WAV ingress (Colleague Z, 2026-08-29) — implemented, awaiting PM review

- Scope: exactly the allowed surfaces — new `client/src-tauri/src/watch_receiver/admission.rs`, modified `watch_receiver/server.rs`, debug-only wiring in `main.rs`, new `client/src/services/watchIngress.ts`, additive `recorder/RecorderOrchestrator.ts`, thin delegation in `recorder.ts`, focused Rust + Vitest tests, and these two authority documents. No dependency or lockfile drift; Watch app/UI untouched; Z2 harness untouched.
- Rust (`admission.rs`): `AdmissionGate` with `Idle | Reserved{request_id, reserved_at}`, request-correlated bounded oneshots (admission 5 s, run-start ack 10 s), Rust-owned lazy 300 s lease with reclamation logging, stale-ID no-ops, idempotent request-conditional `abort`, `watch_gate_state()` snapshot, and `watch_read_reserved_pcm` returning the header-stripped data chunk over the Z2-proven raw binary IPC (re-validated with the same strict WAV parser; gated by the exact live request ID).
- Rust (`server.rs`): admission step inserted after WAV validation and **before** the durable save — `409` for `orchestrator_not_idle` / `provider_not_ready` / `already_reserved` / `bridge_timeout` (never touches disk; previous `received_watch.wav` preserved), `500` for capture/preparation failures; save failure emits `watch://run-abort` (`save_failed`) and aborts the gate; `watch://audio-ready` then a bounded `watch_run_started` acknowledgement precede `201` — which still means transport/run-start success only, never transcription status.
- Rust (`main.rs`): debug-only event-sink registration at the top of `.setup` (a request arriving before registration fails closed via the admission timeout) plus five `#[cfg(debug_assertions)]` entries in the existing `generate_handler!` list, which compile the whole match arms out of release builds. Necessity note: the admission module is mounted from `main.rs` via `#[path = "../watch_receiver/admission.rs"]` inside a `pub mod watch_admission`, so `watch_receiver/mod.rs` keeps its exact Delivery 1A shape (its release-guard tests pin `watch_receiver::start` and pass unchanged); the gate is a `OnceLock` shared between `ReceiverServer` and the commands, so no `.manage()` wiring was needed.
- TS (`watchIngress.ts`): thin fail-closed adapter — `watch://admission-request` → Phase A → Phase B (3 s budget) → `watch_admission_resolve`; `watch://audio-ready` → `watch_read_reserved_pcm` (ArrayBuffer type check) → `beginExternalRun`; `watch://run-abort` → correlated abort; boot `watch_gate_state()` reconciliation releases an orphaned gate (`orphaned_after_reload`). Handlers are injected by `RecorderOrchestrator.init()` (no import cycle).
- TS (`RecorderOrchestrator.ts`, additive): `externalAdmissionBlocker` / `tryReserveExternalRun` (Phase A: synchronous reserve + one-shot in-process capture) / `prepareExternalRun` (Phase B: bounded native capture into the same probe/app-context fields the mic path uses) / `beginExternalRun` (validate → reset run fields → per-run `connect(buildProviderCallbacks())` → re-verify ready → checked `start({runId, disableAi: true, no systemPrompt, …})` → `transition('recording')` → `watch_run_started` ack → exact-accounting feed (per-slice `byteLength / 2`, exact copied slices into `recordedChunks`, short final chunk, post-feed equality assertions **before** `provider.stop`) → sample-derived durations → shared finalize; <0.5 s discards like the mic path) / `abortExternalRun` (request-conditional, idempotent, clears both halves). Guards: PTT down ignores while a reservation exists; PTT up cannot hijack the external `recording` phase. `finishRun` releases the gate on every terminal path. `finalizeRecording` was extracted verbatim from the mic `stopRecording` tail (mic-only teardown stays behind; the external path never calls `stopCapture`).
- Evidence (commands, exit codes): `cargo test watch_receiver` **34/34** (0) — 12 new gate unit tests (accept/reject/correlation/double-resolve/stale-IDs/lease-reclamation/reload-state/abort-completes-pending-ack), 7 new receiver integration tests (busy 409 preserves the previous file; prepare-failure 500 writes nothing; bridge-timeout 409; full accept flow returns 201 only after ack; ack-timeout 500 + release; save-failure 500 + release + run-abort) plus the preserved malformed-WAV/truncation regressions and legacy 201 flows updated to the frozen contract (they now simulate the WebView decision + ack); full `cargo test` **156 passed / 0 failed / 4 ignored** (0); focused Vitest **28/28** (0) — reservation atomicity vs PTT, lifecycle order with no PCM before a successful start, exact short final chunk with byte-for-byte reconstruction, `sample_accounting_mismatch` abort before `stop`, `pcm_length_mismatch` entry validation, probe/context captured exactly once, AI forced off per run, History and Paste reached exactly once via `onFinal`, error cleanup with single gate release, stale-abort conditionality, and the mic-path regression after the extraction; full client Vitest **31 files, 0 failures** (0); `tsc && vite build` (0); `cargo build --release` (0) and the release-marker scan found **all** markers absent — the five Delivery 1A markers (`watch-receiver`, `SAYIT_WATCH_BIND_IP`, `received_watch.wav`, `X-Request-Id`, `api/watch/audio`) plus `watch_admission_resolve`, `watch_run_started`, `watch_run_aborted`, `watch_read_reserved_pcm`, `watch_gate_state`, `AdmissionGate`. `git diff --check` clean. No token, WAV, executable, build cache, model, APK, or local path was committed.
- Boundary: not VERIFIED end-to-end. The real Galaxy Watch → ASR → History → Paste ten-run acceptance (stage 8) stays locked until PM independently reviews this slice.

## Delivery 1B Z3 PM review (2026-08-29) — NO-GO, Repair 1 required

PM reviewed Z3 commit `dc1af55137c6048b2f170a7ffa167f83364daef9` against `07ffdb214d7a5da6e935ced19bc453f55124aeaf`. Scope and diff hygiene passed. Fresh PM evidence before the user paused the review: client Vitest 367/367 passed; TypeScript/Vite build passed; Rust passed 156/0/4 after adding the already-installed `C:\Program Files\CMake\bin` to that command process's PATH. The Release build was deliberately interrupted at the user's request and is not PM-verified.

Decision is **NO-GO** despite the green unit tests. Three Important runtime-contract defects remain: (1) Rust emits preformatted JSON as a `String`, so real Tauri delivers `event.payload` as a string while `watchIngress.ts` reads an object, causing the event to be ignored and the upload to end in `409 bridge_timeout`; tests use an object-only fake and miss this. (2) `server.rs` emits `watch://admission-request` before the gate registers `pending_admission`, so an immediate WebView response is stale and the receiver can time out; tests poll for the waiter and therefore mask the ordering race. (3) the shared Provider `onError` path calls mic-only `stopCapture()` whenever state is `recording`; an external Watch run also uses that state, so a feed-time Provider error violates the no-mic-capture contract and lacks the required dedicated regression test.

Only `docs/DELIVERY-1B-Z3-REPAIR-1.md` is unlocked. It authorizes the three Repair items first, then the versioned Watch UI candidate. Real-device closure remains locked until PM independently accepts both parts.

## Delivery 1A source/build evidence (Colleague D, 2026-08-29)

Toolchain used for the delivery-1A verification runs:

- JDK: Eclipse Temurin 17.0.20.1 (x64)
- Gradle: 8.10.2 (watch project wrapper)
- Android SDK: platform-tools 37.0.1, platforms;android-34, build-tools;34.0.0 (command-line tools installed outside the repository; no local paths committed)
- Kotlin 2.0.21, AGP 8.7.3, Wear Compose 1.4.1, compileSdk 34 / targetSdk 34 / minSdk 30
- Rust: repo toolchain pinned to stable x86_64-pc-windows-msvc (rustc 1.98.0 via rustup); Cargo.lock updated for `tiny_http = "0.12"`
- Node.js v24.16.0 (npm test / build unchanged)

Fresh verification runs (exit codes):

1. `cd watch; .\gradlew.bat testDebugUnitTest lintDebug assembleDebug` — BUILD SUCCESSFUL (exit 0). 35 unit tests, 0 failures. Lint clean. Debug APK produced.
2. `cd client; npm test` — 29 files / 339 tests passed (exit 0).
3. `cd client; npm run build` — tsc + vite build OK (exit 0).
4. `cd src-tauri; cargo test` — 142 tests, 138 passed, 0 failed, 4 ignored (exit 0). Includes watch_receiver module tests (config, WAV parsing, HTTP server, atomic save).
5. Release checks: `cargo build --release` succeeds; the release binary contains none of the receiver markers (`watch-receiver`, `SAYIT_WATCH_BIND_IP`, `received_watch.wav`, `tiny_http`, `api/watch/audio`). Watch release APK manifest has no `usesCleartextTraffic`; debug APK manifest has it.

Debug APK: `watch/app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `81B14FECBC5119300FE98433526CF848E28178878B925F0E85A15DB6FBF06944` (not committed to Git).

Receiver contract implemented in `client/src-tauri/src/watch_receiver/` (debug-only, `#[cfg(debug_assertions)]` gated in `main.rs`): env config fail-closed, `GET /api/health`, `POST /api/watch/audio` with exact Bearer auth, audio/wav content type, 10 MiB cap, RIFF validation, atomic `MoveFileExW` write-through replacement under `%LOCALAPPDATA%\com.sayit.app\watch-receiver\received_watch.wav`.

Watch app implemented under `watch/` (Kotlin + Compose for Wear OS): 16 kHz native verification, dedicated I/O recording, canonical WAV writer, RFC1918-only destination validation, cleartext debug-only, `201` = transport success only.

## Delivery 1A real-device evidence (Colleague D, 2026-08-29) — D evidence pending PM acceptance

Device: Galaxy Watch 7 (SM-L310, Wear OS Android 16 / API 36, 480x480 round), connected via Wi-Fi ADB wireless debugging (no serial recorded; no tokens/APKs committed).

Watch app debug APK installed on device with RECORD_AUDIO granted; destination configured to the PC's LAN RFC1918 IPv4 `192.168.12.142:18099` with a 48-hex-char dev token (supplied via environment, not committed).

Receiver run: debug SayIt started with `SAYIT_WATCH_BIND_IP=192.168.12.142`, `SAYIT_WATCH_PORT=18099`, `SAYIT_WATCH_DEV_TOKEN=<48-char token>`; log line: `watch receiver listening on 192.168.12.142:18099 (dev token present: true)`. Windows firewall rule for TCP 18099 on the Private profile was created.

Observed on the watch (UI state machine): `Ready — 16 kHz verified` (AudioRecord min buffer > 0, STATE_INITIALIZED, actual sampleRate 16000) -> `Recording` -> `Recorded (N samples)` -> `Uploaded / transport verified`. `201 Created` mapped to transport success only; UI showed "Uploaded / transport verified", never "Transcribed".

Two real recordings were transported and durably saved:

| Run | Captured samples | WAV bytes | Duration | Received SHA-256 | Note |
|---|---:|---:|---:|---:|---|
| 1 | 223,360 | 44,764 header+PCM = 446,764 | 13.96 s | `700f4daa17357506167a35d6e84f68d473c21f56c0b7dd576ed202636665f6c9` | Quiet environment audio |
| 2 | 238,080 | 476,204 | 14.88 s | `39a80c7cfa210cbfe4bcac1aca8117e1096e5d3b3f6b9ca8195cd7f4b2b57077` | Speech (0 dBFS peaks; per-second RMS -56.8..-66.3 dBFS) |

Both files validated as PCM format 1, 1 channel, 16,000 Hz, 16-bit, block align 2, byte rate 32,000, even non-empty data, no truncation. `%LOCALAPPDATA%\com.sayit.app\watch-receiver\received_watch.wav` is the durable accepted sample; no temp files remain after each upload. Desktop copies for PM playback: `received_watch_D-evidence.wav` (run 1) and `received_watch_D-evidence-voice.wav` (run 2).

Manual playback acceptance: on 2026-08-29 the user explicitly confirmed the human playback gate. Together with the independently checked file parameters and hash, Delivery 1A stage 5 is accepted. This acceptance covers the received audio only; it does not waive the source findings below or unlock Delivery 1B.

Upload HTTP response (from run-1-style PC self-test and run 2 device upload): `201 Created` with `requestId`, `bytes`, `sampleCount`, `audioDurationMs`, and lowercase hex `sha256` matching the saved file.

## PM review (2026-08-29) — NO-GO

Fresh PM checks confirmed the submitted APK and received WAV hashes match the handoff. The received file independently parses as PCM s16le, 16,000 Hz, mono, 14.88 seconds, 238,080 samples, 476,204 bytes; an automated scan detected no silence interval of 0.5 seconds or longer. The user subsequently confirmed the required human playback acceptance.

The source cannot be accepted yet:

- Watch destination and token rows are plain `Text`; the supplied `onChange` callback is never called, so the required settings cannot be entered through the Watch UI.
- Live duration is not sample-derived in the UI: `elapsedSec` is unused and `_sampleCount` is synchronized only after recording completes.
- A 48-hex-character token represents only 24 decoded bytes; current validators merely check string length and do not enforce the required 256-bit representation.
- Watch sends `X-Request-Id`, but the receiver discards it and creates another UUID, preventing reliable Watch-to-PC trace correlation.
- WAV chunk parsing walks to the physical file end even when the declared RIFF extent is shorter.
- `client/src-tauri/rust-toolchain.toml` changed outside the authorized path without necessity evidence and must return to the baseline declaration.

PM independently reran client Vitest (339 passed) and the client production build successfully. Android verification could not be independently reproduced in the PM shell because Java/JAVA_HOME is absent; Rust tests could not be reproduced there because CMake is absent. These environment blockers do not invalidate D's logs, but those logs are not independent PM proof.

Decision: do not merge, do not mark Delivery 1A accepted, and do not begin Delivery 1B. Colleague D receives only `docs/DELIVERY-1A-D-REPAIR-1.md`.

## Delivery 1A Repair 1 (Colleague D, 2026-08-29) — PM accepted

All six frozen discrepancies were repaired on `codex/review-watch-transport` (commit pushed; see `PROJECT_PROGRESS.md`). Focused diff per item:

1. **On-Watch editable settings**: `RecordingScreen.SettingsField` now opens a Wear dialog with a `BasicTextField` (IME `Done` action); values are committed on confirm. Proven on-device without ADB-pre-seeded prefs: PC IP `192.168.12.142`, port `18099`, and a 64-hex dev token were all typed through the real Wear keyboard and persisted (`watch-ev-*.png` screenshots).
2. **Live sample-derived duration**: `AudioCapture.record` now invokes `onProgress(cumulativeSamples)` after every read; `RecordingViewModel` publishes it to `_sampleCount`, and the status line renders `Recording <ms> ms` while recording (observed on-device increasing through ~3520 ms during a run that finished at 238,080 samples). Wall-clock time is never used as the source of truth.
3. **Frozen 64-hex Dev Token**: new `DevTokenValidator` (Watch) and `watch_receiver::config::validate_token` (Windows) both require exactly 64 hexadecimal chars after trimming (32 decoded bytes / 256 bits). Positive and negative tests cover 63/65 chars, non-hex, surrounding whitespace, and valid 64-char tokens on both sides.
4. **X-Request-Id end to end**: receiver validates `X-Request-Id` as a UUID (missing/invalid -> 400), echoes the same UUID in the 201 JSON, and includes it in the non-secret success log. Watch `TransportClient.verifySuccessResponse` accepts 201 only when the echoed `requestId` equals the sent UUID (mismatch/missing -> failure). Tests for mismatch/missing/invalid headers added.
5. **Strict RIFF bounds**: `wav::parse` now requires the declared RIFF extent to match the file extent (one valid container-pad byte tolerated) and bounds all chunk walking to the declared extent; a valid `fmt`/`data` beyond a shortened declaration is rejected (test added).
6. **Toolchain declaration restored**: `client/src-tauri/rust-toolchain.toml` reverted to `channel = "stable-x86_64-pc-windows-msvc"`. Reproducible evidence the baseline works: with `stable-x86_64-pc-windows-msvc` installed via rustup, `cargo --version` resolves 1.98.0 and `cargo check --tests`/`cargo test`/`cargo build --release` all succeed under the baseline declaration (the earlier failure was caused by the MSVC toolchain not being installed yet, not by the declaration).

Fresh verification for Repair 1:

- `watch`: `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug` — BUILD SUCCESSFUL (exit 0), 45 unit tests / 0 failures, lint clean.
- `client`: `npm test` — 339 passed (exit 0); `npm run build` — exit 0.
- `client/src-tauri`: `cargo test` — 145 tests, 141 passed / 0 failed / 4 ignored (exit 0); receiver module tests 28 passed.
- Release checks: `cargo build --release` binary contains none of `watch-receiver`, `SAYIT_WATCH_BIND_IP`, `received_watch.wav`, `X-Request-Id`, `api/watch/audio`; Watch release APK has no `usesCleartextTraffic`, debug APK has it.
- Debug APK (rebuilt): `watch/app/build/outputs/apk/debug/app-debug.apk`, SHA-256 `1275C49D508A8E03AFD7E9D84F7EC668BE12332DE3E479152DDA38C20873E8D6` (not committed).

Repair-1 real-device evidence (D evidence): a new speech/ambient recording was captured on the Watch (238,080 samples, 14.88 s) and uploaded with a valid `X-Request-Id`; receiver logged `requestId=bb327d88-96a3-4af5-b700-c7eeeaeee942 bytes=476204 samples=238080 durationMs=14880` and durably saved `received_watch.wav` (SHA-256 `2929585a641a1f6d55d3aa715d8f21d153f67941742c5eb299fa135cd3de02d2`); desktop copy `received_watch_D-repair-voice.wav` for PM playback. PC-side self-tests confirmed 201 echoes the sent UUID and missing/invalid `X-Request-Id` return 400.

## Next executable step

The user personally transfers `docs/DELIVERY-1B-Z-CONTRACT-TASK.md` to colleague Z. Z freezes the real Provider/Orchestrator/History/Paste contract on `codex/review-watch-pipeline` and stops. PM reviews that document before issuing any implementation slice.

## PM acceptance (2026-08-29)

PM reviewed the Repair 1 diff against all six frozen findings and independently ran the available verification:

- Watch: unit tests forced to rerun, 45 passed / 0 failed / 0 skipped; lint, Debug APK and Release APK builds succeeded.
- Client: Vitest 29 files / 339 tests passed; TypeScript and Vite production build succeeded.
- Rust: 145 tests total, 141 passed / 0 failed / 4 pre-existing ignored; release build succeeded.
- Release safety: none of the five receiver markers (`watch-receiver`, `SAYIT_WATCH_BIND_IP`, `received_watch.wav`, `X-Request-Id`, `api/watch/audio`) occur in the release executable. Debug merged manifest enables cleartext; Release merged manifest does not enable it.
- Artifact evidence: the submitted repair APK hash was matched before the PM rebuild. A clean PM rebuild produced a new debug-artifact hash, as expected for a regenerated debug APK; it does not replace D's installed artifact identity.
- Device transport: the locally retained Repair 1 WAV hash matches D's report and independently parses as PCM s16le, 16,000 Hz, mono, 14.88 seconds, 238,080 samples, with no detected silence interval of 0.5 seconds or longer. The earlier human playback gate remains accepted.

D reported that IP, port and token were typed with the real Wear keyboard and that live sample-derived duration increased on device. The referenced screenshot files were not included in Git, so this portion remains explicitly classified as D production evidence rather than a PM-replayed attachment. It is consistent with the inspected source and the new device upload.

Decision: Delivery 1A **ACCEPTED**. Delivery 1B is unlocked but not started. No merge, release, installer, or Delivery 1B work was performed by this acceptance.

## Delivery 1A acceptance

- Debug Watch APK builds successfully and release configuration rejects cleartext transport.
- On a real Galaxy Watch 7, `AudioRecord` initializes at exactly 16 kHz, PCM 16-bit, mono.
- Authenticated upload reaches the explicitly configured Windows LAN IPv4.
- The receiver validates the WAV and atomically saves `%LOCALAPPDATA%\com.sayit.app\watch-receiver\received_watch.wav`.
- Response is `201 Created` with request ID, bytes, sample count, duration, and SHA-256.
- PM manually plays the file and records: no truncation, no abnormal gaps, correct speed/pitch, correct duration, and usable speech.

## Delivery 1B guardrail

Delivery 1B may modify the external-audio ingress only after the Delivery 1A acceptance gate is recorded here. It must reuse `RecorderOrchestrator`, the active `TranscriptionProvider`, existing callbacks, History, and Paste; it must not create a second ASR path.

## Security

- Development tokens are generated outside the repository and supplied through environment configuration.
- Tokens, received audio, local SDK paths, signing material, and device identifiers must not enter Git, logs, screenshots, or handoff documents.
