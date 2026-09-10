<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { notifyError } from '@/api/http'
import * as serverApi from '@/api/server'
import type {
  PromptArgumentRequest,
  PromptRequest,
  PromptView,
  ResourceRequest,
  ResourceView,
  ToolView
} from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useMetaStore } from '@/stores/meta'

/**
 * Resource 与 Prompt（SVR-05/06）。
 *
 * 这两类是 Server 对 MCP Client 声明的「非工具能力」：Resource 是可读取的数据（契约文档、
 * 枚举字典），Prompt 是可参数化的提示词模板。它们和 tool 一样属于发布快照的一部分——
 * 控制面改完必须重新发布，客户端才看得到。
 *
 * 与 tool 覆盖不同，这两类对象**没有覆盖层**：改就是改，没有「基座值 vs 生效值」的区分，
 * 所以表单每次提交都是整份替换（清空某个字段就是真的清空）。
 */
const props = defineProps<{ serverId: number }>()
const emit = defineEmits<{ changed: [] }>()

const auth = useAuthStore()
const metaStore = useMetaStore()
const canWrite = computed(() => auth.can('tool:write'))

const innerTab = ref<'resource' | 'prompt'>('resource')

const resources = ref<ResourceView[]>([])
const prompts = ref<PromptView[]>([])
const tools = ref<ToolView[]>([])
const loading = ref(false)

function compile(pattern?: string): RegExp | null {
  if (!pattern) return null
  try {
    return new RegExp(pattern)
  } catch {
    return null
  }
}

const resourceUriPattern = computed<RegExp | null>(() => compile(metaStore.meta?.resourceUriPattern))
const promptNamePattern = computed<RegExp | null>(() => compile(metaStore.meta?.promptNamePattern))

/** 只有「启用的」tool 会进快照：映射到停用 tool 的 resource 会被发布流程静默跳过。 */
const enabledTools = computed(() => tools.value.filter((tool) => tool.enabled))
const enabledToolIds = computed(() => new Set(enabledTools.value.map((tool) => tool.id)))

/**
 * 运行时读 resource 走的是 `toolCallService.call(server, tool, null)` —— **不带任何参数**。
 * 所以带路径参数的 tool 或需要请求体的 tool 映射过来必然失败，这里直接标成不可选。
 */
function callableWithoutArgs(tool: ToolView): boolean {
  return tool.method === 'GET' && !tool.path.includes('{') && !tool.requestBodyRequired
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const [resourceList, promptList, toolList] = await Promise.all([
      serverApi.resources(props.serverId),
      serverApi.prompts(props.serverId),
      serverApi.tools(props.serverId)
    ])
    resources.value = resourceList
    prompts.value = promptList
    tools.value = toolList
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

// ---------------------------------------------------------------- Resource

type SourceKind = 'STATIC' | 'TOOL'

const resourceDialog = reactive({
  visible: false,
  saving: false,
  editingId: null as number | null,
  uri: '',
  name: '',
  description: '',
  mimeType: '',
  ttlMs: undefined as number | undefined,
  sourceKind: 'STATIC' as SourceKind,
  content: '',
  toolId: undefined as number | undefined
})

function openResource(row?: ResourceView): void {
  resourceDialog.editingId = row?.id ?? null
  resourceDialog.uri = row?.uri ?? ''
  resourceDialog.name = row?.name ?? ''
  resourceDialog.description = row?.description ?? ''
  resourceDialog.mimeType = row?.mimeType ?? ''
  resourceDialog.ttlMs = row?.ttlMs ?? undefined
  // 有 toolId 就是「映射 tool」，否则是静态内容。两者互斥，由后端在保存时校验。
  resourceDialog.sourceKind = row?.toolId ? 'TOOL' : 'STATIC'
  resourceDialog.content = row?.content ?? ''
  resourceDialog.toolId = row?.toolId ?? undefined
  resourceDialog.visible = true
}

/** 映射目标已失效（tool 被删）：发布快照会跳过它，必须在列表上显式提示。 */
function mappingBroken(row: ResourceView): boolean {
  return row.toolId != null && !row.toolName
}

function mappingSkipped(row: ResourceView): boolean {
  return row.toolId != null && !enabledToolIds.value.has(row.toolId)
}

async function submitResource(): Promise<void> {
  const uri = resourceDialog.uri.trim()
  if (uri === '') {
    ElMessage.warning('Resource URI 不能为空')
    return
  }
  const pattern = resourceUriPattern.value
  if (pattern && !pattern.test(uri)) {
    ElMessage.warning(`URI 必须带 scheme（如 mcp://crm-order/schema），规则 ${pattern.source}`)
    return
  }

  const isStatic = resourceDialog.sourceKind === 'STATIC'
  if (isStatic && resourceDialog.content.trim() === '') {
    ElMessage.warning('静态内容不能为空：没有内容来源的 resource 在 resources/read 时必然失败')
    return
  }
  if (!isStatic && resourceDialog.toolId === undefined) {
    ElMessage.warning('请选择要映射的 tool')
    return
  }

  const request: ResourceRequest = {
    uri,
    name: resourceDialog.name.trim() || undefined,
    description: resourceDialog.description.trim() || undefined,
    mimeType: resourceDialog.mimeType.trim() || undefined,
    // 静态内容原样提交（不 trim），只把「空」解释为「不填这一项」
    content: isStatic ? resourceDialog.content : undefined,
    toolId: isStatic ? undefined : resourceDialog.toolId,
    ttlMs: resourceDialog.ttlMs ?? null
  }

  resourceDialog.saving = true
  try {
    if (resourceDialog.editingId === null) {
      await serverApi.createResource(props.serverId, request)
    } else {
      await serverApi.updateResource(props.serverId, resourceDialog.editingId, request)
    }
    resourceDialog.visible = false
    await load()
    emit('changed')
    ElMessage.success('已保存，重新发布后对 MCP Client 生效')
  } catch (error) {
    notifyError(error)
  } finally {
    resourceDialog.saving = false
  }
}

async function removeResource(row: ResourceView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `将删除 resource ${row.uri}，重新发布后客户端不再能读到它。此操作会记入审计。`,
      '删除 Resource',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await serverApi.deleteResource(props.serverId, row.id)
    await load()
    emit('changed')
    ElMessage.success('已删除，重新发布后对 MCP Client 生效')
  } catch (error) {
    notifyError(error)
  }
}

// ---------------------------------------------------------------- Prompt

/** 与后端 PromptTemplate.PLACEHOLDER 同一条规则：`{{ name }}`，name 允许字母数字、_ . - */
const PLACEHOLDER = /\{\{\s*([A-Za-z0-9_.-]+)\s*\}\}/g
const ARGUMENT_NAME = /^[A-Za-z0-9_.-]{1,64}$/

/**
 * 模板里字面量 `{{...}}` 只能从脚本里带进模板。
 * 直接写进模板会被 Vue 的插值分隔符吃掉，写成 HTML 实体又会在编译期解码顺序上留下疑问，
 * 用常量最省心。
 */
const PLACEHOLDER_EXAMPLE = '{{orderId}}'
const BRACES = '{{...}}'
const TEMPLATE_PLACEHOLDER_HINT = [
  '请基于下面这个订单的信息做一次风险复盘：',
  '订单号：{{orderId}}',
  '金额：{{amount}}',
  '输出：风险等级 + 三条建议'
].join('\n')

const promptDialog = reactive({
  visible: false,
  saving: false,
  editingId: null as number | null,
  name: '',
  title: '',
  description: '',
  template: '',
  ttlMs: undefined as number | undefined,
  args: [] as PromptArgumentRequest[]
})

/** 模板里出现的占位符，按首次出现顺序去重。 */
const templatePlaceholders = computed<string[]>(() => {
  const found: string[] = []
  const seen = new Set<string>()
  for (const match of promptDialog.template.matchAll(PLACEHOLDER)) {
    const name = match[1]
    if (!seen.has(name)) {
      seen.add(name)
      found.push(name)
    }
  }
  return found
})

const declaredArguments = computed(() =>
  promptDialog.args.map((arg) => arg.name.trim()).filter((name) => name !== '')
)

/**
 * 双向不一致。后端两个方向都会拒绝，这里提前把问题摆在输入框下面，
 * 而不是等用户点了保存再收到一句「模板与参数声明不一致」。
 */
const undeclaredPlaceholders = computed(() =>
  templatePlaceholders.value.filter((name) => !declaredArguments.value.includes(name))
)
const unusedArguments = computed(() =>
  declaredArguments.value.filter((name) => !templatePlaceholders.value.includes(name))
)

function openPrompt(row?: PromptView): void {
  promptDialog.editingId = row?.id ?? null
  promptDialog.name = row?.name ?? ''
  promptDialog.title = row?.title ?? ''
  promptDialog.description = row?.description ?? ''
  promptDialog.template = row?.template ?? ''
  promptDialog.ttlMs = row?.ttlMs ?? undefined
  promptDialog.args = (row?.arguments ?? []).map((arg) => ({
    name: arg.name,
    description: arg.description ?? '',
    required: arg.required
  }))
  promptDialog.visible = true
}

function addArgument(): void {
  promptDialog.args.push({ name: '', description: '', required: false })
}

function removeArgument(index: number): void {
  promptDialog.args.splice(index, 1)
}

/** 把参数名写进模板。手打 `{{orderId}}` 是最容易拼错的地方，能点就别打。 */
function insertPlaceholder(name: string): void {
  const argumentName = name.trim()
  if (argumentName === '') {
    ElMessage.info('请先填写参数名')
    return
  }
  promptDialog.template += `{{${argumentName}}}`
}

function argumentsSummary(row: PromptView): string {
  const args = row.arguments ?? []
  if (args.length === 0) return '无'
  return args.map((arg) => (arg.required ? `${arg.name}*` : arg.name)).join('、')
}

async function submitPrompt(): Promise<void> {
  const name = promptDialog.name.trim()
  if (name === '') {
    ElMessage.warning('Prompt 名不能为空')
    return
  }
  const pattern = promptNamePattern.value
  if (pattern && !pattern.test(name)) {
    ElMessage.warning(`Prompt 名不满足规则 ${pattern.source}`)
    return
  }

  const args: PromptArgumentRequest[] = []
  for (const raw of promptDialog.args) {
    const argumentName = raw.name.trim()
    if (argumentName === '') continue
    if (!ARGUMENT_NAME.test(argumentName)) {
      ElMessage.warning(`参数名「${argumentName}」只能包含字母、数字、下划线、点、连字符，且不超过 64 字符`)
      return
    }
    if (args.some((arg) => arg.name === argumentName)) {
      ElMessage.warning(`参数名「${argumentName}」重复`)
      return
    }
    args.push({
      name: argumentName,
      description: raw.description?.trim() || undefined,
      required: raw.required
    })
  }

  // 空名的参数行被静默忽略，但用户可能以为它生效了——提示一句比让人对着模板猜好
  if (args.length !== promptDialog.args.length) {
    ElMessage.warning('有参数行的名字为空，已忽略；请补全名字或删除该行')
    return
  }

  if (undeclaredPlaceholders.value.length > 0) {
    ElMessage.warning(`模板里的占位符没有声明为参数：${undeclaredPlaceholders.value.join('、')}`)
    return
  }
  if (unusedArguments.value.length > 0) {
    ElMessage.warning(`以下参数声明了但没有在模板里使用：${unusedArguments.value.join('、')}`)
    return
  }

  const request: PromptRequest = {
    name,
    title: promptDialog.title.trim() || undefined,
    description: promptDialog.description.trim() || undefined,
    // 模板原样提交：trim 会把有意义的尾随换行吃掉
    template: promptDialog.template === '' ? undefined : promptDialog.template,
    arguments: args,
    ttlMs: promptDialog.ttlMs ?? null
  }

  promptDialog.saving = true
  try {
    if (promptDialog.editingId === null) {
      await serverApi.createPrompt(props.serverId, request)
    } else {
      await serverApi.updatePrompt(props.serverId, promptDialog.editingId, request)
    }
    promptDialog.visible = false
    await load()
    emit('changed')
    ElMessage.success('已保存，重新发布后对 MCP Client 生效')
  } catch (error) {
    notifyError(error)
  } finally {
    promptDialog.saving = false
  }
}

async function removePrompt(row: PromptView): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `将删除 prompt ${row.name}，重新发布后客户端不再能取到它。此操作会记入审计。`,
      '删除 Prompt',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await serverApi.deletePrompt(props.serverId, row.id)
    await load()
    emit('changed')
    ElMessage.success('已删除，重新发布后对 MCP Client 生效')
  } catch (error) {
    notifyError(error)
  }
}

onMounted(() => {
  void metaStore.load()
  void load()
})
</script>

<template>
  <div v-loading="loading">
    <el-alert type="info" :closable="false" show-icon class="tip">
      <template #title>Resource 与 Prompt 是随发布快照下发的「非工具能力」</template>
      <template #default>
        它们在控制面改完后<b>必须重新发布</b>才进快照——客户端 <span class="mono">resources/list</span> /
        <span class="mono">prompts/list</span> 读的是节点上那份快照，不是这里的表。
        权限与 Tool 共用（读 <span class="mono">server:read</span>，写 <span class="mono">tool:write</span>）。
      </template>
    </el-alert>

    <el-tabs v-model="innerTab" type="card">
      <!-- ------------------------------------------------ Resource -->
      <el-tab-pane name="resource">
        <template #label>Resource（{{ resources.length }}）</template>

        <div class="toolbar">
          <el-button type="primary" :disabled="!canWrite" @click="openResource()">新增 Resource</el-button>
          <span class="muted">URI 在本 Server 内唯一</span>
          <el-button class="right" @click="load">刷新</el-button>
        </div>

        <el-table :data="resources" border stripe size="small">
          <el-table-column label="URI" min-width="240">
            <template #default="{ row }: { row: ResourceView }">
              <span class="mono">{{ row.uri }}</span>
              <div v-if="row.name" class="muted small">{{ row.name }}</div>
            </template>
          </el-table-column>
          <el-table-column label="数据来源" min-width="220">
            <template #default="{ row }: { row: ResourceView }">
              <template v-if="row.toolId != null">
                <el-tag size="small" effect="plain" type="warning">映射 tool</el-tag>
                <span class="mono small path">{{ row.toolName ?? `#${row.toolId}` }}</span>
                <div v-if="mappingBroken(row)" class="small danger">
                  tool 已不存在，发布快照会跳过这条 resource
                </div>
                <div v-else-if="mappingSkipped(row)" class="small danger">
                  tool 已停用，发布快照会跳过这条 resource
                </div>
              </template>
              <template v-else>
                <el-tag size="small" effect="plain" type="info">静态内容</el-tag>
                <span class="muted small path">{{ (row.content ?? '').length }} 字符</span>
              </template>
            </template>
          </el-table-column>
          <el-table-column label="描述" min-width="200" show-overflow-tooltip>
            <template #default="{ row }: { row: ResourceView }">{{ row.description || '—' }}</template>
          </el-table-column>
          <el-table-column label="mimeType" width="150">
            <template #default="{ row }: { row: ResourceView }">
              <span class="mono small">{{ row.mimeType || 'text/plain' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="TTL" width="90">
            <template #default="{ row }: { row: ResourceView }">
              <span class="muted small">{{ row.ttlMs == null ? '继承' : `${row.ttlMs} ms` }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }: { row: ResourceView }">
              <el-button link type="primary" size="small" @click="openResource(row)">编辑</el-button>
              <el-button link type="danger" size="small" :disabled="!canWrite" @click="removeResource(row)">
                删除
              </el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="还没有 Resource。客户端 resources/list 会返回空列表" />
          </template>
        </el-table>
      </el-tab-pane>

      <!-- ------------------------------------------------ Prompt -->
      <el-tab-pane name="prompt">
        <template #label>Prompt（{{ prompts.length }}）</template>

        <div class="toolbar">
          <el-button type="primary" :disabled="!canWrite" @click="openPrompt()">新增 Prompt</el-button>
          <span class="muted">模板占位符与参数声明必须双向一致</span>
          <el-button class="right" @click="load">刷新</el-button>
        </div>

        <el-table :data="prompts" border stripe size="small">
          <el-table-column label="Prompt 名" min-width="200">
            <template #default="{ row }: { row: PromptView }">
              <span class="mono">{{ row.name }}</span>
              <div v-if="row.title" class="muted small">{{ row.title }}</div>
            </template>
          </el-table-column>
          <el-table-column label="描述" min-width="200" show-overflow-tooltip>
            <template #default="{ row }: { row: PromptView }">{{ row.description || '—' }}</template>
          </el-table-column>
          <el-table-column label="参数" min-width="180">
            <template #default="{ row }: { row: PromptView }">
              <span class="mono small">{{ argumentsSummary(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="模板" min-width="240" show-overflow-tooltip>
            <template #default="{ row }: { row: PromptView }">
              <span class="mono small">{{ (row.template ?? '').replace(/\s+/g, ' ').slice(0, 120) || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="TTL" width="90">
            <template #default="{ row }: { row: PromptView }">
              <span class="muted small">{{ row.ttlMs == null ? '继承' : `${row.ttlMs} ms` }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }: { row: PromptView }">
              <el-button link type="primary" size="small" @click="openPrompt(row)">编辑</el-button>
              <el-button link type="danger" size="small" :disabled="!canWrite" @click="removePrompt(row)">
                删除
              </el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty description="还没有 Prompt。客户端 prompts/list 会返回空列表" />
          </template>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- ------------------------------------------------ Resource 弹窗 -->
    <el-dialog v-model="resourceDialog.visible" title="Resource" width="720px" top="6vh">
      <el-form label-width="110px" @submit.prevent="submitResource">
        <el-form-item label="URI">
          <el-input v-model="resourceDialog.uri" maxlength="512" placeholder="mcp://crm-order/schema" :disabled="!canWrite" />
          <div class="hint muted">
            规则 <span class="mono">{{ resourceUriPattern?.source ?? '由后端校验' }}</span>；
            必须带 scheme——把「订单 schema」这种自然语言当 URI 存进去，客户端拿到也没法寻址。
            同一 Server 内不能重复。
          </div>
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="resourceDialog.name" maxlength="128" :disabled="!canWrite" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="resourceDialog.description"
            type="textarea"
            :rows="2"
            maxlength="2000"
            show-word-limit
            :disabled="!canWrite"
          />
        </el-form-item>
        <el-form-item label="mimeType">
          <el-input v-model="resourceDialog.mimeType" maxlength="128" placeholder="application/json" :disabled="!canWrite" />
          <div class="hint muted">留空时按 <span class="mono">text/plain</span> 下发。</div>
        </el-form-item>

        <el-form-item label="数据来源">
          <el-radio-group v-model="resourceDialog.sourceKind" :disabled="!canWrite">
            <el-radio-button value="STATIC">静态内容</el-radio-button>
            <el-radio-button value="TOOL">映射 tool</el-radio-button>
          </el-radio-group>
          <div class="hint muted">
            二者互斥：<b>静态内容</b>适合枚举字典、契约片段这类不常变的东西；
            <b>映射 tool</b> 适合「读一次就等于调一次接口」的场景。
          </div>
        </el-form-item>

        <el-form-item v-if="resourceDialog.sourceKind === 'STATIC'" label="内容">
          <el-input
            v-model="resourceDialog.content"
            type="textarea"
            :rows="10"
            class="mono"
            spellcheck="false"
            maxlength="65536"
            show-word-limit
            :disabled="!canWrite"
          />
          <div class="hint muted">
            这段内容会被固化进<b>每个节点</b>拉取的发布快照，别把大文件塞进来（上限 64KB）。
            内容本身不记审计，只记长度。
          </div>
        </el-form-item>

        <el-form-item v-else label="映射 tool">
          <el-select v-model="resourceDialog.toolId" filterable placeholder="选择要映射的 tool" :disabled="!canWrite">
            <el-option
              v-for="tool in enabledTools"
              :key="tool.id"
              :label="`${tool.effectiveName} · ${tool.method} ${tool.path}`"
              :value="tool.id"
              :disabled="!callableWithoutArgs(tool)"
            >
              <span class="mono">{{ tool.effectiveName }}</span>
              <span class="muted small"> · {{ tool.method }} {{ tool.path }}</span>
              <span v-if="!callableWithoutArgs(tool)" class="small danger">（需要参数，无法映射）</span>
            </el-option>
          </el-select>
          <div class="hint muted">
            读取该 resource 等价于以<b>无参数</b>方式调用这个 tool，所以只列出「已启用 + GET +
            无路径参数 + 无需请求体」的 tool。可选项里带「需要参数」的会在读取时失败。
          </div>
        </el-form-item>

        <el-form-item label="TTL">
          <el-input-number v-model="resourceDialog.ttlMs" :min="0" :controls="false" placeholder="留空 = 继承 Server" :disabled="!canWrite" />
          <div class="hint muted">list 响应的缓存提示（毫秒），留空则用 Server 的 listTtlMs。</div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="resourceDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="resourceDialog.saving" :disabled="!canWrite" @click="submitResource">
          保存
        </el-button>
      </template>
    </el-dialog>

    <!-- ------------------------------------------------ Prompt 弹窗 -->
    <el-dialog v-model="promptDialog.visible" title="Prompt" width="820px" top="5vh">
      <el-form label-width="110px" @submit.prevent="submitPrompt">
        <el-form-item label="Prompt 名">
          <el-input v-model="promptDialog.name" maxlength="128" placeholder="order.review" :disabled="!canWrite" />
          <div class="hint muted">
            规则 <span class="mono">{{ promptNamePattern?.source ?? '由后端校验' }}</span>；
            这是 <span class="mono">prompts/get</span> 的查询键，不参与路由，所以允许写成
            <span class="mono">order.review</span> 这样的层次名。不可重复。
          </div>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="promptDialog.title" maxlength="128" :disabled="!canWrite" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="promptDialog.description"
            type="textarea"
            :rows="2"
            maxlength="2000"
            show-word-limit
            :disabled="!canWrite"
          />
          <div class="hint muted">描述会出现在客户端的 prompt 选择列表里，写清「这个模板适合什么场景」。</div>
        </el-form-item>

        <el-divider content-position="left"><span class="divider-title">参数声明</span></el-divider>

        <el-alert v-if="undeclaredPlaceholders.length > 0" type="error" :closable="false" show-icon class="tip">
          <template #title>模板里的占位符没有声明为参数</template>
          <template #default>
            <span class="mono">{{ undeclaredPlaceholders.join('、') }}</span> ——
            运行时未提供的占位符会被替换成空串，拼错一个字母就等于在提示词里留了个沉默的空洞。
          </template>
        </el-alert>
        <el-alert v-if="unusedArguments.length > 0" type="warning" :closable="false" show-icon class="tip">
          <template #title>以下参数声明了但没有在模板里使用</template>
          <template #default>
            <span class="mono">{{ unusedArguments.join('、') }}</span> ——
            客户端会提示用户填一个对结果毫无影响的值，通常是模板改过但参数没同步清理。
          </template>
        </el-alert>

        <el-table :data="promptDialog.args" border size="small" class="args-table">
          <el-table-column label="参数名" min-width="180">
            <template #default="{ row }: { row: PromptArgumentRequest }">
              <el-input v-model="row.name" maxlength="64" placeholder="orderId" :disabled="!canWrite" />
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="220">
            <template #default="{ row }: { row: PromptArgumentRequest }">
              <el-input v-model="row.description" maxlength="500" :disabled="!canWrite" />
            </template>
          </el-table-column>
          <el-table-column label="必填" width="80" align="center">
            <template #default="{ row }: { row: PromptArgumentRequest }">
              <el-switch v-model="row.required" :disabled="!canWrite" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ $index, row }: { $index: number; row: PromptArgumentRequest }">
              <el-button link type="primary" size="small" :disabled="!canWrite" @click="insertPlaceholder(row.name)">
                插入占位符
              </el-button>
              <el-button link type="danger" size="small" :disabled="!canWrite" @click="removeArgument($index)">
                移除
              </el-button>
            </template>
          </el-table-column>
          <template #empty><el-empty :description="`没有参数。模板里若出现 ${BRACES} 会被拒绝`" :image-size="60" /></template>
        </el-table>
        <el-button class="add-arg" :disabled="!canWrite" @click="addArgument">添加参数</el-button>

        <el-divider content-position="left"><span class="divider-title">模板</span></el-divider>

        <el-form-item label="模板">
          <el-input
            v-model="promptDialog.template"
            type="textarea"
            :rows="12"
            class="mono"
            spellcheck="false"
            maxlength="32000"
            show-word-limit
            :placeholder="TEMPLATE_PLACEHOLDER_HINT"
            :disabled="!canWrite"
          />
          <div class="hint muted">
            占位符写作 <span class="mono">{{ PLACEHOLDER_EXAMPLE }}</span>，允许字母、数字、下划线、点、连字符。
            当前模板识别到：<span class="mono">{{ templatePlaceholders.join('、') || '（无）' }}</span>。
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="promptDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="promptDialog.saving" :disabled="!canWrite" @click="submitPrompt">
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tip {
  margin-bottom: 12px;
}

.toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}

.toolbar .right {
  margin-left: auto;
}

.path {
  margin-left: 6px;
}

.small {
  font-size: 12px;
}

.danger {
  color: var(--el-color-danger);
}

.hint {
  font-size: 12px;
  line-height: 1.6;
}

.divider-title {
  font-size: 13px;
  font-weight: 600;
}

.args-table {
  margin-bottom: 8px;
}

.add-arg {
  margin-bottom: 4px;
}
</style>
