<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { notifyError } from '@/api/http'
import * as serverApi from '@/api/server'
import type { JsonSchema, ToolOverlayRequest, ToolView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useMetaStore } from '@/stores/meta'
import { labelOf, OVERLAY_STATUS_LABEL, statusTag } from '@/utils/format'
import { changed } from '@/utils/form'

/** 布尔覆盖用三态：KEEP 对应后端的 null（不修改），而不是「false」。 */
type TriState = 'KEEP' | 'ON' | 'OFF'

const props = defineProps<{ serverId: number }>()
const emit = defineEmits<{ changed: [] }>()

const auth = useAuthStore()
const metaStore = useMetaStore()

const tools = ref<ToolView[]>([])
const selection = ref<ToolView[]>([])
const loading = ref(false)
const canWrite = computed(() => auth.can('tool:write'))

const dialog = reactive({
  visible: false,
  saving: false,
  toolId: 0,
  anchor: '',
  baseName: '',
  baseSummary: '',
  name: '',
  description: '',
  schemaText: '',
  streamFormat: '',
  enabled: 'ON' as TriState,
  streaming: 'OFF' as TriState
})
/** 打开对话框时的值，用来判断哪些字段真的被改过。 */
const initial = reactive({
  name: '',
  description: '',
  schemaText: '',
  streamFormat: '',
  enabled: 'ON' as TriState,
  streaming: 'OFF' as TriState
})

const toolNamePattern = computed<RegExp | null>(() => {
  const pattern = metaStore.meta?.toolNamePattern
  if (!pattern) return null
  try {
    return new RegExp(pattern)
  } catch {
    return null
  }
})

const suspendedCount = computed(() => tools.value.filter((tool) => tool.overlayStatus === 'SUSPENDED').length)
const streamingCount = computed(() => tools.value.filter((tool) => tool.streaming).length)

async function load(): Promise<void> {
  loading.value = true
  try {
    tools.value = await serverApi.tools(props.serverId)
    selection.value = []
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

function onSelectionChange(rows: ToolView[]): void {
  selection.value = rows
}

function openOverlay(tool: ToolView): void {
  dialog.toolId = tool.id
  dialog.anchor = tool.anchor
  dialog.baseName = tool.baseName
  dialog.baseSummary = tool.baseSummary ?? ''
  dialog.name = tool.effectiveName
  dialog.description = tool.effectiveDescription ?? ''
  dialog.schemaText = tool.effectiveInputSchema ? JSON.stringify(tool.effectiveInputSchema, null, 2) : ''
  dialog.streamFormat = tool.streamFormat ?? ''
  dialog.enabled = tool.enabled ? 'ON' : 'OFF'
  dialog.streaming = tool.streaming ? 'ON' : 'OFF'
  Object.assign(initial, {
    name: dialog.name,
    description: dialog.description,
    schemaText: dialog.schemaText,
    streamFormat: dialog.streamFormat,
    enabled: dialog.enabled,
    streaming: dialog.streaming
  })
  dialog.visible = true
}

function toBoolean(value: TriState): boolean | undefined {
  return value === 'KEEP' ? undefined : value === 'ON'
}

async function submitOverlay(): Promise<void> {
  const name = dialog.name.trim()
  const pattern = toolNamePattern.value
  if (pattern && !pattern.test(name)) {
    ElMessage.warning(`tool 名不满足规则 ${pattern.source}`)
    return
  }

  const schemaText = dialog.schemaText.trim()
  let inputSchema: JsonSchema | undefined
  if (schemaText !== initial.schemaText) {
    if (schemaText === '') {
      // 后端把「空对象」解释为撤销 inputSchema 覆盖；传 null 的含义是不修改，达不到清空的目的
      inputSchema = {}
    } else {
      let parsed: unknown
      try {
        parsed = JSON.parse(schemaText)
      } catch (error) {
        ElMessage.warning(`inputSchema 不是合法 JSON：${(error as Error).message}`)
        return
      }
      if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
        ElMessage.warning('inputSchema 必须是一个 JSON Schema 对象')
        return
      }
      inputSchema = parsed as JsonSchema
    }
  }

  const request: ToolOverlayRequest = {
    name: changed(name, initial.name),
    description: changed(dialog.description.trim(), initial.description),
    inputSchema,
    streamFormat: changed(dialog.streamFormat.trim(), initial.streamFormat),
    enabled: dialog.enabled === initial.enabled ? undefined : toBoolean(dialog.enabled),
    streaming: dialog.streaming === initial.streaming ? undefined : toBoolean(dialog.streaming)
  }
  // 后端即使一个字段都没改也会把 overlayVersion 加一，所以空提交在前端就拦掉
  const touched = Object.values(request).some((value) => value !== undefined)
  if (!touched) {
    ElMessage.info('没有任何改动')
    return
  }

  dialog.saving = true
  try {
    const updated = await serverApi.updateToolOverlay(props.serverId, dialog.toolId, request)
    tools.value = tools.value.map((tool) => (tool.id === updated.id ? updated : tool))
    dialog.visible = false
    emit('changed')
  } catch (error) {
    notifyError(error)
  } finally {
    dialog.saving = false
  }
}

async function resetOverlay(tool: ToolView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `将清除 ${tool.effectiveName} 的全部覆盖，回落到原始文档解析出的值。此操作会记入审计。`,
      '恢复默认',
      { type: 'warning', confirmButtonText: '恢复默认', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const updated = await serverApi.resetToolOverlay(props.serverId, tool.id)
    tools.value = tools.value.map((item) => (item.id === updated.id ? updated : item))
    emit('changed')
  } catch (error) {
    notifyError(error)
  }
}

async function toggleEnabled(tool: ToolView, value: string | number | boolean): Promise<void> {
  // el-switch 的 change 载荷类型是 boolean | string | number（它支持自定义 active-value），
  // 写成 (value: boolean) 会在 strictFunctionTypes 下因参数逆变而编译不过
  const enabled = value === true
  try {
    tools.value = await serverApi.batchToggle(props.serverId, { toolIds: [tool.id], enabled })
    emit('changed')
  } catch (error) {
    notifyError(error)
    // 开关是按受控组件写的，失败后必须重新拉一次，否则界面停在一个没保存成功的状态上
    await load()
  }
}

async function batchToggle(enabled: boolean): Promise<void> {
  const ids = selection.value.map((tool) => tool.id)
  if (ids.length === 0) {
    ElMessage.warning('请先勾选要操作的 tool')
    return
  }
  try {
    tools.value = await serverApi.batchToggle(props.serverId, { toolIds: ids, enabled })
    selection.value = []
    emit('changed')
    ElMessage.success(`已${enabled ? '启用' : '停用'} ${ids.length} 个 tool，重新发布后生效`)
  } catch (error) {
    notifyError(error)
  }
}

onMounted(() => {
  void metaStore.load()
  void load()
})
</script>

<template>
  <div v-loading="loading">
    <el-alert type="info" :closable="false" show-icon class="tip">
      <template #title>基座值只读，改动都写在覆盖层；覆盖靠锚点与新版文档对齐</template>
      <template #default>
        锚点是 <span class="mono">METHOD path</span>，它来自原始文档、不可覆盖。
        重新解析后锚点仍在的覆盖会被保留，锚点失效的进入挂起区（下表标红）而不是被静默丢弃——
        静默丢弃等于悄悄改掉线上 tool 的名字与描述。method / path / anchor 三个字段本身不可覆盖（BR-2）。
      </template>
    </el-alert>

    <el-alert v-if="suspendedCount > 0" type="error" :closable="false" show-icon class="tip">
      <template #title>
        有 {{ suspendedCount }} 个 tool 的覆盖处于挂起状态：原始文档里已经找不到对应锚点
      </template>
      <template #default>
        挂起的覆盖不会进入生效模型，也不会被发布。请确认是接口被删除还是 path 改了，
        然后要么删掉覆盖，要么在新的 tool 上重做一遍。
      </template>
    </el-alert>

    <el-alert v-if="streamingCount > 0" type="warning" :closable="false" show-icon class="tip">
      <template #title>有 {{ streamingCount }} 个 tool 被标为流式，当前版本既不会列出也无法调用</template>
      <template #default>
        流式端点属于 P1（BR-5）。Executor 会把它们从 tools/list 中剔除，直接调用返回 501。
        与其让客户端拿到一个必然失败的 tool，不如现在就把流式标记取消或把该操作停用。
      </template>
    </el-alert>

    <div class="toolbar">
      <el-button type="success" plain :disabled="!canWrite" @click="batchToggle(true)">批量启用</el-button>
      <el-button type="warning" plain :disabled="!canWrite" @click="batchToggle(false)">批量停用</el-button>
      <span class="muted">已选 {{ selection.length }} 项</span>
      <el-button class="right" @click="load">刷新</el-button>
    </div>

    <el-table :data="tools" border stripe size="small" @selection-change="onSelectionChange">
      <el-table-column type="selection" width="42" :selectable="() => canWrite" />
      <el-table-column label="生效名" min-width="200">
        <template #default="{ row }">
          <span class="mono">{{ row.effectiveName }}</span>
          <div v-if="row.baseName !== row.effectiveName" class="muted small">
            基座名 <span class="mono">{{ row.baseName }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="REST 操作" min-width="220">
        <template #default="{ row }">
          <el-tag size="small" effect="plain">{{ row.method }}</el-tag>
          <span class="mono small path">{{ row.path }}</span>
        </template>
      </el-table-column>
      <el-table-column label="描述" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ row.effectiveDescription || row.baseSummary || '—' }}</template>
      </el-table-column>
      <el-table-column label="覆盖" width="130">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.overlayStatus)" size="small">
            {{ labelOf(OVERLAY_STATUS_LABEL, row.overlayStatus) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="标记" width="120">
        <template #default="{ row }">
          <el-tag v-if="row.streaming" type="warning" size="small" effect="plain">流式</el-tag>
          <el-tag v-if="row.idempotent" type="info" size="small" effect="plain">幂等</el-tag>
          <el-tag v-if="row.requestBodyRequired" type="info" size="small" effect="plain">需请求体</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80" align="center">
        <template #default="{ row }">
          <el-switch
            :model-value="row.enabled"
            :disabled="!canWrite"
            @change="(value: boolean | string | number) => toggleEnabled(row, value)"
          />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openOverlay(row)">编辑覆盖</el-button>
          <el-button
            link
            type="danger"
            size="small"
            :disabled="!canWrite || !row.hasOverlay"
            @click="resetOverlay(row)"
          >
            恢复默认
          </el-button>
        </template>
      </el-table-column>
      <template #empty><el-empty description="没有解析出任何操作" /></template>
    </el-table>

    <el-dialog v-model="dialog.visible" title="编辑 Tool 覆盖" width="760px" top="6vh">
      <el-descriptions :column="1" border size="small" class="dialog-head">
        <el-descriptions-item label="锚点（只读）"><span class="mono">{{ dialog.anchor }}</span></el-descriptions-item>
        <el-descriptions-item label="基座名（只读）"><span class="mono">{{ dialog.baseName }}</span></el-descriptions-item>
        <el-descriptions-item label="基座描述（只读）">
          {{ dialog.baseSummary || '—' }}
        </el-descriptions-item>
      </el-descriptions>

      <el-alert type="info" :closable="false" show-icon class="tip">
        <template #title>只有被改动的字段才会提交</template>
        <template #default>
          后端把 <span class="mono">null</span> 当作「不修改」、把空串当作「撤销该字段的覆盖」。
          如果把回显出来的生效值原样提交，就会凭空造出一条与基座完全相同的覆盖，
          差异页上会多出一堆没有意义的改动，所以这里只提交你真正动过的字段。
        </template>
      </el-alert>

      <el-form label-width="120px" @submit.prevent="submitOverlay">
        <el-form-item label="tool 名">
          <el-input v-model="dialog.name" maxlength="64" :disabled="!canWrite" />
          <div class="hint muted">
            规则 <span class="mono">{{ toolNamePattern?.source ?? '由后端校验' }}</span>；
            同一 Server 内生效名不能重复，清空表示撤销覆盖、回落基座名。
          </div>
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="dialog.description"
            type="textarea"
            :rows="3"
            maxlength="4000"
            show-word-limit
            :disabled="!canWrite"
          />
          <div class="hint muted">
            这段文字会直接进模型上下文，写清「什么时候该调它、参数从哪来」比复述接口名有用得多。
          </div>
        </el-form-item>
        <el-form-item label="inputSchema">
          <el-input
            v-model="dialog.schemaText"
            type="textarea"
            :rows="10"
            class="mono"
            spellcheck="false"
            :disabled="!canWrite"
          />
          <div class="hint muted">
            JSON Schema 对象（方言由后端 meta 下发）。清空并提交表示撤销覆盖、回落解析出的 schema。
            注意：把某个参数从 required 里去掉不会让上游接受缺参请求，只会让模型少传一个参数然后拿到 400。
          </div>
        </el-form-item>
        <el-form-item label="启用">
          <el-radio-group v-model="dialog.enabled" :disabled="!canWrite">
            <el-radio-button value="KEEP">不修改</el-radio-button>
            <el-radio-button value="ON">启用</el-radio-button>
            <el-radio-button value="OFF">停用</el-radio-button>
          </el-radio-group>
          <div class="hint muted">停用的 tool 不进入发布快照，客户端 tools/list 里看不到它。</div>
        </el-form-item>
        <el-form-item label="流式">
          <el-radio-group v-model="dialog.streaming" :disabled="!canWrite">
            <el-radio-button value="KEEP">不修改</el-radio-button>
            <el-radio-button value="ON">是</el-radio-button>
            <el-radio-button value="OFF">否</el-radio-button>
          </el-radio-group>
          <div class="hint muted">当前版本不支持流式调用，标为「是」等于把这个 tool 藏起来。</div>
        </el-form-item>
        <el-form-item label="流式格式">
          <el-input v-model="dialog.streamFormat" maxlength="16" placeholder="sse / ndjson" :disabled="!canWrite" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" :disabled="!canWrite" @click="submitOverlay">
          保存覆盖
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tip {
  margin-bottom: 12px;
}

.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}

.toolbar .right {
  margin-left: auto;
}

.path {
  margin-left: 6px;
}

.dialog-head {
  margin-bottom: 12px;
}

.hint {
  font-size: 12px;
  line-height: 1.6;
}
</style>