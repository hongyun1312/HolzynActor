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
    通用结构渲染器：把任意 JSON 渲染成「键值行 + 数组表格 + 嵌套小节」。

    为什么需要它：开发者页要展示的接口有 30 多个，其中相当一部分是调试用的
    Map<String,Object>（世界快照、宏观状态、决策模型 统计…），字段随迭代变化。
    逐接口写死列名会得到一堆「后端加字段前端就看不见」的死表格；
    全塞 JSON 又没法快速扫读。这里按形状自动选渲染方式：
      · 标量        → 键值行（左标签右值）
      · 标量数组    → 标签串
      · 对象数组    → 表格（列 = 各元素标量键的并集，复杂单元格折叠为 JSON 摘要）
      · 嵌套对象    → 小节标题 + 递归（超过 maxDepth 折叠为 JSON 查看器）
    这正是「排查时先看全貌、再看细节」的阅读顺序。
  -->
  <div class="dev-auto">
    <!-- 标量键值 -->
    <DevKv v-if="scalarRows.length" :items="scalarRows" />

    <!-- 复杂字段：数组表格 / 嵌套对象 -->
    <div v-for="entry in complexEntries" :key="entry.key" class="dev-auto-block">
      <div class="dev-subtitle">{{ entry.key }}<span class="dev-auto-count">{{ entry.hint }}</span></div>

      <!-- 空数组：一句话说明，不渲染「No Data」空表格 -->
      <div v-if="entry.kind === 'empty'" class="dev-toolbar-note">空（0 项）</div>

      <!-- 标量数组：标签串 -->
      <div v-else-if="entry.kind === 'primitives'" class="dev-chips">
        <el-tag v-for="(v, i) in entry.value" :key="i" size="small" effect="plain">{{ v }}</el-tag>
        <span v-if="!entry.value.length" class="dev-toolbar-note">空</span>
      </div>

      <!-- 对象数组：表格 -->
      <template v-else-if="entry.kind === 'rows'">
        <el-table :data="entry.rows" class="dev-table" size="small" max-height="360">
          <el-table-column
            v-for="col in entry.columns"
            :key="col"
            :label="col"
            min-width="120"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <span :class="{ 'dev-mono': isComplex(row[col]) }">{{ cellText(row[col]) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="entry.truncated" class="dev-toolbar-note dev-auto-note">
          共 {{ entry.total }} 条，仅显示前 {{ AUTO_ROW_LIMIT }} 条
        </div>
      </template>

      <!-- 嵌套对象：递归一层，深了就直接给 JSON -->
      <DevAuto
        v-else-if="depth < maxDepth"
        :value="entry.value"
        :depth="depth + 1"
        :max-depth="maxDepth"
      />
      <DevJson v-else :value="entry.value" :max-height="240" />
    </div>

    <div v-if="!scalarRows.length && !complexEntries.length" class="dev-toolbar-note">无数据</div>
  </div>
</template>

<script setup>
/**
 * 通用结构渲染器（递归）。
 *
 * <p>列名直接用后端字段名（camelCase）：开发者需要的是「与接口/数据库对得上」，
 * 而不是再翻译一层中文——翻译只做在卡片标题这种人工维护的地方。</p>
 */
import { computed } from 'vue'
import DevKv from './DevKv.vue'
import DevJson from './DevJson.vue'

defineOptions({ name: 'DevAuto' })

/** 数组表格最多渲染的行数（再多也读不过来，且会把页面撑长） */
const AUTO_ROW_LIMIT = 200

const props = defineProps({
  /** 任意结构 */
  value: { type: null, default: null },
  /** 当前递归深度（内部用） */
  depth: { type: Number, default: 0 },
  /** 最大递归深度 */
  maxDepth: { type: Number, default: 2 }
})

/** 是否标量 */
function isScalar(v) {
  return v === null || v === undefined || ['string', 'number', 'boolean'].includes(typeof v)
}

/** 是否复杂值（对象/数组） */
function isComplex(v) {
  return v !== null && typeof v === 'object'
}

/** 数字格式化：小数最多 3 位（与 DevKv 同一约定——double 字段的 17 位小数在页面上读不出信息） */
function fmtNumber(n) {
  if (!Number.isFinite(n)) return String(n)
  if (Number.isInteger(n)) return String(n)
  return String(Math.round(n * 1000) / 1000)
}

/** 单元格文本（复杂值折成短 JSON） */
function cellText(v) {
  if (v === null || v === undefined) return '—'
  if (typeof v === 'number') return fmtNumber(v)
  if (typeof v === 'boolean') return v ? '是' : '否'
  if (isScalar(v)) return String(v)
  try {
    const s = JSON.stringify(v)
    return s.length > 120 ? s.slice(0, 120) + '…' : s
  } catch (_) {
    return '[对象]'
  }
}

/** 顶层对象 → 标量行（保持后端返回顺序） */
const scalarRows = computed(() => {
  const v = props.value
  if (!v || typeof v !== 'object' || Array.isArray(v)) return []
  return Object.entries(v)
    .filter(([, x]) => isScalar(x))
    .map(([k, x]) => ({
      label: k,
      value: typeof x === 'boolean' ? (x ? '是' : '否') : x,
      mono: typeof x === 'string' && x.length > 40
    }))
})

/** 顶层对象 → 复杂字段（数组/嵌套对象） */
const complexEntries = computed(() => {
  const v = props.value
  if (!v || typeof v !== 'object' || Array.isArray(v)) return []
  const out = []
  for (const [k, x] of Object.entries(v)) {
    if (isScalar(x)) continue
    if (Array.isArray(x)) {
      if (x.length === 0) {
        out.push({ key: k, kind: 'empty', hint: '（0 项）' })
        continue
      }
      const primitives = x.length > 0 && x.every(isScalar)
      if (primitives) {
        out.push({ key: k, kind: 'primitives', value: x, hint: `（${x.length} 项）` })
      } else {
        const keys = new Set()
        for (const row of x) {
          if (row && typeof row === 'object' && !Array.isArray(row)) {
            for (const [rk, rv] of Object.entries(row)) if (isScalar(rv)) keys.add(rk)
          }
        }
        let columns = [...keys]
        if (columns.length > 10) columns = columns.slice(0, 10)
        out.push({
          key: k,
          kind: 'rows',
          rows: x.slice(0, AUTO_ROW_LIMIT),
          columns: columns.length ? columns : ['value'],
          total: x.length,
          truncated: x.length > AUTO_ROW_LIMIT,
          hint: `（${x.length} 项${columns.length ? '' : ' · 无标量列，见 JSON'}）`
        })
      }
    } else {
      out.push({ key: k, kind: 'object', value: x, hint: '（对象）' })
    }
  }
  return out
})
</script>

<style scoped>
.dev-auto { display: flex; flex-direction: column; gap: var(--space-2, 8px); min-width: 0; }
.dev-auto-block { min-width: 0; }
.dev-auto-count { margin-left: 6px; font-weight: 400; color: var(--text-tertiary); }
.dev-auto-note { margin-top: 4px; }
</style>
