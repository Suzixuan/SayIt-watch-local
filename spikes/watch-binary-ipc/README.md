# Z2 Binary IPC Spike (isolated harness)

Delivery 1B — isolated runtime proof for the frozen Z1 provider contract's single
CONDITIONAL item: Tauri v2 raw binary IPC over the custom-protocol path.

**This is not SayIt product code.** It proves or disproves one transport mechanism;
external WAV ingress, Watch UI, and all other product work remain unauthorized.

## What it proves (binary result)

- Rust command returns `tauri::ipc::Response::new(Vec<u8>)` with a deterministic
  9,438,418-byte payload (9 MiB + 1234 bytes — ≥9 MiB, even length, deliberately not
  a multiple of the 8192-byte / 4096-sample ingest chunk target).
- JavaScript receives it through `@tauri-apps/api` `invoke` as an `ArrayBuffer` with
  identical `byteLength`, matching first/middle/last sentinel bytes (independently
  re-derived from the frozen `byte_at(i) = (i*31+7) & 0xff` formula), and a SHA-256
  equal to the Rust-computed expected hash — with **zero** `customProtocolIpcFailed`
  / postMessage fallback warnings, captured by a programmatic `console.warn` wrapper.
- The proof runs in the **packaged Windows debug WebView2 app** (`tauri build
  --debug --no-bundle`), not a browser or dev server.

## Stack pins

- Rust `tauri = "=2.10.3"` (`src-tauri/Cargo.toml`)
- `@tauri-apps/api = 2.10.1`, `@tauri-apps/cli = 2.10.1` (`package.json`)

## Run

```sh
npm install
npm test                     # vitest: verification logic + ≥9 MiB acceptance case
npm run build                # versions.json + vite build -> dist/
cd src-tauri && cargo test   # payload/hash unit tests
cd .. && npm run spike:build # packaged debug WebView2 harness (no bundle)
./src-tauri/target/debug/spike-binary-ipc.exe
```

The window shows a PASS/FAIL panel with versions, payload length, actual JS type,
SHA-256 match, sentinel result, and the fallback-warning count. PASS requires every
check green and zero fallback warnings.

## Isolation

No SayIt product file is imported or modified: `client/**`, `watch/**`, the existing
receiver, Provider, Orchestrator, History, Paste, AI, and target/focus code are
untouched. `node_modules/`, `dist/`, `src-tauri/target/`, and `src-tauri/gen/` are
build artifacts and are not committed. The payload is never logged or committed —
only numeric metadata and deterministic hashes.
