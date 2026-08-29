// 本地模式 Provider
// 音频在本地积攒，stop 时调用 Rust 侧本地 ASR 推理
// ASR 完成后可选调用云端 AI 校对

import { invoke } from '@tauri-apps/api/core'
import { getSetting } from '../store'
import { addRuntimeEvent } from '../debugLog'
import { BufferedProvider } from './BufferedProvider'
import type { TranscriptionCallbacks } from './types'

interface AiResult { text: string; elapsed_ms: number }

export class LocalProvider extends BufferedProvider {
  readonly mode = 'local' as const

  /**
   * 本地模式的"就绪"= 选中的模型已完整下载到本地。
   *
   * 以前这里无论如何都报 `asr: true`（连预加载失败的 catch 里也报），于是模型被删光了
   * 按快捷键照样开始录音，录完才在识别阶段报错 —— 而设置页明明写着"按下快捷键不会有
   * 反应"，说到没做到。现在如实上报，未就绪由 RecorderOrchestrator 统一拦下并提示。
   *
   * 判断口径与左下角引擎指示（stores/modeStatus）保持一致：都看 list_downloaded_models
   * 里该模型是否 complete，避免同屏两处结论不同。
   */
  protected async onConnect(callbacks: TranscriptionCallbacks): Promise<boolean> {
    const modelId = await getSetting('localAsr.modelId', 'sensevoice-small-gguf') as string
    if (!modelId) {
      addRuntimeEvent('warn', 'local', 'No local model selected; provider is not ready')
      callbacks.onReady?.({ asr: false, llm: false })
      return false
    }

    let downloaded = false
    try {
      const models = await invoke<{ id: string; complete: boolean }[]>('list_downloaded_models')
      downloaded = models.some((m) => m.id === modelId && m.complete)
    } catch (err) {
      // 读不到列表时不敢断言就绪：宁可拦下并提示，也别录完才失败
      addRuntimeEvent('warn', 'local', 'Could not read local model list; provider is not ready', { error: String(err) })
      callbacks.onReady?.({ asr: false, llm: false })
      return false
    }

    if (!downloaded) {
      addRuntimeEvent('warn', 'local', 'Selected local model is not downloaded; provider is not ready', { modelId })
      callbacks.onReady?.({ asr: false, llm: false })
      return false
    }

    // 已下载：预加载失败（如文件损坏、显卡后端异常）不代表不能用，
    // 真正的失败会在识别阶段带着具体原因报出来，这里仍按就绪处理。
    try {
      const accelerator = await getSetting('localAsr.accelerator', 'auto') as string
      await invoke<string>('preload_local_model', { modelId, accelerator })
    } catch (err) {
      addRuntimeEvent('warn', 'local', 'Local model preload failed; provider remains marked ready', { error: String(err) })
    }
    callbacks.onReady?.({ asr: true, llm: false })
    return true
  }

  protected async processAudio(audioB64: string, durationSec: number, runId: number): Promise<void> {
    if (!this.isRunCurrent(runId)) return
    const startOpts = this.startOpts
    const startTime = performance.now()

    addRuntimeEvent('info', 'local', 'Local ASR started', { durationSec, runId })
    let asrText = ''
    let asrMs = 0

    try {
      const modelId = await getSetting('localAsr.modelId', 'sensevoice-small-gguf')
      if (!this.isRunCurrent(runId)) return
      const language = await getSetting('localAsr.language', 'auto')
      if (!this.isRunCurrent(runId)) return
      const accelerator = await getSetting('localAsr.accelerator', 'auto')
      if (!this.isRunCurrent(runId)) return

      const result = await invoke<{ text: string; elapsed_ms: number }>('local_transcribe', {
        audioB64,
        modelId,
        language,
        accelerator,
      })
      if (!this.isRunCurrent(runId)) return
      asrText = result.text
      asrMs = result.elapsed_ms
    } catch (err) {
      if (!this.isRunCurrent(runId)) return
      addRuntimeEvent('error', 'local', 'Local ASR failed', { error: String(err) })
      this.callbacks.onError?.(String(err))
      this.callbacks.onDone?.()
      return
    }

    if (!this.isRunCurrent(runId)) return
    this.callbacks.onASR?.({ text: asrText, asrMs, durationSec })

    if (!asrText.trim()) {
      if (!this.isRunCurrent(runId)) return
      this.callbacks.onFinal?.({ asrText: '', llmText: '', asrMs, llmMs: 0, durationSec })
      this.callbacks.onDone?.()
      return
    }

    let llmText = asrText
    let llmMs = 0
    let aiSucceeded = false

    const aiEnabled = await getSetting('aiEnabled', false)
    if (!this.isRunCurrent(runId)) return
    const disableAi = startOpts?.disableAi ?? false
    const aiMinDurationSec = Math.max(0, Number(startOpts?.aiMinDurationSec) || 0)
    const shouldUseAi = !disableAi && (aiMinDurationSec === 0 || durationSec >= aiMinDurationSec)

    if (aiEnabled && shouldUseAi) {
      const aiProvider = await getSetting('cloudAi.provider', 'openai_compat') as string
      const aiApiUrl = await getSetting('cloudAi.apiUrl', '') as string
      const aiApiKey = await getSetting('cloudAi.apiKey', '') as string
      const aiModel = await getSetting('cloudAi.model', '') as string
      if (!this.isRunCurrent(runId)) return

      if (aiApiUrl && (aiApiKey || aiProvider === 'ollama')) {
        try {
          const aiResult = await invoke<AiResult>('cloud_polish', {
            request: {
              text: asrText,
              ai_config: { provider: aiProvider, api_url: aiApiUrl, api_key: aiApiKey, model: aiModel },
              system_prompt: startOpts?.systemPrompt || null,
              text_context: startOpts?.textContext || null,
            },
          })
          if (!this.isRunCurrent(runId)) return
          llmText = aiResult.text || asrText
          llmMs = aiResult.elapsed_ms
          aiSucceeded = Boolean(aiResult.text)
        } catch (err) {
          if (!this.isRunCurrent(runId)) return
        addRuntimeEvent('warn', 'local', 'AI cleanup failed; using raw ASR text', { error: String(err) })
        }
      }
    }

    if (aiEnabled && !disableAi && aiMinDurationSec > 0 && durationSec < aiMinDurationSec) {
      addRuntimeEvent('info', 'local', 'AI cleanup skipped below duration threshold', {
        durationSec,
        aiMinDurationSec,
      })
    }

    if (startOpts?.textContext?.selectedText && !aiSucceeded) {
      llmText = startOpts.textContext.selectedText
      addRuntimeEvent('warn', 'local', 'Selected-text edit skipped because AI did not complete')
    }

    if (!this.isRunCurrent(runId)) return
    const totalMs = Math.round(performance.now() - startTime)
    addRuntimeEvent('info', 'local', 'Processing complete', { durationSec, asrMs, llmMs, totalMs, runId })

    this.callbacks.onFinal?.({
      asrText, llmText, asrMs, llmMs, durationSec,
      contextApplied: startOpts?.textContext ? aiSucceeded : undefined,
    })
    this.callbacks.onDone?.()
  }
}
