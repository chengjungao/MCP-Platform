import { get, post } from './http'
import type { ChangePasswordRequest, LoginRequest, LoginResponse, MeResponse, PlatformMeta } from './types'

export function login(request: LoginRequest): Promise<LoginResponse> {
  return post<LoginResponse>('/auth/login', request)
}

export function me(): Promise<MeResponse> {
  return get<MeResponse>('/auth/me')
}

export function changePassword(request: ChangePasswordRequest): Promise<void> {
  return post<void>('/auth/change-password', request)
}

export function logout(): Promise<void> {
  return post<void>('/auth/logout')
}

/**
 * 平台元信息：协议版本、PATH/tool 命名正则、内置角色等。
 *
 * 前端把这些当只读常量渲染提示语，不在 TS 里再抄一份——
 * 协议版本这种东西抄两份，早晚有一天两边说的不一样。
 */
export function meta(): Promise<PlatformMeta> {
  return get<PlatformMeta>('/meta')
}