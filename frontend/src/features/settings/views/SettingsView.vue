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
    设置中心外壳（双模式）。
    关键点：设置子页导航**不再用左侧「设置轨道」**（V2.0 P1 曾用它顶替域侧栏，观感上像进了另一套界面），
    而是与「世界图谱」一致——在页面内用 PageTabs 页内主导航标签切换子页（域侧栏 → 页内标签 → 内容）。
    项目模式外壳由 ProjectLayout 提供（域侧栏常驻）；全局模式（首页 /settings/*）自带 AppShell。
  -->
  <AppShell
    v-if="!projectId"
    mode="global"
    brand-label="HolzynActor"
    :show-sidebar="false"
    :show-back="true"
  >
    <PageShell width="content" class="settings-page">
      <PageTabs
        :model-value="activeKey"
        position="top"
        @tab-change="onTabChange"
      >
        <el-tab-pane v-for="item in tabItems" :key="item.key" :label="item.name" :name="item.key" />
      </PageTabs>
      <!-- 各子页自带 PageHeader（一页一头），此处只挂路由出口 -->
      <router-view />
    </PageShell>
  </AppShell>

  <!-- 项目模式：外壳已由 ProjectLayout 提供，这里同样渲染页内标签 + 子页。
       宽度档位按场景区分：全局设置仍走 content（1200，表单与说明文更好读）；
       项目设置升 wide（1440）——它与其它项目页共用侧栏，1920 屏下留在 1200 会让切页左右跳 120px。 -->
  <PageShell v-else width="wide" class="settings-page">
    <PageTabs
      :model-value="activeKey"
      position="top"
      @tab-change="onTabChange"
    >
      <el-tab-pane v-for="item in tabItems" :key="item.key" :label="item.name" :name="item.key" />
    </PageTabs>
    <router-view />
  </PageShell>
</template>

<script setup>
/**
 * 设置页外壳。
 *
 * <p>职责：承载设置子页的路由出口，并用页内主导航标签（PageTabs）切换子页，
 * 与「世界图谱」等带切面的项目页同一套交互，不再自绘左侧「设置轨道」。
 * 项目 / 全局 / 对谈三种前缀下复用同一份子页清单（navModel.SETTINGS_SUB_ITEMS）。</p>
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppShell from '@/shared/layout/AppShell.vue'
import PageShell from '@/shared/layout/PageShell.vue'
import PageTabs from '@/shared/layout/PageTabs.vue'
import { SETTINGS_SUB_ITEMS } from '@/shared/layout/navModel'

const route = useRoute()
const router = useRouter()

/** 当前项目 ID：项目空间内存在；全局设置（首页）为 null */
const projectId = computed(() => (route.params.id ? Number(route.params.id) : null))

/** 是否处于「对谈」界面的设置子树（该子树没有 player-profile 子路由，标签需过滤） */
const isDialogueSettings = computed(() => route.path.includes('/dialogue/settings'))

/** 设置子页路径前缀（项目/对谈/全局三态） */
const base = computed(() => {
  if (!projectId.value) return '/settings'
  return isDialogueSettings.value
    ? `/world/${projectId.value}/dialogue/settings`
    : `/world/${projectId.value}/settings`
})

/** 设置子页标签清单（与 navModel 同源；对谈子树去掉 player-profile 避免死链） */
const tabItems = computed(() => SETTINGS_SUB_ITEMS
  .filter(i => !(isDialogueSettings.value && i.rel === 'player-profile'))
  .map(i => ({ ...i, path: `${base.value}/${i.rel}` })))

/** 当前激活子页 key（按路径匹配；未命中回退首项） */
const activeKey = computed(() => {
  const hit = tabItems.value.find(i => route.path === i.path || route.path.startsWith(i.path + '/'))
  return hit?.key || tabItems.value[0]?.key || ''
})

/** 标签点击 → 路由跳转（标签仅作导航，内容由 router-view 渲染） */
function onTabChange(key) {
  const item = tabItems.value.find(i => i.key === key)
  if (item && route.path !== item.path) router.push(item.path)
}
</script>

<style scoped>
/* 设置页：纵向节奏由 PageShell 承载；此处仅保留页面级兜底 */
.settings-page { display: flex; flex-direction: column; gap: var(--content-gap, 16px); }
</style>
