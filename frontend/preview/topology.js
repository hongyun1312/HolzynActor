/*
 * 关系拓扑视觉预览入口（仅开发用；`vite` 会把它当独立页面 /preview/topology.html 提供）。
 *
 * 为什么需要它：星海布局的观感（夜空、光晕、牵引手感）只有在**真实画布尺寸 + 真实工作台**
 * 下才看得出来，而启动整个应用要独占 H2 文件库、还要有项目数据。本页用 axios 适配器
 * 把 `GET /api/worlds/1/character-relations` 换成一村子假数据，其余（工具栏、图例、
 * 详情栏、画布、主题、布局）全部是真实组件，专供截图核对。
 *
 * 查询参数：theme=light|dark、layout=nebula|community|...、density=compact|cozy|comfortable
 */
import { createApp, h } from 'vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHashHistory } from 'vue-router'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import '@/shared/styles/fonts.css'
import '@/shared/styles/variables.css'
import '@/shared/styles/global.css'
import HIcon from '@/shared/components/HIcon.vue'
import http from '@/shared/api/http'
import { useLayoutStore } from '@/shared/store/layout'
import { buildMockWorld } from './worldMock.js'
import TopologyPreview from './TopologyPreview.vue'

const params = new URLSearchParams(location.search)
const theme = params.get('theme') === 'dark' ? 'dark' : 'light'
document.documentElement.setAttribute('data-theme', theme)
const density = params.get('density') || 'cozy'

// ==================== 网络层替身 ====================
// 只替换数据源，不改组件：组件里任何真实请求都可能打到后端（本页不允许产生副作用）
const world = buildMockWorld()
http.defaults.adapter = async (config) => {
  const url = String(config.url || '')
  if (url.includes('/character-relations')) {
    return { data: { code: 200, message: 'ok', data: world }, status: 200, statusText: 'OK', headers: {}, config }
  }
  return { data: { code: 200, message: 'ok', data: {} }, status: 200, statusText: 'OK', headers: {}, config }
}
// SSE 替身：本页不连实时（variant=page 且 active=false 时不会订阅），兜底防止重连噪声
if (!window.__noEventSource) {
  window.EventSource = class {
    constructor() { this.readyState = 0 }
    addEventListener() {}
    removeEventListener() {}
    close() {}
  }
}

const router = createRouter({
  history: createWebHashHistory(),
  routes: [{ path: '/:pathMatch(.*)*', component: { render: () => null } }]
})

const app = createApp({
  render: () => h(TopologyPreview, {
    layout: params.get('layout') || 'nebula',
    variant: params.get('variant') === 'tab' ? 'tab' : 'page'
  })
})
const pinia = createPinia()
app.use(pinia)
app.use(router)
app.use(ElementPlus)
app.component('HIcon', HIcon)
const layoutStore = useLayoutStore(pinia)
layoutStore.setDensity(density)
router.isReady().then(() => app.mount('#app'))
