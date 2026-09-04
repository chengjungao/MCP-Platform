<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import * as clusterApi from '@/api/cluster'
import { notifyError } from '@/api/http'
import * as orgApi from '@/api/org'
import type { ClusterRequest, ClusterType, ClusterView, NodeView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { copyText } from '@/utils/clipboard'
import { flattenDepartments, type DepartmentOption } from '@/utils/departments'
import { CLUSTER_TYPE_LABEL, formatDateTime, labelOf, NODE_STATUS_LABEL, statusTag } from '@/utils/format'

const auth = useAuthStore()

const clusters = ref<ClusterView[]>([])
const loading = ref(false)
const departmentOptions = ref<DepartmentOption[]>([])

/** 节点列表按集群懒加载：一进页面就把所有集群的节点全拉一遍没有必要。 */
const nodes = reactive<Record<number, NodeView[]>>({})
const nodesLoading = reactive<Record<number, boolean>>({})

const editDialog = reactive({
  visible: false,
  saving: false,
  id: null as number | null,
  form: {
    name: '',
    type: 'SHARED' as ClusterType,
    entrypoint: '',
    pathPrefix: '',
    ownerDeptId: undefined as number | undefined,
    description: '',
    enabled: true
  }
})
const grantDialog = reactive({
  visible: false,
  saving: false,
  clusterId: 0,
  clusterName: '',
  deptIds: [] as number[]
})
const tokenDialog = reactive({ visible: false, clusterName: '', token: '' })

const canWrite = computed(() => auth.can('cluster:write'))
const canGrant = computed(() => auth.can('cluster:grant'))

async function load(): Promise<void> {
  loading.value = true
  try {
    clusters.value = await clusterApi.list()
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

async function loadDepartments(): Promise<void> {
  if (!auth.can('dept:read')) {
    departmentOptions.value = []
    return
  }
  try {
    departmentOptions.value = flattenDepartments(await orgApi.departmentTree())
  } catch {
    // 拿不到部门树不该挡住集群页：只是归属部门与授权部门两个下拉会变空
    departmentOptions.value = []
  }
}

async function loadNodes(cluster: ClusterView): Promise<void> {
  nodesLoading[cluster.id] = true
  try {
    nodes[cluster.id] = await clusterApi.nodes(cluster.id)
  } catch (error) {
    notifyError(error)
  } finally {
    nodesLoading[cluster.id] = false
  }
}

function onExpandChange(row: ClusterView, expanded: ClusterView[]): void {
  if (expanded.some((item) => item.id === row.id)) {
    void loadNodes(row)
  }
}

function openCreate(): void {
  editDialog.id = null
  editDialog.form.name = ''
  editDialog.form.type = 'SHARED'
  editDialog.form.entrypoint = ''
  editDialog.form.pathPrefix = ''
  editDialog.form.ownerDeptId = undefined
  editDialog.form.description = ''
  editDialog.form.enabled = true
  editDialog.visible = true
}

function openEdit(cluster: ClusterView): void {
  editDialog.id = cluster.id
  editDialog.form.name = cluster.name
  editDialog.form.type = cluster.type
  editDialog.form.entrypoint = cluster.entrypoint
  editDialog.form.pathPrefix = cluster.pathPrefix ?? ''
  editDialog.form.ownerDeptId = cluster.ownerDeptId
  editDialog.form.description = cluster.description ?? ''
  editDialog.form.enabled = cluster.enabled
  editDialog.visible = true
}

async function submitEdit(): Promise<void> {
  const request: ClusterRequest = {
    name: editDialog.form.name.trim(),
    type: editDialog.form.type,
    entrypoint: editDialog.form.entrypoint.trim(),
    pathPrefix: editDialog.form.pathPrefix.trim() || undefined,
    ownerDeptId: editDialog.form.ownerDeptId,
    description: editDialog.form.description.trim() || undefined,
    enabled: editDialog.form.enabled
  }
  if (!request.name || !request.entrypoint) {
    ElMessage.warning('集群名与入口地址都必填')
    return
  }
  editDialog.saving = true
  try {
    if (editDialog.id == null) {
      await clusterApi.create(request)
      ElMessage.success('集群已创建')
    } else {
      await clusterApi.update(editDialog.id, request)
      ElMessage.success('集群已更新')
    }
    editDialog.visible = false
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    editDialog.saving = false
  }
}

function openGrant(cluster: ClusterView): void {
  grantDialog.clusterId = cluster.id
  grantDialog.clusterName = cluster.name
  grantDialog.deptIds = [...cluster.grantedDeptIds]
  grantDialog.visible = true
}

async function submitGrant(): Promise<void> {
  grantDialog.saving = true
  try {
    await clusterApi.grant(grantDialog.clusterId, grantDialog.deptIds)
    grantDialog.visible = false
    ElMessage.success('授权部门已更新')
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    grantDialog.saving = false
  }
}

async function rotateToken(cluster: ClusterView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '轮换后旧令牌立即失效：所有还在用旧令牌的 Executor 节点都会被拒绝注册与拉取快照，' +
        '直到把新令牌配到它们的 EXECUTOR_NODE_TOKEN 并重启。确定继续？',
      `轮换 ${cluster.name} 的节点令牌`,
      { type: 'warning', confirmButtonText: '确认轮换', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const result = await clusterApi.rotateNodeToken(cluster.id)
    tokenDialog.clusterName = cluster.name
    tokenDialog.token = result.token ?? ''
    tokenDialog.visible = true
  } catch (error) {
    notifyError(error)
  }
}

async function offlineNode(cluster: ClusterView, node: NodeView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `下线节点 ${node.nodeKey} 后它不会再出现在该集群的可用节点里；` +
        '若进程还在跑，它会带着旧令牌重新注册回来。要彻底摘掉请同时停掉那个进程。',
      '下线节点',
      { type: 'warning', confirmButtonText: '确认下线', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    nodes[cluster.id] = await clusterApi.offlineNode(node.id)
    await load()
  } catch (error) {
    notifyError(error)
  }
}

async function copyToken(): Promise<void> {
  if (await copyText(tokenDialog.token)) ElMessage.success('令牌已复制')
  else ElMessage.error('复制失败，请手动选择文本')
}

onMounted(() => {
  void load()
  void loadDepartments()
})
</script>

<template>
  <div class="page">
    <div class="page-header">
      <div>
        <h2>集群与节点</h2>
        <p class="subtitle">
          集群是 Executor 节点的编组单位，也是 PATH 前缀与端点模板的来源。
          每次发布、下线都会让集群快照的 revision 加一，Executor 轮询到新版本后原子替换本地快照。
        </p>
      </div>
      <div class="toolbar">
        <el-button type="primary" :disabled="!canWrite" @click="openCreate">新建集群</el-button>
        <el-button @click="load">刷新</el-button>
      </div>
    </div>

    <el-alert type="info" :closable="false" show-icon class="tip">
      <template #title>用户只能自定义 PATH 末段，前缀由集群决定（BR-3）</template>
      <template #default>
        下面的「端点模板」就是最终对外暴露的地址形状。同一个集群内末段必须唯一——
        末段是 MCP Client 配置里唯一能区分 Server 的东西，撞了就意味着两个 Server 抢一个端点。
      </template>
    </el-alert>

    <el-table v-loading="loading" :data="clusters" border stripe @expand-change="onExpandChange">
      <el-table-column type="expand">
        <template #default="{ row }">
          <div class="node-box" v-loading="nodesLoading[row.id]">
            <el-table :data="nodes[row.id] ?? []" border size="small">
              <el-table-column prop="nodeKey" label="节点标识" min-width="160">
                <template #default="node"><span class="mono">{{ node.row.nodeKey }}</span></template>
              </el-table-column>
              <el-table-column label="地址" min-width="180">
                <template #default="node">
                  <span class="mono small">{{ node.row.host }}:{{ node.row.port ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="版本" width="120">
                <template #default="node">{{ node.row.version || '—' }}</template>
              </el-table-column>
              <el-table-column label="协议" width="130">
                <template #default="node">
                  <span class="mono small">{{ node.row.protocolVersion || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="90">
                <template #default="node">
                  <el-tag :type="statusTag(node.row.status)" size="small">
                    {{ labelOf(NODE_STATUS_LABEL, node.row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="最近心跳" width="170">
                <template #default="node">{{ formatDateTime(node.row.lastHeartbeatAt) }}</template>
              </el-table-column>
              <el-table-column label="负载" min-width="180">
                <template #default="node">
                  <span class="mono small">{{
                    node.row.loadInfo ? JSON.stringify(node.row.loadInfo) : '—'
                  }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="90" fixed="right">
                <template #default="node">
                  <el-button
                    link
                    type="danger"
                    size="small"
                    :disabled="!canWrite || node.row.status === 'OFFLINE'"
                    @click="offlineNode(row, node.row)"
                  >
                    下线
                  </el-button>
                </template>
              </el-table-column>
              <template #empty>
                <span class="muted">
                  该集群还没有节点注册。Executor 用节点令牌向 Manager 注册后才会出现在这里。
                </span>
              </template>
            </el-table>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="集群名" min-width="140" />
      <el-table-column label="类型" width="110">
        <template #default="{ row }">
          <el-tag :type="row.type === 'PRIVATE' ? 'warning' : 'primary'" size="small" effect="plain">
            {{ labelOf(CLUSTER_TYPE_LABEL, row.type) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="端点模板" min-width="280">
        <template #default="{ row }">
          <span class="mono small">{{ row.endpointTemplate || '—' }}</span>
          <div class="muted small">入口 {{ row.entrypoint }} · 前缀 {{ row.pathPrefix || '（默认）' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="节点" width="100" align="center">
        <template #default="{ row }">
          <span :class="{ muted: row.onlineNodeCount === 0 }">
            {{ row.onlineNodeCount }} / {{ row.nodeCount }}
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="publishedServerCount" label="已发布" width="90" align="center" />
      <el-table-column label="revision" width="100" align="center">
        <template #default="{ row }"><span class="mono">{{ row.revision }}</span></template>
      </el-table-column>
      <el-table-column label="归属部门" width="130">
        <template #default="{ row }">{{ row.ownerDeptName || '平台' }}</template>
      </el-table-column>
      <el-table-column label="启用" width="80" align="center">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small" effect="plain">
            {{ row.enabled ? '是' : '否' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" :disabled="!canWrite" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" size="small" :disabled="!canGrant" @click="openGrant(row)">授权部门</el-button>
          <el-button link type="danger" size="small" :disabled="!canWrite" @click="rotateToken(row)">轮换令牌</el-button>
        </template>
      </el-table-column>
      <template #empty><el-empty description="还没有集群" /></template>
    </el-table>

    <el-dialog
      v-model="editDialog.visible"
      :title="editDialog.id == null ? '新建集群' : '编辑集群'"
      width="620px"
    >
      <el-form label-width="110px" @submit.prevent="submitEdit">
        <el-form-item label="集群名">
          <el-input v-model="editDialog.form.name" maxlength="64" />
        </el-form-item>
        <el-form-item label="类型">
          <el-radio-group v-model="editDialog.form.type">
            <el-radio-button value="SHARED">共享集群</el-radio-button>
            <el-radio-button value="PRIVATE">专属集群</el-radio-button>
          </el-radio-group>
          <div class="hint muted">
            专属集群只对归属部门与被授权部门开放发布；共享集群所有部门都能发布，靠末段唯一性隔离。
          </div>
        </el-form-item>
        <el-form-item label="入口地址">
          <el-input v-model="editDialog.form.entrypoint" placeholder="https://mcp.example.com" />
          <div class="hint muted">对外暴露的基地址，用来拼端点模板与受保护资源元数据地址。</div>
        </el-form-item>
        <el-form-item label="PATH 前缀">
          <el-input v-model="editDialog.form.pathPrefix" placeholder="留空使用平台默认前缀" />
          <div class="hint muted">BR-3：前缀不可由普通用户在 Server 上自定义。</div>
        </el-form-item>
        <el-form-item label="归属部门">
          <el-select v-model="editDialog.form.ownerDeptId" clearable placeholder="留空表示平台级" style="width: 100%">
            <el-option
              v-for="option in departmentOptions"
              :key="option.id"
              :label="option.label"
              :value="option.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editDialog.form.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="editDialog.form.enabled" />
          <div class="hint muted">停用后不能往该集群发布，已发布的端点仍由 Executor 继续提供。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="editDialog.saving" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="grantDialog.visible" :title="`授权部门 · ${grantDialog.clusterName}`" width="520px">
      <el-alert type="info" :closable="false" show-icon class="tip">
        <template #title>授权是整体替换</template>
        <template #default>提交的列表就是最终的授权集合，取消勾选即收回该部门的发布权限。</template>
      </el-alert>
      <el-select v-model="grantDialog.deptIds" multiple filterable placeholder="选择部门" style="width: 100%">
        <el-option v-for="option in departmentOptions" :key="option.id" :label="option.label" :value="option.id" />
      </el-select>
      <p v-if="departmentOptions.length === 0" class="muted hint">
        拿不到部门列表：可能是当前账号没有 dept:read 权限。
      </p>
      <template #footer>
        <el-button @click="grantDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="grantDialog.saving" @click="submitGrant">保存授权</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="tokenDialog.visible" :title="`新的节点令牌 · ${tokenDialog.clusterName}`" width="620px">
      <el-alert type="error" :closable="false" show-icon class="tip">
        <template #title>这个令牌只显示这一次，关掉对话框就再也拿不回来</template>
        <template #default>
          平台只保存它的 sha256，用于校验 Executor 的注册与快照拉取请求，明文在响应返回后即丢弃。
          请立刻复制到各节点的 <span class="mono">EXECUTOR_NODE_TOKEN</span> 并重启 Executor。
          忘记保存的唯一补救办法是再轮换一次。
        </template>
      </el-alert>
      <el-input :model-value="tokenDialog.token" readonly class="mono">
        <template #append>
          <el-button @click="copyToken">复制</el-button>
        </template>
      </el-input>
      <template #footer>
        <el-button type="primary" @click="tokenDialog.visible = false">我已保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tip {
  margin-bottom: 12px;
}

.node-box {
  padding: 8px 12px 12px 48px;
}

.hint {
  font-size: 12px;
  line-height: 1.6;
}
</style>