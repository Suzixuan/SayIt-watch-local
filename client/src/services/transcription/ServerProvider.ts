// 服务器模式 Provider — 封装现有 WebSocket 逻辑
// 行为与原 websocket.ts 完全一致，只是包装为 TranscriptionProvider 接口

import * as ws from '../websocket'
import type {
  TranscriptionProvider,
  TranscriptionCallbacks,
  StartOptions,
  StopOptions,
} from './types'

export class ServerProvider implements TranscriptionProvider {
  readonly mode = 'server' as const

  private callbacks: TranscriptionCallbacks = {}
  private activeRunId = 0

  async connect(callbacks: TranscriptionCallbacks): Promise<void> {
    this.callbacks = callbacks
    await ws.connect({
      onStateChange: (state) => {
        callbacks.onStateChange?.(state)
      },
      onReady: (data) => {
        callbacks.onReady?.({
          connectionId: data.connectionId,
          asr: data.asr,
          llm: data.llm,
        })
      },
      onASR: (result) => {
        if (this.activeRunId === 0) return
        callbacks.onASR?.({
          text: result.text,
          asrMs: result.asrMs,
          durationSec: result.durationSec,
        })
      },
      onFinal: (result) => {
        if (this.activeRunId === 0) return
        callbacks.onFinal?.({
          asrText: result.asrText,
          llmText: result.llmText,
          asrMs: result.asrMs,
          llmMs: result.llmMs,
          durationSec: result.durationSec,
          asrEngine: result.asrEngine,
          asrModel: result.asrModel,
          contextApplied: result.contextApplied,
        })
      },
      onDone: () => {
        const runId = this.activeRunId
        if (runId === 0) return
        callbacks.onDone?.()
        if (this.activeRunId === runId) this.activeRunId = 0
      },
      onError: (msg) => {
        const runId = this.activeRunId
        if (runId === 0) return
        callbacks.onError?.(msg)
        if (this.activeRunId === runId) this.activeRunId = 0
      },
    })
  }

  start(opts: StartOptions): boolean {
    this.activeRunId = opts.runId
    const started = ws.sendStart(opts)
    if (!started) this.activeRunId = 0
    return started
  }

  cancel(): void {
    this.activeRunId = 0
    ws.disconnect()
  }

  sendAudio(buffer: ArrayBuffer): void {
    ws.sendAudio(buffer)
  }

  stop(opts?: StopOptions): boolean {
    return ws.sendStop(opts)
  }

  disconnect(): void {
    this.activeRunId = 0
    ws.disconnect()
  }

  isReady(): boolean {
    return ws.isConnected()
  }
}
