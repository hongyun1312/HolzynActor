<!--
  Copyright (c) 2026 HONGYUN
  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at
  http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
  SPDX-License-Identifier: Apache-2.0
-->
<template>
  <!--
    AppShell：全站唯一外壳。
    结构：顶栏（图标抽屉 + 搜索 + 显示偏好） / 主体（域侧栏 + 内容区，被固定面板内缩） / 抽屉层 / 全局浮层（命令面板、上下文抽屉、导览）。
    「固定抽屉挤压内容」的实现：抽屉始终绝对定位贴边，而内容区通过 margin 内缩，
    这样只需一层抽屉渲染逻辑，又能得到 flex 布局同等的挤压效果。
  -->
  <div
    class="app-shell"
    :class="{ 'is-fullbleed': fullbleed, 'is-sidebar-overlay': sidebarIsOverlay }"
    :style="shellVars"
  >
    <ShellTopbar
      :mode="mode"
      :project-id="projectId"
      :project="project"
      :breadcrumb="breadcrumb"
      :brand-label="brandLabel"
      :show-back="showBack"
      :show-sidebar-toggle="showSidebar"
      :sidebar-collapsed="effectiveSidebarWidth <= 60"
      @back="onBack"
      @toggle-sidebar="toggleSidebar"
      @open-command="layout.setCommandOpen(true)"
    >
      <template #actions><slot name="topbar-actions"></slot></template>
    </ShellTopbar>

    <div class="shell-body">
      <!-- 主体：侧栏 + 内容区。固定面板绝对定位贴边，内容区靠 margin 内缩（等价于 flex 挤压，
           但只需一层抽屉渲染逻辑）；左侧固定面板让开侧栏，见 DrawerPanel.is-pinned.side-left。 -->
      <div class="shell-main">
        <!-- 域侧栏（或宿主自定义侧栏）；包一层测量容器，供固定面板计算让位宽度 -->
        <div ref="sidebarSlotEl" class="shell-sidebar-slot">
          <slot v-if="!showSidebar" name="sidebar"></slot>
          <DomainSidebar
            v-else
            data-tour="sidebar"
            :project-id="projectId"
            :project="project"
            :is-icon-only="sidebarIsIconOnly"
          />
        </div>

        <!-- 内容区：唯一滚动容器；fullbleed 时去内边距并交给页面自绘 -->
        <main class="shell-content" :class="{ 'is-fullbleed': fullbleed }">
          <slot></slot>
        </main>
      </div>

      <!-- 抽屉层：全部抽屉在这里声明一次；固定/覆盖只由 store 状态决定 -->
      <DrawerPanel
        v-for="d in drawerDefs"
        :key="d.id"
        :id="d.id"
        :title="d.title"
        :icon="d.icon"
        :hint="d.hint"
        :side="d.side"
        :width="d.width"
      >
        <component :is="d.comp" v-bind="d.compProps" />
      </DrawerPanel>

      <!-- 窄屏侧栏遮罩（点击收起） -->
      <div v-if="sidebarIsOverlay" class="shell-mask" @click="layout.setOverlaySidebar(false)"></div>
    </div>

    <!-- 全局浮层 -->
    <CommandPalette />
    <ContextDrawer />
    <GuidedTour />
    <!-- 全局唯一右键上下文菜单（各页/侧栏通过 openContextMenu 调用；这里只挂一次） -->
    <ContextMenu />
  </div>
</template>

<script setup>
/**
 * 应用外壳组件。
 *
 * <p>职责：把「顶栏 + 域侧栏 + 内容区 + 抽屉层 + 全局浮层」组装成唯一骨架，
 * 并集中承担四件跨页面的横切职责：</p>
 * <ol>
 *   <li>**全局快捷键**：读 {@link SHORTCUTS} 分发（命令面板/侧栏/主题/密度/导览/Esc 分层关闭/域跳转）；</li>
 *   <li>**系统命令注册**：把所有导航项与界面开关注册进 {@link commandRegistry}，供命令面板搜索；</li>
 *   <li>**布局数值落地**：把侧栏宽度、固定面板宽度写入内缩样式与 CSS 变量（沙盘 3D 靠它对齐）；</li>
 *   <li>**AI 运行状态轮询**：启动单例轮询，供顶栏活动点与 AI 状态抽屉共用。</li>
 * </ol>
 *
 * <p>模式：`mode="project"` 走域侧栏 + 项目切换；`mode="global"`（画廊/设置等）默认不显示侧栏，
 * 由宿主用 `#sidebar` 插槽注入自己的导航（如设置子页列表）。</p>
 *
 * @see docs/设计文档/HolzynActor_前端布局重构设计规格_V1.0.md §3
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useTheme } from '@/shared/theme'
import { useLayoutStore } from '@/shared/store/layout'
import ShellTopbar from './ShellTopbar.vue'
import DomainSidebar from './DomainSidebar.vue'
import DrawerPanel from './DrawerPanel.vue'
import CommandPalette from './CommandPalette.vue'
import ContextDrawer from './ContextDrawer.vue'
import GuidedTour from './GuidedTour.vue'
import QuickNavDrawer from './QuickNavDrawer.vue'
import ShortcutHelpPanel from './ShortcutHelpPanel.vue'
import ContextMenu from './ContextMenu.vue'
import AiRuntimePanel from './AiRuntimePanel.vue'
import { useShellLayout, SIDEBAR_MARGIN } from './useShellLayout'
import { useAiRuntime } from './useAiRuntime'
import { useContextDrawer } from './useContextDrawer'
import { matchShortcut, isEditableTarget } from './shortcuts'
import { DOMAINS, PINNED_ITEMS, NAV_ITEMS, itemPath } from './navModel'
import { registerCommands, unregisterCommandsByPrefix } from './commandRegistry'
import { DENSITY_MODES } from '@/shared/store/layout'

const props = defineProps({
  /** project | global */
  mode: { type: String, default: 'project' },
  /** 项目 ID（项目模式必填） */
  projectId: { type: Number, default: null },
  /** 项目详情对象（状态卡与项目名） */
  project: { type: Object, default: null },
  /** 面包屑/当前页名 */
  breadcrumb: { type: String, default: '' },
  /** 全局模式品牌标签 */
  brandLabel: { type: String, default: '控制台' },
  /** 是否渲染域侧栏（false 时用 #sidebar 插槽） */
  showSidebar: { type: Boolean, default: true },
  /** 是否显示返回画廊按钮 */
  showBack: { type: Boolean, default: true },
  /**
   * 返回按钮的目标路由（默认回项目画廊）。
   * <p>用 `replace` 而非 `push`：避免「返回后浏览器后退又回到本页」——
   * 对会自动开始执行的页面（如世界初始化 `onMounted(start)`）尤其重要。</p>
   */
  backTo: { type: String, default: '' },
  /** 内容区是否全屏（沙盘 3D） */
  fullbleed: { type: Boolean, default: false }
})

const route = useRoute()
const router = useRouter()
const themeApi = useTheme()
const layout = useLayoutStore()
const ctx = useContextDrawer()
const { effectiveSidebarWidth, sidebarIsIconOnly, toggleSidebar, afterNavigate, shellMode } = useShellLayout()

/** AI 运行状态轮询（顶栏活动点 + AI 抽屉共用一份快照） */
const aiRuntime = useAiRuntime(() => props.projectId)

/** 抽屉声明（内容组件随 projectId 变化，故用 computed 包裹） */
const drawerDefs = computed(() => [
  { id: 'pinned', title: '主工作区', icon: 'ic_gallery_map_all', hint: '沙盘与对话', side: 'left', width: 300,
    comp: QuickNavDrawer, compProps: { domainKey: 'pinned', projectId: props.projectId } },
  { id: 'cast', title: '角色', icon: 'ic_public_contacts', hint: '角色卡 / 人群 / 关系 / 谱系', side: 'left', width: 320,
    comp: QuickNavDrawer, compProps: { domainKey: 'cast', projectId: props.projectId } },
  { id: 'mind', title: '记忆与知识', icon: 'ic_public_albums', hint: '项目记忆 / 归档 / 知识库', side: 'left', width: 320,
    comp: QuickNavDrawer, compProps: { domainKey: 'mind', projectId: props.projectId } },
  { id: 'world', title: '世界设定', icon: 'ic_public_worldclock', hint: '世界详情 / 演化 / 时间线', side: 'left', width: 320,
    comp: QuickNavDrawer, compProps: { domainKey: 'world', projectId: props.projectId } },
  { id: 'ai-runtime', title: 'AI 运行状态', icon: 'ic_public_spinner', hint: '推理中 / 最近调用 / 失败', side: 'right', width: 380,
    comp: AiRuntimePanel, compProps: {} },
  { id: 'help', title: '帮助与快捷键', icon: 'ic_public_file', hint: '上手教程与速查', side: 'right', width: 360,
    comp: ShortcutHelpPanel, compProps: {} }
])

/** 固定抽屉造成的左右内缩量（多个同侧固定面板按各自宽度累加） */
const pinnedLeftWidth = computed(() => drawerDefs.value
  .filter(d => d.side === 'left' && layout.pinnedOpen[d.id])
  .reduce((sum, d) => sum + d.width, 0))
const pinnedRightWidth = computed(() => drawerDefs.value
  .filter(d => d.side === 'right' && layout.pinnedOpen[d.id])
  .reduce((sum, d) => sum + d.width, 0))

/**
 * 侧栏实际占位宽度（px，含其自身外边距，相对外壳左缘）。
 *
 * <p>为什么实测而不是直接读 `--shell-side-w`：global 模式下侧栏由宿主通过 `#sidebar`
 * 插槽注入（画廊/新增项目/全局设置各有自己的导航轨道宽度），常量会错位。</p>
 * <p>用途：让**左侧固定面板**从「侧栏右缘 + 一个外边距」开始排，导航恒定停在最左，
 * 避免点一次「固定」就把导航整体推右的跳位观感。</p>
 */
const sidebarSlotEl = ref(null)
const sidebarOccupiedWidth = ref(0)
let sidebarObserver = null
function measureSidebar() {
  const el = sidebarSlotEl.value
  if (!el) { sidebarOccupiedWidth.value = 0; return }
  const shell = el.closest('.app-shell') || el
  const rect = el.getBoundingClientRect()
  const shellRect = shell.getBoundingClientRect()
  // 空槽位（宽度为 0）时按 0 处理，避免把外壳左缘当成侧栏右缘
  sidebarOccupiedWidth.value = rect.width > 0 ? Math.round(rect.right - shellRect.left) : 0
}

/** 外壳级 CSS 变量：侧栏占位 + 内容区让位宽度
 *  内容区让位 = 固定面板宽 + 一个面板外边距（内容区本身已天然排在侧栏之后，无需再加侧栏宽度） */
const shellVars = computed(() => ({
  '--shell-sidebar-w': `${sidebarOccupiedWidth.value}px`,
  '--pinned-left-inset': `${pinnedLeftWidth.value ? pinnedLeftWidth.value + SIDEBAR_MARGIN : 0}px`,
  '--pinned-right-inset': `${pinnedRightWidth.value ? pinnedRightWidth.value + SIDEBAR_MARGIN : 0}px`
}))

/** 侧栏是否以覆盖方式呈现（窄屏滑入：内容区不为它让位） */
const sidebarIsOverlay = computed(() => shellMode.value === 'overlay' && layout.overlaySidebarOpen)

/** 返回项目画廊 */
function goGallery() { router.push('/') }

/**
 * 顶栏返回按钮：宿主给了 `backTo` 就回宿主指定位置（replace，避免回退又落回本页），
 * 否则回项目画廊。
 */
function onBack() {
  if (props.backTo) router.replace(props.backTo)
  else goGallery()
}

/** 注册系统命令（导航 + 界面开关）；每次 projectId 变化重新注册 */
function registerSystemCommands() {
  const pid = props.projectId
  const navCmds = [...PINNED_ITEMS, ...NAV_ITEMS].map(i => ({
    id: `nav.${i.key}`,
    title: i.name,
    group: '导航',
    icon: i.icon,
    hint: i.desc,
    keywords: [i.name, i.desc, i.domain, i.path],
    enabled: () => pid != null,
    run: () => router.push(itemPath(pid, i.path))
  }))
  const domainCmds = DOMAINS.map((d, idx) => {
    const first = NAV_ITEMS.find(i => i.domain === d.key)
    return {
      id: `domain.${d.key}`,
      title: `前往「${d.name}」域首项`,
      group: '导航',
      icon: d.icon,
      hotkey: `Alt+${idx + 1}`,
      keywords: [d.name, d.desc],
      enabled: () => pid != null && !!first,
      run: () => first && router.push(itemPath(pid, first.path))
    }
  })
  const uiCmds = [
    { id: 'ui.command', title: '打开命令面板', group: '界面', icon: 'ic_public_search', hotkey: 'Ctrl+K', keywords: ['命令', '搜索'], run: () => layout.setCommandOpen(true) },
    { id: 'ui.sidebar', title: layout.sidebarCollapsed ? '展开侧栏' : '收起侧栏', group: '界面', icon: 'ic_public_view_list', hotkey: 'Ctrl+B', keywords: ['侧栏', '折叠'], run: () => toggleSidebar() },
    { id: 'ui.theme', title: '切换浅色 / 深色主题', group: '界面', icon: 'ic_public_brightness', hotkey: 'Ctrl+Shift+L', keywords: ['主题', '深色', '浅色'], run: () => themeApi.toggleMode() },
    ...DENSITY_MODES.map(d => ({
      id: `ui.density.${d.key}`, title: `信息密度：${d.name}`, group: '界面', icon: 'ic_public_view_list',
      hint: d.desc, keywords: ['密度', d.name], run: () => layout.setDensity(d.key)
    })),
    { id: 'ui.width.reset', title: '侧栏宽度恢复默认', group: '界面', icon: 'ic_public_refresh', keywords: ['宽度', '重置'], run: () => layout.resetSidebarWidth() },
    { id: 'ui.tour', title: '重看界面导览', group: '帮助', icon: 'ic_public_highlight', hotkey: 'Ctrl+Shift+T', keywords: ['导览', '教程'], run: () => layout.startTour() },
    { id: 'ui.tutorial', title: '打开新手上手教程', group: '帮助', icon: 'ic_public_albums', keywords: ['教程', '新手', '入门', '怎么用', '上手'], run: () => router.push('/tutorial') },
    ...drawerDefs.value.map(d => ({
      id: `drawer.${d.id}`, title: `打开「${d.title}」面板`, group: '面板', icon: d.icon,
      keywords: ['面板', d.title], run: () => layout.openDrawer(d.id)
    })),
    { id: 'drawer.ai-runtime.refresh', title: '刷新 AI 运行状态', group: '面板', icon: 'ic_public_refresh', keywords: ['ai', '状态'], run: () => layout.openDrawer('ai-runtime') }
  ]
  registerCommands([...navCmds, ...domainCmds, ...uiCmds])
}

/** 全局快捷键分发 */
function onKeydown(ev) {
  const hit = matchShortcut(ev)
  if (!hit) return
  // 输入框内：仅放行带修饰键的系统快捷键（Ctrl+K 等），Esc 交给输入框自身处理
  const editable = isEditableTarget(ev.target)
  if (editable && (hit === 'escape' || hit === 'help')) return

  if (hit === 'escape') {
    // Esc 逐层关闭：命令面板 → 覆盖式抽屉 → 上下文抽屉 → 窄屏侧栏（固定的面板不关）
    if (layout.commandOpen) { layout.setCommandOpen(false); ev.preventDefault(); return }
    if (layout.openDrawerId) { layout.closeDrawer(layout.openDrawerId); ev.preventDefault(); return }
    if (layout.contextDrawerOpen) { ctx.close(); ev.preventDefault(); return }
    if (sidebarIsOverlay.value) { layout.setOverlaySidebar(false); ev.preventDefault() }
    return
  }
  if (hit.startsWith('domain-')) {
    const n = Number(hit.split('-')[1])
    const d = DOMAINS[n - 1]
    const first = d && NAV_ITEMS.find(i => i.domain === d.key)
    if (first && props.projectId != null) { router.push(itemPath(props.projectId, first.path)); ev.preventDefault() }
    return
  }
  const map = {
    command: () => layout.toggleCommand(),
    help: () => layout.toggleDrawer('help'),
    sidebar: () => toggleSidebar(),
    theme: () => themeApi.toggleMode(),
    density: () => layout.cycleDensity(),
    tour: () => layout.startTour()
  }
  if (map[hit]) { map[hit](); ev.preventDefault() }
}

/** 路由变化：关闭覆盖式抽屉（避免「跳页后面板还盖着」），并收起窄屏侧栏 */
watch(() => route.fullPath, () => {
  layout.closeTransientDrawers()
  afterNavigate()
})

onMounted(() => {
  registerSystemCommands()
  window.addEventListener('keydown', onKeydown)
  aiRuntime.start()
  // 侧栏实际占位宽度：用 ResizeObserver 实测（覆盖自定义插槽侧栏与折叠/拖拽改宽）
  measureSidebar()
  if (typeof ResizeObserver !== 'undefined') {
    sidebarObserver = new ResizeObserver(() => measureSidebar())
    if (sidebarSlotEl.value) sidebarObserver.observe(sidebarSlotEl.value)
  } else {
    window.addEventListener('resize', measureSidebar)
  }
  // 首次进入新布局：自动播放一次导览（用户可在设置页重看）
  if (!layout.tourSeen && props.mode === 'project') setTimeout(() => layout.startTour(), 600)
})

// 侧栏显隐/插槽内容变化后重新实测（等一帧，确保 DOM 已布局）
watch([() => props.showSidebar, () => props.mode], () => {
  requestAnimationFrame(() => measureSidebar())
})

watch(() => props.projectId, () => {
  unregisterCommandsByPrefix('nav.')
  unregisterCommandsByPrefix('domain.')
  registerSystemCommands()
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  sidebarObserver?.disconnect()
  sidebarObserver = null
  unregisterCommandsByPrefix('nav.')
  unregisterCommandsByPrefix('domain.')
  unregisterCommandsByPrefix('ui.')
  unregisterCommandsByPrefix('drawer.')
})
</script>

<style scoped>
/* 外壳：满屏纵向布局，只有内容区滚动 */
.app-shell {
  height: 100vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: var(--bg-page);
}

/* 主体：相对定位容器（抽屉绝对定位的参照物） */
.shell-body { flex: 1; min-height: 0; display: flex; position: relative; overflow: hidden; }

/* 侧栏 + 内容区这一列 */
.shell-main { flex: 1; min-width: 0; display: flex; overflow: hidden; }

/* 侧栏测量容器：保持原 flex 行为（不伸缩、按内容定宽），仅为固定面板提供「让位宽度」 */
.shell-sidebar-slot { display: flex; flex-shrink: 0; min-width: 0; }

/* 内容区：唯一滚动容器 + 统一内边距（令牌驱动，随密度档变化）
   margin 由固定面板注入（--pinned-*-inset）：这就是「固定面板挤压内容区」的实现，
   与酒馆「固定面板仍覆盖内容」是有意分歧（见设计规格 §12.4）。 */
.shell-content {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding: var(--content-pad-y, 20px) var(--content-pad-x, 24px);
  margin-left: var(--pinned-left-inset, 0px);
  margin-right: var(--pinned-right-inset, 0px);
}
/* fullbleed：交给页面自绘（沙盘 3D），去内边距并禁滚动 */
.shell-content.is-fullbleed { padding: 0; overflow: hidden; }

/* ===== 全屏沙盘：侧栏改为毛玻璃悬浮在 3D 之上（保留导航可用性） ===== */
.app-shell.is-fullbleed :deep(.domain-sidebar) {
  background: color-mix(in srgb, var(--bg-nav) 78%, transparent);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  box-shadow: var(--shadow-lv2);
}

/* 窄屏侧栏遮罩 */
.shell-mask { position: absolute; inset: 0; background: var(--bg-scrim); z-index: calc(var(--z-sidebar, 30) - 1); }

/* 滚动条 */
.shell-content::-webkit-scrollbar { width: 8px; }
.shell-content::-webkit-scrollbar-thumb { background: var(--scrollbar-thumb, #cdd5e2); border-radius: 4px; }
</style>
