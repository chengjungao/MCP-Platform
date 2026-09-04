<script setup lang="ts">
import { computed, onMounted, reactive } from 'vue'
import { useRouter } from 'vue-router'

import * as clusterApi from '@/api/cluster'
import * as registrationApi from '@/api/registration'
import * as serverApi from '@/api/server'
import { useAuthStore } from '@/stores/auth'
import { useMetaStore } from '@/stores/meta'
import { formatDateTime } from '@/utils/format'

const auth = useAuthStore()
const metaStore = useMetaStore()
const router = useRouter()

const stats = reactive({
  servers: null as number | null,
  registrations: null as number | null,
  clusters: null as number | null,
  onlineNodes: null as number | null,
  loading: false
})

const meta = computed(() => metaStore.meta)

/**
 * 概览页的每个数字都独立取数、独立失败。
 *
 * 用 allSettled 而不是 all：一个只读账号拿不到 cluster:read，
 * 若整体 reject，整个概览页会白屏——而它其实还能显示 Server 数量。
 */
async function load(): Promise<void> {
  stats.loading = true
  const [servers, registrations, clusters] = await Promise.allSettled([
    auth.can('server:read') ? serverApi.page({ page: 0, size: 1 }) : Promise.resolve(null),
    auth.can('registration:read') ? registrationApi.page({ page: 0, size: 1 }) : Promise.resolve(null),
    auth.can('cluster:read') ? clusterApi.list() : Promise.resolve(null)
  ])
  stats.servers = servers.status === 'fulfilled' ? (servers.value?.total ?? 0) : null
  stats.registrations = registrations.status === 'fulfilled' ? (registrations.value?.total ?? 0) : null
  if (clusters.status === 'fulfilled' && clusters.value) {
    stats.clusters = clusters.value.length
    stats.onlineNodes = clusters.value.reduce((sum, c) => sum + c.onlineNodeCount, 0)
  } else {
    stats.clusters = null
    stats.onlineNodes = null
  }
  stats.loading = false
}

function go(path: string, permission?: string): void {
  if (!auth.can(permission)) return
  void router.push(path)
}

onMounted(() => {
  void metaStore.load()
  void load()
})
</script>

<template>
  <div class="page" v-loading="stats.loading">
    <div class="page-header">
      <div>
        <h2>概览</h2>
        <p class="subtitle">
          当前身份 {{ auth.displayName }}，角色 {{ auth.roles.join('、') || '无' }}，
          权限点 {{ auth.permissionSet.size }} 个
        </p>
      </div>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-row :gutter="16">
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="stat" @click="go('/servers', 'server:read')">
          <div class="stat-value">{{ stats.servers ?? '—' }}</div>
          <div class="stat-label">MCP Server</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="stat" @click="go('/registrations', 'registration:read')">
          <div class="stat-value">{{ stats.registrations ?? '—' }}</div>
          <div class="stat-label">注册文档</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="stat" @click="go('/clusters', 'cluster:read')">
          <div class="stat-value">{{ stats.clusters ?? '—' }}</div>
          <div class="stat-label">集群</div>
        </el-card>
      </el-col>
      <el-col :xs="12" :sm="6">
        <el-card shadow="hover" class="stat" @click="go('/clusters', 'cluster:read')">
          <div class="stat-value">{{ stats.onlineNodes ?? '—' }}</div>
          <div class="stat-label">在线节点</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card v-if="meta" shadow="never" class="protocol-card">
      <template #header>
        <div class="card-head">
          <span>协议策略（决策 D1：Modern-only）</span>
          <el-tag type="success" size="small" effect="plain">
            {{ meta.supportedProtocolVersion }}
          </el-tag>
        </div>
      </template>
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="支持的协议版本">
          <span class="mono">{{ meta.supportedProtocolVersion }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="兼容 legacy">
          <el-tag :type="meta.legacySupported ? 'warning' : 'danger'" size="small">
            {{ meta.legacySupported ? '是' : '否（显式拒绝，-32022）' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="已停止支持的版本">
          <span class="mono">{{ meta.legacyProtocolVersions.join('、') }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="JSON Schema 方言">
          <span class="mono">{{ meta.jsonSchemaDialect }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="PATH 保留前缀">
          <span class="mono">{{ meta.defaultPathPrefix }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="list 默认 TTL">
          {{ meta.defaultListTtlMs }} ms
        </el-descriptions-item>
        <el-descriptions-item label="PATH 末段规则">
          <span class="mono">{{ meta.pathSegmentPattern }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="Tool 名规则">
          <span class="mono">{{ meta.toolNamePattern }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="升级指引" :span="2">
          <a :href="meta.upgradeGuideUrl" target="_blank" rel="noopener">{{ meta.upgradeGuideUrl }}</a>
        </el-descriptions-item>
      </el-descriptions>
      <p class="muted note">
        以上取值来自控制面 <span class="mono">GET /api/v1/meta</span>，不在前端硬编码：
        协议策略一旦调整，这里会跟着变。数据获取时间 {{ formatDateTime(new Date().toISOString()) }}。
      </p>
    </el-card>

    <el-card shadow="never" class="quick-card">
      <template #header><span>常用操作</span></template>
      <div class="toolbar">
        <el-button type="primary" :disabled="!auth.can('registration:create')" @click="go('/registrations')">
          注册一份 Swagger 文档
        </el-button>
        <el-button :disabled="!auth.can('server:read')" @click="go('/servers')">
          查看 MCP Server
        </el-button>
        <el-button :disabled="!auth.can('cluster:read')" @click="go('/clusters')">
          查看集群与节点
        </el-button>
        <el-button :disabled="!auth.can('audit:read')" @click="go('/audits')">
          查看审计日志
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
.stat {
  margin-bottom: 16px;
  text-align: center;
  cursor: pointer;
}

.stat-value {
  font-size: 28px;
  font-weight: 600;
  line-height: 1.2;
}

.stat-label {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.protocol-card,
.quick-card {
  margin-bottom: 16px;
}

.note {
  margin: 12px 0 0;
  font-size: 12px;
}
</style>