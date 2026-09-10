<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { notifyError } from '@/api/http'
import * as registrationApi from '@/api/registration'
import type { RegistrationView } from '@/api/types'
import RegistrationDocActions from '@/components/server/RegistrationDocActions.vue'
import { useAuthStore } from '@/stores/auth'
import { formatDateTime, labelOf, REGISTRATION_STATUS_LABEL, shortSha, statusTag } from '@/utils/format'

/**
 * 全局注册列表（URL 直达的排查视图，已不在左侧菜单）。
 * 注册与解析是 MCP Server 管理的二级功能：注册入口在各 Server 详情页「REST 服务」tab，
 * 诊断/原文/重新解析也在那里就地弹出。本页只保留跨 Server 的全局视角。
 */
const router = useRouter()
const auth = useAuthStore()

const loading = ref(false)
const rows = ref<RegistrationView[]>([])
const total = ref(0)
const query = reactive({ page: 0, size: 20 })

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

function onPageChange(page: number): void {
  query.page = page - 1
  void load()
}

onMounted(async () => {
  await load()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>注册与解析</h2>
        <p class="subtitle">
          所有已注册文档的全局列表（排查视图）。原始 Swagger 只读留存并记 sha256；改动通过「重新解析 + 覆盖」完成（BR-2）。
          <strong>注册与日常管理的入口在各 Server 详情页的「REST 服务」tab。</strong>
        </p>
      </div>
      <div class="toolbar">
        <el-button @click="load">刷新</el-button>
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
      <el-table-column label="操作" width="330" fixed="right">
        <template #default="{ row }: { row: RegistrationView }">
          <RegistrationDocActions
            :registration-id="row.id"
            :name="row.name"
            @changed="load"
          />
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
        <el-empty description="还没有注册过文档。请到某个 MCP Server 详情页的「REST 服务」tab 注册" />
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
  </div>
</template>
