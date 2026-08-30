# Delivery 1B — Z2 Binary IPC Spike

Status: unlocked implementation gate. This is an isolated runtime proof, not SayIt product implementation.

Branch: `codex/review-watch-pipeline`

Base: PM acceptance commit containing this task.

## Goal

Prove on the current Windows machine that the pinned Tauri v2 stack can deliver a raw Rust byte response of at least 9 MiB to JavaScript as an identical `ArrayBuffer` through the custom-protocol IPC path, without base64, a JSON number array, or the `customProtocolIpcFailed` postMessage fallback.

The result is binary:

- PASS: the Provider contract automatically upgrades from `CONDITIONAL MATCH` to `MATCH` for the PCM-transfer mechanism, and PM may prepare the external-ingress implementation slice.
- FAIL or ambiguous evidence: stop and return to PM. Do not invent a fallback and do not begin external ingress.

## Isolation boundary

Create a standalone minimal harness under `spikes/watch-binary-ipc/`.

Do not modify `client/src/**`, `client/src-tauri/src/**`, `watch/**`, the existing receiver, Provider, Orchestrator, History, Paste, AI, target/focus code, release configuration, or existing dependency locks.

The harness must pin and report the same stack used by the accepted contract:

- Rust `tauri = 2.10.3`
- JavaScript `@tauri-apps/api = 2.10.1`
- Tauri CLI `2.10.1`

No network service, Watch upload, ASR, pairing, or UI redesign belongs in this slice.

## Required probe

1. The Rust command returns `tauri::ipc::Response::new(Vec<u8>)` with a deterministic payload whose length is at least 9 MiB and deliberately not a round chunk multiple.
2. The JavaScript caller invokes it through `@tauri-apps/api` and verifies at runtime:
   - the result is an `ArrayBuffer`;
   - `byteLength` exactly equals the Rust payload length;
   - first, middle, and last sentinel bytes match;
   - SHA-256 of the complete returned payload equals the deterministic expected SHA-256;
   - the result is not a string, base64 payload, ordinary Array, or JSON number-array object.
3. Before invoking, temporarily wrap `console.warn` and record whether any warning contains `customProtocolIpcFailed` or the Tauri custom-protocol fallback message. Restore `console.warn` afterward even on failure.
4. PASS requires zero fallback warnings. Absence of a visible warning without programmatic capture is insufficient.
5. Run the packaged Windows debug harness, not only a browser/Vite page or Rust unit test. Display a compact PASS/FAIL panel with versions, payload length, actual JS type, SHA-256 match, sentinel result, and fallback-warning count.
6. Do not log or commit the payload. Numeric metadata and deterministic hashes are allowed.

## Automated checks

Add focused tests inside the isolated harness for deterministic payload/hash and for rejection of wrong type, length, sentinel, body/hash, and any fallback warning. Include one acceptance test for the expected ≥9 MiB `ArrayBuffer` without converting it to base64 or a number array.

The real packaged WebView2 run remains mandatory; unit tests alone cannot pass this gate.

## Required verification evidence

Return:

- commit SHA and exact changed-file list;
- `git diff --check` result;
- resolved Rust/JS/CLI versions;
- clean harness install/build/test commands and exit codes;
- packaged debug executable path and SHA-256;
- screenshot of the runtime PASS/FAIL panel;
- numeric result: payload bytes, returned type/bytes, expected/actual SHA-256, sentinel checks, fallback-warning count;
- explicit statement whether `customProtocolIpcFailed` appeared;
- unresolved risks.

Do not commit `node_modules`, `target`, packaged binaries, screenshots, logs, payload dumps, tokens, WAVs, or local paths.

## Allowed tracked files

- `spikes/watch-binary-ipc/**`
- `HANDOFF.md`
- `PROJECT_PROGRESS.md`

No other files.

## Stop conditions

Stop and report PM if the result is not an `ArrayBuffer`, any byte/hash/length differs, any custom-protocol fallback warning appears, the proof works only in a browser/dev server rather than packaged WebView2, the proof requires SayIt product changes or base64/JSON fallback, or the pinned versions cannot be reproduced.

Push the isolated spike and evidence summary, then stop. External WAV ingress remains unauthorized until PM independently reviews the runtime evidence.
