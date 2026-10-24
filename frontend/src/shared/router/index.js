/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAccountStore } from '@/shared/store'
import { fetchProject } from '@/shared/api'
import { isGuideArmed } from '@/shared/onboarding/firstRunGuide'

/**
 * 世界沙盘 S1：应用启动直达上次项目的一次性标记。
 * <p>语义（用户确认「只写不清」）：每次启动/刷新后首次导航到画廊落地页 时，
 * 若本地账户记录有 lastProjectId 且项目仍存在 → 直达该项目沙盘；之后本次会话内
 * 再点「返回画廊」等导航到 / 不再重定向（回画廊可正常停留）。</p>
 */
let startupRedirectHandled = false

/**
 * HolzynActor 前端路由。
 * 顶层路由：/onboarding（首次设置向导）、/（项目画廊）、/project/new（新增项目）。
 * 项目空间路由（带:projectId，全部位于 ProjectLayout 骨架内）：
 * /project/:id/sandbox（世界沙盘·项目主页，默认落地）、/project/:id/dialogue（对谈·静止世界聊天）、
 * /project/:id/dashboard（项目仪表盘）、
 * /world（世界详情，含 /world/edit 修改页）、/characters（NPC 角色）、/chat（对话·单聊/群聊）、
 * /settings（设置：general/apis/prompts/usage）、
 * /crowds（普通型人群）、/timeline（时间线）、/knowledge（知识库）
 * —— 后三个原为隐藏页， 恢复侧边栏入口。
 * 已移除：/login、/register（登录鉴权整体移除，首次启动引导本地账户设置）；
 * /evolve（世界演化旧路由，M4 起能力拆入新首页右栏「世界演化面板」+ 对戏抽屉，路由一并移除）。
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

  // ==================== 新手上手教程（全局，随时可回） ====================
  // 与「界面导览」互补：导览解释界面为什么长这样（一次性、跟随高亮），
  // 教程中心解释该按什么顺序用它（可回看、按章推进、每步带真实入口）。
  {
    path: '/tutorial',
    name: 'tutorial',
    component: () => import('@/features/tutorial/views/TutorialCenterView.vue'),
    meta: { title: '新手上手教程 · HolzynActor' }
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
      { path: '', redirect: '/settings/general' },
      { path: 'general', name: 'global-settings-general', component: () => import('@/features/settings/views/ProjectGeneralSettings.vue'), meta: { requiresAccount: true, title: '通用设置 · HolzynActor', domain: 'settings' } },
      { path: 'apis', name: 'global-settings-apis', component: () => import('@/features/settings/views/ProjectApiSettings.vue'), meta: { requiresAccount: true, title: 'API 配置 · HolzynActor', domain: 'settings' } },
      { path: 'prompts', name: 'global-settings-prompts', component: () => import('@/features/settings/views/ProjectPromptSettings.vue'), meta: { requiresAccount: true, title: 'Prompt 模板 · HolzynActor', domain: 'settings' } },
      { path: 'usage', name: 'global-settings-usage', component: () => import('@/features/settings/views/ProjectUsageSettings.vue'), meta: { requiresAccount: true, title: 'AI 用量 · HolzynActor', domain: 'observe' } },
      // 界面与布局（全局）：面板宽度/信息密度/抽屉固定/导览重看——不依赖具体项目，故项目内外共用同一页
      { path: 'appearance', name: 'global-settings-appearance', component: () => import('@/features/settings/views/ProjectAppearanceSettings.vue'), meta: { requiresAccount: true, title: '界面与布局 · HolzynActor', domain: 'settings' } },
      // 项目级 AI 助手（M1+M2）：能力说明 + docs 索引管理与工具清单
      { path: 'assistant', name: 'global-settings-assistant', component: () => import('@/features/settings/views/ProjectAssistantSettings.vue'), meta: { requiresAccount: true, title: '助手设置 · HolzynActor', domain: 'settings' } },
      // 玩家档案（M3）：persona 六维（用户级）；每角色关系设定需在项目内编辑
      { path: 'player-profile', name: 'global-settings-player-profile', component: () => import('@/features/settings/views/PlayerProfileView.vue'), meta: { requiresAccount: true, title: '玩家档案 · HolzynActor', domain: 'settings' } },
      // 更新与版本（U1 桌面端更新机制）：当前版本/检查更新/下载进度/数据备份回滚/更新说明
      { path: 'update', name: 'global-settings-update', component: () => import('@/features/settings/views/UpdateSettingsView.vue'), meta: { requiresAccount: true, title: '更新与版本 · HolzynActor', domain: 'settings' } }
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
  // 新建项目（手动添加/文件解析）完成后弹窗可跳转；项目空间仪表盘也可进入重跑。
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
      // 世界沙盘 S1：项目默认落地页 = 世界沙盘（绝对主界面，侧边栏第一位）
      // 注意：相对 redirect 在本项目 vue-router 下会按根路径解析（潜藏坑），此处用函数生成绝对路径
      { path: '', redirect: (to) => `/project/${to.params.id}/sandbox` },
      // 1. 世界地图：项目默认落地页（M4 新首页：2D 地图 + 双主角并置，取代旧 3D 沙盘）
      //    meta.fullbleed 不再需要（三层壳内布局，非全屏 3D 画布）
      { path: 'sandbox', name: 'project-sandbox', component: () => import('@/features/home/views/NewHomeView.vue'), meta: { title: '世界地图 · HolzynActor', domain: 'world' } },
      // V2.0 §4.2 P4：世界直播（纯前端聚合：事件流 + 角色当前行动 + 宏观指标 + 世界时钟）
      { path: 'live', name: 'project-live', component: () => import('@/features/live/views/WorldLiveView.vue'), meta: { title: '世界直播 · HolzynActor', domain: 'world' } },
      // 1b. 对谈：与「浮生」主模式同级的**独立界面**（对谈界面的侧边栏由 DIALOGUE_ITEMS 提供）
      //     入口在左上角世界名右侧的「浮生 ⇄ 对谈」切换按钮；/dialogue 子树全部属于对谈界面
      { path: 'dialogue', name: 'project-dialogue', component: () => import('@/features/dialogue/views/DialogueView.vue'), meta: { title: '对谈 · HolzynActor', domain: 'dialogue' } },
      // 1c. 对谈 · 玩家档案：玩家 persona 六维 + 「与我的关系」设定（对谈界面内独立入口；
      //     与旧 settings/player-profile 路由复用同一组件，旧深链仍可用）
      { path: 'dialogue/player-profile', name: 'project-dialogue-player', component: () => import('@/features/settings/views/PlayerProfileView.vue'), meta: { title: '玩家档案 · HolzynActor', domain: 'dialogue' } },
      // 1d. 对谈 · 设置：设置是浮生/对谈**共有**的功能（API 配置 / Prompt / 用量 / 界面 / 助手），
      //     因此在对谈界面侧边栏同样提供入口。路由挂在 /dialogue/settings/* 子树下、复用同一套
      //     设置子页组件（SettingsView 外壳 + 六个子页），从而打开设置时界面**保持「对谈」**，
      //     不会因跳转 /project/:id/settings/* 而切回浮生（见 navModel.DIALOGUE_SETTINGS_ITEMS）。
      //     子页间切换由设置页内部 PageTabs 页内导航标签承担（域侧栏常驻，不再用「设置轨道」顶替侧栏）。
      {
        path: 'dialogue/settings',
        component: () => import('@/features/settings/views/SettingsView.vue'),
        meta: { title: '设置 · HolzynActor', domain: 'dialogue' },
        children: [
          { path: '', redirect: (to) => `/project/${to.params.id}/dialogue/settings/general` },
          { path: 'general', name: 'project-dialogue-settings-general', component: () => import('@/features/settings/views/ProjectGeneralSettings.vue'), meta: { title: '通用设置 · HolzynActor', domain: 'dialogue' } },
          { path: 'apis', name: 'project-dialogue-settings-apis', component: () => import('@/features/settings/views/ProjectApiSettings.vue'), meta: { title: 'API 配置 · HolzynActor', domain: 'dialogue' } },
          { path: 'prompts', name: 'project-dialogue-settings-prompts', component: () => import('@/features/settings/views/ProjectPromptSettings.vue'), meta: { title: 'Prompt 模板 · HolzynActor', domain: 'dialogue' } },
          { path: 'usage', name: 'project-dialogue-settings-usage', component: () => import('@/features/settings/views/ProjectUsageSettings.vue'), meta: { title: 'AI 用量 · HolzynActor', domain: 'dialogue' } },
          { path: 'appearance', name: 'project-dialogue-settings-appearance', component: () => import('@/features/settings/views/ProjectAppearanceSettings.vue'), meta: { title: '界面与布局 · HolzynActor', domain: 'dialogue' } },
          { path: 'assistant', name: 'project-dialogue-settings-assistant', component: () => import('@/features/settings/views/ProjectAssistantSettings.vue'), meta: { title: '助手设置 · HolzynActor', domain: 'dialogue' } },
          { path: 'update', name: 'project-dialogue-settings-update', component: () => import('@/features/settings/views/UpdateSettingsView.vue'), meta: { title: '更新与版本 · HolzynActor', domain: 'dialogue' } }
        ]
      },
      // 2. 项目仪表盘：概况 + 统计 + 最近动态
      { path: 'dashboard', name: 'project-dashboard', component: () => import('@/features/project/views/DashboardView.vue'), meta: { title: '项目仪表盘 · HolzynActor', domain: 'observe' } },
      // 2b. 世界图谱（观测域）：时间/地点/角色/事件/关系/势力/宏观七个只读切面
      { path: 'graph', name: 'project-world-graph', component: () => import('@/features/observe/views/WorldGraphView.vue'), meta: { title: '世界图谱 · HolzynActor', domain: 'observe' } },
      // 2. 世界详情（只读全文）+ 专属修改页（/world/edit）
      { path: 'world', name: 'project-world', component: () => import('@/features/world/views/WorldDetailView.vue'), meta: { title: '世界详情 · HolzynActor', domain: 'world' } },
      { path: 'world/edit', name: 'project-world-edit', component: () => import('@/features/world/views/WorldEditView.vue'), meta: { title: '修改世界 · HolzynActor', domain: 'world' } },
      // 3. 角色详情：NPC 角色（特殊型）
      { path: 'characters', name: 'project-characters', component: () => import('@/features/character/views/CharacterView.vue'), meta: { title: 'NPC 角色 · HolzynActor', domain: 'cast' } },
      // 4. 对话（单聊）
      { path: 'chat', name: 'project-chat', component: () => import('@/features/chat/views/ChatView.vue'), meta: { title: '对话 · HolzynActor', domain: 'cast' } },
      // 5. 设置（设置子页由设置页内部 PageTabs 导航：general/apis/prompts/usage/appearance/assistant/player-profile/update）
      {
        path: 'settings',
        component: () => import('@/features/settings/views/SettingsView.vue'),
        meta: { title: '设置 · HolzynActor', domain: 'settings' },
        children: [
          { path: '', redirect: (to) => `/project/${to.params.id}/settings/general` },
          { path: 'general', name: 'project-settings-general', component: () => import('@/features/settings/views/ProjectGeneralSettings.vue'), meta: { title: '通用设置 · HolzynActor', domain: 'settings' } },
          { path: 'apis', name: 'project-settings-apis', component: () => import('@/features/settings/views/ProjectApiSettings.vue'), meta: { title: 'API 配置 · HolzynActor', domain: 'settings' } },
          { path: 'prompts', name: 'project-settings-prompts', component: () => import('@/features/settings/views/ProjectPromptSettings.vue'), meta: { title: 'Prompt 模板 · HolzynActor', domain: 'settings' } },
          { path: 'usage', name: 'project-settings-usage', component: () => import('@/features/settings/views/ProjectUsageSettings.vue'), meta: { title: 'AI 用量 · HolzynActor', domain: 'observe' } },
          // 界面与布局（新增）：面板宽度、信息密度、内容宽度、抽屉固定、导览重看
          { path: 'appearance', name: 'project-settings-appearance', component: () => import('@/features/settings/views/ProjectAppearanceSettings.vue'), meta: { title: '界面与布局 · HolzynActor', domain: 'settings' } },
          // 项目级 AI 助手（M1+M2）
          { path: 'assistant', name: 'project-settings-assistant', component: () => import('@/features/settings/views/ProjectAssistantSettings.vue'), meta: { title: '助手设置 · HolzynActor', domain: 'settings' } },
          // 玩家档案（M3）：persona 六维 + 本项目角色关系设定/关系行
          { path: 'player-profile', name: 'project-settings-player-profile', component: () => import('@/features/settings/views/PlayerProfileView.vue'), meta: { title: '玩家档案 · HolzynActor', domain: 'settings' } },
          // 更新与版本（U1 桌面端更新机制）
          { path: 'update', name: 'project-settings-update', component: () => import('@/features/settings/views/UpdateSettingsView.vue'), meta: { title: '更新与版本 · HolzynActor', domain: 'settings' } }
        ]
      },

      // ===== 恢复页（从隐藏恢复：入口已回到侧边栏，路由与页面均可用） =====
      // 普通型人群
      { path: 'crowds', name: 'project-crowds', component: () => import('@/features/crowd/views/CrowdView.vue'), meta: { title: '普通型人群 · HolzynActor', domain: 'cast' } },
      // 时间线（竖轴 + 横红线 + 精度切换）
      { path: 'timeline', name: 'project-timeline', component: () => import('@/features/timeline/views/TimelineView.vue'), meta: { title: '时间线 · HolzynActor', domain: 'world' } },
      // 知识库（文档 / 检索 / 项目大事记）
      { path: 'knowledge', name: 'project-knowledge', component: () => import('@/features/knowledge/views/KnowledgeView.vue'), meta: { title: '知识库 · HolzynActor', domain: 'mind' } },
      // 项目记忆
      { path: 'world-memory', name: 'project-world-memory', component: () => import('@/features/worldmemory/views/WorldMemoryView.vue'), meta: { title: '项目记忆 · HolzynActor', domain: 'mind' } },
      // 全局关系拓扑（UI V3.0 工作台：整页画布 + 浮动工具条/图例/状态条 + 可收起详情栏）
      // fullbleed：去掉外壳内边距并禁滚动，画布直接铺满内容区（工作台类页面的标准形态）
      { path: 'topology', name: 'project-topology', component: () => import('@/features/character/views/TopologyView.vue'), meta: { title: '关系拓扑 · HolzynActor', domain: 'cast', fullbleed: true } },
      // 记忆归档
      { path: 'archive', name: 'project-archive', component: () => import('@/features/archive/views/ArchiveView.vue'), meta: { title: '记忆归档 · HolzynActor', domain: 'mind' } },
      // 家族谱系
      { path: 'lineage', name: 'project-lineage', component: () => import('@/features/lineage/views/LineageView.vue'), meta: { title: '家族谱系 · HolzynActor', domain: 'cast' } }
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

  // 世界沙盘 S1：应用启动直达上次所在项目（仅画廊落地页首次导航触发；会话内回画廊不再重定向）
  // v1.1.0-beta.2：新用户引导「待启动」时屏蔽直达——刚完成首次向导的用户必须落在画廊看到引导
  // （否则「安装完成 → 直接进示例世界」绕过了引导；待启动标记由 FirstRunGuide 消费后清除）。
  if (!startupRedirectHandled && account.isOnboarded && to.path === '/' && !isGuideArmed()) {
    startupRedirectHandled = true
    const lastId = account.lastProjectId
    if (lastId) {
      try {
        const p = await fetchProject(lastId)
        if (p && p.deleted !== 1) {
          return { path: `/project/${lastId}/sandbox` }
        }
      } catch (_) {
        // 项目不存在/已删除/后端未就绪 → 留在画廊
      }
    }
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
