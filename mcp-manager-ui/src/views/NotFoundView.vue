<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <el-result icon="warning" title="404" sub-title="页面不存在，可能是链接过期或路径写错了">
    <template #extra>
      <el-button type="primary" @click="router.replace('/')">回到概览</el-button>
    </template>
  </el-result>
</template>