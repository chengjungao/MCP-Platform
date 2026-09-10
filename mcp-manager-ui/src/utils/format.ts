/**
 * 枚举中文标签与格式化。
 *
 * 集中在一个文件里，是为了让「同一个状态在不同页面上叫不同名字」这种事不可能发生。
 */

export type TagType = 'primary' | 'success' | 'warning' | 'info' | 'danger'

export const REGISTRATION_STATUS_LABEL: Record<string, string> = {
  PARSING: '解析中',
  READY: '解析成功',
  FAILED: '解析失败'
}

export const SERVER_STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  CONFIGURED: '已配置',
  PUBLISHED: '已发布',
  OFFLINE: '已下线',
  PUBLISH_FAILED: '发布失败'
}

export const BINDING_STATE_LABEL: Record<string, string> = {
  DRAFT: '草稿',
  PUBLISHED: '生效中',
  OFFLINE: '已下线',
  FAILED: '失败'
}

export const NODE_STATUS_LABEL: Record<string, string> = {
  ONLINE: '在线',
  OFFLINE: '离线'
}

export const CLUSTER_TYPE_LABEL: Record<string, string> = {
  SHARED: '共享集群',
  PRIVATE: '专属集群'
}

export const OVERLAY_STATUS_LABEL: Record<string, string> = {
  NONE: '无覆盖',
  ACTIVE: '覆盖生效',
  SUSPENDED: '挂起（锚点失效）'
}

export const LB_STRATEGY_LABEL: Record<string, string> = {
  ROUND_ROBIN: '轮询',
  WEIGHTED: '加权'
}

export const AUTH_B_TYPE_LABEL: Record<string, string> = {
  NONE: '无需鉴权',
  API_KEY: 'API Key',
  HTTP: 'HTTP（Bearer / Basic）',
  OAUTH2_CLIENT_CREDENTIALS: 'OAuth2 客户端凭据',
  CUSTOM_HEADER: '自定义 Header'
}

export const AUTH_D_MODE_LABEL: Record<string, string> = {
  NONE: '不鉴权',
  STATIC_BEARER: '静态 Bearer 令牌',
  OAUTH2: 'OAuth 2.1 资源服务器'
}

export function labelOf(table: Record<string, string>, value?: string | null): string {
  if (!value) return '—'
  return table[value] ?? value
}

/** 状态色：绿=正常可用，灰=未生效，黄=需要注意，红=失败。 */
export function statusTag(value?: string | null): TagType {
  switch (value) {
    case 'READY':
    case 'PUBLISHED':
    case 'ONLINE':
    case 'ACTIVE':
    case 'CONFIGURED':
      return 'success'
    case 'PARSING':
    case 'DRAFT':
    case 'NONE':
      return 'info'
    case 'SUSPENDED':
    case 'OFFLINE':
      return 'warning'
    case 'FAILED':
    case 'PUBLISH_FAILED':
      return 'danger'
    default:
      return 'info'
  }
}

/** 后端统一用 UTC Instant；这里按浏览器时区显示，但把时区标出来避免误读。 */
export function formatDateTime(value?: string | null): string {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
  )
}

/** sha256 只展示前 16 位：够人工比对，不至于把表格撑爆。 */
export function shortSha(value?: string | null): string {
  if (!value) return '—'
  return value.length > 16 ? `${value.slice(0, 16)}…` : value
}

/** 毫秒转人话，用于 TTL / 超时 / 熔断保持时间这类字段。 */
export function formatDuration(ms?: number | null): string {
  if (ms == null) return '—'
  if (ms < 1000) return `${ms} ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(ms % 1000 === 0 ? 0 : 1)} s`
  return `${(ms / 60_000).toFixed(1)} min`
}

/**
 * 审计动作码 → 中文，与后端 `AuditAction` 常量一一对应。
 *
 * 后端新增动作码时这里会缺一条，`labelOf` 的兜底行为是直接显示原始码，
 * 所以漏译只会让界面变丑，不会让某条审计记录消失。
 */
export const AUDIT_ACTION_LABEL: Record<string, string> = {
  'auth.login': '登录',
  'auth.login_failed': '登录失败',
  'user.create': '新建账号',
  'user.update': '修改账号',
  'user.toggle': '启用/停用账号',
  'user.role_change': '调整账号角色',
  'dept.create': '新建部门',
  'dept.update': '修改部门',
  'dept.delete': '删除部门',
  'role.create': '新建角色',
  'role.update': '修改角色',
  'role.delete': '删除角色',
  'registration.create': '注册文档',
  'registration.reimport': '重新解析',
  'registration.parse_failed': '解析失败',
  'server.update': '修改 Server',
  'server.path_change': '修改 PATH 末段',
  'server.delete': '删除 Server',
  'tool.overlay_update': '修改 Tool 覆盖',
  'tool.overlay_reset': '恢复 Tool 默认',
  'tool.toggle': '启用/停用 Tool',
  'catalog.resource_change': '变更 Resource',
  'catalog.prompt_change': '变更 Prompt',
  'authb.change': '变更REST 服务鉴权',
  'authd.change': '变更MCP 客户端授权',
  'cluster.create': '新建集群',
  'cluster.update': '修改集群',
  'cluster.grant': '集群授权部门',
  'publish.execute': '发布',
  'publish.offline': '下线',
  'publish.rollback': '回滚',
  'node.register': '节点注册',
  'node.offline': '节点下线',
  'access.apply': '提交跨部门申请',
  'access.approve': '批准跨部门申请',
  'access.reject': '驳回跨部门申请',
  'access.revoke': '回收跨部门授权',
  'audit.export': '导出审计'
}

/** 审计页的动作用于下拉选项，顺序与上表一致。 */
export const AUDIT_ACTIONS: string[] = Object.keys(AUDIT_ACTION_LABEL)
export const ACCESS_STATUS_LABEL: Record<string, string> = {
  PENDING: '待审批',
  APPROVED: '已授权',
  REJECTED: '已驳回',
  REVOKED: '已回收'
}

/** 访问申请状态色：绿=已授权，黄=待审批，红=驳回/回收，灰=无。 */
export function accessStatusTag(value?: string | null): TagType {
  switch (value) {
    case 'APPROVED':
      return 'success'
    case 'PENDING':
      return 'warning'
    case 'REJECTED':
    case 'REVOKED':
      return 'danger'
    default:
      return 'info'
  }
}
