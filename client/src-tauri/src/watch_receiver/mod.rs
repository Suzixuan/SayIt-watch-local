//! Delivery 1A debug-only Windows LAN receiver.
//!
//! This module compiles and starts ONLY under `debug_assertions`. Release
//! builds contain no active receiver startup path (see main.rs and the
//! release-guard tests in this module).
//!
//! It binds one explicit RFC1918 LAN IPv4 and serves two endpoints:
//! - `GET /api/health` — minimal JSON identifying a debug receiver.
//! - `POST /api/watch/audio` — authenticated WAV ingest, saved atomically to
//!   `%LOCALAPPDATA%\com.sayit.app\watch-receiver\received_watch.wav`.

pub mod config;
pub mod server;
pub mod wav;

use std::sync::Arc;

/// Starts the debug receiver on a dedicated blocking thread. Returns an error
/// (receiver does not start) when configuration is missing or invalid.
/// Never logs the token.
pub fn start() -> Result<(), Box<dyn std::error::Error>> {
    let cfg = Arc::new(config::load_from_env()?);
    let thread_cfg = Arc::clone(&cfg);
    std::thread::Builder::new()
        .name("watch-receiver".to_string())
        .spawn(move || {
            // tiny_http is fully blocking; the accept loop lives on this thread.
            let server = match server::ReceiverServer::start(thread_cfg) {
                Ok(s) => s,
                Err(e) => {
                    log::error!("watch receiver failed to start: {}", e);
                    return;
                }
            };
            log::info!(
                "watch receiver listening on {}:{} (dev token present: {})",
                server.bind_ip(),
                server.bind_port(),
                cfg.has_dev_token(),
            );
            server.run();
        })?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::fs;

    #[test]
    fn release_guard_start_is_debug_only() {
        // The receiver startup path must be compiled out of release builds.
        // Check the source-level guard that main.rs uses.
        // (cargo test runs with CWD = crate root, i.e. client/src-tauri)
        let main_src = fs::read_to_string("src/main.rs")
            .expect("main.rs must exist next to the watch_receiver module");
        let start_marker = "watch_receiver::start";
        assert!(
            main_src.contains(start_marker),
            "main.rs must call watch_receiver::start"
        );
        let block_start = main_src
            .find(start_marker)
            .expect("start marker present");
        // The start call lives inside `.setup()` (after the event sink is
        // registered), so the enclosing `#[cfg(debug_assertions)]` block may be
        // well over 400 characters earlier — scan back a generous window.
        let window = &main_src[block_start.saturating_sub(4000)..block_start];
        assert!(
            window.contains("#[cfg(debug_assertions)]"),
            "watch_receiver::start must be guarded by #[cfg(debug_assertions)]"
        );
    }

    #[test]
    fn module_gate_is_debug_assertions() {
        // The module declaration in main.rs must also be debug-only.
        let main_src = fs::read_to_string("src/main.rs").expect("main.rs exists");
        let idx = main_src.find("mod watch_receiver;").expect("module declared");
        let window = &main_src[idx.saturating_sub(200)..idx];
        assert!(
            window.contains("#[cfg(debug_assertions)]"),
            "mod watch_receiver must be guarded by #[cfg(debug_assertions)]"
        );
    }
}
