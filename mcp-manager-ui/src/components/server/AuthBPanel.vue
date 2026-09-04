<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { notifyError } from '@/api/http'
import * as serverApi from '@/api/server'
import type { AuthBLocation, AuthBRequest, AuthBType, AuthBView, ExtraHeader } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { AUTH_B_TYPE_LABEL, formatDateTime } from '@/utils/format'
import { splitCsv } from '@/utils/form'

const props = defineProps<{ serverId: number }>()
const emit = defineEmits<{ changed: [] }>()

const auth = useAuthStore()
const view = ref<AuthBView | null>(null)
const loading = ref(false)
const saving = ref(false)

/**
 * 密钥字段永远不回显：后端只返回掩码（SEC-01），表单里的三个密钥输入框
 * 始终从空开始，留空表示「不修改」，要清除凭据只能把 type 改回 NONE。
 */
const form = reactive({
  type: 'NONE' as AuthBType,
  location: 'HEADER' as AuthBLocation,
  name: '',
  scheme: 'bearer',
  username: '',
  tokenUrl: '',
  clientId: '',
  scope: '',
  headerTemplate: '',
  extraHeaders: [] as ExtraHeader[],
  secret: '',
  password: '',
  clientSecret: ''
})

const canWrite = computed(() => auth.can('auth:write'))
/** 有掩码就说明库里已经存了凭据，此时密钥留空是合法的。 */
const hasStoredSecret = computed(() => Boolean(view.value?.maskedPreview))
const isBearer = computed(() => form.type === 'HTTP' && form.scheme === 'bearer')
const isBasic = computed(() => form.type === 'HTTP' && form.scheme === 'basic')

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await serverApi.authB(props.serverId)
    view.value = result
    form.type = result.type ?? 'NONE'
    form.location = result.location ?? 'HEADER'
    form.name = result.name ?? ''
    form.scheme = result.scheme ?? 'bearer'
    form.username = result.username ?? ''
    form.tokenUrl = result.tokenUrl ?? ''
    form.clientId = result.clientId ?? ''
    form.scope = result.scope ?? ''
    form.headerTemplate = result.headerTemplate ?? ''
    form.extraHeaders = (result.extraHeaders ?? []).map((header) => ({ ...header }))
    form.secret = ''
    form.password = ''
    form.clientSecret = ''
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

function addHeader(): void {
  form.extraHeaders.push({ name: '', value: '' })
}

function removeHeader(index: number): void {
  form.extraHeaders.splice(index, 1)
}

/**
 * 前端把后端的必填校验提前一遍，只为省一次往返。
 * 真正的判定仍在 AuthConfigService 里，两边不一致时以后端为准。
 */
function validate(): string | null {
  switch (form.type) {
    case 'API_KEY':
      if (!form.name.trim()) return 'API Key 方式必须填写 header 名或 query 参数名'
      if (!form.secret.trim() && !hasStoredSecret.value) return 'API Key 方式必须填写密钥值'
      return null
    case 'HTTP':
      if (form.scheme !== 'bearer' && form.scheme !== 'basic') return 'scheme 只能是 bearer 或 basic'
      if (isBearer.value && !form.secret.trim() && !hasStoredSecret.value) return 'bearer 方式必须填写 Token'
      if (isBasic.value) {
        if (!form.username.trim()) return 'basic 方式必须填写用户名'
        if (!form.password.trim() && !hasStoredSecret.value) return 'basic 方式必须填写密码'
      }
      return null
    case 'OAUTH2_CLIENT_CREDENTIALS':
      if (!form.tokenUrl.trim()) return 'client_credentials 方式必须填写令牌端点'
      if (!form.clientId.trim()) return 'client_credentials 方式必须填写 clientId'
      if (!form.clientSecret.trim() && !hasStoredSecret.value) return 'client_credentials 方式必须填写 clientSecret'
      return null
    case 'CUSTOM_HEADER':
      if (!form.headerTemplate.trim()) return '自定义 Header 方式必须填写 Header 模板'
      return null
    default:
      return null
  }
}

async function submit(): Promise<void> {
  const problem = validate()
  if (problem) {
    ElMessage.warning(problem)
    return
  }
  const request: AuthBRequest = {
    type: form.type,
    // location 只对 API_KEY 有意义，其它类型不传，免得库里留下一份会被误读的配置
    location: form.type === 'API_KEY' ? form.location : undefined,
    name: form.name.trim() || undefined,
    scheme: form.type === 'HTTP' ? form.scheme : undefined,
    username: form.username.trim() || undefined,
    tokenUrl: form.tokenUrl.trim() || undefined,
    clientId: form.clientId.trim() || undefined,
    scope: form.scope.trim() || undefined,
    headerTemplate: form.headerTemplate.trim() || undefined,
    extraHeaders: form.extraHeaders.filter((header) => header.name.trim()),
    secret: form.secret.trim() || undefined,
    password: form.password.trim() || undefined,
    clientSecret: form.clientSecret.trim() || undefined
  }
  saving.value = true
  try {
    view.value = await serverApi.saveAuthB(props.serverId, request)
    form.secret = ''
    form.password = ''
    form.clientSecret = ''
    ElMessage.success('上行授权已保存，重新发布后 Executor 才会用新凭据')
    emit('changed')
  } catch (error) {
    notifyError(error)
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div v-loading="loading" class="panel">
    <el-alert type="warning" :closable="false" show-icon class="tip">
      <template #title>上行密钥只写不读：保存后平台只留密文，界面永远只能看到掩码</template>
      <template #default>
        凭据以 AES-256-GCM 加密落库，任何接口都不会回传明文（SEC-01）。
        密钥输入框留空即「保持原值不变」；要彻底清除凭据，请把方式改回「无需鉴权」再保存。
        <span v-if="view?.maskedPreview">当前已保存：<span class="mono">{{ view.maskedPreview }}</span></span>
        <span v-if="view?.updatedAt"> · 更新于 {{ formatDateTime(view.updatedAt) }}</span>
      </template>
    </el-alert>

    <el-form label-width="140px" class="panel-form" @submit.prevent="submit">
      <el-form-item label="鉴权方式">
        <el-select v-model="form.type" :disabled="!canWrite" style="width: 280px">
          <el-option
            v-for="(label, value) in AUTH_B_TYPE_LABEL"
            :key="value"
            :label="label"
            :value="value"
          />
        </el-select>
        <div class="hint muted">
          这是「平台 → 部门上游服务」这一跳（Auth-B）。它与客户端访问平台用的 Auth-D 完全独立。
        </div>
      </el-form-item>

      <template v-if="form.type === 'API_KEY'">
        <el-form-item label="注入位置">
          <el-radio-group v-model="form.location" :disabled="!canWrite">
            <el-radio-button value="HEADER">Header</el-radio-button>
            <el-radio-button value="QUERY">Query 参数</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-alert v-if="form.location === 'QUERY'" type="error" :closable="false" show-icon class="tip">
          <template #title>凭据放在 query 上会被记进上游访问日志、中间代理日志与链路追踪</template>
          <template #default>
            这些日志的可见范围通常远大于密钥本身，等于把长期凭据扩散出去。
            除非上游只支持这种方式，否则请改用 Header。
          </template>
        </el-alert>
        <el-form-item label="参数名">
          <el-input v-model="form.name" placeholder="X-API-Key 或 api_key" :disabled="!canWrite" />
        </el-form-item>
        <el-form-item label="密钥值">
          <el-input
            v-model="form.secret"
            type="password"
            show-password
            :placeholder="hasStoredSecret ? '留空表示不修改' : '请输入 API Key'"
            :disabled="!canWrite"
          />
        </el-form-item>
      </template>

      <template v-else-if="form.type === 'HTTP'">
        <el-form-item label="scheme">
          <el-radio-group v-model="form.scheme" :disabled="!canWrite">
            <el-radio-button value="bearer">bearer</el-radio-button>
            <el-radio-button value="basic">basic</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="isBearer" label="Bearer Token">
          <el-input
            v-model="form.secret"
            type="password"
            show-password
            :placeholder="hasStoredSecret ? '留空表示不修改' : '请输入 Token'"
            :disabled="!canWrite"
          />
        </el-form-item>
        <template v-else>
          <el-form-item label="用户名">
            <el-input v-model="form.username" :disabled="!canWrite" />
          </el-form-item>
          <el-form-item label="密码">
            <el-input
              v-model="form.password"
              type="password"
              show-password
              :placeholder="hasStoredSecret ? '留空表示不修改' : '请输入密码'"
              :disabled="!canWrite"
            />
          </el-form-item>
        </template>
      </template>

      <template v-else-if="form.type === 'OAUTH2_CLIENT_CREDENTIALS'">
        <el-form-item label="令牌端点">
          <el-input v-model="form.tokenUrl" placeholder="https://idp.example.com/oauth/token" :disabled="!canWrite" />
        </el-form-item>
        <el-form-item label="clientId">
          <el-input v-model="form.clientId" :disabled="!canWrite" />
        </el-form-item>
        <el-form-item label="clientSecret">
          <el-input
            v-model="form.clientSecret"
            type="password"
            show-password
            :placeholder="hasStoredSecret ? '留空表示不修改' : '请输入 clientSecret'"
            :disabled="!canWrite"
          />
        </el-form-item>
        <el-form-item label="scope">
          <el-input v-model="form.scope" placeholder="逗号分隔，可留空" :disabled="!canWrite" />
          <div class="hint muted">解析结果：{{ splitCsv(form.scope).join(' | ') || '（不申请 scope）' }}</div>
        </el-form-item>
      </template>

      <template v-else-if="form.type === 'CUSTOM_HEADER'">
        <el-form-item label="Header 模板">
          <el-input
            v-model="form.headerTemplate"
            placeholder="例如 Token ${secret}"
            :disabled="!canWrite"
          />
          <div class="hint muted">
            模板里的 <span class="mono">${secret}</span> 会在 Executor 侧用下面填的密钥替换，
            替换发生在请求发出的那一刻，模板本身可以安全地存明文。
          </div>
        </el-form-item>
        <el-form-item label="密钥值">
          <el-input
            v-model="form.secret"
            type="password"
            show-password
            placeholder="可选；模板不含 ${secret} 时留空"
            :disabled="!canWrite"
          />
        </el-form-item>
      </template>

      <template v-if="form.type !== 'NONE'">
        <el-divider content-position="left">附加请求头（可选）</el-divider>
        <el-form-item v-for="(header, index) in form.extraHeaders" :key="index" :label="index === 0 ? '附加头' : ' '">
          <div class="header-row">
            <el-input v-model="header.name" placeholder="Header 名" :disabled="!canWrite" />
            <el-input v-model="header.value" placeholder="Header 值" :disabled="!canWrite" />
            <el-button type="danger" link :disabled="!canWrite" @click="removeHeader(index)">删除</el-button>
          </div>
        </el-form-item>
        <el-form-item :label="form.extraHeaders.length === 0 ? '附加头' : ' '">
          <el-button :disabled="!canWrite" @click="addHeader">添加一行</el-button>
        </el-form-item>
      </template>

      <el-form-item>
        <el-button type="primary" :loading="saving" :disabled="!canWrite" @click="submit">保存上行授权</el-button>
        <el-button :disabled="!canWrite" @click="load">重置</el-button>
        <span v-if="!canWrite" class="muted hint">当前账号没有 auth:write 权限，只能查看掩码与非敏感字段</span>
      </el-form-item>
    </el-form>
  </div>
</template>

<style scoped>
.panel-form {
  max-width: 780px;
}

.tip {
  margin-bottom: 16px;
}

.hint {
  font-size: 12px;
  line-height: 1.6;
}

.header-row {
  display: flex;
  gap: 8px;
  width: 100%;
}
</style>