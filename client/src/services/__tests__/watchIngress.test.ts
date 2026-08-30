// Delivery 1B Z3 — focused Vitest for the watchIngress event/command adapter
// (Provider Contract §B). The orchestrator is faked at the handler boundary;
// policy lives in the orchestrator tests.

import { beforeEach, describe, expect, it, vi } from 'vitest'

const invokeMock = vi.fn()
const listenMock = vi.fn(async (_event: string, _handler?: (e: unknown) => unknown) => () => {})

vi.mock('../bridge', () => ({
  invoke: (...args: unknown[]) => invokeMock(...(args as [string, Record<string, unknown>?])),
  listen: (event: string, handler: (e: unknown) => unknown) => listenMock(event, handler),
}))

import { initWatchIngress, type WatchIngressHandlers } from '../watchIngress'

type Handlers = WatchIngressHandlers

function makeHandlers() {
  return {
    tryReserveExternalRun: vi.fn((): boolean => true),
    externalAdmissionBlocker: vi.fn((): string | null => null),
    prepareExternalRun: vi.fn(async (): Promise<{ ok: boolean; reason?: string }> => ({ ok: true })),
    beginExternalRun: vi.fn(async () => true),
    abortExternalRun: vi.fn(),
    hasExternalReservation: vi.fn((): boolean => false),
  }
}

type RealHandlers = ReturnType<typeof makeHandlers>

async function setup(
  handlerOverrides: Partial<Handlers> = {},
): Promise<{ handlers: RealHandlers; listeners: Map<string, (e: unknown) => unknown> }> {
  vi.resetModules()
  const mod = await import('../watchIngress')
  const handlers = { ...makeHandlers(), ...handlerOverrides } as RealHandlers
  mod.initWatchIngress(handlers)
  const listeners = new Map<string, (e: unknown) => unknown>()
  for (const [event, handler] of listenMock.mock.calls) {
    listeners.set(event, handler as (e: unknown) => unknown)
  }
  return { handlers, listeners }
}

async function emit(
  listeners: Map<string, (e: unknown) => unknown>,
  event: string,
  payload: unknown,
): Promise<void> {
  const handler = listeners.get(event)
  expect(handler, `listener for ${event}`).toBeTruthy()
  await handler?.({ payload })
}

const REQ = '11111111-1111-4111-8111-111111111111'

beforeEach(() => {
  invokeMock.mockReset()
  listenMock.mockClear()
  // Sane command defaults; individual tests override.
  invokeMock.mockImplementation(async (cmd: string) => {
    if (cmd === 'watch_admission_resolve') return true
    if (cmd === 'watch_run_started') return true
    if (cmd === 'watch_read_reserved_pcm') return new ArrayBuffer(8)
    if (cmd === 'watch_gate_state') return { state: 'Idle' }
    return true
  })
})

describe('watchIngress admission flow', () => {
  it('rejects with the blocker reason without reserving when busy/not ready', async () => {
    const { handlers, listeners } = await setup()
    handlers.externalAdmissionBlocker.mockReturnValue('orchestrator_not_idle')

    await emit(listeners, 'watch://admission-request', { requestId: REQ, sampleCount: 100 })

    expect(handlers.tryReserveExternalRun).not.toHaveBeenCalled()
    expect(invokeMock).toHaveBeenCalledWith('watch_admission_resolve', {
      requestId: REQ,
      accepted: false,
      reason: 'orchestrator_not_idle',
    })
  })

  it('reserves (Phase A), captures (Phase B), then accepts', async () => {
    const { handlers, listeners } = await setup()

    await emit(listeners, 'watch://admission-request', { requestId: REQ, sampleCount: 100 })

    expect(handlers.tryReserveExternalRun).toHaveBeenCalledWith(REQ)
    expect(handlers.prepareExternalRun).toHaveBeenCalledWith(REQ)
    expect(invokeMock).toHaveBeenCalledWith('watch_admission_resolve', {
      requestId: REQ,
      accepted: true,
    })
    expect(handlers.beginExternalRun).not.toHaveBeenCalled()
  })

  it('answers already_reserved when Phase A loses the race', async () => {
    const { handlers, listeners } = await setup()
    handlers.tryReserveExternalRun.mockReturnValue(false)

    await emit(listeners, 'watch://admission-request', { requestId: REQ, sampleCount: 100 })

    expect(handlers.prepareExternalRun).not.toHaveBeenCalled()
    expect(invokeMock).toHaveBeenCalledWith('watch_admission_resolve', {
      requestId: REQ,
      accepted: false,
      reason: 'already_reserved',
    })
  })

  it('forwards the Phase-B failure reason (fail-closed, no acceptance)', async () => {
    const { handlers, listeners } = await setup()
    handlers.prepareExternalRun.mockResolvedValue({ ok: false, reason: 'context_capture_failed' })

    await emit(listeners, 'watch://admission-request', { requestId: REQ, sampleCount: 100 })

    expect(invokeMock).toHaveBeenCalledWith('watch_admission_resolve', {
      requestId: REQ,
      accepted: false,
      reason: 'context_capture_failed',
    })
  })

  it('aborts both sides when the resolve finds no waiting receiver', async () => {
    const { handlers, listeners } = await setup()
    invokeMock.mockImplementation(async (cmd: string) => {
      if (cmd === 'watch_admission_resolve') return false
      if (cmd === 'watch_gate_state') return { state: 'Idle' }
      return true
    })

    await emit(listeners, 'watch://admission-request', { requestId: REQ, sampleCount: 100 })

    expect(handlers.abortExternalRun).toHaveBeenCalledWith(REQ, 'admission_resolve_unmatched')
  })

  it('treats a string payload (legacy/buggy emitter) as invalid — fail-closed', async () => {
    // Repair 1 regression: a Rust side that emits a pre-formatted JSON string
    // would deliver `payload` as a string here. The adapter must ignore it (the
    // receiver then times out fail-closed) and must NOT treat it as success.
    const { handlers, listeners } = await setup()

    await emit(listeners, 'watch://admission-request', JSON.stringify({ requestId: REQ, sampleCount: 100 }))

    expect(handlers.tryReserveExternalRun).not.toHaveBeenCalled()
    expect(handlers.prepareExternalRun).not.toHaveBeenCalled()
    expect(invokeMock).not.toHaveBeenCalledWith('watch_admission_resolve', expect.anything())
  })

  it('ignores malformed admission payloads (receiver times out fail-closed)', async () => {
    const { handlers, listeners } = await setup()

    await emit(listeners, 'watch://admission-request', { requestId: REQ })
    await emit(listeners, 'watch://admission-request', undefined)

    expect(handlers.tryReserveExternalRun).not.toHaveBeenCalled()
    expect(invokeMock).not.toHaveBeenCalledWith('watch_admission_resolve', expect.anything())
  })
})

describe('watchIngress audio-ready handoff', () => {
  it('reads the reserved PCM and hands the exact buffer + sampleCount to the run', async () => {
    const { handlers, listeners } = await setup()
    const pcm = new ArrayBuffer(16000)

    await emit(listeners, 'watch://audio-ready', { requestId: REQ, sampleCount: 8000 })

    expect(invokeMock).toHaveBeenCalledWith('watch_read_reserved_pcm', { requestId: REQ })
    expect(handlers.beginExternalRun).toHaveBeenCalledWith(REQ, pcm, 8000)
  })

  it('aborts when the binary IPC result is not an ArrayBuffer', async () => {
    const { handlers, listeners } = await setup()
    invokeMock.mockImplementation(async (cmd: string) => {
      if (cmd === 'watch_read_reserved_pcm') return 'base64ish-string'
      if (cmd === 'watch_gate_state') return { state: 'Idle' }
      return true
    })

    await emit(listeners, 'watch://audio-ready', { requestId: REQ, sampleCount: 8000 })

    expect(handlers.beginExternalRun).not.toHaveBeenCalled()
    expect(handlers.abortExternalRun).toHaveBeenCalledWith(REQ, 'pcm_type_mismatch')
  })

  it('aborts when the PCM read fails', async () => {
    const { handlers, listeners } = await setup()
    invokeMock.mockImplementation(async (cmd: string) => {
      if (cmd === 'watch_read_reserved_pcm') throw new Error('not reserved')
      if (cmd === 'watch_gate_state') return { state: 'Idle' }
      return true
    })

    await emit(listeners, 'watch://audio-ready', { requestId: REQ, sampleCount: 8000 })

    expect(handlers.abortExternalRun).toHaveBeenCalledWith(REQ, 'pcm_read_failed')
    expect(handlers.beginExternalRun).not.toHaveBeenCalled()
  })
})

describe('watchIngress correlated abort and reconciliation', () => {
  it('forwards receiver-initiated run-abort to the orchestrator', async () => {
    const { handlers, listeners } = await setup()

    await emit(listeners, 'watch://run-abort', { requestId: REQ, reason: 'save_failed' })

    expect(handlers.abortExternalRun).toHaveBeenCalledWith(REQ, 'save_failed')
  })

  it('releases an orphaned gate on boot (reload reconciliation)', async () => {
    invokeMock.mockImplementation(async (cmd: string) => {
      if (cmd === 'watch_gate_state') return { state: 'Reserved', request_id: REQ }
      return true
    })
    const { handlers } = await setup()

    await vi.waitFor(() => {
      expect(invokeMock).toHaveBeenCalledWith('watch_run_aborted', {
        requestId: REQ,
        reason: 'orphaned_after_reload',
      })
    })
    expect(handlers.abortExternalRun).not.toHaveBeenCalled()
  })

  it('keeps a live reservation during reconciliation', async () => {
    invokeMock.mockImplementation(async (cmd: string) => {
      if (cmd === 'watch_gate_state') return { state: 'Reserved', request_id: REQ }
      return true
    })
    // The override must be in place before init: the reconciliation continuation
    // runs in a microtask that beats the test's first await.
    const { handlers } = await setup({ hasExternalReservation: vi.fn(() => true) })

    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(handlers.hasExternalReservation).toHaveBeenCalledWith(REQ)

    expect(invokeMock).not.toHaveBeenCalledWith(
      'watch_run_aborted',
      expect.objectContaining({ requestId: REQ }),
    )
  })
})
