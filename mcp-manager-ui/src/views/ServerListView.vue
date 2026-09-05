<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { notifyError } from '@/api/http'
import * as orgApi from '@/api/org'
import * as serverApi from '@/api/server'
import type { DepartmentView, ServerView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useMetaStore } from '@/stores/meta'
import { copyText } from '@/utils/clipboard'
import { formatDateTime, labelOf, SERVER_STATUS_LABEL, statusTag } from '@/utils/format'

const router = useRouter()
const auth = useAuthStore()
const metaStore = useMetaStore()

const loading = ref(false)
const rows = ref<ServerView[]>([])
const total = ref(0)
const query = reactive({ page: 0, size: 20 })
const departments = ref<DepartmentView[]>([])

// 新建 Server 对话框
const createDialog = reactive({
  visible: false,
  name: '',
  title: '',
  description: '',
  pathSegment: '',
  deptId: undefined as number | undefined,
  saving: false
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await serverApi.page({ page: query.page, size: query.size })
    rows.value = result.items
    total.value = result.total
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

function onPageChange(page: number): void {
  query.page = page - 1
  void load()
}

function open(row: ServerView): void {
  void router.push(`/servers/${row.id}`)
}

async function copy(endpoint?: string): Promise<void> {
  if (!endpoint) return
  if (await copyText(endpoint)) {
    ElMessage.success('端点已复制')
  } else {
    ElMessage.error('复制失败，请手动选择文本')
  }
}

function openCreate(): void {
  createDialog.name = ''
  createDialog.title = ''
  createDialog.description = ''
  createDialog.pathSegment = ''
  createDialog.deptId = undefined
  createDialog.visible = true
}

async function submitCreate(): Promise<void> {
  if (!createDialog.name.trim()) {
    ElMessage.warning('请填写 Server 名称')
    return
  }
  createDialog.saving = true
  try {
    const created = await serverApi.create({
      name: createDialog.name.trim(),
      title: createDialog.title.trim() || undefined,
      description: createDialog.description.trim() || undefined,
      pathSegment: createDialog.pathSegment.trim() || undefined,
      deptId: createDialog.deptId
    })
    createDialog.visible = false
    ElMessage.success('已创建空 Server，跳转到详情页注册文档')
    void router.push(`/servers/${created.id}`)
  } catch (error) {
    notifyError(error)
  } finally {
    createDialog.saving = false
  }
}

onMounted(async () => {
  void metaStore.load()
  await load()
  if (auth.can('dept:read')) {
    try {
      departments.value = await orgApi.departments()
    } catch {
      departments.value = []
    }
  }
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>MCP Server</h2>
        <p class="subtitle">
          先创建 MCP Server（维护基础信息与 PATH 末段），再在该 Server 下注册多份 Swagger 文档（多 REST 服务）。
          每份 Swagger 有独立上游与鉴权，tool 自动带服务前缀。
        </p>
      </div>
      <div class="toolbar">
        <el-button @click="load">刷新</el-button>
        <el-button
          v-if="auth.can('server:write')"
          type="primary"
          @click="openCreate"
        >
          新建 Server
        </el-button>
      </div>
    </div>

    <el-table v-loading="loading" :data="rows" border stripe @row-dblclick="open">
      <el-table-column label="名称" min-width="180">
        <template #default="{ row }">
          <el-link type="primary" :underline="false" @click="open(row)">{{ row.name }}</el-link>
          <div v-if="row.title" class="muted small">{{ row.title }}</div>
        </template>
      </el-table-column>
      <el-table-column label="PATH 末段" width="140">
        <template #default="{ row }">
          <span class="mono">{{ row.pathSegment }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">
            {{ labelOf(SERVER_STATUS_LABEL, row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="Tool" width="100" align="center">
        <template #default="{ row }">
          <span :class="{ muted: row.enabledToolCount < row.toolCount }">
            {{ row.enabledToolCount }} / {{ row.toolCount }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="上游服务" width="100" align="center">
        <template #default="{ row }">
          <span :class="{ muted: !row.upstreams?.length }">{{ row.upstreams?.length ?? 0 }}</span>
        </template>
      </el-table-column>
      <el-table-column label="端点" min-width="280">
        <template #default="{ row }">
          <template v-if="row.endpointPreview">
            <span class="mono small">{{ row.endpointPreview }}</span>
            <el-button link type="primary" size="small" @click="copy(row.endpointPreview)">
              复制
            </el-button>
          </template>
          <span v-else class="muted small">未发布，无端点</span>
        </template>
      </el-table-column>
      <el-table-column prop="deptName" label="归属部门" width="130">
        <template #default="{ row }">{{ row.deptName || '—' }}</template>
      </el-table-column>
      <el-table-column label="更新时间" width="170">
        <template #default="{ row }">{{ formatDateTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="open(row)">详情</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="还没有 Server，先点「新建 Server」创建一个" />
      </template>
    </el-table>

    <el-pagination
      class="pager"
      layout="total, prev, pager, next"
      :total="total"
      :page-size="query.size"
      :current-page="query.page + 1"
      @current-change="onPageChange"
    />

    <!-- 新建 Server -->
    <el-dialog v-model="createDialog.visible" title="新建 MCP Server" width="560px">
      <el-alert type="info" :closable="false" show-icon class="create-tip">
        <template #title>先建基础信息</template>
        <template #default>
          创建后得到一个空 Server（无 tool、无上游）。接下来在 Server 详情页的「上游服务」tab 里注册 Swagger 文档，
          每份文档 = 一个 REST 服务的 tool 集合，自动挂到本 Server 下。
        </template>
      </el-alert>
      <el-form label-width="100px">
        <el-form-item label="Server 名称" required>
          <el-input v-model="createDialog.name" placeholder="如 order-domain（业务域名）" maxlength="128" show-word-limit />
        </el-form-item>
        <el-form-item label="展示名">
          <el-input v-model="createDialog.title" placeholder="如 订单域聚合服务" maxlength="128" show-word-limit />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="createDialog.description" type="textarea" :rows="2" maxlength="2000" show-word-limit />
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
          <el-input v-model="createDialog.pathSegment" placeholder="留空则由名称推导；集群内唯一" maxlength="64">
            <template #prepend>{{ metaStore.meta?.defaultPathPrefix ?? '/mcp' }}</template>
          </el-input>
          <div class="hint muted">PATH 末段是稳定契约，改名等于让已配置的 MCP Client 断链。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="createDialog.saving" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.create-tip {
  margin-bottom: 16px;
}

.full {
  width: 100%;
}

.hint {
  font-size: 12px;
  line-height: 1.6;
}
</style>