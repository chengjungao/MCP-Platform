import { downloadCsv, get } from './http'
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

/**
 * 导出当前筛选条件下的审计为 CSV。
 *
 * 只带 action 一个筛选参数：部门隔离由后端按当前登录身份施加，前端不传、也传不了部门参数——
 * 把"能看到哪些部门"这件事留在服务端是唯一安全的做法。
 *
 * 超出行数上限时后端回 409 + 可读原因（不做静默截断），调用方用 `notifyError` 展示即可。
 */
export function exportCsv(action?: string): Promise<void> {
  return downloadCsv('/audits/export', action ? { action } : {}, `audit-${stamp()}.csv`)
}

/** 本地时间的 yyyyMMdd-HHmm，仅作为后端没给 Content-Disposition 时的兜底文件名。 */
function stamp(): string {
  const now = new Date()
  const pad = (value: number): string => String(value).padStart(2, '0')
  return (
    `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}` +
    `-${pad(now.getHours())}${pad(now.getMinutes())}`
  )
}