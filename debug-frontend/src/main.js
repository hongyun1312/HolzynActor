import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import { initTheme } from './theme'
import './styles/variables.css'
import './styles/global.css'

// 调试前端入口：独立应用（与主前端完全隔离），hash 路由，Element Plus 全量引入。
// 主题：与主前端同源的 DSH 令牌体系（deep/light + 主色换肤），启动前应用持久化主题避免闪烁。
initTheme()

const app = createApp(App)
app.use(router)
app.use(ElementPlus)
app.mount('#app')
