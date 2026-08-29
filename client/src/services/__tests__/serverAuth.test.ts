import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('../store', () => ({ getSetting: vi.fn() }))
vi.mock('../runtimeConfig', () => ({ getBackendBaseUrl: vi.fn() }))

import { serverFetch, shouldAttachServerToken, invalidateServerToken } from '../serverAuth'
import { getSetting } from '../store'
import { getBackendBaseUrl } from '../runtimeConfig'

const mockGetSetting = getSetting as unknown as ReturnType<typeof vi.fn>
const mockGetBackendBaseUrl = getBackendBaseUrl as unknown as ReturnType<typeof vi.fn>

function okFetch(): (url: RequestInfo | URL, init?: RequestInit) => Promise<Response> {
  return async (_url: RequestInfo | URL, _init?: RequestInit) => new Response('{}', { status: 200 })
}

describe('serverAuth：令牌只发给同源服务器（PM 审查要求 1）', () => {
  beforeEach(() => {
    invalidateServerToken()
    mockGetSetting.mockReset()
    mockGetBackendBaseUrl.mockReset()
    mockGetBackendBaseUrl.mockReturnValue('http://127.0.0.1:8000')
    mockGetSetting.mockResolvedValue('secret-token')
  })

  it('shouldAttachServerToken：同源（scheme+host+port）才为 true', () => {
    expect(shouldAttachServerToken('http://127.0.0.1:8000/api/notice')).toBe(true)
    expect(shouldAttachServerToken('http://127.0.0.1:8000/ws/transcribe')).toBe(true)
    // 协议不同 → 不同源
    expect(shouldAttachServerToken('https://127.0.0.1:8000/api/notice')).toBe(false)
    // 其他域名 → 不同源
    expect(shouldAttachServerToken('https://official.example/api/desktop-updates/win32/x64/manifest')).toBe(false)
    expect(shouldAttachServerToken('https://sayitapp.site/api/notice')).toBe(false)
  })

  it('serverFetch：同源请求附加 Bearer 令牌', async () => {
    const fetchMock = vi.fn(okFetch())
    vi.stubGlobal('fetch', fetchMock)
    await serverFetch('http://127.0.0.1:8000/api/notice')
    const [_, init] = fetchMock.mock.calls[0]
    expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer secret-token')
  })

  it('serverFetch：跨域（官方更新回退等）绝不携带令牌', async () => {
    const fetchMock = vi.fn(okFetch())
    vi.stubGlobal('fetch', fetchMock)
    await serverFetch('https://official.example/api/desktop-updates/win32/x64/manifest')
    const [_, init] = fetchMock.mock.calls[0]
    expect(new Headers(init?.headers).has('Authorization')).toBe(false)
  })

  it('serverFetch：未配置令牌时不附加任何鉴权头', async () => {
    mockGetSetting.mockResolvedValue('')
    const fetchMock = vi.fn(okFetch())
    vi.stubGlobal('fetch', fetchMock)
    await serverFetch('http://127.0.0.1:8000/api/notice')
    const [_, init] = fetchMock.mock.calls[0]
    expect(new Headers(init?.headers).has('Authorization')).toBe(false)
  })
})