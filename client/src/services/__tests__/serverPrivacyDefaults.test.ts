import { describe, it, expect } from 'vitest'
import { DEFAULTS } from '../defaults'

describe('服务器隐私默认值（PM 审查要求 7）', () => {
  it('诊断元数据上报默认关闭', () => {
    expect(DEFAULTS.serverShareMetadata).toBe(false)
  })

  it('服务器访问令牌默认空（不鉴权）', () => {
    expect(DEFAULTS.serverToken).toBe('')
  })

  it('本地自用默认工作模式为 local（完全离线）', () => {
    expect(DEFAULTS.workMode).toBe('local')
  })
})