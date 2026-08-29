import { describe, it, expect, vi, beforeEach } from 'vitest'

const { serverFetchMock } = vi.hoisted(() => ({ serverFetchMock: vi.fn() }))

vi.mock('@/services/runtimeConfig', () => ({
  getUpdateBaseUrl: () => 'https://my-server.example',
  getOfficialUpdateBaseUrl: () => 'https://official.example',
  isOfficialUpdateChannel: () => false,
}))
vi.mock('@/services/serverAuth', () => ({ serverFetch: serverFetchMock }))

import { checkVersionUpdate } from '../updateChecker'

function notFoundFetch(): (url: RequestInfo | URL, init?: RequestInit) => Promise<Response> {
  return async (_url: RequestInfo | URL, _init?: RequestInit) => new Response(JSON.stringify({}), { status: 404 })
}

describe('updateChecker：官方更新回退绝不携带令牌（PM 审查要求 1）', () => {
  beforeEach(() => {
    serverFetchMock.mockReset()
    serverFetchMock.mockResolvedValue(new Response(JSON.stringify({}), { status: 404 }))
    vi.stubGlobal('fetch', vi.fn(notFoundFetch()))
  })

  it('configured 请求走 serverFetch（仅它可能携带令牌）', async () => {
    await checkVersionUpdate('0.1.8')
    expect(serverFetchMock).toHaveBeenCalled()
    expect(String(serverFetchMock.mock.calls[0][0])).toContain('my-server.example')
  })

  it('官方回退（跨域）使用无鉴权的普通 fetch，绝无 Authorization 头', async () => {
    const fetchMock = vi.fn(notFoundFetch())
    vi.stubGlobal('fetch', fetchMock)

    await checkVersionUpdate('0.1.8')

    const officialCall = fetchMock.mock.calls.find(([url]) => String(url).includes('official.example'))
    expect(officialCall).toBeDefined()
    const [_, init] = officialCall as [RequestInfo | URL, RequestInit | undefined]
    expect(new Headers(init?.headers).has('Authorization')).toBe(false)
  })

  it('configured 已返回版本时不触发官方回退', async () => {
    serverFetchMock.mockResolvedValue(
      new Response(JSON.stringify({ version: '9.9.9' }), { status: 200 }),
    )
    const fetchMock = vi.fn(notFoundFetch())
    vi.stubGlobal('fetch', fetchMock)

    const info = await checkVersionUpdate('0.1.8')
    expect(info.latestVersion).toBe('9.9.9')
    expect(fetchMock).not.toHaveBeenCalled()
  })
})