import { del, get, post, put } from './http'
import type {
  AuthBRequest,
  AuthBView,
  AuthDRequest,
  AuthDView,
  BindingView,
  DiffView,
  EffectiveModelView,
  PageQuery,
  PageView,
  PublishRequest,
  PublishResult,
  RollbackRequest,
  ServerUpdateRequest,
  ServerView,
  ToolBatchToggleRequest,
  ToolOverlayRequest,
  ToolView,
  UpstreamRequest
} from './types'

export function page(params: PageQuery = {}): Promise<PageView<ServerView>> {
  return get<PageView<ServerView>>('/servers', { ...params })
}

export function view(id: number): Promise<ServerView> {
  return get<ServerView>(`/servers/${id}`)
}

export function update(id: number, request: ServerUpdateRequest): Promise<ServerView> {
  return put<ServerView>(`/servers/${id}`, request)
}

export function updateUpstream(id: number, request: UpstreamRequest): Promise<ServerView> {
  return put<ServerView>(`/servers/${id}/upstream`, request)
}

export function authB(id: number): Promise<AuthBView> {
  return get<AuthBView>(`/servers/${id}/auth-b`)
}

export function saveAuthB(id: number, request: AuthBRequest): Promise<AuthBView> {
  return put<AuthBView>(`/servers/${id}/auth-b`, request)
}

export function authD(id: number): Promise<AuthDView> {
  return get<AuthDView>(`/servers/${id}/auth-d`)
}

export function saveAuthD(id: number, request: AuthDRequest): Promise<AuthDView> {
  return put<AuthDView>(`/servers/${id}/auth-d`, request)
}

export function tools(id: number): Promise<ToolView[]> {
  return get<ToolView[]>(`/servers/${id}/tools`)
}

/** 原始 vs 生效差异视图（BR-2 明确要求 UI 提供）。 */
export function diff(id: number): Promise<DiffView> {
  return get<DiffView>(`/servers/${id}/diff`)
}

/** 发布时生效模型预览：Executor 将加载的内容，不含任何凭据。 */
export function effective(id: number): Promise<EffectiveModelView> {
  return get<EffectiveModelView>(`/servers/${id}/effective`)
}

export function bindings(id: number): Promise<BindingView[]> {
  return get<BindingView[]>(`/servers/${id}/bindings`)
}

export function updateToolOverlay(
  serverId: number,
  toolId: number,
  request: ToolOverlayRequest
): Promise<ToolView> {
  return put<ToolView>(`/servers/${serverId}/tools/${toolId}/overlay`, request)
}

/** 恢复默认 = 删掉该 tool 的全部覆盖，回落到原始解析值。 */
export function resetToolOverlay(serverId: number, toolId: number): Promise<ToolView> {
  return del<ToolView>(`/servers/${serverId}/tools/${toolId}/overlay`)
}

export function batchToggle(serverId: number, request: ToolBatchToggleRequest): Promise<ToolView[]> {
  return post<ToolView[]>(`/servers/${serverId}/tools/batch-toggle`, request)
}

// ---- 发布（PUB-01 ~ PUB-04） ----

export function publish(serverId: number, request: PublishRequest): Promise<PublishResult> {
  return post<PublishResult>(`/servers/${serverId}/publish`, request)
}

export function offline(serverId: number, clusterId: number): Promise<PublishResult> {
  return post<PublishResult>(`/servers/${serverId}/offline`, undefined, { clusterId })
}

export function rollback(
  serverId: number,
  clusterId: number,
  request: RollbackRequest
): Promise<PublishResult> {
  return post<PublishResult>(`/servers/${serverId}/rollback`, request, { clusterId })
}

export function publishHistory(serverId: number, clusterId: number): Promise<BindingView[]> {
  return get<BindingView[]>(`/servers/${serverId}/publish-history`, { clusterId })
}