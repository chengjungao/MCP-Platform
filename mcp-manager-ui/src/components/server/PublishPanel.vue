<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import * as clusterApi from '@/api/cluster'
import { notifyError } from '@/api/http'
import * as serverApi from '@/api/server'
import type { BindingView, ClusterView, DiffView, EffectiveModelView, ServerView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { copyText } from '@/utils/clipboard'
import { BINDING_STATE_LABEL, CLUSTER_TYPE_LABEL, formatDateTime, labelOf, statusTag } from '@/utils/format'

const props = defineProps<{ serverId: number; server: ServerView }>()
const emit = defineEmits<{ changed: [] }>()

const auth = useAuthStore()

const diff = ref<DiffView | null>(null)
const effectiveModel = ref<EffectiveModelView | null>(null)
const bindings = ref<BindingView[]>([])
const clusters = ref<ClusterView[]>([])
const clusterHint = ref('')
const loading = ref(false)

const publishDialog = reactive({
  visible: false,
  saving: false,
  clusterId: undefined as number | undefined,
  note: ''
})
const rollbackDialog = reactive({
  visible: false,
  saving: false,
  clusterId: 0,
  clusterName: '',
  versions: [] as BindingView[],
  version: undefined as number | undefined
})

// ---- MCP 客户端配置（发布详情给出完整可复制的 mcpServers JSON）----
const rememberedToken = ref('') // 会话内记住操作方粘贴的 Auth-D 静态令牌（仅内存，不落库）
const mcpDialog = reactive({
  visible: false,
  binding: null as BindingView | null,
  token: '',
  activeTab: 'json'
})

const authDMode = computed(() => props.server.authD?.mode ?? 'NONE')
const tokenRequired = computed(
  () => authDMode.value === 'STATIC_BEARER' && mcpDialog.binding != null
)

/** STATIC_BEARER 需要真实令牌才允许复制；NONE/OAUTH2 不受限。 */
function copyable(): boolean {
  return !tokenRequired.value || mcpDialog.token.trim().length > 0
}

function openMcpConfig(binding: BindingView): void {
  if (!binding.endpoint) return
  mcpDialog.binding = binding
  mcpDialog.token = rememberedToken.value
  mcpDialog.activeTab = 'json'
  mcpDialog.visible = true
}

/** Authorization 头：NONE 无；STATIC_BEARER 用输入令牌（未输入给占位）；OAUTH2 占位（P1 未实现）。 */
function authHeader(): string | null {
  if (authDMode.value === 'NONE') return null
  if (authDMode.value === 'OAUTH2') return 'Bearer <OAuth2 令牌>'
  const token = mcpDialog.token.trim()
  return token ? `Bearer ${token}` : 'Bearer <静态令牌>'
}

function mcpConfigJson(): string {
  const binding = mcpDialog.binding
  if (!binding) return ''
  const entry: Record<string, unknown> = { type: 'http', url: binding.endpoint }
  const header = authHeader()
  if (header) entry.headers = { Authorization: header }
  // key 用 PATH 末段（全局唯一），便于多个 Server 的配置合并进同一份 mcpServers
  return JSON.stringify({ mcpServers: { [props.server.pathSegment]: entry } }, null, 2)
}

function mcpCurl(): string {
  const binding = mcpDialog.binding
  if (!binding) return ''
  const lines = [
    `curl -sS -X POST '${binding.endpoint}' \\`,
    "  -H 'Content-Type: application/json' \\",
    "  -H 'Mcp-Protocol-Version: 2026-07-28' \\"
  ]
  const header = authHeader()
  if (header) lines.push(`  -H 'Authorization: ${header}' \\`)
  lines.push("  -d '{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\"}'")
  return lines.join('\n')
}

async function copyMcpJson(): Promise<void> {
  if (!copyable()) {
    ElMessage.warning('STATIC_BEARER 下行鉴权需要令牌：请先粘贴 Auth-D 静态令牌')
    return
  }
  rememberedToken.value = mcpDialog.token.trim()
  await copy(mcpConfigJson())
}

async function copyMcpCurl(): Promise<void> {
  if (!copyable()) {
    ElMessage.warning('STATIC_BEARER 下行鉴权需要令牌：请先粘贴 Auth-D 静态令牌')
    return
  }
  rememberedToken.value = mcpDialog.token.trim()
  await copy(mcpCurl())
}

const canPublish = computed(() => auth.can('publish:execute'))
const canRollback = computed(() => auth.can('publish:rollback'))
const currentBindings = computed(() => bindings.value.filter((binding) => binding.current))
const failedBindings = computed(() => bindings.value.filter((binding) => Boolean(binding.failureReason)))

/**
 * 四份数据各自独立失败。
 *
 * 只读账号拿不到 cluster:read，若用 Promise.all 整个面板会一片空白，
 * 而他其实完全有权看差异与生效模型。
 */
async function load(): Promise<void> {
  loading.value = true
  const [diffResult, effectiveResult, bindingResult, clusterResult] = await Promise.allSettled([
    serverApi.diff(props.serverId),
    serverApi.effective(props.serverId),
    serverApi.bindings(props.serverId),
    clusterApi.list()
  ])
  diff.value = diffResult.status === 'fulfilled' ? diffResult.value : null
  effectiveModel.value = effectiveResult.status === 'fulfilled' ? effectiveResult.value : null
  bindings.value = bindingResult.status === 'fulfilled' ? bindingResult.value : []
  if (clusterResult.status === 'fulfilled') {
    clusters.value = clusterResult.value
    clusterHint.value = ''
  } else {
    clusters.value = []
    clusterHint.value = '拿不到集群列表（可能是没有 cluster:read 权限），发布时需要管理员代为操作'
  }
  if (diffResult.status === 'rejected') notifyError(diffResult.reason)
  loading.value = false
}

function openPublish(binding?: BindingView): void {
  publishDialog.clusterId = binding?.clusterId
  publishDialog.note = ''
  publishDialog.visible = true
}

async function submitPublish(): Promise<void> {
  if (publishDialog.clusterId == null) {
    ElMessage.warning('请选择目标集群')
    return
  }
  publishDialog.saving = true
  try {
    const result = await serverApi.publish(props.serverId, {
      clusterId: publishDialog.clusterId,
      note: publishDialog.note.trim() || undefined
    })
    publishDialog.visible = false
    ElMessage.success(
      `已发布到 ${result.clusterName}：版本 v${result.version}，集群快照 revision ${result.revision}`
    )
    emit('changed')
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    publishDialog.saving = false
  }
}

async function offline(binding: BindingView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `下线后 ${binding.endpoint} 会立刻从集群快照中移除，已配置该端点的 MCP Client 将收到 404。`,
      `从 ${binding.clusterName} 下线`,
      { type: 'warning', confirmButtonText: '确认下线', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const result = await serverApi.offline(props.serverId, binding.clusterId)
    ElMessage.success(result.message ?? '已下线')
    emit('changed')
    await load()
  } catch (error) {
    notifyError(error)
  }
}

async function openRollback(binding: BindingView): Promise<void> {
  rollbackDialog.clusterId = binding.clusterId
  rollbackDialog.clusterName = binding.clusterName
  rollbackDialog.version = undefined
  rollbackDialog.versions = []
  rollbackDialog.visible = true
  try {
    rollbackDialog.versions = await serverApi.publishHistory(props.serverId, binding.clusterId)
  } catch (error) {
    notifyError(error)
  }
}

async function submitRollback(): Promise<void> {
  if (rollbackDialog.version == null) {
    ElMessage.warning('请选择要回滚到的版本')
    return
  }
  rollbackDialog.saving = true
  try {
    const result = await serverApi.rollback(props.serverId, rollbackDialog.clusterId, {
      version: rollbackDialog.version
    })
    rollbackDialog.visible = false
    ElMessage.success(`已回滚：以 v${rollbackDialog.version} 的内容创建了新版本 v${result.version}`)
    emit('changed')
    await load()
  } catch (error) {
    notifyError(error)
  } finally {
    rollbackDialog.saving = false
  }
}

async function copy(text?: string): Promise<void> {
  if (!text) return
  if (await copyText(text)) ElMessage.success('已复制')
  else ElMessage.error('复制失败，请手动选择文本')
}

function formatValue(value: unknown): string {
  if (value == null) return '—'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

/** 下面两个取数写在方法里而不是模板里：模板表达式里做类型断言很难读，也不利于以后改类型。 */
function paramCount(tool: { inputSchema?: Record<string, unknown> }): number {
  const properties = tool.inputSchema?.properties
  return properties && typeof properties === 'object' ? Object.keys(properties).length : 0
}

function overriddenCount(diff: { fields: { overridden: boolean }[] }): number {
  return diff.fields.filter((field) => field.overridden).length
}

/** Prompt 参数摘要：必填带 `*`。快照里 `arguments` 可能缺省，兜住 undefined。 */
function argumentSummary(prompt: { arguments?: { name: string; required: boolean }[] }): string {
  const args = prompt.arguments ?? []
  if (args.length === 0) return '—'
  return args.map((arg) => (arg.required ? `${arg.name}*` : arg.name)).join('、')
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div v-loading="loading">
    <div class="toolbar">
      <el-button type="primary" :disabled="!canPublish || clusters.length === 0" @click="openPublish()">
        发布到集群
      </el-button>
      <el-button @click="load">刷新</el-button>
      <span v-if="clusterHint" class="muted">{{ clusterHint }}</span>
      <el-tag class="right" :type="statusTag(server.status)" size="small">
        当前状态 {{ server.status }}
      </el-tag>
    </div>

    <el-alert type="info" :closable="false" show-icon class="tip">
      <template #title>发布前后端会拦下这几种情况，与其失败后猜原因不如先看一眼</template>
      <template #default>
        <ul class="checklist">
          <li>协议版本必须是 <span class="mono">{{ server.protocolVersion }}</span>（决策 D1：只支持 Modern）</li>
          <li>至少配置一个服务地址，否则 Executor 无法转发调用</li>
          <li>至少有一个启用状态的 tool，否则发布后 tools/list 是空的</li>
          <li>生效 tool 名不能重复（覆盖改名容易撞车）</li>
          <li>PATH 末段合法且没有被多个 Server 占用（BR-3，写入时已校验，发布时再确认一次防并发）</li>
        </ul>
      </template>
    </el-alert>

    <el-card shadow="never" class="block">
      <template #header><span>生效模型（Executor 实际会加载的内容）</span></template>
      <template v-if="effectiveModel">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="name"><span class="mono">{{ effectiveModel.name }}</span></el-descriptions-item>
          <el-descriptions-item label="PATH 末段">
            <span class="mono">{{ effectiveModel.pathSegment }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="协议版本">
            <span class="mono">{{ effectiveModel.protocolVersion }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="展示名">{{ effectiveModel.title || '—' }}</el-descriptions-item>
          <el-descriptions-item label="版本">{{ effectiveModel.version || '—' }}</el-descriptions-item>
          <el-descriptions-item label="list TTL">{{ effectiveModel.listTtlMs }} ms</el-descriptions-item>
          <el-descriptions-item label="描述" :span="3">
            {{ effectiveModel.description || '—' }}
          </el-descriptions-item>
        </el-descriptions>
        <el-table :data="effectiveModel.tools" border size="small" class="inner">
          <el-table-column prop="name" label="tool 名" min-width="180">
            <template #default="{ row }"><span class="mono">{{ row.name }}</span></template>
          </el-table-column>
          <el-table-column label="REST 服务" min-width="220">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ row.method }}</el-tag>
              <span class="mono small path">{{ row.path }}</span>
            </template>
          </el-table-column>
          <el-table-column label="参数数" width="90" align="center">
            <template #default="{ row }">{{ paramCount(row) }}</template>
          </el-table-column>
          <el-table-column label="流式" width="80" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.streaming" type="warning" size="small" effect="plain">是</el-tag>
              <span v-else class="muted">否</span>
            </template>
          </el-table-column>
        </el-table>
        <p class="muted hint">
          这里只包含启用状态的 tool。流式 tool 即使在列表里，Executor 也不会把它放进 tools/list。
        </p>

        <h4>Resource（{{ effectiveModel.resources?.length ?? 0 }}）</h4>
        <el-table :data="effectiveModel.resources" border size="small" class="inner">
          <el-table-column label="URI" min-width="220">
            <template #default="{ row }"><span class="mono">{{ row.uri }}</span></template>
          </el-table-column>
          <el-table-column label="数据来源" min-width="200">
            <template #default="{ row }">
              <template v-if="row.toolName">
                <el-tag size="small" effect="plain" type="warning">映射 tool</el-tag>
                <span class="mono small path">{{ row.toolName }}</span>
              </template>
              <template v-else>
                <el-tag size="small" effect="plain" type="info">静态内容</el-tag>
                <span class="muted small path">{{ (row.content ?? '').length }} 字符</span>
              </template>
            </template>
          </el-table-column>
          <el-table-column label="mimeType" width="150">
            <template #default="{ row }">
              <span class="mono small">{{ row.mimeType || 'text/plain' }}</span>
            </template>
          </el-table-column>
          <template #empty><el-empty description="快照里没有 Resource" :image-size="60" /></template>
        </el-table>
        <p class="muted hint">
          映射的 tool 若被停用或删除，对应 Resource 会被发布流程<b>跳过</b>（不阻断发布）——
          对着这张表确认一次，比发完再被客户端问「资源怎么没了」强。
        </p>

        <h4>Prompt（{{ effectiveModel.prompts?.length ?? 0 }}）</h4>
        <el-table :data="effectiveModel.prompts" border size="small" class="inner">
          <el-table-column label="Prompt 名" min-width="180">
            <template #default="{ row }"><span class="mono">{{ row.name }}</span></template>
          </el-table-column>
          <el-table-column label="参数（* = 必填）" min-width="220">
            <template #default="{ row }"><span class="mono small">{{ argumentSummary(row) }}</span></template>
          </el-table-column>
          <el-table-column label="描述" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ row.description || '—' }}</template>
          </el-table-column>
          <template #empty><el-empty description="快照里没有 Prompt" :image-size="60" /></template>
        </el-table>
        <p class="muted hint">
          快照里存的是<b>模板原文</b>（含 <span class="mono">占位符</span>），参数由客户端在
          <span class="mono">prompts/get</span> 时传入、Executor 侧渲染。
        </p>
      </template>
      <el-empty v-else description="拿不到生效模型" :image-size="60" />
    </el-card>

    <el-card shadow="never" class="block">
      <template #header><span>基座 ⊕ 覆盖 差异</span></template>
      <template v-if="diff">
        <el-alert v-if="diff.suspendedOverlays.length > 0" type="error" :closable="false" show-icon class="tip">
          <template #title>{{ diff.suspendedOverlays.length }} 条覆盖因锚点失效被挂起，不会进入生效模型</template>
          <template #default>
            <span v-for="anchor in diff.suspendedOverlays" :key="anchor" class="mono chip">{{ anchor }}</span>
          </template>
        </el-alert>

        <h4>Server 级字段</h4>
        <el-table :data="diff.serverFields" border size="small">
          <el-table-column prop="field" label="字段" width="160" />
          <el-table-column label="基座值" min-width="220">
            <template #default="{ row }"><span class="mono small">{{ formatValue(row.baseValue) }}</span></template>
          </el-table-column>
          <el-table-column label="生效值" min-width="220">
            <template #default="{ row }"><span class="mono small">{{ formatValue(row.effectiveValue) }}</span></template>
          </el-table-column>
          <el-table-column label="已覆盖" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="row.overridden ? 'success' : 'info'" size="small" effect="plain">
                {{ row.overridden ? '是' : '否' }}
              </el-tag>
            </template>
          </el-table-column>
          <template #empty><span class="muted">Server 级字段全部使用基座值</span></template>
        </el-table>

        <h4>Tool 级字段</h4>
        <el-table :data="diff.tools" border size="small">
          <el-table-column type="expand">
            <template #default="{ row }">
              <el-table :data="row.fields" border size="small" class="nested">
                <el-table-column prop="field" label="字段" width="160" />
                <el-table-column label="基座值" min-width="240">
                  <template #default="inner"><span class="mono small">{{ formatValue(inner.row.baseValue) }}</span></template>
                </el-table-column>
                <el-table-column label="生效值" min-width="240">
                  <template #default="inner"><span class="mono small">{{ formatValue(inner.row.effectiveValue) }}</span></template>
                </el-table-column>
                <el-table-column label="已覆盖" width="90" align="center">
                  <template #default="inner">{{ inner.row.overridden ? '是' : '否' }}</template>
                </el-table-column>
              </el-table>
            </template>
          </el-table-column>
          <el-table-column prop="anchor" label="锚点" min-width="200">
            <template #default="{ row }"><span class="mono small">{{ row.anchor }}</span></template>
          </el-table-column>
          <el-table-column label="tool 名" min-width="200">
            <template #default="{ row }">
              <span class="mono">{{ row.effectiveName }}</span>
              <span v-if="row.baseName !== row.effectiveName" class="muted small"> ← {{ row.baseName }}</span>
            </template>
          </el-table-column>
          <el-table-column label="覆盖字段数" width="110" align="center">
            <template #default="{ row }">{{ overriddenCount(row) }}</template>
          </el-table-column>
          <template #empty><span class="muted">没有 tool 被覆盖</span></template>
        </el-table>
      </template>
      <el-empty v-else description="拿不到差异数据" :image-size="60" />
    </el-card>

    <el-card shadow="never" class="block">
      <template #header><span>发布绑定（一个 Server 可以发布到多个集群）</span></template>
      <el-table :data="bindings" border size="small">
        <el-table-column label="集群" min-width="150">
          <template #default="{ row }">
            {{ row.clusterName }}
            <el-tag v-if="row.clusterType" size="small" effect="plain">
              {{ labelOf(CLUSTER_TYPE_LABEL, row.clusterType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="80" align="center">
          <template #default="{ row }">v{{ row.version }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.state)" size="small">{{ labelOf(BINDING_STATE_LABEL, row.state) }}</el-tag>
            <el-tag v-if="row.current" type="primary" size="small" effect="plain">当前</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="端点" min-width="260">
          <template #default="{ row }">
            <template v-if="row.endpoint">
              <span class="mono small">{{ row.endpoint }}</span>
              <el-button link type="primary" size="small" @click="copy(row.endpoint)">复制</el-button>
            </template>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="toolCount" label="Tool" width="70" align="center" />
        <el-table-column label="发布时间" width="170">
          <template #default="{ row }">{{ formatDateTime(row.publishedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.state === 'PUBLISHED' && row.current && row.endpoint"
              link
              type="success"
              size="small"
              @click="openMcpConfig(row)"
            >
              MCP 配置
            </el-button>
            <el-button link type="primary" size="small" :disabled="!canPublish" @click="openPublish(row)">
              重新发布
            </el-button>
            <el-button
              link
              type="warning"
              size="small"
              :disabled="!canPublish || !row.current || row.state !== 'PUBLISHED'"
              @click="offline(row)"
            >
              下线
            </el-button>
            <el-button link type="danger" size="small" :disabled="!canRollback" @click="openRollback(row)">
              回滚
            </el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="还没有发布记录" :image-size="60" /></template>
      </el-table>

      <div v-for="binding in failedBindings" :key="`fail-${binding.id}`" class="failure">
        <el-alert type="error" :closable="false" show-icon>
          <template #title>
            {{ binding.clusterName }} v{{ binding.version }} 发布失败：{{ binding.failureReason }}
          </template>
        </el-alert>
      </div>

      <p class="muted hint">
        当前生效的绑定共 {{ currentBindings.length }} 条。Executor 通过轮询拉取集群快照，
        发布与下线的传播上界约 30 秒；传播完成前旧端点仍可能可用。
      </p>
    </el-card>

    <el-dialog v-model="publishDialog.visible" title="发布到集群" width="560px">
      <el-form label-width="90px" @submit.prevent="submitPublish">
        <el-form-item label="目标集群">
          <el-select v-model="publishDialog.clusterId" placeholder="选择集群" style="width: 100%">
            <el-option
              v-for="cluster in clusters"
              :key="cluster.id"
              :label="`${cluster.name}（${labelOf(CLUSTER_TYPE_LABEL, cluster.type)}，在线 ${cluster.onlineNodeCount}/${cluster.nodeCount}）`"
              :value="cluster.id"
              :disabled="!cluster.enabled"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="publishDialog.note" type="textarea" :rows="2" placeholder="会记入审计，建议写清本次改动" />
        </el-form-item>
      </el-form>
      <p class="muted hint">
        发布是幂等的：同一个 (Server, 集群) 下再次发布会生成一个新的版本号，旧版本保留下来可供回滚。
      </p>
      <template #footer>
        <el-button @click="publishDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="publishDialog.saving" @click="submitPublish">确认发布</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="rollbackDialog.visible" :title="`回滚 ${rollbackDialog.clusterName}`" width="620px">
      <el-alert type="info" :closable="false" show-icon class="tip">
        <template #title>回滚不是把版本号往回拨，而是用历史快照的内容创建一个新版本</template>
        <template #default>
          历史版本一条都不会被删除，所以回滚本身也可以再回滚。这样审计链是连续的，
          不会出现「版本号 5 的内容前后不一样」这种没法追查的情况。
        </template>
      </el-alert>
      <el-form label-width="90px" @submit.prevent="submitRollback">
        <el-form-item label="回滚到">
          <el-select v-model="rollbackDialog.version" placeholder="选择历史版本" style="width: 100%">
            <el-option
              v-for="item in rollbackDialog.versions"
              :key="item.id"
              :label="`v${item.version} · ${labelOf(BINDING_STATE_LABEL, item.state)} · ${formatDateTime(item.publishedAt)} · ${item.toolCount} tools`"
              :value="item.version"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rollbackDialog.visible = false">取消</el-button>
        <el-button type="danger" :loading="rollbackDialog.saving" @click="submitRollback">确认回滚</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="mcpDialog.visible" title="MCP 客户端配置" width="680px" top="6vh">
      <template v-if="mcpDialog.binding">
        <el-alert type="info" :closable="false" show-icon class="tip">
          <template #title>
            {{ mcpDialog.binding.clusterName }} · v{{ mcpDialog.binding.version }}：{{ mcpDialog.binding.endpoint }}
          </template>
          <template #default>
            平台端点仅支持 Streamable HTTP 传输（POST、无会话，协议规范 2026-07-28），不支持 SSE / stdio。
            把 JSON 粘进支持远程 MCP 的客户端（Claude Desktop / Cursor 等）的 mcpServers 配置即可；
            配置 key 取 PATH 末段「{{ props.server.pathSegment }}」（全局唯一），多个 Server 可直接合并。
          </template>
        </el-alert>

        <el-alert v-if="authDMode === 'OAUTH2'" type="warning" :closable="false" show-icon class="tip">
          <template #title>该 Server 配置了 OAuth 2.1 下行鉴权，属 P1 能力，Executor 当前会返回 501</template>
          <template #default>请先在「MCP 客户端授权 Auth-D」改为 STATIC_BEARER 或 NONE，否则下方配置无法连通。</template>
        </el-alert>

        <el-form v-if="tokenRequired" label-width="96px" class="token-form">
          <el-form-item label="Auth-D 令牌">
            <el-input
              v-model="mcpDialog.token"
              type="password"
              show-password
              placeholder="粘贴配置 Auth-D 时设置的静态令牌"
              @keyup.enter="copyMcpJson"
            />
          </el-form-item>
          <p class="muted hint">
            静态令牌只以 sha256 存库、平台无法回显。在此粘贴仅用于当场拼装配置并复制到剪贴板，不保存、不上传。
          </p>
        </el-form>

        <el-tabs v-model="mcpDialog.activeTab">
          <el-tab-pane label="mcpServers JSON" name="json">
            <div class="code-head">
              <span class="muted">替换目标客户端配置中的 mcpServers 对象即可</span>
              <el-button link type="primary" size="small" @click="copyMcpJson">复制 JSON</el-button>
            </div>
            <pre class="code">{{ mcpConfigJson() }}</pre>
          </el-tab-pane>
          <el-tab-pane label="curl 冒烟" name="curl">
            <div class="code-head">
              <span class="muted">粘贴到终端验证连通，应返回 server/discover 能力清单</span>
              <el-button link type="primary" size="small" @click="copyMcpCurl">复制 curl</el-button>
            </div>
            <pre class="code">{{ mcpCurl() }}</pre>
          </el-tab-pane>
        </el-tabs>
      </template>
      <template #footer>
        <el-button @click="mcpDialog.visible = false">关闭</el-button>
        <el-button type="primary" :disabled="!copyable()" @click="copyMcpJson">复制 JSON</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}

.toolbar .right {
  margin-left: auto;
}

.tip {
  margin-bottom: 12px;
}

.block {
  margin-bottom: 12px;
}

.block h4 {
  margin: 16px 0 8px;
  font-size: 13px;
}

.inner {
  margin-top: 12px;
}

.nested {
  margin: 8px 0 8px 48px;
}

.checklist {
  margin: 0;
  padding-left: 18px;
  font-size: 12px;
  line-height: 1.8;
}

.chip {
  display: inline-block;
  padding: 1px 6px;
  margin: 2px 4px 2px 0;
  font-size: 12px;
  background: #fef0f0;
  border-radius: 3px;
}

.path {
  margin-left: 6px;
}

.code-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.code {
  margin: 0;
  padding: 10px 12px;
  max-height: 300px;
  overflow: auto;
  font-family: 'JetBrains Mono', Consolas, Menlo, monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre;
  color: var(--el-text-color-primary);
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.token-form {
  margin-top: 12px;
}

.token-form .hint {
  margin: -6px 0 0;
}

.failure {
  margin-top: 8px;
}

.hint {
  font-size: 12px;
  line-height: 1.6;
}

.small {
  font-size: 12px;
}
</style>