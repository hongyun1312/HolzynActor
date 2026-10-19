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

  新增项目弹窗 —— 项目画廊三个入口弹窗之一（与「导入项目 / 设置」共用 h-dialog 弹窗规范）。
  <p>背景：画廊「新增项目」此前跳转 /project/new 独立页面（顶栏 + 侧边导航 + 四面板），
  与画廊的「导入项目 / 设置」两个弹窗形态割裂。本次统一为弹窗：宽度 760px（中档），
  顶部标题 + 说明 alert + Tab 组织四种创建方式（手动添加 / 创意发散生成 / 文件解析 / 项目导入），
  页脚「关闭」；四种创建方式复用原面板组件（各自内部自带操作与跳转）。</p>
  <p>零路由依赖：通过 expose 的 open() 打开；面板内「去配置 API」等 open-settings 事件
  由本弹窗内嵌的全局设置弹窗承接。</p>
-->
<template>
  <el-dialog v-model="visible" title="新增项目" width="760px" top="6vh"
    class="h-dialog new-project-dialog" :close-on-click-modal="false" destroy-on-close>
    <!-- 顶部说明：与其他弹窗一致的 el-alert 形态（h-dialog 规范统一间距） -->
    <el-alert class="h-dialog-alert" type="info" :closable="false" show-icon
      title="选择一种创建方式：手动填写 / AI 发散生成 / 文件解析 / 导入 .holzyn 包"
      description="创建完成后可直接进入对应项目空间（世界沙盘 / 世界详情 / 初始化页），并可选择是否进行默认世界初始化。" />

    <!-- 四种创建方式以 Tab 内嵌（复用 /project/new 页面同一套面板组件） -->
    <el-tabs v-model="activeTab" class="np-tabs">
      <el-tab-pane label="手动添加" name="manual" lazy>
        <ManualAddPanel />
      </el-tab-pane>
      <el-tab-pane label="创意发散生成" name="worldgen" lazy>
        <WorldgenPanel @open-settings="openSettings" />
      </el-tab-pane>
      <el-tab-pane label="文件解析" name="file" lazy>
        <FileParsePanel @open-settings="openSettings" />
      </el-tab-pane>
      <el-tab-pane label="项目导入" name="holzyn" lazy>
        <ProjectImportPanel />
      </el-tab-pane>
    </el-tabs>

    <template #footer>
      <el-button type="primary" plain @click="visible = false">关闭</el-button>
    </template>

    <!-- 面板内「去配置 API」等入口 → 本弹窗内嵌的全局设置弹窗（零路由依赖） -->
    <GlobalSettingsDialog ref="settingsRef" />
  </el-dialog>
</template>

<script setup>
/**
 * 新增项目弹窗。
 * <p>与画廊「导入项目」「设置」两个弹窗统一 h-dialog 规范：标题 + 说明 alert + 内容 + 页脚关闭；
 * 宽度 760px（中档）。四种创建方式（手动 / 发散 / 文件 / 导入）复用原新建项目页的面板组件，
 * 行为与页面版完全一致（成功后各自跳转对应项目空间页面）。</p>
 * <p>使用：父组件持有本组件 ref，调用 ref.open() 打开。</p>
 */
import { ref } from 'vue'
import GlobalSettingsDialog from '@/features/settings/views/GlobalSettingsDialog.vue'
import ManualAddPanel from './ManualAddPanel.vue'
import WorldgenPanel from './WorldgenPanel.vue'
import FileParsePanel from './FileParsePanel.vue'
import ProjectImportPanel from './ProjectImportPanel.vue'

const visible = ref(false)
/** 当前激活 Tab：manual / worldgen / file / holzyn */
const activeTab = ref('manual')

/** 打开新增项目弹窗（默认定位「手动添加」） */
function open() {
  visible.value = true
}

/** 面板内「去配置 API」→ 打开本弹窗内嵌的全局设置弹窗并定位「API 配置」Tab */
function openSettings(tab = 'apis') {
  settingsRef.value?.open(tab)
}

const settingsRef = ref(null)

defineExpose({ open })
</script>

<style scoped>
/* Tab 头与内容间距统一（与设置弹窗 gs-tabs 同款令牌）；面板根自带 max-width 限宽，在 760px 弹窗内自适应 */
.np-tabs { --el-tabs-header-margin-bottom: 14px; }
</style>

<!-- 弹窗体：h-dialog 规范已统一 body 内边距/滚动/页脚（global.css 非 scoped 规则）；
     本弹窗内容（SSE 控制台等）较多，沿用 80vh 内部滚动即可，无需额外规则 -->
