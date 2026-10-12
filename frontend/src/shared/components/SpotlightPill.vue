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
  <span class="spot" :title="title || undefined"><template
    v-for="(seg, i) in segments"
    :key="i"
  ><mark v-if="seg.hit" class="spot-hit" data-hit>{{ seg.text }}</mark><template v-else>{{ seg.text }}</template></template></span>
</template>

<script setup>
/**
 * 关键词高亮片段（Spotlight）。
 *
 * <p>职责单一：给定「文本 + 关键词」，把命中的部分包成 `<mark class="spot-hit">`。
 * 语义（大小写不敏感 / 多关键词 / 正则元字符安全 / 重叠取最长 / 窗口化截断）全部委托给
 * {@link module:shared/components/spotlight}，组件本身**不做字符串拼接、不用 `v-html`**——
 * 因此关键词里带 `<img onerror=...>` 也只会被当作普通文字显示，不可能注入标记。</p>
 *
 * <p>使用场景：全局搜索（命令与命中结果）、知识库文档筛选与检索预览等所有"我搜的词在哪"的界面。
 * 各页不要再自己写 `split('') + v-html` 之类的高亮。</p>
 */
import { computed } from 'vue'
import { spotlight } from './spotlight'

const props = defineProps({
  /** 原文本 */
  text: { type: [String, Number], default: '' },
  /** 关键词：字符串（按空白切分）或数组 */
  keyword: { type: [String, Array], default: '' },
  /** 最大显示字符数（>0 时以首个命中为中心截断并加省略号；0 = 不截断） */
  maxLength: { type: Number, default: 0 },
  /** 原生 title 提示（常用于"全文比显示的长"的场景） */
  title: { type: String, default: '' }
})

/** 渲染片段 */
const segments = computed(() => spotlight(props.text, props.keyword, props.maxLength))
</script>

<style scoped>
.spot {
  word-break: break-word;
}
/* 高亮色：用主题强调色而非浏览器默认的黄底黑字，暗色主题下也不刺眼 */
.spot-hit {
  background: transparent;
  color: var(--color-primary, var(--el-color-primary));
  font-weight: 600;
  padding: 0 1px;
  border-radius: 2px;
}
</style>
