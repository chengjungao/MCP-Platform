/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** 控制面 API 前缀。开发与生产都用同源相对路径，跨域交给代理/nginx。 */
  readonly VITE_API_BASE?: string
  readonly VITE_APP_TITLE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}