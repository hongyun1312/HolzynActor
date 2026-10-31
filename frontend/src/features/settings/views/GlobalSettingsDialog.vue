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
  <el-dialog v-model="visible" title="设置 · 全局（用户级）" width="900px" top="6vh"
    class="h-dialog settings-dialog" :close-on-click-modal="false" destroy-on-close @closed="$emit('closed')">
    <!-- 顶部说明（与其他弹窗一致的 el-alert 形态，h-dialog 规范统一间距） -->
    <el-alert type="info" :closable="false" show-icon class="h-dialog-alert"
      title="全局（用户级）设置，所有项目共享"
      description="此处配置用户级 API / Prompt 默认值，并查看全部项目的 AI 用量总览；项目专属设置（基本信息 / 世界时钟 / 导入导出 / 危险区）请进入对应项目空间 → 设置 中配置。" />

    <!-- 四个设置子页以 Tab 内嵌（与 /settings 全局设置页复用同一套组件，行为完全一致） -->
    <el-tabs v-model="activeTab" class="gs-tabs">
      <el-tab-pane label="通用设置" name="general" lazy>
        <ProjectGeneralSettings embedded />
      </el-tab-pane>
      <el-tab-pane label="API 配置" name="apis" lazy>
        <ModelApiSettings embedded />
      </el-tab-pane>
      <el-tab-pane label="Prompt 模板" name="prompts" lazy>
        <ProjectPromptSettings embedded />
      </el-tab-pane>
      <el-tab-pane label="AI 用量" name="usage" lazy>
        <ProjectUsageSettings embedded />
      </el-tab-pane>
    </el-tabs>

    <template #footer>
      <el-button type="primary" plain @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
/**
 * 全局（用户级）设置弹窗 —— 项目空间外所有「设置」入口的统一弹窗。
 * <p>背景：首页「设置」此前为「内嵌 ModelApiSettings 页 + 打开完整设置页路由跳转」，
 * 路由跳转在用户环境不可靠（次对接文档 §13/§14），且弹窗 UI 与其它弹窗不统一；本次重构为
 * 标准 el-dialog（标题 + 说明 + Tab 内容 + 底部按钮），与画廊导入/导出等弹窗形态一致。</p>
 * <p>职责：将全局设置四个子页（通用设置 / API 配置 / Prompt 模板 / AI 用量）以 Tab 形式内嵌，
 * 零路由依赖——通过 expose 的 open(tab) 打开；子页复用 /settings 全局设置页同一套组件
 * （各组件在非项目路由下 projectId 为空 = 用户级默认 / 全部项目总用量），保证行为一致。</p>
 * <p>使用：父组件持有本组件 ref，调用 ref.open('apis') 打开并定位到指定 Tab（默认 apis=API 配置）。</p>
 */
import { ref } from 'vue'
import ProjectGeneralSettings from './ProjectGeneralSettings.vue'
import ModelApiSettings from './ModelApiSettings.vue'
import ProjectPromptSettings from './ProjectPromptSettings.vue'
import ProjectUsageSettings from './ProjectUsageSettings.vue'

const visible = ref(false)

/**
 * 关闭事件：设置里可能刚接上模型，父页（如世界画廊的「还没接上模型」横幅）
 * 需要据此复查配置状态，不必等用户重进页面。
 */
defineEmits(['closed'])

/** 设置弹窗的合法 Tab（顺序即 Tab 顺序，第一项 = 打开时默认页） */
const TABS = ['general', 'apis', 'prompts', 'usage']

/** 当前激活 Tab：general / apis / prompts / usage。默认第一项（通用设置） */
const activeTab = ref('general')

/**
 * 打开设置弹窗。
 *
 * <p>为什么要白名单校验：模板里常写成 {@code @click="open"}，Vue 会把 MouseEvent 当第一个参数传进来，
 * {@code activeTab} 被赋成事件对象后没有任何 el-tab-pane 匹配，弹窗就整片空白（用户反馈的"打开是空的"）。
 * 非字符串/非法值一律回落到第一个 Tab。</p>
 *
 * @param {string} tab 初始 Tab（general/apis/prompts/usage），默认 general（第一个）
 */
function open(tab = 'general') {
  activeTab.value = TABS.includes(tab) ? tab : 'general'
  visible.value = true
}

defineExpose({ open })
</script>

<style scoped>
/* Tab 头与内容间距统一（与新增项目弹窗 np-tabs 同款令牌） */
.gs-tabs { --el-tabs-header-margin-bottom: 16px; }
</style>

<!-- 弹窗体：h-dialog 规范已统一头部/内容内边距/80vh 内部滚动/页脚（global.css 非 scoped 规则）；
     保留 gs-alert 与滚动声明前的兼容类名不再需要，滚动能力由 .h-dialog 提供 -->
