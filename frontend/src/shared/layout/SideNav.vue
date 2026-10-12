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
    SideNav：侧栏「表现层」组件（不含任何路由/数据依赖，纯展示 + 事件）。
    设计意图：把「悬浮岛侧栏」的视觉与交互收敛成一份实现，
    供 DomainSidebar（项目六域导航）、NewProjectView（新建向导导航）、SettingsView（设置子导航）复用，
    彻底消除上一版「三套侧栏各写一遍、样式逐渐分叉」的问题。
  -->
  <nav class="side-nav" :class="{ 'is-icon-only': collapsed }">
    <!-- 置顶固定项（不属于任何域，永远在最前） -->
    <div v-if="pinned && pinned.length" class="side-pinned" data-tour="pinned-nav">
      <a
        v-for="item in pinned"
        :key="item.key"
        class="side-item"
        :class="{ active: item.key === activeKey }"
        :title="collapsed ? `${item.name}${item.desc ? ' · ' + item.desc : ''}` : ''"
        href="#"
        @click.prevent="$emit('navigate', item)"
        @contextmenu.prevent="$emit('context', $event, item)"
      >
        <el-icon class="side-icon"><HIcon :name="item.icon" /></el-icon>
        <span v-if="!collapsed" class="side-text">{{ item.name }}</span>
      </a>
    </div>

    <!-- 分组（域）导航：点击折叠/展开；当前页所在分组默认展开 -->
    <div
      v-for="section in sections"
      :key="section.key"
      class="side-section"
      :class="{ open: isOpen(section.key) }"
    >
      <button
        type="button"
        class="side-head"
        :title="collapsed ? section.name : ''"
        @click="toggle(section.key)"
      >
        <el-icon class="side-icon"><HIcon :name="section.icon" /></el-icon>
        <span v-if="!collapsed" class="side-text">{{ section.name }}</span>
        <!-- 折叠箭头用 CSS 绘制：ic_public_drawer 在鸿蒙图标集中是「抽屉」而非雪佛龙，
             直接映射 ArrowDown 会渲染成三条横线（≡），语义与观感都不对 -->
        <span v-if="!collapsed" class="side-caret" aria-hidden="true"></span>
      </button>

      <!-- 子项：图标栏形态下也保留（悬浮/展开时可见），保证收起后仍可直达 -->
      <div v-show="isOpen(section.key) || collapsed" class="side-children">
        <a
          v-for="child in section.items"
          :key="child.key"
          class="side-item child"
          :class="{ active: child.key === activeKey }"
          :title="collapsed ? `${child.name}${child.desc ? ' · ' + child.desc : ''}` : (child.desc || child.name)"
          href="#"
          @click.prevent="$emit('navigate', child)"
          @contextmenu.prevent="$emit('context', $event, child)"
        >
          <el-icon class="side-icon"><HIcon :name="child.icon" /></el-icon>
          <span v-if="!collapsed" class="side-text">{{ child.name }}</span>
        </a>
      </div>
    </div>

    <!-- 底部插槽：项目状态小卡 / 版本号等由宿主注入 -->
    <div v-if="!collapsed" class="side-foot">
      <slot name="foot"></slot>
    </div>
  </nav>
</template>

<script setup>
/**
 * 侧栏表现层组件。
 *
 * <p>职责：渲染「置顶项 + 若干可折叠分组」的导航列表，处理展开状态与当前项高亮，
 * 并通过 `navigate` 事件把点击意图交回宿主（宿主负责 router.push / 关闭覆盖式侧栏）。</p>
 *
 * <p>为什么做成表现层：项目空间侧栏（六域）、新建项目页侧栏（创建方式四步）、
 * 设置页侧栏（设置子页）在功能上都是「分组导航」，此前三处各写一遍导致样式分叉。
 * 统一成本组件后，任何视觉调整只改一处。</p>
 *
 * <p>折叠（icon-only）形态：由宿主通过 CSS 变量控制侧栏宽度；本组件据 `collapsed` 隐藏文字，
 * 并把名称降级为原生 title 提示（避免引入额外浮层依赖）。</p>
 */
import { computed, ref, watch } from 'vue'

const props = defineProps({
  /** 置顶固定项：[{ key, name, icon, desc }] */
  pinned: { type: Array, default: () => [] },
  /** 分组：[{ key, name, icon, items: [{ key, name, icon, desc }] }] */
  sections: { type: Array, default: () => [] },
  /** 当前激活项 key（由宿主按路由推导） */
  activeKey: { type: String, default: '' },
  /** 是否图标窄栏形态 */
  collapsed: { type: Boolean, default: false }
})
// context：右键导航项（$event + item），由宿主决定弹什么菜单（本组件不预设业务动作）
defineEmits(['navigate', 'context'])

/** 分组展开状态（默认全部展开；进入页面后自动展开当前项所在分组） */
const openGroups = ref({})

/** 全部展开为初始态，保证首次进入即可看到所有功能（旧版默认收起导致发现性差） */
watch(() => props.sections, (list) => {
  const next = { ...openGroups.value }
  for (const s of list || []) if (next[s.key] === undefined) next[s.key] = true
  openGroups.value = next
}, { immediate: true })

/** 当前激活项所在分组必须展开（例如从命令面板跳到某页后，侧栏要对得上） */
watch(() => props.activeKey, (key) => {
  if (!key) return
  for (const s of props.sections || []) {
    if ((s.items || []).some(i => i.key === key)) openGroups.value = { ...openGroups.value, [s.key]: true }
  }
}, { immediate: true })

const isOpen = (key) => !!openGroups.value[key]
function toggle(key) { openGroups.value = { ...openGroups.value, [key]: !openGroups.value[key] } }

/** 用于模板调试（不参与渲染逻辑） */
const sectionCount = computed(() => (props.sections || []).length)
</script>

<style scoped>
/* 侧栏本体：占满 aside 高度，纵向滚动（内容超长时只有导航滚动，不影响内容区） */
.side-nav {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding: var(--space-2, 8px) var(--space-2, 8px) var(--space-3, 12px);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

/* ===== 置顶项与子项共用的行样式 ===== */
.side-item {
  display: flex;
  align-items: center;
  gap: 10px;
  height: var(--density-row-h, 34px);
  padding: 0 10px;
  border-radius: var(--radius-sm, 8px);
  color: var(--text-secondary);
  font-size: var(--density-font, 0.9rem);
  cursor: pointer;
  transition: background .15s var(--dsw-ease), color .15s var(--dsw-ease);
  white-space: nowrap;
  overflow: hidden;
  user-select: none;
}
.side-item:hover { background: var(--bg-hover); color: var(--text-primary); }
/* 当前页：主色浅底 + 主色文字 + 左侧主色指示条 */
.side-item.active {
  background: var(--accent-soft);
  color: var(--accent-text);
  font-weight: 600;
  position: relative;
}
.side-item.active::before {
  content: '';
  position: absolute;
  left: 2px; top: 50%;
  transform: translateY(-50%);
  width: 3px; height: 16px;
  border-radius: 2px;
  background: var(--accent);
}
.side-icon { font-size: 16px; flex-shrink: 0; }
.side-text { flex: 1; overflow: hidden; text-overflow: ellipsis; }

/* 置顶项与分组之间加一道细分隔，形成「主工作区 / 功能域」的视觉分层 */
.side-pinned { display: flex; flex-direction: column; gap: 2px; padding-bottom: var(--space-2, 8px); margin-bottom: var(--space-2, 8px); border-bottom: 1px solid var(--border-l1); }

/* ===== 分组头 ===== */
.side-section { display: flex; flex-direction: column; }
.side-head {
  display: flex; align-items: center; gap: 10px;
  height: var(--density-row-h, 34px);
  padding: 0 10px;
  background: none; border: none; width: 100%;
  color: var(--text-secondary);
  font-size: var(--density-font-sm, 0.82rem);
  font-weight: 600;
  letter-spacing: .02em;
  cursor: pointer;
  border-radius: var(--radius-sm, 8px);
  transition: background .15s var(--dsw-ease), color .15s var(--dsw-ease);
}
.side-head:hover { background: var(--bg-hover); color: var(--text-primary); }
.side-caret {
  margin-left: auto; flex-shrink: 0; width: 6px; height: 6px;
  border-right: 1.6px solid currentColor; border-bottom: 1.6px solid currentColor;
  transform: rotate(45deg); transform-origin: center;
  transition: transform .18s var(--dsw-ease); opacity: .5;
}
.side-head:hover .side-caret { opacity: .85; }
.side-section.open .side-caret { transform: rotate(-135deg); }

/* 子项缩进（图标栏形态取消缩进，避免内容被裁） */
.side-children { display: flex; flex-direction: column; gap: 2px; }
.side-item.child { padding-left: 26px; }
.is-icon-only .side-item.child { padding-left: 10px; }
/* 图标栏形态：图标居中、取消内边距 */
.is-icon-only .side-item,
.is-icon-only .side-head { justify-content: center; padding: 0; }
.is-icon-only .side-caret { display: none; }
/* 分组之间留白，避免「域」糊成一片 */
.side-section + .side-section { margin-top: var(--space-2, 8px); }

/* 底部插槽 */
.side-foot { margin-top: auto; padding-top: var(--space-3, 12px); }

.side-nav::-webkit-scrollbar { width: 6px; }
.side-nav::-webkit-scrollbar-thumb { background: rgba(120, 140, 180, 0.2); border-radius: 3px; }
</style>
