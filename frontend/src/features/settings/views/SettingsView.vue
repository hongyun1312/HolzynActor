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
    关键重构点：项目模式**不再自绘顶部栏与左侧子导航**——外壳由 ProjectLayout/AppShell 提供，
    设置子页作为「设置域」直接出现在域侧栏里（与其它域同一套导航），因此不再有「设置页里还有一层侧栏」的嵌套观感。
    全局模式（首页 /settings/*）自身就是顶层路由，故自带 AppShell。
  -->
  <AppShell
    v-if="!projectId"
    mode="global"
    brand-label="HolzynActor"
    :show-sidebar="false"
    :show-back="true"
  >
    <template #sidebar>
      <aside class="settings-rail">
        <SideNav :sections="globalSections" :active-key="activeKey" @navigate="go" />
      </aside>
    </template>
    <div class="content-col">
      <!-- 不再自绘「设置」页头：域侧栏已标明当前位置，页头由各子页自己提供（一页一头）。
           全局/项目差异由子页自身的说明承担，避免出现「设置 + 子页标题」双层页头。 -->
      <router-view />
    </div>
  </AppShell>

  <!-- 项目模式：外壳已由 ProjectLayout 提供（设置区自带导航轨道），这里只渲染子页。
       V2.0：统一走 PageShell width="content"（1200），替换原 .content-col（1440）——
       设置类页面是"正文/表单"性质，1200 的阅读列与其它正文页一致。 -->
  <PageShell v-else width="content">
    <router-view />
  </PageShell>
</template>

<script setup>
/**
 * 设置页外壳。
 *
 * <p>职责：承载四个（现为五个）设置子页的路由出口，并按模式决定是否自带外壳：</p>
 * <ul>
 *   <li>**项目模式**（`/project/:id/settings/*`）：外壳来自 ProjectLayout，子导航来自域侧栏的「设置」域；</li>
 *   <li>**全局模式**（`/settings/*`）：自带 AppShell（全局品牌 + 左侧设置导航轨道）。</li>
 * </ul>
 *
 * <p>重构前后差异：原实现在项目模式下也渲染 ConsoleTopbar 与一层 180px 的左侧子导航，
 * 形成「侧栏里套侧栏」的双层导航；现统一到骨架，导航只有一处。</p>
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppShell from '@/shared/layout/AppShell.vue'
import SideNav from '@/shared/layout/SideNav.vue'
import PageShell from '@/shared/layout/PageShell.vue'

const route = useRoute()
const router = useRouter()

/** 当前项目 ID：项目空间内存在；全局设置（首页）为 null */
const projectId = computed(() => (route.params.id ? Number(route.params.id) : null))

/** 设置子页清单（项目/全局共用同一份定义，路径前缀不同） */
const base = computed(() => (projectId.value ? `/project/${projectId.value}/settings` : '/settings'))
const navItems = computed(() => [
  { key: 'general', name: '通用设置', icon: 'ic_public_settings', desc: '账户、主题外观、世界时钟、导入导出', path: `${base.value}/general` },
  { key: 'apis', name: 'API 配置', icon: 'ic_public_connection', desc: '模型供应商与密钥', path: `${base.value}/apis` },
  { key: 'prompts', name: 'Prompt 模板', icon: 'ic_public_edit', desc: '场景模板覆盖与版本', path: `${base.value}/prompts` },
  { key: 'usage', name: 'AI 用量', icon: 'ic_public_view_list', desc: 'Token 与场景用量统计', path: `${base.value}/usage` },
  { key: 'appearance', name: '界面与布局', icon: 'ic_public_highlight', desc: '侧栏宽度、密度、面板固定与导览', path: `${base.value}/appearance` },
  { key: 'assistant', name: '助手设置', icon: 'ic_public_message', desc: '项目级 AI 助手：说明、docs 索引与工具', path: `${base.value}/assistant` },
  { key: 'player-profile', name: '玩家档案', icon: 'ic_public_contacts', desc: '玩家 persona 与角色关系设定', path: `${base.value}/player-profile` },
  { key: 'update', name: '更新与版本', icon: 'ic_public_download', desc: '检查更新、下载与数据备份回滚', path: `${base.value}/update` }
])

/** 全局模式的单分组导航（SideNav 需要 sections 结构） */
const globalSections = computed(() => [{ key: 'settings', name: '设置', icon: 'ic_public_settings', items: navItems.value }])

/** 当前激活子页 key（供 SideNav 高亮） */
const activeKey = computed(() => navItems.value.find(i => isActive(i.path))?.key || 'general')

/** 子导航激活判断 */
function isActive(path) {
  return route.path === path || route.path.startsWith(path + '/')
}

/** SideNav 点击 → 路由跳转（SideNav 只抛事件，保持表现层纯净） */
function go(item) {
  if (item?.path) router.push(item.path)
}
</script>

<style scoped>
/* 全局模式左侧设置导航轨道：与域侧栏同款悬浮岛观感，宽度略窄 */
.settings-rail {
  width: 224px;
  flex-shrink: 0;
  margin: var(--shell-side-margin, 12px) 0 var(--shell-side-margin, 12px) var(--shell-side-margin, 12px);
  background: var(--bg-nav);
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-lg, 16px);
  box-shadow: var(--shadow-lv1);
  display: flex;
  flex-direction: column;
  min-height: 0;
}
</style>
