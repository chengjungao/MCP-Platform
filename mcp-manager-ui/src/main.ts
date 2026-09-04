import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'

import 'element-plus/dist/index.css'
import './styles/index.css'

import App from './App.vue'
import router from './router'
import { onUnauthorized } from './api/http'
import { useAuthStore } from './stores/auth'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
app.use(ElementPlus)

// 401 统一处理放在这里而不是 axios 模块内部：http.ts 不认识 router，
// 认识 router 的地方不该在拦截器里被 import（循环依赖）
const auth = useAuthStore(pinia)
onUnauthorized(() => {
  auth.clear()
  const current = router.currentRoute.value
  if (current.path !== '/login') {
    void router.replace({ path: '/login', query: { redirect: current.fullPath } })
  }
})

app.mount('#app')