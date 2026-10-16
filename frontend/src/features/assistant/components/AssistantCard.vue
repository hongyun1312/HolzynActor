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
  <div class="assistant-card" :class="`is-${card.type}`">
    <div class="assistant-card-head">
      <span class="assistant-card-type">{{ typeLabel }}</span>
      <span class="assistant-card-title">{{ card.title || '数据' }}</span>
    </div>
    <div v-if="fields.length" class="assistant-card-body">
      <div v-for="(f, i) in fields" :key="i" class="assistant-card-field">
        <span class="assistant-card-label">{{ f.label }}</span>
        <span class="assistant-card-value">{{ f.value }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 助手数据卡片（结构化数据展示）。
 * 后端 AssistantCardVO：{ type: world/character/location/macro/event/guide, title, fields:[{label,value}] }。
 * 纯展示组件，零逻辑。
 */
import { computed } from 'vue'

const props = defineProps({
  card: { type: Object, default: null }
})

const TYPE_LABELS = {
  world: '世界概览',
  character: '角色',
  location: '地点',
  macro: '宏观',
  event: '事件',
  guide: '功能导览',
  trace: '决策链路',
  stats: 'AI 调用统计',
  result: '干预结果'
}

const typeLabel = computed(() => TYPE_LABELS[props.card?.type] || '数据')
const fields = computed(() => (props.card?.fields || []).filter((f) => f && f.label))
</script>

<style scoped>
.assistant-card {
  border: 1px solid var(--border-l2, #e5e9f2);
  border-radius: 10px;
  background: var(--bg-elev, #ffffff);
  overflow: hidden;
}
.assistant-card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: color-mix(in srgb, var(--color-primary, #4f6ef7) 8%, transparent);
  border-bottom: 1px solid var(--border-l1, #eef1f6);
}
.assistant-card-type {
  font-size: 11px;
  color: var(--color-primary, #4f6ef7);
  background: color-mix(in srgb, var(--color-primary, #4f6ef7) 14%, transparent);
  border-radius: 999px;
  padding: 1px 8px;
}
.assistant-card-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-strong, #1f2a44);
}
.assistant-card-body {
  padding: 6px 12px 8px;
}
.assistant-card-field {
  display: flex;
  gap: 10px;
  padding: 4px 0;
  border-bottom: 1px dashed var(--border-l1, #eef1f6);
  font-size: 12.5px;
}
.assistant-card-field:last-child { border-bottom: none; }
.assistant-card-label {
  flex: 0 0 96px;
  color: var(--text-dim, #8a94a8);
}
.assistant-card-value {
  color: var(--text-main, #3a4660);
  word-break: break-all;
}
</style>
