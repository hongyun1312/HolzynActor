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
  >
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
import { useRoute } from 'vue-router'
import { fetchProject, fetchWorldSetting, setLastProject } from '@/shared/api'
import { useTheme } from '@/shared/theme'
import AppShell from '@/shared/layout/AppShell.vue'

const route = useRoute()
const themeApi = useTheme()

const projectId = computed(() => Number(route.params.id))
const project = ref(null)

/** 当前面包屑（由路由 meta 标题去掉 · HolzynActor 后缀） */
const breadcrumb = computed(() => (route.meta?.title || '').replace(' · HolzynActor', ''))

/**
 * 全屏内容区判定。
 * <p>原先硬编码 `route.name === 'project-sandbox'`（旧 3D 沙盘全屏画布）；
 * M4 新首页改为三层壳内布局（非全屏），故此处只读路由 meta.fullbleed，
 * 使新增全屏页（如图谱全屏）无需改外壳代码。</p>
 */
const isFullbleed = computed(() => route.meta?.fullbleed === true)

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
