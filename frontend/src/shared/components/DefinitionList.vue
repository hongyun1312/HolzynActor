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

  DefinitionList：统一「标签 → 值」定义列表（界面布局与 IA 人性化重构 V2.0 · 批次一）。

  为什么要有它（实测依据）：
    ① 角色概览 tab 的标签-值网格（姓名/头衔/类型/是否主角/位置/重要度/年龄/发育阶段/认知档位）
       与 11 维档案里的标签-值行（PersonaArchivePanel 的 .pa-line-label/.pa-line-value）是**两套各写一遍**的实现；
    ② 长值（`detail` 字段 400–900 字）没有任何阅读宽度约束，1920 宽下单行可达 100+ CJK 字
       ——WCAG 1.4.8 对 CJK 的上限是每行 40 字；
    ③ 值列起始位置靠各页手写宽度，跨页不齐（审计 UI-03 的"标签值不对齐"）。

  规范（结构强制）：
    label 列固定宽度（--dl-label-w，右对齐冒号）；value 列 1fr；
    prose 模式下 value 约束到 --prose-measure（40 CJK 字/行）并放宽行高；
    emphasis 行给左侧主色条（用于「秘密/恐惧/欲望」这类高价值维度）。

  用法：
    <DefinitionList :items="[{label:'姓名', value:'沈守拙'}, {label:'秘密', value:'…', emphasis:true}]" />
    <DefinitionList :items="items" :columns="2" :clamp-lines="4" prose />
-->
<template>
  <dl class="dl" :class="[`is-${columns}col`, { 'is-prose': prose }]" :style="{ '--dl-label-w': labelWidth }">
    <template v-for="(it, i) in items" :key="i">
      <div class="dl-row" :class="{ 'is-emphasis': it.emphasis }">
        <dt class="dl-label" :title="it.label">{{ it.label }}</dt>
        <dd
          class="dl-value"
          :class="{ 'is-mono': it.mono, 'is-clamped': isClamped(it, i), 'is-muted': it.value === '' || it.value === '—' }"
        >
          <slot name="value" :item="it">
            <span class="dl-value-text">{{ it.value === '' || it.value == null ? '—' : it.value }}</span>
          </slot>
          <button
            v-if="clampLines > 0 && isLong(it)"
            type="button"
            class="dl-more"
            @click="toggle(i)"
          >{{ expanded[i] ? '收起' : '展开全文' }}</button>
        </dd>
      </div>
    </template>
  </dl>
</template>

<script setup>
/**
 * 统一「标签 → 值」定义列表。
 *
 * <p>只解决三件事：标签列定宽右对齐、值列对齐、长值阅读宽度（CJK 40 字/行）。
 * 表格语义用原生 `dl/dt/dd`（配合 `display:contents` 让行列在 grid 中参与多列排布）。</p>
 *
 * @see docs/设计文档/HolzynActor_界面布局与IA人性化重构_方案_V2.0.md §5.2 / §5.6
 */
import { reactive } from 'vue'

const props = defineProps({
  /** 行数组：[{ label, value, emphasis?, mono? }] */
  items: { type: Array, default: () => [] },
  /** 标签列宽（CSS 长度，默认 96px） */
  labelWidth: { type: String, default: '96px' },
  /** 列组数：1（默认）| 2（两列并排，适合维度多的档案） */
  columns: { type: Number, default: 1 },
  /** 长值是否加阅读宽度约束（--prose-measure）与更松行高 */
  prose: { type: Boolean, default: false },
  /** 超过该行数则折叠为「展开全文」（0 = 不折叠） */
  clampLines: { type: Number, default: 0 }
})

/** 需要按行数折叠的阈值（字符）：约 clampLines × 44 个 CJK 字 */
const LONG_CHARS = 60
const expanded = reactive({})

function isLong(it) {
  return String(it?.value ?? '').length > LONG_CHARS
}
function isClamped(it, i) {
  return props.clampLines > 0 && isLong(it) && !expanded[i]
}
function toggle(i) {
  expanded[i] = !expanded[i]
}
</script>

<style scoped>
.dl {
  display: grid;
  grid-template-columns: var(--dl-label-w, 96px) minmax(0, 1fr);
  gap: var(--density-gap, 10px) 12px;
  margin: 0;
}
/* 两列并排：每行占 [label value label value] 四个格子 */
.dl.is-2col { grid-template-columns: var(--dl-label-w, 96px) minmax(0, 1fr) var(--dl-label-w, 96px) minmax(0, 1fr); }

/* 行容器不参与布局，让 dt/dd 直接落到 grid 格子（保持原生列表语义） */
.dl-row { display: contents; }

.dl-label {
  font-size: var(--density-font-sm, 0.82rem);
  color: var(--text-tertiary);
  text-align: right;
  line-height: 1.7;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dl-value {
  margin: 0;
  min-width: 0;
  font-size: var(--density-font, 0.9rem);
  color: var(--text-primary);
  line-height: 1.7;
  word-break: break-word;
  white-space: pre-wrap;
}
.dl-value.is-muted { color: var(--text-tertiary); }
.dl-value.is-mono { font-family: var(--font-mono, monospace); font-size: var(--density-font-sm, 0.82rem); }

/* 长文阅读列：CJK 40 字/行（WCAG 1.4.8） */
.dl.is-prose .dl-value { max-width: var(--prose-measure, 40em); line-height: var(--prose-leading, 1.75); }

/* 折叠：line-clamp 需要 -webkit-box */
.dl-value.is-clamped .dl-value-text {
  display: -webkit-box;
  -webkit-line-clamp: v-bind('props.clampLines');
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.dl-more {
  display: inline-block;
  margin-top: 2px;
  border: none; background: transparent; cursor: pointer;
  color: var(--accent-text); font-size: 0.74rem; padding: 0;
}
.dl-more:hover { text-decoration: underline; }

/* 高价值行：左侧主色条（秘密/恐惧/欲望/核心价值） */
.dl-row.is-emphasis .dl-label { color: var(--text-secondary); font-weight: 600; }
.dl-row.is-emphasis .dl-label::before {
  content: '';
  display: inline-block;
  width: 3px; height: 12px;
  margin-right: 6px;
  vertical-align: -1px;
  border-radius: 2px;
  background: var(--accent);
}
</style>