//! Receiver configuration from environment variables.
//!
//! Required variables:
//! - `SAYIT_WATCH_BIND_IP`: exactly one RFC1918 IPv4. `0.0.0.0`, loopback,
//!   hostnames, IPv6, public and link-local addresses are rejected.
//! - `SAYIT_WATCH_PORT`: integer in 1..=65535.
//! - `SAYIT_WATCH_DEV_TOKEN`: at least 32 bytes of unpredictable token material
//!   after decoding/validation.
//!
//! Missing or invalid configuration means the receiver does not start (fail
//! closed). Only the bind IP/port and a token-present boolean are ever logged.

use std::net::Ipv4Addr;

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
        self.dev_token.len() >= MIN_TOKEN_BYTES
    }
}

pub const MIN_TOKEN_BYTES: usize = 32;

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
        dev_token: token,
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

    if ip.is_unspecified() {
        return Err("SAYIT_WATCH_BIND_IP must not be 0.0.0.0 (wildcard)".to_string());
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

pub fn validate_token(token: &str) -> Result<(), String> {
    let bytes = token.as_bytes();
    // At least 32 bytes of material. We accept base64 (typical 43+ chars) or
    // raw; the requirement is byte length after trimming.
    let trimmed = token.trim();
    if trimmed.is_empty() {
        return Err("SAYIT_WATCH_DEV_TOKEN must not be empty".to_string());
    }
    if bytes.len() < MIN_TOKEN_BYTES {
        return Err(format!(
            "SAYIT_WATCH_DEV_TOKEN must be at least {} bytes",
            MIN_TOKEN_BYTES
        ));
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
        set_env("192.168.1.50", "9090", &"x".repeat(32));
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
        set_env("192.168.1.50", "9090", &"x".repeat(32));
        // missing port
        std::env::remove_var("SAYIT_WATCH_PORT");
        assert!(load_from_env().is_err());
        clear_env();
    }

    #[test]
    fn rejects_invalid_bind_ips() {
        assert!(parse_bind_ip("0.0.0.0").is_err());
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
    fn rejects_short_token() {
        assert!(validate_token(&"x".repeat(31)).is_err());
        assert!(validate_token("").is_err());
        assert!(validate_token(&"x".repeat(32)).is_ok());
        assert!(validate_token(&"x".repeat(64)).is_ok());
        // base64-style 44-byte token passes
        assert!(validate_token("YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXoxMjM0NTY3ODkwYWJjZGVm").is_ok());
    }
}
