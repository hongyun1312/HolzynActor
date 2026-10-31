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

import { defineStore } from 'pinia'

/** 布局偏好的 localStorage 键（独立键，不污染主题键 holzyn-actor.theme.v1） */
export const LAYOUT_STORAGE_KEY = 'holzyn.ui.layout.v1'

/** 侧栏宽度默认值与边界（与 variables.css 的 --shell-side-w* 保持一致；默认 210 = 基准 1280 下更紧凑的左栏） */
export const SIDEBAR_DEFAULT_WIDTH = 210
export const SIDEBAR_MIN_WIDTH = 208
export const SIDEBAR_MAX_WIDTH = 360
export const SIDEBAR_COLLAPSED_WIDTH = 56

/** 信息密度三档（写入 html[data-density]，由令牌驱动样式） */
export const DENSITY_MODES = [
  { key: 'compact', name: '紧凑', desc: '行高最小、留白最少（贴近酒馆默认观感）' },
  { key: 'cozy', name: '舒适', desc: '默认档位，密度与呼吸感平衡' },
  { key: 'comfortable', name: '宽松', desc: '留白充足，长时间阅读更轻松' }
]

/** 内容区宽度模式 */
export const CONTENT_MODES = [
  { key: 'centered', name: '居中列', desc: '内容限宽居中（默认，长文更易读）' },
  { key: 'wide', name: '全宽', desc: '内容铺满可用宽度（表格/图谱类页面更实用）' }
]

/** 读取持久化偏好（损坏时静默回退默认，避免脏数据把界面卡死） */
function loadPrefs() {
  const fallback = {
    sidebarWidth: SIDEBAR_DEFAULT_WIDTH,
    sidebarCollapsed: false,
    density: 'cozy',
    contentMode: 'centered',
    dragEnabled: true,
    drawerPinned: {},
    tourSeen: false,
    workspace: {}
  }
  try {
    const raw = localStorage.getItem(LAYOUT_STORAGE_KEY)
    if (!raw) return fallback
    const parsed = JSON.parse(raw)
    return {
      sidebarWidth: clampWidth(parsed.sidebarWidth ?? fallback.sidebarWidth),
      sidebarCollapsed: !!parsed.sidebarCollapsed,
      density: DENSITY_MODES.some(d => d.key === parsed.density) ? parsed.density : fallback.density,
      contentMode: CONTENT_MODES.some(m => m.key === parsed.contentMode) ? parsed.contentMode : fallback.contentMode,
      dragEnabled: parsed.dragEnabled !== false,
      drawerPinned: parsed.drawerPinned && typeof parsed.drawerPinned === 'object' ? parsed.drawerPinned : {},
      tourSeen: !!parsed.tourSeen,
      workspace: normalizeWorkspace(parsed.workspace)
    }
  } catch (_) {
    return { ...fallback, workspace: {} }
  }
}

/**
 * 归一化「可并置多面板工作区」的持久化状态。
 *
 * <p>结构：`{ [storageKey]: { widths: { [paneKey]: px }, collapsed: { [paneKey]: true } } }`。
 * 非法值（非对象、宽度非数字）一律丢弃而不是抛错——布局偏好坏掉不该让页面白屏。</p>
 *
 * @param {*} raw localStorage 里读到的原始值
 * @returns {object} 归一化后的工作区状态
 */
function normalizeWorkspace(raw) {
  const out = {}
  if (!raw || typeof raw !== 'object') return out
  for (const [key, val] of Object.entries(raw)) {
    if (!val || typeof val !== 'object') continue
    const widths = {}
    if (val.widths && typeof val.widths === 'object') {
      for (const [pk, w] of Object.entries(val.widths)) {
        const n = Math.round(Number(w))
        if (Number.isFinite(n) && n > 80 && n < 2000) widths[pk] = n
      }
    }
    const collapsed = {}
    if (val.collapsed && typeof val.collapsed === 'object') {
      for (const [pk, c] of Object.entries(val.collapsed)) if (c) collapsed[pk] = true
    }
    out[key] = { widths, collapsed }
  }
  return out
}

/** 宽度钳制（拖拽/持久化/异常值统一走这里） */
export function clampWidth(w) {
  const n = Number(w)
  if (!Number.isFinite(n)) return SIDEBAR_DEFAULT_WIDTH
  return Math.min(SIDEBAR_MAX_WIDTH, Math.max(SIDEBAR_MIN_WIDTH, Math.round(n)))
}

/**
 * 界面布局 store。
 *
 * <p>职责：集中保存**用户对界面骨架的偏好与瞬时状态**——域侧栏宽度/折叠、信息密度、内容宽度模式、
 * 顶部抽屉的开合与「图钉固定」、命令面板与帮助面板开关、一次性导览是否已看。</p>
 *
 * <p>为什么需要它：上一版把侧栏宽度、分组展开、主题切换等状态散落在各页面局部 ref 里，
 * 导致「三个侧栏实现互不同步、刷新即丢」。本轮把所有骨架状态收敛到本 store，
 * 并单一持久化到 {@link LAYOUT_STORAGE_KEY}。</p>
 *
 * <p>抽屉互斥规则（对齐酒馆肌肉记忆，见设计规格 §5.2 / §12.2）：</p>
 * <ul>
 *   <li>打开一个未固定的抽屉时，先关闭其他**未固定**的抽屉（互斥）；</li>
 *   <li>被固定的抽屉不参与互斥、不被「点击空白」或 Esc 关闭，且**参与布局**（挤压内容区）；</li>
 *   <li>pin 状态用 `drawerPinned[id]` 记录并持久化（纯状态，无需额外样式规则）。</li>
 * </ul>
 */
export const useLayoutStore = defineStore('layout', {
  state: () => {
    const prefs = loadPrefs()
    return {
      // ===== 持久化偏好 =====
      sidebarWidth: prefs.sidebarWidth,           // 用户拖拽后的期望宽度（px，未折叠时生效）
      sidebarCollapsed: prefs.sidebarCollapsed,   // 是否收起为图标窄栏
      density: prefs.density,                     // compact | cozy | comfortable
      contentMode: prefs.contentMode,             // centered | wide
      dragEnabled: prefs.dragEnabled,             // 是否允许拖拽改变面板宽度（设置页可关）
      drawerPinned: prefs.drawerPinned,           // { [drawerId]: true }
      tourSeen: prefs.tourSeen,                   // 新布局导览是否已完成
      workspace: prefs.workspace,                 // { [storageKey]: { widths, collapsed } } 可并置工作区

      // ===== 瞬时状态（不持久化）=====
      overlaySidebarOpen: false,                  // 窄屏（<1024）覆盖式侧栏是否滑入
      sidebarManualExpand: false,                 // 中屏（1024–1439）用户是否手动展开过侧栏（覆盖自动收窄）
      openDrawerId: null,                         // 当前打开的非固定抽屉（互斥，最多一个）
      pinnedOpen: {},                             // { [drawerId]: true } 当前打开且被固定的抽屉
      contextDrawerOpen: false,                   // 右侧上下文抽屉（当前对象详情）
      commandOpen: false,                         // 命令面板
      tourActive: false,                          // 新布局导览进行中
      aiRuntime: null                             // AI 运行状态快照（由 AiRuntimePill 轮询写入）
    }
  },
  getters: {
    /** 是否已有任一抽屉打开（用于顶栏图标高亮与遮罩显隐） */
    anyDrawerOpen: (state) => !!state.openDrawerId || Object.keys(state.pinnedOpen).length > 0,
    /** 当前密度档的描述对象 */
    densityMeta: (state) => DENSITY_MODES.find(d => d.key === state.density) || DENSITY_MODES[1]
  },
  actions: {
    /** 落盘持久化偏好（只写偏好，不写瞬时状态） */
    persist() {
      try {
        localStorage.setItem(LAYOUT_STORAGE_KEY, JSON.stringify({
          sidebarWidth: this.sidebarWidth,
          sidebarCollapsed: this.sidebarCollapsed,
          density: this.density,
          contentMode: this.contentMode,
          dragEnabled: this.dragEnabled,
          drawerPinned: this.drawerPinned,
          tourSeen: this.tourSeen,
          workspace: this.workspace
        }))
      } catch (_) { /* 隐私模式/配额满：静默忽略，界面仍可用 */ }
    },

    // ===== 侧栏 =====
    /** 设置侧栏宽度（自动钳制到 208–360） */
    setSidebarWidth(w) {
      this.sidebarWidth = clampWidth(w)
      this.persist()
    },
    /** 恢复默认侧栏宽度 */
    resetSidebarWidth() {
      this.sidebarWidth = SIDEBAR_DEFAULT_WIDTH
      this.persist()
    },
    /** 折叠 / 展开侧栏 */
    toggleSidebar(force) {
      this.sidebarCollapsed = typeof force === 'boolean' ? force : !this.sidebarCollapsed
      this.persist()
    },
    /** 窄屏覆盖式侧栏开关（不持久化：每次进入窄屏都从收起开始，符合移动端直觉） */
    setOverlaySidebar(v) { this.overlaySidebarOpen = !!v },
    /** 中屏是否手动展开过侧栏（覆盖「自动收窄」；不持久化） */
    setSidebarManualExpand(v) { this.sidebarManualExpand = !!v },

    // ===== 密度 / 内容宽度 =====
    setDensity(key) {
      if (DENSITY_MODES.some(d => d.key === key)) { this.density = key; this.persist() }
    },
    /** 在紧凑/舒适/宽松之间循环（顶栏快捷按钮） */
    cycleDensity() {
      const idx = DENSITY_MODES.findIndex(d => d.key === this.density)
      this.setDensity(DENSITY_MODES[(idx + 1) % DENSITY_MODES.length].key)
    },
    setContentMode(mode) {
      if (CONTENT_MODES.some(m => m.key === mode)) { this.contentMode = mode; this.persist() }
    },
    setDragEnabled(v) {
      this.dragEnabled = !!v
      this.persist()
    },

    // ===== 抽屉 =====
    /**
     * 打开抽屉。
     * @param {string} id 抽屉标识
     * @param {{pinned?:boolean}} [opts] pinned=true 表示以「固定」方式打开（参与布局、不互斥）
     */
    openDrawer(id, opts = {}) {
      if (!id) return
      if (opts.pinned) {
        this.pinnedOpen[id] = true
        if (this.openDrawerId === id) this.openDrawerId = null
        return
      }
      // 互斥：关闭其他未固定抽屉（被固定的不动）
      this.openDrawerId = id
    },
    /** 关闭指定抽屉（固定与非固定都关） */
    closeDrawer(id) {
      if (this.openDrawerId === id) this.openDrawerId = null
      if (this.pinnedOpen[id]) { const next = { ...this.pinnedOpen }; delete next[id]; this.pinnedOpen = next }
    },
    /** 关闭所有未固定抽屉（点击空白 / Esc 的第一层） */
    closeTransientDrawers() {
      this.openDrawerId = null
      this.contextDrawerOpen = false
    },
    /** 开关抽屉（顶栏图标点击） */
    toggleDrawer(id) {
      if (this.openDrawerId === id || this.pinnedOpen[id]) this.closeDrawer(id)
      else this.openDrawer(id)
    },
    /** 切换抽屉的固定状态：固定后从瞬时态转为常驻态（反之亦然） */
    toggleDrawerPin(id) {
      const pinned = !this.drawerPinned[id]
      this.drawerPinned = { ...this.drawerPinned, [id]: pinned }
      if (pinned) {
        // 转为固定：从互斥位挪到常驻位
        if (this.openDrawerId === id) this.openDrawerId = null
        this.pinnedOpen[id] = true
      } else {
        // 取消固定：若仍在常驻位则关闭（对齐酒馆「取消 pin 后主动关闭」的行为）
        if (this.pinnedOpen[id]) { const next = { ...this.pinnedOpen }; delete next[id]; this.pinnedOpen = next }
      }
      this.persist()
    },
    /** 某抽屉当前是否可见（非固定打开 或 固定常驻） */
    isDrawerVisible(id) {
      return this.openDrawerId === id || !!this.pinnedOpen[id]
    },
    /** 设置右侧上下文抽屉 */
    setContextDrawer(open) {
      this.contextDrawerOpen = !!open
    },

    // ===== 命令面板 / 导览 =====
    setCommandOpen(v) { this.commandOpen = !!v },
    toggleCommand() { this.commandOpen = !this.commandOpen },
    startTour() { this.tourActive = true },
    endTour(markSeen = true) {
      this.tourActive = false
      if (markSeen) { this.tourSeen = true; this.persist() }
    },
    /** 重置导览（设置页「重新观看导览」） */
    resetTour() { this.tourSeen = false; this.persist() },

    // ===== AI 运行状态 =====
    setAiRuntime(snapshot) { this.aiRuntime = snapshot },

    // ===== 可并置多面板工作区（WorkspaceSplit）=====
    /**
     * 读取某工作区某面板的偏好（宽度/折叠）。
     *
     * @param {string} storageKey 工作区键（通常「页面.切面」）
     * @param {string} paneKey 面板键
     * @returns {{width: (number|null), collapsed: boolean}} 偏好
     */
    workspacePane(storageKey, paneKey) {
      const ws = this.workspace?.[storageKey]
      if (!ws) return { width: null, collapsed: false }
      return {
        width: ws.widths?.[paneKey] ?? null,
        collapsed: !!ws.collapsed?.[paneKey]
      }
    },
    /** 记录某工作区某面板的宽度（拖拽结束时落盘；钳制交给调用方传合法值） */
    setWorkspaceWidth(storageKey, paneKey, width) {
      const n = Math.round(Number(width))
      if (!Number.isFinite(n) || n <= 80) return
      const cur = this.workspace?.[storageKey] || { widths: {}, collapsed: {} }
      this.workspace = {
        ...this.workspace,
        [storageKey]: { ...cur, widths: { ...(cur.widths || {}), [paneKey]: n } }
      }
      this.persist()
    },
    /** 恢复某面板默认宽度（双击拖拽手柄 / 设置页重置） */
    resetWorkspaceWidth(storageKey, paneKey) {
      const cur = this.workspace?.[storageKey]
      if (!cur?.widths?.[paneKey]) return
      const widths = { ...cur.widths }
      delete widths[paneKey]
      this.workspace = { ...this.workspace, [storageKey]: { ...cur, widths } }
      this.persist()
    },
    /**
     * 折叠/展开某工作区面板。
     *
     * @param {string} storageKey 工作区键
     * @param {string} paneKey 面板键
     * @param {boolean} [force] 指定目标态（省略则取反）
     */
    toggleWorkspaceCollapse(storageKey, paneKey, force) {
      const cur = this.workspace?.[storageKey] || { widths: {}, collapsed: {} }
      const collapsed = { ...(cur.collapsed || {}) }
      const next = typeof force === 'boolean' ? force : !collapsed[paneKey]
      if (next) collapsed[paneKey] = true
      else delete collapsed[paneKey]
      this.workspace = { ...this.workspace, [storageKey]: { ...cur, collapsed } }
      this.persist()
    },
    /** 重置整个工作区偏好（设置页「恢复默认面板布局」） */
    resetWorkspace(storageKey) {
      if (!this.workspace?.[storageKey]) return
      const next = { ...this.workspace }
      delete next[storageKey]
      this.workspace = next
      this.persist()
    }
  }
})
