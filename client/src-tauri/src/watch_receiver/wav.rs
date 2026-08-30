//! Safe RIFF/WAV parsing for the debug receiver.
//!
//! Requirements enforced here:
//! - RIFF container with a `WAVE` format tag.
//! - `fmt ` chunk: PCM (format 1), one channel, 16,000 Hz, 16 bits.
//! - Consistent block alignment (channels * bytes-per-sample) and byte rate.
//! - Non-empty even-length `data` chunk; no truncated chunks.
//! - Unknown chunks and even-byte padding are handled safely.

use std::fmt;

#[derive(Debug)]
pub struct WavInfo {
    pub data_size: usize,
    pub sample_count: u64,
    pub duration_ms: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WavError(pub String);

impl fmt::Display for WavError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.0)
    }
}

impl std::error::Error for WavError {}

const SAMPLE_RATE: u32 = 16_000;
const CHANNELS: u16 = 1;
const BITS_PER_SAMPLE: u16 = 16;
const BYTES_PER_SAMPLE: u32 = 2;
const BLOCK_ALIGN: u16 = CHANNELS * BYTES_PER_SAMPLE as u16; // 2
const BYTE_RATE: u32 = SAMPLE_RATE * BLOCK_ALIGN as u32; // 32000

/// Parses and validates a full WAV. Returns the validated data chunk info.
pub fn parse(bytes: &[u8]) -> Result<WavInfo, WavError> {
    if bytes.len() < 12 {
        return Err(WavError("too small to be a RIFF file".into()));
    }
    if &bytes[0..4] != b"RIFF" {
        return Err(WavError("missing RIFF magic".into()));
    }
    if &bytes[8..12] != b"WAVE" {
        return Err(WavError("missing WAVE tag".into()));
    }
    let declared_riff_size = read_u32_le(&bytes[4..8]) as usize;
    // RIFF size field = file extent - 8. The declared extent must match the
    // accepted file extent exactly, apart from one valid padding rule: the
    // RIFF container size does not include the container's own trailing pad
    // byte, so a file whose body is one byte longer than the declared extent
    // is tolerated (the extra byte is the container pad).
    let file_body = bytes.len().saturating_sub(8);
    let declared_extent = 8usize
        .checked_add(declared_riff_size)
        .ok_or_else(|| WavError("RIFF size overflow".into()))?;
    if declared_extent != bytes.len() && declared_extent + 1 != bytes.len() {
        return Err(WavError(format!(
            "RIFF declared extent {declared_extent} does not match file extent {}",
            bytes.len()
        )));
    }
    // Bound every chunk walk to the declared extent, never to bytes.len()
    // after a shorter declaration.
    let walk_bound = declared_extent;

    let mut offset = 12usize;
    let mut fmt: Option<(u16, u16, u32, u32, u16, u16)> = None; // format, channels, rate, byte_rate, block_align, bits
    let mut data_size: Option<usize> = None;
    let mut data_absolute: Option<usize> = None;

    while offset + 8 <= walk_bound {
        let chunk_id = &bytes[offset..offset + 4];
        let chunk_size = read_u32_le(&bytes[offset + 4..offset + 8]) as usize;
        let payload_start = offset + 8;
        // A chunk must fit inside the declared extent. Even-byte padding means
        // a chunk may legitimately occupy chunk_size + (chunk_size & 1) bytes.
        let padded_size = chunk_size.checked_add(chunk_size & 1).ok_or_else(|| WavError("chunk size overflow".into()))?;
        if payload_start.checked_add(padded_size).map(|e| e > walk_bound).unwrap_or(true) {
            return Err(WavError("truncated chunk".into()));
        }
        let payload = &bytes[payload_start..payload_start + chunk_size];

        match chunk_id {
            b"fmt " => {
                if payload.len() < 16 {
                    return Err(WavError("fmt chunk too small".into()));
                }
                let audio_format = read_u16_le(&payload[0..2]);
                let channels = read_u16_le(&payload[2..4]);
                let sample_rate = read_u32_le(&payload[4..8]);
                let byte_rate = read_u32_le(&payload[8..12]);
                let block_align = read_u16_le(&payload[12..14]);
                let bits = read_u16_le(&payload[14..16]);
                if fmt.is_some() {
                    return Err(WavError("duplicate fmt chunk".into()));
                }
                fmt = Some((audio_format, channels, sample_rate, byte_rate, block_align, bits));
            }
            b"data" => {
                if data_size.is_some() {
                    return Err(WavError("duplicate data chunk".into()));
                }
                data_size = Some(chunk_size);
                data_absolute = Some(payload_start);
            }
            _ => { /* unknown chunk: skip safely */ }
        }
        offset = payload_start + padded_size;
    }

    let (audio_format, channels, sample_rate, byte_rate, block_align, bits) =
        fmt.ok_or_else(|| WavError("missing fmt chunk".into()))?;

    if audio_format != 1 {
        return Err(WavError(format!("unsupported audio format {audio_format}; PCM (1) required")));
    }
    if channels != CHANNELS {
        return Err(WavError(format!("unsupported channel count {channels}; mono required")));
    }
    if sample_rate != SAMPLE_RATE {
        return Err(WavError(format!("unsupported sample rate {sample_rate}; 16000 Hz required")));
    }
    if bits != BITS_PER_SAMPLE {
        return Err(WavError(format!("unsupported bit depth {bits}; 16 required")));
    }
    if block_align != BLOCK_ALIGN {
        return Err(WavError(format!("inconsistent block alignment {block_align}; expected {BLOCK_ALIGN}")));
    }
    if byte_rate != BYTE_RATE {
        return Err(WavError(format!("inconsistent byte rate {byte_rate}; expected {BYTE_RATE}")));
    }

    let data_size = data_size.ok_or_else(|| WavError("missing data chunk".into()))?;
    if data_size == 0 {
        return Err(WavError("empty data chunk".into()));
    }
    if data_size % 2 != 0 {
        return Err(WavError("odd data chunk size; PCM16 must be even".into()));
    }
    // The data payload must actually be present within the declared extent.
    let data_absolute = data_absolute.ok_or_else(|| WavError("missing data chunk payload".into()))?;
    if data_absolute.checked_add(data_size).map(|e| e > walk_bound).unwrap_or(true) {
        return Err(WavError("data chunk exceeds declared extent".into()));
    }

    let sample_count = (data_size as u64) / (BYTES_PER_SAMPLE as u64);
    let duration_ms = (sample_count * 1000) / (SAMPLE_RATE as u64);

    Ok(WavInfo {
        data_size,
        sample_count,
        duration_ms,
    })
}

fn read_u16_le(b: &[u8]) -> u16 {
    u16::from_le_bytes([b[0], b[1]])
}

fn read_u32_le(b: &[u8]) -> u32 {
    u32::from_le_bytes([b[0], b[1], b[2], b[3]])
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Builds a canonical 16 kHz mono PCM16 WAV with `data_len` payload bytes.
    fn canonical_wav(data_len: usize) -> Vec<u8> {
        let mut wav = Vec::with_capacity(44 + data_len);
        wav.extend_from_slice(b"RIFF");
        wav.extend_from_slice(&(36u32 + data_len as u32).to_le_bytes());
        wav.extend_from_slice(b"WAVE");
        wav.extend_from_slice(b"fmt ");
        wav.extend_from_slice(&16u32.to_le_bytes());
        wav.extend_from_slice(&1u16.to_le_bytes()); // PCM
        wav.extend_from_slice(&1u16.to_le_bytes()); // mono
        wav.extend_from_slice(&16000u32.to_le_bytes());
        wav.extend_from_slice(&32000u32.to_le_bytes());
        wav.extend_from_slice(&2u16.to_le_bytes());
        wav.extend_from_slice(&16u16.to_le_bytes());
        wav.extend_from_slice(b"data");
        wav.extend_from_slice(&(data_len as u32).to_le_bytes());
        wav.extend(vec![0u8; data_len]);
        wav
    }

    #[test]
    fn parses_valid_wav() {
        let wav = canonical_wav(32000);
        let info = parse(&wav).expect("valid wav");
        assert_eq!(info.data_size, 32000);
        assert_eq!(info.sample_count, 16000);
        assert_eq!(info.duration_ms, 1000);
    }

    #[test]
    fn rejects_too_small() {
        assert!(parse(&[]).is_err());
        assert!(parse(b"RIFF").is_err());
    }

    #[test]
    fn rejects_wrong_magic_or_tag() {
        let mut wav = canonical_wav(100);
        wav[0..4].copy_from_slice(b"FORM");
        assert!(parse(&wav).is_err());
        let mut wav = canonical_wav(100);
        wav[8..12].copy_from_slice(b"AVI ");
        assert!(parse(&wav).is_err());
    }

    #[test]
    fn rejects_truncated_chunk() {
        let mut wav = canonical_wav(100);
        wav.truncate(44 + 50); // data chunk claims 100 but only 50 present
        assert!(parse(&wav).is_err());
    }

    #[test]
    fn rejects_declared_extent_mismatch() {
        // RIFF size claims less than the actual file body -> reject.
        let mut wav = canonical_wav(100);
        // Actual file body = 44 + 100 - 8 = 136; claim 36+50=86 instead.
        wav[4..8].copy_from_slice(&86u32.to_le_bytes());
        assert!(parse(&wav).is_err());

        // RIFF size claims more than the actual file body -> reject.
        let mut wav = canonical_wav(100);
        wav[4..8].copy_from_slice(&(36u32 + 200).to_le_bytes());
        assert!(parse(&wav).is_err());
    }

    #[test]
    fn rejects_valid_chunk_beyond_shortened_declaration() {
        // Build a file whose fmt/data chunks are complete and valid but the
        // RIFF size declaration is deliberately shortened so the declared
        // extent ends before the data chunk. The parser must reject it instead
        // of walking to bytes.len() and accepting out-of-declaration chunks.
        let mut wav = Vec::new();
        wav.extend_from_slice(b"RIFF");
        // Declare only the fmt chunk (20 bytes body = 12 header + 8) + 8 tag...
        // Declared extent covers RIFF(12) + fmt(24) = 36 bytes => size field = 28.
        wav.extend_from_slice(&28u32.to_le_bytes());
        wav.extend_from_slice(b"WAVE");
        wav.extend_from_slice(b"fmt ");
        wav.extend_from_slice(&16u32.to_le_bytes());
        wav.extend_from_slice(&1u16.to_le_bytes()); // PCM
        wav.extend_from_slice(&1u16.to_le_bytes()); // mono
        wav.extend_from_slice(&16000u32.to_le_bytes());
        wav.extend_from_slice(&32000u32.to_le_bytes());
        wav.extend_from_slice(&2u16.to_le_bytes());
        wav.extend_from_slice(&16u16.to_le_bytes());
        // A fully valid data chunk physically present beyond the declaration.
        wav.extend_from_slice(b"data");
        wav.extend_from_slice(&100u32.to_le_bytes());
        wav.extend(vec![0u8; 100]);
        // File extent (12+24+8+100=144) does not match declared extent (36).
        assert_eq!(wav.len(), 144);
        assert!(parse(&wav).is_err());
    }

    #[test]
    fn rejects_wrong_rate_stereo_bits_pcm() {
        // wrong rate
        let mut wav = canonical_wav(100);
        wav[24..28].copy_from_slice(&44100u32.to_le_bytes());
        wav[28..32].copy_from_slice(&88200u32.to_le_bytes());
        assert!(parse(&wav).is_err());

        // stereo
        let mut wav = canonical_wav(100);
        wav[22..24].copy_from_slice(&2u16.to_le_bytes());
        wav[28..32].copy_from_slice(&64000u32.to_le_bytes());
        wav[32..34].copy_from_slice(&4u16.to_le_bytes());
        assert!(parse(&wav).is_err());

        // 8-bit
        let mut wav = canonical_wav(100);
        wav[34..36].copy_from_slice(&8u16.to_le_bytes());
        assert!(parse(&wav).is_err());

        // non-PCM format
        let mut wav = canonical_wav(100);
        wav[20..22].copy_from_slice(&3u16.to_le_bytes());
        assert!(parse(&wav).is_err());
    }

    #[test]
    fn rejects_inconsistent_byte_rate_and_block_align() {
        let mut wav = canonical_wav(100);
        wav[28..32].copy_from_slice(&12345u32.to_le_bytes());
        assert!(parse(&wav).is_err());

        let mut wav = canonical_wav(100);
        wav[32..34].copy_from_slice(&3u16.to_le_bytes());
        assert!(parse(&wav).is_err());
    }

    #[test]
    fn rejects_empty_and_odd_data() {
        let empty = canonical_wav(0);
        assert!(parse(&empty).is_err());

        let odd = canonical_wav(99);
        assert!(parse(&odd).is_err());
    }

    #[test]
    fn handles_unknown_chunks_and_padding() {
        // Insert an unknown chunk with odd payload (padding byte) before data.
        // Layout: RIFF(12) + fmt(8+16=24) + JUNK(8+3+1 pad=12) + data(8+100=108)
        //         = 156 bytes total; RIFF size field = 156 - 8 = 148.
        let mut wav = Vec::new();
        wav.extend_from_slice(b"RIFF");
        wav.extend_from_slice(&148u32.to_le_bytes());
        wav.extend_from_slice(b"WAVE");
        wav.extend_from_slice(b"fmt ");
        wav.extend_from_slice(&16u32.to_le_bytes());
        wav.extend_from_slice(&1u16.to_le_bytes());
        wav.extend_from_slice(&1u16.to_le_bytes());
        wav.extend_from_slice(&16000u32.to_le_bytes());
        wav.extend_from_slice(&32000u32.to_le_bytes());
        wav.extend_from_slice(&2u16.to_le_bytes());
        wav.extend_from_slice(&16u16.to_le_bytes());
        // unknown chunk "JUNK" with 3-byte payload + pad
        wav.extend_from_slice(b"JUNK");
        wav.extend_from_slice(&3u32.to_le_bytes());
        wav.extend_from_slice(&[1, 2, 3, 0]); // payload + pad byte
        wav.extend_from_slice(b"data");
        wav.extend_from_slice(&100u32.to_le_bytes());
        wav.extend(vec![7u8; 100]);
        assert_eq!(wav.len(), 156);

        let info = parse(&wav).expect("unknown chunk + padding tolerated");
        assert_eq!(info.data_size, 100);
    }
}
