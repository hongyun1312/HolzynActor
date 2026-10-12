<!--
  SPDX-License-Identifier: AGPL-3.0-or-later
  Copyright (C) 2026 HolzynActor

  PageHeader：全站统一页头（界面布局重构 V1.0 批次②）。

  为什么要有它：此前 `.page-header / .page-title / .page-desc` 在 11 个文件里各自 scoped 重复定义，
  字号（1.3rem / 1.25rem / 1.15rem）、间距、对齐方式各不相同，且每新增一页就复制一遍。
  现在样式由 `styles/global.css` 的全局类提供，本组件只负责**结构**与插槽，页面不再写页头样式。

  用法：
    <PageHeader title="世界详情" desc="只读全文展示" icon="ic_public_file">
      <template #actions><el-button>修改</el-button></template>
    </PageHeader>

  标题需要追加标签（版本号/来源标记）时用默认插槽覆盖 title：
    <PageHeader><template #default>名称 <el-tag>v1</el-tag></template></PageHeader>
-->
<template>
  <header class="page-header" :class="size === 'sm' ? 'is-sm' : ''">
    <div class="ph-main">
      <h2 class="page-title">
        <el-icon v-if="icon" class="ph-icon" :size="18"><HIcon :name="icon" /></el-icon>
        <slot>{{ title }}</slot>
      </h2>
      <p v-if="desc || $slots.desc" class="page-desc">
        <slot name="desc">{{ desc }}</slot>
      </p>
    </div>
    <div v-if="$slots.actions" class="page-actions">
      <slot name="actions"></slot>
    </div>
  </header>
</template>

<script setup>
/**
 * 统一页头。
 * <p>只保证结构与插槽，视觉一律来自全局类 `.page-header/.page-title/.page-desc/.page-actions`，
 * 因此调整全站页头只改 `global.css` 一处。</p>
 * <p>`HIcon` 由 `main.js` 全局注册（与全站其它视图一致），此处不再重复 import。</p>
 */
defineProps({
  /** 标题文本（需要内联标签时改用默认插槽） */
  title: { type: String, default: '' },
  /** 副标题/说明文本 */
  desc: { type: String, default: '' },
  /** 可选前导图标（鸿蒙图标名或已映射的 Element 名） */
  icon: { type: String, default: '' },
  /** md = 页面级页头（默认）；sm = 嵌在页面内的子面板页头（画廊各标签面板/工作流面板） */
  size: { type: String, default: 'md' }
})
</script>

<style scoped>
.ph-main { min-width: 0; }
.ph-icon { margin-right: 6px; vertical-align: -2px; color: var(--text-secondary); }
</style>
