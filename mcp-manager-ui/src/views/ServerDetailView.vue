<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import AuthDPanel from '@/components/server/AuthDPanel.vue'
import BasicPanel from '@/components/server/BasicPanel.vue'
import PublishPanel from '@/components/server/PublishPanel.vue'
import ResourcePromptPanel from '@/components/server/ResourcePromptPanel.vue'
import ServerReadonlyPanel from '@/components/server/ServerReadonlyPanel.vue'
import ToolsPanel from '@/components/server/ToolsPanel.vue'
import UpstreamPanel from '@/components/server/UpstreamPanel.vue'
import { notifyError } from '@/api/http'
import * as serverApi from '@/api/server'
import type { ServerView } from '@/api/types'
import { copyText } from '@/utils/clipboard'
import { formatDateTime, labelOf, SERVER_STATUS_LABEL, statusTag } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const serverId = computed(() => Number(route.params.id))
const server = ref<ServerView | null>(null)
const loading = ref(false)
const activeTab = ref('basic')

async function load(): Promise<void> {
  loading.value = true
  try {
    server.value = await serverApi.view(serverId.value)
  } catch (error) {
    notifyError(error)
    server.value = null
  } finally {
    loading.value = false
  }
}

/** 任何一个面板保存成功都要刷新页头：状态、覆盖版本、tool 计数都可能跟着变。 */
function onSaved(updated: ServerView): void {
  server.value = updated
  ElMessage.success('已保存。改动要重新发布后才会被 Executor 加载')
}

/** 跨部门只读授权（manageable=false）时隐藏全部管理 tab，渲染只读精简视图。 */
const isReadonly = computed(() => (server.value ? !server.value.manageable : false))

async function copyEndpoint(): Promise<void> {
  const endpoint = server.value?.endpointPreview
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
  <div class="page" v-loading="loading">
    <template v-if="server">
      <el-card shadow="never" class="head">
        <div class="head-row">
          <div>
            <h2 class="title">
              {{ server.name }}
              <el-tag :type="statusTag(server.status)" size="small">
                {{ labelOf(SERVER_STATUS_LABEL, server.status) }}
              </el-tag>
              <el-tag type="info" size="small" effect="plain">{{ server.protocolVersion }}</el-tag>
            </h2>
            <p class="subtitle">
              <span v-if="server.title">{{ server.title }} · </span>
              归属 {{ server.deptName || '未指定' }} · 覆盖版本 v{{ server.overlayVersion }} ·
              Tool {{ server.enabledToolCount }}/{{ server.toolCount }} 启用 ·
              更新 {{ formatDateTime(server.updatedAt) }}
            </p>
            <p class="endpoint">
              <span class="mono">{{ server.endpointPreview || '未发布，暂无端点' }}</span>
              <el-button v-if="server.endpointPreview" link type="primary" size="small" @click="copyEndpoint">
                复制
              </el-button>
            </p>
          </div>
          <div class="toolbar">
            <el-button @click="router.push(isReadonly ? '/access' : '/servers')">
              {{ isReadonly ? '返回访问申请' : '返回列表' }}
            </el-button>
            <el-button @click="load">刷新</el-button>
          </div>
        </div>
      </el-card>

      <!-- 各面板 lazy：详情页一进来就并发六个请求既慢又会在无权限时刷出一片 403 -->
      <template v-if="!isReadonly">
        <el-tabs v-model="activeTab" class="tabs">
          <el-tab-pane label="基本信息" name="basic" lazy>
            <BasicPanel :server-id="serverId" :server="server" @saved="onSaved" />
          </el-tab-pane>
          <el-tab-pane label="REST 服务" name="upstream" lazy>
            <UpstreamPanel :server-id="serverId" :server="server" @saved="onSaved" />
          </el-tab-pane>
          <el-tab-pane label="MCP 客户端授权 Auth-D" name="authd" lazy>
            <AuthDPanel :server-id="serverId" @changed="load" />
          </el-tab-pane>
          <el-tab-pane label="Tool 与覆盖" name="tools" lazy>
            <ToolsPanel :server-id="serverId" @changed="load" />
          </el-tab-pane>
          <el-tab-pane label="Resource 与 Prompt" name="catalog" lazy>
            <ResourcePromptPanel :server-id="serverId" @changed="load" />
          </el-tab-pane>
          <el-tab-pane label="差异与发布" name="publish" lazy>
            <PublishPanel :server-id="serverId" :server="server" @changed="load" />
          </el-tab-pane>
        </el-tabs>
      </template>
      <ServerReadonlyPanel v-else :server-id="serverId" :server="server" />
    </template>

    <el-empty v-else-if="!loading" description="Server 不存在，或你所在的部门无权访问它">
      <el-button type="primary" @click="router.push('/servers')">返回列表</el-button>
    </el-empty>
  </div>
</template>

<style scoped>
.head {
  margin-bottom: 12px;
}

.head-row {
  display: flex;
  gap: 16px;
  align-items: flex-start;
  justify-content: space-between;
}

.title {
  display: flex;
  gap: 8px;
  align-items: center;
  margin: 0;
  font-size: 18px;
}

.endpoint {
  margin: 6px 0 0;
}
</style>