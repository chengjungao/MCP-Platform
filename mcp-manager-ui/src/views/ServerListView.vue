<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { notifyError } from '@/api/http'
import * as serverApi from '@/api/server'
import type { ServerView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { copyText } from '@/utils/clipboard'
import { formatDateTime, labelOf, SERVER_STATUS_LABEL, statusTag } from '@/utils/format'

const router = useRouter()
const auth = useAuthStore()

const loading = ref(false)
const rows = ref<ServerView[]>([])
const total = ref(0)
const query = reactive({ page: 0, size: 20 })

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

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>MCP Server</h2>
        <p class="subtitle">
          每份注册文档 1:1 生成一个 Server（BR-1）。配置改动写库后不会自动生效，
          必须重新发布，Executor 才会拉到新快照。
        </p>
      </div>
      <div class="toolbar">
        <el-button :disabled="!auth.can('registration:create')" @click="router.push('/registrations')">
          去注册文档
        </el-button>
        <el-button type="primary" @click="load">刷新</el-button>
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
        <el-empty description="还没有 Server，先注册一份 OpenAPI / Swagger 文档" />
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
  </div>
</template>