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
import { fetchCurrentWorld, openWorld, closeWorld } from '@/shared/api'
import { isGuideArmed } from '@/shared/onboarding/firstRunGuide'
import { isDevUnlocked } from '@/shared/composables/useDevMode'
import { consumeSkipStartupRedirect, markDevEntry } from '@/features/dev/devContext'

/**
 * 世界沙盘 S1：应用启动直达上次项目的一次性标记。
 * <p>语义（用户确认「只写不清」）：每次启动/刷新后首次导航到画廊落地页 时，
 * 若目录库记录的"当前挂载世界"仍存在 → 直达该世界沙盘；之后本次会话内
 * 再点「返回画廊」等导航到 / 不再重定向（回世界画廊可正常停留）。</p>
 */
let startupRedirectHandled = false

/**
 * 深链兜底（V1.1.2-beta.1 M5 §3.12）：最近一次已确认挂载的世界 id。
 *
 * <p>单活动世界模型下，`/world/:id/**` 的页面全都要读世界库；若那个世界没挂载，
 * 这些接口会 409/空数据，用户看到的是"页面空白"而不是"请先打开世界"。所以每次进入
 * 某个世界的路由前，先把该世界挂上（幂等：已挂载则后端 `newlyOpened=false`）。</p>
 *
 * <p>用缓存避免"每跳一次页就问一次后端"：只有目标 id 与缓存不一致时才真去问。
 * 世界画廊里换世界后缓存会自然失效（id 不同 → 走一次 `/current` 校正）。</p>
 */
let ensuredWorldId = null

/** 深链正则：只认 `/world/<数字>`（`/world/new`、`/world/abc` 不参与，避免把新建页当成世界） */
const WORLD_DEEP_LINK = /^\/world\/(\d+)(?:\/|$)/

/**
 * 清除"已确认挂载"缓存：关闭世界后必须调用。
 *
 * <p>否则「关闭世界 → 回世界画廊 → 用浏览器前进/深链再进同一个世界」会被缓存骗过守卫，
 * 直接进到一个没挂载的世界里（面板全空）。导出给 ProjectLayout 的退出/关闭流程使用。</p>
 */
export function resetEnsuredWorld() {
  ensuredWorldId = null
}

/**
 * HolzynActor 前端路由。
 * 顶层路由：/onboarding（首次设置向导）、/（世界画廊，V1.1.2-beta.1 M5 起）、/world/new（新建世界）。
 * 世界空间路由（带 :id，全部位于 ProjectLayout 骨架内）：
 * /world/:id/sandbox（世界地图·世界主页，默认落地）、/world/:id/dialogue（对谈·静止世界聊天）、
 * /world/:id/dashboard（世界仪表盘）、
 * /world（世界详情，含 /world/edit 修改页）、/characters（NPC 角色）、/chat（对话·单聊/群聊）、
 * /settings（设置：general/apis/prompts/usage）、
 * /crowds（普通型人群）、/timeline（时间线）、/knowledge（知识库）
 * —— 后三个原为隐藏页， 恢复侧边栏入口。
 * 已移除：/login、/register（登录鉴权整体移除，首次启动引导本地账户设置）；
 * /evolve（世界演化旧路由，M4 起能力拆入新首页右栏「世界演化面板」+ 对戏抽屉，路由一并移除）。
 * 路径词汇（M5 §3.13）：对外 HTTP/前端路由一律用 world(s)，Java 实体/服务名/project_id 列保留旧名。
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
  // 与项目空间内的 /world/:id/settings/* 共用同一套 SettingsView 外壳与四个子页组件；
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
      // 决策模型（本机部署）：下载权重 + 便携运行时到本机数据目录，或改用远程 API；用户级配置，与项目无关
      { path: 'decision-model', name: 'global-settings-decision-model', component: () => import('@/features/settings/views/DecisionModelSettings.vue'), meta: { requiresAccount: true, title: '决策模型 · HolzynActor', domain: 'settings' } },
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

  // ==================== 开发者模式控制台（/dev，独立页面 + 桌面独立窗口） ====================
  // 入口：设置 → 更新与版本 → 连点「当前版本」5 次解锁（见 shared/composables/useDevMode.js）。
  // 未解锁时守卫直接踢回设置页——避免 /dev 成为一个「知道地址就能进」的后门。
  // 布局：9 个功能域共用 DevShell 一个外壳（顶栏 + 域侧栏 + 1440 限宽内容列），
  // 页面宽度档位钉在外壳层，切域不会左右抖动。
  // 新增顶层路由必须同步 SpaForwardController 白名单（已加入 /dev、/dev/**）。
  {
    path: '/dev',
    component: () => import('@/features/dev/DevShell.vue'),
    meta: { dev: true, title: '开发者模式 · HolzynActor' },
    children: [
      { path: '', redirect: '/dev/overview' },
      { path: 'overview', name: 'dev-overview', component: () => import('@/features/dev/views/OverviewDevView.vue'), meta: { dev: true, title: '开发者 · 总览' } },
      { path: 'world', name: 'dev-world', component: () => import('@/features/dev/views/WorldDevView.vue'), meta: { dev: true, title: '开发者 · 世界引擎' } },
      { path: 'npcs', name: 'dev-npcs', component: () => import('@/features/dev/views/NpcDevView.vue'), meta: { dev: true, title: '开发者 · 角色与状态' } },
      { path: 'npcs/:id', name: 'dev-npc-detail', component: () => import('@/features/dev/views/NpcDetailDevView.vue'), meta: { dev: true, title: '开发者 · 角色全息详情' } },
      { path: 'agent', name: 'dev-agent', component: () => import('@/features/dev/views/AgentDevView.vue'), meta: { dev: true, title: '开发者 · 决策与认知' } },
      { path: 'memory', name: 'dev-memory', component: () => import('@/features/dev/views/MemoryDevView.vue'), meta: { dev: true, title: '开发者 · 记忆' } },
      { path: 'ai', name: 'dev-ai', component: () => import('@/features/dev/views/AiDevView.vue'), meta: { dev: true, title: '开发者 · AI 调用与 Prompt' } },
      { path: 'data', name: 'dev-data', component: () => import('@/features/dev/views/DataDevView.vue'), meta: { dev: true, title: '开发者 · 数据' } },
      { path: 'config', name: 'dev-config', component: () => import('@/features/dev/views/ConfigDevView.vue'), meta: { dev: true, title: '开发者 · 配置与调参' } },
      { path: 'tools', name: 'dev-tools', component: () => import('@/features/dev/views/ToolsDevView.vue'), meta: { dev: true, title: '开发者 · 工具' } }
    ]
  },

  // ==================== 世界画廊（默认落地页，V1.1.2-beta.1 M5） ====================
  {
    path: '/',
    name: 'gallery',
    component: () => import('@/features/gallery/views/SaveLibraryView.vue'),
    meta: { requiresAccount: true, title: '世界画廊 · HolzynActor' }
  },

  // ==================== 新建世界页（三种创建方式） ====================
  {
    path: '/world/new',
    name: 'project-new',
    component: () => import('@/features/gallery/views/NewProjectView.vue'),
    meta: { requiresAccount: true, title: '新建世界 · HolzynActor' }
  },

  // ==================== 专属世界初始化页（独立全屏，不进项目空间侧边栏） ====================
  // 新建项目（手动添加/文件解析）完成后弹窗可跳转；项目空间仪表盘也可进入重跑。
  // 上半屏横向进度条（6 步节点）+ 下半屏控制台日志（SSE 实时推送后端初始化日志）。
  {
    path: '/world/:id/init',
    name: 'project-init',
    component: () => import('@/features/project/views/WorldInitView.vue'),
    meta: { requiresAccount: true, title: '世界初始化 · HolzynActor' }
  },

  // ==================== 项目空间（悬浮岛侧边栏 + 顶部栏 + 内容区） ====================
  {
    path: '/world/:id',
    component: () => import('@/features/project/ProjectLayout.vue'),
    meta: { requiresAccount: true },
    children: [
      // 世界沙盘 S1：项目默认落地页 = 世界沙盘（绝对主界面，侧边栏第一位）
      // 注意：相对 redirect 在本项目 vue-router 下会按根路径解析（潜藏坑），此处用函数生成绝对路径
      { path: '', redirect: (to) => `/world/${to.params.id}/sandbox` },
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
      //     不会因跳转 /world/:id/settings/* 而切回浮生（见 navModel.DIALOGUE_SETTINGS_ITEMS）。
      //     子页间切换由设置页内部 PageTabs 页内导航标签承担（域侧栏常驻，不再用「设置轨道」顶替侧栏）。
      {
        path: 'dialogue/settings',
        component: () => import('@/features/settings/views/SettingsView.vue'),
        meta: { title: '设置 · HolzynActor', domain: 'dialogue' },
        children: [
          { path: '', redirect: (to) => `/world/${to.params.id}/dialogue/settings/general` },
          { path: 'general', name: 'project-dialogue-settings-general', component: () => import('@/features/settings/views/ProjectGeneralSettings.vue'), meta: { title: '通用设置 · HolzynActor', domain: 'dialogue' } },
          { path: 'apis', name: 'project-dialogue-settings-apis', component: () => import('@/features/settings/views/ProjectApiSettings.vue'), meta: { title: 'API 配置 · HolzynActor', domain: 'dialogue' } },
          { path: 'decision-model', name: 'project-dialogue-settings-decision-model', component: () => import('@/features/settings/views/DecisionModelSettings.vue'), meta: { title: '决策模型 · HolzynActor', domain: 'dialogue' } },
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
          { path: '', redirect: (to) => `/world/${to.params.id}/settings/general` },
          { path: 'general', name: 'project-settings-general', component: () => import('@/features/settings/views/ProjectGeneralSettings.vue'), meta: { title: '通用设置 · HolzynActor', domain: 'settings' } },
          { path: 'apis', name: 'project-settings-apis', component: () => import('@/features/settings/views/ProjectApiSettings.vue'), meta: { title: 'API 配置 · HolzynActor', domain: 'settings' } },
          // 决策模型（本机部署）：与全局设置共用同一页——部署在本机，与具体项目无关
          { path: 'decision-model', name: 'project-settings-decision-model', component: () => import('@/features/settings/views/DecisionModelSettings.vue'), meta: { title: '决策模型 · HolzynActor', domain: 'settings' } },
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
      // 2026-10 布局审计：取消 meta.fullbleed —— 全宽形态让本页内容从侧栏边缘起画
      // （实测 x=276 / w=1164，其它项目页 x=300 / w=1108），切页时整块画布横向跳 24px。
      // 现在统一进外壳内容列（24px 留白 + 滚动条槽位），画布以卡片形式吃掉页头后的剩余高度。
      { path: 'topology', name: 'project-topology', component: () => import('@/features/character/views/TopologyView.vue'), meta: { title: '关系拓扑 · HolzynActor', domain: 'cast' } },
      // 记忆归档
      { path: 'archive', name: 'project-archive', component: () => import('@/features/archive/views/ArchiveView.vue'), meta: { title: '记忆归档 · HolzynActor', domain: 'mind' } },
      // 家族谱系
      { path: 'lineage', name: 'project-lineage', component: () => import('@/features/lineage/views/LineageView.vue'), meta: { title: '家族谱系 · HolzynActor', domain: 'cast' } }
    ]
  },

  // ==================== 旧路由收敛（全部重定向到画廊） ====================
  { path: '/worlds', redirect: '/' },
  { path: '/chat', redirect: '/' },
  { path: '/actions', redirect: '/' },
  { path: '/crowd', redirect: '/' },
  { path: '/knowledge', redirect: '/' },
  // 兜底：未知路径回画廊
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({ history: createWebHistory(), routes, scrollBehavior() { return { top: 0 } } })

/**
 * 确保目标世界处于挂载状态（幂等）：已挂载则什么都不做。
 *
 * <p>当前挂着别的世界时 `open` 会 409（消息含 `WORLD_BUSY`）：此时先关闭旧世界再开目标。
 * 这是"切世界"的正确语义——旧世界不关闭就永远占着 `world.mv.db` 的独占锁；
 * `close` 会 SHUTDOWN COMPACT 压盘并回写用量摘要，不会丢数据。</p>
 *
 * @param {number} wanted 目标世界 id
 * @returns {Promise<void>} 失败时抛出（调用方负责提示 + 回世界画廊）
 */
async function ensureWorldMounted(wanted) {
  let current = null
  try {
    current = await fetchCurrentWorld()
  } catch (_) {
    current = null
  }
  if (current && current.id === wanted) return
  // 单活动世界：切世界必须「先关当前、再开目标」。
  // 旧实现是直接 open 目标、等报 409 再按失败文案补 close——可 axios 的 message 是
  // "Request failed with status code 409"，永远匹配不到 WORLD_BUSY，于是：
  // ① 从世界内切另一个世界必失败并弹错；② 旧世界仍占着世界库独占锁，回画廊后
  // 也打不开目标世界，只有"先打开原世界 → 回画廊（触发 close）→ 再开目标"才成功。
  if (current && current.id) {
    try {
      await closeWorld(current.id)
    } catch (e) {
      // 关闭失败不直接阻断：下面 open 若仍被占会拿到后端的 409 人话提示
      console.warn('[router] 关闭当前世界失败，继续尝试打开目标世界：', e)
    }
    resetEnsuredWorld()
  }
  await openWorld(wanted)
}

/**
 * 全局守卫（本地单用户，无登录鉴权）。
 * 职责：进入受保护页面（画廊/新增/项目空间）前确保本地账户已加载；
 * 未完成首次设置向导时重定向到 /onboarding；向导页本身放行。
 * 说明：/settings、/account、/onboarding 始终放行——新账户即使未完成首次向导，
 * 也必须能进入设置页/账户页配置用户级 API（否则「首页设置进不去、解析文件无法用」）。
 */
router.beforeEach(async (to, from) => {
  const account = useAccountStore()
  // 幂等加载本地账户（仅首次真正请求 /api/local-account/me）
  if (!account.loaded) {
    try { await account.loadAccount() } catch (_) { /* 后端未就绪时按未完成向导处理，进入向导页可重试 */ }
  }
  // 设置 / 账户 / 首次向导：无条件放行（配置 API 不依赖完成首次向导）
  if (to.path === '/onboarding' || to.path === '/account' || to.path.startsWith('/settings')) {
    return true
  }
  // 开发者模式：未解锁则踢回设置页（解锁状态存 localStorage，连点 5 次版本号写入）
  if (to.path === '/dev' || to.path.startsWith('/dev/')) {
    if (!isDevUnlocked()) {
      ElMessage.warning('开发者模式未解锁：设置 → 更新与版本 → 连点版本号 5 次')
      return { path: '/settings/update' }
    }
    // 记住进入点：开发者页「返回」要原路退回这里（否则会退到设置页、再被画廊守卫带进世界，
    // 在桌面端独立开发者窗口里就表现为「两个一模一样的世界界面」）
    if (from.path && !from.path.startsWith('/dev')) {
      markDevEntry(from.fullPath)
    }
    return true
  }
  if (to.meta?.requiresAccount && !account.isOnboarded) {
    return { path: '/onboarding', query: { redirect: to.fullPath } }
  }

  // 深链兜底守卫（M5 §3.12）：直接访问/刷新 /world/:id/** 时，先把该世界挂上。
  // 单活动世界模型下世界内接口都要求"已挂载"；不在这里补一次 open，用户看到的就是
  // 一堆空面板或 409，而不是"这个世界被打开了"。
  // 切世界（顶栏世界切换器）也走这条路：当前挂着 A、目标 B → 先 close(A) 再 open(B)，
  // close 会把 A 压盘并回写摘要，所以自动关闭是安全的，也正是用户点 B 时想要的语义。
  const deepLink = WORLD_DEEP_LINK.exec(to.path)
  if (deepLink) {
    const wanted = Number(deepLink[1])
    if (ensuredWorldId !== wanted) {
      try {
        await ensureWorldMounted(wanted)
        ensuredWorldId = wanted
      } catch (e) {
        ensuredWorldId = null
        ElMessage.error('打开世界失败：' + (e?.message || '请先在世界画廊里打开它'))
        return { path: '/' }
      }
    }
  }

  // 世界沙盘 S1：应用启动直达上次所在项目（仅画廊落地页首次导航触发；会话内回画廊不再重定向）
  // v1.1.0-beta.2：新用户引导「待启动」时屏蔽直达——刚完成首次向导的用户必须落在画廊看到引导
  // （否则「安装完成 → 直接进示例世界」绕过了引导；待启动标记由 FirstRunGuide 消费后清除）。
  // V1.1.2-beta.1 M5：「上次所在项目」改由目录库的"当前挂载世界"提供（GET /api/worlds/current）——
  // 单活动世界模型下，"世界还开着"是唯一的自动直达理由；没挂载就老老实实停在世界画廊。
  if (!startupRedirectHandled && account.isOnboarded && to.path === '/' && !isGuideArmed()
      && !consumeSkipStartupRedirect()) {
    startupRedirectHandled = true
    try {
      const current = await fetchCurrentWorld()
      if (current && current.id && current.mounted !== false) {
        return { path: `/world/${current.id}/sandbox` }
      }
    } catch (_) {
      // 后端未就绪 / 目录库还没有世界 → 留在世界画廊
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
