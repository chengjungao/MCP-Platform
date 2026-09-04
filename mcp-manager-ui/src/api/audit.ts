import { get } from './http'
import type { AuditView, PageQuery, PageView } from './types'

export function page(params: PageQuery & { action?: string } = {}): Promise<PageView<AuditView>> {
  return get<PageView<AuditView>>('/audits', { ...params })
}

export function byTarget(
  targetType: string,
  targetId: string | number,
  params: PageQuery = {}
): Promise<PageView<AuditView>> {
  return get<PageView<AuditView>>(`/audits/target/${targetType}/${targetId}`, { ...params })
}