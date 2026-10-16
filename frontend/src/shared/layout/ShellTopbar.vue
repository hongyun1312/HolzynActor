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

      <!-- 界面切换（浮生 ⇄ 对谈）：两个完全独立的界面，各自拥有独立的侧边栏。
           按钮内显示当前界面功能名（浮生 / 对谈），点击即切换到另一界面。 -->
      <button
        v-if="mode === 'project'"
        class="tb-interface"
        data-tour="interface-switch"
        :title="`当前界面：${currentInterface.name}（${currentInterface.desc}）· 点击切换到「${otherInterface.name}」`"
        @click="$emit('switch-interface')"
      >
        <el-icon :size="15"><HIcon :name="currentInterface.icon" /></el-icon>
        <span class="tb-iface-name">{{ currentInterface.name }}</span>
        <el-icon :size="12" class="tb-iface-swap"><HIcon name="Refresh" /></el-icon>
      </button>

      <!-- 当前副标题：落地页显示界面说明，其余显示当前页名 -->
      <span v-if="interfaceSubtitle" class="tb-crumb">
        <span class="tb-crumb-sep">/</span>{{ interfaceSubtitle }}
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

      <!-- ===== V2.0 顶栏右侧：3 个分组（常驻无标签图标 10 → 5）=====
           组1 定位：面板下拉（合并原「角色 / 记忆与知识 / 世界设定」三个与侧栏同源的域抽屉）
           组2 运行观测：AI 运行状态 · 通知
           组3 显示与帮助：信息密度 · 深浅主题 · 帮助
           设计依据：PatternFly「工具条内完整展示的动作 ≤3，其余进溢出菜单」+ Apple HIG「分组 ≤3」；
           硬约束：禁止折行（空间不足时由分组与下拉消化，不换行）、每个图标必须有 tooltip、同一功能唯一入口。 -->
      <div class="tb-icons" data-tour="topbar-icons">
        <el-dropdown trigger="click" placement="bottom-end" @command="onPanelCommand">
          <button
            class="tb-icon-btn"
            :class="{ 'is-open': panelsOpen }"
            :title="panelsEntry ? panelsEntry.hint : '面板'"
          >
            <el-icon :size="17"><HIcon :name="panelsEntry ? panelsEntry.icon : 'ic_public_view_list'" /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu class="tb-panel-menu">
              <el-dropdown-item v-for="p in drawerPanels" :key="p.id" :command="p.id">
                <el-icon :size="14"><HIcon :name="p.icon" /></el-icon>
                <span class="tb-panel-name">{{ p.title }}</span>
                <span class="tb-panel-hint">{{ p.hint }}</span>
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>

      <span class="tb-divider"></span>

      <!-- 组2：运行观测 -->
      <div class="tb-icons">
        <button
          v-if="aiEntry"
          class="tb-icon-btn"
          :class="{ 'is-open': isEntryOpen(aiEntry) }"
          :title="`${aiEntry.name}${aiEntry.hint ? ' · ' + aiEntry.hint : ''}`"
          @click="onEntryClick(aiEntry)"
        >
          <el-icon :size="17"><HIcon :name="aiEntry.icon" /></el-icon>
          <!-- AI 运行状态：胶囊上的活动点（有推理中任务时点亮） -->
          <span v-if="aiBusy" class="tb-dot"></span>
        </button>
        <NotificationBell class="tb-bell" />
      </div>

      <span class="tb-divider"></span>

      <!-- 组3：显示偏好（信息密度 / 深浅主题 / 内容宽度三合一）+ 帮助
           V2.0 批次二：此前密度与主题是两个常驻图标，加上「内容宽度」实际是 3 个同族开关
           占 3 个槽位（顶栏常驻图标 7 个，超出 §10-9 的 ≤6 预算）。三者同属「显示偏好」，
           合并为 1 个弹层入口后降到 6，能力一个不少（形态与位置不变）。 -->
      <div class="tb-icons" data-tour="display-prefs">
        <el-dropdown trigger="click" placement="bottom-end" @command="onDisplayCommand" @visible-change="(v) => (displayOpen = v)">
          <button
            class="tb-icon-btn"
            :class="{ 'is-open': displayOpen }"
            :title="`显示偏好：密度 ${densityMeta.name} · ${themeModeName} · ${contentModeName}（点击调整）`"
          >
            <el-icon :size="17"><HIcon name="Setting" /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu class="tb-display-menu">
              <el-dropdown-item :disabled="true">
                <span class="tb-dd-title">信息密度</span>
              </el-dropdown-item>
              <el-dropdown-item
                v-for="d in DENSITY_MODES"
                :key="d.key"
                class="tb-dm-item"
                :command="`density:${d.key}`"
                :class="{ 'is-current': layout.density === d.key }"
              >
                <span class="tb-dm-name">{{ d.name }}</span>
                <span class="tb-dm-hint">{{ d.desc }}</span>
              </el-dropdown-item>
              <el-dropdown-item divided :disabled="true">
                <span class="tb-dd-title">外观主题</span>
              </el-dropdown-item>
              <el-dropdown-item
                v-for="t in THEME_MODES"
                :key="t.key"
                class="tb-dm-item"
                :command="`theme:${t.key}`"
                :class="{ 'is-current': currentThemeMode === t.key }"
              >
                <span class="tb-dm-name">{{ t.name }}</span>
                <span class="tb-dm-hint">{{ t.desc }}</span>
              </el-dropdown-item>
              <el-dropdown-item divided :disabled="true">
                <span class="tb-dd-title">内容宽度</span>
              </el-dropdown-item>
              <el-dropdown-item
                v-for="c in CONTENT_MODES"
                :key="c.key"
                class="tb-dm-item"
                :command="`content:${c.key}`"
                :class="{ 'is-current': layout.contentMode === c.key }"
              >
                <span class="tb-dm-name">{{ c.name }}</span>
                <span class="tb-dm-hint">{{ c.desc }}</span>
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
        <button
          v-if="helpEntry"
          class="tb-icon-btn"
          :class="{ 'is-open': isEntryOpen(helpEntry) }"
          :title="`${helpEntry.name}（${helpEntry.hotkey}）`"
          data-tour="help"
          @click="onEntryClick(helpEntry)"
        >
          <el-icon :size="17"><HIcon :name="helpEntry.icon" /></el-icon>
        </button>
      </div>
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
import { useLayoutStore, DENSITY_MODES, CONTENT_MODES } from '@/shared/store/layout'
import { TOPBAR_ENTRIES, DRAWER_PANELS, interfaceInfo } from './navModel'
import { fetchProjects } from '@/shared/api'
import NotificationBell from '@/shared/components/console/NotificationBell.vue'
import AvatarMenu from '@/shared/components/console/AvatarMenu.vue'

/** 深浅模式三档（与 theme 模块的 mode 取值一一对应；不额外持久化，由 theme 模块负责） */
const THEME_MODES = [
  { key: 'system', name: '跟随系统', desc: '与操作系统的深浅设置同步' },
  { key: 'light', name: '浅色', desc: '始终使用浅色界面' },
  { key: 'dark', name: '深色', desc: '始终使用深色界面' }
]

const props = defineProps({
  /** 形态：project（项目空间）| global（画廊/设置等全局页） */
  mode: { type: String, default: 'project' },
  /** 项目 ID（项目模式） */
  projectId: { type: Number, default: null },
  /** 项目详情 */
  project: { type: Object, default: null },
  /** 面包屑文本（当前页名） */
  breadcrumb: { type: String, default: '' },
  /** 当前界面（浮生 / 对谈）：驱动左上角界面切换按钮与副标题 */
  interfaceKey: { type: String, default: 'fusheng' },
  /** 全局模式的品牌标签 */
  brandLabel: { type: String, default: '控制台' },
  /** 是否显示返回画廊按钮 */
  showBack: { type: Boolean, default: true },
  /** 是否显示侧栏开关 */
  showSidebarToggle: { type: Boolean, default: true },
  /** 侧栏是否处于折叠/窄栏形态（决定图标方向） */
  sidebarCollapsed: { type: Boolean, default: false }
})
const emit = defineEmits(['back', 'toggle-sidebar', 'open-command', 'open-drawer', 'open-help', 'switch-project', 'switch-interface'])

const router = useRouter()
const themeApi = useTheme()
const layout = useLayoutStore()

const densityMeta = computed(() => layout.densityMeta)
/** 当前深浅模式 key（system | light | dark），用于「显示偏好」弹层里的选中态 */
const currentThemeMode = computed(() => themeApi.theme.value.mode || 'system')
/** 当前主题模式中文名（写进 tooltip，用户不必展开弹层就知道当前档位） */
const themeModeName = computed(() => (THEME_MODES.find(t => t.key === currentThemeMode.value) || THEME_MODES[0]).name)
/** 当前内容宽度模式中文名 */
const contentModeName = computed(() => (CONTENT_MODES.find(c => c.key === layout.contentMode) || CONTENT_MODES[0]).name)
/** 「显示偏好」弹层是否展开（仅用于图标点亮反馈） */
const displayOpen = ref(false)
/** AI 是否有进行中的推理（驱动图标上的活动点；快照由 AiRuntimePill 轮询写入） */
const aiBusy = computed(() => Number(layout.aiRuntime?.inFlight || 0) > 0)

/** 当前界面元信息（浮生 / 对谈）与「另一个界面」（切换目标） */
const currentInterface = computed(() => interfaceInfo(props.interfaceKey))
const otherInterface = computed(() => interfaceInfo(props.interfaceKey === 'dialogue' ? 'fusheng' : 'dialogue'))

/**
 * 当前副标题：落地页（面包屑 = 界面名）时显示界面说明，其余页面显示当前页名。
 * <p>对齐交互约定「切换按钮右侧标注当前副标题」——用户在任意界面都知道自己身在何处。</p>
 */
const interfaceSubtitle = computed(() => {
  if (!props.breadcrumb) return ''
  return props.breadcrumb === currentInterface.value.name ? currentInterface.value.desc : props.breadcrumb
})

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

/**
 * 「显示偏好」弹层命令：`density:<key>` / `theme:<key>` / `content:<key>`。
 * <p>三个开关同源同族，故共用一个入口；命令前缀保证将来加档位不必再改顶栏结构。</p>
 */
function onDisplayCommand(cmd) {
  const [kind, key] = String(cmd || '').split(':')
  if (!key) return
  if (kind === 'density') layout.setDensity(key)
  else if (kind === 'theme') themeApi.setMode(key)
  else if (kind === 'content') layout.setContentMode(key)
}

/** 顶栏条目按 key 取用（V2.0：入口清单已收敛为 3 条 + 显示偏好弹层内的 3 个开关） */
const entryOf = (key) => TOPBAR_ENTRIES.find(e => e.key === key) || null
const panelsEntry = entryOf('panels')
const aiEntry = entryOf('ai-runtime')
const helpEntry = entryOf('help')

/** 面板下拉的数据源（与 AppShell 抽屉共用同一份元数据，避免两处漂移） */
const drawerPanels = DRAWER_PANELS

/** 「面板」图标是否需要点亮：任一面板抽屉处于可见态 */
const panelsOpen = computed(() => DRAWER_PANELS.some(p => layout.isDrawerVisible(p.id)))

/** 面板下拉选择 → 开合对应抽屉（点已开的面板即关闭，符合开关直觉） */
function onPanelCommand(id) { layout.toggleDrawer(id) }

/** 图标抽屉是否处于打开态（用于高亮） */
function isEntryOpen(e) {
  if (!e) return false
  if (e.kind === 'panel' && e.target === 'command') return layout.commandOpen
  return layout.isDrawerVisible(e.target)
}

/** 图标点击：命令面板为独立浮层；其余（导航型 + 帮助 + AI 状态）都是可固定的抽屉 */
function onEntryClick(e) {
  if (!e) return
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

/* 界面切换按钮（浮生 ⇄ 对谈）：胶囊形态，突出「两个独立界面」的顶层心智 */
.tb-interface {
  display: inline-flex; align-items: center; gap: 6px;
  flex-shrink: 0;
  padding: 5px 10px;
  background: var(--accent-soft);
  border: 1px solid var(--accent-border);
  border-radius: 999px;
  color: var(--accent-text);
  font-size: var(--density-font, 0.9rem);
  font-weight: 600;
  cursor: pointer;
  transition: all .15s var(--dsw-ease);
}
.tb-interface:hover { background: var(--accent); color: var(--accent-soft-contrast, #fff); box-shadow: var(--shadow-lv1); }
.tb-iface-name { white-space: nowrap; }
.tb-iface-swap { opacity: .6; transition: transform .25s var(--dsw-ease); }
.tb-interface:hover .tb-iface-swap { opacity: 1; transform: rotate(180deg); }

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

/* V2.0 面板下拉：名称 + 弱化提示（让用户知道每个面板里有什么，而不是只看到 4 个抽象名词） */
.tb-panel-name { margin-left: 6px; }
.tb-panel-hint { margin-left: 10px; font-size: 0.72rem; color: var(--text-tertiary); }

/* V2.0 显示偏好弹层：同族开关合并后，弹层内用「名称 + 说明」区分档位，当前档位高亮。
   弹层被 teleport 到 body，但元素仍带 scoped 属性，故类选择器规则照常生效；
   说明文字较长，必须允许换行（Element Plus 默认 nowrap 会把弹层撑得很宽）。 */
.tb-dm-item { display: flex; align-items: baseline; white-space: normal; line-height: 1.5; padding-top: 6px; padding-bottom: 6px; }
.tb-dm-name { margin-left: 6px; flex-shrink: 0; }
.tb-dm-hint { margin-left: 10px; font-size: 0.72rem; color: var(--text-tertiary); }

/* ============================================================
   P1-3 顶栏响应式：此前无任何断点 —— 900px 视口下中部的 520px 搜索框
   与右侧图标组互相压叠（实测截图可见 Ctrl K 徽标压在图标上）。
   策略：窄屏优先保住「左侧上下文 + 右侧功能入口」，搜索框逐级让位。
   V2.0 批次二：断点值收敛到全站三档（1024 / 1440 / 1920）——
   原先的 1279 / 1023 两个野值分别并入 1440 与 1024（§5.1）。
   ============================================================ */
@media (max-width: 1440px) {
  /* 只收窄，不隐藏提示文字：搜索框的占位文案是「搜索能搜到什么」的唯一说明，
     隐藏后用户只看到一个放大镜，实测 1440 档观感明显变差（批次二截图核对发现）。 */
  .tb-search { width: min(300px, 100%); }
}
@media (max-width: 1024px) {
  .tb-center { flex: 0 0 auto; }
  .tb-search { width: 32px; height: 32px; justify-content: center; padding: 0; border-radius: var(--radius-sm, 8px); overflow: hidden; }
  .tb-search-ph { display: none; }
  .tb-kbd { display: none; }
  .tb-crumb { display: none; }
  .tb-project { max-width: 132px; }
}
</style>
