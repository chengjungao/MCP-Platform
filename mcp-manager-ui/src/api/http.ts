import axios, { AxiosError, type AxiosInstance, type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'

/** 令牌只放 localStorage：控制台是内部系统，XSS 风险由同源反代与 CSP 兜，不引入 Cookie 复杂度。 */
export const TOKEN_KEY = 'mcp.manager.token'

/** 后端 `ApiResponse<T>` 信封。前端只认 success + code，业务错误也走同一结构。 */
export interface ApiEnvelope<T> {
  success: boolean
  code: string
  message: string | null
  data: T
  details: Record<string, unknown> | null
  timestamp: string
}

/**
 * 归一化后的错误。
 *
 * 之所以要把 HTTP 层错误也包成 ApiError：后端业务失败是 200/4xx + 信封，
 * 网络失败是 axios 异常，两条路径如果各说各话，页面上就会出现两种风格的报错，
 * 用户没法判断「是我填错了还是平台挂了」。
 */
export class ApiError extends Error {
  readonly code: string
  readonly status: number
  readonly details: Record<string, unknown> | null

  constructor(code: string, message: string, status: number, details: Record<string, unknown> | null) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
    this.details = details
  }
}

export const http: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE ?? '/api/v1',
  timeout: 60_000
})

type UnauthorizedHandler = () => void

let unauthorizedHandler: UnauthorizedHandler | null = null

/**
 * 注册 401 处理器。
 *
 * 用回调注册而不是在这里直接 import router/store，是为了避免
 * 「http → store → api → http」的循环依赖：模块初始化顺序一旦颠倒就是 undefined。
 */
export function onUnauthorized(handler: UnauthorizedHandler): void {
  unauthorizedHandler = handler
}

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ApiEnvelope<unknown>>) => {
    const status = error.response?.status ?? 0
    const body = error.response?.data
    const apiError = new ApiError(
      body?.code ?? (status === 0 ? 'NETWORK_ERROR' : `HTTP_${status}`),
      body?.message ?? describeTransport(error, status),
      status,
      body?.details ?? null
    )
    if (status === 401) {
      unauthorizedHandler?.()
    }
    return Promise.reject(apiError)
  }
)

function describeTransport(error: AxiosError, status: number): string {
  if (status === 0) {
    return error.code === 'ECONNABORTED' ? '请求超时，请检查网络或稍后重试' : '无法连接控制面，请确认 Manager 已启动'
  }
  if (status === 401) return '登录已过期，请重新登录'
  if (status === 403) return '没有权限执行此操作'
  if (status === 404) return '资源不存在，或不属于你所在的部门'
  if (status >= 500) return '控制面内部错误，请带上 traceId 联系平台管理员'
  return error.message || `请求失败（HTTP ${status}）`
}

/** 发请求并拆信封。业务代码只拿到 `data`，失败一律以 ApiError 抛出。 */
export async function api<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await http.request<ApiEnvelope<T>>(config)
  const body = response.data
  if (!body || body.success === false) {
    throw new ApiError(
      body?.code ?? 'UNKNOWN',
      body?.message ?? '请求失败',
      response.status,
      body?.details ?? null
    )
  }
  return body.data
}

export function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  return api<T>({ method: 'GET', url, params })
}

export function post<T>(url: string, data?: unknown, params?: Record<string, unknown>): Promise<T> {
  return api<T>({ method: 'POST', url, data, params })
}

export function put<T>(url: string, data?: unknown, params?: Record<string, unknown>): Promise<T> {
  return api<T>({ method: 'PUT', url, data, params })
}

export function del<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  return api<T>({ method: 'DELETE', url, params })
}

/**
 * 取原始 Swagger 文档（BR-2：只读回显）。
 * 这个端点直接返回 text/plain，不走信封，所以不能复用 api()。
 */
export async function rawText(url: string): Promise<string> {
  const response = await http.get<string>(url, {
    responseType: 'text',
    // axios 默认会尝试 JSON.parse，原文是 YAML 时会抛错，这里显式关掉
    transformResponse: [(data: string) => data]
  })
  return response.data
}

/** 上传 multipart。progress 供大文档上传时给用户反馈。 */
export function upload<T>(  url: string,
  form: FormData,
  onProgress?: (percent: number) => void
): Promise<T> {
  return api<T>({
    method: 'POST',
    url,
    data: form,
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 120_000,
    onUploadProgress: (event) => {
      if (onProgress && event.total) {
        onProgress(Math.round((event.loaded * 100) / event.total))
      }
    }
  })
}

/**
 * 触发一次文件下载（例如审计 CSV 导出）。
 *
 * 为什么不能直接 `<a href="/api/v1/audits/export">`：需要带 Authorization 头，浏览器原生跳转带不上。
 * 所以走 axios 取 blob，再在前端造一个临时链接点一下。
 *
 * 这里不经过 `api()` 拆信封，因此要自己处理失败路径：出错的响应体是 **Blob**，
 * 拿不到 `message`，不还原的话用户只会看到一句「请求失败（HTTP 409）」，而真正的原因
 * （例如「导出条数超过上限，请缩小筛选范围」）就丢了。
 */
export async function downloadCsv(
  url: string,
  params?: Record<string, unknown>,
  fallbackName = 'export.csv'
): Promise<void> {
  const response = await http
    .request<Blob>({ method: 'GET', url, params, responseType: 'blob' })
    .catch(async (error: AxiosError<unknown>) => {
      throw await toApiError(error)
    })

  const blob = response.data
  if (blob.type.includes('json')) {
    // 后端明确回了 JSON：说明不是文件，而是一条错误信封（200 但 content-type 是 json 的情况）
    const text = await blob.text()
    const body = parseEnvelope(text)
    throw new ApiError(body?.code ?? 'EXPORT_FAILED', body?.message ?? '导出失败', response.status, body?.details ?? null)
  }

  const objectUrl = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = objectUrl
  link.download = filenameOf(response.headers['content-disposition']) ?? fallbackName
  document.body.appendChild(link)
  link.click()
  link.remove()
  // 必须显式回收：blob URL 会一直占着内存直到页面卸载
  URL.revokeObjectURL(objectUrl)
}

/** 把 blob 形态的错误响应还原成正常的 ApiError，保住后端的 message 与 details。 */
async function toApiError(error: AxiosError<unknown>): Promise<ApiError> {
  const status = error.response?.status ?? 0
  const data = error.response?.data
  if (data instanceof Blob && data.type.includes('json')) {
    const body = parseEnvelope(await data.text())
    if (body) {
      return new ApiError(body.code ?? `HTTP_${status}`, body.message ?? '导出失败', status, body.details ?? null)
    }
  }
  const body = data as ApiEnvelope<unknown> | undefined
  return new ApiError(
    body?.code ?? (status === 0 ? 'NETWORK_ERROR' : `HTTP_${status}`),
    body?.message ?? describeTransport(error, status),
    status,
    body?.details ?? null
  )
}

function parseEnvelope(text: string): ApiEnvelope<unknown> | null {
  try {
    const parsed = JSON.parse(text) as ApiEnvelope<unknown>
    return parsed && typeof parsed === 'object' ? parsed : null
  } catch {
    return null
  }
}

/** 从 `attachment; filename="audit-20260910-1530Z.csv"` 里取文件名。 */
function filenameOf(disposition?: string): string | null {
  if (!disposition) return null
  const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition)
  return match ? decodeURIComponent(match[1]) : null
}

/**
 * 统一的错误提示。
 *
 * 优先展示 details 里的结构化原因（例如解析诊断的字段与缺失项、PATH 冲突的占用方），
 * 只弹一句 message 的话用户还得自己去翻后端日志。
 */
export function notifyError(error: unknown): void {
  if (error instanceof ApiError) {
    const detail = error.details ? truncate(JSON.stringify(error.details), 200) : ''
    ElMessage.error(detail ? `${error.message}（${detail}）` : error.message)
    return
  }
  ElMessage.error(error instanceof Error ? error.message : String(error))
}

function truncate(text: string, max: number): string {
  return text.length > max ? `${text.slice(0, max)}…` : text
}