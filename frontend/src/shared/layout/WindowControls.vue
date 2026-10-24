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
    无边框窗口的自绘标题栏控件（最小化 / 最大化切换 / 关闭）。
    只在 Electron 桌面壳里渲染（window.holzynDesktop.window 存在时）；纯浏览器打开时为无 DOM，
    不会出现「点了没反应」的假按钮。
    位置：顶栏最右端、贴窗口右缘（Windows 惯例）；关闭按钮 hover 变红。
  -->
  <div v-if="available" class="win-controls" :class="{ 'is-standalone': standalone }">
    <button
      class="win-btn"
      type="button"
      title="最小化"
      aria-label="最小化窗口"
      @click="onMinimize"
    >
      <svg class="win-glyph" viewBox="0 0 10 10" aria-hidden="true">
        <path d="M2 5h6" />
      </svg>
    </button>
    <button
      class="win-btn"
      type="button"
      :title="maximized ? '向下还原' : '最大化'"
      :aria-label="maximized ? '向下还原窗口' : '最大化窗口'"
      @click="onToggleMaximize"
    >
      <!-- 还原态画两个错位的方框，最大化态画一个方框（与 Windows 原生观感一致） -->
      <svg v-if="maximized" class="win-glyph" viewBox="0 0 10 10" aria-hidden="true">
        <path d="M2.5 2.5h5v5h-5z" />
        <path d="M1 3.5v5.5h5.5" />
      </svg>
      <svg v-else class="win-glyph" viewBox="0 0 10 10" aria-hidden="true">
        <path d="M2 2h6v6H2z" />
      </svg>
    </button>
    <button
      class="win-btn is-close"
      type="button"
      title="关闭（可在「设置 → 更新与版本」改为直接退出程序）"
      aria-label="关闭窗口"
      @click="onClose"
    >
      <svg class="win-glyph" viewBox="0 0 10 10" aria-hidden="true">
        <path d="M2.2 2.2l5.6 5.6M7.8 2.2L2.2 7.8" />
      </svg>
    </button>
  </div>
</template>

<script setup>
/**
 * 无边框窗口控件。
 *
 * <p>为什么自绘而不是用 Electron 的 `titleBarOverlay`（Windows 原生三键）：</p>
 * <ul>
 *   <li>原生 overlay 会占掉右上角一块固定高度的系统区域，与全站玻璃顶栏（圆角/毛玻璃/悬停令牌）
 *       观感割裂，且切换深浅主题要再走一遍「主进程 setTitleBarOverlay」的额外链路；</li>
 *   <li>本组件的三个按钮完全复用顶栏的令牌与 hover 规则，深浅主题自动跟随，且尺寸可随
 *       `--shell-topbar-h` 一起变化（52px 顶栏里按钮满高，符合「沉浸式」无边框观感）。</li>
 * </ul>
 *
 * <p>拖拽移动窗口不由本组件负责：顶栏 `.shell-topbar` 整体是 `-webkit-app-region: drag`，
 * 按钮所在的子树被标为 `no-drag`（见 ShellTopbar.vue 样式）。</p>
 */
import { onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({
  /**
   * 独立模式：不在 AppShell 顶栏里，而是浮在无外壳页面（如 /onboarding 首次设置向导）右上角。
   * 独立模式去掉「吃掉顶栏右内边距」的负外边距，并自带与顶栏等高的高度。
   */
  standalone: { type: Boolean, default: false }
})
void props // 仅声明；模板里以 standalone 使用

/** 桌面壳窗口桥（浏览器环境为 undefined → 不渲染任何控件） */
const api = typeof window !== 'undefined' && window.holzynDesktop ? window.holzynDesktop.window : null
const available = !!api
/** 是否最大化（决定「最大化/还原」图标与 tooltip） */
const maximized = ref(false)

let unsubscribe = null

onMounted(async () => {
  if (!api) return
  try {
    maximized.value = await api.isMaximized()
  } catch (_) { /* 桥不可用时保持默认态，不影响按钮本身 */ }
  if (typeof api.onMaximizedChange === 'function') {
    unsubscribe = api.onMaximizedChange((value) => { maximized.value = value })
  }
})

onBeforeUnmount(() => {
  if (typeof unsubscribe === 'function') unsubscribe()
  unsubscribe = null
})

function onMinimize() { api?.minimize?.() }
function onClose() { api?.close?.() }

/** 最大化 ⇄ 还原：以主进程返回的真实状态为准，避免双击/系统还原造成图标与状态不一致 */
async function onToggleMaximize() {
  if (!api) return
  try {
    const next = await api.toggleMaximize()
    if (typeof next === 'boolean') maximized.value = next
  } catch (_) { /* 忽略：窗口正在关闭时 invoke 会失败 */ }
}
</script>

<style scoped>
/* 贴右缘满高：顶栏 padding 左右各 16px，用负外边距吃掉右内边距，视觉上直通窗口边缘 */
.win-controls {
  display: flex;
  align-items: stretch;
  align-self: stretch;
  margin-left: 4px;
  margin-right: calc(var(--space-4, 16px) * -1);
  flex-shrink: 0;
  /* 无边框窗口：控件必须 no-drag，否则点击会被当作拖拽窗口 */
  -webkit-app-region: no-drag;
  app-region: no-drag;
}

/* 独立模式（无外壳页面，如 /onboarding）：不贴顶栏右内边距，自带顶栏高度 */
.win-controls.is-standalone {
  margin-left: 0;
  margin-right: 0;
  height: var(--shell-topbar-h, 52px);
  align-self: auto;
}

.win-btn {
  width: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  transition: background-color .12s ease, color .12s ease;
  -webkit-app-region: no-drag;
  app-region: no-drag;
}

.win-btn:hover { background: var(--bg-hover); color: var(--text-primary); }
.win-btn:active { background: var(--bg-active); }
/* 关闭按钮：Windows 惯例的红色 hover（固定色，不随主题变，保证任何主题下都是「危险动作」） */
.win-btn.is-close:hover { background: #c42b1c; color: #fff; }
.win-btn.is-close:active { background: #b02315; color: #fff; }
.win-btn:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; }

.win-glyph {
  width: 10px;
  height: 10px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.1;
  stroke-linecap: square;
}
</style>
