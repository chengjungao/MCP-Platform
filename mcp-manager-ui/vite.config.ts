import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发期用代理把 /api 打到本机 Manager，省掉 CORS 与「前端要配后端地址」这两件事。
// 生产期由 nginx 同源反代（见 deploy/docker/ui-nginx.conf），前端代码里永远只写相对路径。
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.VITE_PROXY_TARGET ?? 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    // Element Plus 全量引入，单包体积偏大是已知取舍：内部控制台首屏不如可维护性重要。
    // 但仍要把它和业务代码拆开——否则每次发版都会让 1MB 的 UI 库缓存跟着失效，
    // 而 UI 库其实几个月才升一次。
    rollupOptions: {
      output: {
        manualChunks(id: string) {
          if (!id.includes('node_modules')) return undefined
          if (id.includes('element-plus')) return 'element-plus'
          if (id.includes('@vue') || id.includes('/vue/') || id.includes('vue-router') || id.includes('pinia')) {
            return 'vue'
          }
          return 'vendor'
        }
      }
    },
    chunkSizeWarningLimit: 1000
  }
})