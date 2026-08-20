import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from '@/shared/router'
import '@/shared/styles/variables.css'
import '@/shared/styles/global.css'
import { initTheme } from '@/shared/theme'
import HIcon from '@/shared/components/HIcon.vue'

// 应用入口：注册 Pinia / 路由 / Element Plus（主题由 design tokens 驱动）+ HarmonyOS 图标组件
// 说明：登录/鉴权已移除，无 /login 路由；首次启动由本地账户 store 引导首次设置向导。
// 主题：启动前先应用持久化主题（深浅模式 + 主色），避免闪烁。
// 图标：2026-08-18 全量更换为华为鸿蒙图标（HIcon 全局组件；Element Plus 图标注册已移除，
// 所有 el-icon 内部统一用 <HIcon name="..."/> 渲染）。
initTheme()

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus)
// 全局注册 HarmonyOS 图标组件（name 传 Element 图标名或 ic_public_xxx 文件名）
app.component('HIcon', HIcon)
app.mount('#app')

// 字体加载状态诊断：DevTools Console 直接调用 window.__holzynFontStatus()
// 逐个字重检查 HarmonyOS Sans SC 是否已加载（document.fonts.check 为 true 表示可用）。
// 说明：@font-face 为 font-display: swap，浏览器按需下载；返回某字重 false 不一定是失败，
// 可能只是该字重尚未被页面文本使用（未触发下载），用 Network 面板确认对应 ttf 请求即可。
window.__holzynFontStatus = () => {
  const weights = [
    { weight: 100, file: 'HarmonyOS_Sans_SC_Thin' },
    { weight: 300, file: 'HarmonyOS_Sans_SC_Light' },
    { weight: 400, file: 'HarmonyOS_Sans_SC_Regular' },
    { weight: 500, file: 'HarmonyOS_Sans_SC_Medium' },
    { weight: 700, file: 'HarmonyOS_Sans_SC_Bold' },
    { weight: 900, file: 'HarmonyOS_Sans_SC_Black' }
  ]
  return weights.map(w => ({
    weight: w.weight,
    file: w.file,
    loaded: document.fonts.check(`${w.weight} 16px "HarmonyOS Sans SC"`, '中文字体')
  }))
}
