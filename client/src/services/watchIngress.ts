// Delivery 1B — PC external WAV ingress bridge (frozen Provider Contract §B).
//
// Bridges the debug receiver's admission/handoff events into the
// RecorderOrchestrator and acknowledges back through the debug-only commands:
//
//   watch://admission-request → Phase A (sync reserve) → Phase B (bounded native
//   capture) → watch_admission_resolve
//   watch://audio-ready → watch_read_reserved_pcm (raw binary PCM) → connect →
//   checked start → watch_run_started (the receiver may now answer 201) → feed
//   watch://run-abort → correlated abort of the JS half
//
// The orchestrator owns every policy decision; this module is a thin, fail-closed
// event/command adapter. Never logs transcript text, PCM, tokens, or editor text.

import { invoke, listen } from './bridge'
import { addRuntimeEvent } from './debugLog'

/** Phase B budget (contract §B.2): the bounded native context capture. */
const PHASE_B_BUDGET_MS = 3_000

export interface WatchIngressHandlers {
  /** Phase A: synchronous, atomic reserve (also performs the one-shot sync capture). */
  tryReserveExternalRun(requestId: string): boolean
  /** Fixed busy/not-ready reason, or null when admissible. */
  externalAdmissionBlocker(): string | null
  /** Phase B: bounded native focus/app-context capture; fail-closed on error/timeout. */
  prepareExternalRun(requestId: string): Promise<{ ok: boolean; reason?: string }>
  /** Validated PCM → connect → checked start → ack → exact feed → finalize. */
  beginExternalRun(requestId: string, pcm: ArrayBuffer, sampleCount: number): Promise<boolean>
  /** Correlated JS-half abort (request-conditional, idempotent). */
  abortExternalRun(requestId: string, reason: string): void
  /** Whether the orchestrator still holds the reservation for this request. */
  hasExternalReservation(requestId: string): boolean
}

interface AdmissionRequestPayload {
  requestId?: string
  sampleCount?: number
}

let initialized = false

/** Registers the receiver event listeners and the boot reconciliation. */
export function initWatchIngress(handlers: WatchIngressHandlers): void {
  if (initialized) return
  initialized = true

  void listen('watch://admission-request', (event) => {
    // The handler returns the flow's promise so test harnesses (and any future
    // awaiter) can await the full chain; Tauri itself fire-and-forgets it.
    return onAdmissionRequest(handlers, (event as { payload?: AdmissionRequestPayload }).payload)
  })
  void listen('watch://audio-ready', (event) => {
    return onAudioReady(handlers, (event as { payload?: AdmissionRequestPayload }).payload)
  })
  void listen('watch://run-abort', (event) => {
    const payload = (event as { payload?: { requestId?: string; reason?: string } }).payload
    const requestId = payload?.requestId
    if (!requestId) return
    handlers.abortExternalRun(requestId, String(payload?.reason ?? 'receiver_abort'))
  })

  // Boot/reload reconciliation (contract §B.7): a Reserved gate with no matching
  // local reservation is an orphan from a dead WebView — release it.
  void reconcileGate(handlers)
}

async function reconcileGate(handlers: WatchIngressHandlers): Promise<void> {
  try {
    const state = (await invoke('watch_gate_state')) as {
      state?: string
      request_id?: string
    }
    const requestId = state?.request_id
    if (state?.state === 'Reserved' && requestId && !handlers.hasExternalReservation(requestId)) {
      addRuntimeEvent('warn', 'watchIngress', 'Orphaned admission gate after reload; releasing', {
        requestId,
      })
      await invoke('watch_run_aborted', { requestId, reason: 'orphaned_after_reload' }).catch(() => {})
    }
  } catch {
    // Debug-only command; absent in release builds. Nothing to reconcile there.
  }
}

async function onAdmissionRequest(
  handlers: WatchIngressHandlers,
  payload: AdmissionRequestPayload | undefined,
): Promise<void> {
  const requestId = payload?.requestId
  const sampleCount = Number(payload?.sampleCount ?? 0)
  // Malformed payloads never touch the gate: the receiver fails closed on its
  // own bounded timeout.
  if (!requestId || !Number.isFinite(sampleCount) || sampleCount <= 0) return

  const blocker = handlers.externalAdmissionBlocker()
  if (blocker !== null) {
    addRuntimeEvent('info', 'watchIngress', 'Admission rejected (busy/not ready)', {
      requestId,
      reason: blocker,
    })
    await invoke('watch_admission_resolve', { requestId, accepted: false, reason: blocker }).catch(
      () => {},
    )
    return
  }

  // Phase A — synchronous and atomic; a PTT run cannot interleave.
  if (!handlers.tryReserveExternalRun(requestId)) {
    await invoke('watch_admission_resolve', {
      requestId,
      accepted: false,
      reason: 'already_reserved',
    }).catch(() => {})
    return
  }

  // Phase B — bounded native capture; failure already aborted the JS half.
  const prepared = await handlers.prepareExternalRun(requestId)
  if (!prepared.ok) {
    await invoke('watch_admission_resolve', {
      requestId,
      accepted: false,
      reason: prepared.reason ?? 'context_capture_failed',
    }).catch(() => {})
    return
  }

  // Accept. If the receiver stopped waiting (bridge timeout won the race), the
  // resolve returns false and both sides abort.
  const delivered = await invoke<boolean>('watch_admission_resolve', {
    requestId,
    accepted: true,
  }).catch(() => false)
  if (delivered !== true) {
    handlers.abortExternalRun(requestId, 'admission_resolve_unmatched')
  }
}

async function onAudioReady(
  handlers: WatchIngressHandlers,
  payload: AdmissionRequestPayload | undefined,
): Promise<void> {
  const requestId = payload?.requestId
  const sampleCount = Number(payload?.sampleCount ?? 0)
  if (!requestId || !Number.isFinite(sampleCount) || sampleCount <= 0) return

  // Z2-proven raw binary IPC: identical ArrayBuffer bytes, no base64.
  let pcm: unknown
  try {
    pcm = await invoke('watch_read_reserved_pcm', { requestId })
  } catch {
    handlers.abortExternalRun(requestId, 'pcm_read_failed')
    return
  }
  if (!(pcm instanceof ArrayBuffer)) {
    handlers.abortExternalRun(requestId, 'pcm_type_mismatch')
    return
  }
  // beginExternalRun performs the full §B.5 validation (length vs sampleCount,
  // even bytes) and every subsequent step; failures abort both sides there.
  await handlers.beginExternalRun(requestId, pcm, sampleCount)
}

export { PHASE_B_BUDGET_MS }
