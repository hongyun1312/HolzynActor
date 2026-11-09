<!--
  关系拓扑页的隔离预览壳（仅开发用）。

  它就是「PageShell + PageHeader + 关系工作台」的最小复刻：上方一条真页头，下方铺满画布，
  目的是让截图里的留白、圆角、浮层位置与真实页面一致（直接裸挂组件会少掉页头这层几何关系）。
-->
<template>
  <div class="preview-shell">
    <header class="preview-head">
      <div class="preview-title">
        <el-icon><HIcon name="Share" /></el-icon>
        <span>关系拓扑</span>
        <span class="preview-tag">预览</span>
      </div>
      <div class="preview-sub">25 人 · 5 个圈子 · 星海（可拖动）</div>
    </header>
    <div class="preview-body">
      <RelationTopology ref="topoRef" :project-id="1" :variant="variant" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import RelationTopology from '@/features/character/components/RelationTopology.vue'

const props = defineProps({
  /** 初始布局（默认星海） */
  layout: { type: String, default: 'nebula' },
  /** 变体：page=整页工作台 / tab=角色页内嵌 */
  variant: { type: String, default: 'page' }
})

const topoRef = ref(null)

onMounted(() => {
  if (props.layout && props.layout !== 'nebula') {
    // setLayout 是工作台对外暴露的正式入口（工具栏下拉走的是同一条路）
    setTimeout(() => topoRef.value?.setLayout?.(props.layout), 60)
  }
})
</script>

<style scoped>
.preview-shell {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--bg-layer-2);
}
.preview-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  height: 56px;
  padding: 0 20px;
  border-bottom: 1px solid var(--border-light);
  background: var(--bg-layer-1);
  flex-shrink: 0;
}
.preview-title {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 0.96rem;
  font-weight: 600;
  color: var(--text-primary);
}
.preview-tag {
  padding: 1px 6px;
  border-radius: 6px;
  background: color-mix(in srgb, var(--accent) 14%, transparent);
  color: var(--accent);
  font-size: 0.68rem;
  font-weight: 500;
}
.preview-sub {
  font-size: 0.76rem;
  color: var(--text-tertiary);
}
.preview-body {
  flex: 1;
  min-height: 0;
  padding: 12px;
}
</style>
