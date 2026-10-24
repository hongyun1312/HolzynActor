import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// Vite 配置：开发服务器 + 后端代理（/api、/uploads 转发至 Spring Boot；登录/鉴权已移除，无 /oauth2、/logout 代理）
// @ 别名 -> src：功能域（features/）+ 共享层（shared/）统一引用方式，目录重组不受相对路径深度影响
// 后端地址：默认 18080（与 backend/src/main/resources/application.yml 的 server.port 同源，
// 2026-10-14 由 8080 迁移——8080 易被其他项目占用）；需要临时改端口时设 HOLZYN_ACTOR_BACKEND_URL。
const backend = process.env.HOLZYN_ACTOR_BACKEND_URL || 'http://localhost:18080'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  // 生产构建拆包（V2.0 §8.3）。Vite 8 底层是 rolldown，用 output.codeSplitting.groups（不是
  // rollup 的 manualChunks）。拆包目的不是"数字好看"，而是两件真事：
  //   ① 变更局部化——升级 Element Plus 不该让 vue 核心缓存在用户机器上失效；
  //   ② 让「首屏到底下了哪些字节」可被 first-paint.mjs 直接量出来（真省字节靠**异步加载**：
  //      echarts 从静态 import 改成按需 import 后，首屏实测 JS 884.1 KB → 519.3 KB）。
  // ⚠ 三个实测约束（都踩过）：
  //   · 不要加 `maxSize`（组级或全局）：把 element-plus 内部模块按体积切成多片会让应用**根本挂不起来**
  //     （pageerror `$e is not a function`、挂载点空）——同库内部模块落到不同 chunk 后初始化顺序不成立。
  //     这也解释了为什么"单 chunk < 500 kB"这条原始指标要改判（见方案 §13.5）。
  //   · 不要加 catch-all `vendor` 组：`test: /node_modules/` 会把本应留在异步 chunk 里的模块
  //     提升成静态共享 chunk（未验证到崩，但会改变首屏集合）。
  //   · 分组只决定"同一批模块放进哪个文件"，不决定"是否首屏加载"。
  build: {
    rolldownOptions: {
      output: {
        codeSplitting: {
          groups: [
            { name: 'vendor-vue', test: /[\\/]node_modules[\\/](vue|vue-router|pinia|@vue)[\\/]/, priority: 30 },
            // 组件库：体积最大且升级频繁 → 单独一组，升级它不会让 vue 核心缓存失效。
            // ⚠ 不要再给这里加 maxSize：实测（2026-10-15）按 maxSize 把 element-plus 内部模块切成多片
            // 会让应用**根本挂不起来**（页面 `$e is not a function`，挂载点空）——同一库内部模块被拆到
            // 不同 chunk 后初始化顺序不再成立。同理全局 maxSize 也会踩这个坑。
            { name: 'vendor-element', test: /[\\/]node_modules[\\/](element-plus|@element-plus)[\\/]/, priority: 30 },
            { name: 'vendor-echarts', test: /[\\/]node_modules[\\/](echarts|zrender)[\\/]/, priority: 30 },
            { name: 'vendor-g6', test: /[\\/]node_modules[\\/](@antv|d3|d3-[a-z-]+)[\\/]/, priority: 30 },
            // 地图渲染：PixiJS 只在首页地图用，体积约 500KB（未压缩）→ 单独一组，
            // 升级 Pixi 不会让其它 vendor 组失效（本地打包，不走 CDN：决策 #9）。
            { name: 'vendor-pixi', test: /[\\/]node_modules[\\/](pixi\.js|@pixi)[\\/]/, priority: 30 },
            { name: 'vendor-markdown', test: /[\\/]node_modules[\\/](markdown-it|highlight\.js|linkify-it|mdurl|uc\.micro|entities)[\\/]/, priority: 25 }
          ]
        }
      }
    }
  },
  server: {
    port: 5174,
    proxy: {
      '/api': { target: backend, changeOrigin: true },
      '/uploads': { target: backend, changeOrigin: true }
    }
  },
  // 生产构建预览（`npm run build` 后 `npm run preview`，默认 5175）：同样代理 /api 到后端，
  // 这样才能对**真实产物**（分片后的 chunk 集合）做首屏实测——dev server 是按模块即时编译的，
  // 它的 Network 集合与打包产物无关，量不出「首屏实际下载了哪些 chunk」。
  preview: {
    port: 5175,
    proxy: {
      '/api': { target: backend, changeOrigin: true },
      '/uploads': { target: backend, changeOrigin: true }
    }
  }
})
