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

import { reactive } from 'vue'

/**
 * 全局右键上下文菜单（模块级单例状态）。
 *
 * <p>职责：把「右键某处 → 弹出与该处相关的动作」这件事收敛成**一处菜单渲染 + 一份动作数组**，
 * 避免各页面各写一个浮层菜单（上一版就有 3 处各自实现的右键/长按菜单，动作名与样式都不一致）。</p>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>**动作由调用方给**：菜单不预设业务动作，只负责渲染与交互（图标/文案/禁用/危险色/分隔线/快捷键提示）。</li>
 *   <li>**坐标已夹取**：接近视口右/下边时会自动回收，避免菜单被裁切（菜单尺寸按 items 估算，无需等 DOM）。</li>
 *   <li>**关闭时机**：点击任意项、Esc、点击菜单外、滚动、窗口尺寸变化、路由切换（调用方可在 navigate 后自行关闭）。</li>
 *   <li>**零依赖**：不引入第三方右键菜单库，样式与主题令牌一致。</li>
 * </ul>
 *
 * <p>用法：</p>
 * <pre>
 * import { openContextMenu } from '@/shared/layout/useContextMenu'
 * openContextMenu(event, {
 *   title: 'NPC 角色',
 *   items: [
 *     { key: 'a', label: '打开详情', icon: 'ic_public_arrow_right', run: () =&gt; ... },
 *     { divider: true },
 *     { key: 'b', label: '删除', danger: true, run: () =&gt; ... }
 *   ]
 * })
 * </pre>
 */

/** 菜单单项高度估算（px），用于坐标夹取；与实际渲染尺寸一致（CSS 里同一数值） */
const ITEM_H = 32
/** 菜单内边距（上下合计）+ 标题高度估算 */
const CHROME_H = 26
/** 菜单宽度（与 CSS 一致） */
const MENU_W = 210
/** 距视口边缘的最小留白 */
const EDGE = 8

/** 单例状态（ContextMenu.vue 渲染它） */
const state = reactive({
  /** 是否展开 */
  open: false,
  /** 视口坐标（已夹取） */
  x: 0,
  y: 0,
  /** 菜单标题（可空） */
  title: '',
  /** 动作数组（见文件头注释） */
  items: [],
  /** 调用方上下文（原样回传，便于动作里取用；不参与渲染） */
  context: null
})

/**
 * 在鼠标位置打开菜单。
 *
 * @param {MouseEvent} event 触发事件（用于取 clientX/clientY）
 * @param {object} options 菜单配置
 * @param {Array} options.items 动作数组
 * @param {string} [options.title] 菜单标题
 * @param {*} [options.context] 调用方上下文
 */
export function openContextMenu(event, options = {}) {
  const items = Array.isArray(options.items) ? options.items.filter(Boolean) : []
  if (!items.length) return
  const rows = items.filter((i) => !i.divider).length
  const height = rows * ITEM_H + CHROME_H + 12
  const vw = window.innerWidth || 1280
  const vh = window.innerHeight || 800
  let x = Number(event?.clientX) || 0
  let y = Number(event?.clientY) || 0
  if (x + MENU_W + EDGE > vw) x = Math.max(EDGE, vw - MENU_W - EDGE)
  if (y + height + EDGE > vh) y = Math.max(EDGE, vh - height - EDGE)
  state.x = x
  state.y = y
  state.title = options.title || ''
  state.items = items
  state.context = options.context ?? null
  state.open = true
}

/** 关闭菜单（保留 items 以便淡出动画；不置空避免渲染时闪空） */
export function closeContextMenu() {
  state.open = false
}

/**
 * 读取菜单状态（组件内使用）。
 *
 * @returns {object} 单例状态
 */
export function useContextMenu() {
  return state
}
