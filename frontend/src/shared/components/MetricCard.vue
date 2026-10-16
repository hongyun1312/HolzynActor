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

  MetricCard：统一指标卡（界面布局与 IA 人性化重构 V2.0 · 批次一）。

  为什么要有它（实测依据）：项目仪表盘改造前是这样的——
    角色卡： 7      10      知识库卡：38    0      记忆卡：18    0      AI 用量卡：0    0
             特殊型  普通型            文档  向量块          角色级  项目级            Tokens 调用次数
    · 一张卡并排两个**同尺寸**大数字，语义不明（是分数？对比？还是两项独立指标？）；
    · 数字没有单位、没有口径说明；
    · 缺失值用「季节·收成 —」这种破折号表达且不解释原因；
    · 提示行「AI 强度：标准·第 150…」被截断且无 tooltip；
    · 卡片右侧开关标签「手动常开」被挤成竖排 4 字并贴到卡片边缘（直接表现为"界面坏了"）。

  规范（本组件用 API 形状强制）：
    **一卡一指标**：只有 value 一个大数字；第二、第三项数据一律走 breakdown（小字行），
    结构上不可能再出现两个同尺寸大数字。
    缺失可解释：missing 时显示「—」并给出 missingReason（不是无意义的破折号）。
    口径可查：hint 渲染为带 title 的 ⓘ，同时作为截断提示行的完整文本来源。
    文字不竖排：标签/提示一律 nowrap + ellipsis + tooltip，绝不换行成竖排。

  用法：
    <MetricCard label="角色" :value="7" unit="位" hint="特殊型 NPC，独立 11 维档案"
      :breakdown="[{label:'普通型', value:10}]" action-text="前往角色详情" @action="go" />
    <MetricCard label="季节·收成" missing missing-reason="世界运行满 1 天后生成" />
-->
<template>
  <div class="mc" :class="{ 'is-missing': missing, 'is-clickable': clickable }" @click="onCardClick">
    <div class="mc-head">
      <span class="mc-label" :title="label">{{ label }}</span>
      <el-tooltip v-if="hint" :content="hint" placement="top" effect="dark">
        <span class="mc-info" aria-hidden="true">ⓘ</span>
      </el-tooltip>
    </div>

    <!-- 主值区：一卡一指标 -->
    <div class="mc-value-row">
      <template v-if="missing">
        <span class="mc-dash">—</span>
        <span class="mc-missing-reason" :title="missingReason">{{ missingReason || '尚未生成' }}</span>
      </template>
      <template v-else>
        <span class="mc-value">{{ displayValue }}</span>
        <span v-if="unit" class="mc-unit">{{ unit }}</span>
        <span
          v-if="trend"
          class="mc-trend"
          :class="`is-${trend.direction || 'flat'}`"
          :title="trend.label || ''"
        >{{ trend.direction === 'up' ? '▲' : trend.direction === 'down' ? '▼' : '—' }}{{ trend.value }}</span>
      </template>
    </div>

    <!-- 次级数据：小字行（刻意不用大数字，避免语义混淆） -->
    <ul v-if="breakdown && breakdown.length" class="mc-breakdown">
      <li v-for="(b, i) in breakdown" :key="i" class="mc-bd-item">
        <span class="mc-bd-label" :title="b.label">{{ b.label }}</span>
        <span class="mc-bd-value">{{ b.value }}</span>
      </li>
    </ul>

    <div v-if="actionText" class="mc-foot">
      <button type="button" class="mc-action" @click.stop="emit('action')">{{ actionText }} →</button>
    </div>
  </div>
</template>

<script setup>
/**
 * 统一指标卡（一卡一指标 + 单位 + 口径 tooltip + 可解释缺失 + 次级小字行）。
 *
 * @see docs/设计文档/HolzynActor_界面布局与IA人性化重构_方案_V2.0.md §5.6 / §7
 */
import { computed } from 'vue'

const props = defineProps({
  /** 指标名（如「角色」「季节·收成」） */
  label: { type: String, default: '' },
  /** 主值（数字或文本；空即视为缺失，除非 missing=false 且 valueText 给定） */
  value: { type: [Number, String], default: null },
  /** 单位（如「位」「文/石」），渲染为小字紧随主值 */
  unit: { type: String, default: '' },
  /** 口径说明（tooltip；同时是截断提示的完整文本） */
  hint: { type: String, default: '' },
  /** 是否处于"数据未生成"状态（显示 — 与原因） */
  missing: { type: Boolean, default: false },
  /** 缺失原因（面向用户的结果语言，如「世界运行满 1 天后生成」） */
  missingReason: { type: String, default: '' },
  /** 趋势：[{ value: '8%', direction: 'up'|'down'|'flat', label: '较昨日' }] */
  trend: { type: Object, default: null },
  /** 次级数据小字行：[{ label, value }]（**不要**在这里再放一个大数字） */
  breakdown: { type: Array, default: () => [] },
  /** 底部动作文字（留空不渲染） */
  actionText: { type: String, default: '' }
})
const emit = defineEmits(['action'])

const clickable = computed(() => !!props.actionText)
const displayValue = computed(() => {
  const v = props.value
  if (v === null || v === undefined || v === '') return '—'
  return typeof v === 'number' ? v.toLocaleString('zh-CN') : String(v)
})
function onCardClick() { if (clickable.value) emit('action') }
</script>

<style scoped>
.mc {
  display: flex; flex-direction: column; gap: 6px;
  padding: var(--density-card-pad, 16px);
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-lg, 16px);
  box-shadow: var(--shadow-lv1);
  min-width: 0;
}
.mc.is-clickable { cursor: pointer; transition: border-color .15s var(--dsw-ease), box-shadow .15s var(--dsw-ease); }
.mc.is-clickable:hover { border-color: var(--accent-border); box-shadow: var(--shadow-lv2); }

/* 标签行：nowrap + ellipsis（关键：绝不允许换成竖排） */
.mc-head { display: flex; align-items: center; gap: 4px; min-width: 0; }
.mc-label {
  font-size: var(--density-font-sm, 0.82rem);
  color: var(--text-secondary);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.mc-info { font-size: 0.7rem; color: var(--text-tertiary); cursor: help; flex-shrink: 0; }

/* 主值：唯一的大数字 */
.mc-value-row { display: flex; align-items: baseline; gap: 5px; min-width: 0; }
.mc-value {
  font-size: 1.5rem; font-weight: 700; line-height: 1.2;
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}
.mc-unit { font-size: var(--density-font-sm, 0.82rem); color: var(--text-tertiary); white-space: nowrap; }
.mc-trend { font-size: 0.72rem; color: var(--text-tertiary); white-space: nowrap; }
.mc-trend.is-up { color: var(--state-success, #22c55e); }
.mc-trend.is-down { color: var(--state-error, #ef4444); }

/* 缺失态：破折号 + 原因（不是无解释的 —） */
.mc-dash { font-size: 1.5rem; font-weight: 700; color: var(--text-tertiary); line-height: 1.2; }
.mc-missing-reason {
  font-size: 0.72rem; color: var(--text-tertiary);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}

/* 次级数据：小字行 */
.mc-breakdown { display: flex; flex-wrap: wrap; gap: 4px 12px; margin: 0; }
.mc-bd-item { display: inline-flex; align-items: baseline; gap: 4px; min-width: 0; }
.mc-bd-label {
  font-size: 0.72rem; color: var(--text-tertiary);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.mc-bd-value { font-size: 0.74rem; color: var(--text-secondary); font-variant-numeric: tabular-nums; }

.mc-foot { margin-top: 2px; }
.mc-action {
  border: none; background: transparent; cursor: pointer; padding: 0;
  color: var(--accent-text); font-size: 0.74rem;
}
.mc-action:hover { text-decoration: underline; }
</style>