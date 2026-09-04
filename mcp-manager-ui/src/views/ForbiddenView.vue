<script setup lang="ts">
import { useRouter } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
</script>

<template>
  <div class="page">
    <el-result icon="error" title="403" sub-title="你的角色没有访问该页面的权限点">
      <template #extra>
        <el-button type="primary" @click="router.replace('/')">回到概览</el-button>
      </template>
    </el-result>
    <el-card shadow="never" class="hint">
      <p class="muted">
        当前角色：{{ auth.roles.join('、') || '无' }}。
        前端隐藏入口只是体验优化，真正的鉴权在控制面完成；
        需要权限请联系部门管理员在「角色权限」里调整。
      </p>
    </el-card>
  </div>
</template>

<style scoped>
.hint {
  max-width: 640px;
  margin: 0 auto;
}
</style>