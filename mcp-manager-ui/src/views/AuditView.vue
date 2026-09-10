<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import * as auditApi from '@/api/audit'
import { notifyError } from '@/api/http'
import type { AuditView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { copyText } from '@/utils/clipboard'
import { AUDIT_ACTIONS, AUDIT_ACTION_LABEL, formatDateTime, labelOf } from '@/utils/format'

const router = useRouter()
const auth = useAuthStore()

const rows = ref<AuditView[]>([])
const total = ref(0)
const loading = ref(false)
const exporting = ref(false)
const query = reactive({ page: 0, size: 50, action: '' })

/** 后端默认每页 50，这里不擅自改小：审计页的用法是顺着时间往下扫，翻页越少越好。 */
const canRead = computed(() => auth.can('audit:read'))

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await auditApi.page({
      page: query.page,
      size: query.size,
      action: query.action || undefined
    })
    rows.value = result.items
    total.value = result.total
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

function onActionChange(): void {
  query.page = 0
  void load()
}

function onPageChange(page: number): void {
  query.page = page - 1
  void load()
}

function onSizeChange(size: number): void {
  query.size = size
  query.page = 0
  void load()
}

/**
 * 导出 CSV。
 *
 * 导出的是「当前筛选条件」，而不是当前这一页——页面上写清楚，避免有人以为只导了 50 条。
 * 成功后不弹 toast：浏览器自己会出现下载项，再弹一个只会挡住视线。
 */
async function onExport(): Promise<void> {
  exporting.value = true
  try {
    await auditApi.exportCsv(query.action || undefined)
  } catch (error) {
    notifyError(error)
  } finally {
    exporting.value = false
  }
}

function formatDetail(detail?: Record<string, unknown>): string {
  if (!detail) return '（无附加信息）'
  return JSON.stringify(detail, null, 2)
}

/** 审计里绝不出现密钥明文：后端只记类型与掩码，这里如实展示，不做任何补全。 */
async function copyTrace(traceId?: string): Promise<void> {
  if (!traceId) return
  if (await copyText(traceId)) ElMessage.success('traceId 已复制')
  else ElMessage.error('复制失败，请手动选择文本')
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>审计日志</h2>
        <p class="subtitle">
          注册、覆盖、发布、回滚、密钥变更与成员授权全部留痕（MGM-05）。
          日志只读，任何角色都不能删改——数据库层另有触发器兜底；密钥类记录只含类型与掩码。
        </p>
      </div>
      <div class="toolbar">
        <el-select
          v-model="query.action"
          clearable
          filterable
          placeholder="按动作过滤"
          style="width: 220px"
          @change="onActionChange"
        >
          <el-option v-for="action in AUDIT_ACTIONS" :key="action" :label="labelOf(AUDIT_ACTION_LABEL, action)" :value="action">
            <span>{{ labelOf(AUDIT_ACTION_LABEL, action) }}</span>
            <span class="mono small option-code">{{ action }}</span>
          </el-option>
        </el-select>
        <el-button type="primary" @click="load">刷新</el-button>
        <el-button :loading="exporting" @click="onExport">导出 CSV</el-button>
      </div>
    </div>

    <el-alert v-if="!canRead" type="warning" :closable="false" show-icon class="tip">
      <template #title>当前账号没有 audit:read 权限，下面的列表大概率是空的</template>
    </el-alert>

    <p class="muted export-hint">
      导出的是<b>当前筛选条件下的全部记录</b>（不只是本页），超过后端上限会直接拒绝并提示缩小范围——
      平台不做静默截断，因为被截断的审计文件看起来是完整的。
    </p>

    <el-table v-loading="loading" :data="rows" border stripe size="small">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="detail">
            <h4>附加信息</h4>
            <pre class="raw-block">{{ formatDetail(row.detail) }}</pre>
            <p class="muted hint">
              traceId <span class="mono">{{ row.traceId || '—' }}</span>
              <el-button v-if="row.traceId" link type="primary" size="small" @click="copyTrace(row.traceId)">
                复制
              </el-button>
              · 客户端 IP <span class="mono">{{ row.clientIp || '—' }}</span>
            </p>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作人" width="140">
        <template #default="{ row }">{{ row.actorName || '系统' }}</template>
      </el-table-column>
      <el-table-column label="动作" width="180">
        <template #default="{ row }">
          {{ labelOf(AUDIT_ACTION_LABEL, row.action) }}
          <div class="mono small muted">{{ row.action }}</div>
        </template>
      </el-table-column>
      <el-table-column label="对象" min-width="180">
        <template #default="{ row }">
          <template v-if="row.targetType">
            <el-tag size="small" effect="plain">{{ row.targetType }}</el-tag>
            <span class="mono small target-id">{{ row.targetId }}</span>
            <el-button
              v-if="row.targetType === 'server' && row.targetId"
              link
              type="primary"
              size="small"
              @click="router.push(`/servers/${row.targetId}`)"
            >
              打开
            </el-button>
          </template>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="部门" width="90" align="center">
        <template #default="{ row }">{{ row.deptId ?? '—' }}</template>
      </el-table-column>
      <el-table-column prop="clientIp" label="来源 IP" width="140">
        <template #default="{ row }"><span class="mono small">{{ row.clientIp || '—' }}</span></template>
      </el-table-column>
      <template #empty><el-empty description="没有匹配的审计记录" /></template>
    </el-table>

    <el-pagination
      class="pager"
      layout="total, sizes, prev, pager, next"
      :total="total"
      :page-size="query.size"
      :page-sizes="[20, 50, 100]"
      :current-page="query.page + 1"
      @current-change="onPageChange"
      @size-change="onSizeChange"
    />
  </div>
</template>

<style scoped>
.tip {
  margin-bottom: 12px;
}

.export-hint {
  margin: 0 0 12px;
  font-size: 12px;
  line-height: 1.6;
}

.detail {
  padding: 8px 16px 12px 48px;
}

.detail h4 {
  margin: 0 0 6px;
  font-size: 13px;
}

.target-id {
  margin-left: 6px;
}

.option-code {
  float: right;
  margin-left: 16px;
  color: var(--el-text-color-secondary);
}

.hint {
  font-size: 12px;
}
</style>