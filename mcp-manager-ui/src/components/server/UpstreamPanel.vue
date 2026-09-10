<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { notifyError } from '@/api/http'
import * as registrationApi from '@/api/registration'
import * as serverApi from '@/api/server'
import AuthBFields from '@/components/server/AuthBFields.vue'
import RegistrationDocActions from '@/components/server/RegistrationDocActions.vue'
import type { LbStrategy, RegistrationView, ServerView, UpstreamView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { authBFormFromView, authBFormToRequest, emptyAuthBForm, validateAuthBForm } from '@/utils/authB'
import { formatDuration, labelOf, LB_STRATEGY_LABEL } from '@/utils/format'
import { joinCsv, splitCsvNumbers, splitLines } from '@/utils/form'

const props = defineProps<{ serverId: number; server: ServerView }>()
const emit = defineEmits<{ saved: [ServerView] }>()

const auth = useAuthStore()

// ---- 注册文档到本 Server ----
const registerDialog = reactive({
  visible: false,
  mode: 'url' as 'url' | 'file' | 'text',
  name: '',
  url: '',
  text: '',
  file: null as File | null,
  progress: 0,
  saving: false
})
const registerFileInput = ref<HTMLInputElement | null>(null)

function openRegister(): void {
  registerDialog.mode = 'url'
  registerDialog.name = ''
  registerDialog.url = ''
  registerDialog.text = ''
  registerDialog.file = null
  registerDialog.progress = 0
  registerDialog.visible = true
}

function pickRegisterFile(): void {
  registerFileInput.value?.click()
}

function onRegisterFilePicked(event: Event): void {
  const target = event.target as HTMLInputElement
  registerDialog.file = target.files?.[0] ?? null
  if (registerDialog.file && !registerDialog.name) {
    registerDialog.name = registerDialog.file.name.replace(/\.(json|ya?ml)$/i, '')
  }
  target.value = ''
}

async function submitRegister(): Promise<void> {
  if (!registerDialog.name.trim()) {
    ElMessage.warning('请填写服务名（将作为 serviceId 与 tool 前缀）')
    return
  }
  registerDialog.saving = true
  registerDialog.progress = 0
  try {
    const targetServerId = props.serverId
    const name = registerDialog.name.trim()
    let view
    if (registerDialog.mode === 'url') {
      if (!registerDialog.url.trim()) {
        ElMessage.warning('请填写文档 URL')
        registerDialog.saving = false
        return
      }
      view = await registrationApi.createByUrl({
        name,
        url: registerDialog.url.trim(),
        targetServerId
      })
    } else if (registerDialog.mode === 'file') {
      if (!registerDialog.file) {
        ElMessage.warning('请选择 OpenAPI / Swagger 文件')
        registerDialog.saving = false
        return
      }
      view = await registrationApi.createByUpload(
        { name, file: registerDialog.file, targetServerId },
        (percent) => { registerDialog.progress = percent }
      )
    } else {
      if (!registerDialog.text.trim()) {
        ElMessage.warning('请粘贴文档内容')
        registerDialog.saving = false
        return
      }
      view = await registrationApi.createByText(name, registerDialog.text, undefined, undefined, targetServerId)
    }
    registerDialog.visible = false
    if (view.status === 'FAILED') {
      ElMessage.warning('文档已收下，但解析未通过，已为你弹出诊断详情')
      // FAILED 的注册不会生成 upstream 条目，诊断入口只能在此当场给出：
      // 借隐藏的文档动作组件把诊断弹出，用户修完文档重新注册即可
      failedRegistration.value = view
      await nextTick()
      failedDocActions.value?.openDiagnostics()
    } else {
      ElMessage.success(`已注册到本 Server，生成 ${view.operationCount} 个接口，REST 服务「${name}」`)
    }
    // 刷新 Server 视图（新 upstream + tool 进来）
    const updated = await serverApi.view(props.serverId)
    emit('saved', updated)
  } catch (error) {
    notifyError(error)
  } finally {
    registerDialog.saving = false
  }
}

const saving = ref(false)
const deleting = ref(false)

/** 当前选中的 REST 服务 serviceId；null 表示尚未选或无 upstream。 */
const activeServiceId = ref<string | null>(null)

const upstreams = computed<UpstreamView[]>(() => props.server.upstreams ?? [])

// ---- 注册文档的解析管理（诊断 / 原文 / 重新解析）----
// 通过注册创建的 REST 服务条目，serviceId = registrationId 字符串；手工补录的可能是任意 slug。
// 用数字形态的 serviceId 反查 registration，且校验 serverId 归属，命中才显示文档管理动作。
const registrationForActive = ref<RegistrationView | null>(null)

/** FAILED 注册的文档动作组件实例：注册失败当场弹出诊断用（此时无 upstream 可挂）。 */
const failedRegistration = ref<RegistrationView | null>(null)
const failedDocActions = ref<InstanceType<typeof RegistrationDocActions> | null>(null)

async function resolveRegistration(value: UpstreamView | null): Promise<void> {
  registrationForActive.value = null
  if (!value || !/^\d+$/.test(value.serviceId)) return
  const serviceId = value.serviceId
  try {
    const view = await registrationApi.view(Number(serviceId))
    // 异步回来时选中项可能已切换，且必须是当前 Server 的注册才算命中
    if (activeUpstream.value?.serviceId === serviceId && view.serverId === props.serverId) {
      registrationForActive.value = view
    }
  } catch {
    // 数字 serviceId 恰好撞上不存在的 registration（手工补录），静默降级为无文档动作
  }
}

const activeUpstream = computed<UpstreamView | null>(() => {
  if (!activeServiceId.value) {
    return upstreams.value[0] ?? null
  }
  return upstreams.value.find((u) => u.serviceId === activeServiceId.value) ?? null
})

// 必须放在 activeUpstream 声明之后：immediate watch 会在 setup 期间同步触发
watch(activeUpstream, (value) => void resolveRegistration(value), { immediate: true })

watch(
  () => upstreams.value,
  (list) => {
    if (list.length > 0 && !list.some((u) => u.serviceId === activeServiceId.value)) {
      activeServiceId.value = list[0].serviceId
    } else if (list.length === 0) {
      activeServiceId.value = 'default'
    }
  },
  { immediate: true }
)

/**
 * `PUT /servers/{id}/upstreams/{serviceId}` 是按 serviceId upsert，不是整体替换。
 * 新增服务时 serviceId 由用户输入（建议用 registrationId 或服务 slug）。
 */
const form = reactive({
  serviceId: 'default',
  name: '',
  baseUrlsText: '',
  lbStrategy: 'ROUND_ROBIN' as LbStrategy,
  /** 与 baseUrls 等长，按索引一一对应；仅在 WEIGHTED 时提交给后端。 */
  weights: [] as number[],
  connectTimeoutMs: 3000,
  readTimeoutMs: 30000,
  retries: 1,
  retryOnStatusText: '502, 503, 504',
  cbFailureThreshold: 5,
  cbOpenMs: 30000,
  cbHalfOpenProbes: 2
})

/** 服务地址逐行解析结果，权重编辑器按它逐项渲染，保证「一行地址 ↔ 一个权重」。 */
const baseUrlList = computed(() => splitLines(form.baseUrlsText))

/**
 * 权重长度跟随地址数量。
 *
 * 地址增删时补齐或截断，已填的值按索引保留——重排权重比丢掉用户刚敲的数字更烦人。
 * 新增项默认 1，等于「还没表态」，切到 WEIGHTED 时立刻是一个合法的等权配置。
 */
watch(
  () => [form.lbStrategy, baseUrlList.value.length] as const,
  () => {
    if (form.lbStrategy !== 'WEIGHTED') return
    const count = baseUrlList.value.length
    form.weights = Array.from({ length: count }, (_, i) => form.weights[i] ?? 1)
  },
  { immediate: true }
)

/** 本 REST 服务专属的上行鉴权（Auth-B），与上面的连接参数一起提交。 */
const authBForm = reactive(emptyAuthBForm())
/** 当前服务库里是否已存有凭据：有掩码即说明配过，密钥留空才合法。 */
const hasStoredAuthB = computed(() => Boolean(activeUpstream.value?.authB?.maskedPreview))

watch(
  activeUpstream,
  (value) => {
    if (value) {
      form.serviceId = value.serviceId
      form.name = value.name ?? value.serviceId
      form.baseUrlsText = (value.config?.baseUrls ?? []).join('\n')
      form.lbStrategy = value.config?.lbStrategy ?? 'ROUND_ROBIN'
      form.weights = [...(value.config?.weights ?? [])]
      form.connectTimeoutMs = value.config?.connectTimeoutMs ?? 3000
      form.readTimeoutMs = value.config?.readTimeoutMs ?? 30000
      form.retries = value.config?.retries ?? 1
      form.retryOnStatusText = joinCsv(value.config?.retryOnStatus ?? [502, 503, 504])
      form.cbFailureThreshold = value.config?.circuitBreaker?.failureThreshold ?? 5
      form.cbOpenMs = value.config?.circuitBreaker?.openMs ?? 30000
      form.cbHalfOpenProbes = value.config?.circuitBreaker?.halfOpenProbes ?? 2
      Object.assign(authBForm, authBFormFromView(value.authB))
    } else {
      Object.assign(authBForm, emptyAuthBForm())
    }
  },
  { immediate: true }
)

async function submit(): Promise<void> {
  const serviceId = form.serviceId.trim() || 'default'
  const baseUrls = splitLines(form.baseUrlsText)
  if (baseUrls.length === 0) {
    ElMessage.warning('至少填写一个服务地址')
    return
  }
  const bad = baseUrls.filter((url) => !/^https?:\/\//i.test(url))
  if (bad.length > 0) {
    ElMessage.warning(`服务地址必须以 http:// 或 https:// 开头：${bad[0]}`)
    return
  }
  const authProblem = validateAuthBForm(authBForm, hasStoredAuthB.value)
  if (authProblem) {
    ElMessage.warning(authProblem)
    return
  }
  // 权重只在 WEIGHTED 下有语义。后端会拒绝「WEIGHTED 却没给权重」，前端先行一步是为了
  // 把错误定位到具体第几个地址，而不是只回一句「权重非法」。
  let weights: number[] | undefined
  if (form.lbStrategy === 'WEIGHTED') {
    weights = Array.from({ length: baseUrls.length }, (_, i) => Number(form.weights[i] ?? 0))
    const negativeIndex = weights.findIndex((w) => !Number.isFinite(w) || w < 0)
    if (negativeIndex >= 0) {
      ElMessage.warning(`第 ${negativeIndex + 1} 个地址的权重必须是非负整数`)
      return
    }
    if (weights.reduce((sum, w) => sum + w, 0) <= 0) {
      ElMessage.warning('权重之和必须大于 0，否则 Executor 无法分发')
      return
    }
  }
  saving.value = true
  try {
    emit(
      'saved',
      await serverApi.upsertUpstream(props.serverId, serviceId, {
        serviceId,
        name: form.name,
        baseUrls,
        lbStrategy: form.lbStrategy,
        weights,
        connectTimeoutMs: form.connectTimeoutMs,
        readTimeoutMs: form.readTimeoutMs,
        retries: form.retries,
        retryOnStatus: splitCsvNumbers(form.retryOnStatusText),
        cbFailureThreshold: form.cbFailureThreshold,
        cbOpenMs: form.cbOpenMs,
        cbHalfOpenProbes: form.cbHalfOpenProbes,
        authB: authBFormToRequest(authBForm)
      })
    )
    ElMessage.success('已保存 REST 服务配置')
    activeServiceId.value = serviceId
  } catch (error) {
    notifyError(error)
  } finally {
    saving.value = false
  }
}

async function removeUpstream(serviceId: string): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确认删除 REST 服务「${serviceId}」？关联的 tool 不会自动迁移，需重新配置归属。`,
      '删除 REST 服务',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  deleting.value = true
  try {
    emit('saved', await serverApi.deleteUpstream(props.serverId, serviceId))
    ElMessage.success(`已删除 REST 服务「${serviceId}」`)
  } catch (error) {
    notifyError(error)
  } finally {
    deleting.value = false
  }
}

function switchUpstream(serviceId: string): void {
  activeServiceId.value = serviceId
}

function startNewUpstream(): void {
  activeServiceId.value = 'new'
  form.serviceId = ''
  form.name = ''
  form.baseUrlsText = ''
  form.lbStrategy = 'ROUND_ROBIN'
  form.weights = []
  form.connectTimeoutMs = 3000
  form.readTimeoutMs = 30000
  form.retries = 1
  form.retryOnStatusText = '502, 503, 504'
  form.cbFailureThreshold = 5
  form.cbOpenMs = 30000
  form.cbHalfOpenProbes = 2
  Object.assign(authBForm, emptyAuthBForm())
}
</script>

<template>
  <div class="upstream-panel">
    <el-alert type="info" :closable="false" show-icon class="tip">
      <template #title>一个 MCP Server 可挂多个 REST 服务</template>
      <template #default>
        每份 Swagger 对应一个 REST 服务（独立 baseUrls / 负载均衡 / 熔断 / Auth-B）。
        tool 在注册时自动绑定到所属 Swagger 的 REST 服务，调用时按 upstreamRef 路由。
        熔断按 <span class="mono">serverId:serviceId</span> 隔离，一个服务挂了不会牵连其他服务。
      </template>
    </el-alert>

    <div class="register-bar">
      <el-button
        v-if="auth.can('registration:create')"
        type="primary"
        plain
        size="small"
        @click="openRegister"
      >
        注册文档到本 Server
      </el-button>
      <span class="muted hint">每份 Swagger = 一个 REST 服务的 tool 集合，自动挂到当前 Server 下</span>
    </div>

    <div class="upstream-list-bar">
      <el-select
        v-if="upstreams.length > 0"
        :model-value="activeServiceId ?? ''"
        placeholder="选择 REST 服务"
        @update:model-value="switchUpstream"
      >
        <el-option
          v-for="u in upstreams"
          :key="u.serviceId"
          :label="`${u.name} (${u.serviceId})`"
          :value="u.serviceId"
        />
      </el-select>
      <el-button v-if="activeServiceId !== 'new'" size="small" @click="startNewUpstream">
        新增 REST 服务
      </el-button>
      <el-button
        v-if="activeUpstream && upstreams.length > 0"
        size="small"
        type="danger"
        plain
        :loading="deleting"
        :disabled="!auth.can('server:write')"
        @click="removeUpstream(activeUpstream.serviceId)"
      >
        删除当前
      </el-button>
    </div>

    <!-- 该 REST 服务来自注册文档时，就地提供解析管理（二级功能下沉到 Server 详情） -->
    <div v-if="registrationForActive" class="register-bar doc-bar">
      <span class="muted hint">
        文档解析（{{ registrationForActive.name }} · v{{ registrationForActive.docVersion }}）：
      </span>
      <RegistrationDocActions
        :registration-id="registrationForActive.id"
        :name="registrationForActive.name"
        @changed="resolveRegistration(activeUpstream)"
      />
    </div>

    <el-alert v-if="activeServiceId === 'new'" type="info" :closable="false" show-icon class="tip">
      <template #title>新增 REST 服务</template>
      <template #default>
        填写 serviceId（建议用 registrationId 或服务 slug，如 order-service）与服务配置，保存后该服务会挂到当前 MCP Server。
        通过注册页新增的 Swagger 会自动创建对应的 REST 服务条目，这里用于手工补录或调整。
      </template>
    </el-alert>

    <el-form label-width="130px" class="panel-form" @submit.prevent="submit">
      <el-form-item label="服务标识">
        <el-input
          v-model="form.serviceId"
          placeholder="例如 order-service 或 registrationId"
          :disabled="!!activeUpstream && activeServiceId !== 'new'"
        />
        <div class="hint muted">
          tool 的 upstreamRef 指向此标识。通过注册页创建的 Swagger 会自动用 registrationId 绑定，无需手工填；
          这里主要用于手工补录 REST 服务时指定标识，保存后不可改（改名会让已绑定的 tool 断链）。
        </div>
      </el-form-item>

      <el-form-item label="展示名">
        <el-input v-model="form.name" placeholder="来自 Swagger info.title" :disabled="!auth.can('server:write')" />
      </el-form-item>

      <el-form-item label="服务地址">
        <el-input
          v-model="form.baseUrlsText"
          type="textarea"
          :rows="3"
          placeholder="每行一个，例如 http://order-service:8080"
          :disabled="!auth.can('server:write')"
        />
        <div class="hint muted">
          一行一个。多个地址时由 Executor 按下面的策略选址；地址末尾的斜杠会被吃掉再拼接请求路径。
        </div>
      </el-form-item>

      <el-form-item label="负载均衡">
        <el-radio-group v-model="form.lbStrategy" :disabled="!auth.can('server:write')">
          <el-radio-button value="ROUND_ROBIN">{{ labelOf(LB_STRATEGY_LABEL, 'ROUND_ROBIN') }}</el-radio-button>
          <el-radio-button value="WEIGHTED">{{ labelOf(LB_STRATEGY_LABEL, 'WEIGHTED') }}</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-form-item v-if="form.lbStrategy === 'WEIGHTED'" label="权重">
        <div class="weight-list">
          <div v-for="(url, i) in baseUrlList" :key="`${i}-${url}`" class="weight-row">
            <span class="mono weight-addr" :title="url">{{ url }}</span>
            <el-input-number
              v-model="form.weights[i]"
              :min="0"
              :max="1000"
              size="small"
              controls-position="right"
              :disabled="!auth.can('server:write')"
            />
          </div>
          <div v-if="baseUrlList.length === 0" class="hint muted">先填写服务地址，权重按地址逐项对应。</div>
          <div v-else class="hint muted">
            权重与地址按顺序一一对应，本行合计 {{ form.weights.reduce((sum, w) => sum + (Number(w) || 0), 0) }}。
            后端会校验「数量与地址一致、总和大于 0」；写入后由 Executor 按权分发。
            全部填相同值等价于轮询，不必为了「稳妥」一律填 1。
          </div>
        </div>
      </el-form-item>

      <el-form-item label="连接超时">
        <el-input-number v-model="form.connectTimeoutMs" :min="100" :max="60000" :step="500" :disabled="!auth.can('server:write')" />
        <span class="unit muted">毫秒</span>
      </el-form-item>

      <el-form-item label="读取超时">
        <el-input-number v-model="form.readTimeoutMs" :min="100" :max="300000" :step="1000" :disabled="!auth.can('server:write')" />
        <span class="unit muted">毫秒</span>
      </el-form-item>

      <el-form-item label="重试次数">
        <el-input-number v-model="form.retries" :min="0" :max="5" :disabled="!auth.can('server:write')" />
        <span class="unit muted">0 表示不重试。只对下面的状态码与连接失败生效，不会重放已成功返回的请求。</span>
      </el-form-item>

      <el-form-item label="重试状态码">
        <el-input v-model="form.retryOnStatusText" placeholder="502, 503, 504" :disabled="!auth.can('server:write')" />
        <div class="hint muted">逗号分隔。留空表示任何状态码都不重试——注意这与「用默认值」不是一回事。</div>
      </el-form-item>

      <el-divider content-position="left">熔断</el-divider>

      <el-alert type="info" :closable="false" show-icon class="tip">
        <template #title>熔断状态按 REST 服务（serverId:serviceId）各自维护，不跨服务共享</template>
        <template #default>
          它度量的是「本节点到该 REST 服务」这条链路的健康度。多服务后服务 A 连续失败不会误熔断服务 B。
        </template>
      </el-alert>

      <el-form-item label="失败阈值">
        <el-input-number v-model="form.cbFailureThreshold" :min="1" :max="100" :disabled="!auth.can('server:write')" />
        <span class="unit muted">连续失败达到该次数后打开熔断</span>
      </el-form-item>

      <el-form-item label="熔断保持">
        <el-input-number v-model="form.cbOpenMs" :min="1000" :max="600000" :step="1000" :disabled="!auth.can('server:write')" />
        <span class="unit muted">毫秒，约 {{ formatDuration(form.cbOpenMs) }}；到期转半开</span>
      </el-form-item>

      <el-form-item label="半开探测数">
        <el-input-number v-model="form.cbHalfOpenProbes" :min="1" :max="20" :disabled="!auth.can('server:write')" />
        <span class="unit muted">半开期间放行的探测请求数，全部成功才闭合；任一失败立刻重新打开</span>
      </el-form-item>

      <el-divider content-position="left">上行鉴权（Auth-B）</el-divider>

      <el-alert type="info" :closable="false" show-icon class="tip">
        <template #title>每个 REST 服务独立配置上行鉴权</template>
        <template #default>
          这里配的是「平台 → 本服务」这一跳的凭据，只作用于当前 REST 服务，不影响同 Server 下的其它服务。
          <span v-if="activeUpstream?.authB?.maskedPreview">
            当前已保存：<span class="mono">{{ activeUpstream.authB.maskedPreview }}</span>
          </span>
          <span v-else>当前未配置。</span>
        </template>
      </el-alert>

      <AuthBFields
        :form="authBForm"
        :has-stored-secret="hasStoredAuthB"
        :disabled="!auth.can('server:write')"
      />

      <el-form-item>
        <el-button type="primary" :loading="saving" :disabled="!auth.can('server:write')" @click="submit">
          {{ activeServiceId === 'new' ? '新增 REST 服务' : '保存服务配置' }}
        </el-button>
        <span v-if="!auth.can('server:write')" class="muted hint">当前账号没有 server:write 权限</span>
      </el-form-item>
    </el-form>

    <!-- 注册文档到本 Server -->
    <input ref="registerFileInput" type="file" accept=".json,.yaml,.yml" hidden @change="onRegisterFilePicked" />
    <!-- FAILED 注册没有 upstream 条目可挂文档动作，借这个隐藏实例把诊断弹出来 -->
    <div v-if="failedRegistration" class="hidden-doc-actions" aria-hidden="true">
      <RegistrationDocActions
        ref="failedDocActions"
        :registration-id="failedRegistration.id"
        :name="failedRegistration.name"
      />
    </div>
    <el-dialog v-model="registerDialog.visible" title="注册 Swagger 文档到本 Server" width="600px">
      <el-alert type="info" :closable="false" show-icon class="tip">
        <template #title>本 Server 的 tool 集合来自多份 Swagger</template>
        <template #default>
          每份文档 = 一个 REST 服务，自动创建独立服务配置（baseUrls / 熔断 / Auth-B）。
          tool 名带服务前缀（如 <span class="mono">order_getUser</span>）避免跨服务同名冲突。
        </template>
      </el-alert>
      <el-tabs v-model="registerDialog.mode">
        <el-tab-pane label="从 URL 抓取" name="url">
          <el-form label-width="90px">
            <el-form-item label="文档 URL">
              <el-input v-model="registerDialog.url" placeholder="https://svc.internal/v3/api-docs" />
            </el-form-item>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="上传文件" name="file">
          <el-form label-width="90px">
            <el-form-item label="文档文件">
              <el-button @click="pickRegisterFile">选择文件</el-button>
              <span class="muted file-name">{{ registerDialog.file?.name ?? '未选择（.json / .yaml / .yml）' }}</span>
            </el-form-item>
            <el-form-item v-if="registerDialog.progress > 0 && registerDialog.progress < 100" label="上传进度">
              <el-progress :percentage="registerDialog.progress" />
            </el-form-item>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="粘贴内容" name="text">
          <el-input
            v-model="registerDialog.text"
            type="textarea"
            :rows="10"
            placeholder="粘贴 OpenAPI 3.x 或 Swagger 2.0 文档"
            class="mono"
          />
        </el-tab-pane>
      </el-tabs>
      <el-form label-width="90px">
        <el-form-item label="服务名" required>
          <el-input v-model="registerDialog.name" placeholder="如 order-service，将作为 serviceId 与 tool 前缀" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="registerDialog.saving" @click="submitRegister">注册并解析</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.upstream-panel {
  max-width: 800px;
}

.register-bar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
}

.hidden-doc-actions {
  display: none;
}

.file-name {
  margin-left: 8px;
  font-size: 12px;
}

.upstream-list-bar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
}

.panel-form {
  max-width: 760px;
}

.tip {
  margin-bottom: 16px;
}

.hint {
  font-size: 12px;
  line-height: 1.6;
}

.unit {
  margin-left: 8px;
  font-size: 12px;
}

/* 权重编辑器：地址左、权重右，逐行对齐，长地址省略但不换行破坏对应关系 */
.weight-list {
  width: 100%;
  max-width: 520px;
}

.weight-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 6px;
}

.weight-addr {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
}
</style>
