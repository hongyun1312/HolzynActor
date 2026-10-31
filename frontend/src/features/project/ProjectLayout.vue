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
    V1.1.2-beta.1 M5：顶栏加「返回画廊」——单活动世界模型下退出世界 = 关闭（压盘）世界再回世界画廊，
    不能只是 router.back()（那会留下一个仍挂载的世界，占着世界库文件锁）。
  -->
  <AppShell
    mode="project"
    :project-id="projectId"
    :project="project"
    :breadcrumb="breadcrumb"
    :fullbleed="isFullbleed"
    :show-back="false"
  >
    <template #topbar-actions>
      <el-button size="small" plain :disabled="leaving" @click="onLeaveWorld">
        <el-icon><HIcon name="Back" /></el-icon>&nbsp;返回画廊
      </el-button>
    </template>
    <!-- 设置区不再替换域侧栏（V2.0 P1 曾用「设置轨道」顶替侧栏，观感上像进了另一套界面）：
         设置子页与其它项目页一致，仍显示域侧栏；子页间切换由设置页内部的页内导航标签（PageTabs）承担。 -->
    <router-view />

    <!-- 关闭世界时的遮罩：压盘（SHUTDOWN COMPACT）+ 镜像同步 + 摘要回写可能秒级，
         没有提示用户会以为"点了没反应"而重复点击。 -->
    <div v-if="leaving" class="world-leave-mask">
      <div class="world-leave-box">
        <el-icon class="is-loading" :size="20"><HIcon name="Loading" /></el-icon>
        <div class="world-leave-text">正在保存世界…</div>
        <div class="world-leave-sub">世界库压盘、镜像同步、摘要回写，完成后回到世界画廊</div>
      </div>
    </div>
  </AppShell>
</template>

<script setup>
/**
 * 项目空间布局（AppShell 的项目模式包装）。
 *
 * <p>职责边界（刻意收窄）：</p>
 * <ul>
 *   <li>加载项目详情 `GET /api/worlds/{id}`（供顶栏项目切换器与侧栏状态卡使用）；</li>
 *   <li>题材联动换肤：取 `genre` 调 `useTheme().setProjectContext`，离开项目时清理；</li>
 *   <li>进入项目即写「上次所在项目」（`setLastProject`，只写不清，供应用启动直达）；</li>
 *   <li>全屏页判定：世界沙盘需要全屏 3D 画布（`is-fullbleed`）。</li>
 * </ul>
 *
 * <p>不再负责：顶栏/侧栏/导航渲染、宽度与折叠状态、主题切换按钮——这些已下沉到
 * {@link AppShell} 与 layout store，由所有页面共享同一份实现。</p>
 *
 * <p>数据来源：`/api/worlds/{id}`、`/api/worlds/{id}/world-setting`。</p>
 *
 * <p><b>退出世界（M5）</b>：顶栏「返回画廊」不是普通返回，而是 {@code POST /api/worlds/{id}/close} +
 * 跳世界画廊。理由：单活动世界模型下，世界库文件被挂载进程独占；只做前端跳转会让世界一直挂着，
 * 世界画廊里那个世界会一直显示"运行中"，用户下次打开别处还会 409。关闭失败（如后端已自行关闭）时
 * 仍然回世界画廊——目的是"离开世界"，不是"必须关闭成功"。</p>
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { fetchProject, fetchWorldSetting, setLastProject } from '@/shared/api'
import { leaveToLibrary } from '@/shared/worldSession'
import { useTheme } from '@/shared/theme'
import AppShell from '@/shared/layout/AppShell.vue'
import HIcon from '@/shared/components/HIcon.vue'

const route = useRoute()
const router = useRouter()
const themeApi = useTheme()

const projectId = computed(() => Number(route.params.id))
const project = ref(null)
/** 正在离开世界（关闭+压盘）：顶栏按钮禁用 + 全屏遮罩 */
const leaving = ref(false)

/**
 * 返回世界画廊：先关闭（卸载+压盘）当前世界，再跳转。
 *
 * <p>顺序不能反：先跳转的话世界还挂着，`/worlds` 列表会显示它"运行中"，
 * 下次打开别处还会 409。关闭失败也照常跳（见 {@link leaveToLibrary}）。</p>
 */
async function onLeaveWorld() {
  if (leaving.value) return
  leaving.value = true
  try {
    const r = await leaveToLibrary(router)
    if (r?.error) {
      ElMessage.warning('关闭世界时出错，已返回世界画廊：' + (r.error?.message || '未知错误'))
    }
  } finally {
    // 组件即将卸载，leaving 不再复位；只保证异常路径不卡在遮罩上
    leaving.value = false
  }
}

/** 当前面包屑（由路由 meta 标题去掉 · HolzynActor 后缀） */
const breadcrumb = computed(() => (route.meta?.title || '').replace(' · HolzynActor', ''))

/**
 * 全屏内容区判定。
 * <p>原先硬编码 `route.name === 'project-sandbox'`（旧 3D 沙盘全屏画布，已于 P0-4 删除）；
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

<style scoped>
/* 项目外壳不再承载设置区导航：域侧栏常驻，设置子页导航由设置页内部 PageTabs 承担。 */

/* 返回画廊（关闭世界）遮罩：盖住整个应用，用 --z-mask 令牌（AGENTS §8.3 禁自定 z-index） */
.world-leave-mask {
  position: fixed;
  inset: 0;
  z-index: var(--z-mask);
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(8, 14, 22, 0.42);
  backdrop-filter: blur(2px);
}
.world-leave-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 22px 28px;
  border-radius: 12px;
  background: var(--el-bg-color-overlay);
  border: 1px solid var(--el-border-color-lighter);
  box-shadow: var(--el-box-shadow);
}
.world-leave-text {
  font-size: 14px;
  font-weight: 600;
}
.world-leave-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
