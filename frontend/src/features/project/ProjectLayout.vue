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
    项目空间外壳：只负责「项目数据 + 主题联动」，视觉与交互全部交给 AppShell。
    重构前本文件 362 行（顶栏+侧栏+导航+样式全内联，与 ConsoleTopbar / NewProjectView / SettingsView 三套并存）；
    现在只保留项目语义：加载项目详情、题材联动换肤、记录上次所在项目、全屏页判定。
  -->
  <AppShell
    mode="project"
    :project-id="projectId"
    :project="project"
    :breadcrumb="breadcrumb"
    :fullbleed="isFullbleed"
    :show-sidebar="!isRailSidebar"
  >
    <!-- V2.0（P1 设置收敛）：设置区自带导航轨道——
         侧栏不再逐个列设置子页（浮生 17→13 项、对谈 9→3 项），改由设置区提供完整子导航，
         并且两个界面共用同一份子页清单（navModel.SETTINGS_SUB_ITEMS），呈现方式一致。 -->
    <template v-if="isRailSidebar" #sidebar>
      <aside class="settings-rail">
        <SideNav
          :sections="settingsSections"
          :active-key="settingsActiveKey"
          @navigate="goSettings"
        />
        <div class="settings-rail-foot">
          <button type="button" class="settings-back" @click="backToInterface">
            ← 返回{{ interfaceLabel }}
          </button>
        </div>
      </aside>
    </template>
    <router-view />
  </AppShell>
</template>

<script setup>
/**
 * 项目空间布局（AppShell 的项目模式包装）。
 *
 * <p>职责边界（刻意收窄）：</p>
 * <ul>
 *   <li>加载项目详情 `GET /api/projects/{id}`（供顶栏项目切换器与侧栏状态卡使用）；</li>
 *   <li>题材联动换肤：取 `genre` 调 `useTheme().setProjectContext`，离开项目时清理；</li>
 *   <li>进入项目即写「上次所在项目」（`setLastProject`，只写不清，供应用启动直达）；</li>
 *   <li>全屏页判定：世界沙盘需要全屏 3D 画布（`is-fullbleed`）。</li>
 * </ul>
 *
 * <p>不再负责：顶栏/侧栏/导航渲染、宽度与折叠状态、主题切换按钮——这些已下沉到
 * {@link AppShell} 与 layout store，由所有页面共享同一份实现。</p>
 *
 * <p>数据来源：`/api/projects/{id}`、`/api/projects/{id}/world-setting`。</p>
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchProject, fetchWorldSetting, setLastProject } from '@/shared/api'
import { useTheme } from '@/shared/theme'
import AppShell from '@/shared/layout/AppShell.vue'
import SideNav from '@/shared/layout/SideNav.vue'
import { SETTINGS_SUB_ITEMS } from '@/shared/layout/navModel'

const route = useRoute()
const router = useRouter()
const themeApi = useTheme()

const projectId = computed(() => Number(route.params.id))
const project = ref(null)

/** 当前面包屑（由路由 meta 标题去掉 · HolzynActor 后缀） */
const breadcrumb = computed(() => (route.meta?.title || '').replace(' · HolzynActor', ''))

/**
 * 全屏内容区判定。
 * <p>原先硬编码 `route.name === 'project-sandbox'`（旧 3D 沙盘全屏画布，已于 P0-4 删除）；
 * M4 新首页改为三层壳内布局（非全屏），故此处只读路由 meta.fullbleed，
 * 使新增全屏页（如图谱全屏）无需改外壳代码。</p>
 */
const isFullbleed = computed(() => route.meta?.fullbleed === true)

// ===================== V2.0（P1）设置区导航轨道 =====================

/** 是否处于「设置区」（路由 meta.railSidebar）：此时隐藏域侧栏，改用设置区自己的轨道 */
const isRailSidebar = computed(() => route.meta?.railSidebar === true)

/** 当前是否在「对谈」界面的设置子树内（决定前缀与轨道内容） */
const isDialogueSettings = computed(() => route.path.includes('/dialogue/settings'))

/** 设置子页的路径前缀 */
const settingsBase = computed(() => isDialogueSettings.value
  ? `/project/${projectId.value}/dialogue/settings`
  : `/project/${projectId.value}/settings`)

/**
 * 设置区轨道分组。
 * <p>数据源是 `navModel.SETTINGS_SUB_ITEMS`（唯一事实来源），因此侧栏、轨道、命令面板永远同源。
 * 对谈界面下的设置子树没有 `player-profile` 子路由（它在对谈界面是独立置顶项），故此处过滤，
 * 避免出现点了 404 的死链。</p>
 */
const settingsSections = computed(() => [{
  key: 'settings',
  name: '设置',
  icon: 'ic_public_settings',
  items: SETTINGS_SUB_ITEMS
    .filter(i => !(isDialogueSettings.value && i.rel === 'player-profile'))
    .map(i => ({ ...i, path: `${settingsBase.value}/${i.rel}` }))
}])

/** 当前激活子页 key（按路径匹配；未命中回退首项） */
const settingsActiveKey = computed(() => {
  const sections = settingsSections.value[0]
  const hit = sections.items.find(i => route.path === i.path || route.path.startsWith(i.path + '/'))
  return hit?.key || sections.items[0]?.key || ''
})

/** 轨道点击 → 跳转（SideNav 只抛事件） */
function goSettings(item) {
  if (item?.path && route.path !== item.path) router.push(item.path)
}

/** 设置区返回当前界面落地页（避免用户进了设置"出不去"） */
const interfaceLabel = computed(() => (isDialogueSettings.value ? '对谈' : '世界地图'))
function backToInterface() {
  router.push(isDialogueSettings.value ? `/project/${projectId.value}/dialogue` : `/project/${projectId.value}/sandbox`)
}

/** 加载项目详情 + 世界题材（用于主题联动换肤） */
async function loadProject() {
  try { project.value = await fetchProject(projectId.value) } catch (_) { project.value = null }
  let genre = null
  try {
    const world = await fetchWorldSetting(projectId.value)
    genre = world?.genre || null
  } catch (_) { /* 无世界观时用默认主色 */ }
  themeApi.setProjectContext(projectId.value, genre)
}

// 项目切换：重载详情（导航路径由 computed 自动跟随）+ 记录上次所在项目
watch(projectId, (id) => {
  loadProject()
  setLastProject(id).catch(() => { /* 后端未就绪时静默，下次进入再写 */ })
})

onMounted(() => {
  loadProject()
  // 世界沙盘 S1：进入项目即记录「上次所在项目」（只写不清；供下次启动直达）
  setLastProject(projectId.value).catch(() => { /* 后端未就绪时静默，下次进入再写 */ })
})
onBeforeUnmount(() => themeApi.clearProjectContext())
</script>

<style scoped>
/* 设置区导航轨道：与域侧栏同款悬浮岛观感（V1.0 全局设置页已是此形态，此处用于项目内设置区） */
.settings-rail {
  width: 232px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
  margin: var(--shell-side-margin, 12px) 0 var(--shell-side-margin, 12px) var(--shell-side-margin, 12px);
  background: var(--bg-nav);
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-lg, 16px);
  box-shadow: var(--shadow-lv1);
}
.settings-rail-foot { padding: 10px 12px; border-top: 1px solid var(--border-l1); }
.settings-back {
  width: 100%;
  padding: 6px 8px;
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-sm, 8px);
  background: var(--bg-layer-2);
  color: var(--text-secondary);
  font-size: var(--density-font-sm, 0.82rem);
  cursor: pointer;
}
.settings-back:hover { border-color: var(--accent-border); color: var(--accent-text); }
</style>
