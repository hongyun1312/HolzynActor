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
  <PageShell width="wide" fill class="topology-page">
    <!-- 统一页头（与「世界图谱」「项目记忆」同款）：本页此前是 fullbleed 整页画布，
         内容直接从侧栏边缘起画（实测 x=276 / w=1164），与其它页的内容列（x=300 / w=1108）
         差 24px，切页时整块画布会横向跳一格。收进标准内容列后，栅格与页头层级一致。 -->
    <PageHeader
      title="关系拓扑"
      desc="全部角色、普通人群与幽灵角色的关系网络：点节点看档案，可在画布上拖动布局，也可让 AI 补全缺失关系。"
    />

    <!--
      全局关系拓扑页：整页容器渲染全项目角色/普通人群/幽灵关系网络图；
      点击节点在右上角弹出角色卡片；幽灵角色（暂无具体信息）可「前往补充」→ 跳 NPC 角色页预填新增
    -->
    <div class="topology-view">
      <RelationTopology
        :project-id="projectId"
        :active="true"
        variant="page"
        @navigate-supplement="goSupplement"
      />
    </div>
  </PageShell>
</template>

<script setup>
/**
 * 全局关系拓扑页（侧边栏「角色」分组 · 普通人群之后）。
 * <p>职责：整页容器展示项目全部角色（NPC + 普通人群成员 + 幽灵·待补充）的关系网络图。
 * 点击节点 → 右上角角色卡片（NPC/普通人群显示档案信息；两表都没有的幽灵角色显示
 * 「暂无具体信息，请前往补充」）→ 跳转项目「NPC 角色」页自动打开新增弹窗并预填角色名；
 * 保存成功后回跳本页，后端已按名称全表扫描关系表完成关联。</p>
 * <p>数据源与渲染全部复用 RelationTopology（variant=page）。</p>
 */
import { useRoute, useRouter } from 'vue-router'
import PageShell from '@/shared/layout/PageShell.vue'
import PageHeader from '@/shared/layout/PageHeader.vue'
import RelationTopology from '../components/RelationTopology.vue'

const route = useRoute()
const router = useRouter()
const projectId = Number(route.params.id)

/** 幽灵角色「前往补充」：跳项目 NPC 角色页，带 from=topology&name=角色名（页面自动开新增弹窗预填） */
function goSupplement(name) {
  router.push({ path: `/world/${projectId}/characters`, query: { from: 'topology', name: name || '' } })
}
</script>

<style scoped>
/* 画布容器：吃掉页头之后的剩余高度（PageShell fill 已保证 ps-body 是整高 flex 列） */
.topology-view {
  flex: 1 1 auto;
  height: auto;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
</style>
