/*
 * 侧栏视觉预览入口（仅开发用；`vite` 会把它当作独立页面 /preview/sidebar.html 提供）。
 *
 * 为什么需要它：侧栏的真实观感（留白、行高、对齐）只有在**真实外壳几何**下才看得准，
 * 而启动整个应用要独占 H2 文件库、还要有项目数据。本页只挂真实 `<DomainSidebar>` +
 * 真实设计令牌 + 与 AppShell 同构的容器，用一个假项目渲染，专供截图核对。
 *
 * 查询参数：theme=light|dark、density=compact|cozy|comfortable、iface=fusheng|dialogue、
 *           icon=1（图标栏形态）、active=<nav key 对应的相对路径>。
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
import { useLayoutStore } from '@/shared/store/layout'
import SidebarPreview from './SidebarPreview.vue'

const params = new URLSearchParams(location.search)
const theme = params.get('theme') === 'dark' ? 'dark' : 'light'
document.documentElement.setAttribute('data-theme', theme)
// 注意：密度不能只写 data-density —— useShellLayout 会把 store 里的 density 回写到
// <html data-density>，直接写属性会在挂载后被覆盖回 cozy。所以必须改 store。
const density = params.get('density') || 'cozy'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/:pathMatch(.*)*', component: { render: () => null } }
  ]
})
// 先写 hash 再装路由：路由安装时会按当前 location 做首次导航，
// 若在此之后再 push，会被首次导航覆盖回 '/'（高亮就永远为空）。
const start = params.get('active') || 'sandbox'
location.hash = `#/world/1/${start.replace(/^\//, '')}`

const app = createApp({ render: () => h(SidebarPreview) })
const pinia = createPinia()
app.use(pinia)
app.use(router)
app.use(ElementPlus)
app.component('HIcon', HIcon)
// 先定密度/折叠态再挂载：挂载时 useShellLayout 会把 store 值写到 <html> 与 --shell-side-w
const layoutStore = useLayoutStore(pinia)
layoutStore.setDensity(density)
if (params.get('icon') === '1') layoutStore.sidebarCollapsed = true
router.isReady().then(() => app.mount('#app'))
