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
    class="app-shell glass-scene"
    :class="{ 'is-fullbleed': fullbleed, 'is-overlay-mode': shellMode === 'overlay' && showSidebar, 'is-sidebar-overlay': sidebarIsOverlay, 'is-glass': true, 'no-cloudtrain': !cloudTrainEnabled }"
    :style="shellVars"
  >
    <!-- 云间列车液态玻璃背景：全站唯一实例（铺满视口、z-index:-1），浅色=落日云间 / 深色=夜空。
         可在「设置 → 通用设置 → 主题与外观」关闭（关闭后为纯色背景，玻璃材质保留）或调整渲染分辨率。 -->
    <CloudTrainBackground v-if="cloudTrainEnabled" :overrides="cloudTrainOverrides" />
    <ShellTopbar
      :mode="mode"
      :project-id="projectId"
      :project="project"
      :breadcrumb="breadcrumb"
      :interface-key="interfaceKey"
      :brand-label="brandLabel"
      :show-back="showBack"
      :show-sidebar-toggle="showSidebar"
      :sidebar-collapsed="effectiveSidebarWidth <= 60"
      @back="onBack"
      @toggle-sidebar="toggleSidebar"
      @open-command="layout.setCommandOpen(true)"
      @switch-interface="switchInterface"
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
            :interface-key="interfaceKey"
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
    <!-- 全局项目级 AI 助手（浮窗 + 侧边栏；跨页面保持） -->
    <AssistantWidget />
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
import CloudTrainBackground from '@/shared/components/CloudTrainBackground.vue'
import { useGlassScene } from '@/shared/composables/useGlassScene'
import AssistantWidget from '@/features/assistant/AssistantWidget.vue'
import { useShellLayout, SIDEBAR_MARGIN } from './useShellLayout'
import { useAiRuntime } from './useAiRuntime'
import { useContextDrawer } from './useContextDrawer'
import { matchShortcut, isEditableTarget } from './shortcuts'
import { DOMAINS, PINNED_ITEMS, NAV_ITEMS, DIALOGUE_ITEMS, DIALOGUE_SETTINGS_ITEMS, DRAWER_PANELS, itemPath, interfaceOfPath, interfaceInfo } from './navModel'
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
  fullbleed: { type: Boolean, default: false },
  /**
   * （历史兼容，保留但已不生效）云间列车液态玻璃场景已全站常驻——
   * 外壳恒为玻璃化（.app-shell.is-glass + .glass-scene），不再按页开关。
   */
  glass: { type: Boolean, default: false }
})

const route = useRoute()
const router = useRouter()
const themeApi = useTheme()
const layout = useLayoutStore()
const ctx = useContextDrawer()
const { effectiveSidebarWidth, sidebarIsIconOnly, toggleSidebar, afterNavigate, shellMode } = useShellLayout()

/** 液态玻璃场景：全站唯一外壳常驻 → body 常挂 holzyn-glass（Teleport 浮层吃到玻璃令牌） */
useGlassScene()

/** 云间列车背景是否渲染（设置页可开关；关闭后为纯色背景，玻璃材质保留） */
const cloudTrainEnabled = computed(() => themeApi.theme.value.cloudTrain?.enabled !== false)
/** 云间列车渲染参数覆盖（当前仅分辨率；主题夜空/落日参数由组件内部处理） */
const cloudTrainOverrides = computed(() => {
  const r = Number(themeApi.theme.value.cloudTrain?.resolution)
  return Number.isFinite(r) && r > 0 ? { resolution: r } : {}
})

/** AI 运行状态轮询（顶栏活动点 + AI 抽屉共用一份快照） */
const aiRuntime = useAiRuntime(() => props.projectId)

/** 当前所处界面（浮生 / 对谈）：由路由路径判定，切换界面即切换侧边栏 */
const interfaceKey = computed(() => interfaceOfPath(route.path, props.projectId))

/**
 * 切换界面（浮生 ⇄ 对谈）：跳转到另一界面的落地页。
 * <p>由左上角世界名右侧的切换按钮触发；两侧边栏完全独立，互不混排。</p>
 */
function switchInterface() {
  const target = interfaceKey.value === 'dialogue' ? 'fusheng' : 'dialogue'
  const landing = interfaceInfo(target).landing
  if (props.projectId == null) return
  router.push(itemPath(props.projectId, landing))
}

/** 抽屉声明（元数据唯一来源 = navModel.DRAWER_PANELS；此处只补「内容组件」映射，
 *  这样顶栏「面板」下拉与抽屉层永远列的是同一批面板，不会各写一份而漂移） */
const DRAWER_COMPONENTS = {
  pinned: { comp: QuickNavDrawer, props: () => ({ domainKey: 'pinned', projectId: props.projectId, interfaceKey: interfaceKey.value }) },
  cast: { comp: QuickNavDrawer, props: () => ({ domainKey: 'cast', projectId: props.projectId }) },
  mind: { comp: QuickNavDrawer, props: () => ({ domainKey: 'mind', projectId: props.projectId }) },
  world: { comp: QuickNavDrawer, props: () => ({ domainKey: 'world', projectId: props.projectId }) },
  'ai-runtime': { comp: AiRuntimePanel, props: () => ({}) },
  help: { comp: ShortcutHelpPanel, props: () => ({}) }
}
const drawerDefs = computed(() => DRAWER_PANELS.map(d => ({
  ...d,
  comp: DRAWER_COMPONENTS[d.id]?.comp,
  compProps: DRAWER_COMPONENTS[d.id]?.props() || {}
})))

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
  const navCmds = [...PINNED_ITEMS, ...NAV_ITEMS, ...DIALOGUE_ITEMS, ...DIALOGUE_SETTINGS_ITEMS].map(i => ({
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
    // 浮生 ⇄ 对谈界面切换（P0-1：两个独立界面的顶层入口）
    { id: 'ui.interface', title: interfaceKey.value === 'dialogue' ? '切换到「浮生」界面（世界运行）' : '切换到「对谈」界面（与角色对谈）', group: '界面', icon: interfaceKey.value === 'dialogue' ? 'ic_public_worldclock' : 'ic_public_message', keywords: ['浮生', '对谈', '界面', '切换', '聊天'], run: () => switchInterface() },
    { id: 'ui.sidebar', title: effectiveSidebarWidth.value <= 60 ? '展开侧栏' : '收起侧栏', group: '界面', icon: 'ic_public_view_list', hotkey: 'Ctrl+B', keywords: ['侧栏', '折叠'], run: () => toggleSidebar() },
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
/* 外壳：满屏纵向布局，只有内容区滚动
   背景：云间列车画布（z-index:-1）之上叠一层主题自适应压暗层（--glass-scrim），
   既保证文字可读，又不遮挡动态背景（见 global.css .glass-scene） */
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

/* ===== 覆盖式模式（<1024）：侧栏做成「滑入抽屉」，内容区占满 =====
   侧栏脱离文档流浮在内容之上（z 高于遮罩）；收起时 --shell-side-w=0 → 宽度 0 自然不可见，
   展开时从左侧滑入。旧实现没有这套定位，侧栏始终占 flex 位，内容被挤窄且遮罩无效。 */
.app-shell.is-overlay-mode .shell-sidebar-slot {
  position: absolute;
  top: 0; bottom: 0; left: 0;
  z-index: var(--z-sidebar, 30);
}
/* 贴左缘的抽屉观感：去左留白、右侧圆角、阴影作边缘（覆盖式下弃用 border，
   否则收起（width 0）时左右 1px 边线会露出 2px 细条）；拖拽手柄在覆盖式下无意义，一并隐藏；
   overflow:hidden 保证收起时导航文本不外溢 */
.app-shell.is-overlay-mode :deep(.domain-sidebar) {
  margin-left: 0;
  border: 0;
  border-radius: 0 var(--radius-lg, 16px) var(--radius-lg, 16px) 0;
  box-shadow: var(--shadow-lv3);
  overflow: hidden;
}
.app-shell.is-overlay-mode :deep(.side-resize) { display: none; }

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

/* 全屏沙盘：侧栏改为毛玻璃悬浮在 3D 之上（保留导航可用性） */
.app-shell.is-fullbleed :deep(.domain-sidebar) {
  background: color-mix(in srgb, var(--bg-nav) 78%, transparent);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  box-shadow: var(--shadow-lv2);
}

/* ===== 云间列车液态玻璃场景（全站常驻）：外壳透明化 + 顶栏玻璃 =====
   外壳背景改为主题自适应压暗层（--glass-scrim，global.css 的 .glass-scene 定义，
   浅色=浅玻璃白纱 / 深色=夜空暗纱），让云间列车画布透出且文字始终可读 */
.app-shell.is-glass {
  background: var(--glass-scrim, transparent);
}
/* 关闭云间列车背景：背景退化为纯色（跟随深浅主题），玻璃材质（glass-scene 令牌）仍保留 */
.app-shell.is-glass.no-cloudtrain {
  background: var(--bg-page);
}
/* 顶栏玻璃化：浅色=浅玻璃（继承深色文字令牌）；深色=深玻璃（本地文字令牌切浅色）。
   -webkit- 前缀在标准属性之前，避免被 lightningcss 前缀合并吞掉 */
.app-shell.is-glass :deep(.shell-topbar) {
  background: var(--glass-topbar-bg, rgba(8, 14, 22, 0.55));
  -webkit-backdrop-filter: blur(var(--glass-blur, 20px)) saturate(1.4);
  backdrop-filter: blur(var(--glass-blur, 20px)) saturate(1.4);
  border-bottom: 1px solid var(--glass-topbar-border, rgba(255, 255, 255, 0.1));
}
/* 深色：顶栏背景深玻璃时，内部文字/控件令牌切到浅色系 */
:global(html[data-theme="dark"]) .app-shell.is-glass :deep(.shell-topbar) {
  --text-primary: #f2f6fc;
  --text-regular: #dbe4f0;
  --text-secondary: #b7c5d9;
  --text-placeholder: #6c7d97;
  --bg-hover: rgba(255, 255, 255, 0.1);
  --bg-active: rgba(255, 255, 255, 0.16);
  --bg-layer-1: rgba(255, 255, 255, 0.07);
  --bg-layer-2: rgba(255, 255, 255, 0.05);
  --border-l1: rgba(255, 255, 255, 0.12);
  --border-l2: rgba(255, 255, 255, 0.2);
  --accent-text: #9db9ff;
}

/* 窄屏侧栏遮罩 */
.shell-mask { position: absolute; inset: 0; background: var(--bg-scrim); z-index: calc(var(--z-sidebar, 30) - 1); }

/* 滚动条 */
.shell-content::-webkit-scrollbar { width: 8px; }
.shell-content::-webkit-scrollbar-thumb { background: var(--scrollbar-thumb, #cdd5e2); border-radius: 4px; }
</style>
