// 服务器模式鉴权：可选的服务器访问令牌（Bearer）。
// 令牌为空 = 不鉴权（本地默认）；配置了令牌后，服务端要求所有 /api/* 与 /ws/* 请求携带它。
// 密钥按敏感字段在 Rust 侧 DPAPI 加密存储，这里只读取明文使用。
//
// 安全约束（PM 审查要求）：
//  - 令牌只发给「用户配置的服务器地址」同源的请求；官方更新回退等其他域名绝不携带。
//  - WebSocket 使用 query 携带令牌，所有日志/诊断必须遮蔽 token 参数。

import { getSetting } from './store'
import { getBackendBaseUrl } from './runtimeConfig'

let tokenCache: string | null = null
let shareMetaCache: boolean | null = null

export async function getServerToken(): Promise<string> {
  if (tokenCache === null) {
    try {
      tokenCache = (await getSetting('serverToken', '') as string) || ''
    } catch {
      // 存储读取失败时回退为「无令牌」：宁可鉴权失败，也不因异常拖垮连接建立
      tokenCache = ''
    }
  }
  return tokenCache
}

/** 令牌被修改后调用，丢弃缓存，下次请求重新读取。 */
export function invalidateServerToken(): void {
  tokenCache = null
}

/** 是否向该 URL 附加令牌：仅当与「用户配置的服务器地址」同源（scheme+host+port）。 */
export function shouldAttachServerToken(targetUrl: string): boolean {
  try {
    return new URL(targetUrl).origin === new URL(getBackendBaseUrl()).origin
  } catch {
    return false
  }
}

/** 带鉴权的 fetch：仅在同源请求且配置了令牌时附加 Authorization: Bearer <token>。 */
export async function serverFetch(url: string, init?: RequestInit): Promise<Response> {
  const token = await getServerToken()
  const headers = new Headers(init?.headers)
  if (token && shouldAttachServerToken(url)) headers.set('Authorization', `Bearer ${token}`)
  return fetch(url, { ...init, headers })
}

/** 服务器模式是否获准上报诊断元数据（用户名/主机名/IP/进程/exe 路径等）。默认关闭。 */
export async function getServerShareMetadata(): Promise<boolean> {
  if (shareMetaCache === null) {
    try {
      shareMetaCache = Boolean(await getSetting('serverShareMetadata', false))
    } catch {
      // 读取失败回退为 false：元数据默认不上报
      shareMetaCache = false
    }
  }
  return shareMetaCache
}

/** 元数据授权开关变化后调用，丢弃缓存。 */
export function invalidateServerShareMetadata(): void {
  shareMetaCache = null
}