import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// HolzynActor 调试前端 Vite 配置（2026-08-20）。
// - dev 端口 5175（与主前端 5174 隔离）；/api 代理到后端 8080
// - base './'：构建产物可部署到任意子路径（jar 内 static/debug/，经后端 /debug 转发）
// - hash 路由（index.html 内跳转不依赖后端路由转发）
export default defineConfig({
  plugins: [vue()],
  base: './',
  server: {
    port: 5175,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true }
    }
  }
})
