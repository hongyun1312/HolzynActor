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
    抽屉面板外壳（酒馆式 drawer 的三段式：toggle 在顶栏、content 在这里）。
    两种存在形态：
      ① 覆盖式（默认）：浮在内容区之上，点击面板外空白或 Esc 关闭（对齐酒馆）；
      ② 固定式（pinned）：作为 shell-body 的 flex 子项存在，**参与布局**（挤压内容区），不被自动关闭。
    固定式是本项目对酒馆的有意增强：本项目常需「对话 + 角色卡」同屏对照，覆盖式会互相遮挡。
  -->
  <section
    v-if="visible"
    class="drawer-panel"
    :class="[`side-${side}`, { 'is-pinned': pinned, 'is-overlay': !pinned }]"
    :style="panelStyle"
  >
    <header class="dp-head">
      <el-icon class="dp-head-icon" :size="16"><HIcon :name="icon" /></el-icon>
      <span class="dp-title">{{ title }}</span>
      <span v-if="hint" class="dp-hint">{{ hint }}</span>
      <button
        v-if="pinEnabled"
        class="dp-btn"
        :class="{ active: pinned }"
        :title="pinned ? '取消固定（恢复为自动关闭）' : '固定常驻（不参与互斥、不被 Esc/点空白关闭）'"
        @click="store.toggleDrawerPin(id)"
      >
        <el-icon :size="15"><HIcon name="Key" /></el-icon>
      </button>
      <button class="dp-btn" title="关闭" @click="store.closeDrawer(id)">
        <el-icon :size="15"><HIcon name="Close" /></el-icon>
      </button>
    </header>
    <div class="dp-body">
      <slot></slot>
    </div>
  </section>
</template>

<script setup>
/**
 * 抽屉面板外壳。
 *
 * <p>职责：统一抽屉的头部（图标/标题/提示/图钉/关闭）与主体滚动容器；可见性与固定状态
 * 全部读自 layout store，因此任何地方（顶栏图标、命令面板、右键菜单、页面按钮）
 * 都只需调用 `layout.openDrawer(id)` 即可打开同一个抽屉。</p>
 *
 * <p>图钉语义（对齐酒馆并做了本项目化增强）：</p>
 * <ul>
 *   <li>未固定：与其它未固定抽屉互斥；点击空白或 Esc 关闭；</li>
 *   <li>固定：不参与互斥、不被自动关闭，且由 AppShell 作为 flex 子项渲染 → 挤压内容区而非遮挡。</li>
 * </ul>
 */
import { computed } from 'vue'
import { useLayoutStore } from '@/shared/store/layout'

const props = defineProps({
  /** 抽屉唯一标识（与 navModel.TOPBAR_ENTRIES.target 一致） */
  id: { type: String, required: true },
  /** 标题 */
  title: { type: String, default: '' },
  /** 头部图标（HIcon 名称） */
  icon: { type: String, default: 'ic_public_file' },
  /** 副标题/提示 */
  hint: { type: String, default: '' },
  /** 停靠方向：left | right | center（center = 顶部居中下拉，对齐酒馆 7 个面板的行为） */
  side: { type: String, default: 'right' },
  /** 宽度（px） */
  width: { type: Number, default: 360 },
  /** 是否允许固定（酒馆只有 3 个面板可固定；本项目默认都允许） */
  pinEnabled: { type: Boolean, default: true }
})

const store = useLayoutStore()

/** 是否可见：非固定打开 或 已被固定并常驻 */
const visible = computed(() => store.isDrawerVisible(props.id))
/** 是否处于固定常驻形态 */
const pinned = computed(() => !!store.pinnedOpen[props.id])

/** 面板尺寸：居中下拉时取 min(宽度, 视口的 60%)，避免窄屏溢出 */
const panelStyle = computed(() => {
  if (props.side === 'center') {
    return { width: `min(${props.width}px, 60vw)` }
  }
  return { width: `${props.width}px` }
})
</script>

<style scoped>
.drawer-panel {
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l1);
  overflow: hidden;
}

/* ===== 覆盖式：绝对定位浮在内容区之上（不挤压内容，对齐酒馆） ===== */
.drawer-panel.is-overlay { position: absolute; z-index: var(--z-drawer, 40); box-shadow: var(--shadow-lv3); }
.drawer-panel.is-overlay.side-left { left: 0; top: 0; bottom: 0; border-radius: 0 var(--radius-lg, 16px) var(--radius-lg, 16px) 0; }
.drawer-panel.is-overlay.side-right { right: 0; top: 0; bottom: 0; border-radius: var(--radius-lg, 16px) 0 0 var(--radius-lg, 16px); }
/* 居中下拉：对齐酒馆 `.drawer-content`（top 起于顶栏下沿、左右居中、内部滚动、最大高度止于底部） */
.drawer-panel.is-overlay.side-center {
  top: 0; left: 50%; transform: translateX(-50%);
  max-height: 100%;
  border-radius: 0 0 var(--radius-lg, 16px) var(--radius-lg, 16px);
}

/* ===== 固定式：几何相同（贴边满高），但**内容区被 AppShell 内缩**，因此是「挤压」而非「遮挡」 =====
   视觉上用实色右边框替代浮起阴影，明确「这块是常驻布局的一部分」。
   左侧固定面板**必须让开域侧栏**（--shell-sidebar-w 由 AppShell 实测注入）：
   若从 left:0 起排，导航侧栏会被整体推向右侧，点一次「固定」就让导航跳位，观感很差。
   因此左侧固定面板紧贴侧栏右缘 + 一个外边距，导航恒定停在最左。 */
.drawer-panel.is-pinned { position: absolute; top: 0; bottom: 0; z-index: 2; box-shadow: none; }
.drawer-panel.is-pinned.side-left {
  left: calc(var(--shell-sidebar-w, 0px) + var(--shell-side-margin, 12px));
  border-right: 1px solid var(--border-l2);
}
.drawer-panel.is-pinned.side-right {
  right: var(--shell-side-margin, 12px);
  border-left: 1px solid var(--border-l2);
}

/* 头部 */
.dp-head {
  display: flex; align-items: center; gap: var(--space-2, 8px);
  height: 42px; flex-shrink: 0;
  padding: 0 var(--space-3, 12px);
  border-bottom: 1px solid var(--border-l1);
  background: var(--bg-layer-2);
}
.dp-head-icon { color: var(--accent-text); }
.dp-title { font-size: var(--density-font, 0.9rem); font-weight: 600; color: var(--text-primary); white-space: nowrap; }
.dp-hint { flex: 1; font-size: 0.74rem; color: var(--text-tertiary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dp-btn {
  margin-left: auto;
  width: 26px; height: 26px; flex-shrink: 0;
  display: inline-flex; align-items: center; justify-content: center;
  background: transparent; border: none; border-radius: var(--radius-sm, 8px);
  color: var(--text-secondary); cursor: pointer; transition: all .15s var(--dsw-ease);
}
.dp-btn + .dp-btn { margin-left: 0; }
.dp-btn:hover { background: var(--bg-hover); color: var(--text-primary); }
/* 已固定：图钉点亮 */
.dp-btn.active { color: var(--accent-text); background: var(--accent-soft); }

/* 主体：独立滚动容器 */
.dp-body { flex: 1; min-height: 0; overflow-y: auto; padding: var(--density-card-pad, 16px); }
.dp-body::-webkit-scrollbar { width: 6px; }
.dp-body::-webkit-scrollbar-thumb { background: rgba(120, 140, 180, 0.2); border-radius: 3px; }
</style>
