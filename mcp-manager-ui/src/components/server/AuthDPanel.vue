<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { notifyError } from '@/api/http'
import * as serverApi from '@/api/server'
import type { AuthDMode, AuthDView } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { AUTH_D_MODE_LABEL } from '@/utils/format'
import { joinLines, splitLines } from '@/utils/form'

const props = defineProps<{ serverId: number }>()
const emit = defineEmits<{ changed: [] }>()

const auth = useAuthStore()
const view = ref<AuthDView | null>(null)
const loading = ref(false)
const saving = ref(false)

const form = reactive({
  mode: 'NONE' as AuthDMode,
  staticTokensText: '',
  scopesText: '',
  issuer: '',
  authorizationEndpoint: '',
  tokenEndpoint: '',
  registrationEndpoint: ''
})

const canWrite = computed(() => auth.can('auth:write'))

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await serverApi.authD(props.serverId)
    view.value = result
    form.mode = result.mode ?? 'NONE'
    form.scopesText = joinLines(result.scopes)
    form.issuer = result.issuer ?? ''
    form.authorizationEndpoint = result.authorizationEndpoint ?? ''
    form.tokenEndpoint = result.tokenEndpoint ?? ''
    form.registrationEndpoint = result.registrationEndpoint ?? ''
    // 令牌只存 sha256，明文无法回显，所以这里刻意留空而不是回显点什么
    form.staticTokensText = ''
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

async function submit(): Promise<void> {
  const tokens = splitLines(form.staticTokensText)
  if (form.mode === 'STATIC_BEARER' && tokens.length === 0) {
    ElMessage.warning('STATIC_BEARER 方式至少要填一个令牌；保存是整体替换，漏填等于吊销')
    return
  }
  if (form.mode === 'OAUTH2') {
    if (!form.issuer.trim() || !form.authorizationEndpoint.trim() || !form.tokenEndpoint.trim()) {
      ElMessage.warning('OAUTH2 方式必须填写 issuer、授权端点与令牌端点')
      return
    }
  }
  saving.value = true
  try {
    view.value = await serverApi.saveAuthD(props.serverId, {
      mode: form.mode,
      staticTokens: form.mode === 'STATIC_BEARER' ? tokens : undefined,
      scopes: splitLines(form.scopesText),
      issuer: form.issuer.trim() || undefined,
      authorizationEndpoint: form.authorizationEndpoint.trim() || undefined,
      tokenEndpoint: form.tokenEndpoint.trim() || undefined,
      registrationEndpoint: form.registrationEndpoint.trim() || undefined
    })
    form.staticTokensText = ''
    ElMessage.success('MCP 客户端授权已保存，重新发布后生效')
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
    <el-form label-width="150px" class="panel-form" @submit.prevent="submit">
      <el-form-item label="鉴权模式">
        <el-select v-model="form.mode" :disabled="!canWrite" style="width: 300px">
          <el-option v-for="(label, value) in AUTH_D_MODE_LABEL" :key="value" :label="label" :value="value" />
        </el-select>
        <div class="hint muted">
          这是「MCP Client → 平台」这一跳（Auth-D）。Executor 本身无状态，校验只依赖快照里的哈希与元数据。
        </div>
      </el-form-item>

      <template v-if="form.mode === 'STATIC_BEARER'">
        <el-alert type="error" :closable="false" show-icon class="tip">
          <template #title>保存会整体替换令牌集合，而平台无法回显已保存的令牌</template>
          <template #default>
            平台只保存令牌的 sha256，明文在保存那一刻就丢弃了，因此这里永远是空框。
            要把「新增一个令牌」和「保留原有令牌」一起做，就必须把所有要保留的令牌全部重新填进来——
            只填新的那个等于吊销其余全部。当前已保存
            <strong>{{ view?.staticTokenCount ?? 0 }}</strong> 个令牌。
          </template>
        </el-alert>
        <el-form-item label="静态令牌">
          <el-input
            v-model="form.staticTokensText"
            type="textarea"
            :rows="4"
            placeholder="一行一个 Bearer 令牌（不含 'Bearer ' 前缀）"
            :disabled="!canWrite"
          />
          <div class="hint muted">本次提交将保存 {{ splitLines(form.staticTokensText).length }} 个令牌。</div>
        </el-form-item>
      </template>

      <template v-else-if="form.mode === 'OAUTH2'">
        <el-alert type="info" :closable="false" show-icon class="tip">
          <template #title>OAuth 2.1 资源服务器模式属于 P1（EXE-07）</template>
          <template #default>
            当前版本会把下面这些元数据端点保存进快照并对外暴露，
            但 Executor 侧的授权码校验与动态客户端注册（DCR）要到 P1 才实现。
            现在需要立刻可用的鉴权请选静态 Bearer 令牌。
          </template>
        </el-alert>
        <el-form-item label="issuer">
          <el-input v-model="form.issuer" placeholder="https://idp.example.com" :disabled="!canWrite" />
        </el-form-item>
        <el-form-item label="授权端点">
          <el-input v-model="form.authorizationEndpoint" :disabled="!canWrite" />
        </el-form-item>
        <el-form-item label="令牌端点">
          <el-input v-model="form.tokenEndpoint" :disabled="!canWrite" />
        </el-form-item>
        <el-form-item label="注册端点（DCR）">
          <el-input v-model="form.registrationEndpoint" placeholder="可选" :disabled="!canWrite" />
        </el-form-item>
        <el-form-item v-if="view?.resourceMetadataUrl" label="受保护资源元数据">
          <span class="mono small">{{ view.resourceMetadataUrl }}</span>
          <div class="hint muted">
            由后端按端点自动补 <span class="mono">/.well-known/oauth-protected-resource</span>，只读、不可手工填写。
          </div>
        </el-form-item>
      </template>

      <el-form-item v-if="form.mode !== 'NONE'" label="scope">
        <el-input v-model="form.scopesText" type="textarea" :rows="2" placeholder="一行一个" :disabled="!canWrite" />
      </el-form-item>

      <el-form-item>
        <el-button type="primary" :loading="saving" :disabled="!canWrite" @click="submit">保存MCP 客户端授权</el-button>
        <el-button :disabled="!canWrite" @click="load">重置</el-button>
        <span v-if="!canWrite" class="muted hint">当前账号没有 auth:write 权限</span>
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
</style>