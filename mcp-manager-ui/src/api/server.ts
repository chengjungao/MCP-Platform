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
  PromptRequest,
  PromptView,
  PublishRequest,
  PublishResult,
  ResourceRequest,
  ResourceView,
  RollbackRequest,
  ServerCreateRequest,
  ServerUpdateRequest,
  ServerView,
  ToolBatchToggleRequest,
  ToolOverlayRequest,
  ToolView,
  UpstreamEntryRequest
} from './types'

export function page(params: PageQuery = {}): Promise<PageView<ServerView>> {
  return get<PageView<ServerView>>('/servers', { ...params })
}

export function view(id: number): Promise<ServerView> {
  return get<ServerView>(`/servers/${id}`)
}

/** 新建空 MCP Server（先建基础信息，再注册文档）。 */
export function create(request: ServerCreateRequest): Promise<ServerView> {
  return post<ServerView>('/servers', request)
}

export function update(id: number, request: ServerUpdateRequest): Promise<ServerView> {
  return put<ServerView>(`/servers/${id}`, request)
}

/** 按 serviceId upsert 单个 REST 服务配置（多服务支持）。 */
export function upsertUpstream(
  id: number,
  serviceId: string,
  request: UpstreamEntryRequest
): Promise<ServerView> {
  return put<ServerView>(`/servers/${id}/upstreams/${serviceId}`, request)
}

/** 删除某个 REST 服务（多服务场景下移除一份 Swagger 对应的服务配置）。 */
export function deleteUpstream(id: number, serviceId: string): Promise<ServerView> {
  return del<ServerView>(`/servers/${id}/upstreams/${serviceId}`)
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

// ---- Resource（SVR-05）----

export function resources(serverId: number): Promise<ResourceView[]> {
  return get<ResourceView[]>(`/servers/${serverId}/resources`)
}

/** 新增 Resource；URI 在 Server 内唯一，重复会被后端 400。 */
export function createResource(serverId: number, request: ResourceRequest): Promise<ResourceView> {
  return post<ResourceView>(`/servers/${serverId}/resources`, request)
}

export function updateResource(
  serverId: number,
  resourceId: number,
  request: ResourceRequest
): Promise<ResourceView> {
  return put<ResourceView>(`/servers/${serverId}/resources/${resourceId}`, request)
}

export function deleteResource(serverId: number, resourceId: number): Promise<void> {
  return del<void>(`/servers/${serverId}/resources/${resourceId}`)
}

// ---- Prompt（SVR-06）----

export function prompts(serverId: number): Promise<PromptView[]> {
  return get<PromptView[]>(`/servers/${serverId}/prompts`)
}

/** 新增 Prompt；模板占位符与参数声明不一致会被后端 400，前端也做同一份校验。 */
export function createPrompt(serverId: number, request: PromptRequest): Promise<PromptView> {
  return post<PromptView>(`/servers/${serverId}/prompts`, request)
}

export function updatePrompt(
  serverId: number,
  promptId: number,
  request: PromptRequest
): Promise<PromptView> {
  return put<PromptView>(`/servers/${serverId}/prompts/${promptId}`, request)
}

export function deletePrompt(serverId: number, promptId: number): Promise<void> {
  return del<void>(`/servers/${serverId}/prompts/${promptId}`)
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

/** 删除 MCP Server（已发布到集群的会被后端 409 拦截，须先下线）。 */
export function remove(serverId: number): Promise<void> {
  return del<void>(`/servers/${serverId}`)
}