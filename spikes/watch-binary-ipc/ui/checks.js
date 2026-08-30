// Pure verification logic for the Z2 binary-IPC spike. Unit-tested in checks.test.mjs.

export const FALLBACK_MARKERS = [
  'customProtocolIpcFailed',
  'IPC custom protocol failed',
  'postMessage interface',
]

/** Count warnings that indicate the custom-protocol IPC fell back to postMessage. */
export function countFallbackWarnings(warnings) {
  return (warnings ?? []).filter((w) =>
    FALLBACK_MARKERS.some((m) => String(w).includes(m)),
  ).length
}

/** Deterministic payload formula — must match src-tauri/src/payload.rs `byte_at`. */
export function expectedByteAt(i) {
  return (i * 31 + 7) & 0xff
}

/**
 * Evaluate one spike result against the Rust-reported metadata.
 * `sha256Hex` must be computed by the caller (WebCrypto preferred) over the raw result.
 */
export function evaluateSpike({ result, meta, warnings, sha256Hex }) {
  const actualType = Object.prototype.toString.call(result)
  const isArrayBuffer = actualType === '[object ArrayBuffer]'
  const notString = typeof result !== 'string'
  const notPlainArray = !Array.isArray(result)
  const lengthMatches = isArrayBuffer && result.byteLength === meta.len

  let sentinelsMatch = false
  if (isArrayBuffer && lengthMatches) {
    const u8 = new Uint8Array(result)
    const mid = meta.len >> 1
    sentinelsMatch =
      u8[0] === meta.first &&
      u8[mid] === meta.middle &&
      u8[meta.len - 1] === meta.last &&
      // Independent re-derivation from the frozen formula — meta itself must agree.
      meta.first === expectedByteAt(0) &&
      meta.middle === expectedByteAt(mid) &&
      meta.last === expectedByteAt(meta.len - 1)
  }

  const hashMatches = typeof sha256Hex === 'string' && sha256Hex === meta.sha256
  const fallbackWarningCount = countFallbackWarnings(warnings)

  const pass =
    isArrayBuffer &&
    notString &&
    notPlainArray &&
    lengthMatches &&
    sentinelsMatch &&
    hashMatches &&
    fallbackWarningCount === 0

  return {
    pass,
    actualType,
    checks: { isArrayBuffer, notString, notPlainArray, lengthMatches, sentinelsMatch, hashMatches },
    fallbackWarningCount,
  }
}
