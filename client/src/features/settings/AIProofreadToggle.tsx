// AI 整理开关、快捷键与短语音门槛（状态接入全局 store）

import { Card, CardContent } from '@/components/ui/card'
import { Switch } from '@/components/ui/switch'
import { useAiEnabled } from '@/hooks/useAiEnabled'
import { toggleAiEnabled } from '@/stores/aiEnabled'
import { useLocale, useT } from '@/i18n/useT'
import { ComboShortcutInput } from './ShortcutInputs'
import { useEffect, useState } from 'react'
import { getPresetShortcuts, getSetting, setSetting } from '@/services/store'
import { refreshRecorderSettings } from '@/services/recorder'
import { pttShortcutConflictsWithAccelerator } from '@/lib/shortcutKeys'
import * as bridge from '@/services/bridge'
import { MAX_RECORDING_SEC } from '@/services/recorder/types'

export default function AIProofreadToggle() {
  const t = useT()
  const locale = useLocale()
  const aiEnabled = useAiEnabled()
  const [shortcut, setShortcut] = useState('')
  const [minDurationSec, setMinDurationSec] = useState(0)

  useEffect(() => {
    void Promise.all([
      getSetting('shortcutToggleAi', ''),
      getSetting('aiMinDurationSec', 0),
    ]).then(([savedShortcut, savedMinDuration]) => {
      setShortcut(String(savedShortcut || ''))
      setMinDurationSec(Math.max(0, Math.min(MAX_RECORDING_SEC, Number(savedMinDuration) || 0)))
    })
  }, [])

  const validateShortcut = async (value: string) => {
    const [ptt, handsFree, presetShortcuts] = await Promise.all([
      getSetting<string>('shortcutPTT', 'ControlRight'),
      getSetting<string>('shortcutHandsFree', 'AltRight'),
      getPresetShortcuts(),
    ])
    if (pttShortcutConflictsWithAccelerator(ptt, value)) return t('aiProofread.shortcutConflictPtt')
    if (handsFree === value) return t('aiProofread.shortcutConflictHandsFree')
    if (Object.values(presetShortcuts).includes(value)) return t('aiProofread.shortcutConflictPreset')
    return null
  }

  const saveShortcut = async (value: string) => {
    setShortcut(value)
    await setSetting('shortcutToggleAi', value)
    bridge.notifyShortcutsChanged()
  }

  const saveMinDuration = async (next: number) => {
    setMinDurationSec(next)
    await setSetting('aiMinDurationSec', next)
    await refreshRecorderSettings()
  }

  const handleMinDurationChange = (value: string, input: HTMLInputElement) => {
    const next = value === '' ? 0 : Math.max(0, Math.min(MAX_RECORDING_SEC, Math.round(Number(value) || 0)))
    // 数值不变时 React 会跳过重渲染，浏览器便会保留 `00` 之类的原始输入；
    // 直接回写规范值，使 0 始终只有一个，也顺便限制在 0–300 内。
    input.value = String(next)
    void saveMinDuration(next)
  }

  // 数字本身不如「实际会怎样」容易理解。默认值保留为可直接编辑的 0，
  // 旁边同步说明 0 / 非 0 各自的效果，用户无需先记住规则再决定要不要改。
  const minDurationEffect = minDurationSec === 0
    ? t('aiProofread.minDurationDefaultHint')
    : t('aiProofread.minDurationActiveHint', { seconds: minDurationSec })
  // 中文“始终整理”足够短，固定在 64px 输入框正下方居中；英文文案更长，
  // 保持右对齐以避免越出卡片。填写数值后的状态说明始终使用右对齐。
  const centerDefaultChineseEffect = minDurationSec === 0 && locale === 'zh-CN'

  return (
    <Card>
      <CardContent className="p-6">
        {/* min-w-0 + gap：最小窗口下说明文字要能挤，不能把开关顶出卡片 */}
        <div className="flex items-center justify-between gap-4">
          <div className="min-w-0">
            <h2 id="ai-proofread-heading" className="text-lg font-semibold">{t('titleBar.aiCleanup')}</h2>
            <p className="mt-1 text-xs text-muted-foreground">
              {aiEnabled ? t('aiProofread.onDesc') : t('aiProofread.offDesc')}
            </p>
          </div>
          {/* 开关原来既没有 label 也没有 aria-label，相邻的标题也没关联——读屏念到的是
              一个没有名字的「切换按钮」 */}
          <Switch
            checked={aiEnabled}
            onChange={() => { void toggleAiEnabled() }}
            labelledBy="ai-proofread-heading"
            className="shrink-0"
          />
        </div>
        <div className="mt-5 space-y-4">
          <ComboShortcutInput
            value={shortcut}
            onChange={saveShortcut}
            validate={validateShortcut}
            comboOnly
            allowMouseShortcut
            label={t('aiProofread.shortcutLabel')}
            description={t('aiProofread.shortcutDesc')}
          />
          <div>
            <div className="flex items-center justify-between gap-5">
              <div className="min-w-0">
                <p className="text-sm font-medium">{t('aiProofread.minDurationLabel')}</p>
                <p className="mt-0.5 text-xs leading-relaxed text-muted-foreground">{t('aiProofread.minDurationDesc')}</p>
              </div>
              <label className="mr-2 flex shrink-0 items-center gap-1.5">
                <input
                  type="number"
                  min="0"
                  max={MAX_RECORDING_SEC}
                  step="1"
                  value={minDurationSec}
                  placeholder={t('aiProofread.minDurationOff')}
                  onChange={(event) => handleMinDurationChange(event.target.value, event.currentTarget)}
                  className="h-8 w-16 rounded-md border border-input bg-background px-2 text-right text-sm tabular-nums outline-none transition-colors placeholder:text-muted-foreground focus:border-primary focus:ring-2 focus:ring-primary/20"
                  aria-label={t('aiProofread.minDurationLabel')}
                  aria-describedby="ai-min-duration-effect"
                />
                <span className="text-sm text-muted-foreground">{t('aiProofread.seconds')}</span>
              </label>
            </div>
            <div className="mt-1 flex justify-end">
              <p
                id="ai-min-duration-effect"
                className={`text-xs leading-relaxed text-muted-foreground ${centerDefaultChineseEffect
                  ? 'mr-[1.875rem] w-16 text-center'
                  : 'w-fit max-w-full text-right max-w-[22rem]'}`}
              >
                {minDurationEffect}
              </p>
            </div>
          </div>
        </div>
      </CardContent>
    </Card>
  )
}
