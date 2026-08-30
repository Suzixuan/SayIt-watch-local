// Deterministic payload for the Z2 binary-IPC spike.
// The pattern must stay in lockstep with ui/checks.js `expectedByteAt`.

use serde::Serialize;
use sha2::{Digest, Sha256};

/// ≥9 MiB and deliberately NOT a multiple of the contract's recommended
/// 8192-byte (4096-sample) ingest chunk target.
pub const SPIKE_PAYLOAD_LEN: usize = 9 * 1024 * 1024 + 1234; // 9,438,418 bytes
pub const CHUNK_TARGET_SAMPLES: usize = 4096;
pub const CHUNK_TARGET_BYTES: usize = CHUNK_TARGET_SAMPLES * 2; // 8,192

#[inline]
pub fn byte_at(i: usize) -> u8 {
    ((i * 31 + 7) & 0xff) as u8
}

pub fn generate_payload() -> Vec<u8> {
    let mut v = Vec::with_capacity(SPIKE_PAYLOAD_LEN);
    for i in 0..SPIKE_PAYLOAD_LEN {
        v.push(byte_at(i));
    }
    v
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    let digest = hasher.finalize();
    let mut out = String::with_capacity(64);
    for byte in digest {
        out.push_str(&format!("{byte:02x}"));
    }
    out
}

#[derive(Debug, Clone, Serialize)]
pub struct SpikeMeta {
    pub len: usize,
    pub sha256: String,
    pub first: u8,
    pub middle: u8,
    pub last: u8,
}

pub fn spike_meta() -> SpikeMeta {
    let payload = generate_payload();
    let len = payload.len();
    SpikeMeta {
        len,
        sha256: sha256_hex(&payload),
        first: payload[0],
        middle: payload[len / 2],
        last: payload[len - 1],
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn payload_len_is_at_least_nine_mib_and_not_a_chunk_multiple() {
        assert_eq!(SPIKE_PAYLOAD_LEN, 9_438_418);
        assert!(SPIKE_PAYLOAD_LEN >= 9 * 1024 * 1024);
        assert_eq!(SPIKE_PAYLOAD_LEN % 2, 0, "PCM16-like even length");
        assert_ne!(
            SPIKE_PAYLOAD_LEN % CHUNK_TARGET_BYTES,
            0,
            "deliberately not an 8192-byte chunk multiple"
        );
    }

    #[test]
    fn pattern_is_deterministic() {
        let a = generate_payload();
        let b = generate_payload();
        assert_eq!(a, b);
        assert_eq!(byte_at(0), 7);
        assert_eq!(
            byte_at(SPIKE_PAYLOAD_LEN / 2),
            ((SPIKE_PAYLOAD_LEN / 2 * 31 + 7) & 0xff) as u8
        );
        assert_eq!(
            byte_at(SPIKE_PAYLOAD_LEN - 1),
            (((SPIKE_PAYLOAD_LEN - 1) * 31 + 7) & 0xff) as u8
        );
    }

    #[test]
    fn meta_hash_matches_payload_and_sentinels() {
        let payload = generate_payload();
        let m = spike_meta();
        assert_eq!(m.len, payload.len());
        assert_eq!(m.sha256, sha256_hex(&payload));
        assert_eq!(m.first, payload[0]);
        assert_eq!(m.middle, payload[payload.len() / 2]);
        assert_eq!(m.last, payload[payload.len() - 1]);
    }

    #[test]
    fn sha256_known_answer() {
        assert_eq!(
            sha256_hex(b"abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }
}
