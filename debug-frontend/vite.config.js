import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// HolzynActor 调试前端 Vite 配置（2026-08-20）。
// - dev 端口 5175（与主前端 5174 隔离）；/api 代理到后端
// - 后端地址：默认 18080（与 backend application.yml server.port 同源，2026-10-14 由 8080 迁移）；
//   需要临时改端口时设 HOLZYN_ACTOR_BACKEND_URL
// - base './'：构建产物可部署到任意子路径（jar 内 static/debug/，经后端 /debug 转发）
// - hash 路由（index.html 内跳转不依赖后端路由转发）
const backend = process.env.HOLZYN_ACTOR_BACKEND_URL || 'http://localhost:18080'

export default defineConfig({
  plugins: [vue()],
  base: './',
  server: {
    port: 5175,
    proxy: {
      '/api': { target: backend, changeOrigin: true }
    }
  }
})
