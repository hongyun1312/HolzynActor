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
    顶部栏（52px）：全局唯一实现。
    三段结构：左侧（返回/侧栏开关/项目切换/标题面包屑）· 中部（全局搜索=命令面板入口）· 右侧（酒馆式图标抽屉栏 + 主题/密度 + 通知 + 账户）。
    历史背景：此前项目空间顶栏与 ConsoleTopbar 是两套独立实现，导致「画廊能用的入口进项目就没了」；
    现统一到本组件，用 mode 区分数据来源（项目模式显示项目切换与面包屑，全局模式显示品牌）。
  -->
  <header class="shell-topbar">
    <div class="tb-left">
      <button v-if="showBack" class="tb-icon-btn" title="返回项目画廊" @click="$emit('back')">
        <el-icon :size="17"><HIcon name="ArrowLeft" /></el-icon>
      </button>
      <button
        v-if="showSidebarToggle"
        class="tb-icon-btn"
        :title="sidebarCollapsed ? '展开侧栏（Ctrl+B）' : '收起侧栏（Ctrl+B）'"
        @click="$emit('toggle-sidebar')"
      >
        <el-icon :size="17"><HIcon :name="sidebarCollapsed ? 'Expand' : 'Fold'" /></el-icon>
      </button>

      <!-- 项目切换器：单击展开最近项目列表（数据来自 /api/projects，只读） -->
      <el-dropdown v-if="mode === 'project'" trigger="click" @command="switchProject">
        <button class="tb-project" title="切换项目">
          <span class="tb-project-name">{{ project?.name || '项目' }}</span>
          <el-icon :size="12"><HIcon name="ArrowDown" /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item :disabled="true">
              <span class="tb-dd-title">最近项目</span>
            </el-dropdown-item>
            <el-dropdown-item
              v-for="p in projects"
              :key="p.id"
              :command="p.id"
              :class="{ 'is-current': p.id === projectId }"
            >
              {{ p.name }}
            </el-dropdown-item>
            <el-dropdown-item v-if="!projects.length" :disabled="true">暂无其它项目</el-dropdown-item>
            <el-dropdown-item divided command="__gallery__">前往项目画廊…</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>

      <div v-else class="tb-brand">
        <span class="tb-brand-name">Holzyn</span>
        <span class="tb-brand-sep">|</span>
        <span class="tb-brand-label">{{ brandLabel }}</span>
      </div>

      <span v-if="breadcrumb" class="tb-crumb">
        <span class="tb-crumb-sep">/</span>{{ breadcrumb }}
      </span>
    </div>

    <!-- 中部：搜索框（点击/Ctrl+K 打开命令面板；此处不直接输入，避免双输入源） -->
    <div class="tb-center">
      <button class="tb-search" data-tour="search" title="搜索与命令（Ctrl+K）" @click="$emit('open-command')">
        <el-icon :size="14"><HIcon name="Search" /></el-icon>
        <span class="tb-search-ph">搜索页面、角色、记忆与命令</span>
        <kbd class="tb-kbd">Ctrl K</kbd>
      </button>
    </div>

    <div class="tb-right">
      <!-- 宿主追加的按钮（如「新建」） -->
      <slot name="actions"></slot>

      <!-- 酒馆式图标抽屉栏：点击开合对应面板 -->
      <div class="tb-icons" data-tour="topbar-icons">
        <button
          v-for="e in TOPBAR_ENTRIES"
          :key="e.key"
          class="tb-icon-btn"
          :class="{ 'is-open': isEntryOpen(e) }"
          :data-tour="e.key === 'help' ? 'help' : null"
          :title="e.hotkey ? `${e.name}（${e.hotkey}）` : `${e.name}${e.hint ? ' · ' + e.hint : ''}`"
          @click="onEntryClick(e)"
        >
          <el-icon :size="17"><HIcon :name="e.icon" /></el-icon>
          <!-- AI 运行状态：胶囊上的活动点（有推理中任务时点亮） -->
          <span v-if="e.key === 'ai-runtime' && aiBusy" class="tb-dot"></span>
        </button>
      </div>

      <span class="tb-divider"></span>

      <!-- 显示偏好：信息密度 + 深浅主题（导览目标） -->
      <div class="tb-icons" data-tour="display-prefs">
        <!-- 信息密度快速切换（紧凑 → 舒适 → 宽松） -->
        <button class="tb-icon-btn" :title="`信息密度：${densityMeta.name}（点击切换）`" @click="layout.cycleDensity()">
          <el-icon :size="17"><HIcon name="DataAnalysis" /></el-icon>
        </button>
        <!-- 深浅主题 -->
        <button class="tb-icon-btn" :title="isDark ? '切换到浅色主题' : '切换到深色主题'" @click="toggleMode">
          <el-icon :size="17"><HIcon :name="isDark ? 'Sunny' : 'Moon'" /></el-icon>
        </button>
      </div>
      <NotificationBell class="tb-bell" />
      <AvatarMenu />
    </div>
  </header>
</template>

<script setup>
/**
 * 顶部栏组件（项目空间与全局页共用）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>左侧：返回画廊、侧栏开关、项目切换器（下拉最近项目）、标题面包屑；</li>
 *   <li>中部：命令面板入口（点击或 Ctrl+K，快捷键由 AppShell 统一监听）；</li>
 *   <li>右侧：酒馆式图标抽屉组（来自 navModel.TOPBAR_ENTRIES）、信息密度切换、深浅主题、通知、账户。</li>
 * </ul>
 *
 * <p>为什么图标抽屉要放在顶栏：对齐 SillyTavern 用户的肌肉记忆——「功能入口是一排顶部图标，
 * 点击展开面板」。本项目在此基础上保留常驻域侧栏（页面多、纯图标发现性不足），
 * 二者互补：侧栏负责「去哪一页」，顶栏图标负责「快速看一眼 / 快速切一个开关」。</p>
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useTheme } from '@/shared/theme'
import { useLayoutStore } from '@/shared/store/layout'
import { TOPBAR_ENTRIES } from './navModel'
import { fetchProjects } from '@/shared/api'
import NotificationBell from '@/shared/components/console/NotificationBell.vue'
import AvatarMenu from '@/shared/components/console/AvatarMenu.vue'

const props = defineProps({
  /** 形态：project（项目空间）| global（画廊/设置等全局页） */
  mode: { type: String, default: 'project' },
  /** 项目 ID（项目模式） */
  projectId: { type: Number, default: null },
  /** 项目详情 */
  project: { type: Object, default: null },
  /** 面包屑文本（当前页名） */
  breadcrumb: { type: String, default: '' },
  /** 全局模式的品牌标签 */
  brandLabel: { type: String, default: '控制台' },
  /** 是否显示返回画廊按钮 */
  showBack: { type: Boolean, default: true },
  /** 是否显示侧栏开关 */
  showSidebarToggle: { type: Boolean, default: true },
  /** 侧栏是否处于折叠/窄栏形态（决定图标方向） */
  sidebarCollapsed: { type: Boolean, default: false }
})
const emit = defineEmits(['back', 'toggle-sidebar', 'open-command', 'open-drawer', 'open-help', 'switch-project'])

const router = useRouter()
const themeApi = useTheme()
const layout = useLayoutStore()

const isDark = computed(() => document.documentElement.hasAttribute('data-theme'))
const densityMeta = computed(() => layout.densityMeta)
/** AI 是否有进行中的推理（驱动图标上的活动点；快照由 AiRuntimePill 轮询写入） */
const aiBusy = computed(() => Number(layout.aiRuntime?.inFlight || 0) > 0)

/** 最近项目列表（项目切换器；失败静默，不阻塞顶栏） */
const projects = ref([])
onMounted(async () => {
  if (props.mode !== 'project') return
  try {
    const list = await fetchProjects()
    const arr = Array.isArray(list) ? list : (list?.records || list?.list || [])
    projects.value = arr.filter(p => p && p.deleted !== 1 && p.id !== props.projectId).slice(0, 10)
  } catch (_) { projects.value = [] }
})

/** 切换项目 / 前往画廊 */
function switchProject(id) {
  if (id === '__gallery__') { router.push('/'); return }
  if (!id || id === props.projectId) return
  emit('switch-project', id)
  router.push(`/project/${id}/sandbox`)
}

function toggleMode() { themeApi.toggleMode() }

/** 图标抽屉是否处于打开态（用于高亮） */
function isEntryOpen(e) {
  if (e.kind === 'panel' && e.target === 'command') return layout.commandOpen
  return layout.isDrawerVisible(e.target)
}

/** 图标点击：命令面板为独立浮层；其余（导航型 + 帮助 + AI 状态）都是可固定的抽屉 */
function onEntryClick(e) {
  if (e.kind === 'panel' && e.target === 'command') { layout.toggleCommand(); return }
  layout.toggleDrawer(e.target)
}
</script>

<style scoped>
.shell-topbar {
  height: var(--shell-topbar-h, 52px);
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: var(--space-3, 12px);
  padding: 0 var(--space-4, 16px);
  background: var(--bg-nav);
  color: var(--text-regular);
  border-bottom: 1px solid var(--border-l1);
  box-shadow: var(--shadow-lv1);
  z-index: var(--z-topbar, 50);
}

.tb-left { display: flex; align-items: center; gap: var(--space-2, 8px); min-width: 0; }
.tb-right { display: flex; align-items: center; gap: 2px; margin-left: auto; }
.tb-icons { display: flex; align-items: center; gap: 2px; }
.tb-center { flex: 1; display: flex; justify-content: center; min-width: 0; }

/* 图标按钮（顶栏统一控件） */
.tb-icon-btn {
  position: relative;
  width: 32px; height: 32px;
  display: inline-flex; align-items: center; justify-content: center;
  color: var(--text-secondary);
  background: transparent;
  border: 1px solid transparent;
  border-radius: var(--radius-sm, 8px);
  cursor: pointer;
  transition: all .15s var(--dsw-ease);
  flex-shrink: 0;
}
.tb-icon-btn:hover { background: var(--bg-hover); color: var(--text-primary); }
/* 打开态：主色浅底 + 主色图标（对齐酒馆「图标点亮」的反馈） */
.tb-icon-btn.is-open { background: var(--accent-soft); color: var(--accent-text); border-color: var(--accent-border); }
/* AI 活动点 */
.tb-dot {
  position: absolute; top: 4px; right: 4px;
  width: 6px; height: 6px; border-radius: 50%;
  background: var(--state-success);
  box-shadow: 0 0 0 2px var(--bg-nav);
}

/* 项目切换器 */
.tb-project {
  display: inline-flex; align-items: center; gap: 6px;
  max-width: 260px;
  padding: 5px 10px;
  background: var(--bg-layer-2);
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-sm, 8px);
  color: var(--text-primary);
  font-size: var(--density-font, 0.9rem);
  font-weight: 600;
  cursor: pointer;
  transition: all .15s var(--dsw-ease);
}
.tb-project:hover { border-color: var(--accent-border); background: var(--bg-layer-1); }
.tb-project-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* 品牌（全局模式） */
.tb-brand { display: flex; align-items: center; gap: 6px; padding-left: 4px; }
.tb-brand-name { font-weight: 700; color: var(--text-primary); }
.tb-brand-sep { color: var(--border-l3); }
.tb-brand-label { font-size: var(--density-font-sm, 0.82rem); color: var(--text-secondary); }

/* 面包屑 */
.tb-crumb { font-size: var(--density-font-sm, 0.82rem); color: var(--text-secondary); white-space: nowrap; }
.tb-crumb-sep { color: var(--border-l3); margin: 0 4px; }

/* 中部搜索（点击开命令面板：外观像输入框，实际是按钮，避免双输入源） */
.tb-search {
  display: flex; align-items: center; gap: var(--space-2, 8px);
  width: min(520px, 100%);
  padding: 6px 12px;
  background: var(--bg-layer-2);
  border: 1px solid var(--border-l1);
  border-radius: 999px;
  color: var(--text-placeholder);
  cursor: pointer;
  transition: all .15s var(--dsw-ease);
}
.tb-search:hover { border-color: var(--accent-border); background: var(--bg-layer-1); color: var(--text-secondary); }
.tb-search-ph { flex: 1; text-align: left; font-size: var(--density-font-sm, 0.82rem); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.tb-kbd {
  font-family: var(--dsw-font-family-code);
  font-size: 0.7rem;
  padding: 1px 6px;
  border: 1px solid var(--border-l2);
  border-bottom-width: 2px;
  border-radius: 4px;
  color: var(--text-tertiary);
}

.tb-divider { width: 1px; height: 20px; background: var(--border-l1); margin: 0 6px; }
.tb-bell { cursor: pointer; color: var(--text-regular); }
.tb-dd-title { font-size: 0.75rem; color: var(--text-tertiary); }
:deep(.el-dropdown-menu__item.is-current) { color: var(--accent-text); font-weight: 600; }
</style>
