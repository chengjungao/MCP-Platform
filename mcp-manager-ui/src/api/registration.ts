import { api, get, post, rawText, upload } from './http'
import type {
  CreateByUrlRequest,
  DiffReport,
  PageQuery,
  PageView,
  RegistrationView
} from './types'

export function page(params: PageQuery = {}): Promise<PageView<RegistrationView>> {
  return get<PageView<RegistrationView>>('/registrations', { ...params })
}

export function view(id: number): Promise<RegistrationView> {
  return get<RegistrationView>(`/registrations/${id}`)
}

export function createByUrl(request: CreateByUrlRequest): Promise<RegistrationView> {
  return post<RegistrationView>('/registrations/by-url', request)
}

export interface UploadRegistrationParams {
  name: string
  file: File
  deptId?: number
  pathSegment?: string
}

export function createByUpload(
  params: UploadRegistrationParams,
  onProgress?: (percent: number) => void
): Promise<RegistrationView> {
  const form = new FormData()
  form.append('name', params.name)
  form.append('file', params.file)
  if (params.deptId != null) form.append('deptId', String(params.deptId))
  if (params.pathSegment) form.append('pathSegment', params.pathSegment)
  return upload<RegistrationView>('/registrations/upload', form, onProgress)
}

/** 直接粘贴 OpenAPI/Swagger 文本注册。后端按 text/plain 收，避免 JSON 被二次解析。 */
export function createByText(
  name: string,
  rawDoc: string,
  deptId?: number,
  pathSegment?: string
): Promise<RegistrationView> {
  const params: Record<string, unknown> = { name }
  if (deptId != null) params.deptId = deptId
  if (pathSegment) params.pathSegment = pathSegment
  // by-text 的请求体是裸文本而不是 JSON，必须显式指定 Content-Type，
  // 否则 axios 会把它当字符串体配上 application/json，后端的 consumes 匹配不上直接 415
  return api<RegistrationView>({
    method: 'POST',
    url: '/registrations/by-text',
    params,
    data: rawDoc,
    headers: { 'Content-Type': 'text/plain' }
  })
}

export function reimportUpload(id: number, file: File): Promise<DiffReport> {
  const form = new FormData()
  form.append('file', file)
  return upload<DiffReport>(`/registrations/${id}/reimport-upload`, form)
}

/** 原始文档只读回显：前端不允许编辑，改动必须走「重新解析 + 覆盖」。 */
export function rawDoc(id: number): Promise<string> {
  return rawText(`/registrations/${id}/raw`)
}