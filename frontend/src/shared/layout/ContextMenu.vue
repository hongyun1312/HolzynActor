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
  <!-- 全局唯一右键菜单（挂在 AppShell 根部，全站可用；teleport 到 body 避免被祖先 overflow 裁切） -->
  <Teleport to="body">
    <div
      v-if="menu.open"
      ref="boxRef"
      class="ctx-menu"
      role="menu"
      :style="{ left: menu.x + 'px', top: menu.y + 'px' }"
      @contextmenu.prevent
    >
      <div v-if="menu.title" class="ctx-title">{{ menu.title }}</div>
      <template v-for="(it, i) in menu.items" :key="it.key || 'd' + i">
        <div v-if="it.divider" class="ctx-divider"></div>
        <button
          v-else
          type="button"
          class="ctx-item"
          :class="{ 'is-danger': it.danger, 'is-disabled': it.disabled }"
          :disabled="!!it.disabled"
          :data-ctx-key="it.key"
          role="menuitem"
          @click="run(it)"
        >
          <el-icon v-if="it.icon" class="ctx-icon"><HIcon :name="it.icon" /></el-icon>
          <span v-else class="ctx-icon"></span>
          <span class="ctx-label">{{ it.label }}</span>
          <span v-if="it.hint" class="ctx-hint">{{ it.hint }}</span>
        </button>
      </template>
    </div>
  </Teleport>
</template>

<script setup>
/**
 * 全局右键上下文菜单（表现层）。
 *
 * <p>与 {@link useContextMenu} 的分工：状态与坐标夹取在 store，本组件只管渲染与关闭时机。</p>
 *
 * <p>关闭时机（对齐桌面端操作习惯）：选中某项、Esc、点击菜单外、页面滚动、窗口尺寸变化。
 * 键盘可达：打开后自动聚焦第一个可用项，↑/↓ 移动，Enter 触发。</p>
 */
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { closeContextMenu, useContextMenu } from './useContextMenu'

const menu = useContextMenu()
const boxRef = ref(null)

/** 触发某个动作（先关菜单再执行：动作可能弹对话框/跳路由，菜单必须先消失） */
function run(item) {
  if (!item || item.disabled) return
  const fn = item.run
  closeContextMenu()
  if (typeof fn === 'function') fn()
}

/** 全局事件：Esc / 点击外部 / 滚动 / 尺寸变化 */
function onKeydown(e) {
  if (!menu.open) return
  if (e.key === 'Escape') {
    e.stopPropagation()
    closeContextMenu()
    return
  }
  const items = menu.items.filter((i) => !i.divider && !i.disabled)
  const cur = document.activeElement
  const idx = items.findIndex((i) => cur && cur.dataset && cur.dataset.ctxKey === i.key)
  if ((e.key === 'ArrowDown' || e.key === 'ArrowUp') && items.length) {
    e.preventDefault()
    const next = e.key === 'ArrowDown' ? (idx + 1 + items.length) % items.length : (idx - 1 + items.length) % items.length
    const el = boxRef.value?.querySelector(`[data-ctx-key="${items[next].key}"]`)
    if (el) el.focus()
  }
}

function onPointerDown(e) {
  if (!menu.open) return
  if (boxRef.value && !boxRef.value.contains(e.target)) closeContextMenu()
}

function onScrollOrResize() {
  if (menu.open) closeContextMenu()
}

onMounted(() => {
  window.addEventListener('keydown', onKeydown, true)
  window.addEventListener('pointerdown', onPointerDown, true)
  window.addEventListener('scroll', onScrollOrResize, true)
  window.addEventListener('resize', onScrollOrResize)
  window.addEventListener('wheel', onScrollOrResize, { passive: true })
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown, true)
  window.removeEventListener('pointerdown', onPointerDown, true)
  window.removeEventListener('scroll', onScrollOrResize, true)
  window.removeEventListener('resize', onScrollOrResize)
  window.removeEventListener('wheel', onScrollOrResize)
})

// 打开后把焦点放到第一项（键盘可达；↑/↓ 依 data-ctx-key 定位）
watch(() => menu.open, async (v) => {
  if (!v) return
  await nextTick()
  boxRef.value?.querySelector('.ctx-item:not([disabled])')?.focus()
})
</script>

<style scoped>
.ctx-menu {
  position: fixed;
  z-index: var(--z-context-menu, 3000);
  min-width: 210px;
  max-width: 300px;
  padding: 6px;
  background: var(--bg-layer-2, var(--bg-layer-1));
  border: 1px solid var(--border-l1);
  border-radius: 10px;
  box-shadow: var(--shadow-lv3);
  backdrop-filter: blur(12px);
  animation: ctx-in 0.12s var(--dsw-ease, ease-out);
}
@keyframes ctx-in {
  from { opacity: 0; transform: translateY(-4px) scale(0.98); }
  to { opacity: 1; transform: none; }
}
.ctx-title {
  padding: 4px 10px 6px;
  font-size: 12px;
  color: var(--text-secondary);
  border-bottom: 1px solid var(--border-l1);
  margin-bottom: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ctx-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  height: 32px;
  padding: 0 10px;
  border: none;
  background: transparent;
  border-radius: 6px;
  font-size: 13px;
  color: var(--text-primary);
  cursor: pointer;
  text-align: left;
  font-family: inherit;
}
.ctx-item:hover:not(:disabled) {
  background: var(--bg-hover);
}
/* V2.0 a11y：原实现把键盘焦点与 hover 合并后 outline:none，等于键盘用户没有任何焦点指示。
   右键菜单是纯键盘可操作场景（方向键 + Enter），焦点必须可见 → 实心主色内缩环。 */
.ctx-item:focus-visible {
  background: var(--bg-hover);
  outline: 2px solid var(--accent);
  outline-offset: -2px;
}
.ctx-item.is-danger {
  color: var(--el-color-danger);
}
.ctx-item.is-disabled {
  color: var(--text-secondary);
  cursor: not-allowed;
  opacity: 0.6;
}
.ctx-icon {
  width: 16px;
  flex: none;
  display: inline-flex;
  justify-content: center;
  font-size: 14px;
  color: var(--text-secondary);
}
.ctx-item.is-danger .ctx-icon {
  color: var(--el-color-danger);
}
.ctx-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ctx-hint {
  font-size: 11px;
  color: var(--text-secondary);
  flex: none;
}
.ctx-divider {
  height: 1px;
  margin: 4px 8px;
  background: var(--border-l1);
}
</style>
