<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'

import * as accessApi from '@/api/access'
import { notifyError } from '@/api/http'
import type { AccessCatalogRow, AccessView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import {
  accessStatusTag,
  ACCESS_STATUS_LABEL,
  formatDateTime,
  labelOf,
  SERVER_STATUS_LABEL,
  statusTag
} from '@/utils/format'

/**
 * 跨部门访问申请（D1-D4）。
 * 三视图：申请目录 / 我发起的 / 审批管理（待审批 + 已授权回收）。
 * 授权只读：打开外部门 Server 后渲染只读详情（ServerView.manageable=false）。
 */
const router = useRouter()
const auth = useAuthStore()

const canApprove = computed(() =>
  auth.roles.includes('PLATFORM_ADMIN') || auth.roles.includes('DEPT_ADMIN')
)

const activeTab = ref('catalog')

// ---- 申请目录 ----
const catalogLoading = ref(false)
const catalogRows = ref<AccessCatalogRow[]>([])
const catalogTotal = ref(0)
const catalogQuery = reactive({ page: 0, size: 20 })

// ---- 我发起的 / 待审批 / 已授权 ----
const mineLoading = ref(false)
const mineRows = ref<AccessView[]>([])
const todoLoading = ref(false)
const todoRows = ref<AccessView[]>([])
const grantLoading = ref(false)
const grantRows = ref<AccessView[]>([])

// ---- 申请 / 审批对话框 ----
const applyDialog = reactive({
  visible: false,
  serverId: 0,
  serverName: '',
  reason: '',
  saving: false
})
const reviewDialog = reactive({
  visible: false,
  row: null as AccessView | null,
  action: '' as 'approve' | 'reject' | 'revoke',
  note: '',
  saving: false
})

async function loadCatalog(): Promise<void> {
  catalogLoading.value = true
  try {
    const result = await accessApi.catalog({ page: catalogQuery.page, size: catalogQuery.size })
    catalogRows.value = result.items
    catalogTotal.value = result.total
  } catch (error) {
    notifyError(error)
  } finally {
    catalogLoading.value = false
  }
}

async function loadMine(): Promise<void> {
  mineLoading.value = true
  try {
    mineRows.value = await accessApi.mine()
  } catch (error) {
    notifyError(error)
  } finally {
    mineLoading.value = false
  }
}

async function loadTodo(): Promise<void> {
  todoLoading.value = true
  try {
    todoRows.value = await accessApi.todo()
  } catch (error) {
    notifyError(error)
  } finally {
    todoLoading.value = false
  }
}

async function loadGrants(): Promise<void> {
  grantLoading.value = true
  try {
    grantRows.value = await accessApi.grants()
  } catch (error) {
    notifyError(error)
  } finally {
    grantLoading.value = false
  }
}

function openApply(serverId: number, serverName: string): void {
  applyDialog.serverId = serverId
  applyDialog.serverName = serverName
  applyDialog.reason = ''
  applyDialog.visible = true
}

async function submitApply(): Promise<void> {
  if (!applyDialog.reason.trim()) {
    ElMessage.warning('请填写申请理由（说明用途，审批人将据此判断）')
    return
  }
  applyDialog.saving = true
  try {
    await accessApi.apply({ serverId: applyDialog.serverId, reason: applyDialog.reason.trim() })
    applyDialog.visible = false
    ElMessage.success('申请已提交，等待资源方部门管理员审批')
    await Promise.all([loadCatalog(), loadMine()])
  } catch (error) {
    notifyError(error)
  } finally {
    applyDialog.saving = false
  }
}

function openReview(row: AccessView, action: 'approve' | 'reject' | 'revoke'): void {
  reviewDialog.row = row
  reviewDialog.action = action
  reviewDialog.note = ''
  reviewDialog.visible = true
}

async function submitReview(): Promise<void> {
  const row = reviewDialog.row
  if (!row) return
  reviewDialog.saving = true
  const note = reviewDialog.note.trim() || undefined
  try {
    const label = reviewDialog.action === 'approve' ? '已授权' : reviewDialog.action === 'reject' ? '已驳回' : '已回收'
    if (reviewDialog.action === 'approve') {
      await accessApi.approve(row.id, note)
    } else if (reviewDialog.action === 'reject') {
      await accessApi.reject(row.id, note)
    } else {
      await accessApi.revoke(row.id, note)
    }
    reviewDialog.visible = false
    ElMessage.success(`申请已${label}`)
    await Promise.all([loadTodo(), loadGrants(), loadMine(), loadCatalog()])
  } catch (error) {
    notifyError(error)
  } finally {
    reviewDialog.saving = false
  }
}

function onTabChange(name: string | number): void {
  if (name === 'catalog') {
    void loadCatalog()
  } else if (name === 'mine') {
    void loadMine()
  } else if (name === 'review') {
    if (!canApprove.value) return
    void Promise.all([loadTodo(), loadGrants()])
  }
}

function openServer(row: { serverId: number }): void {
  void router.push(`/servers/${row.serverId}`)
}

function onCatalogPageChange(page: number): void {
  catalogQuery.page = page - 1
  void loadCatalog()
}

async function confirmRevokeSoon(row: AccessView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认回收「${row.deptName || row.deptId}」对 Server「${row.serverName}」的只读访问？`,
      '回收授权',
      { type: 'warning', confirmButtonText: '回收', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  openReview(row, 'revoke')
}

onMounted(() => {
  void loadCatalog()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>访问申请</h2>
        <p class="subtitle">
          跨部门访问 MCP Server 需向<b>资源方</b>（Server 所属部门的部门管理员 / 平台管理员）申请，获批后<b>只读</b>访问。
          授权按部门生效（覆盖本部门及子部门），本部门的人仍正常管理本部门的 Server。
        </p>
      </div>
      <div class="toolbar">
        <el-button @click="onTabChange(activeTab)">刷新</el-button>
      </div>
    </div>

    <el-tabs v-model="activeTab" @tab-change="onTabChange">
      <!-- 申请目录 -->
      <el-tab-pane label="申请目录" name="catalog">
        <el-table v-loading="catalogLoading" :data="catalogRows" border stripe size="small">
          <el-table-column prop="name" label="Server" min-width="160" show-overflow-tooltip>
            <template #default="{ row }: { row: AccessCatalogRow }">
              <span>{{ row.name }}</span>
              <span v-if="row.title" class="muted"> · {{ row.title }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="pathSegment" label="PATH 末段" width="130">
            <template #default="{ row }: { row: AccessCatalogRow }">
              <span class="mono">{{ row.pathSegment }}</span>
            </template>
          </el-table-column>
          <el-table-column label="归属部门" width="130">
            <template #default="{ row }: { row: AccessCatalogRow }">{{ row.deptName || '—' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }: { row: AccessCatalogRow }">
              <el-tag size="small" :type="statusTag(row.status)">
                {{ labelOf(SERVER_STATUS_LABEL, row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="我的申请" width="110" align="center">
            <template #default="{ row }: { row: AccessCatalogRow }">
              <el-tag v-if="row.myStatus" size="small" :type="accessStatusTag(row.myStatus)">
                {{ labelOf(ACCESS_STATUS_LABEL, row.myStatus) }}
              </el-tag>
              <span v-else class="muted">未申请</span>
            </template>
          </el-table-column>
          <el-table-column label="注册时间" width="160">
            <template #default="{ row }: { row: AccessCatalogRow }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="{ row }: { row: AccessCatalogRow }">
              <el-button
                v-if="!row.myStatus || row.myStatus === 'REJECTED' || row.myStatus === 'REVOKED'"
                link
                type="primary"
                size="small"
                @click="openApply(row.id, row.name)"
              >
                {{ row.myStatus === 'REJECTED' || row.myStatus === 'REVOKED' ? '重新申请' : '申请访问' }}
              </el-button>
              <el-button v-else-if="row.myStatus === 'PENDING'" link type="info" size="small" disabled>
                审批中
              </el-button>
              <el-button v-else link type="success" size="small" @click="openServer({ serverId: row.id })">
                打开（只读）
              </el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="没有可申请的 Server（当前账号可访问全部 Server 时目录为空）" />
          </template>
        </el-table>
        <div class="pager">
          <el-pagination
            layout="total, prev, pager, next, sizes"
            :total="catalogTotal"
            :current-page="catalogQuery.page + 1"
            :page-size="catalogQuery.size"
            :page-sizes="[10, 20, 50, 100]"
            @current-change="onCatalogPageChange"
            @size-change="(size: number) => { catalogQuery.size = size; catalogQuery.page = 0; loadCatalog() }"
          />
        </div>
      </el-tab-pane>

      <!-- 我发起的 -->
      <el-tab-pane label="我发起的" name="mine">
        <el-table v-loading="mineLoading" :data="mineRows" border stripe size="small">
          <el-table-column prop="serverName" label="Server" min-width="150" show-overflow-tooltip />
          <el-table-column label="申请部门" width="130">
            <template #default="{ row }: { row: AccessView }">{{ row.deptName || row.deptId }}</template>
          </el-table-column>
          <el-table-column prop="reason" label="申请理由" min-width="200" show-overflow-tooltip />
          <el-table-column label="状态" width="100">
            <template #default="{ row }: { row: AccessView }">
              <el-tag size="small" :type="accessStatusTag(row.status)">
                {{ labelOf(ACCESS_STATUS_LABEL, row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="申请人" width="110">
            <template #default="{ row }: { row: AccessView }">{{ row.requesterName || `#${row.requestedBy}` }}</template>
          </el-table-column>
          <el-table-column label="申请时间" width="160">
            <template #default="{ row }: { row: AccessView }">{{ formatDateTime(row.requestedAt) }}</template>
          </el-table-column>
          <el-table-column label="审批意见" min-width="180">
            <template #default="{ row }: { row: AccessView }">
              <template v-if="row.reviewNote">
                <span>{{ row.reviewNote }}</span>
                <span v-if="row.reviewerName" class="muted">（{{ row.reviewerName }}）</span>
              </template>
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }: { row: AccessView }">
              <el-button v-if="row.status === 'APPROVED'" link type="success" size="small" @click="openServer(row)">
                打开（只读）
              </el-button>
              <el-button
                v-else-if="row.status === 'REJECTED' || row.status === 'REVOKED'"
                link
                type="primary"
                size="small"
                @click="openApply(row.serverId, row.serverName)"
              >
                重新申请
              </el-button>
              <span v-else class="muted">审批中…</span>
            </template>
          </el-table-column>
          <template #empty><el-empty description="本部门还没有发起过访问申请" /></template>
        </el-table>
      </el-tab-pane>

      <!-- 审批管理（资源方） -->
      <el-tab-pane name="review" :disabled="!canApprove">
        <template #label>
          审批管理
          <el-tag v-if="canApprove" size="small" type="warning" effect="plain">资源方</el-tag>
        </template>
        <el-alert v-if="!canApprove" type="info" :closable="false" show-icon>
          审批需要资源方部门管理员（DEPT_ADMIN）或平台管理员身份
        </el-alert>

        <template v-if="canApprove">
          <h3 class="section-title">待我审批</h3>
          <el-table v-loading="todoLoading" :data="todoRows" border stripe size="small">
            <el-table-column prop="serverName" label="Server" min-width="150" show-overflow-tooltip>
              <template #default="{ row }: { row: AccessView }">
                <el-button v-if="row.manageable" link type="primary" size="small" @click="openServer(row)">
                  {{ row.serverName }}
                </el-button>
                <span v-else>{{ row.serverName }}</span>
              </template>
            </el-table-column>
            <el-table-column label="申请部门" width="130">
              <template #default="{ row }: { row: AccessView }">{{ row.deptName || row.deptId }}</template>
            </el-table-column>
            <el-table-column prop="reason" label="申请理由" min-width="200" show-overflow-tooltip />
            <el-table-column label="申请人" width="110">
              <template #default="{ row }: { row: AccessView }">{{ row.requesterName || `#${row.requestedBy}` }}</template>
            </el-table-column>
            <el-table-column label="申请时间" width="160">
              <template #default="{ row }: { row: AccessView }">{{ formatDateTime(row.requestedAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }: { row: AccessView }">
                <el-button link type="success" size="small" @click="openReview(row, 'approve')">通过</el-button>
                <el-button link type="danger" size="small" @click="openReview(row, 'reject')">驳回</el-button>
              </template>
            </el-table-column>
            <template #empty><el-empty description="暂无待审批的申请" /></template>
          </el-table>

          <h3 class="section-title">已授权（可回收）</h3>
          <el-table v-loading="grantLoading" :data="grantRows" border stripe size="small">
            <el-table-column prop="serverName" label="Server" min-width="150" show-overflow-tooltip />
            <el-table-column label="授权部门" width="130">
              <template #default="{ row }: { row: AccessView }">{{ row.deptName || row.deptId }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }: { row: AccessView }">
                <el-tag size="small" :type="accessStatusTag(row.status)">
                  {{ labelOf(ACCESS_STATUS_LABEL, row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="授权时间" width="160">
              <template #default="{ row }: { row: AccessView }">{{ formatDateTime(row.reviewedAt) }}</template>
            </el-table-column>
            <el-table-column prop="reviewNote" label="审批备注" min-width="160" show-overflow-tooltip>
              <template #default="{ row }: { row: AccessView }">
                <span v-if="row.reviewNote">{{ row.reviewNote }}</span>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="150" fixed="right">
              <template #default="{ row }: { row: AccessView }">
                <el-button link type="warning" size="small" @click="confirmRevokeSoon(row)">回收</el-button>
              </template>
            </el-table-column>
            <template #empty><el-empty description="还没有对外部门授权" /></template>
          </el-table>
        </template>
      </el-tab-pane>
    </el-tabs>

    <!-- 申请对话框 -->
    <el-dialog v-model="applyDialog.visible" title="申请访问 MCP Server" width="520px">
      <el-alert type="info" :closable="false" show-icon class="dialog-tip">
        <template #title>向资源方申请只读访问「{{ applyDialog.serverName }}」</template>
        <template #default>
          审批人为该 Server 所属部门的部门管理员 / 平台管理员。通过后<b>本部门及子部门</b>成员可查看其详情与
          Tool 列表（不含凭据与写操作）。
        </template>
      </el-alert>
      <el-form label-width="80px" class="apply-form">
        <el-form-item label="申请理由" required>
          <el-input v-model="applyDialog.reason" type="textarea" :rows="4" maxlength="500" show-word-limit
            placeholder="说明用途与期望使用方式，审批人将据此判断" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="applyDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="applyDialog.saving" @click="submitApply">提交申请</el-button>
      </template>
    </el-dialog>

    <!-- 审批对话框 -->
    <el-dialog
      v-model="reviewDialog.visible"
      :title="reviewDialog.action === 'approve' ? '通过申请' : reviewDialog.action === 'reject' ? '驳回申请' : '回收授权'"
      width="520px"
    >
      <p class="muted">
        {{ reviewDialog.action === 'approve'
          ? `确认授予「${reviewDialog.row?.deptName || ''}」只读访问 Server「${reviewDialog.row?.serverName || ''}」？`
          : reviewDialog.action === 'reject'
            ? `驳回后该部门可带新理由重新申请。确认驳回「${reviewDialog.row?.deptName || ''}」的申请？`
            : `回收后该部门将无法再查看此 Server。确认回收？` }}
      </p>
      <el-form label-width="70px">
        <el-form-item label="审批意见">
          <el-input v-model="reviewDialog.note" type="textarea" :rows="3" maxlength="500" show-word-limit
            :placeholder="reviewDialog.action === 'reject' ? '建议说明驳回原因（必填更佳）' : '可留空'" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewDialog.visible = false">取消</el-button>
        <el-button
          :type="reviewDialog.action === 'approve' ? 'success' : reviewDialog.action === 'reject' ? 'danger' : 'warning'"
          :loading="reviewDialog.saving"
          @click="submitReview"
        >
          {{ reviewDialog.action === 'approve' ? '通过并授权' : reviewDialog.action === 'reject' ? '驳回' : '确认回收' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.section-title {
  margin: 18px 0 10px;
  font-size: 14px;
  font-weight: 600;
}

.dialog-tip {
  margin-bottom: 16px;
}

.apply-form {
  margin-top: 8px;
}
</style>
