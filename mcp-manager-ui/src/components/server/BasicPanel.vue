<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'

import { notifyError } from '@/api/http'
import * as serverApi from '@/api/server'
import type { ServerView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useMetaStore } from '@/stores/meta'
import { changed } from '@/utils/form'

const props = defineProps<{ serverId: number; server: ServerView }>()
const emit = defineEmits<{ saved: [ServerView] }>()

const auth = useAuthStore()
const metaStore = useMetaStore()
const saving = ref(false)

const form = reactive({
  name: '',
  title: '',
  description: '',
  pathSegment: '',
  listTtlMs: 0
})
/** 打开时的生效值，用于判断哪些字段真的被改过。 */
const initial = reactive({ ...form })

watch(
  () => props.server,
  (value) => {
    form.name = value.name
    form.title = value.title ?? ''
    form.description = value.description ?? ''
    form.pathSegment = value.pathSegment
    form.listTtlMs = value.listTtlMs
    Object.assign(initial, form)
  },
  { immediate: true }
)

/**
 * 末段规则由后端 `/api/v1/meta` 下发，不在前端抄一份正则。
 * meta 还没到时不做本地校验，交给后端兜——本地校验只是省一次往返，不是安全边界。
 */
const segmentPattern = computed<RegExp | null>(() => {
  const pattern = metaStore.meta?.pathSegmentPattern
  if (!pattern) return null
  try {
    return new RegExp(pattern)
  } catch {
    return null
  }
})

const segmentError = computed(() => {
  const value = form.pathSegment.trim()
  if (!value) return ''
  const pattern = segmentPattern.value
  if (pattern && !pattern.test(value)) {
    return `不满足规则 ${pattern.source}：只能用小写字母、数字、连字符与下划线，且首尾必须是字母或数字`
  }
  return ''
})

const segmentHint = computed(() => {
  const rule =
    'BR-3：前缀由集群决定，用户只能自定义末段；末段在集群内唯一，且是稳定契约——' +
    '改名等于让已经配置好的 MCP Client 断链，所以后端会做唯一性校验并单独记一条审计。'
  return form.pathSegment.trim() ? rule : `清空即撤销覆盖，回落文档推导出的默认末段。${rule}`
})

async function submit(): Promise<void> {
  saving.value = true
  try {
    emit(
      'saved',
      await serverApi.update(props.serverId, {
        name: changed(form.name.trim(), initial.name),
        title: changed(form.title.trim(), initial.title),
        description: changed(form.description.trim(), initial.description),
        pathSegment: changed(form.pathSegment.trim(), initial.pathSegment),
        listTtlMs: changed(form.listTtlMs, initial.listTtlMs)
      })
    )
  } catch (error) {
    notifyError(error)
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  // PATH 前缀与末段正则都从 meta 取；meta store 内部已做去重，多个面板重复调不会多发请求
  void metaStore.load()
})
</script>

<template>
  <el-form label-width="130px" class="panel-form" @submit.prevent="submit">
    <el-alert type="info" :closable="false" show-icon class="tip">
      <template #title>
        这些字段都是「覆盖」：原始文档推导出的基座值只读，改动只写进覆盖层，
        清空输入框即撤销该项覆盖、回落基座值。没动过的字段不会提交，避免产生与基座相同的空覆盖。
      </template>
    </el-alert>

    <el-form-item label="服务名">
      <el-input v-model="form.name" maxlength="128" show-word-limit :disabled="!auth.can('server:write')" />
      <div class="hint muted">MCP Server 的 name，客户端在 tools/list 之外也能看到它。</div>
    </el-form-item>

    <el-form-item label="展示名">
      <el-input v-model="form.title" maxlength="128" show-word-limit :disabled="!auth.can('server:write')" />
    </el-form-item>

    <el-form-item label="描述">
      <el-input
        v-model="form.description"
        type="textarea"
        :rows="3"
        maxlength="2000"
        show-word-limit
        :disabled="!auth.can('server:write')"
      />
    </el-form-item>

    <el-form-item label="PATH 末段" :error="segmentError || undefined">
      <el-input v-model="form.pathSegment" maxlength="64" :disabled="!auth.can('server:write')">
        <template #prepend>{{ metaStore.meta?.defaultPathPrefix ?? '/mcp' }}</template>
      </el-input>
      <div class="hint muted">{{ segmentHint }}</div>
    </el-form-item>

    <el-form-item label="tools/list TTL">
      <el-input-number
        v-model="form.listTtlMs"
        :min="0"
        :step="1000"
        :disabled="!auth.can('server:write')"
      />
      <span class="unit muted">毫秒，0 表示不缓存。随 tools/list 响应下发给客户端。</span>
    </el-form-item>

    <el-form-item>
      <el-button type="primary" :loading="saving" :disabled="!auth.can('server:write')" @click="submit">
        保存
      </el-button>
      <span v-if="!auth.can('server:write')" class="muted hint">当前账号没有 server:write 权限</span>
    </el-form-item>
  </el-form>
</template>

<style scoped>
.panel-form {
  max-width: 760px;
}

.tip {
  margin-bottom: 16px;
}

.hint {
  font-size: 12px;
  line-height: 1.6;
}

.unit {
  margin-left: 8px;
  font-size: 12px;
}
</style>