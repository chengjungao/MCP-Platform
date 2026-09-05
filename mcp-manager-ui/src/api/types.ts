/**
 * 控制面 DTO 的 TypeScript 镜像。
 *
 * 这些类型必须与后端 `com.mcpbridge.manager.web.dto` 保持一致——两边没有代码生成，
 * 靠 review 维持同步。改后端 record 时记得同步这里，否则运行期拿到 undefined 而类型检查全绿。
 *
 * 后端全局配置了 `default-property-inclusion: non_null`，
 * 因此所有可空字段在这里都是 `?`，取值时不要假定它一定存在。
 */

// ---------------------------------------------------------------- 通用

export interface PageView<T> {
  items: T[]
  total: number
  page: number
  size: number
  totalPages: number
}

export interface PageQuery {
  page?: number
  size?: number
  sort?: string | string[]
}

// ---------------------------------------------------------------- 枚举

export type DocSource = 'FILE' | 'URL'
export type RegistrationStatus = 'PARSING' | 'READY' | 'FAILED'
export type ServerStatus = 'DRAFT' | 'CONFIGURED' | 'PUBLISHED' | 'OFFLINE' | 'PUBLISH_FAILED'
export type BindingState = 'DRAFT' | 'PUBLISHED' | 'OFFLINE' | 'FAILED'
export type ClusterType = 'SHARED' | 'PRIVATE'
export type NodeStatus = 'ONLINE' | 'OFFLINE'
export type OverlayStatus = 'NONE' | 'ACTIVE' | 'SUSPENDED'
export type LbStrategy = 'ROUND_ROBIN' | 'WEIGHTED'
export type AuthBType = 'NONE' | 'API_KEY' | 'HTTP' | 'OAUTH2_CLIENT_CREDENTIALS' | 'CUSTOM_HEADER'
export type AuthBLocation = 'HEADER' | 'QUERY'
export type AuthDMode = 'NONE' | 'STATIC_BEARER' | 'OAUTH2'
export type DiagnosticLevel = 'ERROR' | 'WARN'

// ---------------------------------------------------------------- 认证与账号

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResponse {
  token: string
  tokenType: string
  expiresInSeconds: number
  user: UserView
}

export interface MeResponse {
  userId: number
  username: string
  displayName?: string
  deptId?: number
  roles: string[]
  permissions: string[]
  loginAt?: string
}

export interface ChangePasswordRequest {
  currentPassword: string
  newPassword: string
}

export interface UserView {
  id: number
  username: string
  displayName?: string
  email?: string
  deptId?: number
  deptName?: string
  enabled: boolean
  roles: string[]
  permissions: string[]
  createdAt?: string
  lastLoginAt?: string
}

export interface UserCreateRequest {
  username: string
  password: string
  displayName?: string
  email?: string
  deptId?: number
  roleCodes: string[]
}

export interface UserUpdateRequest {
  displayName?: string
  email?: string
  deptId?: number
  roleCodes?: string[]
  enabled?: boolean
  /** 留空表示不改密码。后端要求 8~64 位。 */
  password?: string
}

// ---------------------------------------------------------------- 组织

export interface DepartmentView {
  id: number
  name: string
  parentId?: number
  description?: string
  enabled: boolean
  memberCount: number
  children?: DepartmentView[]
}

export interface DepartmentRequest {
  name: string
  parentId?: number | null
  description?: string
  enabled?: boolean
}

export interface RoleView {
  id: number
  code: string
  name: string
  description?: string
  builtin: boolean
  permissions: string[]
}

export interface RoleRequest {
  code: string
  name: string
  description?: string
  permissions: string[]
}

export interface PermissionCatalogView {
  permissions: string[]
}

// ---------------------------------------------------------------- 注册与解析

export interface Diagnostic {
  level: DiagnosticLevel
  pointer?: string
  field?: string
  message: string
}

export interface RegistrationView {
  id: number
  name: string
  deptId?: number
  deptName?: string
  docSource: DocSource
  sourceRef?: string
  swaggerVersion?: string
  docVersion: number
  status: RegistrationStatus
  operationCount: number
  diagnostics?: Diagnostic[]
  /** 解析成功后 1:1 生成的 MCP Server id（BR-1）。 */
  serverId?: number
  rawDocSha256?: string
  createdAt?: string
  updatedAt?: string
}

export interface CreateByUrlRequest {
  name: string
  url: string
  deptId?: number
  pathSegment?: string
  /** 挂到已有 MCP Server（多服务聚合）；不传则新建 Server。 */
  targetServerId?: number
}

/** 重新解析后的差异报告（REG-03 / US-12）。 */
export interface DiffReport {
  newDocVersion: number
  added: string[]
  removed: string[]
  changed: string[]
  /** 锚点失效、进入挂起区的覆盖项——不静默丢弃，UI 必须显式呈现。 */
  suspendedOverlays: string[]
  preservedOverlays: string[]
}

// ---------------------------------------------------------------- Server / Tool

export interface UpstreamSnapshot {
  baseUrls: string[]
  lbStrategy?: LbStrategy
  weights?: number[]
  connectTimeoutMs?: number
  readTimeoutMs?: number
  retries?: number
  retryOnStatus?: number[]
  circuitBreaker?: {
    failureThreshold: number
    openMs: number
    halfOpenProbes: number
  }
}

/** 多上游场景下单个上游服务视图（后端 ServerDtos.UpstreamView）。 */
export interface UpstreamView {
  serviceId: string
  name: string
  config: UpstreamSnapshot
  authB?: AuthBView | null
  updatedAt?: string
}

/** 按 serviceId upsert 单个上游服务的请求（后端 ServerDtos.UpstreamEntryRequest）。 */
export interface UpstreamEntryRequest {
  serviceId?: string
  name?: string
  baseUrls: string[]
  lbStrategy?: LbStrategy
  connectTimeoutMs?: number
  readTimeoutMs?: number
  retries?: number
  retryOnStatus?: number[]
  cbFailureThreshold?: number
  cbOpenMs?: number
  cbHalfOpenProbes?: number
}

export interface ExtraHeader {
  name: string
  value: string
}

/**
 * 上行授权回显：只有掩码与非敏感字段。
 * 后端不会回传任何密钥明文，表单里的密钥留空即表示「不修改」（SEC-01）。
 */
export interface AuthBView {
  type?: AuthBType
  location?: AuthBLocation
  name?: string
  scheme?: string
  username?: string
  maskedPreview?: string
  tokenUrl?: string
  clientId?: string
  scope?: string
  headerTemplate?: string
  extraHeaders?: ExtraHeader[]
  updatedAt?: string
}

export interface AuthBRequest {
  type?: AuthBType
  location?: AuthBLocation
  name?: string
  scheme?: string
  username?: string
  secret?: string
  password?: string
  tokenUrl?: string
  clientId?: string
  clientSecret?: string
  scope?: string
  headerTemplate?: string
  extraHeaders?: ExtraHeader[]
}

export interface AuthDView {
  mode?: AuthDMode
  staticTokenCount: number
  scopes?: string[]
  issuer?: string
  authorizationEndpoint?: string
  tokenEndpoint?: string
  registrationEndpoint?: string
  resourceMetadataUrl?: string
}

export interface AuthDRequest {
  mode?: AuthDMode
  staticTokens?: string[]
  scopes?: string[]
  issuer?: string
  authorizationEndpoint?: string
  tokenEndpoint?: string
  registrationEndpoint?: string
}

export interface ServerView {
  id: number
  name: string
  title?: string
  description?: string
  pathSegment: string
  /** 已发布时给出主集群上的完整端点示例；未发布为 null。 */
  endpointPreview?: string
  version?: string
  protocolVersion: string
  status: ServerStatus
  overlayVersion: number
  deptId?: number
  deptName?: string
  registrationId?: number
  toolCount: number
  enabledToolCount: number
  listTtlMs: number
  /** 多上游服务列表（一个 Server 挂多个 REST 服务）。 */
  upstreams?: UpstreamView[]
  authB?: AuthBView
  authD?: AuthDView
  bindings?: BindingView[]
  createdAt?: string
  updatedAt?: string
  /**
   * 当前账号是否可在本部门树内管理该 Server。
   * false = 跨部门只读授权（后端已脱敏：endpoint/authB/authD/bindings/上游配置均为空），
   * 前端据此渲染只读态，不依赖权限点判断。
   */
  manageable: boolean
}

export interface ServerUpdateRequest {
  name?: string
  title?: string
  description?: string
  pathSegment?: string
  listTtlMs?: number
}

/** 新建 MCP Server（先建基础信息，再在该 Server 下注册多份 Swagger 文档）。 */
export interface ServerCreateRequest {
  name: string
  title?: string
  description?: string
  pathSegment?: string
  deptId?: number
}

export interface ToolView {
  id: number
  anchor: string
  method: string
  path: string
  baseName: string
  effectiveName: string
  baseSummary?: string
  effectiveDescription?: string
  baseInputSchema?: JsonSchema
  effectiveInputSchema?: JsonSchema
  parameterIn?: Record<string, string>
  requestBodyRequired: boolean
  idempotent: boolean
  enabled: boolean
  streaming: boolean
  streamFormat?: string
  overlayStatus: OverlayStatus
  hasOverlay: boolean
}

/** JSON Schema 是任意嵌套结构，这里不做过度建模，用索引签名兜住。 */
export type JsonSchema = Record<string, unknown>

/**
 * Tool 覆盖写入（BR-2：可覆盖字段集合受限，防越界）。
 * 字段传 null 表示不修改；传空串表示清空覆盖、回落到基座值。
 */
export interface ToolOverlayRequest {
  name?: string | null
  description?: string | null
  inputSchema?: JsonSchema | null
  enabled?: boolean | null
  streaming?: boolean | null
  streamFormat?: string | null
}

export interface ToolBatchToggleRequest {
  toolIds: number[]
  enabled: boolean
}

export interface FieldChange {
  field: string
  baseValue: unknown
  effectiveValue: unknown
  overridden: boolean
}

export interface ToolDiff {
  anchor: string
  baseName: string
  effectiveName: string
  fields: FieldChange[]
}

export interface DiffView {
  serverId: number
  serverFields: FieldChange[]
  tools: ToolDiff[]
  suspendedOverlays: string[]
}

export interface ToolSnapshot {
  name: string
  title?: string
  description?: string
  method: string
  path: string
  anchor?: string
  inputSchema?: JsonSchema
  parameterIn?: Record<string, string>
  requestBodyRequired: boolean
  streaming: boolean
  streamFormat?: string
  idempotent: boolean
  /** 指向 UpstreamView.serviceId，注册时自动绑定。 */
  upstreamRef?: string
}

export interface EffectiveModelView {
  serverId: number
  name: string
  pathSegment: string
  title?: string
  description?: string
  version?: string
  protocolVersion: string
  listTtlMs: number
  tools: ToolSnapshot[]
}

// ---------------------------------------------------------------- 集群与发布

export interface ClusterView {
  id: number
  name: string
  type: ClusterType
  entrypoint: string
  pathPrefix?: string
  /** 端点模板，用于提示用户「只能自定义末段」（BR-3）。 */
  endpointTemplate?: string
  ownerDeptId?: number
  ownerDeptName?: string
  description?: string
  enabled: boolean
  grantedDeptIds: number[]
  nodeCount: number
  onlineNodeCount: number
  publishedServerCount: number
  revision: number
  createdAt?: string
}

export interface ClusterRequest {
  name: string
  type: ClusterType
  entrypoint: string
  pathPrefix?: string
  ownerDeptId?: number
  description?: string
  enabled?: boolean
  scopes?: Record<string, unknown>
}

export interface NodeView {
  id: number
  clusterId: number
  nodeKey: string
  host?: string
  port?: number
  version?: string
  protocolVersion?: string
  status: NodeStatus
  lastHeartbeatAt?: string
  loadInfo?: Record<string, unknown>
}

export interface PublishRequest {
  clusterId: number
  note?: string
}

export interface RollbackRequest {
  version: number
}

export interface BindingView {
  id: number
  serverId: number
  serverName: string
  pathSegment: string
  clusterId: number
  clusterName: string
  clusterType?: ClusterType
  version: number
  state: BindingState
  current: boolean
  endpoint: string
  publishedBy?: number
  publishedAt?: string
  offlinedAt?: string
  failureReason?: string
  toolCount: number
}

export interface PublishResult {
  bindingId: number
  version: number
  endpoint: string
  clusterName: string
  state: BindingState
  revision: number
  message?: string
}

// ---------------------------------------------------------------- 审计与元信息

export interface AuditView {
  id: number
  actorId?: number
  actorName?: string
  action: string
  targetType?: string
  targetId?: string
  deptId?: number
  detail?: Record<string, unknown>
  traceId?: string
  clientIp?: string
  createdAt?: string
}

export interface PlatformMeta {
  supportedProtocolVersion: string
  legacyProtocolVersions: string[]
  legacySupported: boolean
  upgradeGuideUrl: string
  jsonSchemaDialect: string
  defaultListTtlMs: number
  defaultPathPrefix: string
  pathSegmentPattern: string
  toolNamePattern: string
  builtinRoles: string[]
}
// ---------------------------------------------------------------- 跨部门访问申请

export type AccessStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'REVOKED'

/** 可申请目录行（我不可直接访问的 Server 最小信息）。 */
export interface AccessCatalogRow {
  id: number
  name: string
  title?: string
  pathSegment: string
  status: ServerStatus
  deptId: number
  deptName?: string
  /** 我的部门对该 Server 的申请状态；null = 从未申请。 */
  myStatus?: AccessStatus | null
  accessId?: number | null
  createdAt?: string
}

/** 申请/授权记录视图（我发起的 / 待审批 / 已授权共用）。 */
export interface AccessView {
  id: number
  serverId: number
  serverName: string
  serverPathSegment: string
  deptId: number
  deptName?: string
  reason?: string
  status: AccessStatus
  requestedBy: number
  requesterName?: string
  requestedAt?: string
  reviewedBy?: number
  reviewerName?: string
  reviewedAt?: string
  reviewNote?: string
  /** 当前账号是否可管理该 Server（资源方视角）。 */
  manageable: boolean
}
