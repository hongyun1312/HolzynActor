import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAccountStore } from '@/shared/store'

/**
 * HolzynActor 前端路由（模块化重构 V1.0，功能域划分 + 本地单用户）。
 *
 * 顶层路由：/onboarding（首次设置向导）、/（项目画廊）、/project/new（新增项目）。
 * 项目空间路由（带 :projectId，全部位于 ProjectLayout 骨架内）：
 *   /project/:id/dashboard（项目仪表盘）、/world（世界详情，含 /world/edit 修改页）、
 *   /characters（NPC 角色）、/chat（对话·单聊/群聊）、/settings（设置：general/apis/prompts/usage）、
 *   /crowds（普通型人群）、/evolve（世界演化）、/timeline（时间线）、/knowledge（知识库）
 *   —— 后四个原为隐藏页，2026-08-18 恢复侧边栏入口。
 * 已移除：/login、/register（登录鉴权整体移除，首次启动引导本地账户设置）。
 */
const routes = [
  // ==================== 首次设置向导（本地个人账户） ====================
  {
    path: '/onboarding',
    name: 'onboarding',
    component: () => import('@/features/account/views/FirstRunSetup.vue'),
    meta: { title: '本地账户设置 · HolzynActor' }
  },

  // ==================== 本地账户设置（全局，随时可编辑） ====================
  {
    path: '/account',
    name: 'account-settings',
    component: () => import('@/features/account/views/AccountSettingsView.vue'),
    meta: { requiresAccount: true, title: '本地账户设置 · HolzynActor' }
  },

  // ==================== 全局设置（首页/未进入项目时，复用项目设置四子页） ====================
  // 与项目空间内的 /project/:id/settings/* 共用同一套 SettingsView 外壳与四个子页组件；
  // 差异：API 配置 / Prompt 模板为「用户级默认/覆盖」（projectId 为空），AI 用量统计全部项目总用量。
  {
    path: '/settings',
    name: 'global-settings',
    component: () => import('@/features/settings/views/SettingsView.vue'),
    meta: { requiresAccount: true, title: '设置 · HolzynActor' },
    children: [
      { path: '', redirect: 'general' },
      { path: 'general', name: 'global-settings-general', component: () => import('@/features/settings/views/ProjectGeneralSettings.vue'), meta: { requiresAccount: true, title: '通用设置 · HolzynActor' } },
      { path: 'apis', name: 'global-settings-apis', component: () => import('@/features/settings/views/ProjectApiSettings.vue'), meta: { requiresAccount: true, title: 'API 配置 · HolzynActor' } },
      { path: 'prompts', name: 'global-settings-prompts', component: () => import('@/features/settings/views/ProjectPromptSettings.vue'), meta: { requiresAccount: true, title: 'Prompt 模板 · HolzynActor' } },
      { path: 'usage', name: 'global-settings-usage', component: () => import('@/features/settings/views/ProjectUsageSettings.vue'), meta: { requiresAccount: true, title: 'AI 用量 · HolzynActor' } }
    ]
  },

  // ==================== 项目画廊（默认落地页） ====================
  {
    path: '/',
    name: 'gallery',
    component: () => import('@/features/gallery/views/GalleryView.vue'),
    meta: { requiresAccount: true, title: '项目画廊 · HolzynActor' }
  },

  // ==================== 新增项目页（三种创建方式） ====================
  {
    path: '/project/new',
    name: 'project-new',
    component: () => import('@/features/gallery/views/NewProjectView.vue'),
    meta: { requiresAccount: true, title: '新增项目 · HolzynActor' }
  },

  // ==================== 专属世界初始化页（独立全屏，不进项目空间侧边栏） ====================
  // 2026-08-19 新增：新建项目（手动添加/文件解析）完成后弹窗可跳转；项目空间仪表盘也可进入重跑。
  // 上半屏横向进度条（6 步节点）+ 下半屏控制台日志（SSE 实时推送后端初始化日志）。
  {
    path: '/project/:id/init',
    name: 'project-init',
    component: () => import('@/features/project/views/WorldInitView.vue'),
    meta: { requiresAccount: true, title: '世界初始化 · HolzynActor' }
  },

  // ==================== 项目空间（悬浮岛侧边栏 + 顶部栏 + 内容区） ====================
  {
    path: '/project/:id',
    component: () => import('@/features/project/ProjectLayout.vue'),
    meta: { requiresAccount: true },
    children: [
      // 默认落在项目仪表盘
      { path: '', redirect: 'dashboard' },
      // 1. 项目仪表盘：概况 + 统计 + 最近动态
      { path: 'dashboard', name: 'project-dashboard', component: () => import('@/features/project/views/DashboardView.vue'), meta: { title: '项目仪表盘 · HolzynActor' } },
      // 2. 世界详情（只读全文）+ 专属修改页（/world/edit）
      { path: 'world', name: 'project-world', component: () => import('@/features/world/views/WorldDetailView.vue'), meta: { title: '世界详情 · HolzynActor' } },
      { path: 'world/edit', name: 'project-world-edit', component: () => import('@/features/world/views/WorldEditView.vue'), meta: { title: '修改世界 · HolzynActor' } },
      // 3. 角色详情：NPC 角色（特殊型）
      { path: 'characters', name: 'project-characters', component: () => import('@/features/character/views/CharacterView.vue'), meta: { title: 'NPC 角色 · HolzynActor' } },
      // 4. 对话（单聊）
      { path: 'chat', name: 'project-chat', component: () => import('@/features/chat/views/ChatView.vue'), meta: { title: '对话 · HolzynActor' } },
      // 5. 设置（默认通用；子页 general/apis/prompts/usage）
      {
        path: 'settings',
        component: () => import('@/features/settings/views/SettingsView.vue'),
        meta: { title: '设置 · HolzynActor' },
        children: [
          { path: '', redirect: 'general' },
          { path: 'general', name: 'project-settings-general', component: () => import('@/features/settings/views/ProjectGeneralSettings.vue'), meta: { title: '通用设置 · HolzynActor' } },
          { path: 'apis', name: 'project-settings-apis', component: () => import('@/features/settings/views/ProjectApiSettings.vue'), meta: { title: 'API 配置 · HolzynActor' } },
          { path: 'prompts', name: 'project-settings-prompts', component: () => import('@/features/settings/views/ProjectPromptSettings.vue'), meta: { title: 'Prompt 模板 · HolzynActor' } },
          { path: 'usage', name: 'project-settings-usage', component: () => import('@/features/settings/views/ProjectUsageSettings.vue'), meta: { title: 'AI 用量 · HolzynActor' } }
        ]
      },

      // ===== 恢复页（2026-08-18 从隐藏恢复：入口已回到侧边栏，路由与页面均可用） =====
      // 普通型人群
      { path: 'crowds', name: 'project-crowds', component: () => import('@/features/crowd/views/CrowdView.vue'), meta: { title: '普通型人群 · HolzynActor' } },
      // 世界演化（世界模拟控制台）
      { path: 'evolve', name: 'project-evolve', component: () => import('@/features/evolve/views/EvolveView.vue'), meta: { title: '世界演化 · HolzynActor' } },
      // 时间线（竖轴 + 横红线 + 精度切换）
      { path: 'timeline', name: 'project-timeline', component: () => import('@/features/timeline/views/TimelineView.vue'), meta: { title: '时间线 · HolzynActor' } },
      // 知识库（文档 / 检索 / 项目大事记）
      { path: 'knowledge', name: 'project-knowledge', component: () => import('@/features/knowledge/views/KnowledgeView.vue'), meta: { title: '知识库 · HolzynActor' } },
      // 全局关系拓扑（角色页同款组件 variant=page：整页网络图 + 右上角角色卡片 + 补充跳转）
      { path: 'topology', name: 'project-topology', component: () => import('@/features/character/views/TopologyView.vue'), meta: { title: '关系拓扑 · HolzynActor' } }
    ]
  },

  // ==================== 旧路由收敛（全部重定向到画廊） ====================
  { path: '/projects', redirect: '/' },
  { path: '/chat', redirect: '/' },
  { path: '/actions', redirect: '/' },
  { path: '/crowd', redirect: '/' },
  { path: '/knowledge', redirect: '/' },
  // 兜底：未知路径回画廊
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({ history: createWebHistory(), routes, scrollBehavior() { return { top: 0 } } })

/**
 * 全局守卫（本地单用户，无登录鉴权）。
 * 职责：进入受保护页面（画廊/新增/项目空间）前确保本地账户已加载；
 * 未完成首次设置向导时重定向到 /onboarding；向导页本身放行。
 * 说明：/settings、/account、/onboarding 始终放行——新账户即使未完成首次向导，
 * 也必须能进入设置页/账户页配置用户级 API（否则「首页设置进不去、解析文件无法用」）。
 */
router.beforeEach(async (to) => {
  const account = useAccountStore()
  // 幂等加载本地账户（仅首次真正请求 /api/local-account/me）
  if (!account.loaded) {
    try { await account.loadAccount() } catch (_) { /* 后端未就绪时按未完成向导处理，进入向导页可重试 */ }
  }
  // 设置 / 账户 / 首次向导：无条件放行（配置 API 不依赖完成首次向导）
  if (to.path === '/onboarding' || to.path === '/account' || to.path.startsWith('/settings')) {
    return true
  }
  if (to.meta?.requiresAccount && !account.isOnboarded) {
    return { path: '/onboarding', query: { redirect: to.fullPath } }
  }
  return true
})

router.afterEach((to) => { if (to.meta?.title) document.title = to.meta.title })

/**
 * 全局导航错误兜底：懒加载路由组件失败 / 守卫异常时给出可见提示，
 * 避免「点击无反应」——便于定位（如某个页面模块加载失败）。
 */
router.onError((error) => {
  console.error('[router] 导航失败:', error)
  try {
    ElMessage.error('页面加载失败：' + (error?.message || '未知错误，请刷新重试'))
  } catch (_) { /* 忽略提示失败 */ }
})

export default router
