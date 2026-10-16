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

import { computed, ref, watchEffect } from 'vue'
import { storeToRefs } from 'pinia'
import {
  useLayoutStore,
  SIDEBAR_COLLAPSED_WIDTH,
  SIDEBAR_MIN_WIDTH,
  SIDEBAR_MAX_WIDTH
} from '@/shared/store/layout'

/**
 * 断点（**唯一事实来源在 CSS 令牌**：`variables.css` 的 `--bp-md/--bp-lg/--bp-xl`；
 * 本文件的两个常量必须与之保持同值——改一处必须同时改另一处，别处不得再定义断点）。
 * V2.0 起 CSS 侧只允许使用这三个档位值。
 */
export const BREAKPOINT_MEDIUM = 1440
export const BREAKPOINT_SMALL = 1024

/** 侧栏与内容区之间的留白（悬浮岛左右各一份；沙盘 3D 向左延伸需用同一数值） */
export const SIDEBAR_MARGIN = 12

/** 视口宽度（模块级单例：整个应用只挂一份监听，避免每个组件各挂一套） */
const viewportWidth = ref(typeof window === 'undefined' ? BREAKPOINT_MEDIUM : window.innerWidth)
let mediaBound = false

function bindViewport() {
  if (mediaBound || typeof window === 'undefined') return
  mediaBound = true
  window.addEventListener('resize', () => { viewportWidth.value = window.innerWidth }, { passive: true })
}

/**
 * 外壳布局组合式函数。
 *
 * <p>职责：把「视口断点 → 侧栏形态 → CSS 变量」这条链路收敛到一处，让所有页面与组件
 * 只读令牌、不各自算尺寸。同时提供抽屉开关的语义化封装（互斥 / Esc 分层）。</p>
 *
 * <p>三种形态（对齐设计规格 §3.3）：</p>
 * <ul>
 *   <li>`wide`（≥1440）：侧栏按用户记忆宽度常驻；</li>
 *   <li>`medium`（1024–1439）：侧栏自动收窄为 56px 图标栏（用户手动展开可覆盖该自动行为）；</li>
 *   <li>`overlay`（&lt;1024）：侧栏改为覆盖式滑入，内容区占满。</li>
 * </ul>
 *
 * <p>关键职责之二：**动态写入 `--shell-side-w` 与 `--sb-inset-left`**。
 * 上一版把 `--sb-inset-left: 264px` 硬编码在 CSS 里（= 248 侧栏 + 16 留白），
 * 一旦侧栏宽度变化，世界沙盘的 3D 画布就会错位——本轮改为实测值注入，彻底解耦。</p>
 *
 * <p>用法：在需要跟随布局的组件里调用一次即可；CSS 变量的写入是通过 `watchEffect`
 * 自动跟随的，无需手动触发。</p>
 */
export function useShellLayout() {
  const layout = useLayoutStore()
  const { sidebarWidth, sidebarCollapsed, density, contentMode } = storeToRefs(layout)

  bindViewport()

  /** 当前断点形态：wide | medium | overlay */
  const shellMode = computed(() => {
    if (viewportWidth.value < BREAKPOINT_SMALL) return 'overlay'
    if (viewportWidth.value < BREAKPOINT_MEDIUM) return 'medium'
    return 'wide'
  })

  /**
   * 实际生效的侧栏宽度（px）。
   * <p>覆盖式（<1024）下：只有「滑入打开」时才按记忆值渲染，收起时为 0——
   * 旧实现恒返回记忆宽度，导致窄屏侧栏始终占位、内容区被挤窄，且遮罩出现侧栏却不动。</p>
   */
  const effectiveSidebarWidth = computed(() => {
    if (shellMode.value === 'overlay') return layout.overlaySidebarOpen ? sidebarWidth.value : 0
    if (shellMode.value === 'medium' && !layout.sidebarManualExpand) return SIDEBAR_COLLAPSED_WIDTH
    return sidebarCollapsed.value ? SIDEBAR_COLLAPSED_WIDTH : sidebarWidth.value
  })

  /** 侧栏是否处于「窄图标栏」形态（用于隐藏文字、改为 tooltip） */
  const sidebarIsIconOnly = computed(() =>
    shellMode.value !== 'overlay' && effectiveSidebarWidth.value <= SIDEBAR_COLLAPSED_WIDTH + 4
  )

  /** 内容区可用宽度（给需要自适应绘图的页面参考，如关系拓扑/图表） */
  const contentAreaWidth = computed(() => {
    const sb = shellMode.value === 'overlay' ? 0 : effectiveSidebarWidth.value + SIDEBAR_MARGIN * 2
    return Math.max(320, viewportWidth.value - sb)
  })

  /** 把布局数值同步到 CSS 变量（样式层唯一事实来源）；返回值便于测试断言 */
  function applyCssVars() {
    if (typeof document === 'undefined') return
    const root = document.documentElement
    root.style.setProperty('--shell-side-w', `${effectiveSidebarWidth.value}px`)
    root.style.setProperty('--shell-side-margin', `${SIDEBAR_MARGIN}px`)
    // 世界沙盘 3D 画布向左延伸量 = 侧栏左留白 + 侧栏宽度（= 内容区左边缘距视口左边的距离；
    // 旧实现硬编码 264 = 248 侧栏 + 16 左留白，语义相同，现改为实测值）
    root.style.setProperty('--sb-inset-left', `${effectiveSidebarWidth.value + SIDEBAR_MARGIN}px`)
    root.setAttribute('data-density', density.value)
    root.setAttribute('data-content-mode', contentMode.value)
    root.setAttribute('data-shell-mode', shellMode.value)
  }

  // 响应式同步：任一布局数值变化 → 立即写 CSS 变量（无需手动调用）
  watchEffect(() => { applyCssVars() })

  /** 拖拽改宽（PointerEvent；dragEnabled 关闭时不响应） */
  function startResize(ev) {
    if (!layout.dragEnabled || typeof window === 'undefined') return
    const startX = ev.clientX
    const startW = effectiveSidebarWidth.value
    try { ev.currentTarget?.setPointerCapture?.(ev.pointerId) } catch (_) { /* 环境不支持则忽略 */ }

    const onMove = (e) => {
      const next = Math.min(SIDEBAR_MAX_WIDTH, Math.max(SIDEBAR_MIN_WIDTH, startW + (e.clientX - startX)))
      layout.setSidebarWidth(next)
      // 拖拽期间若处于自动收窄形态，视为用户主动展开；若已折叠则先展开
      if (shellMode.value === 'medium') layout.setSidebarManualExpand(true)
      if (shellMode.value !== 'overlay' && sidebarCollapsed.value) layout.toggleSidebar(false)
    }
    const onUp = () => {
      window.removeEventListener('pointermove', onMove)
      window.removeEventListener('pointerup', onUp)
      window.removeEventListener('pointercancel', onUp)
      document.body.classList.remove('is-resizing')
    }
    document.body.classList.add('is-resizing')
    window.addEventListener('pointermove', onMove)
    window.addEventListener('pointerup', onUp)
    window.addEventListener('pointercancel', onUp)
  }

  /** 双击手柄恢复默认宽度与自动行为 */
  function resetWidth() {
    layout.resetSidebarWidth()
    layout.setSidebarManualExpand(false)
  }

  /** 开关侧栏（按当前形态决定是折叠还是覆盖式滑入） */
  function toggleSidebar() {
    if (shellMode.value === 'overlay') {
      layout.setOverlaySidebar(!layout.overlaySidebarOpen)
      return
    }
    if (shellMode.value === 'medium') {
      // 中屏（1024–1439）自动收窄为图标栏；用户手动展开可覆盖该行为。
      // 旧实现只翻转 sidebarCollapsed，而中屏自动态下宽度由 sidebarManualExpand 决定，
      // 于是「展开侧栏」点了毫无反应——这里改管 manualExpand：展开⇄回到自动收窄。
      const expanded = layout.sidebarManualExpand && !layout.sidebarCollapsed
      layout.setSidebarManualExpand(!expanded)
      layout.toggleSidebar(false)
      return
    }
    layout.toggleSidebar()
  }

  /** 点击导航后关闭覆盖式侧栏（窄屏体验） */
  function afterNavigate() {
    if (shellMode.value === 'overlay') layout.setOverlaySidebar(false)
  }

  return {
    // 状态
    shellMode,
    effectiveSidebarWidth,
    sidebarIsIconOnly,
    contentAreaWidth,
    sidebarWidth,
    sidebarCollapsed,
    // 动作
    applyCssVars,
    startResize,
    resetWidth,
    toggleSidebar,
    afterNavigate,
    layout
  }
}
