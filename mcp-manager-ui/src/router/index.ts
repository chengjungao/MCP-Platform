import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

import { useAuthStore } from '@/stores/auth'

declare module 'vue-router' {
  interface RouteMeta {
    /** 浏览器标题与面包屑。 */
    title?: string
    /** 进入该页所需的权限点；缺省表示只需登录。 */
    permission?: string
    /** 无需登录即可访问。 */
    public?: boolean
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', public: true }
  },
  {
    path: '/',
    component: () => import('@/layouts/DefaultLayout.vue'),
    children: [
      {
        path: '',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '概览' }
      },
      {
        // 不进左侧菜单：注册与解析是 MCP Server 管理的二级功能，
        // 入口在 Server 详情页「上游服务」tab；此路由保留为 URL 直达的全局排查视图
        path: 'registrations',
        name: 'registrations',
        component: () => import('@/views/RegistrationListView.vue'),
        meta: { title: '注册与解析', permission: 'registration:read' }
      },
      {
        path: 'servers',
        name: 'servers',
        component: () => import('@/views/ServerListView.vue'),
        meta: { title: 'MCP Server', permission: 'server:read' }
      },
      {
        path: 'servers/:id(\\d+)',
        name: 'server-detail',
        component: () => import('@/views/ServerDetailView.vue'),
        meta: { title: 'Server 详情', permission: 'server:read' }
      },
      {
        path: 'access',
        name: 'access',
        component: () => import('@/views/AccessView.vue'),
        meta: { title: '访问申请', permission: 'server:read' }
      },
      {
        path: 'clusters',
        name: 'clusters',
        component: () => import('@/views/ClusterListView.vue'),
        meta: { title: '集群与节点', permission: 'cluster:read' }
      },
      {
        path: 'departments',
        name: 'departments',
        component: () => import('@/views/DepartmentView.vue'),
        meta: { title: '部门', permission: 'dept:read' }
      },
      {
        path: 'roles',
        name: 'roles',
        component: () => import('@/views/RoleView.vue'),
        meta: { title: '角色权限', permission: 'role:read' }
      },
      {
        path: 'users',
        name: 'users',
        component: () => import('@/views/UserView.vue'),
        meta: { title: '账号', permission: 'user:read' }
      },
      {
        path: 'audits',
        name: 'audits',
        component: () => import('@/views/AuditView.vue'),
        meta: { title: '审计日志', permission: 'audit:read' }
      },
      {
        path: '403',
        name: 'forbidden',
        component: () => import('@/views/ForbiddenView.vue'),
        meta: { title: '无权限' }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在', public: true }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (to.meta.public === true) {
    // 已登录还去 /login，多半是刷新或书签，直接回首页
    if (to.name === 'login' && auth.isAuthenticated) return { path: '/' }
    return true
  }

  if (!auth.isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  if (!auth.me) {
    try {
      await auth.loadMe()
    } catch {
      auth.clear()
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }

  if (to.meta.permission && !auth.can(to.meta.permission)) {
    return { path: '/403' }
  }
  return true
})

router.afterEach((to) => {
  const base = import.meta.env.VITE_APP_TITLE ?? 'MCP 桥接平台'
  document.title = to.meta.title ? `${to.meta.title} · ${base}` : base
})

export default router