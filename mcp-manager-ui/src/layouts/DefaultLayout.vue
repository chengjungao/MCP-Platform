<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { Component } from 'vue'
import {
  ArrowDown,
  Connection,
  Document,
  Expand,
  Fold,
  Grid,
  HomeFilled,
  Key,
  OfficeBuilding,
  Tickets,
  UserFilled
} from '@element-plus/icons-vue'

import { notifyError } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { useMetaStore } from '@/stores/meta'

interface MenuItem {
  path: string
  title: string
  icon: Component
  permission?: string
}

/**
 * 菜单项写死在这里而不是从后端下发：菜单结构是产品形态的一部分，
 * 让它可配置只会多一个「配置错了整个控制台空白」的故障面。
 * 真正的权限点仍由后端在 /auth/me 里给出，这里只负责按权限点隐藏入口。
 */
const menu: MenuItem[] = [
  { path: '/', title: '概览', icon: HomeFilled },
  { path: '/registrations', title: '注册与解析', icon: Document, permission: 'registration:read' },
  { path: '/servers', title: 'MCP Server', icon: Grid, permission: 'server:read' },
  { path: '/clusters', title: '集群与节点', icon: Connection, permission: 'cluster:read' },
  { path: '/departments', title: '部门', icon: OfficeBuilding, permission: 'dept:read' },
  { path: '/roles', title: '角色权限', icon: Key, permission: 'role:read' },
  { path: '/users', title: '账号', icon: UserFilled, permission: 'user:read' },
  { path: '/audits', title: '审计日志', icon: Tickets, permission: 'audit:read' }
]

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const metaStore = useMetaStore()

const collapsed = ref(false)
const visibleMenu = computed(() => menu.filter((item) => auth.can(item.permission)))

/** 详情页也要让对应的列表菜单高亮，否则进到 /servers/12 侧栏会整片失焦。 */
const activeMenu = computed(() => {
  const segments = route.path.split('/').filter(Boolean)
  return segments.length === 0 ? '/' : `/${segments[0]}`
})

const pageTitle = computed(() => route.meta.title ?? '')
const protocol = computed(() => metaStore.meta)

const passwordDialog = reactive({ visible: false, current: '', next: '', confirm: '', saving: false })

function openPasswordDialog(): void {
  passwordDialog.current = ''
  passwordDialog.next = ''
  passwordDialog.confirm = ''
  passwordDialog.visible = true
}

async function submitPassword(): Promise<void> {
  if (passwordDialog.next.length < 8) {
    ElMessage.warning('新密码至少 8 位')
    return
  }
  if (passwordDialog.next !== passwordDialog.confirm) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  passwordDialog.saving = true
  try {
    await auth.changePassword(passwordDialog.current, passwordDialog.next)
    passwordDialog.visible = false
    ElMessage.success('密码已修改，请使用新密码重新登录')
    await auth.logout()
    await router.replace('/login')
  } catch (error) {
    notifyError(error)
  } finally {
    passwordDialog.saving = false
  }
}

async function onCommand(command: string): Promise<void> {
  if (command === 'password') {
    openPasswordDialog()
    return
  }
  if (command === 'logout') {
    try {
      await ElMessageBox.confirm('退出登录后需要重新输入账号密码，确认退出？', '退出登录', {
        type: 'warning',
        confirmButtonText: '退出',
        cancelButtonText: '取消'
      })
    } catch {
      return
    }
    await auth.logout()
    await router.replace('/login')
  }
}

onMounted(() => {
  void metaStore.load()
})
</script>

<template>
  <el-container class="layout">
    <el-aside :width="collapsed ? '64px' : '210px'" class="aside">
      <div class="brand">
        <span class="brand-mark">MCP</span>
        <span v-if="!collapsed" class="brand-text">桥接平台</span>
      </div>
      <el-menu
        :default-active="activeMenu"
        :collapse="collapsed"
        :collapse-transition="false"
        router
        class="menu"
      >
        <el-menu-item v-for="item in visibleMenu" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <template #title>{{ item.title }}</template>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-button text @click="collapsed = !collapsed">
            <el-icon><component :is="collapsed ? Expand : Fold" /></el-icon>
          </el-button>
          <span class="page-title">{{ pageTitle }}</span>
        </div>
        <div class="header-right">
          <el-tooltip
            v-if="protocol && protocol.legacySupported === false"
            placement="bottom"
            :content="`仅支持 MCP ${protocol.supportedProtocolVersion}；legacy 客户端会被显式拒绝（-32022）`"
          >
            <el-tag type="success" effect="plain" size="small">
              Modern-only · {{ protocol.supportedProtocolVersion }}
            </el-tag>
          </el-tooltip>
          <el-dropdown @command="onCommand">
            <span class="user">
              {{ auth.displayName }}
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>
                  角色：{{ auth.roles.length ? auth.roles.join('、') : '无' }}
                </el-dropdown-item>
                <el-dropdown-item command="password" divided>修改密码</el-dropdown-item>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main">
        <!-- 刻意不加 keep-alive：这些页面展示的都是控制面状态，
             缓存下来只会让用户看到陈旧数据还以为发布没生效 -->
        <router-view />
      </el-main>
    </el-container>

    <el-dialog v-model="passwordDialog.visible" title="修改密码" width="420px">
      <el-form label-width="90px" @submit.prevent>
        <el-form-item label="当前密码">
          <el-input v-model="passwordDialog.current" type="password" show-password autocomplete="current-password" />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="passwordDialog.next" type="password" show-password autocomplete="new-password" />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="passwordDialog.confirm" type="password" show-password autocomplete="new-password" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="passwordDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="passwordDialog.saving" @click="submitPassword">
          确认修改
        </el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
}

.aside {
  background: #fff;
  border-right: 1px solid var(--mcp-border);
  transition: width 0.2s;
  overflow-x: hidden;
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 56px;
  padding: 0 16px;
  border-bottom: 1px solid var(--mcp-border);
}

.brand-mark {
  padding: 2px 6px;
  font-weight: 700;
  color: #fff;
  background: #409eff;
  border-radius: 4px;
}

.brand-text {
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
}

.menu {
  border-right: none;
}

.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  background: #fff;
  border-bottom: 1px solid var(--mcp-border);
}

.header-left,
.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.page-title {
  font-size: 15px;
  font-weight: 600;
}

.user {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: #303133;
  cursor: pointer;
  outline: none;
}

.main {
  padding: 0;
  overflow-y: auto;
}
</style>