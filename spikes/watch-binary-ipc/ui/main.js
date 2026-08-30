import { invoke } from '@tauri-apps/api/core'
import { evaluateSpike, countFallbackWarnings, expectedByteAt } from './checks.js'
import { sha256Hex as sha256HexPure } from './sha256.js'

const $ = (id) => document.getElementById(id)

const row = (k, v, cls = '') =>
  `<tr><td class="k">${k}</td><td class="v ${cls}">${v}</td></tr>`
const mark = (ok) => (ok ? '<span class="ok">✓</span>' : '<span class="bad">✗</span>')

async function sha256Of(result) {
  if (globalThis.crypto?.subtle) {
    const digest = await crypto.subtle.digest('SHA-256', result)
    return { hex: [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join(''), impl: 'WebCrypto subtle' }
  }
  return { hex: sha256HexPure(result), impl: 'pure-JS fallback' }
}

async function main() {
  // 3. Wrap console.warn for the whole invoke window; restore even on failure.
  const warnings = []
  const originalWarn = console.warn.bind(console)
  console.warn = (...args) => {
    warnings.push(args.map((a) => (typeof a === 'string' ? a : String(a))).join(' '))
    return originalWarn(...args)
  }

  let meta = null
  let result = null
  let invokeError = null
  let elapsedMs = null
  try {
    meta = await invoke('spike_payload_meta')
    const t0 = performance.now()
    result = await invoke('spike_payload')
    elapsedMs = Math.round(performance.now() - t0)
  } catch (e) {
    invokeError = String(e)
  } finally {
    console.warn = originalWarn
  }

  let sha256Hex = ''
  let hashImpl = 'n/a (no ArrayBuffer)'
  if (result instanceof ArrayBuffer) {
    try {
      const h = await sha256Of(result)
      sha256Hex = h.hex
      hashImpl = h.impl
    } catch (e) {
      hashImpl = `hash error: ${String(e)}`
    }
  }

  const report = invokeError
    ? { pass: false, actualType: `invoke error: ${invokeError}`, checks: {}, fallbackWarningCount: countFallbackWarnings(warnings) }
    : evaluateSpike({ result, meta, warnings, sha256Hex })

  const versions = await fetch('./versions.json').then((r) => r.json()).catch(() => ({}))
  const verdictEl = $('verdict')
  verdictEl.textContent = report.pass ? 'PASS' : 'FAIL'
  verdictEl.className = report.pass ? 'pass' : 'fail'
  document.title = `${report.pass ? 'PASS' : 'FAIL'} — Z2 Binary IPC Spike`

  const c = report.checks ?? {}
  const metaRows = meta
    ? row('payload bytes (Rust)', String(meta.len)) +
      row('returned type', report.actualType) +
      row('returned byteLength', result instanceof ArrayBuffer ? String(result.byteLength) : 'n/a') +
      row('expected SHA-256', meta.sha256) +
      row('actual SHA-256', sha256Hex || 'n/a', report.checks?.hashMatches ? 'ok' : '') +
      row('hash implementation', hashImpl) +
      row(
        'sentinels first/middle/last',
        result instanceof ArrayBuffer
          ? `${meta.first}/${meta.middle}/${meta.last} (Rust) vs ${
              new Uint8Array(result)[0]
            }/${new Uint8Array(result)[meta.len >> 1]}/${new Uint8Array(result)[meta.len - 1]} (JS)` +
            ` — formula re-check: ${expectedByteAt(0)}/${expectedByteAt(meta.len >> 1)}/${expectedByteAt(meta.len - 1)}`
          : 'n/a',
      )
    : row('metadata', 'invoke failed before metadata', 'bad')

  $('panel').innerHTML =
    row('versions', `tauri ${versions.rustTauriPin ?? '?'} · @tauri-apps/api ${versions.apiVersion ?? '?'} · @tauri-apps/cli ${versions.cliVersion ?? '?'}`) +
    row('invoke elapsed ms', elapsedMs == null ? 'n/a' : String(elapsedMs)) +
    metaRows +
    row('check: result is ArrayBuffer', mark(c.isArrayBuffer ?? false)) +
    row('check: not a string / not base64', mark(c.notString ?? false)) +
    row('check: not a JSON number array', mark(c.notPlainArray ?? false)) +
    row('check: byteLength === payload', mark(c.lengthMatches ?? false)) +
    row('check: sentinels + formula', mark(c.sentinelsMatch ?? false)) +
    row('check: SHA-256 === expected', mark(c.hashMatches ?? false)) +
    row(
      'fallback warnings (customProtocolIpcFailed / postMessage)',
      `${report.fallbackWarningCount} captured (total console.warn: ${warnings.length})`,
      report.fallbackWarningCount === 0 ? 'ok' : 'bad',
    )
}

main()
