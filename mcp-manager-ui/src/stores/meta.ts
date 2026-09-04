import { defineStore } from 'pinia'
import { ref } from 'vue'

import { meta as fetchMeta } from '@/api/auth'
import type { PlatformMeta } from '@/api/types'

/**
 * 平台元信息（GET /api/v1/meta）。
 *
 * 协议版本、PATH 命名正则这类常量由后端下发，前端不抄一份：
 * 决策 D1（Modern-only，仅 2026-07-28）一旦调整，UI 上的提示要跟着变，
 * 两处各写一份的话早晚有一天互相打脸。
 */
export const useMetaStore = defineStore('meta', () => {
  const meta = ref<PlatformMeta | null>(null)
  const loading = ref(false)

  async function load(force = false): Promise<PlatformMeta | null> {
    if (meta.value && !force) return meta.value
    if (loading.value) return meta.value
    loading.value = true
    try {
      meta.value = await fetchMeta()
      return meta.value
    } catch {
      // 元信息拿不到不该挡住整个控制台：页面退化为不显示协议版本提示
      return null
    } finally {
      loading.value = false
    }
  }

  return { meta, loading, load }
})