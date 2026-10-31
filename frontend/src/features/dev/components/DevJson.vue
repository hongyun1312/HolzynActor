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
    只读 JSON / 长文本查看器。

    为什么不用 el-input textarea：开发者页要看的是「原样的结构化数据」（Prompt、persona、
    决策输出），textarea 会吃掉换行缩进、还抢焦点。这里用 pre + 等宽字体 + 复制按钮，
    并提供「折叠上限高度」，避免一条 30KB 的 Prompt 把整页撑成一条细线。
  -->
  <div class="dev-json">
    <div v-if="title || copyable" class="dev-json-head">
      <span v-if="title" class="dev-json-title">{{ title }}</span>
      <span v-if="sizeText" class="dev-json-size">{{ sizeText }}</span>
      <div class="dev-json-tools">
        <el-button v-if="collapsible && overflows" link size="small" @click="expanded = !expanded">
          {{ expanded ? '收起' : '展开全部' }}
        </el-button>
        <el-button v-if="copyable" link size="small" @click="copy">复制</el-button>
      </div>
    </div>
    <pre
      ref="preEl"
      class="dev-json-body"
      :style="{ maxHeight: expanded ? 'none' : maxHeight }"
    >{{ text }}</pre>
  </div>
</template>

<script setup>
/**
 * JSON / 文本查看器。
 *
 * <p>`value` 为对象时自动 JSON 美化；为字符串时按原样显示（后端很多字段本身就是 JSON 文本，
 * 例如 personaJson、physioJson——二次 stringify 会得到一层转义反斜杠，反而更难读）。</p>
 */
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  /** 数据源：对象 → 美化 JSON；字符串 → 原样 */
  value: { type: [Object, Array, String, Number, Boolean], default: null },
  /** 左上角标题 */
  title: { type: String, default: '' },
  /** 折叠状态下最大高度 */
  maxHeight: { type: String, default: '320px' },
  /** 是否允许「展开全部」 */
  collapsible: { type: Boolean, default: true },
  /** 是否显示复制按钮 */
  copyable: { type: Boolean, default: true }
})

const expanded = ref(false)
const preEl = ref(null)

/**
 * 文本内容：字符串直接展示（并尝试把「看起来像 JSON 的字符串」美化一次，便于阅读）。
 *
 * @returns {string} 展示文本
 */
const text = computed(() => {
  const v = props.value
  if (v == null) return '—'
  if (typeof v === 'string') {
    const s = v.trim()
    if (s.startsWith('{') || s.startsWith('[')) {
      try {
        return JSON.stringify(JSON.parse(s), null, 2)
      } catch (_) {
        return v
      }
    }
    return v
  }
  try {
    return JSON.stringify(v, null, 2)
  } catch (_) {
    return String(v)
  }
})

/** 字符数提示（判断是否值得展开） */
const sizeText = computed(() => {
  const n = text.value.length
  if (n < 200) return ''
  return n > 1024 ? `${(n / 1024).toFixed(1)} KB · ${n} 字符` : `${n} 字符`
})

/** 内容是否超过折叠高度（估算：约 40 行） */
const overflows = computed(() => text.value.split('\n').length > 14 || text.value.length > 1200)

/** 复制到剪贴板（非安全上下文下降级提示，不阻断） */
async function copy() {
  try {
    await navigator.clipboard.writeText(text.value)
    ElMessage.success('已复制')
  } catch (_) {
    ElMessage.warning('当前环境不允许写剪贴板，请手动选中复制')
  }
}
</script>

<style scoped>
.dev-json {
  min-width: 0;
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-md, 10px);
  background: var(--bg-layer-2, var(--bg-hover));
  overflow: hidden;
}
.dev-json-head {
  display: flex;
  align-items: center;
  gap: var(--space-2, 8px);
  padding: 6px 10px;
  border-bottom: 1px solid var(--border-l1);
  background: var(--bg-layer-1);
}
.dev-json-title { font-size: var(--density-font-sm, 0.82rem); font-weight: 600; color: var(--text-secondary); }
.dev-json-size { font-size: 0.72rem; color: var(--text-tertiary); }
.dev-json-tools { margin-left: auto; display: flex; align-items: center; gap: 4px; }
.dev-json-body {
  margin: 0;
  padding: 10px 12px;
  overflow: auto;
  font-family: var(--font-mono, ui-monospace, 'Cascadia Mono', Consolas, monospace);
  font-size: 0.78rem;
  line-height: 1.65;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
