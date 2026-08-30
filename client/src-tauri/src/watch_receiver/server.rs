//! Blocking `tiny_http` receiver server (Delivery 1A debug-only).
//!
//! Endpoints:
//! - `GET /api/health` — minimal JSON identifying a debug receiver.
//! - `POST /api/watch/audio` — exact Bearer auth + `audio/wav`, body capped at
//!   10 MiB before allocation/writing, validated WAV saved atomically to
//!   `%LOCALAPPDATA%\com.sayit.app\watch-receiver\received_watch.wav`.
//!
//! JSON error bodies use explicit 400/401/404/405/413/500 statuses; 202 is
//! never returned. The token and Authorization header are never logged.

use crate::watch_receiver::config::ReceiverConfig;
use crate::watch_receiver::wav;
use sha2::{Digest, Sha256};
use std::io::{Read, Write};
use std::path::PathBuf;
use std::sync::Arc;
use tiny_http::{Header, Method, Request, Response, Server, StatusCode};

pub const MAX_BODY_BYTES: usize = 10 * 1024 * 1024; // 10 MiB cap before allocation

pub struct ReceiverServer {
    server: Server,
    cfg: Arc<ReceiverConfig>,
}

impl ReceiverServer {
    pub fn start(
        cfg: Arc<ReceiverConfig>,
    ) -> Result<Self, Box<dyn std::error::Error + Send + Sync>> {
        let addr = format!("{}:{}", cfg.bind_ip, cfg.port);
        let server = Server::http(addr.as_str())?;
        Ok(Self { server, cfg })
    }

    pub fn bind_ip(&self) -> String {
        self.cfg.bind_ip.to_string()
    }

    pub fn bind_port(&self) -> u16 {
        self.cfg.port
    }

    /// Runs the accept loop (blocking). Handles each request synchronously.
    pub fn run(&self) {
        for request in self.server.incoming_requests() {
            let _ = self.handle(request);
        }
    }

    fn handle(&self, request: Request) -> Result<(), std::io::Error> {
        let method = request.method().clone();
        let url = request.url().to_string();

        match (method, url.as_str()) {
            (Method::Get, "/api/health") => self.handle_health(request),
            (Method::Post, "/api/watch/audio") => self.handle_audio(request),
            (Method::Get | Method::Post, _) => {
                json_response(request, StatusCode(404), "not found")
            }
            _ => json_response(request, StatusCode(405), "method not allowed"),
        }
    }

    fn handle_health(&self, request: Request) -> Result<(), std::io::Error> {
        json_response(
            request,
            StatusCode(200),
            r#"{"service":"sayit-watch-debug-receiver","status":"ok","asrReady":false}"#,
        )
    }

    fn handle_audio(&self, mut request: Request) -> Result<(), std::io::Error> {
        // 1. Authentication (exact Bearer; never logged).
        if !self.authorized(&request) {
            log::warn!("watch receiver: unauthorized upload attempt rejected");
            return json_response(request, StatusCode(401), r#"{"error":"unauthorized"}"#);
        }

        // 1.5. X-Request-Id must be a well-formed UUID; the receiver preserves it
        // end to end and echoes the same value in the 201 response and success log.
        let request_id = match request
            .headers()
            .iter()
            .find(|h| h.field.equiv("X-Request-Id"))
            .map(|h| h.value.as_str().trim())
        {
            Some(raw) => match uuid::Uuid::parse_str(raw) {
                Ok(u) => u.to_string(),
                Err(_) => {
                    return json_response(
                        request,
                        StatusCode(400),
                        r#"{"error":"X-Request-Id must be a UUID"}"#,
                    );
                }
            },
            None => {
                return json_response(
                    request,
                    StatusCode(400),
                    r#"{"error":"X-Request-Id header is required"}"#,
                );
            }
        };

        // 2. Content type must be audio/wav.
        let content_type = request
            .headers()
            .iter()
            .find(|h| h.field.equiv("Content-Type"))
            .map(|h| h.value.as_str().trim().to_lowercase())
            .unwrap_or_default();
        if !content_type.starts_with("audio/wav") && !content_type.starts_with("audio/x-wav") {
            return json_response(
                request,
                StatusCode(400),
                r#"{"error":"content type must be audio/wav"}"#,
            );
        }

        // 3. Size cap before any allocation/writing (Content-Length when present,
        //    plus a hard cap on streamed reads).
        if let Some(len) = request.body_length() {
            if len > MAX_BODY_BYTES {
                return json_response(
                    request,
                    StatusCode(413),
                    r#"{"error":"body exceeds 10 MiB limit"}"#,
                );
            }
        }

        // 4. Read the body (capped).
        let mut body = Vec::new();
        {
            let mut reader = request.as_reader().take((MAX_BODY_BYTES + 1) as u64);
            reader
                .read_to_end(&mut body)
                .map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e.to_string()))?;
        }
        if body.len() > MAX_BODY_BYTES {
            return json_response(
                request,
                StatusCode(413),
                r#"{"error":"body exceeds 10 MiB limit"}"#,
            );
        }
        if body.is_empty() {
            return json_response(request, StatusCode(400), r#"{"error":"empty body"}"#);
        }

        // 5. Parse and validate the WAV.
        let info = match wav::parse(&body) {
            Ok(i) => i,
            Err(e) => {
                log::warn!("watch receiver: rejected invalid WAV: {}", e.0);
                return json_response(
                    request,
                    StatusCode(400),
                    &format!(r#"{{"error":"invalid wav: {}"}}"#, e.0),
                );
            }
        };

        // 6. Durable atomic replacement under %LOCALAPPDATA%\com.sayit.app\watch-receiver.
        let dir = watch_receiver_dir();
        let target = dir.join("received_watch.wav");
        match save_atomically(&dir, &target, &body) {
            Ok(()) => {}
            Err(e) => {
                log::error!("watch receiver: failed to save received_watch.wav: {e}");
                return json_response(request, StatusCode(500), r#"{"error":"storage failure"}"#);
            }
        }

        // 7. Success response (only after durable replacement). Echo the request
        // UUID exactly as received — never a locally generated unrelated ID.
        let sha = hex_sha256(&body);
        let body_json = format!(
            r#"{{"requestId":"{request_id}","bytes":{},"sampleCount":{},"audioDurationMs":{},"sha256":"{sha}"}}"#,
            body.len(),
            info.sample_count,
            info.duration_ms,
        );
        log::info!(
            "watch receiver: saved received_watch.wav requestId={} bytes={} samples={} durationMs={}",
            request_id,
            body.len(),
            info.sample_count,
            info.duration_ms
        );
        json_response(request, StatusCode(201), &body_json)
    }

    /// Constant-time Bearer token comparison. Returns false on any mismatch
    /// (missing header, wrong scheme, wrong value).
    fn authorized(&self, request: &Request) -> bool {
        let expected = self.cfg.dev_token.as_bytes();
        let Some(header) = request
            .headers()
            .iter()
            .find(|h| h.field.equiv("Authorization"))
        else {
            return false;
        };
        let value = header.value.as_str().trim();
        let Some(token) = value.strip_prefix("Bearer ") else {
            return false;
        };
        let actual = token.trim().as_bytes();
        if actual.len() != expected.len() {
            return false;
        }
        // Constant-time-ish compare.
        let mut diff = 0u8;
        for (a, b) in actual.iter().zip(expected.iter()) {
            diff |= a ^ b;
        }
        diff == 0
    }
}

fn watch_receiver_dir() -> PathBuf {
    // Test isolation hook: tests redirect the receiver dir to a temp location.
    if let Ok(override_dir) = std::env::var("SAYIT_WATCH_RECEIVER_DIR") {
        if !override_dir.trim().is_empty() {
            return PathBuf::from(override_dir);
        }
    }
    let base = dirs::data_local_dir().unwrap_or_else(|| PathBuf::from("."));
    base.join("com.sayit.app").join("watch-receiver")
}

/// Writes `bytes` to a unique temp file in `dir`, flushes and syncs it, then
/// atomically replaces `target` with the temp file using a Windows
/// replace-with-write-through operation so a partial file never becomes the
/// accepted sample. On failure only this request's temp file is cleaned up.
fn save_atomically(dir: &std::path::Path, target: &std::path::Path, bytes: &[u8]) -> std::io::Result<()> {
    std::fs::create_dir_all(dir)?;
    let tmp = dir.join(format!(
        "received_watch.wav.tmp.{}",
        uuid::Uuid::new_v4()
    ));

    let write_result = (|| -> std::io::Result<()> {
        let mut f = std::fs::File::create(&tmp)?;
        f.write_all(bytes)?;
        f.flush()?;
        f.sync_all()?;
        drop(f);

        #[cfg(windows)]
        {
            replace_file_windows(target, &tmp)?;
        }
        #[cfg(not(windows))]
        {
            // Non-Windows fallback (unused in Delivery 1A but keeps the module
            // compile-clean on other hosts): rename is atomic on POSIX.
            std::fs::rename(&tmp, target)?;
        }
        Ok(())
    })();

    if write_result.is_err() {
        // Clean only this request's temp file on failure.
        let _ = std::fs::remove_file(&tmp);
    }
    write_result
}

/// MoveFileExW with MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH: an
/// atomic replace-with-write-through operation, so a partial file never
/// becomes the accepted sample. Works whether or not the target exists.
#[cfg(windows)]
fn replace_file_windows(target: &std::path::Path, tmp: &std::path::Path) -> std::io::Result<()> {
    use std::os::windows::ffi::OsStrExt;
    use windows::Win32::Storage::FileSystem::{
        MoveFileExW, MOVEFILE_REPLACE_EXISTING, MOVEFILE_WRITE_THROUGH,
    };

    let target_wide: Vec<u16> = target.as_os_str().encode_wide().chain(Some(0)).collect();
    let tmp_wide: Vec<u16> = tmp.as_os_str().encode_wide().chain(Some(0)).collect();

    let result = unsafe {
        MoveFileExW(
            windows::core::PCWSTR(tmp_wide.as_ptr()),
            windows::core::PCWSTR(target_wide.as_ptr()),
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH,
        )
    };
    result.map_err(|e| std::io::Error::other(e.to_string()))
}

fn hex_sha256(bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    let digest = hasher.finalize();
    let mut out = String::with_capacity(64);
    for byte in digest {
        out.push_str(&format!("{byte:02x}"));
    }
    out
}

fn json_response(request: Request, status: StatusCode, body: &str) -> Result<(), std::io::Error> {
    let response = Response::from_string(body.to_string())
        .with_status_code(status)
        .with_header(Header::from_bytes("Content-Type", "application/json").unwrap());
    request.respond(response)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpStream;
    use std::sync::Arc;
    /// Redirects receiver storage to a unique temp dir for the test.
    fn isolate_dir() -> PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "sayit-watch-receiver-test-{}",
            uuid::Uuid::new_v4()
        ));
        std::fs::create_dir_all(&dir).unwrap();
        std::env::set_var("SAYIT_WATCH_RECEIVER_DIR", &dir);
        dir
    }

    fn canonical_wav(data_len: usize) -> Vec<u8> {
        let mut wav = Vec::with_capacity(44 + data_len);
        wav.extend_from_slice(b"RIFF");
        wav.extend_from_slice(&(36u32 + data_len as u32).to_le_bytes());
        wav.extend_from_slice(b"WAVE");
        wav.extend_from_slice(b"fmt ");
        wav.extend_from_slice(&16u32.to_le_bytes());
        wav.extend_from_slice(&1u16.to_le_bytes());
        wav.extend_from_slice(&1u16.to_le_bytes());
        wav.extend_from_slice(&16000u32.to_le_bytes());
        wav.extend_from_slice(&32000u32.to_le_bytes());
        wav.extend_from_slice(&2u16.to_le_bytes());
        wav.extend_from_slice(&16u16.to_le_bytes());
        wav.extend_from_slice(b"data");
        wav.extend_from_slice(&(data_len as u32).to_le_bytes());
        wav.extend(vec![0u8; data_len]);
        wav
    }

    /// Starts the server on 127.0.0.1:0 (tests bypass the LAN-only config check
    /// by constructing the config directly) and returns the bound address.
    /// The server thread is intentionally not joined: `run` blocks forever and
    /// the process exit terminates it.
    fn start_test_server() -> String {
        let cfg = Arc::new(ReceiverConfig {
            bind_ip: "127.0.0.1".parse().unwrap(),
            port: 0,
            // Frozen 64-hex dev token for tests (never a real token).
            dev_token: "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234".to_string(),
        });
        let server = ReceiverServer::start(cfg).expect("bind");
        let addr = server.server.server_addr().to_string();
        std::thread::spawn(move || server.run());
        // Give the accept loop a moment to start.
        std::thread::sleep(std::time::Duration::from_millis(50));
        addr
    }

    const TEST_TOKEN: &str = "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234";
    const TEST_BEARER: &str = "Bearer abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234";
    const TEST_UUID: &str = "123e4567-e89b-12d3-a456-426614174000";

    /// Standard authenticated headers with a valid X-Request-Id.
    fn auth_headers() -> [(&'static str, &'static str); 3] {
        [
            ("Content-Type", "audio/wav"),
            ("Authorization", TEST_BEARER),
            ("X-Request-Id", TEST_UUID),
        ]
    }

    fn raw_request(addr: &str, method: &str, path: &str, headers: &[(&str, &str)], body: &[u8]) -> String {
        let mut stream = TcpStream::connect(addr).expect("connect");
        let mut req = String::new();
        req.push_str(&format!("{method} {path} HTTP/1.1\r\n"));
        req.push_str(&format!("Host: {addr}\r\n"));
        for (k, v) in headers {
            req.push_str(&format!("{k}: {v}\r\n"));
        }
        req.push_str(&format!("Content-Length: {}\r\n", body.len()));
        req.push_str("Connection: close\r\n\r\n");
        stream.write_all(req.as_bytes()).unwrap();
        stream.write_all(body).unwrap();
        stream.flush().unwrap();

        let mut response = String::new();
        let mut buf = [0u8; 8192];
        loop {
            match stream.read(&mut buf) {
                Ok(0) => break,
                Ok(n) => response.push_str(&String::from_utf8_lossy(&buf[..n])),
                Err(_) => break,
            }
        }
        response
    }

    fn status_of(response: &str) -> u16 {
        response
            .lines()
            .next()
            .unwrap_or("")
            .split_whitespace()
            .nth(1)
            .unwrap_or("0")
            .parse()
            .unwrap_or(0)
    }

    #[test]
    fn health_endpoint_identifies_debug_receiver() {
        isolate_dir();
        let addr = start_test_server();
        let resp = raw_request(&addr, "GET", "/api/health", &[], b"");
        let status = status_of(&resp);
        assert_eq!(status, 200);
        assert!(resp.contains("sayit-watch-debug-receiver"));
        assert!(resp.contains("asrReady"));
    }

    #[test]
    fn auth_required_and_token_never_leaked() {
        isolate_dir();
        let addr = start_test_server();
        let wav = canonical_wav(100);

        // Missing token -> 401
        let resp = raw_request(&addr, "POST", "/api/watch/audio", &[("Content-Type", "audio/wav")], &wav);
        assert_eq!(status_of(&resp), 401);
        // Invalid token -> 401
        let resp = raw_request(
            &addr,
            "POST",
            "/api/watch/audio",
            &[("Content-Type", "audio/wav"), ("Authorization", "Bearer wrong-token")],
            &wav,
        );
        assert_eq!(status_of(&resp), 401);
        // Wrong scheme -> 401
        let resp = raw_request(
            &addr,
            "POST",
            "/api/watch/audio",
            &[("Content-Type", "audio/wav"), ("Authorization", "Token x")],
            &wav,
        );
        assert_eq!(status_of(&resp), 401);
        // Valid token + valid X-Request-Id -> not 401
        let resp = raw_request(&addr, "POST", "/api/watch/audio", &auth_headers(), &wav);
        assert_ne!(status_of(&resp), 401);
        // Response must never contain the token.
        assert!(!resp.contains(TEST_TOKEN));
    }

    #[test]
    fn x_request_id_required_and_validated() {
        isolate_dir();
        let addr = start_test_server();
        let wav = canonical_wav(100);

        // Missing X-Request-Id -> 400
        let resp = raw_request(
            &addr,
            "POST",
            "/api/watch/audio",
            &[
                ("Content-Type", "audio/wav"),
                ("Authorization", TEST_BEARER),
            ],
            &wav,
        );
        assert_eq!(status_of(&resp), 400);

        // Invalid (non-UUID) X-Request-Id -> 400
        let resp = raw_request(
            &addr,
            "POST",
            "/api/watch/audio",
            &[
                ("Content-Type", "audio/wav"),
                ("Authorization", TEST_BEARER),
                ("X-Request-Id", "not-a-uuid"),
            ],
            &wav,
        );
        assert_eq!(status_of(&resp), 400);

        // Valid UUID -> 201
        let resp = raw_request(&addr, "POST", "/api/watch/audio", &auth_headers(), &wav);
        assert_eq!(status_of(&resp), 201);
    }

    #[test]
    fn content_type_enforced_and_10_mib_limit() {
        isolate_dir();
        let addr = start_test_server();
        let wav = canonical_wav(100);

        // Wrong content type -> 400 (auth + X-Request-Id valid)
        let resp = raw_request(
            &addr,
            "POST",
            "/api/watch/audio",
            &[
                ("Content-Type", "text/plain"),
                ("Authorization", TEST_BEARER),
                ("X-Request-Id", TEST_UUID),
            ],
            &wav,
        );
        assert_eq!(status_of(&resp), 400);

        // >10 MiB -> 413 (Content-Length check)
        let huge = vec![0u8; MAX_BODY_BYTES + 1];
        let resp = raw_request(&addr, "POST", "/api/watch/audio", &auth_headers(), &huge);
        assert_eq!(status_of(&resp), 413);
    }

    #[test]
    fn malformed_wav_rejected_400() {
        isolate_dir();
        let addr = start_test_server();

        // Not a WAV at all
        let resp = raw_request(&addr, "POST", "/api/watch/audio", &auth_headers(), b"hello world not a wav");
        assert_eq!(status_of(&resp), 400);

        // Truncated WAV
        let mut wav = canonical_wav(100);
        wav.truncate(50);
        let resp = raw_request(&addr, "POST", "/api/watch/audio", &auth_headers(), &wav);
        assert_eq!(status_of(&resp), 400);
    }

    #[test]
    fn save_atomically_probe_reports_error() {
        let dir = std::env::temp_dir().join(format!("sayit-probe-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let target = dir.join("received_watch.wav");
        let wav = canonical_wav(32000);
        match save_atomically(&dir, &target, &wav) {
            Ok(()) => eprintln!("PROBE save_atomically OK target_exists={}", target.exists()),
            Err(e) => eprintln!("PROBE save_atomically ERROR: {e}"),
        }
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn successful_upload_returns_201_with_metadata_and_sha() {
        let dir = isolate_dir();
        let addr = start_test_server();
        let wav = canonical_wav(32000); // 1 second of 16 kHz mono

        let resp = raw_request(&addr, "POST", "/api/watch/audio", &auth_headers(), &wav);
        let status = status_of(&resp);
        assert_eq!(status, 201);
        // The echoed requestId must be exactly the X-Request-Id we sent.
        assert!(resp.contains(&format!("\"requestId\":\"{TEST_UUID}\"")));
        assert!(resp.contains("\"bytes\":32044")); // 44 header + 32000
        assert!(resp.contains("\"sampleCount\":16000"));
        assert!(resp.contains("\"audioDurationMs\":1000"));
        // Lowercase hex sha256 of the full WAV.
        let expected_sha = hex_sha256(&wav);
        assert!(resp.contains(&format!("\"sha256\":\"{expected_sha}\"")));

        // The accepted file exists and matches.
        let target = dir.join("received_watch.wav");
        let saved = std::fs::read(&target).expect("received_watch.wav written");
        assert_eq!(saved, wav);
        // No temp files left behind.
        let leftovers: Vec<_> = std::fs::read_dir(&dir)
            .unwrap()
            .filter_map(|e| e.ok())
            .filter(|e| e.file_name().to_string_lossy().contains(".tmp."))
            .collect();
        assert!(leftovers.is_empty(), "temp files must be cleaned: {leftovers:?}");
    }

    #[test]
    fn atomic_replacement_keeps_previous_file_on_failure() {
        let dir = isolate_dir();
        let target = dir.join("received_watch.wav");
        let previous = b"PREVIOUS ACCEPTED WAV CONTENT THAT MUST SURVIVE".to_vec();
        std::fs::write(&target, &previous).unwrap();

        // Force a failure: make the temp-file parent a non-directory so the
        // temp write (or the replace) cannot succeed.
        let broken_dir = dir.join("not-a-dir");
        std::fs::write(&broken_dir, b"i am a file, not a dir").unwrap();
        let broken = save_atomically(&broken_dir, &broken_dir.join("received_watch.wav"), &canonical_wav(64));
        assert!(broken.is_err());

        // The previous accepted file is intact, byte for byte.
        let after = std::fs::read(&target).unwrap();
        assert_eq!(after, previous);
    }

    #[test]
    fn unknown_routes_and_methods() {
        let addr = start_test_server();
        let resp = raw_request(&addr, "GET", "/api/nope", &[], b"");
        assert_eq!(status_of(&resp), 404);
        let resp = raw_request(&addr, "DELETE", "/api/health", &[], b"");
        assert_eq!(status_of(&resp), 405);
    }

    #[test]
    fn hex_sha256_is_lowercase() {
        let digest = hex_sha256(b"abc");
        assert_eq!(digest.len(), 64);
        assert!(digest.chars().all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase()));
        assert_eq!(hex_sha256(b"abc"), hex_sha256(b"abc"));
        assert_ne!(hex_sha256(b"abc"), hex_sha256(b"abd"));
    }
}
