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
    「界面与布局」设置页：所有骨架偏好的可视化出口。
    为什么必须有这一页：布局重构后大量设置变成了「拖拽/点图标」这类隐性操作，
    没有集中入口的话，用户（尤其从旧版升级上来的老用户）会找不到；
    这里同时也是「重置到默认」的唯一出口。
    项目内外共用同一页（偏好是用户级、与项目无关）。
  -->
  <div class="settings-stack">
    <PageHeader
      title="界面与布局"
      desc="侧栏宽度、信息密度、面板固定与导览。所有偏好保存在本地浏览器，不影响项目数据。"
    >
      <template #actions>
        <el-button size="small" @click="restartTour">
          <el-icon><HIcon name="ic_public_highlight" /></el-icon>
          <span>重看界面导览</span>
        </el-button>
        <el-button size="small" @click="resetAll">恢复默认</el-button>
      </template>
    </PageHeader>

    <!-- ===== 侧栏 ===== -->
    <section class="set-card">
      <div class="set-card-head">
        <h3 class="set-card-title">域侧栏</h3>
        <span class="set-tip">用于「去哪一页」；窄屏（&lt;1024）会自动改为滑入式</span>
      </div>

      <div class="set-row">
        <span class="set-row-label">侧栏宽度</span>
        <div class="set-control wide">
          <el-slider
            :model-value="layout.sidebarWidth"
            :min="SIDEBAR_MIN_WIDTH"
            :max="SIDEBAR_MAX_WIDTH"
            :step="4"
            show-input
            :show-input-controls="false"
            @update:model-value="onWidth"
          />
        </div>
        <span class="set-value">{{ effectiveSidebarWidth }}px</span>
      </div>

      <div class="set-row">
        <span class="set-row-label">默认折叠</span>
        <div class="set-control">
          <el-switch :model-value="layout.sidebarCollapsed" @update:model-value="v => layout.toggleSidebar(v)" />
        </div>
        <span class="set-value is-fill">折叠后仅显示图标（收起 56px）</span>
      </div>

      <div class="set-row">
        <span class="set-row-label">允许拖拽改宽</span>
        <div class="set-control">
          <el-switch :model-value="layout.dragEnabled" @update:model-value="v => layout.setDragEnabled(v)" />
        </div>
        <span class="set-value is-fill">关闭后侧栏右缘的拖拽手柄隐藏（避免误触）</span>
      </div>
    </section>

    <!-- ===== 信息密度 ===== -->
    <section class="set-card">
      <div class="set-card-head">
        <h3 class="set-card-title">信息密度</h3>
        <span class="set-tip">同时影响行高、间距、字号与内容区留白</span>
      </div>
      <div class="ap-density">
        <button
          v-for="d in DENSITY_MODES"
          :key="d.key"
          class="ap-density-item"
          :class="{ active: layout.density === d.key }"
          @click="layout.setDensity(d.key)"
        >
          <span class="ap-density-name">{{ d.name }}</span>
          <span class="ap-density-desc">{{ d.desc }}</span>
        </button>
      </div>

      <div class="set-row">
        <span class="set-row-label">内容宽度</span>
        <div class="set-control">
          <el-radio-group :model-value="layout.contentMode" @update:model-value="v => layout.setContentMode(v)">
            <el-radio-button v-for="m in CONTENT_MODES" :key="m.key" :value="m.key">{{ m.name }}</el-radio-button>
          </el-radio-group>
        </div>
        <span class="set-value is-fill">{{ contentModeHint }}</span>
      </div>
    </section>

    <!-- ===== 面板（抽屉）固定 ===== -->
    <section class="set-card">
      <div class="set-card-head">
        <h3 class="set-card-title">面板固定</h3>
        <span class="set-tip">固定后的面板常驻显示并挤压内容区，不会被 Esc 或点击空白关闭</span>
      </div>
      <div class="ap-drawers">
        <div v-for="d in DRAWERS" :key="d.id" class="ap-drawer-item">
          <el-icon class="ap-drawer-icon" :size="16"><HIcon :name="d.icon" /></el-icon>
          <span class="ap-drawer-text">
            <span class="ap-drawer-name">{{ d.title }}</span>
            <span class="ap-drawer-side">{{ d.side === 'left' ? '左侧' : '右侧' }}</span>
          </span>
          <el-tag v-if="layout.pinnedOpen[d.id]" size="small" type="success" effect="plain">已常驻</el-tag>
          <el-tag v-else-if="layout.openDrawerId === d.id" size="small" type="primary" effect="plain">打开中</el-tag>
          <el-button size="small" text @click="layout.toggleDrawer(d.id)">
            {{ layout.isDrawerVisible(d.id) ? '关闭' : '打开' }}
          </el-button>
          <el-button size="small" :type="layout.drawerPinned[d.id] ? 'primary' : 'default'" text @click="layout.toggleDrawerPin(d.id)">
            {{ layout.drawerPinned[d.id] ? '取消固定' : '固定' }}
          </el-button>
        </div>
      </div>
    </section>

    <!-- ===== 导览与快捷键 ===== -->
    <section class="set-card">
      <div class="set-card-head">
        <h3 class="set-card-title">上手辅助</h3>
        <span class="set-tip">新布局的入口变了，这些辅助能帮你快速找回肌肉记忆</span>
      </div>
      <div class="set-row">
        <span class="set-row-label">界面导览</span>
        <div class="set-control">
          <el-button size="small" @click="restartTour">播放导览</el-button>
        </div>
        <span class="set-value is-fill">{{ layout.tourSeen ? '已观看过（可随时重看）' : '尚未观看' }}</span>
      </div>
      <div class="set-row">
        <span class="set-row-label">快捷键速查</span>
        <div class="set-control">
          <el-button size="small" @click="layout.openDrawer('help')">打开帮助面板</el-button>
        </div>
        <span class="set-value is-fill">命令面板 Ctrl+K · 侧栏 Ctrl+B · 密度 Ctrl+Shift+D</span>
      </div>
    </section>
  </div>
</template>

<script setup>
/**
 * 界面与布局设置页。
 *
 * <p>职责：把 layout store 的全部偏好暴露为可视化控件，并提供「恢复默认」与「重看导览」。
 * 本页**不含任何业务数据请求**——偏好全部在本地，因此项目内外可共用同一组件
 * （路由：`/world/:id/settings/appearance` 与 `/settings/appearance`）。</p>
 */
import { computed } from 'vue'
import {
  useLayoutStore,
  DENSITY_MODES,
  CONTENT_MODES,
  SIDEBAR_MIN_WIDTH,
  SIDEBAR_MAX_WIDTH,
  SIDEBAR_DEFAULT_WIDTH
} from '@/shared/store/layout'
import { useShellLayout } from '@/shared/layout/useShellLayout'
import PageHeader from '@/shared/layout/PageHeader.vue'

const layout = useLayoutStore()
const { effectiveSidebarWidth } = useShellLayout()

/** 与 AppShell 的抽屉声明保持一致的只读清单（仅用于本页展示与开关） */
const DRAWERS = [
  { id: 'pinned', title: '主工作区', icon: 'ic_gallery_map_all', side: 'left' },
  { id: 'cast', title: '角色', icon: 'ic_public_contacts', side: 'left' },
  { id: 'mind', title: '记忆与知识', icon: 'ic_public_albums', side: 'left' },
  { id: 'world', title: '世界设定', icon: 'ic_public_worldclock', side: 'left' },
  { id: 'ai-runtime', title: 'AI 运行状态', icon: 'ic_public_spinner', side: 'right' },
  { id: 'help', title: '帮助与快捷键', icon: 'ic_public_file', side: 'right' }
]

const contentModeHint = computed(() => (
  layout.contentMode === 'wide' ? '表格/图谱类页面更实用' : '长文阅读更舒适（推荐）'
))

function onWidth(v) { layout.setSidebarWidth(v) }
function restartTour() { layout.startTour() }

/** 恢复默认：宽高/折叠/密度/内容宽度/拖拽/固定面板全部复位（不动主题与账户） */
function resetAll() {
  layout.resetSidebarWidth()
  layout.toggleSidebar(false)
  layout.setSidebarManualExpand(false)
  layout.setDensity('cozy')
  layout.setContentMode('centered')
  layout.setDragEnabled(true)
  for (const d of DRAWERS) {
    if (layout.drawerPinned[d.id]) layout.toggleDrawerPin(d.id)
    if (layout.isDrawerVisible(d.id)) layout.closeDrawer(d.id)
  }
}
</script>

<style scoped>
/* V2.1：卡片/标题/行控件/提示走全局设置页规范（.set-card/.set-card-head/.set-row 等），
   此处仅保留密度选择器与抽屉清单的本页特有样式。 */
/* 密度三档选择卡 */
.ap-density { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: var(--space-2, 8px); margin-bottom: var(--space-3, 12px); }
.ap-density-item {
  display: flex; flex-direction: column; gap: 4px;
  padding: 10px 12px;
  background: var(--bg-layer-2);
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-md, 12px);
  cursor: pointer; text-align: left;
  transition: all .15s var(--dsw-ease);
}
.ap-density-item:hover { border-color: var(--accent-border); }
.ap-density-item.active { border-color: var(--accent); background: var(--accent-soft); }
.ap-density-name { font-size: var(--density-font, 0.9rem); font-weight: 600; color: var(--text-primary); }
.ap-density-desc { font-size: var(--density-font-sm, 0.82rem); color: var(--text-tertiary); line-height: 1.5; }

/* 面板（抽屉）固定清单：行分隔线式列表 */
.ap-drawers { display: flex; flex-direction: column; }
.ap-drawer-item { display: flex; align-items: center; gap: var(--space-2, 8px); padding: 7px 0; border-bottom: 1px dashed var(--border-l1); }
.ap-drawer-item:last-child { border-bottom: none; }
.ap-drawer-icon { color: var(--accent-text); flex-shrink: 0; }
.ap-drawer-text { display: flex; flex-direction: column; flex: 1; min-width: 0; }
.ap-drawer-name { font-size: var(--density-font-sm, 0.82rem); color: var(--text-primary); }
.ap-drawer-side { font-size: 0.72rem; color: var(--text-tertiary); }
</style>
