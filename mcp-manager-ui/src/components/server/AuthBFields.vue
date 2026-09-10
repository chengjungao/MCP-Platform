<script setup lang="ts">
import { computed } from 'vue'

import type { AuthBForm } from '@/utils/authB'
import { AUTH_B_TYPE_LABEL } from '@/utils/format'
import { splitCsv } from '@/utils/form'

/**
 * 上行鉴权（Auth-B）的字段集合，本身不含加载/保存逻辑。
 *
 * <p>抽成独立组件是为了让「REST 服务级」与「Server 级」共用同一套字段与提示——
 * 两者语义完全一致，差别只在保存时打到哪个端点。
 *
 * <p>直接修改传入的 {@code form} 对象属性（引用共享），父组件负责提交。
 */
const props = defineProps<{
  form: AuthBForm
  /** 库里是否已存有凭据（有掩码即为真），决定密钥留空是否合法。 */
  hasStoredSecret?: boolean
  disabled?: boolean
}>()

const isBearer = computed(() => props.form.type === 'HTTP' && props.form.scheme === 'bearer')
const isBasic = computed(() => props.form.type === 'HTTP' && props.form.scheme === 'basic')

function addHeader(): void {
  props.form.extraHeaders.push({ name: '', value: '' })
}

function removeHeader(index: number): void {
  props.form.extraHeaders.splice(index, 1)
}
</script>

<template>
  <el-form-item label="鉴权方式">
    <el-select v-model="form.type" :disabled="disabled" style="width: 280px">
      <el-option
        v-for="(label, value) in AUTH_B_TYPE_LABEL"
        :key="value"
        :label="label"
        :value="value"
      />
    </el-select>
    <div class="hint muted">
      这是「平台 → 该 REST 服务」这一跳（Auth-B）。它与 MCP 客户端访问平台用的 Auth-D 完全独立。
    </div>
  </el-form-item>

  <template v-if="form.type === 'API_KEY'">
    <el-form-item label="注入位置">
      <el-radio-group v-model="form.location" :disabled="disabled">
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
      <el-input v-model="form.name" placeholder="X-API-Key 或 api_key" :disabled="disabled" />
    </el-form-item>
    <el-form-item label="密钥值">
      <el-input
        v-model="form.secret"
        type="password"
        show-password
        :placeholder="hasStoredSecret ? '留空表示不修改' : '请输入 API Key'"
        :disabled="disabled"
      />
    </el-form-item>
  </template>

  <template v-else-if="form.type === 'HTTP'">
    <el-form-item label="scheme">
      <el-radio-group v-model="form.scheme" :disabled="disabled">
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
        :disabled="disabled"
      />
    </el-form-item>
    <template v-else-if="isBasic">
      <el-form-item label="用户名">
        <el-input v-model="form.username" :disabled="disabled" />
      </el-form-item>
      <el-form-item label="密码">
        <el-input
          v-model="form.password"
          type="password"
          show-password
          :placeholder="hasStoredSecret ? '留空表示不修改' : '请输入密码'"
          :disabled="disabled"
        />
      </el-form-item>
    </template>
  </template>

  <template v-else-if="form.type === 'OAUTH2_CLIENT_CREDENTIALS'">
    <el-form-item label="令牌端点">
      <el-input
        v-model="form.tokenUrl"
        placeholder="https://idp.example.com/oauth/token"
        :disabled="disabled"
      />
    </el-form-item>
    <el-form-item label="clientId">
      <el-input v-model="form.clientId" :disabled="disabled" />
    </el-form-item>
    <el-form-item label="clientSecret">
      <el-input
        v-model="form.clientSecret"
        type="password"
        show-password
        :placeholder="hasStoredSecret ? '留空表示不修改' : '请输入 clientSecret'"
        :disabled="disabled"
      />
    </el-form-item>
    <el-form-item label="scope">
      <el-input v-model="form.scope" placeholder="逗号分隔，可留空" :disabled="disabled" />
      <div class="hint muted">
        解析结果：{{ splitCsv(form.scope).join(' ') || '（不申请 scope）' }}
      </div>
    </el-form-item>
  </template>

  <template v-else-if="form.type === 'CUSTOM_HEADER'">
    <el-form-item label="Header 模板">
      <el-input
        v-model="form.headerTemplate"
        placeholder="例如 X-Api-Token: {value}"
        :disabled="disabled"
      />
      <div class="hint muted">
        每行一个 <span class="mono">Name: Value</span>，支持
        <span class="mono">{value}</span> <span class="mono">{username}</span>
        <span class="mono">{password}</span> <span class="mono">{clientId}</span>
        <span class="mono">{scope}</span> 占位符；替换发生在 Executor 发出请求的那一刻。
      </div>
    </el-form-item>
    <el-form-item label="密钥值">
      <el-input
        v-model="form.secret"
        type="password"
        show-password
        placeholder="可选；模板不含 {value} 时留空"
        :disabled="disabled"
      />
    </el-form-item>
  </template>

  <template v-if="form.type !== 'NONE'">
    <el-divider content-position="left">附加请求头（可选）</el-divider>
    <el-form-item
      v-for="(header, index) in form.extraHeaders"
      :key="index"
      :label="index === 0 ? '附加头' : ' '"
    >
      <div class="header-row">
        <el-input v-model="header.name" placeholder="Header 名" :disabled="disabled" />
        <el-input v-model="header.value" placeholder="Header 值" :disabled="disabled" />
        <el-button type="danger" link :disabled="disabled" @click="removeHeader(index)">删除</el-button>
      </div>
    </el-form-item>
    <el-form-item :label="form.extraHeaders.length === 0 ? '附加头' : ' '">
      <el-button :disabled="disabled" @click="addHeader">添加一行</el-button>
    </el-form-item>
  </template>
</template>

<style scoped>
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
