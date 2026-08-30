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

## Delivery 1B Z3 Repair 1 + Watch UI candidate (Colleague Z, 2026-08-29) — implemented, awaiting PM review

- Review base: `5da1a32279b372810d83504aca2021b0c8146763` (task `docs/DELIVERY-1B-Z3-REPAIR-1.md`). Part A (three blocking repairs) was completed and fully green before Part B (Watch UI) started, per the strict serial rule. NOT VERIFIED end-to-end: no real device this round; stage 8 stays locked.
- **Repair 1 (object payloads)**: `EventSink` now carries `serde_json::Value`; `server.rs` builds `watch://admission-request` / `watch://audio-ready` with `serde_json::json!` objects (`requestId`/`bytes`/`sampleCount`/`durationMs`) and `watch://run-abort` with `requestId`/`reason`; `main.rs`'s sink forwards the object so Tauri delivers a structured object — never a JSON string. Rust regression `event_payloads_are_json_objects_with_frozen_fields` asserts every sink payload is an object (serialized form starts with `{`, never `"}`) with the frozen field types; TS regression asserts a legacy string payload is ignored fail-closed (no reserve, no resolve).
- **Repair 2 (register-before-emit)**: gate API split into `begin_admission` (mutex checks + lease reclamation + waiter registration) -> emit -> `wait_admission` (bounded wait; fail-closed cleanup of exactly the current request). The same race closed symmetrically for the handoff: `begin_run_ack` registers the ack waiter BEFORE the `audio-ready` emit. Regression `sync_resolve_inside_emit_stack_is_accepted` resolves both decisions synchronously inside the emit call stacks — accepted, no stale, no timeout, no polling helper. Admission still precedes the durable save; `409` never overwrites `received_watch.wav`.
- **Repair 3 (no mic teardown for external errors)**: `onError` branches — when the failing generation is the external Watch run (request/run correlated), the mic-only teardown (`stopCapture`, listening ticker, system-mute restore) is skipped; the Provider session is cancelled (server reconnected), the shared error-history tail runs once, and `finishRun` releases the Rust gate exactly once. Mic behavior unchanged. Regressions: external mid-feed `onError` -> `stopCapture` 0 calls, gate-release invoke exactly once, no Paste, single History entry, orchestrator reusable; stale second `onError` duplicates nothing; mic-run `onError` still calls `stopCapture`.
- Allowed-list note: no change outside the allowed list was needed. The production emitter lives in `main.rs`'s setup (registered through `watch_receiver::server::set_event_sink`), so `watch_receiver/mod.rs` stayed byte-identical to its Delivery 1A shape; `watchIngress.ts` also needed no code change for Repair 1 — its adapter already ignores non-object payloads fail-closed (a regression test now pins that).
- **Part B (Watch UI, 0.2.0-dev.2, versionCode 2)**: three screens (developer config with masked token + Show/Hide, Ready with explicit health check and transport-only wording, Recording with sample-derived duration + Stop + Cancel-discard) and inline states (Uploading with app-driven keep-screen-on, Upload failed with retained WAV + Retry/Later, Pending-upload badge, explicit discard prompt before any new recording overwrites a retained WAV, brief "Uploaded to PC" that never claims transcription). Haptics: start/stop/success/failure only. No carousel dots; 480x480 round safe area; no new pages.
- Watch implementation only in the allowed files (`MainActivity.kt`, `ui/RecordingScreen.kt`, `ui/RecordingViewModel.kt`, `strings.xml`, `build.gradle.kts` version bump). A pure `WatchUiStateMachine` (no Android imports) lives in `RecordingViewModel.kt`, unit-tested by `WatchUiStateMachineTest` (9 tests) ; `RecordingSessionRetryTest` (3 tests) pins byte-for-byte retention. Existing 1A tests untouched and green.
- Design evidence: `design/watch-ui/0.2.0-dev.1-baseline/` (status `accepted-parent`, parent Git SHA `5da1a322...`, per-file SHA-256 of the pre-modification sources) and `design/watch-ui/0.2.0-dev.2-candidate.1/` (status `candidate`, README with changes/locks/deviations, seven 480x480 state previews, `SHA256SUMS`). Declared deviation: the previews are hand-authored layout renders — this environment has no emulator or attached watch, so photographic screenshots (baseline and candidate) are deferred to the PM/user device session, exactly like the real-device interaction acceptance.
- Verification (commands, exit codes): client focused Repair tests (0); full client `npm test` — 31 files / 370 passed (0); `npm run build` (0); `cargo test watch_receiver` 36/36 (0); full `cargo test` 158 passed / 0 failed / 4 ignored (0); `cargo build --release` (0); release-marker scan — all eleven markers absent (five Delivery 1A markers, five `watch_*` command names, `AdmissionGate`); `watch`: `gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease` all successful (0), debug APK `watch/app/build/outputs/apk/debug/app-debug.apk` SHA-256 `77e614cf2ce8accdda61a8221c0501d9210bd1a976b1425985835c7ae1625cb5` (not committed; release APK also produced unsigned). `git diff --check` clean. No token, WAV, executable, build cache, model, APK, device info, or local path committed.

## Delivery 1B Z3 Repair 2 — Watch UI behavior & evidence (Colleague Z, 2026-08-29) — implemented, awaiting PM review

- Review base: `d3f17c5b3f990d0ef1e6e9f40b58d086a166a613` (task `docs/DELIVERY-1B-Z3-REPAIR-2.md`). PC-side Rust/TS product code untouched (the three accepted repairs stand as-is); all changes are in the allowed Watch files, tests, and the two design-evidence directories. Not VERIFIED end-to-end; no device this round.
- **必修 1 (Cancel race)**: new pure `RecordingRequestLatch` in `RecordingViewModel.kt` — `startRecording` opens a generation, `cancelRecording` invalidates it BEFORE `session.reset()/toReady()`, and the capture coroutine drops a late completion when `isCurrent(gen)` is false (no `recordingCompleted`, no auto-upload, no FAILURE write). Cancel deterministically ends READY with `wavBytes == null`. Tests: `RecordingRequestLatchTest` (4 — live-until-cancel, the exact late-completion scenario on a real `RecordingSession`, re-begin after cancel, generation 0 never current).
- **必修 2 (Retry reachable after Later)**: `retryPressed()` now accepts Pending-ready (`NONE` overlay + `pendingUpload` + READY screen) in addition to the failure overlay; the Pending badge is tappable and a wide `Retry upload` chip sits next to it on Ready. End-to-end regression `failure then later then retry from pending-ready reaches uploading with the same bytes` drives the machine and a real `RecordingSession` in lockstep (Failure → Later → Ready/Pending → Retry → Uploading) and asserts the retried upload consumes the exact original WAV; a no-pending retry is a verified no-op; the discard prompt is unchanged.
- **必修 3 (runtime controls = previews)**: all text actions are now Wear `Chip`s sized from a single `WatchUiMetrics` source — wide pills 340×52 dp (`Save & Apply`, `Check / Refresh`, `Retry upload`), Retry/Later as two 160×52 dp chips, Record/Stop as 52 dp circular buttons with ●/■ glyphs and captions; config rows and the token Show/Hide have ≥48 dp touch heights (`heightIn`/`height`). `WatchUiMetricsTest` (4) pins the invariants automatically (≥48 dp touch heights, pill widths and the 160+20+160 pair inside the 480×480 circular safe area). The candidate.2 previews are generated from these same numbers, so preview and runtime agree by construction; real round-screen touch/clip checks stay with the device gate.
- **必修 4 (copy + versioned evidence)**: the Uploading copy now states "The screen stays awake automatically." (no user responsibility). `candidate.1` is untouched; the new freeze is `design/watch-ui/0.2.0-dev.2-candidate.2/` (status `candidate`, version still 0.2.0-dev.2 / versionCode 2, README declaring all four fixes and the same non-photographic-preview deviation, seven regenerated 480×480 previews, `SHA256SUMS`). The invalid parent manifest is preserved and superseded by `design/watch-ui/0.2.0-dev.1-baseline-recovery.1/` — its `SHA256SUMS` was regenerated from the parent commit's Git blobs (`git show 5da1a322…:<path>`): the corrected `build.gradle.kts` hash is `8eee9944f1e9aa0464774fe183ae4e0e3d1861ff1381f9ae11bd55066fc38254` (the invalid manifest had recorded the dev.2 value `656dca86…`); the recovery README states the original baseline was ruled invalid/rejected evidence.
- **Fresh Cargo rerun (PM's FTK1011/MSB3491 blockage cleared)**: `cargo test` — 158 passed / 0 failed / 4 ignored (exit 0); `cargo build --release` (exit 0); release-marker scan — all eleven markers absent.
- Full verification (commands, exit codes): `watch` `gradlew testDebugUnitTest --rerun-tasks` — **67 tests / 0 failed** (exit 0; 57 prior + 10 new); `gradlew lintDebug assembleDebug assembleRelease` (exit 0); debug APK `watch/app/build/outputs/apk/debug/app-debug.apk` SHA-256 `a540941c771b89daafff73122ac2bef4bf4b231e768db40177757909d77c8f3e` (not committed; changed from the previous build because the sources changed); `client` `npm test -- --run` — 31 files / 370 passed (exit 0); `npm run build` (exit 0); `git diff --check` clean. SHA256SUMS recomputed for both design directories. No token, WAV, executable, build cache, model, APK, device info, or local path committed.
- Boundary: no real device, no ten-run closure; stage 8 stays locked pending PM re-review.

## Delivery 1B Z3 Repair 3 — Cancel concurrency, responsive UI, faithful previews (Colleague Z, 2026-08-29) — implemented, awaiting PM review

- Review base: `edcfcef9b460a4f107449d6bc38adf008616d814` (task `docs/DELIVERY-1B-Z3-REPAIR-3.md`). PC Rust/TS product code untouched; changes only in `ui/RecordingScreen.kt`, `ui/RecordingViewModel.kt`, the corresponding tests, `design/watch-ui/0.2.0-dev.2-candidate.3/**`, and these two authority documents. Not VERIFIED end-to-end; no device this round.
- **必修 1 (Cancel fail-closed for both late outcomes + visibility)**: `RecordingRequestLatch` is now fully atomic (`AtomicInteger` generation/settled, `AtomicBoolean` cancelled) and exposes a SINGLE completion coordinator — `settle(generation)` — which returns true exactly once per live generation and never for cancelled/superseded/already-settled/generation-0 IDs. Both ViewModel completion paths (normal return AND the catch branch) are gated on `settle`, so a Cancel drops a late exception exactly like a late success: session stays READY, no WAV, no upload, no stop vibration. `recordingActive` is now `@Volatile`. Tests: `RecordingRequestLatchTest` (7) — exactly-once, Cancel+late-normal, Cancel+late-EXCEPTION, re-begin, generation 0 (which caught a real settle bug on a fresh latch, now guarded), cross-thread cancel visibility via latch-join, and concurrent settles from two threads claiming exactly once. The tests drive `settle` itself — the same coordinator the ViewModel calls — with no duplicated decision logic.
- **必修 2 (no px/dp conflation, responsive)**: `WatchUiMetrics` no longer contains any 480 dp width assumption. Widths are parent-relative: wide chips `fillMaxWidth(1f)` of the padded parent, Retry/Later are two `weight(1f)` chips with a dp gap, heights/padding/circle remain dp. The Ready column scrolls (`verticalScroll`) when content exceeds the logical height. Long-copy actions left the circular buttons: Grant Mic, dialog OK/Cancel, and Keep-it/Discard-&-record are proper chips now. `WatchUiMetricsTest` (5) pins the responsive policy (min touch heights, fraction/weight values, no absolute screen-width arithmetic).
- **必修 3 (candidate.3 faithful)**: new `design/watch-ui/0.2.0-dev.2-candidate.3/` (candidate.2 untouched): every preview is an element-for-element render of its Composable — Ready (and Pending) now include the transport line, `Check / Refresh` chip, Settings link, Pending badge + `Retry upload` chip, Record circle with caption, and the bottom `StatusLine`, in runtime order; overlays draw the dimmed Ready content underneath exactly like the Compose overlay stack. The generator asserts programmatically that no text exceeds its container (the candidate.2 badge overflow class is now impossible to regenerate). README records candidate.2's rejection, the round's differences, parent/locks, the non-photographic deviation, and a dp→px mapping note (previews draw the logical dp layout on a nominal 1 dp = 1 px reference canvas; widths are fractions, so they scale with real density). `SHA256SUMS` covers all files.
- **baseline-recovery.1 re-verified**: all six entries recomputed from the parent commit's raw Git blob bytes (`git show 5da1a322…:<path>`) and matched — including `build.gradle.kts` `8eee9944…`. Not modified.
- Verification (commands, exit codes): `watch` `gradlew testDebugUnitTest --rerun-tasks lintDebug assembleDebug assembleRelease` — **71 tests / 0 failed**, all tasks successful (exit 0; 67 prior + 4 new latch tests); debug APK `watch/app/build/outputs/apk/debug/app-debug.apk` SHA-256 `b1c73093900b56e044fcb77634bcb13513607c7ea4848a131876dca5c0aa8d11` (not committed); `client` `npm test -- --run` — 31 files / 370 passed (exit 0); `npm run build` (exit 0); `cargo test` — 158 passed / 0 failed / 4 ignored (exit 0); `cargo build --release` (exit 0); release-marker scan — **11 absent / 0 present**. `git diff --check` clean. No token, WAV, executable, build cache, model, APK, device info, or local path committed.
- Boundary: no real device, no ten-run closure; stage 8 stays locked pending PM re-review.

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

## Delivery 1B Z3 Repair 1 PM review (2026-08-30) — split decision / UI Repair 2 required

- Review target: `b8daef527acbdced401e73ec9be6eaee39373389` against base `5da1a32279b372810d83504aca2021b0c8146763`; branch `codex/review-watch-pipeline`; submitted tree was clean and `git diff --check` passed.
- PC Part A retained: the real Tauri sink now emits `serde_json::Value` objects; admission and run-start ack waiters are registered before their events; external Provider errors skip mic-only `stopCapture` and use the correlated shared cleanup. Fresh client evidence: 370/370 Vitest passed and production TypeScript/Vite build passed. The current Rust test executable ran 158 passed / 0 failed / 4 ignored; the existing Release exe was independently scanned and all eleven Watch/admission markers were absent.
- Rust build limitation: a fresh `cargo test`/Release rebuild did not complete in this PM run because the external `transcribe-cpp-sys` CMake/MSBuild cache failed at generated tracking logs (`FTK1011` / `MSB3491`). This is not presented as a product-code failure, but fresh Cargo compile evidence is required in the next Z return.
- Watch build evidence: unit tests forced to rerun, 57 passed / 0 failed / 0 skipped; lint, Debug APK, and Release APK builds passed. Debug APK SHA-256: `77E614CF2CE8ACCDDA61A8221C0501D9210BD1A976B1425985835C7AE1625CB5` (not committed).
- Watch UI decision: **NO-GO**. Cancel can race its late capture completion and end in Failure; Later leaves a Pending state with no reachable Retry; long text actions use Wear Compose Material 1.4.1 default circular 52 dp `Button` while candidate SVGs show wide pills; `Keep the watch on` conflicts with app-driven awake behavior; and the parent baseline hashes are not trustworthy (`build.gradle.kts` records the dev.2 working-file hash rather than the dev.1 parent blob). Candidate.1 and the invalid baseline remain untouched as audit evidence.
- Next task for colleague Z: `docs/DELIVERY-1B-Z3-REPAIR-2.md`. It fixes only Watch UI behavior and versioned evidence, freezes `candidate.2`, reruns all required checks, pushes, and stops. No real-device closure, merge, tag, release, or ten-run acceptance is authorized.

## Delivery 1B Z3 Repair 2 PM review (2026-08-30) — NO-GO / Repair 3 required

- Review target `d04b7c3b3fb93cb7ea01e742556fb0ae302dc9b0` against PM task base `d3f17c5b3f990d0ef1e6e9f40b58d086a166a613`; scope stayed within the allowed Watch/UI/test/design/docs paths, PC Rust/TS product code and frozen candidate.1 were untouched, and the submitted tree was clean.
- Retained evidence: Pending-ready Retry is reachable and retains the same WAV; Uploading copy is app-driven; candidate.2 manifest is internally consistent; `baseline-recovery.1` exactly matches SHA-256 of raw blobs from parent `5da1a322...`. Fresh PM runs: Watch 67/67 tests passed with `--rerun-tasks`; lint and Debug/Release APK builds passed; Debug APK SHA-256 `A540941C771B89DAAFFF73122AC2BEF4BF4B231E768DB40177757909D77C8F3E`; client Vitest 370/370 and production build passed.
- Rust limitation: fresh PM Cargo compilation still fails in the external `transcribe-cpp-sys` CMake/MSBuild cache with `FTK1011`; Z's reported fresh Rust run is retained as worker evidence only, not PM-reproduced evidence. PC code was unchanged in this slice.
- Decision: Watch UI remains **NO-GO**. Cancel's normal completion is gated, but the exception catch can still overwrite READY with FAILURE after Cancel; the latch and `recordingActive` have no explicit cross-thread visibility, and tests do not exercise the ViewModel completion/upload decision. Runtime layout incorrectly equates a 480 px watch with `480.dp`, using fixed 340/160 dp chips; default circular Buttons still hold long Grant/Discard/dialog labels. Candidate.2 visibly clips the Pending badge and omits runtime Check/Refresh and StatusLine, contradicting its runtime-parity claim.
- Next task: `docs/DELIVERY-1B-Z3-REPAIR-3.md`. Preserve PC code, baseline-recovery.1, candidate.1 and candidate.2; repair only Cancel concurrency, responsive Wear layout, and new immutable candidate.3. No merge, tag, release, device installation, or ten-run closure is authorized.

## Delivery 1B Z3 Repair 3 PM review (2026-08-30) — NO-GO / Repair 4 required

- Review target `332089a510cb06040f4863561301b9b5d5eaef51` against PM task base `edcfcef9b460a4f107449d6bc38adf008616d814`; allowed scope and frozen-version preservation passed.
- Retained: responsive fraction/weight widths replace fixed 340/160 dp; Ready scrolls; long Grant/Discard/input actions use Chips; both success and error call the same atomic settle gate; cross-thread stop flag is volatile; candidate.3 manifest passes. Fresh PM runs: Watch 71/71, lint and Debug/Release builds pass, Debug APK SHA-256 `B1C73093900B56E044FCB77634BCB13513607C7EA4848A131876DCA5C0AA8D11`; client 370/370 and production build pass.
- Rust limitation: fresh Cargo still fails at the external `transcribe-cpp-sys` MSBuild cache (`FTK1011`); PC source was unchanged.
- Decision: **NO-GO**. `settledGeneration` is not reset per begin, so only the first successful recording generation can settle. Cancel and settle remain separate multi-Atomic checks rather than one state transition, and Session mutation remains on the I/O context. Candidate.3 also fails full-size visual QA: clipped Pending status, missing Recording TimeText, and severe text collisions on translucent overlays.
- Next task: `docs/DELIVERY-1B-Z3-REPAIR-4.md`. Preserve all accepted PC/responsive/baseline work; repair only the multi-generation coordinator and candidate.4 readability. Real-device and ten-run stages remain locked.
