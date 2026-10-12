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
    可并置多面板工作区：2~3 栏并排、可拖宽、可折叠、宽度与折叠态落盘记忆。
    与上一版的关系：上一版每个页面各自写 `display:grid; grid-template-columns:260px 1fr`，
    宽度不可调、不可折叠、刷新即回默认；本组件把「并置 + 拖宽 + 折叠 + 记忆」收敛成一处。
  -->
  <div class="wsplit" :class="{ 'is-resizing': dragging }" :data-workspace="storageKey">
    <template v-for="(pane, i) in visiblePanes" :key="pane.key">
      <!-- 面板之间的拖拽手柄（放在两个面板中间，拖的是「左面板」的宽度） -->
      <div
        v-if="i > 0"
        class="wsplit-handle"
        :class="{ 'is-disabled': !resizeAllowed(visiblePanes[i - 1]) }"
        :title="resizeAllowed(visiblePanes[i - 1]) ? '拖拽调整左侧面板宽度（双击恢复默认）' : ''"
        :data-handle="`${visiblePanes[i - 1].key}|${pane.key}`"
        @pointerdown="onResizeStart($event, visiblePanes[i - 1])"
        @dblclick="onResizeReset(visiblePanes[i - 1])"
      ></div>

      <!-- 折叠态：细轨（竖向标题 + 展开按钮），保证折叠后仍可发现、可还原 -->
      <div
        v-if="isCollapsed(pane)"
        class="wsplit-rail"
        :data-pane="pane.key"
        :data-collapsed="'true'"
      >
        <button
          type="button"
          class="wsplit-rail-btn"
          :title="`展开「${pane.title}」`"
          :data-expand="pane.key"
          @click="toggleCollapse(pane, false)"
        >
          <el-icon><HIcon name="ic_public_arrow_right" /></el-icon>
        </button>
        <span class="wsplit-rail-title">{{ pane.title }}</span>
        <span v-if="pane.count != null" class="wsplit-rail-count">{{ pane.count }}</span>
      </div>

      <!-- 展开态：统一面板框（标题栏由本组件渲染，页面不再各写一套） -->
      <section
        v-else
        class="wsplit-pane tech-card"
        :class="[`is-${pane.key}`, { 'is-flex': isFlex(pane) }]"
        :style="paneStyle(pane)"
        :data-pane="pane.key"
        :data-collapsed="'false'"
      >
        <header class="wsplit-head">
          <span class="wsplit-title" :title="pane.title">{{ pane.title }}</span>
          <span v-if="pane.count != null" class="wsplit-count">{{ pane.count }}</span>
          <span class="wsplit-spacer"></span>
          <slot :name="`actions-${pane.key}`" :pane="pane"></slot>
          <button
            v-if="pane.collapsible"
            type="button"
            class="wsplit-icon-btn"
            :title="`折叠「${pane.title}」`"
            :data-collapse="pane.key"
            @click="toggleCollapse(pane, true)"
          >
            <el-icon><HIcon name="ic_public_arrow_left" /></el-icon>
          </button>
          <button
            v-if="pane.closable"
            type="button"
            class="wsplit-icon-btn"
            :title="`关闭「${pane.title}」`"
            :data-close="pane.key"
            @click="$emit('close-pane', pane.key)"
          >
            <el-icon><HIcon name="ic_public_close" /></el-icon>
          </button>
        </header>
        <div class="wsplit-body" :class="pane.bodyClass">
          <!-- 每个面板一份隔离上下文（并置时互不污染选中项/筛选） -->
          <WorkspacePane :pane-key="pane.key" :context="pane.context || null" :initial="pane.context ? {} : (pane.initial || {})">
            <slot :name="`pane-${pane.key}`" :pane="pane"></slot>
          </WorkspacePane>
        </div>
      </section>
    </template>
  </div>
</template>

<script setup>
/**
 * 可并置多面板工作区容器（批次③ 剩余交互能力）。
 *
 * <p>能力：① **并置** 2~3 个面板（含运行时增删，如「再开一栏角色详情」）；
 * ② **拖宽**（PointerEvent，与外壳侧栏同一套范式）；③ **折叠**（折叠为 34px 细轨，可还原）；
 * ④ **记忆**（宽度/折叠态写入 layout store 并落盘，刷新后保持）；⑤ **统一面板标题栏**
 * （标题/计数/操作区/折叠/关闭），页面不再各写一套头。</p>
 *
 * <p>为什么不用 splitpanes 库：外壳侧栏已有一套自研拖拽+记忆实现（`useShellLayout.startResize`），
 * 再引一个库会同时存在两套拖拽范式（事件、钳制、落盘时机各异）；本组件复用既有范式，
 * 零新增依赖。</p>
 *
 * <p>用法（面板插槽名 = `pane-<key>`，操作区插槽 = `actions-<key>`）：</p>
 * <pre>
 * &lt;WorkspaceSplit storage-key="wg.locations" :panes="panes" @close-pane="closePane"&gt;
 *   &lt;template #pane-locations&gt;…&lt;/template&gt;
 *   &lt;template #pane-detail&gt;…&lt;/template&gt;
 * &lt;/WorkspaceSplit&gt;
 * </pre>
 *
 * <p>`panes` 描述项：`{ key, title, count?, width?, min?, max?, resizable?, collapsible?,
 * closable?, flex?, bodyClass? }`。每栏的**上下文隔离**由 {@link usePanelContext} 负责，
 * 本组件只负责几何与标题栏。</p>
 */
import { computed, ref } from 'vue'
import { useLayoutStore } from '@/shared/store/layout'
import WorkspacePane from './WorkspacePane.vue'

const props = defineProps({
  /** 面板描述数组（顺序即渲染顺序；运行时增删即并置变化） */
  panes: { type: Array, required: true },
  /** 工作区键（宽度/折叠记忆的命名空间，建议「页面.切面」） */
  storageKey: { type: String, required: true },
  /** 默认面板宽度（未记忆时的回退值） */
  defaultWidth: { type: Number, default: 260 },
  /** 面板最小宽度 */
  minWidth: { type: Number, default: 180 },
  /** 面板最大宽度 */
  maxWidth: { type: Number, default: 560 }
})
const emit = defineEmits(['close-pane', 'resize-start', 'resize-end'])

const layout = useLayoutStore()
const dragging = ref(false)

/** 可见面板（页面通过增删 panes 控制并置数量） */
const visiblePanes = computed(() => props.panes.filter(Boolean))

/** 是否可拖拽（全局「允许拖拽」开关 + 面板自身 resizable + 非 flex 栏） */
function resizeAllowed(pane) {
  return layout.dragEnabled && pane && pane.resizable !== false && !isFlex(pane)
}

/** 是否是「自适应剩余宽度」的栏（通常是详情栏） */
function isFlex(pane) {
  return !!pane.flex
}

/** 某面板是否处于折叠态（记忆值） */
function isCollapsed(pane) {
  if (!pane.collapsible) return false
  return layout.workspacePane(props.storageKey, pane.key).collapsed
}

/** 面板内联样式：固定栏给宽度，flex 栏给自适应 */
function paneStyle(pane) {
  if (isFlex(pane)) return {}
  const w = pane.width ?? layout.workspacePane(props.storageKey, pane.key).width ?? props.defaultWidth
  return { width: `${w}px` }
}

/** 折叠/展开某面板（写入记忆） */
function toggleCollapse(pane, collapsed) {
  layout.toggleWorkspaceCollapse(props.storageKey, pane.key, collapsed)
}

/** 当前生效宽度（px） */
function widthOf(pane) {
  return pane.width ?? layout.workspacePane(props.storageKey, pane.key).width ?? props.defaultWidth
}

/**
 * 开始拖拽（拖的是左侧那个固定宽面板）。
 *
 * @param {PointerEvent} ev 指针事件
 * @param {object} pane 被拖宽的面板
 */
function onResizeStart(ev, pane) {
  if (!resizeAllowed(pane)) return
  emit('resize-start', pane.key)
  dragging.value = true
  const startX = ev.clientX
  const startW = widthOf(pane)
  const min = pane.min ?? props.minWidth
  const max = pane.max ?? props.maxWidth
  try { ev.currentTarget?.setPointerCapture?.(ev.pointerId) } catch (_) { /* 环境不支持则忽略 */ }
  const onMove = (e) => {
    const next = Math.min(max, Math.max(min, Math.round(startW + (e.clientX - startX))))
    layout.setWorkspaceWidth(props.storageKey, pane.key, next)
  }
  const onUp = () => {
    dragging.value = false
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
    window.removeEventListener('pointercancel', onUp)
    document.body.classList.remove('is-resizing')
    emit('resize-end', pane.key)
  }
  document.body.classList.add('is-resizing')
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
  window.addEventListener('pointercancel', onUp)
}

/** 双击手柄恢复该面板默认宽度 */
function onResizeReset(pane) {
  if (!resizeAllowed(pane)) return
  layout.resetWorkspaceWidth(props.storageKey, pane.key)
}
</script>

<style scoped>
.wsplit {
  display: flex;
  align-items: stretch;
  gap: 0;
  min-height: 0;
  min-width: 0;
}
.wsplit-pane {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  padding: 0;
  overflow: hidden;
}
.wsplit-pane.is-flex {
  flex: 1 1 0;
}
.wsplit-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 9px 10px 8px 12px;
  border-bottom: 1px solid var(--border-l1);
  flex: none;
}
.wsplit-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.wsplit-count {
  font-size: 12px;
  color: var(--text-secondary);
  font-variant-numeric: tabular-nums;
}
.wsplit-spacer {
  flex: 1;
}
.wsplit-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: none;
  border-radius: 5px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
  font-size: 13px;
}
.wsplit-icon-btn:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}
.wsplit-body {
  flex: 1;
  min-height: 0;
  min-width: 0;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  overflow: auto;
}
/* 拖拽手柄：视觉上 1px 分隔线，命中区 9px（避免「看得见点不到」） */
.wsplit-handle {
  flex: none;
  width: 9px;
  margin: 0 -4px;
  cursor: col-resize;
  background: transparent;
  position: relative;
  z-index: 2;
}
.wsplit-handle::after {
  content: '';
  position: absolute;
  left: 4px;
  top: 4px;
  bottom: 4px;
  width: 1px;
  background: var(--border-l1);
  transition: background 0.15s ease;
}
.wsplit-handle:hover::after,
.is-resizing .wsplit-handle::after {
  background: var(--color-primary, var(--el-color-primary));
  width: 2px;
}
.wsplit-handle.is-disabled {
  cursor: default;
}
/* 折叠细轨：竖向标题 + 展开按钮（折叠后仍可发现） */
.wsplit-rail {
  flex: none;
  width: 34px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l1);
  border-radius: 10px;
  color: var(--text-secondary);
}
.wsplit-rail-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: none;
  border-radius: 5px;
  background: transparent;
  color: var(--text-secondary);
  cursor: pointer;
}
.wsplit-rail-btn:hover {
  background: var(--bg-hover);
  color: var(--text-primary);
}
.wsplit-rail-title {
  writing-mode: vertical-rl;
  font-size: 12px;
  letter-spacing: 1px;
  color: var(--text-regular);
  white-space: nowrap;
}
.wsplit-rail-count {
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}
</style>
