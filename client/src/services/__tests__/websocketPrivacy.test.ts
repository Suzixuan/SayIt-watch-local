import { describe, it, expect } from 'vitest'
import { maskWsUrl, serverMetadataPayload } from '../websocket'
import type { ClientRuntimeInfo } from '../../types/appApi'
import type { ActiveAppContext } from '../../types/appContext'

const fakeClientMeta: ClientRuntimeInfo = {
  userId: 'user',
  userName: 'user',
  deviceId: 'dev-1',
  hostname: 'my-pc',
  clientVersion: '0.1.8',
  platform: 'win32',
  osVersion: 'Windows 10',
  localIp: '192.168.1.5',
  systemLocale: 'zh-CN',
  cpuCores: 8,
  memoryMb: 16384,
}

const fakeAppContext: ActiveAppContext = {
  processName: 'notepad.exe',
  exePath: 'C:\\Windows\\notepad.exe',
  windowTitle: 'Untitled - Notepad',
  windowClass: 'Notepad',
  focusClass: 'Edit',
  controlType: 'Edit',
}

describe('maskWsUrl：日志/诊断中遮蔽 token（PM 审查要求 3）', () => {
  it('遮蔽 query 中的 token 值', () => {
    const masked = maskWsUrl('wss://srv.local/ws/transcribe?token=super-secret-token-abc')
    expect(masked).not.toContain('super-secret-token-abc')
    expect(masked).toContain('token=***')
  })

  it('无 token 时原样返回', () => {
    expect(maskWsUrl('wss://srv.local/ws/transcribe')).toBe('wss://srv.local/ws/transcribe')
  })

  it('非 URL 输入也能兜底遮蔽', () => {
    expect(maskWsUrl('wss://srv.local/?token=abc&x=1')).toContain('token=***')
  })
})

describe('serverMetadataPayload：元数据默认关闭、仅在授权后发送（PM 审查要求 7）', () => {
  it('clientMeta/appContext 为空时不产生任何字段', () => {
    expect(serverMetadataPayload(null, null)).toEqual({})
    expect(serverMetadataPayload(undefined, undefined)).toEqual({})
  })

  it('有值时生成 client_meta 与 app_context（供 sendStart 在授权开关打开时附带）', () => {
    const payload = serverMetadataPayload(fakeClientMeta, fakeAppContext)
    expect(payload.client_meta?.user_id).toBe('user')
    expect(payload.client_meta?.hostname).toBe('my-pc')
    expect(payload.client_meta?.local_ip).toBe('192.168.1.5')
    expect(payload.app_context?.process_name).toBe('notepad.exe')
    expect(payload.app_context?.exe_path).toBe('C:\\Windows\\notepad.exe')
  })
})