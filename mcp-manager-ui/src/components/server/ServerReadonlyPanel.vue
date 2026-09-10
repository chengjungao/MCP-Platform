<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { notifyError } from '@/api/http'
import * as serverApi from '@/api/server'
import type { ServerView, ToolView } from '@/api/types'
import { formatDateTime, labelOf, SERVER_STATUS_LABEL, statusTag } from '@/utils/format'

/**
 * 跨部门只读详情：授权部门成员查看外部门 Server 的最小视图。
 * 数据已由后端脱敏（无端点 / 凭据 / 服务地址），本组件只渲染基本信息 + Tool 列表，无任何写操作。
 */
const props = defineProps<{ serverId: number; server: ServerView }>()

const toolsLoading = ref(false)
const tools = ref<ToolView[]>([])

async function loadTools(): Promise<void> {
  toolsLoading.value = true
  try {
    tools.value = await serverApi.tools(props.serverId)
  } catch (error) {
    notifyError(error)
    ElMessage.error('Tool 列表加载失败，请确认访问授权仍有效')
  } finally {
    toolsLoading.value = false
  }
}

onMounted(() => {
  void loadTools()
})
</script>

<template>
  <div class="readonly">
    <el-alert type="info" :closable="false" show-icon class="tip">
      <template #title>跨部门只读访问</template>
      <template #default>
        已获得资源方授权（只读）。可查看基本信息与 Tool 列表，写操作、发布与凭据配置不对本部门开放。
      </template>
    </el-alert>

    <el-card shadow="never" class="block">
      <template #header><span>基本信息</span></template>
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="Server">{{ server.name }}</el-descriptions-item>
        <el-descriptions-item label="归属部门">{{ server.deptName || '未指定' }}</el-descriptions-item>
        <el-descriptions-item label="PATH 末段">
          <span class="mono">{{ server.pathSegment }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag size="small" :type="statusTag(server.status)">
            {{ labelOf(SERVER_STATUS_LABEL, server.status) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item v-if="server.title" label="展示名" :span="2">{{ server.title }}</el-descriptions-item>
        <el-descriptions-item v-if="server.description" label="描述" :span="2">{{ server.description }}</el-descriptions-item>
        <el-descriptions-item label="Tool">
          {{ server.enabledToolCount }}/{{ server.toolCount }} 启用
        </el-descriptions-item>
        <el-descriptions-item label="REST 服务">
          <template v-if="server.upstreams?.length">
            {{ server.upstreams.map((u) => u.name || u.serviceId).join('、') }}
          </template>
          <span v-else>—</span>
        </el-descriptions-item>
        <el-descriptions-item label="更新时间" :span="2">{{ formatDateTime(server.updatedAt) }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card shadow="never" class="block">
      <template #header><span>Tool 列表（只读）</span></template>
      <el-table v-loading="toolsLoading" :data="tools" border stripe size="small">
        <el-table-column prop="effectiveName" label="Tool 名" min-width="180">
          <template #default="{ row }: { row: ToolView }">
            <span class="mono">{{ row.effectiveName }}</span>
            <el-tag v-if="!row.enabled" size="small" type="info" effect="plain">停用</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="接口" min-width="200">
          <template #default="{ row }: { row: ToolView }">
            <span class="mono muted">{{ row.method }} {{ row.path }}</span>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="220" show-overflow-tooltip>
          <template #default="{ row }: { row: ToolView }">
            {{ row.effectiveDescription || row.baseSummary || '—' }}
          </template>
        </el-table-column>
        <template #empty><el-empty description="该 Server 暂无 Tool" /></template>
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.readonly {
  max-width: 900px;
}

.tip {
  margin-bottom: 14px;
}

.block {
  margin-bottom: 14px;
}
</style>
