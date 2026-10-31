<!--
  SPDX-License-Identifier: AGPL-3.0-or-later
  Copyright (C) 2026 HolzynActor

  PageTabs：页内主导航标签（界面布局重构 V1.0 批次②）。

  定位（三层导航的中间层）：域侧栏 → **页内主导航标签** → 面板内标签。
  与普通 `el-tabs` 的区别：
   - 页内主导航用本组件（视觉更重：更大间距、选中态更强、左置时固定导航列宽）；
   - 面板内部（角色详情信息栏、对话信息栏、设置对话框）继续直接用 `el-tabs`，不套本组件。

  统一掉的历史不一致：`WorldDetailView` / `WorldEditView` / `WorldSettingForm`（原 `ManualAddPanel`）三处左置标签
  各自复制了同一段 `.world-tabs :deep(...)` 样式（128px 导航列宽 + 去掉右边距 + 内容区不裁切），
  现由本组件 + 全局 `.page-tabs` 样式统一提供。

  用法：
    <PageTabs v-model="activeSection" position="left">
      <el-tab-pane label="基本信息" name="basic">…</el-tab-pane>
    </PageTabs>
-->
<template>
  <el-tabs
    class="page-tabs"
    :class="`is-${position}`"
    :model-value="modelValue"
    :tab-position="position"
    :stretch="stretch"
    @update:model-value="onUpdate"
    @tab-change="onChange"
  >
    <slot></slot>
  </el-tabs>
</template>

<script setup>
/** 页内主导航标签容器（薄封装：统一视觉与左置列宽，替换 10 处各自为政的 el-tabs）。 */
const props = defineProps({
  /** 当前选中项（v-model） */
  modelValue: { type: [String, Number], default: '' },
  /** 标签位置：top = 常规横向；left = 左侧竖直导航列 */
  position: { type: String, default: 'top' },
  /** 是否等宽拉伸（Element Plus 透传） */
  stretch: { type: Boolean, default: false }
})
void props
const emit = defineEmits(['update:modelValue', 'tab-change'])

function onUpdate(v) { emit('update:modelValue', v) }
function onChange(v) { emit('tab-change', v) }
</script>
