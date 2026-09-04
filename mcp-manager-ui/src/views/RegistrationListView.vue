<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { notifyError } from '@/api/http'
import * as orgApi from '@/api/org'
import * as registrationApi from '@/api/registration'
import type { DepartmentView, Diagnostic, DiffReport, RegistrationView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime, labelOf, REGISTRATION_STATUS_LABEL, shortSha, statusTag } from '@/utils/format'

const router = useRouter()
const auth = useAuthStore()

const loading = ref(false)
const rows = ref<RegistrationView[]>([])
const total = ref(0)
const query = reactive({ page: 0, size: 20 })
const departments = ref<DepartmentView[]>([])

// ---- 新建注册 ----
const createDialog = reactive({
  visible: false,
  mode: 'url' as 'url' | 'file' | 'text',
  name: '',
  url: '',
  deptId: undefined as number | undefined,
  pathSegment: '',
  text: '',
  file: null as File | null,
  progress: 0,
  saving: false
})
const fileInput = ref<HTMLInputElement | null>(null)
// 重新解析对话框有自己独立的文件 input：它和「新建」对话框的生命周期不同，
// 共用一个 ref 会让「重置其中一个」误伤另一个。
const reimportFileInput = ref<HTMLInputElement | null>(null)

// ---- 诊断 / 原文 / 重新解析 ----
const diagnosticDialog = reactive({ visible: false, items: [] as Diagnostic[], name: '' })
const rawDialog = reactive({ visible: false, content: '', name: '', sha256: '', loading: false })
const reimportDialog = reactive({ visible: false, id: 0, report: null as DiffReport | null, saving: false })

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await registrationApi.page({ page: query.page, size: query.size })
    rows.value = result.items
    total.value = result.total
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  createDialog.mode = 'url'
  createDialog.name = ''
  createDialog.url = ''
  createDialog.deptId = undefined
  createDialog.pathSegment = ''
  createDialog.text = ''
  createDialog.file = null
  createDialog.progress = 0
  createDialog.visible = true
}

function pickFile(): void {
  fileInput.value?.click()
}

function onFilePicked(event: Event): void {
  const target = event.target as HTMLInputElement
  createDialog.file = target.files?.[0] ?? null
  if (createDialog.file && !createDialog.name) {
    createDialog.name = createDialog.file.name.replace(/\.(json|ya?ml)$/i, '')
  }
  // 清空 input 的 value，否则连续选同一个文件不会触发 change
  target.value = ''
}

async function submitCreate(): Promise<void> {
  if (!createDialog.name.trim()) {
    ElMessage.warning('请填写服务名')
    return
  }
  createDialog.saving = true
  createDialog.progress = 0
  try {
    const deptId = createDialog.deptId
    const pathSegment = createDialog.pathSegment.trim() || undefined
    let view: RegistrationView
    if (createDialog.mode === 'url') {
      if (!createDialog.url.trim()) {
        ElMessage.warning('请填写文档 URL')
        return
      }
      view = await registrationApi.createByUrl({
        name: createDialog.name.trim(),
        url: createDialog.url.trim(),
        deptId,
        pathSegment
      })
    } else if (createDialog.mode === 'file') {
      if (!createDialog.file) {
        ElMessage.warning('请选择 OpenAPI / Swagger 文件')
        return
      }
      view = await registrationApi.createByUpload(
        { name: createDialog.name.trim(), file: createDialog.file, deptId, pathSegment },
        (percent) => {
          createDialog.progress = percent
        }
      )
    } else {
      if (!createDialog.text.trim()) {
        ElMessage.warning('请粘贴文档内容')
        return
      }
      view = await registrationApi.createByText(createDialog.name.trim(), createDialog.text, deptId, pathSegment)
    }
    createDialog.visible = false
    await load()
    if (view.status === 'FAILED') {
      openDiagnostics(view)
      ElMessage.warning('文档已收下，但解析未通过，请查看诊断')
    } else {
      ElMessage.success(`解析成功，生成 ${view.operationCount} 个接口`)
      if (view.serverId != null) {
        void router.push(`/servers/${view.serverId}`)
      }
    }
  } catch (error) {
    notifyError(error)
  } finally {
    createDialog.saving = false
  }
}

function openDiagnostics(row: RegistrationView): void {
  diagnosticDialog.name = row.name
  diagnosticDialog.items = row.diagnostics ?? []
  diagnosticDialog.visible = true
}

async function openRaw(row: RegistrationView): Promise<void> {
  rawDialog.name = row.name
  rawDialog.sha256 = row.rawDocSha256 ?? ''
  rawDialog.content = ''
  rawDialog.visible = true
  rawDialog.loading = true
  try {
    rawDialog.content = await registrationApi.rawDoc(row.id)
  } catch (error) {
    notifyError(error)
    rawDialog.visible = false
  } finally {
    rawDialog.loading = false
  }
}

function openReimport(row: RegistrationView): void {
  reimportDialog.id = row.id
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
    await load()
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

function onPageChange(page: number): void {
  query.page = page - 1
  void load()
}

onMounted(async () => {
  await load()
  if (auth.can('dept:read')) {
    try {
      departments.value = await orgApi.departments()
    } catch {
      // 拿不到部门列表不该挡住注册页，归属留空即归到创建者所在部门
      departments.value = []
    }
  }
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>注册与解析</h2>
        <p class="subtitle">
          原始 Swagger 文档只读留存并记 sha256；改动通过「重新解析 + 覆盖」完成，不允许直接编辑原文（BR-2）
        </p>
      </div>
      <div class="toolbar">
        <el-button @click="load">刷新</el-button>
        <el-button
          v-if="auth.can('registration:create')"
          type="primary"
          @click="openCreate"
        >
          新建注册
        </el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe size="small">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="name" label="服务名" min-width="150" show-overflow-tooltip />
      <el-table-column prop="deptName" label="归属部门" width="120" show-overflow-tooltip />
      <el-table-column label="来源" width="90">
        <template #default="{ row }: { row: RegistrationView }">
          <el-tag size="small" effect="plain">{{ row.docSource === 'URL' ? 'URL' : '文件' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="swaggerVersion" label="文档规范" width="100" />
      <el-table-column prop="docVersion" label="文档版本" width="90" align="center" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }: { row: RegistrationView }">
          <el-tag size="small" :type="statusTag(row.status)">
            {{ labelOf(REGISTRATION_STATUS_LABEL, row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="operationCount" label="接口数" width="80" align="center" />
      <el-table-column label="原文 sha256" width="160">
        <template #default="{ row }: { row: RegistrationView }">
          <span class="mono muted">{{ shortSha(row.rawDocSha256) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="注册时间" width="160">
        <template #default="{ row }: { row: RegistrationView }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="290" fixed="right">
        <template #default="{ row }: { row: RegistrationView }">
          <el-button link type="primary" size="small" @click="openDiagnostics(row)">
            诊断<template v-if="row.diagnostics?.length">（{{ row.diagnostics.length }}）</template>
          </el-button>
          <el-button link type="primary" size="small" @click="openRaw(row)">原文</el-button>
          <el-button
            v-if="auth.can('registration:reimport')"
            link
            type="warning"
            size="small"
            @click="openReimport(row)"
          >
            重新解析
          </el-button>
          <el-button
            v-if="row.serverId != null && auth.can('server:read')"
            link
            type="success"
            size="small"
            @click="router.push(`/servers/${row.serverId}`)"
          >
            打开 Server
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="还没有注册过文档" />
      </template>
    </el-table>

    <div class="pager">
      <el-pagination
        layout="total, prev, pager, next, sizes"
        :total="total"
        :current-page="query.page + 1"
        :page-size="query.size"
        :page-sizes="[10, 20, 50, 100]"
        @current-change="onPageChange"
        @size-change="(size: number) => { query.size = size; query.page = 0; load() }"
      />
    </div>

    <input ref="fileInput" type="file" accept=".json,.yaml,.yml" hidden @change="onFilePicked" />

    <!-- 新建注册 -->
    <el-dialog v-model="createDialog.visible" title="注册 Swagger / OpenAPI 文档" width="640px">
      <el-tabs v-model="createDialog.mode">
        <el-tab-pane label="从 URL 抓取" name="url">
          <el-form label-width="90px">
            <el-form-item label="文档 URL">
              <el-input v-model="createDialog.url" placeholder="https://svc.internal/v3/api-docs" />
            </el-form-item>
          </el-form>
          <el-alert type="warning" :closable="false" show-icon>
            控制面默认拒绝抓取回环与内网地址（SSRF 防护）。本地联调需把
            <span class="mono">MANAGER_PARSE_ALLOW_PRIVATE_NETWORKS</span> 置为 true，生产必须为 false。
          </el-alert>
        </el-tab-pane>

        <el-tab-pane label="上传文件" name="file">
          <el-form label-width="90px">
            <el-form-item label="文档文件">
              <el-button @click="pickFile">选择文件</el-button>
              <span class="muted file-name">{{ createDialog.file?.name ?? '未选择（.json / .yaml / .yml，≤20MB）' }}</span>
            </el-form-item>
            <el-form-item v-if="createDialog.progress > 0 && createDialog.progress < 100" label="上传进度">
              <el-progress :percentage="createDialog.progress" />
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="粘贴内容" name="text">
          <el-input
            v-model="createDialog.text"
            type="textarea"
            :rows="10"
            placeholder="粘贴 OpenAPI 3.x 或 Swagger 2.0 文档"
            class="mono"
          />
        </el-tab-pane>
      </el-tabs>

      <el-form label-width="90px" class="common-form">
        <el-form-item label="服务名" required>
          <el-input v-model="createDialog.name" placeholder="如 order-service，将用于推导 tool 名与 PATH 末段" />
        </el-form-item>
        <el-form-item label="归属部门">
          <el-select v-model="createDialog.deptId" clearable placeholder="留空则归到本人所在部门" class="full">
            <el-option
              v-for="dept in departments"
              :key="dept.id"
              :label="dept.name"
              :value="dept.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="PATH 末段">
          <el-input v-model="createDialog.pathSegment" placeholder="留空则由服务名推导；只能自定义末段（BR-3）" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="createDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="createDialog.saving" @click="submitCreate">
          提交并解析
        </el-button>
      </template>
    </el-dialog>

    <!-- 诊断 -->
    <el-dialog v-model="diagnosticDialog.visible" :title="`解析诊断 · ${diagnosticDialog.name}`" width="820px">
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
    </el-dialog>

    <!-- 原始文档 -->
    <el-dialog v-model="rawDialog.visible" :title="`原始文档（只读）· ${rawDialog.name}`" width="820px" top="6vh">
      <div v-loading="rawDialog.loading">
        <p class="muted">
          sha256：<span class="mono">{{ rawDialog.sha256 || '—' }}</span>
          （与注册记录比对可确认文档未被篡改）
        </p>
        <pre class="raw-block">{{ rawDialog.content }}</pre>
      </div>
    </el-dialog>

    <!-- 重新解析 -->
    <el-dialog v-model="reimportDialog.visible" title="重新解析并生成差异报告" width="620px">
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
  </div>
</template>

<style scoped>
.file-name {
  margin-left: 8px;
  font-size: 12px;
}

.common-form {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed var(--mcp-border);
}

.full {
  width: 100%;
}

.note {
  margin: 12px 0 0;
  font-size: 12px;
}
</style>