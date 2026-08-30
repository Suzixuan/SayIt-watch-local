import { describe, expect, it } from 'vitest'
import { evaluateSpike, countFallbackWarnings, expectedByteAt } from './checks.js'
import { sha256Hex as sha256HexPure } from './sha256.js'

const LEN = 9 * 1024 * 1024 + 1234 // mirrors src-tauri payload: ≥9 MiB, not an 8192-byte multiple

function buildPayload(len = LEN) {
  const u8 = new Uint8Array(len)
  for (let i = 0; i < len; i++) u8[i] = expectedByteAt(i)
  return u8
}

async function metaFor(u8) {
  const digest = await crypto.subtle.digest('SHA-256', u8)
  const hex = [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('')
  const mid = u8.length >> 1
  return { len: u8.length, sha256: hex, first: u8[0], middle: u8[mid], last: u8[u8.length - 1] }
}

describe('pure-JS SHA-256 fallback', () => {
  it('matches the known answer for "abc"', () => {
    expect(sha256HexPure(new TextEncoder().encode('abc'))).toBe(
      'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad',
    )
  })
})

describe('evaluateSpike', () => {
  it('acceptance: the ≥9 MiB result passes as a raw ArrayBuffer with no base64/number-array conversion', async () => {
    const u8 = buildPayload()
    expect(u8.length).toBeGreaterThanOrEqual(9 * 1024 * 1024)
    expect(u8.length % 8192).not.toBe(0)
    const result = u8.buffer.slice(u8.byteOffset, u8.byteOffset + u8.byteLength) // an ArrayBuffer, nothing else
    const meta = await metaFor(u8)
    const report = evaluateSpike({ result, meta, warnings: [], sha256Hex: meta.sha256 })
    expect(report.actualType).toBe('[object ArrayBuffer]')
    expect(report.pass).toBe(true)
    expect(report.fallbackWarningCount).toBe(0)
  }, 30000)

  it('rejects a wrong byteLength', async () => {
    const u8 = buildPayload(LEN - 2)
    const meta = await metaFor(buildPayload(LEN))
    const report = evaluateSpike({ result: u8.buffer.slice(0), meta, warnings: [], sha256Hex: meta.sha256 })
    expect(report.checks.lengthMatches).toBe(false)
    expect(report.pass).toBe(false)
  })

  it('rejects a wrong sentinel byte', async () => {
    const u8 = buildPayload()
    u8[LEN - 1] ^= 0xff
    const result = u8.buffer.slice(0)
    const meta = await metaFor(buildPayload())
    const report = evaluateSpike({ result, meta, warnings: [], sha256Hex: meta.sha256 })
    expect(report.checks.sentinelsMatch).toBe(false)
    expect(report.pass).toBe(false)
  })

  it('rejects a wrong body/hash', async () => {
    const u8 = buildPayload()
    u8[12345] ^= 0xff
    const meta = await metaFor(buildPayload()) // expected hash of the pristine payload
    const wrongHash = await metaFor(u8)
    const report = evaluateSpike({ result: u8.buffer.slice(0), meta, warnings: [], sha256Hex: wrongHash.sha256 })
    expect(report.checks.hashMatches).toBe(false)
    expect(report.pass).toBe(false)
  })

  it('rejects a string (base64-shaped) result and a plain JSON number array', async () => {
    const meta = await metaFor(buildPayload(64))
    const asString = evaluateSpike({ result: 'AAECAwQ=', meta, warnings: [], sha256Hex: meta.sha256 })
    expect(asString.checks.isArrayBuffer).toBe(false)
    expect(asString.pass).toBe(false)
    const asArray = evaluateSpike({ result: [0, 1, 2, 3], meta, warnings: [], sha256Hex: meta.sha256 })
    expect(asArray.checks.isArrayBuffer).toBe(false)
    expect(asArray.pass).toBe(false)
  })

  it('fails when a custom-protocol fallback warning was captured', async () => {
    const u8 = buildPayload()
    const meta = await metaFor(u8)
    const report = evaluateSpike({
      result: u8.buffer.slice(0),
      meta,
      warnings: ['IPC custom protocol failed, Tauri will now use the postMessage interface instead', 'unrelated warn'],
      sha256Hex: meta.sha256,
    })
    expect(report.fallbackWarningCount).toBe(1)
    expect(report.pass).toBe(false)
  })
})

describe('countFallbackWarnings', () => {
  it('matches only the known fallback markers', () => {
    expect(countFallbackWarnings(['customProtocolIpcFailed = true'])).toBe(1)
    expect(countFallbackWarnings(['IPC custom protocol failed, Tauri will now use the postMessage interface instead'])).toBe(1)
    expect(countFallbackWarnings(['some unrelated warning'])).toBe(0)
    expect(countFallbackWarnings([])).toBe(0)
  })
})
