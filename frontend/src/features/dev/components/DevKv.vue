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
  <!--
    键值列表（左标签定宽 108px，右值可换行）。

    为什么不用共享的 DefinitionList：那个组件是「文档式两列表格」（带表头、适合详情页正文），
    开发者页的信息密度更高、字段更零碎（一次 20~40 行），需要更紧凑的行高与「空值显示 —」的约定。
  -->
  <div class="dev-kv">
    <div v-for="item in normalized" :key="item.label" class="dev-kv-row">
      <span class="dev-kv-label" :title="item.label">{{ item.label }}</span>
      <span
        class="dev-kv-value"
        :class="{ 'is-muted': item.muted, 'dev-mono': item.mono, 'is-long': item.long }"
        :title="item.long ? item.text : undefined"
      >{{ item.text }}</span>
    </div>
    <slot></slot>
  </div>
</template>

<script setup>
/**
 * 键值列表。
 *
 * <p>空值统一显示「—」而不是 `null`/空白：开发者页最常见的困惑是「这个字段是空的，
 * 还是后端没返回，还是我读错了接口」。破折号 + 灰色是最低成本的区分。</p>
 */
import { computed } from 'vue'

const props = defineProps({
  /**
   * 行数据：[{ label, value, mono?, muted? }]
   * <p>value 为 null/undefined/'' 时按「—」渲染。</p>
   */
  items: { type: Array, default: () => [] }
})

/**
 * 数字格式化：小数最多 3 位。
 *
 * <p>为什么必须截断：PAD 情绪这类字段后端存的是 double，`0.24939345797300777` 这种
 * 17 位小数在页面上既读不出差异也挤爆一行；四舍五入到 3 位后「愉悦 0.249 / 唤醒 0.062」
 * 一眼可比。整数（ID、时间戳）原样保留，避免把 13044230551 变成 1.3e10。</p>
 *
 * @param {number} n 原始数字
 *
 * @return {string} 展示文本
 */
function fmtNumber(n) {
  if (!Number.isFinite(n)) return String(n)
  if (Number.isInteger(n)) return String(n)
  const r = Math.round(n * 1000) / 1000
  return String(r)
}

/** 超长文本阈值：超过则给 .is-long（限高 + 内部滚动），避免一整段记忆把卡片拉成三屏 */
const LONG_TEXT = 300

/**
 * 归一化：空值 → 「—」且置灰；布尔/数字转文本。
 */
const normalized = computed(() => (props.items || []).map((it) => {
  const raw = it.value
  const isEmpty = raw === null || raw === undefined || raw === ''
  let text
  if (isEmpty) text = '—'
  else if (typeof raw === 'boolean') text = raw ? '是' : '否'
  else if (typeof raw === 'number') text = fmtNumber(raw)
  else if (typeof raw === 'object') text = JSON.stringify(raw)
  else text = String(raw)
  return {
    label: it.label,
    text,
    mono: !!it.mono,
    muted: isEmpty || !!it.muted,
    long: !isEmpty && text.length > LONG_TEXT
  }
}))
</script>
