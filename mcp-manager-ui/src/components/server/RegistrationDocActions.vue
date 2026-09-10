<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { notifyError } from '@/api/http'
import * as registrationApi from '@/api/registration'
import type { Diagnostic, DiffReport } from '@/api/types'
import { useAuthStore } from '@/stores/auth'

/**
 * 单份注册文档的解析管理动作：诊断 / 原文 / 重新解析，以及对应的三个弹出框。
 *
 * 注册与解析是 MCP Server 管理的二级功能：Server 详情页「REST 服务」tab 按 REST 服务条目挂载本组件，
 * 全局列表页（/registrations，URL 直达）也复用它，避免两处各养一套一模一样的对话框。
 */
const props = defineProps<{ registrationId: number; name?: string }>()
const emit = defineEmits<{ changed: [] }>()

const auth = useAuthStore()

// 重新解析对话框的文件 input
const reimportFileInput = ref<HTMLInputElement | null>(null)

// ---- 诊断 / 原文 / 重新解析 ----
const diagnosticDialog = reactive({ visible: false, items: [] as Diagnostic[], loading: false, name: '' })
const rawDialog = reactive({ visible: false, content: '', name: '', sha256: '', loading: false })
const reimportDialog = reactive({ visible: false, id: 0, report: null as DiffReport | null, saving: false })

function displayName(): string {
  return props.name ?? `#${props.registrationId}`
}

function openDiagnostics(): void {
  diagnosticDialog.name = displayName()
  diagnosticDialog.items = []
  diagnosticDialog.loading = true
  diagnosticDialog.visible = true
  registrationApi
    .view(props.registrationId)
    .then((view) => {
      diagnosticDialog.items = view.diagnostics ?? []
    })
    .catch((error) => {
      notifyError(error)
      diagnosticDialog.visible = false
    })
    .finally(() => {
      diagnosticDialog.loading = false
    })
}

async function openRaw(): Promise<void> {
  rawDialog.name = displayName()
  rawDialog.sha256 = ''
  rawDialog.content = ''
  rawDialog.visible = true
  rawDialog.loading = true
  try {
    const view = await registrationApi.view(props.registrationId)
    rawDialog.sha256 = view.rawDocSha256 ?? ''
    rawDialog.content = await registrationApi.rawDoc(props.registrationId)
  } catch (error) {
    notifyError(error)
    rawDialog.visible = false
  } finally {
    rawDialog.loading = false
  }
}

function openReimport(): void {
  reimportDialog.id = props.registrationId
  reimportDialog.report = null
  reimportDialog.visible = true
}

function chooseReimportFile(): void {
  reimportFileInput.value?.click()
}

function pickReimportFile(event: Event): void {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  target.value = ''
  if (!file) return
  void submitReimport(file)
}

/**
 * 重新解析后必须把「挂起的覆盖」显式摊开给用户看。
 *
 * 静默丢弃覆盖等于悄悄改掉线上 tool 的名字与描述；只提示「有变化」而不列出条目，
 * 用户也没法判断要不要重做覆盖。
 */
async function submitReimport(file: File): Promise<void> {
  reimportDialog.saving = true
  try {
    reimportDialog.report = await registrationApi.reimportUpload(reimportDialog.id, file)
    emit('changed')
    const report = reimportDialog.report
    if (report.suspendedOverlays.length > 0) {
      ElMessage.warning(`${report.suspendedOverlays.length} 条覆盖因锚点失效进入挂起区`)
    } else {
      ElMessage.success('重新解析完成')
    }
  } catch (error) {
    notifyError(error)
  } finally {
    reimportDialog.saving = false
  }
}

defineExpose({ openDiagnostics })
</script>

<template>
  <span class="doc-actions">
    <el-button link type="primary" size="small" @click="openDiagnostics">诊断</el-button>
    <el-button link type="primary" size="small" @click="openRaw">原文</el-button>
    <el-button
      v-if="auth.can('registration:reimport')"
      link
      type="warning"
      size="small"
      @click="openReimport"
    >
      重新解析
    </el-button>
  </span>

  <!-- 诊断 -->
  <el-dialog v-model="diagnosticDialog.visible" :title="`解析诊断 · ${diagnosticDialog.name}`" width="820px" append-to-body>
    <div v-loading="diagnosticDialog.loading">
      <el-table :data="diagnosticDialog.items" border size="small" max-height="460">
        <el-table-column label="级别" width="80">
          <template #default="{ row }: { row: Diagnostic }">
            <el-tag size="small" :type="row.level === 'ERROR' ? 'danger' : 'warning'">{{ row.level }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="pointer" label="位置（JSON Pointer）" min-width="220" show-overflow-tooltip>
          <template #default="{ row }: { row: Diagnostic }">
            <span class="mono">{{ row.pointer || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="field" label="字段" width="130" show-overflow-tooltip />
        <el-table-column prop="message" label="说明" min-width="260" show-overflow-tooltip />
        <template #empty><el-empty description="没有诊断信息" /></template>
      </el-table>
      <p class="muted note">
        ERROR 会阻断该接口生成 tool；WARN 只提示不阻断（例如 path 参数被文档标成可选，已强制按必填处理）。
      </p>
    </div>
  </el-dialog>

  <!-- 原始文档 -->
  <el-dialog v-model="rawDialog.visible" :title="`原始文档（只读）· ${rawDialog.name}`" width="820px" top="6vh" append-to-body>
    <div v-loading="rawDialog.loading">
      <p class="muted">
        sha256：<span class="mono">{{ rawDialog.sha256 || '—' }}</span>
        （与注册记录比对可确认文档未被篡改）
      </p>
      <pre class="raw-block">{{ rawDialog.content }}</pre>
    </div>
  </el-dialog>

  <!-- 重新解析 -->
  <el-dialog v-model="reimportDialog.visible" title="重新解析并生成差异报告" width="620px" append-to-body>
    <input ref="reimportFileInput" type="file" accept=".json,.yaml,.yml" hidden @change="pickReimportFile" />
    <p class="muted">
      选择新版文档后立刻解析。原文只读，覆盖项通过锚点 <span class="mono">METHOD path</span> 对齐；
      锚点失效的覆盖不会静默丢弃，而是进入挂起区等你处理。
    </p>
    <el-button type="primary" :loading="reimportDialog.saving" @click="chooseReimportFile">
      选择新版文档
    </el-button>

    <template v-if="reimportDialog.report">
      <el-divider />
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="新文档版本">v{{ reimportDialog.report.newDocVersion }}</el-descriptions-item>
        <el-descriptions-item label="新增接口">
          <span v-if="!reimportDialog.report.added.length" class="muted">无</span>
          <div v-for="item in reimportDialog.report.added" :key="item" class="mono">{{ item }}</div>
        </el-descriptions-item>
        <el-descriptions-item label="删除接口">
          <span v-if="!reimportDialog.report.removed.length" class="muted">无</span>
          <div v-for="item in reimportDialog.report.removed" :key="item" class="mono">{{ item }}</div>
        </el-descriptions-item>
        <el-descriptions-item label="签名变化">
          <span v-if="!reimportDialog.report.changed.length" class="muted">无</span>
          <div v-for="item in reimportDialog.report.changed" :key="item" class="mono">{{ item }}</div>
        </el-descriptions-item>
        <el-descriptions-item label="保留的覆盖">
          {{ reimportDialog.report.preservedOverlays.length }} 条
        </el-descriptions-item>
        <el-descriptions-item label="挂起的覆盖">
          <el-tag v-if="reimportDialog.report.suspendedOverlays.length" type="danger" size="small">
            {{ reimportDialog.report.suspendedOverlays.length }} 条需人工处理
          </el-tag>
          <span v-else class="muted">无</span>
          <div v-for="item in reimportDialog.report.suspendedOverlays" :key="item" class="mono">{{ item }}</div>
        </el-descriptions-item>
      </el-descriptions>
    </template>
  </el-dialog>
</template>

<style scoped>
.note {
  margin: 12px 0 0;
  font-size: 12px;
}

.doc-actions {
  display: inline-flex;
  align-items: center;
}

.doc-actions .el-button + .el-button {
  margin-left: 8px;
}
</style>
