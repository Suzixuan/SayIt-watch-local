# Delivery 1B — Z1 Provider Contract (frozen from source evidence)

- Author: colleague Z (Z1 contract gate).
- Status: submitted, awaiting PM review. Evidence-and-architecture gate only — no product code was changed in this slice.
- Branch: `codex/review-watch-pipeline`, base commit `5cc14b4c8658920cc9ca9b21ab1dcaf878f1a136` (parent `fe096d7` = accepted Delivery 1A PM commit).
- All line references are relative to the repository root at the base commit.

## 0. Verdict

**MATCH.** The proposed external-audio feed (validated Watch WAV → raw PCM → active `TranscriptionProvider` → existing `onFinal` → History → Paste path) is implementable on top of the current source with additive changes only. Every proposed clause of the audio contract is confirmed by source evidence, and the required `409` admission boundary is designable without a second ASR path, without refactoring `RecorderOrchestrator`, and without any new focus-tracking machinery.

Per-clause verdicts:

| # | Proposed contract clause | Verdict |
|---|---|---|
| 1 | Raw PCM only, no WAV header, into `sendAudio` | **CONFIRMED** (capture path sends headerless PCM; the Watch WAV header is stripped once during ingress — §A.3) |
| 2 | 16,000 Hz | **CONFIRMED** (both capture and receiver-side WAV validation enforce it) |
| 3 | Mono | **CONFIRMED** (both sides enforce it) |
| 4 | Little-endian signed i16 | **CONFIRMED** (both sides enforce it) |
| 5 | `ArrayBuffer` chunks | **CONFIRMED** (`sendAudio(buffer: ArrayBuffer)`) |
| 6 | Duration derived from `bytes / 2 / 16000` | **CONFIRMED** (orchestrator and capture code both compute exactly this) |

---

## A. Required source evidence

### A.1 `RecorderOrchestrator` singleton and run lifecycle

- Module-level singleton: `client/src/services/recorder.ts:5` (`let orchestrator = new RecorderOrchestrator()`); every consumer goes through the exported wrappers in that file. There is exactly one orchestrator per WebView process.
- Active provider is a module-level singleton too: `client/src/services/transcription/index.ts:13-14`, lazily created by `getProvider()` (lines 31-36), replaced wholesale by `switchProvider()` (lines 48-77, which disconnects the old provider first). The orchestrator reaches it via `get provider()` (`RecorderOrchestrator.ts:204`).
- Run identity: a monotonically increasing `runSequence` with `activeRunId` pointing at the current generation (`RecorderOrchestrator.ts:126-127`); `runId = ++this.runSequence; this.activeRunId = runId` at lines 1351-1352. `isRunCurrent(runId)` (lines 258-260) is the stale-generation guard used at every async artifact boundary.
- Idempotent teardown: `finishRun(runId)` (lines 262-275) clears `pendingHistoryArtifact` / `timedOutProcessingContext` only when they belong to that generation, and zeroes `activeRunId`. It is reached from every terminal path: cancel recording (834), cancel processing (881), `onDone` (1060), `onError` (1149), paste success (1262), fallback card (1298), empty final (2361), timeout-fallback history write (1990, 1995).
- State machine: `'idle' | 'recording' | 'processing'` with the only transitions `idle→recording`, `recording→processing`, `recording→idle`, `processing→idle` (`types.ts:1`, `RecorderOrchestrator.ts:74-85`, enforced in `transition()` 655-668; invalid transitions are logged and rejected).
- Run creation (`startRecording`, 1317-1648): synchronous preconditions at entry — `state === 'idle'`, `!startRecordingLock`, `!textInsertionInFlight`, `finalizingLateRunId === 0` (1318-1331) — then `provider.isReady()` (1334, not-ready → error card, no run created), then any timed-out leftover generation is discarded with `provider.cancel()` (1344-1349). The provider connect and mic capture run in parallel (1521-1572); `provider.start(promptOpts)` is sent only after both are ready (1580) and `transition('recording')` happens after it (1588).
- Run finalization (`stopRecording`, 1801-2003): waits for capture setup (max 3 s, 1811-1819), stops capture, discards audio shorter than 0.5 s via `provider.cancel()` (1858-1865), otherwise calls `provider.stop({...})` (1876-1886), transitions to `processing` (1894), arms `processingCancelable` (1899) and the processing timeout (1901, 1907-2002).
- Timeout: `computeProcessingTimeoutMs` (`recorder/helpers.ts:108-132`) = 15 s base + 500 ms/audio-second, capped +30 s; ≥30 s floor for non-server modes; cloud API clamped to ≤90 s. On timeout the orchestrator snapshots a `TimedOutProcessingContext` (1913-1922), returns to idle with `preserveLateFinalContext: true` (2001), and keeps a 15 s late-final grace window (`LATE_FINAL_GRACE_MS`, line 87). If no late final arrives within the grace, a fallback empty history entry with the saved audio is written for later re-identification (1935-1999).

### A.2 `TranscriptionProvider` contract — real order and return semantics

Interface: `client/src/services/transcription/types.ts:80-103`.

| Method | Signature | Real semantics (evidence) |
|---|---|---|
| `connect` | `(callbacks) => Promise<void>` (84) | Resolves when the underlying transport is ready; `onReady` reports asr/llm capability (`ServerProvider.ts:18-65`). `BufferedProvider.connect` lets a subclass veto readiness — a falsy subclass hook result leaves `isReady() === false` (`BufferedProvider.ts:30-49`). Failure rejects; orchestrator retries `ensureConnection()` every 5 s (`RecorderOrchestrator.ts:1307-1313`). |
| `isReady` | `() => boolean` (102) | Synchronous, no I/O. Server: WebSocket connected (`ServerProvider.ts:92-94`). Cloud API: cached `this.ready` flag (`CloudAPIProvider.ts:263-265`). Local: model selected **and** downloaded (`LocalProvider.ts:29-47` via `BufferedProvider`). |
| `start` | `(opts) => boolean` (87) | Synchronous send of the session-start command with the full `StartOptions`; `false` means "connected but unusable" and the orchestrator treats it as a start failure (`RecorderOrchestrator.ts:1580-1583` → catch path 1629-1647). `ServerProvider.start` records `activeRunId = opts.runId` and resets it to 0 when the send fails (67-72). |
| `sendAudio` | `(buffer: ArrayBuffer) => void` (93) | Fire-and-forget streaming of raw PCM Int16 chunks; documented as "流式，PCM Int16 ArrayBuffer" (`types.ts:92-93`). Stale chunks are dropped by the orchestrator's `isRunCurrent` guard before the provider ever sees them (`RecorderOrchestrator.ts:1526-1534`). |
| `stop` | `(opts?) => boolean` (96) | Synchronous end-of-audio signal that triggers finalization (server: `sendStop`, `ServerProvider.ts:83-85`, `websocket.ts:506`). `StopOptions` may append `disableAi` (server-mode short-speech skip, `types.ts:58-73`) — it can only turn AI off, never on. |
| `cancel` | `() => void` (90) | Immediate session teardown; underlying tasks may finish naturally but no further callbacks may surface — enforced in the server provider by zeroing `activeRunId`, which gates `onASR/onFinal/onDone/onError` (`ServerProvider.ts:32,40,54,60,74-77`). `cancel()` is valid in any state; server-mode cancels are followed by `ensureConnection()` to isolate a possibly stale socket (`RecorderOrchestrator.ts:854, 895, 1863, 1942`). |
| `disconnect` | `() => void` (99) | Full teardown (transport closed; `BufferedProvider.disconnect` also cancels the session, `BufferedProvider.ts:81-82`). |

Observed call order enforced by the orchestrator: `connect → isReady → start → sendAudio* → stop → (onASR? → onFinal → onDone?) | onError`, with `cancel`/`disconnect` legal at any point. Nothing in the source ever calls `start` twice without an intervening `cancel`.

### A.3 Audio contract — confirm/reject from source evidence

**CONFIRMED in full.** The Provider input is raw PCM, 16,000 Hz, mono, little-endian signed i16, delivered as `ArrayBuffer` chunks, with duration computed as `bytes / 2 / 16000`.

Capture side (`client/src/services/audio.ts`):

- `TARGET_SAMPLE_RATE = 16000` (line 21); the AudioWorklet `PCMProcessor` resamples the device input to that rate (lines 72-124) and converts Float32 → Int16 with `s * 32768 / s * 32767` saturation (127-131), posting the transferable `int16.buffer` (133).
- Mono: only `inputs[0]?.[0]` is read (line 80); the ScriptProcessor fallback is created as `createScriptProcessor(4096, 1, 1)` (291) and the source node is pinned to `channelCount: 1` (524).
- Duration from bytes: both the fallback and worklet paths log `pcmDurationSec = (totalPCMBytes / 2) / 16000` (340, 469); the orchestrator's own duration is `audioSentSamples / 16000` (`RecorderOrchestrator.ts:738-740`) where `audioSentSamples` accumulates `pcmFrame.length` (Int16 samples, 1539).

Receiver side (`client/src-tauri/src/watch_receiver/wav.rs`): the Delivery 1A WAV validator enforces exactly the same envelope before anything is accepted — PCM format 1 (line 120-122), mono (119-121 area: `channels != CHANNELS` → error, with `CHANNELS = 1` at line 18), 16,000 Hz (123-125, `SAMPLE_RATE = 16_000` at line 17), 16-bit (126-128, `BITS_PER_SAMPLE = 16`), consistent block align (2) and byte rate (32000) (129-136), non-empty even-length data chunk (140-149). `WavInfo.sample_count = data_size / 2` and `duration_ms = sample_count * 1000 / 16000` (152-154) — i.e. the receiver already computes duration as bytes/2/16000.

Consequence for ingress: a validated `received_watch.wav` data chunk **is** a valid `sendAudio` payload byte-for-byte; the only transformation required is dropping the RIFF container (header chunks) once. No resampling, no format conversion, no channel folding is ever needed on the PC.

### A.4 Callback association and stale-result rejection

- One callbacks object per run is built by `buildProviderCallbacks()` (`RecorderOrchestrator.ts:900-1154`) and re-supplied on every `connect` (1522). The provider (server mode) additionally filters callbacks through its own `activeRunId !== 0` check (`ServerProvider.ts:32, 40, 54, 60`), so results from a canceled session are dropped at the provider boundary as well.
- Generation guards:
  - `onPartialASR` — only while `state === 'recording'` (902-906).
  - `onASR` (empty-result terminal) — only while `processing`, only if no final was handled yet, only for the current run (908-916).
  - `onFinal` — only while `processing` (997), or through the late-final grace path which requires a still-valid `timedOutProcessingContext` within 15 s (`consumeTimedOutProcessingContext`, 2196-2207; grace check at 2204). Duplicate finals are rejected via `finalHandledInCurrentRun` (1021-1024).
  - `onDone` — only while `processing`, no final handled, no insertion in flight (1054-1057).
  - `onError` — requires `isRunCurrent` **and** state `recording|processing` (1064-1069); stale-session errors are logged and dropped.
- Cancellation beats in-flight results: `cancelRecording`/`cancelProcessing` leave the recording state and invalidate the run **before any await** (825-835, 874-881), so a racing PTT-up or queued provider callback can only be dropped. Server-mode cancel also forces a socket reconnect so a late server frame cannot leak into the next generation.

### A.5 Normal final → History → Paste (including the insertion probe)

`onFinal` → `processFinalResult` (`RecorderOrchestrator.ts:2209-2384`), in order:

1. Generation guard (2215) and context-aware output resolution (2222-2227).
2. History first: subject to `historyEnabled` (2274) and `audioRetentionEnabled` (2279), the run's audio chunks are saved via `saveRecordingAudio` (2283, `services/audioFileService.ts`) and one `addHistory` record is written with `asrText`, `llmText` (post-processed), durations, provider metadata and the history metadata snapshot (2301-2328; `addHistory` at `services/store.ts:326`). `bridge.emit('history-updated')` refreshes the UI (2333).
3. Insertion second: `textInsertionInFlight = true` (2370) → `handleTextInsertion` (2372 → 1158-1281).
4. `handleTextInsertion` uses **the probe snapshot carried by the run's context** (`options.probeResult`, 1180-1181) — the probe that was captured exactly once at `startRecording` time via `captureActiveInsertionTarget(...)` + `bridge.getRecordingContext(includeTextContext)` and stored in `this.cachedProbeResult` (1369-1391; comment at 196-199 explains why: insert into the window focused when the run started, not whatever is focused later). Only if no snapshot exists does it fall back to a live probe (1181).
5. Non-editable target → fallback card (`showFallbackAndReset`, 1224-1236, 1287-1303). Editable target → modifier-key settle wait (1239, 2108-2118), `processingCancelable = false` + Esc disabled before the irreversible paste (1242-1246), then `pasteService.pasteText(text, probe, cachedProtectClipboard)` (1250; `PasteService.ts:54-71` wraps `bridge.pasteText`, `bridge.ts:112`). Success → `finishRun` + idle (1262-1265). Failure → fallback card (1271-1280).

The probe is a one-shot capture per run. There is no continuous focus tracking in the pipeline: `startInsertionTargetTracking` exists only to serve the pre-probe used at recording start, and `textInsertion.ts:316` (`captureActiveInsertionTarget`) is invoked once per run.

### A.6 State inventory an external run must respect

| State | Meaning / evidence | External-run obligation |
|---|---|---|
| `idle` + provider ready | `state === 'idle'` and `provider.isReady()` (`RecorderOrchestrator.ts:1318, 1334`) | The only state from which a run (mic or external) may be created. |
| busy (not ready) | `startRecording` refuses with a not-ready message when `!isReady()` (1334-1340); `notReadyMessage` is mode-specific (317-326) | `409` with reason `provider_not_ready`. |
| busy (not idle) | `recording`/`processing` reject PTT-down (417-425); `startRecording` also refuses while `startRecordingLock`, `textInsertionInFlight`, or `finalizingLateRunId` (1318-1331) | `409` with reason `orchestrator_not_idle`. |
| `recording` | Between `start` and `stop` (1588, 1894); mic chunks flow via `sendAudio` (1533-1534) | External run occupies `recording` for the (short) ingest, feeding validated PCM chunks. |
| `processing` | After `stop` until final handling completes; Esc-cancelable while `processingCancelable` (1899, 858-896); canceled permanently before system paste (1244) | Reused verbatim; external runs get the same thinking overlay, Esc behavior and fallback cards. |
| cancellation | `cancelRecording` (805-856) / `cancelProcessing` (858-896): run invalidated pre-await, history artifacts of the canceled generation discarded (`discardCanceledHistory`, 795-803) | External run must route any cancel through the same paths so artifacts and the admission gate are cleaned up. |
| late final | 15 s grace after timeout; final is processed with `allowInsertionWhenIdle` (997-1019, 2196-2207); grace expiry discards the provider session and writes the fallback history entry (1935-1999) | No change; the gate must stay reserved until the grace path fully resolves (it ends in `finishRun`). |
| timeout | `computeProcessingTimeoutMs` (helpers 108-132), armed at 1907 | No change. |
| text insertion | `textInsertionInFlight` blocks new runs and `onDone` (1318-1331, 1057); timeout extends while insertion is active (1909-1912) | No change; external runs must set/clear it exactly as mic runs do (2370-2383). |

### A.7 AI-cleanup disable setting and freezing `disableAi: true`

- The setting key is **`aiEnabled`**: read at `RecorderOrchestrator.ts:572` (`getSetting('aiEnabled', false)`) into `cachedAiEnabled` (175, 590); written only through `stores/aiEnabled.ts` (`setAiEnabled` → `setSetting('aiEnabled', next)` + `setAiEnabledCache(next)`), with the tray toggle broadcast handled in the same store. There is also a per-run short-speech skip: `aiMinDurationSec` (177, 591, 1873-1875).
- How a run disables AI today: `StartOptions.disableAi` (`transcription/types.ts:44`) is computed as `disableAi: !this.cachedAiEnabled` (1493, 1504) and, when AI is off, `systemPrompt` is deliberately omitted (`systemPrompt: this.cachedAiEnabled ? … : undefined`, 1492). Server mode may additionally append `disableAi` at stop time (`StopOptions.disableAi`, types 65; orchestrator 1876-1886) — the server runs AI right after ASR, so stop is the last decision point. Providers therefore already implement a fully data-driven AI-off path.
- Freezing `disableAi: true` for an external Watch run: pass `disableAi: true` in **both** the external run's `StartOptions` and `StopOptions`, and omit `systemPrompt` — mirroring lines 1492-1493. No AI feature is deleted and the user's `aiEnabled` preference is neither read-modified nor persisted; the freeze is per-run, exactly like the existing short-speech skip. Provider selection (`getProvider()`) and provider code are untouched.

---

## B. Required boundary design — race-safe `409` admission between the Rust receiver and the frontend Orchestrator

### B.1 Topology

The authoritative busy/ready state lives in the WebView (`RecorderOrchestrator` + `getProvider()`); the blocking receiver lives on its own Rust thread (`watch_receiver/mod.rs:21-44`, tiny_http accept loop, `server.rs:45-49`). The smallest boundary that gives the receiver a synchronous admission decision is a **one-shot admission request across the existing Tauri event/IPC bridge, with the decision made atomically inside a single synchronous JS task**.

New pieces (all debug-only, under the same `#[cfg(debug_assertions)]` gate as the receiver):

1. **Rust `AdmissionGate`** (`watch_receiver/admission.rs`, new, ~100 lines): `Arc<Mutex<GateState>>` with `Idle | Reserved { request_id: String }`, plus the pending oneshot reply slot. Registered as managed Tauri state; the receiver thread and the three new commands share it.
2. **Receiver insertion point** in `handle_audio` (`server.rs:73-194`): exactly one new step between WAV validation (step 5, 154-164) and the durable save (step 6, 166-175). Everything before and after is unchanged.
3. **Three new Tauri commands** (debug-only):
   - `watch_admission_resolve(request_id, accepted)` — completes the receiver's blocking oneshot with the JS decision.
   - `watch_admission_release(request_id)` — returns the gate to `Idle` (receiver save-failure path, run completion, watchdog).
   - `watch_read_reserved_pcm(request_id)` — reads the durably saved `received_watch.wav`, strips the validated header, and returns the raw PCM data-chunk bytes plus `{ sampleRate: 16000, channels: 1, bitsPerSample: 16 }` as a **binary IPC response** (Tauri v2 raw-payload invoke → `ArrayBuffer`; no base64).
4. **Two new Rust→JS events**: `watch://admission-request { requestId, bytes, sampleCount, durationMs }` and `watch://audio-ready { requestId }` (correlation key is the already-mandatory `X-Request-Id` UUID, `server.rs:82-105`).
5. **JS ingress module** (`services/watchIngress.ts`, new, thin): listens for the two events, calls the three commands, and delegates into two small additive orchestrator methods.
6. **Additive orchestrator surface** (`RecorderOrchestrator`):
   - `tryReserveExternalRun(requestId): boolean` — **fully synchronous**: checks `state === 'idle'`, `!startRecordingLock`, `!textInsertionInFlight`, `finalizingLateRunId === 0`, `provider.isReady()`, no existing reservation; on success captures the insertion probe + app context once (`captureActiveInsertionTarget` + `bridge.getRecordingContext(false)`), creates the runId, sets `activeRunId`, and records `externalReservation = { requestId, runId }`. No `await` anywhere inside.
   - `beginExternalRun(requestId, pcm: ArrayBuffer)` — async: transitions `idle→recording`, feeds the PCM in chunks through the same `sendAudio` path, then performs the `stopRecording` tail (`provider.stop({ disableAi: true, pttHoldMs: durationMs })`, `transition('processing')`, processing timeout, thinking overlay) — after which the ordinary `onFinal → History → Paste` machinery takes over unchanged.
   - One added synchronous guard clause in `startRecording`'s existing precondition block (1318-1331): refuse when `externalReservation` is set.
   - One hook in `finishRun` (262-275): when the finished generation is the external run, clear `externalReservation` and fire-and-forget `watch_admission_release(requestId)`. Because every terminal path (success, error, cancel, empty, timeout-fallback, late-final) funnels through `finishRun` (§A.1), this single hook keeps the gate consistent.

### B.2 Atomicity argument

- **JS-side check-and-reserve is one macrotask.** The WebView event loop is single-threaded; `tryReserveExternalRun` contains no `await`, so a PTT-down event (a separate macrotask, `RecorderOrchestrator.ts:407-426`) can never interleave between the busy/ready check and the reservation. The mirror-image guard added to `startRecording` closes the reverse race (external reservation held → PTT refused) at its existing synchronous entry block. The two race directions the task names are therefore structurally eliminated, not merely narrowed.
- **Rust-side gate serializes everything else.** The receiver handles one request at a time (blocking accept loop, `server.rs:45-49`), so admission requests cannot pile up; the `Mutex<GateState>` additionally makes `Reserved` visible to the receiver thread, the commands, and (optionally) `/api/health` regardless of event ordering. A second upload arriving while a previous external run is still processing finds `Reserved` and gets `409` without touching the WebView.
- **Fail-closed.** If the WebView does not answer the admission request within 5 s (busy loading a local model, suspended, crashed), the receiver times out and answers `409 {"error":"busy","detail":"bridge_timeout"}` — it never assumes ready, and never 201s. The same fail-closed rule covers `watch_admission_resolve` reporting a rejected decision.
- **A JS-side watchdog** (e.g. 30 s, well above the 15 s late-final grace) releases a stale reservation if the post-201 handoff (`watch://audio-ready`) is lost, so a dead run can never wedge the gate shut.

### B.3 Modified `handle_audio` order (only step 5.5 and one release path are new)

1. Bearer auth → `401` (unchanged, 75-78).
2. `X-Request-Id` UUID → `400` (unchanged, 82-105).
3. Content-Type `audio/wav` → `400` (unchanged, 108-120).
4. 10 MiB cap → `413` (unchanged, 124-148). Note the cap already accommodates the maximum run the pipeline accepts: `MAX_RECORDING_SEC = 300 s` (`recorder/types.ts:53`) × 32,000 B/s = 9.6 MiB < 10 MiB.
5. WAV validation → `400` (unchanged, 154-164); guarantees PCM/mono/16 kHz/16-bit (§A.3).
6. **NEW — admission:** emit `watch://admission-request`, block on the oneshot (≤5 s). Rejected / timeout → **`409`** `{"error":"busy","detail":"orchestrator_not_idle" | "provider_not_ready" | "bridge_timeout" | "already_reserved","requestId":…}`. Gate stays `Idle`; nothing was written to disk, so the previously accepted `received_watch.wav` remains intact and the Watch WAV is trivially retryable.
7. Durable atomic save → `500` on failure (unchanged, 166-175); on failure the receiver additionally calls `watch_admission_release(request_id)` so the reserved run does not leak.
8. **NEW — handoff:** emit `watch://audio-ready { requestId }` (safe to emit before responding: the file is already `sync_all`-ed by `save_atomically`, 239-271).
9. `201` with the existing metadata body (unchanged, 179-193). The contract "201 only after durable save **and** acceptance" holds: acceptance (reservation) happened at step 6, the save at step 7.

### B.4 PCM transfer and chunking (cost accounting, no base64)

- Transfer path: validated bytes (already in receiver memory) → durable file → `watch_read_reserved_pcm` reads it back and returns the data-chunk slice via binary IPC. Copies: one disk read + one IPC transfer into the WebView, then the chunk slices handed to `sendAudio`. The RIFF header is stripped exactly once, in Rust, using offsets already proven by `wav::parse` — the WebView never parses the container.
- Rejected alternative: carrying PCM in the admission-resolve command body (JSON/base64) would add ~33 % size inflation plus JSON serialization on up to 9.6 MiB, and would couple the admission decision to a multi-megabyte payload. Rejected alternative: keeping the WAV header and shipping it to `sendAudio` — violates the confirmed raw-PCM clause (§A.3).
- Chunking: PC-internal chunk size is a free parameter; the contract permits reusing the capture chunk size. Recommendation: fixed 4096-sample (8 KiB) chunks — the same buffer the ScriptProcessor fallback uses (`audio.ts:290-291`) — with a `setTimeout(0)` yield between chunks so the ingest loop never starves the event loop (keeps `onPartialASR` streaming and the overlay responsive). ~1,200 sends for a 5-minute file; each `sendAudio` is an O(chunk) buffer handoff.
- The Watch upload stays whole-file HTTP (no streaming); only PC-internal feeding is chunked.

### B.5 Probe, reuse, and retryability

- **Probe exactly once at admission:** `tryReserveExternalRun` performs the same one-shot capture `startRecording` does (`captureActiveInsertionTarget` + `getRecordingContext`, 1369-1391) and stores it as the run's probe snapshot; it flows into `processFinalResult` via the run context (1047) exactly like a mic run's. `includeTextContext` is hard-false for external runs: AI is disabled for them, so editor text is never read (privacy boundary matches actual behavior, cf. comment at 1374-1376). No Target Manager, no Focus Tracking, no Target Lock — nothing new tracks the user.
- **Full reuse:** the external run uses the active provider instance (`getProvider()`, `index.ts:31-36`), the same `buildProviderCallbacks()` object (900), the same History write (`processFinalResult` 2301-2328 — the Watch audio lands in history with `saveRecordingAudio` exactly like mic audio), the same `PasteService`/paste path, the same overlay states, Esc handling, late-final grace and timeout machinery. No second ASR path exists anywhere in the design.
- **AI freeze:** `StartOptions.disableAi = true` + `StopOptions.disableAi = true` + omitted `systemPrompt` for the external run (§A.7).
- **Failed admission leaves the WAV retryable:** admission precedes the save, so a `409` never overwrites the previously accepted file and no run artifacts are created; the Watch simply re-sends later. A post-admission save failure releases the gate and returns `500` (no partial run exists — the run is abandoned before `beginExternalRun` is ever called). If the run later fails mid-flight, the ordinary `onError`/cancel paths plus the `finishRun` hook tear everything down; no duplicate run can remain because admission is guarded by the same `state === 'idle'` precondition as mic runs.

### B.6 Request-ID and error crossing without secrets

- `X-Request-Id` (UUID, already mandatory and echoed end-to-end in the 201, `server.rs:80-105, 179-193`) is the single correlation key across HTTP, both events, all three commands, and every `addRuntimeEvent` log line. No new ID scheme is introduced.
- Error bodies and log lines carry only: status code, fixed reason strings (`unauthorized`, `busy` + one of four fixed details, `storage failure`, …), the requestId, and numeric metadata (bytes, sampleCount, durationMs, sha256). Transcript text, PCM, tokens, editor text and the Authorization header are never logged — this preserves the existing receiver rule ("The token and Authorization header are never logged", `server.rs:9-11`) and the orchestrator's length-only logging (`RecorderOrchestrator.ts:1209, 2268`).

---

## C. Ordered event lists (sequence diagrams)

### C.1 Accepted request

```
Watch          Receiver(Rust)                    Gate            WebView(JS)
  |  POST /api/watch/audio  |                      |                 |
  |------------------------->| auth/id/type/size    |                 |
  |                          | wav::parse OK        |                 |
  |                          |--- admission-request (event) --------->|
  |                          |                      |<-- tryReserveExternalRun (sync):
  |                          |                      |    idle? ready? no reservation?
  |                          |                      |    probe captured once, runId assigned,
  |                          |                      |    externalReservation set
  |                          |<-- resolve(accepted) (command)         |
  |                          | Reserved{requestId}  |                 |
  |                          | save_atomically OK   |                 |
  |                          |--- audio-ready (event) ---------------->|
  |                          |                      |                 | watch_read_reserved_pcm
  |<-------- 201 {requestId, bytes, sampleCount, durationMs, sha256}   | (binary PCM back)
  |                          |                      |                 | beginExternalRun:
  |                          |                      |                 |   idle→recording, sendAudio*,
  |                          |                      |                 |   stop(disableAi) → processing
  |                          |                      |                 | onFinal → History → Paste
  |                          |                      |<-- release -----| (finishRun hook → Idle)
```

### C.2 Busy / not-ready request

```
  |  POST /api/watch/audio   |                      |                 |
  |------------------------->| auth/id/type/size OK |                 |
  |                          | wav::parse OK        |                 |
  |                          |--- admission-request (event) --------->|
  |                          |                      |                 | tryReserveExternalRun (sync):
  |                          |                      |                 |   state!=='idle' OR !isReady()
  |                          |                      |                 |   OR reservation exists → false
  |                          |<-- resolve(rejected) (command)         |
  |<--- 409 {"error":"busy","detail":"orchestrator_not_idle"|"provider_not_ready"|"already_reserved","requestId":…}
  |                          |                      |    (no disk write, previous WAV intact, retryable)
  |
  (bridge_timeout variant: receiver gives up after 5 s with no JS answer → 409 "bridge_timeout")
```

### C.3 Cancellation or failure after admission

```
  (after 201, external run is in recording/processing)
  case A – provider onError:
    WebView: onError → error history entry (failReasonCode) → finishRun → resetToIdle
           → finishRun hook: release(requestId) → gate Idle
  case B – Esc during processing (processingCancelable):
    WebView: cancelProcessing → provider.cancel, canceled-history discard → finishRun → release → Idle
  case C – processing timeout, no late final:
    WebView: timeout → idle (late context preserved) → 15 s grace expires
           → provider.cancel + fallback history entry (audio saved for retry) → finishRun → release → Idle
  case D – save fails after admission (before 201):
    Receiver: release(requestId) → 500 {"error":"storage failure"}; no run was ever started
  case E – WebView lost after 201 (audio-ready never handled):
    WebView watchdog (30 s) → release(requestId) → gate Idle; that run's audio is lost (see Risks 5)
```

### C.4 Successful `onFinal → History → Paste` completion

```
  WebView: onFinal(FinalResult)
    → generation + duplicate guards (finalHandledInCurrentRun, isRunCurrent)
    → processFinalResult:
        historyEnabled/audioRetentionEnabled checks
        → saveRecordingAudio(recordId, externalChunks)   (Watch audio in history, same as mic)
        → addHistory({asrText, llmText, durations, providerMeta, appMeta})
        → history-updated event
    → textInsertionInFlight = true
    → handleTextInsertion(probe snapshot from admission):
        probe.editable? → modifier settle wait → Esc disabled → pasteText(text, probe, protectClipboard)
        → ok:     finishRun → release(requestId) → Idle
        → failed: fallback card (copy/dismiss) → finishRun → release(requestId) → Idle
```

---

## D. Stop-condition evaluation (task §Stop conditions)

1. "Provider input is not raw 16 kHz mono LE i16 PCM" — **Not triggered** (§A.3: both capture and `wav.rs` enforce exactly this envelope).
2. "A normal run cannot be created without bypassing or substantially refactoring `RecorderOrchestrator`" — **Not triggered**: the design adds one sibling entry method pair, one guard clause, and one `finishRun` hook. The state machine, callback wiring, History/Paste flow and timeout machinery are reused untouched.
3. "`onASR/onFinal` cannot safely reuse the current History/Paste path" — **Not triggered** (§A.4, §A.5): external runs are indistinguishable from mic runs downstream of `start`.
4. "Exact 409 admission requires a new second Provider, direct `local_transcribe`, or duplicated ASR/History/Paste logic" — **Not triggered** (§B): the gate consults the existing singletons; no provider is created and no pipeline logic is duplicated.
5. "The proposed bridge introduces a focus tracker, persistent target lock, Watch streaming, or release HTTP receiver" — **Not triggered** (§B.5): one-shot probe at admission; whole-file HTTP; receiver remains debug-only.

---

## E. Minimal implementation file list proposed for the next Z slice

| File | Change |
|---|---|
| `client/src-tauri/src/watch_receiver/admission.rs` | **New.** `AdmissionGate` (`Idle`/`Reserved{request_id}`), oneshot plumbing, release semantics; unit tests (accept/reject/timeout/double-resolve/stale-release). |
| `client/src-tauri/src/watch_receiver/server.rs` | **Modify.** Insert admission step 5.5 + `audio-ready` emit + release-on-save-failure; add `409`/release integration tests alongside the existing ones. |
| `client/src-tauri/src/main.rs` | **Modify (debug-only).** Register the gate as managed state; pass a handle to the receiver thread. |
| `client/src/services/watchIngress.ts` | **New.** Event listeners (`watch://admission-request`, `watch://audio-ready`), command wrappers, watchdog. |
| `client/src/services/recorder/RecorderOrchestrator.ts` | **Modify (additive).** `tryReserveExternalRun`, `beginExternalRun`, one guard clause in `startRecording`, one hook in `finishRun`. |
| `client/src/services/recorder.ts` | **Modify.** Thin delegation for the two new entry points. |
| Vitest additions | Reserve-atomicity (PTT-vs-admission interleave via mocked macrotasks), ingest→stop→final ordering, gate release on every terminal path. |

Not touched: any `TranscriptionProvider*` file, History/store, `PasteService`, text insertion, AI, settings, update/backup/storage-security, watch app, release builds.

---

## F. Risks and unanswered questions

1. **Bridge latency on the hot path.** Admission depends on one WebView round-trip (5 s timeout, fail-closed). While the WebView is busy (e.g. loading a local model), uploads get `409` even though the receiver itself is healthy. Accepted trade-off; the Watch can retry.
2. **Provider mode switch during a reserved run.** `switchProvider` disconnects the old provider unconditionally (`index.ts:48-77`) — a pre-existing hazard for mic runs in `recording`/`processing` too. Unchanged by Z1; recommended follow-up (out of scope): defer mode switches while not idle.
3. **Watch-side retry semantics.** A Watch retry after a `201` (but before completion) gets `409` and stays retryable afterwards; a retry after full completion would be admitted as a **new** run (duplicate transcript). Suppression beyond the busy window is out of scope for Z1 — PM should confirm the Watch app only retries on transport errors, not on success.
4. **Single-slot storage.** `received_watch.wav` is one file, atomically replaced (`server.rs:166-175`); a second Watch is out of scope, same as Delivery 1A.
5. **Lost post-201 handoff** (WebView reloaded between reservation and `audio-ready`): the watchdog releases the gate, but that run's audio is lost (the slot will be overwritten by the next accepted upload). If PM wants stronger guarantees, the receiver could reject new uploads while `Reserved` past a longer deadline — cost: a wedged gate on a dead WebView. Left as a PM decision.
6. **Binary IPC verification.** `watch_read_reserved_pcm` relies on Tauri v2 raw binary responses (no base64) for an up-to-9.6 MiB payload. This must be verified early in the implementation slice against the pinned Tauri version; fallback (chunked base64 commands) is documented with its +33 % cost but should not be needed.
7. **`/api/health` `asrReady:false`** is hardcoded (`server.rs:69`). Exposing the gate's `Idle`/`Reserved` state as a new field would be a cheap diagnostic, but changes the health contract — left to PM.
8. **Overlay UX for external runs.** Reusing the pipeline means Watch runs show the same thinking/canceled/fallback overlay as mic runs. Assumed desirable (consistency + Esc cancel works); PM may prefer a distinct indicator later.
9. **Local mode readiness.** Admission's `provider.isReady()` covers the local model-downloaded case (`BufferedProvider.ts:87`); a 5-minute Watch WAV (9.6 MiB) plus a resident local model fits memory budgets, but end-to-end local-mode acceptance is device work outside Z1.

---

*End of Z1 contract document. Implementation is explicitly NOT authorized until the PM reviews and freezes this contract.*
