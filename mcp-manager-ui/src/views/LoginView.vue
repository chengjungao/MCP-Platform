<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'

import { notifyError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useMetaStore } from '@/stores/meta'

const auth = useAuthStore()
const metaStore = useMetaStore()
const router = useRouter()
const route = useRoute()

const form = reactive({ username: '', password: '' })
const loading = ref(false)

async function submit(): Promise<void> {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入账号与密码')
    return
  }
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    const redirect = route.query.redirect
    await router.replace(typeof redirect === 'string' && redirect ? redirect : '/')
  } catch (error) {
    notifyError(error)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  // /meta 是免鉴权的，登录页就能拿到协议版本，把 Modern-only 这条硬承诺摆在门口
  void metaStore.load()
})
</script>

<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <div class="login-head">
        <span class="brand-mark">MCP</span>
        <h1>桥接平台控制台</h1>
        <p class="muted">把部门已有的 REST API 发布成 MCP Server</p>
      </div>

      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="账号">
          <el-input
            v-model="form.username"
            placeholder="用户名"
            autocomplete="username"
            size="large"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            show-password
            autocomplete="current-password"
            size="large"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="primary" size="large" class="submit" :loading="loading" @click="submit">
          登录
        </el-button>
      </el-form>

      <el-alert
        v-if="metaStore.meta"
        class="protocol"
        type="info"
        :closable="false"
        show-icon
        :title="`仅支持 MCP ${metaStore.meta.supportedProtocolVersion}`"
      >
        <template #default>
          <div class="protocol-body">
            本平台不做协议版本协商，legacy 客户端会被显式拒绝（错误码 -32022）。
            <a :href="metaStore.meta.upgradeGuideUrl" target="_blank" rel="noopener">升级指引</a>
          </div>
        </template>
      </el-alert>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100%;
  padding: 24px;
  background: linear-gradient(135deg, #1f3b73 0%, #409eff 100%);
}

.login-card {
  width: 100%;
  max-width: 420px;
}

.login-head {
  margin-bottom: 20px;
  text-align: center;
}

.login-head h1 {
  margin: 8px 0 4px;
  font-size: 20px;
}

.login-head p {
  margin: 0;
  font-size: 12px;
}

.brand-mark {
  padding: 3px 8px;
  font-weight: 700;
  color: #fff;
  background: #409eff;
  border-radius: 4px;
}

.submit {
  width: 100%;
}

.protocol {
  margin-top: 16px;
}

.protocol-body {
  font-size: 12px;
  line-height: 1.6;
}
</style>