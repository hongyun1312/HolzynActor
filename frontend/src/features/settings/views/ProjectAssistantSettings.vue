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
  <div class="settings-stack">
    <!-- 一页一头：子页自己的页头（父级壳不自绘） -->
    <PageHeader
      title="助手设置"
      desc="项目级 AI 助手（M1+M2）：讲解项目 / 世界 / 角色 / 宏观，检索世界知识与项目文档，双模式回答（叙事 / 事实）。"
    />

    <!-- ① 助手说明 -->
    <div class="set-card">
      <div class="set-card-title">项目级 AI 助手</div>
      <div class="set-card-desc">
        <b>定位</b>：世界与你之间的对话入口——既能讲解项目本身，也能讲述你所在世界正在发生的一切。
        <br /><b>入口</b>：右下角浮窗（可拖拽），或快捷键 <code>Ctrl+Shift+A</code>；<code>Esc</code> 收起，跨页面保持。
        <br /><b>能力</b>：项目导览 · 世界状态 · 角色解读 · 宏观讲解 · 知识检索（世界知识 + 项目文档）。
        <br /><b>双模式</b>：默认叙事（事实为骨、文学为肉）；提问含「多少 / 数值 / 数据」等自动切严格事实模式，
        也可在单轮中说「用数据说话」显式切换；会话默认模式见助手侧边栏右上角徽标。
      </div>
    </div>

    <!-- ② docs 文档索引 -->
    <div class="set-card">
      <div class="set-card-title">项目文档索引（docs/ 向量检索）</div>
      <div class="set-card-desc">
        助手回答「项目功能 / 设计文档怎么说」时从 docs/ 目录检索：首次使用自动全量切片 + 向量化，
        之后按文件变更增量重建；向量供应商未配置或向量化失败时自动降级关键词检索（来源标注「检索降级」）。
      </div>
      <div v-if="indexStatus" class="as-index-grid">
        <span class="as-index-item"><b>{{ indexStatus.enabled ? '已启用' : '未启用' }}</b></span>
        <span class="as-index-item">文档 {{ indexStatus.docCount ?? '—' }} 篇</span>
        <span class="as-index-item">切片 {{ indexStatus.chunkCount ?? '—' }} 段</span>
        <span class="as-index-item">向量库 {{ indexStatus.vectorStoreChunks ?? '—' }} 条</span>
      </div>
      <div v-if="indexStatus && indexStatus.lastError" class="as-index-error">
        最近一次索引异常：{{ indexStatus.lastError }}
      </div>
      <el-button type="primary" plain :loading="rebuilding" @click="rebuildIndex">
        {{ rebuilding ? '重建中…' : '重建文档索引' }}
      </el-button>
    </div>

    <!-- ③ 工具清单 -->
    <div class="set-card">
      <div class="set-card-title">对话工具（只读数据源）</div>
      <div class="set-card-desc">助手工具循环可调用的数据源；全部只读，不修改任何世界状态。</div>
      <div class="as-tools">
        <span v-for="t in tools" :key="t.name" class="as-tool-chip" :title="t.description">{{ t.name }}</span>
        <span v-if="!tools.length" class="set-card-desc">工具清单加载中…</span>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 助手设置页（项目/全局共用）。
 * <p>职责：助手能力说明 + docs 索引状态与重建 + 对话工具清单（只读展示）。</p>
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import PageHeader from '@/shared/layout/PageHeader.vue'
import { getAssistantDocsIndexStatus, rebuildAssistantDocsIndex, listAssistantTools } from '@/shared/api/assistant'

const route = useRoute()
const projectId = computed(() => (route.params.id ? Number(route.params.id) : null))

const indexStatus = ref(null)
const tools = ref([])
const rebuilding = ref(false)

onMounted(async () => {
  try {
    indexStatus.value = await getAssistantDocsIndexStatus()
  } catch (_) {
    indexStatus.value = null
  }
  try {
    tools.value = (await listAssistantTools()) || []
  } catch (_) {
    tools.value = []
  }
})

async function rebuildIndex() {
  rebuilding.value = true
  try {
    indexStatus.value = await rebuildAssistantDocsIndex(projectId.value || 1)
  } finally {
    rebuilding.value = false
  }
}
</script>

<style scoped>
/* V2.1：卡片/标题/描述走全局设置页规范（.settings-stack/.set-card/.set-card-title/.set-card-desc），
   此处仅保留索引统计与工具清单的本页特有样式；颜色一律走令牌，深浅主题自适应。 */
.set-card-desc code {
  background: var(--bg-layer-3);
  padding: 1px 6px;
  border-radius: 5px;
  font-size: 0.75rem;
  color: var(--accent-text);
}
.as-index-grid { display: flex; flex-wrap: wrap; gap: var(--space-3, 12px); margin: var(--space-3, 12px) 0; }
.as-index-item {
  font-size: var(--density-font-sm, 0.82rem);
  padding: 4px 12px;
  border-radius: 8px;
  background: var(--bg-layer-2);
  color: var(--text-regular);
}
.as-index-error { color: var(--state-error, #e5484d); font-size: var(--density-font-sm, 0.82rem); margin-bottom: var(--space-2, 8px); }
.as-tools { display: flex; flex-wrap: wrap; gap: 6px; margin-top: var(--space-2, 8px); }
.as-tool-chip {
  font-size: var(--density-font-sm, 0.82rem);
  font-family: ui-monospace, monospace;
  padding: 3px 10px;
  border-radius: 999px;
  border: 1px solid var(--border-l2);
  color: var(--text-regular);
  background: var(--bg-layer-1);
}
</style>
