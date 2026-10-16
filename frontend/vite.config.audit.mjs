// 临时 UI 审计用 Vite 配置（不修改仓库既有 vite.config.js）
// 原因：本机 8080 被另一个项目（RuoYi）占用，HolzynActor 后端改跑 8081。
// 用法：在 frontend 目录执行  npx vite --config vite.config.audit.mjs
// 审计结束后可直接删除本文件。
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5175,
    proxy: {
      '/api': { target: 'http://localhost:8081', changeOrigin: true },
      '/uploads': { target: 'http://localhost:8081', changeOrigin: true }
    }
  }
})