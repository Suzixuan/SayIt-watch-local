//! Receiver configuration.
//!
//! Resolution order (fail closed only if every source is missing/invalid):
//! 1. environment variables: `SAYIT_WATCH_BIND_IP`, `SAYIT_WATCH_PORT`,
//!    `SAYIT_WATCH_DEV_TOKEN`;
//! 2. a persisted config file at `%LOCALAPPDATA%\com.sayit.app\watch-receiver.config.json`;
//! 3. bootstrap from a gitignored `.watch-dev-token` in the repo root, using
//!    `0.0.0.0:18099` and then persisting the config file. Allowing `0.0.0.0`
//!    (listen on every interface) plus this fallback lets a user just
//!    double-click `sayit.exe` with no environment setup, and survive a PC
//!    DHCP LAN-IP change.
//!
//! Only the bind IP/port and a token-present boolean are ever logged.

use std::fs;
use std::net::Ipv4Addr;
use std::path::PathBuf;

#[derive(Debug, Clone)]
pub struct ReceiverConfig {
    pub bind_ip: Ipv4Addr,
    pub port: u16,
    /// Validated development token (kept private; never logged).
    pub dev_token: String,
}

impl ReceiverConfig {
    /// True when a validated token is present (the only token fact we log).
    pub fn has_dev_token(&self) -> bool {
        validate_token(&self.dev_token).is_ok()
    }
}

/// Parses and validates configuration. Returns an error for any missing or
/// invalid variable — the receiver then does not start.
pub fn load_from_env() -> Result<ReceiverConfig, String> {
    let ip_raw = std::env::var("SAYIT_WATCH_BIND_IP").map_err(|_| "SAYIT_WATCH_BIND_IP is required".to_string())?;
    let port_raw = std::env::var("SAYIT_WATCH_PORT").map_err(|_| "SAYIT_WATCH_PORT is required".to_string())?;
    let token = std::env::var("SAYIT_WATCH_DEV_TOKEN").map_err(|_| "SAYIT_WATCH_DEV_TOKEN is required".to_string())?;

    let bind_ip = parse_bind_ip(&ip_raw)?;
    let port = parse_port(&port_raw)?;
    validate_token(&token)?;

    Ok(ReceiverConfig {
        bind_ip,
        port,
        dev_token: token.trim().to_string(),
    })
}

/// Full resolution: env vars, then persisted config file, then a bootstrap from
/// the repo-root `.watch-dev-token`. Lets the PC run by double-clicking the exe
/// with no environment setup.
pub fn load() -> Result<ReceiverConfig, String> {
    if let Ok(cfg) = load_from_env() {
        return Ok(cfg);
    }
    if let Ok(cfg) = load_from_config_file() {
        return Ok(cfg);
    }
    let cfg = bootstrap_from_dev_token()?;
    // Persist so later launches read the config file directly.
    if let Err(e) = write_config_file(&cfg) {
        log::warn!("watch receiver: could not persist config file: {}", e);
    }
    Ok(cfg)
}

fn config_file_path() -> PathBuf {
    dirs::data_local_dir()
        .unwrap_or_else(std::env::temp_dir)
        .join("com.sayit.app")
        .join("watch-receiver.config.json")
}

fn load_from_config_file() -> Result<ReceiverConfig, String> {
    let path = config_file_path();
    let txt = fs::read_to_string(&path)
        .map_err(|_| format!("config file not found at {}", path.display()))?;
    let v: serde_json::Value =
        serde_json::from_str(&txt).map_err(|e| format!("invalid config JSON: {e}"))?;
    let ip_raw = v["bind_ip"].as_str().ok_or("config missing bind_ip")?;
    let port = v["port"].as_u64().ok_or("config missing port")?;
    let token = v["dev_token"].as_str().ok_or("config missing dev_token")?;
    let bind_ip = parse_bind_ip(ip_raw)?;
    let port = parse_port(&port.to_string())?;
    validate_token(token)?;
    Ok(ReceiverConfig {
        bind_ip,
        port,
        dev_token: token.trim().to_string(),
    })
}

fn write_config_file(cfg: &ReceiverConfig) -> Result<(), String> {
    let path = config_file_path();
    if let Some(dir) = path.parent() {
        fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    }
    let json = serde_json::json!({
        "bind_ip": cfg.bind_ip.to_string(),
        "port": cfg.port,
        "dev_token": cfg.dev_token,
    });
    fs::write(&path, serde_json::to_string_pretty(&json).map_err(|e| e.to_string())?)
        .map_err(|e| e.to_string())
}

/// Fallback from the repo-root `.watch-dev-token` (gitignored): default
/// `0.0.0.0:18099` so a double-clicked exe just works.
fn bootstrap_from_dev_token() -> Result<ReceiverConfig, String> {
    let repo = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .and_then(|p| p.parent())
        .ok_or("cannot resolve repo root")?;
    let token_path = repo.join(".watch-dev-token");
    let token = fs::read_to_string(&token_path)
        .map_err(|_| format!("SAYIT_WATCH_* env vars missing and no config/token at {}", token_path.display()))?;
    validate_token(&token)?;
    Ok(ReceiverConfig {
        bind_ip: Ipv4Addr::new(0, 0, 0, 0), // listen on all interfaces
        port: 18099,
        dev_token: token.trim().to_string(),
    })
}

/// One RFC1918 IPv4 only; rejects wildcard, loopback, link-local, public,
/// hostnames, and IPv6.
pub fn parse_bind_ip(raw: &str) -> Result<Ipv4Addr, String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Err("SAYIT_WATCH_BIND_IP must not be empty".to_string());
    }
    // IPv6 literal or anything with a colon is rejected outright.
    if trimmed.contains(':') {
        return Err("SAYIT_WATCH_BIND_IP must be a dotted IPv4 address".to_string());
    }
    // Hostnames (non-numeric) are rejected.
    let octets: Vec<&str> = trimmed.split('.').collect();
    if octets.len() != 4 || octets.iter().any(|o| o.is_empty() || !o.chars().all(|c| c.is_ascii_digit())) {
        return Err("SAYIT_WATCH_BIND_IP must be a dotted IPv4 address".to_string());
    }
    let ip: Ipv4Addr = trimmed
        .parse()
        .map_err(|_| "SAYIT_WATCH_BIND_IP must be a valid IPv4 address".to_string())?;

    // 0.0.0.0 binds all interfaces, so a DHCP-driven LAN-IP change on the PC no
    // longer silently breaks the Watch link. Bearer-token auth still guards it.
    if ip.is_unspecified() {
        return Ok(ip);
    }
    if ip.is_loopback() {
        return Err("SAYIT_WATCH_BIND_IP must not be loopback".to_string());
    }
    if ip.is_link_local() {
        return Err("SAYIT_WATCH_BIND_IP must not be link-local".to_string());
    }
    if !is_rfc1918(ip) {
        return Err("SAYIT_WATCH_BIND_IP must be an RFC1918 private IPv4 address".to_string());
    }
    Ok(ip)
}

pub fn parse_port(raw: &str) -> Result<u16, String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Err("SAYIT_WATCH_PORT must not be empty".to_string());
    }
    let port: u32 = trimmed
        .parse()
        .map_err(|_| "SAYIT_WATCH_PORT must be an integer".to_string())?;
    if port == 0 || port > 65535 {
        return Err("SAYIT_WATCH_PORT must be in 1..=65535".to_string());
    }
    Ok(port as u16)
}

/// Frozen Dev Token rule: exactly 64 hexadecimal characters after trimming
/// (32 decoded bytes = 256 bits). Mirrors the Watch's DevTokenValidator.
pub fn validate_token(token: &str) -> Result<(), String> {
    let trimmed = token.trim();
    if trimmed.len() != 64 {
        return Err("SAYIT_WATCH_DEV_TOKEN must be exactly 64 characters (32 bytes of hex)".to_string());
    }
    if !trimmed.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err("SAYIT_WATCH_DEV_TOKEN must be hexadecimal characters only".to_string());
    }
    Ok(())
}

fn is_rfc1918(ip: Ipv4Addr) -> bool {
    let o = ip.octets();
    match o[0] {
        10 => true,
        172 => (16..=31).contains(&o[1]),
        192 => o[1] == 168,
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn set_env(ip: &str, port: &str, token: &str) {
        std::env::set_var("SAYIT_WATCH_BIND_IP", ip);
        std::env::set_var("SAYIT_WATCH_PORT", port);
        std::env::set_var("SAYIT_WATCH_DEV_TOKEN", token);
    }

    fn clear_env() {
        std::env::remove_var("SAYIT_WATCH_BIND_IP");
        std::env::remove_var("SAYIT_WATCH_PORT");
        std::env::remove_var("SAYIT_WATCH_DEV_TOKEN");
    }

    #[test]
    fn loads_valid_configuration() {
        set_env("192.168.1.50", "9090", &"a".repeat(64));
        let cfg = load_from_env().expect("valid env");
        assert_eq!(cfg.bind_ip, Ipv4Addr::new(192, 168, 1, 50));
        assert_eq!(cfg.port, 9090);
        assert!(cfg.has_dev_token());
        clear_env();
    }

    #[test]
    fn fails_on_missing_configuration() {
        clear_env();
        assert!(load_from_env().is_err());
        set_env("192.168.1.50", "9090", &"a".repeat(64));
        // missing port
        std::env::remove_var("SAYIT_WATCH_PORT");
        assert!(load_from_env().is_err());
        clear_env();
    }

    #[test]
    fn rejects_invalid_bind_ips() {
        assert!(parse_bind_ip("127.0.0.1").is_err());
        assert!(parse_bind_ip("169.254.1.1").is_err());
        assert!(parse_bind_ip("8.8.8.8").is_err());
        assert!(parse_bind_ip("1.1.1.1").is_err());
        assert!(parse_bind_ip("localhost").is_err());
        assert!(parse_bind_ip("my-pc").is_err());
        assert!(parse_bind_ip("::1").is_err());
        assert!(parse_bind_ip("fe80::1").is_err());
        assert!(parse_bind_ip("").is_err());
        assert!(parse_bind_ip("192.168.1").is_err());
        assert!(parse_bind_ip("172.32.0.1").is_err());
        assert!(parse_bind_ip("100.64.0.1").is_err());
    }

    #[test]
    fn accepts_rfc1918_bind_ips() {
        // 0.0.0.0 binds all interfaces (listens on whatever address the PC has),
        // so a DHCP LAN-IP change cannot break the Watch link.
        assert_eq!(parse_bind_ip("0.0.0.0").unwrap(), Ipv4Addr::new(0, 0, 0, 0));
        assert_eq!(parse_bind_ip("10.0.0.1").unwrap(), Ipv4Addr::new(10, 0, 0, 1));
        assert_eq!(parse_bind_ip("172.16.0.1").unwrap(), Ipv4Addr::new(172, 16, 0, 1));
        assert_eq!(parse_bind_ip("172.31.255.255").unwrap(), Ipv4Addr::new(172, 31, 255, 255));
        assert_eq!(parse_bind_ip("192.168.1.100").unwrap(), Ipv4Addr::new(192, 168, 1, 100));
    }

    #[test]
    fn rejects_invalid_ports() {
        assert!(parse_port("0").is_err());
        assert!(parse_port("65536").is_err());
        assert!(parse_port("-1").is_err());
        assert!(parse_port("abc").is_err());
        assert!(parse_port("").is_err());
        assert_eq!(parse_port("1").unwrap(), 1);
        assert_eq!(parse_port("65535").unwrap(), 65535);
    }

    #[test]
    fn frozen_64_hex_token_rule() {
        // 63 chars -> reject
        assert!(validate_token(&"a".repeat(63)).is_err());
        // 65 chars -> reject
        assert!(validate_token(&"a".repeat(65)).is_err());
        // empty -> reject
        assert!(validate_token("").is_err());
        // non-hex -> reject
        assert!(validate_token(&"g".repeat(64)).is_err());
        assert!(validate_token(&"-".repeat(64)).is_err());
        // 32 chars (old rule) -> reject
        assert!(validate_token(&"a".repeat(32)).is_err());
        // exactly 64 hex -> accept (lower and upper case)
        assert!(validate_token(&"a".repeat(64)).is_ok());
        assert!(validate_token(&"A".repeat(64)).is_ok());
        assert!(validate_token("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789").is_ok());
        // surrounding whitespace is trimmed
        assert!(validate_token(&format!("  {}  ", "a".repeat(64))).is_ok());
        // 63 hex + whitespace still 63 after trim -> reject
        assert!(validate_token(&format!("  {}  ", "a".repeat(63))).is_err());
    }
}
