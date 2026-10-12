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
  <!-- 统一外壳（global 模式）：顶栏与侧栏均来自 AppShell，本页不再自绘。
       侧栏为「创建方式」本地导航（非路由驱动），由 #sidebar 插槽注入 SideNav 表现层组件。 -->
  <AppShell
    mode="global"
    brand-label="新增项目"
    :breadcrumb="breadcrumb"
    :show-sidebar="false"
    :show-back="true"
  >
    <template #topbar-actions>
      <!-- 项目页「设置」入口：统一 SettingsButton → 打开全局设置弹窗（零路由依赖） -->
      <SettingsButton @click="openSettingsDialog" />
    </template>

    <template #sidebar>
      <aside class="np-rail">
        <SideNav
          :sections="[{ key: 'create', name: '创建方式', icon: 'ic_public_add', items: navItems }]"
          :active-key="active"
          @navigate="item => (active = item.key)"
        />
        <div class="np-rail-foot">
          <el-tag size="small" type="info" effect="plain">创建方式</el-tag>
        </div>
      </aside>
    </template>

    <div class="np-content">
      <ManualAddPanel v-if="active === 'manual'" />
      <WorldgenPanel v-else-if="active === 'worldgen'" @open-settings="openSettingsDialog" />
      <FileParsePanel v-else-if="active === 'file'" @open-settings="openSettingsDialog" />
      <ProjectImportPanel v-else />
    </div>

    <!-- 全局设置弹窗（项目空间外「设置」统一弹窗，零路由依赖） -->
    <GlobalSettingsDialog ref="settingsRef" />
  </AppShell>
</template>

<script setup>
/**
 * 项目页（重构：UI 改为与项目空间布局相同； 增「创意发散生成」入口）。
 * <p>职责：新建项目的独立页面（不在项目空间内），不再分为平铺的三张方式卡片，
 * 而是采用与项目空间一致的「顶栏 + 悬浮岛式侧边栏 + 主内容区」布局：
 * 侧边导航栏含「手动添加 / 创意发散生成 / 文件解析 / 项目导入」四个入口；内容区对应四个面板：
 * ① 手动添加：一次性填写项目信息 + 完整世界观设定（非必填，布局参考世界详情页左侧 Tab）；
 * ② 创意发散生成：输入题材创意 → 自动建项目 → 6 维链式 AI 生成世界观 → 预览确认落库（任务控制台）；
 * ③ 文件解析：选择文件 + 解析按钮 + 文件信息（文件名/格式/大小）+ 控制台日志区（SSE 实时输出后端解析日志）；
 * ④ 项目导入：导入 .holzyn 项目包（布局适配）。</p>
 * <p>手动添加 / 文件解析 / 创意发散生成完成后均跳转对应项目空间页面（详情/初始化）。</p>
 */
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import AppShell from '@/shared/layout/AppShell.vue'
import SideNav from '@/shared/layout/SideNav.vue'
import SettingsButton from '@/shared/components/SettingsButton.vue'
import GlobalSettingsDialog from '@/features/settings/views/GlobalSettingsDialog.vue'
import ManualAddPanel from '../components/ManualAddPanel.vue'
import WorldgenPanel from '../components/WorldgenPanel.vue'
import FileParsePanel from '../components/FileParsePanel.vue'
import ProjectImportPanel from '../components/ProjectImportPanel.vue'

const route = useRoute()
const active = ref('manual')

/** 侧边导航项（手动添加 / 创意发散生成 / 文件解析 / 项目导入）——交给 SideNav 表现层渲染 */
const navItems = [
  { key: 'manual', name: '手动添加', icon: 'ic_public_edit', desc: '项目 + 世界观' },
  { key: 'worldgen', name: '创意发散生成', icon: 'ic_public_highlight', desc: 'AI 生成世界观' },
  { key: 'file', name: '文件解析', icon: 'ic_public_upload_filled', desc: '上传文件 AI 解析' },
  { key: 'holzyn', name: '项目导入', icon: 'ic_public_download', desc: '.holzyn 包' }
]

/** 当前面包屑（由路由 meta 标题去掉 · HolzynActor 后缀） */
const breadcrumb = computed(() => {
  const t = route.meta?.title || ''
  return t.replace(' · HolzynActor', '')
})

// ===== 全局设置弹窗（项目空间外「设置」统一弹窗入口，零路由依赖） =====
const settingsRef = ref(null)

/** 打开全局设置弹窗（可指定初始 Tab，默认 apis=API 配置） */
function openSettingsDialog(tab = 'apis') {
  settingsRef.value?.open(tab)
}
</script>

<style scoped>
/* 创建方式导航轨道：与域侧栏同款悬浮岛观感（顶栏与内容区滚动均由 AppShell 提供） */
.np-rail {
  width: 236px;
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
.np-rail-foot { padding: 10px 12px; border-top: 1px solid var(--border-l1); }

/* 主内容区：壳内已提供滚动与内边距，这里只做限宽 */
.np-content { width: 100%; max-width: var(--content-max-w, 1440px); margin: 0 auto; }
</style>
