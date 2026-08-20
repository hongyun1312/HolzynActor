<template>
  <div class="topology-view">
    <!-- 全局关系拓扑页：整页容器渲染全项目角色/普通人群/幽灵关系网络图；
         点击节点在右上角弹出角色卡片；幽灵角色（暂无具体信息）可「前往补充」→ 跳 NPC 角色页预填新增 -->
    <RelationTopology
      :project-id="projectId"
      :active="true"
      variant="page"
      @navigate-supplement="goSupplement"
    />
  </div>
</template>

<script setup>
/**
 * 全局关系拓扑页（侧边栏「角色」分组 · 普通人群之后）。
 * <p>职责：整页容器展示项目全部角色（NPC + 普通人群成员 + 幽灵·待补充）的关系网络图。
 * 点击节点 → 右上角角色卡片（NPC/普通人群显示档案信息；两表都没有的幽灵角色显示
 * 「暂无具体信息，请前往补充」）→ 跳转项目「NPC 角色」页自动打开新增弹窗并预填角色名；
 * 保存成功后回跳本页，后端已按名称全表扫描关系表完成关联。</p>
 * <p>数据源与渲染全部复用 RelationTopology（variant=page）。</p>
 * <p>所属模块：features/character（角色功能域）</p>
 */
import { useRoute, useRouter } from 'vue-router'
import RelationTopology from '../components/RelationTopology.vue'

const route = useRoute()
const router = useRouter()
const projectId = Number(route.params.id)

/** 幽灵角色「前往补充」：跳项目 NPC 角色页，带 from=topology&name=角色名（页面自动开新增弹窗预填） */
function goSupplement(name) {
  router.push({ path: `/project/${projectId}/characters`, query: { from: 'topology', name: name || '' } })
}
</script>

<style scoped>
.topology-view {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
</style>
