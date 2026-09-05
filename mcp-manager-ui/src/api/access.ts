import { get, post } from './http'
import type {
  AccessCatalogRow,
  AccessView,
  PageQuery,
  PageView
} from './types'

/** 跨部门访问申请：目录 / 发起 / 我发起的 / 待审批 / 已授权 / 审批动作。 */

export function catalog(params: PageQuery = {}): Promise<PageView<AccessCatalogRow>> {
  return get<PageView<AccessCatalogRow>>('/access/catalog', { ...params })
}

export interface ApplyRequest {
  serverId: number
  reason?: string
}

export function apply(request: ApplyRequest): Promise<AccessView> {
  return post<AccessView>('/access', request)
}

export function mine(): Promise<AccessView[]> {
  return get<AccessView[]>('/access/mine')
}

export function todo(): Promise<AccessView[]> {
  return get<AccessView[]>('/access/todo')
}

export function grants(): Promise<AccessView[]> {
  return get<AccessView[]>('/access/grants')
}

export function approve(id: number, note?: string): Promise<AccessView> {
  return post<AccessView>(`/access/${id}/approve`, { note: note ?? null })
}

export function reject(id: number, note?: string): Promise<AccessView> {
  return post<AccessView>(`/access/${id}/reject`, { note: note ?? null })
}

export function revoke(id: number, note?: string): Promise<AccessView> {
  return post<AccessView>(`/access/${id}/revoke`, { note: note ?? null })
}
