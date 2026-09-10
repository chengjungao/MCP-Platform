import type { AuthBLocation, AuthBRequest, AuthBType, AuthBView, ExtraHeader } from '@/api/types'
import { splitCsv } from '@/utils/form'

/**
 * Auth-B 表单模型。
 *
 * <p>密钥字段（secret / password / clientSecret）永远从空开始：后端只回掩码（SEC-01），
 * 留空表示「不修改」，要清除凭据只能把 type 改回 NONE。
 *
 * <p>Server 级与 REST 服务级共用本模型——两者的字段与校验完全一致，
 * 差别只在保存时打到哪个端点。
 */
export interface AuthBForm {
  type: AuthBType
  location: AuthBLocation
  name: string
  scheme: string
  username: string
  tokenUrl: string
  clientId: string
  scope: string
  headerTemplate: string
  extraHeaders: ExtraHeader[]
  secret: string
  password: string
  clientSecret: string
}

export function emptyAuthBForm(): AuthBForm {
  return {
    type: 'NONE',
    location: 'HEADER',
    name: '',
    scheme: 'bearer',
    username: '',
    tokenUrl: '',
    clientId: '',
    scope: '',
    headerTemplate: '',
    extraHeaders: [],
    secret: '',
    password: '',
    clientSecret: ''
  }
}

/** 用后端回显（只含掩码与非敏感字段）填充表单，密钥输入框一律置空。 */
export function authBFormFromView(view?: AuthBView | null): AuthBForm {
  const form = emptyAuthBForm()
  if (!view) {
    return form
  }
  form.type = view.type ?? 'NONE'
  form.location = view.location ?? 'HEADER'
  form.name = view.name ?? ''
  form.scheme = view.scheme ?? 'bearer'
  form.username = view.username ?? ''
  form.tokenUrl = view.tokenUrl ?? ''
  form.clientId = view.clientId ?? ''
  form.scope = view.scope ?? ''
  form.headerTemplate = view.headerTemplate ?? ''
  form.extraHeaders = (view.extraHeaders ?? []).map((header) => ({ ...header }))
  return form
}

/**
 * 表单 → 后端请求。
 *
 * <p>scope 在界面上按逗号分隔录入，但 OAuth2 规范要求空格分隔，
 * 这里统一归一化——否则 `read,write` 会被上游当成一个名叫「read,write」的 scope。
 */
export function authBFormToRequest(form: AuthBForm): AuthBRequest {
  const scopes = splitCsv(form.scope)
  return {
    type: form.type,
    // location 只对 API_KEY 有意义，其它类型不传，免得库里留下一份会被误读的配置
    location: form.type === 'API_KEY' ? form.location : undefined,
    name: form.name.trim() || undefined,
    scheme: form.type === 'HTTP' ? form.scheme : undefined,
    username: form.username.trim() || undefined,
    tokenUrl: form.tokenUrl.trim() || undefined,
    clientId: form.clientId.trim() || undefined,
    scope: scopes.length > 0 ? scopes.join(' ') : undefined,
    headerTemplate: form.headerTemplate.trim() || undefined,
    extraHeaders: form.extraHeaders.filter((header) => header.name.trim()),
    secret: form.secret.trim() || undefined,
    password: form.password.trim() || undefined,
    clientSecret: form.clientSecret.trim() || undefined
  }
}

/**
 * 前端把后端的必填校验提前一遍，只为省一次往返。
 * 真正的判定仍在 AuthConfigService 里，两边不一致时以后端为准。
 */
export function validateAuthBForm(form: AuthBForm, hasStoredSecret: boolean): string | null {
  switch (form.type) {
    case 'API_KEY':
      if (!form.name.trim()) return 'API Key 方式必须填写 header 名或 query 参数名'
      if (!form.secret.trim() && !hasStoredSecret) return 'API Key 方式必须填写密钥值'
      return null
    case 'HTTP': {
      if (form.scheme !== 'bearer' && form.scheme !== 'basic') return 'scheme 只能是 bearer 或 basic'
      if (form.scheme === 'bearer' && !form.secret.trim() && !hasStoredSecret) {
        return 'bearer 方式必须填写 Token'
      }
      if (form.scheme === 'basic') {
        if (!form.username.trim()) return 'basic 方式必须填写用户名'
        if (!form.password.trim() && !hasStoredSecret) return 'basic 方式必须填写密码'
      }
      return null
    }
    case 'OAUTH2_CLIENT_CREDENTIALS':
      if (!form.tokenUrl.trim()) return 'client_credentials 方式必须填写令牌端点'
      if (!form.clientId.trim()) return 'client_credentials 方式必须填写 clientId'
      if (!form.clientSecret.trim() && !hasStoredSecret) {
        return 'client_credentials 方式必须填写 clientSecret'
      }
      return null
    case 'CUSTOM_HEADER':
      if (!form.headerTemplate.trim()) return '自定义 Header 方式必须填写 Header 模板'
      return null
    default:
      return null
  }
}
