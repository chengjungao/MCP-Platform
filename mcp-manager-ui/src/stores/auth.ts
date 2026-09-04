import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import * as authApi from '@/api/auth'
import { TOKEN_KEY } from '@/api/http'
import type { MeResponse } from '@/api/types'

/**
 * 登录态与权限点。
 *
 * 令牌放 localStorage 以便刷新后免登录；`me` 不持久化——权限点是服务端事实，
 * 缓存下来只会在管理员改了角色后让用户看到「点得动但提交就 403」的界面。
 * 每次进入受保护路由时按需拉一次。
 */
export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem(TOKEN_KEY))
  const me = ref<MeResponse | null>(null)

  const isAuthenticated = computed(() => token.value !== null && token.value !== '')
  const displayName = computed(() => me.value?.displayName || me.value?.username || '未登录')
  const roles = computed<string[]>(() => (me.value ? [...me.value.roles] : []))
  const permissionSet = computed<Set<string>>(() => new Set(me.value?.permissions ?? []))

  /**
   * 是否具备某权限点。传空表示「不需要权限」。
   *
   * 注意：这只用于隐藏入口，**不是**安全边界。真正的鉴权在后端
   * `@PreAuthorize` 与 `DepartmentScope` 上，越权请求一律 403。
   */
  function can(permission?: string | null): boolean {
    if (!permission) return true
    return permissionSet.value.has(permission)
  }

  function setToken(value: string | null): void {
    token.value = value
    if (value) {
      localStorage.setItem(TOKEN_KEY, value)
    } else {
      localStorage.removeItem(TOKEN_KEY)
    }
  }

  async function login(username: string, password: string): Promise<void> {
    const result = await authApi.login({ username, password })
    setToken(result.token)
    await loadMe()
  }

  async function loadMe(): Promise<MeResponse | null> {
    if (!isAuthenticated.value) return null
    me.value = await authApi.me()
    return me.value
  }

  async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
    await authApi.changePassword({ currentPassword, newPassword })
  }

  async function logout(): Promise<void> {
    if (isAuthenticated.value) {
      try {
        await authApi.logout()
      } catch {
        // 令牌已失效时后端登出会报错，本地清理照做——用户要的是「退出」这个结果
      }
    }
    clear()
  }

  function clear(): void {
    setToken(null)
    me.value = null
  }

  return {
    token,
    me,
    isAuthenticated,
    displayName,
    roles,
    permissionSet,
    can,
    login,
    loadMe,
    changePassword,
    logout,
    clear
  }
})