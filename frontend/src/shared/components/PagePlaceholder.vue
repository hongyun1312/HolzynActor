<template>
  <div class="placeholder-page tech-card">
    <div class="ph-title">{{ title }}</div>
    <div class="ph-desc">{{ desc }}</div>
    <el-tag type="info" effect="plain">该页面将在后续里程碑（vP5-2 / vP5-3）实现完整功能</el-tag>
  </div>
</template>

<script setup>
/**
 * 项目内页面通用占位组件。
 * <p>职责：布局重构过渡期使用——尚未实现的项目内页面渲染一个占位卡片，
 * 标题取路由 meta 的 placeholderTitle（缺省用 meta.title），描述取 placeholderDesc。
 * 各页面在 vP5-2/vP5-3 里程碑中被完整实现替换。</p>
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

/** 占位标题：优先 placeholderTitle，其次页面标题去掉模块后缀 */
const title = computed(() => (route.meta?.placeholderTitle || route.meta?.title || '页面').replace(' · HolzynActor', ''))

/** 占位描述 */
const desc = computed(() => route.meta?.placeholderDesc || '')
</script>

<style scoped>
.placeholder-page { padding: 60px 40px; text-align: center; display: flex; flex-direction: column; align-items: center; gap: 14px; }
.ph-title { font-size: 1.3rem; font-weight: 700; color: var(--text-primary); }
.ph-desc { font-size: 0.9rem; color: var(--text-secondary); max-width: 560px; line-height: 1.7; }
</style>
