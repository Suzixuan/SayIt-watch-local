import { useEffect, useState } from 'react'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Github, Loader2, CheckCircle } from 'lucide-react'
import { open as shellOpen } from '@tauri-apps/plugin-shell'
import { getAutoUpdateState, onAutoUpdateChange, checkForUpdateNow, installPendingUpdate, type AutoUpdateState } from '@/features/update/autoUpdate'
import { RELEASE_HIGHLIGHTS } from '@/features/update/releaseHighlights'
import appIcon from '@/assets/icon-128.png'
import { getLocale, t } from '@/i18n'
import { useT } from '@/i18n/useT'

const currentVersion = __APP_VERSION__

const REPO_URL = 'https://github.com/crosswk/SayIt'
const RELEASES_URL = `${REPO_URL}/releases`

function formatTimestamp(value: number | null | undefined) {
  if (!value) return t('about.neverChecked')
  // 时间格式跟界面语言：中文界面用 zh-CN 的写法，英文界面用 en-US 的。
  return new Date(value).toLocaleString(getLocale(), { hour12: false })
}

export default function About() {
  const t = useT()
  const [state, setState] = useState<AutoUpdateState>(getAutoUpdateState)

  useEffect(() => {
    return onAutoUpdateChange(setState)
  }, [])

  const { phase, versionInfo, checkedAt, error, pending } = state
  const checking = phase === 'checking'
  const downloading = phase === 'downloading'
  const installing = phase === 'installing'
  // 「有包等着装」与 phase 正交：后台每 6 小时会跑一次检查，那期间 phase 是 'checking'，
  // 但安装按钮该一直在。别把这个条件写成 phase === 某个值（那正是卡住过的写法）。
  const ready = !!pending && !installing
  const hasUpdate = !!versionInfo?.hasUpdate

  const updateStatusText = (() => {
    if (installing) return t('about.installing')
    // ready 排在检查/下载之前：待安装的包可能是上次运行下载的，此时 versionInfo 还没回来；
    // 而且后台周期检查不该把"已经下载好了"这条更重要的信息挤掉。
    if (ready) return t('about.downloaded', { version: String(pending?.version) })
    if (checking) return t('about.checking')
    if (!versionInfo) return null
    if (versionInfo.error) return t('about.checkFailed')
    // String() 而不是 ?? ''：保持与改造前 `${...}` 完全一致的输出，
    // 这轮只做翻译，不顺手改 latestVersion 为空时的表现。
    if (downloading) return t('about.downloading', { version: String(versionInfo.latestVersion), percent: Math.round(state.downloadPercent ?? 0) })
    if (hasUpdate) return t('about.updateAvailable', { version: String(versionInfo.latestVersion) })
    return t('about.upToDate')
  })()

  return (
    <div className="mx-auto max-w-4xl">
      <h1 className="mb-4 text-2xl font-bold">{t('about.title')}</h1>

      <Card>
        <CardContent className="p-6">
          {/* 品牌 */}
          <div className="flex items-center gap-4">
            <img src={appIcon} alt="SayIt" className="h-16 w-16 rounded-2xl" />
            <div>
              <h2 className="text-2xl font-bold tracking-tight" style={{ fontFamily: "'Nunito', sans-serif", fontWeight: 800 }}>
                SayIt
              </h2>
              <p className="text-sm text-muted-foreground">{t('about.tagline')}</p>
              <p className="mt-0.5 text-xs text-muted-foreground/60">by Liu Qianglong & Claude</p>
              <div className="mt-1.5 flex items-center gap-2">
                {/* 版本号做成和右边 GitHub 图标同一种药丸按钮：点开 releases 页，
                    想看历史版本或某一版改了什么的人第一反应就是点这里的版本号。 */}
                <button
                  type="button"
                  onClick={() => void shellOpen(RELEASES_URL)}
                  className="flex h-6 items-center rounded-full bg-muted/50 px-2.5 text-xs text-muted-foreground transition-colors hover:bg-foreground/15 hover:text-foreground"
                  title={t('about.viewReleases')}
                  aria-label={t('about.viewReleases')}
                >
                  v{currentVersion}
                </button>
                <button
                  type="button"
                  onClick={() => void shellOpen(REPO_URL)}
                  className="flex h-6 w-6 items-center justify-center rounded-full bg-muted/50 text-muted-foreground transition-colors hover:bg-foreground/15 hover:text-foreground"
                  title="GitHub"
                  aria-label="GitHub"
                >
                  <Github className="h-3.5 w-3.5" aria-hidden />
                </button>
              </div>
            </div>
          </div>

          {/* 更新 */}
          <div className="mt-5 border-t border-border pt-5">
            <h3 className="mb-3 text-sm font-medium">{t('about.updateSection')}</h3>
            <div className="flex items-center justify-between">
              <div className="space-y-0.5">
                {updateStatusText && (
                  <p className={`text-sm ${hasUpdate || ready ? 'text-primary font-medium' : 'text-muted-foreground'}`}>
                    {updateStatusText}
                  </p>
                )}
                {error && (
                  <p className="text-xs text-red-500">{error}</p>
                )}
                {/* 待安装时补一句"不装也会装上"：用户在这一页才有机会知道退出兜底那条路 */}
                {ready && (
                  <p className="text-xs text-muted-foreground/60">{t('about.readyHint')}</p>
                )}
                {checkedAt && (
                  <p className="text-xs text-muted-foreground/60">{t('about.lastChecked', { time: formatTimestamp(checkedAt) })}</p>
                )}
              </div>
              <div className="flex items-center gap-2">
                {/* 点了就直接装，不再叠一层确认框：用户是被侧栏那枚变绿的图标引到这一页、
                    看完状态说明之后才按下这个按钮的，「立即安装」四个字本身就是确认。
                    上方 about.readyHint 已经说过"不装的话关闭应用时也会自动完成"。 */}
                {ready && (
                  <Button size="sm" onClick={() => void installPendingUpdate()}>
                    <CheckCircle className="mr-1.5 h-3.5 w-3.5" aria-hidden />
                    {t('about.installNow')}
                  </Button>
                )}
                {/* 下载中。没有单独的「下载更新」按钮了：发现新版就自动下载，
                    下载失败时按「检查更新」会重来一遍。 */}
                {downloading && (
                  <Button size="sm" disabled>
                    <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" />
                    {t('about.downloadingShort')}
                  </Button>
                )}
                {installing && (
                  <Button size="sm" disabled>
                    <Loader2 className="mr-1.5 h-3.5 w-3.5 animate-spin" />
                    {t('about.installingShort')}
                  </Button>
                )}
                {!downloading && !installing && !ready && (
                  <Button variant="outline" size="sm" onClick={() => void checkForUpdateNow()} disabled={checking}>
                    {checking ? t('about.checkingShort') : t('about.checkUpdate')}
                  </Button>
                )}
              </div>
            </div>
          </div>

          {/* 本次更新 */}
          {RELEASE_HIGHLIGHTS.version === currentVersion && RELEASE_HIGHLIGHTS.items.length > 0 && (
            <div className="mt-6">
              <h3 className="mb-3 text-sm font-medium">{t('about.releaseNotes', { version: RELEASE_HIGHLIGHTS.version })}</h3>
              <ul className="space-y-1.5">
                {RELEASE_HIGHLIGHTS.items.map((item, index) => (
                  <li key={index} className="flex gap-2 text-sm text-muted-foreground">
                    <span className="mt-2 h-1 w-1 shrink-0 rounded-full bg-muted-foreground/50" />
                    <span>{item}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
