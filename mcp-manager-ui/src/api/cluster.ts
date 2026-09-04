import { get, post, put } from './http'
import type { ClusterRequest, ClusterView, NodeView } from './types'

export function list(): Promise<ClusterView[]> {
  return get<ClusterView[]>('/clusters')
}

export function view(id: number): Promise<ClusterView> {
  return get<ClusterView>(`/clusters/${id}`)
}

export function create(request: ClusterRequest): Promise<ClusterView> {
  return post<ClusterView>('/clusters', request)
}

export function update(id: number, request: ClusterRequest): Promise<ClusterView> {
  return put<ClusterView>(`/clusters/${id}`, request)
}

export function grant(id: number, deptIds: number[]): Promise<ClusterView> {
  return put<ClusterView>(`/clusters/${id}/grants`, { deptIds })
}

/**
 * 轮换节点接入令牌。
 *
 * 新令牌只在这一次响应里出现，后端只存 sha256，之后无法再取回——
 * UI 必须把它显式展示出来并提示用户立刻保存。
 */
export function rotateNodeToken(id: number): Promise<Record<string, string>> {
  return post<Record<string, string>>(`/clusters/${id}/rotate-node-token`)
}

export function nodes(id: number): Promise<NodeView[]> {
  return get<NodeView[]>(`/clusters/${id}/nodes`)
}

export function offlineNode(nodeId: number): Promise<NodeView[]> {
  return post<NodeView[]>(`/clusters/nodes/${nodeId}/offline`)
}