import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// Vite 配置：开发服务器 + 后端代理（/api、/uploads 转发至 Spring Boot 8080；登录/鉴权已移除，无 /oauth2、/logout 代理）
// @ 别名 -> src：功能域（features/）+ 共享层（shared/）统一引用方式，目录重组不受相对路径深度影响
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5174,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/uploads': { target: 'http://localhost:8080', changeOrigin: true }
    }
  }
})
