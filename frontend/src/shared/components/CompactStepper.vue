/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
<!--
  CompactStepper：紧凑分段步骤器（表现层组件，零业务逻辑）。

  <p><b>为什么不直接用 el-steps</b>：`el-steps` 即使去掉 description 仍要占 72px 以上
  （圆点 + 标题 + 连接线纵向堆叠），`simple` 形态则是「灰色填充条 + 箭头分隔」（主题变量
  `--el-fill-color-light` 铺底、padding 13px 8%），在玻璃卡片里既压不下来也不符合
  「细线型分段器」的观感。首启向导与世界初始化页都需要「一行 20px 左右、细线连接、
  品牌色标当前步」的同一规格，改造成本已大于这 40 行样式，故收敛成本组件（两页共用一份，
  避免各写一套导致两处规格再次分叉）。</p>

  <p><b>用途</b>：放在页头/卡片顶部一行内，例如
  `① 连接模型 —— ② 我的形象`。标题过长时省略号截断，完整文案走 title 属性（原生悬停）。</p>
-->
<template>
  <ol class="cstep" :aria-label="label">
    <li
      v-for="(item, i) in items"
      :key="item.key ?? i"
      class="cstep__item"
      :class="[stateClass(i), { 'is-clickable': clickable && i !== active }]"
      :aria-current="i === active ? 'step' : undefined"
      :title="item.desc || item.title"
      @click="pick(i)"
    >
      <span class="cstep__dot" aria-hidden="true">{{ i + 1 }}</span>
      <span class="cstep__label">{{ item.title }}</span>
      <span v-if="i < items.length - 1" class="cstep__line" aria-hidden="true" />
    </li>
  </ol>
</template>

<script setup>
/**
 * 紧凑分段步骤器。
 * <p>状态：已完成（序号 &lt; active）/ 当前（序号 = active）/ 未开始（序号 &gt; active），
 * `error` 为真时当前步显示失败色。</p>
 * <p>可点击仅回退到已完成步骤或前进到任意步骤由父级决定（本组件只负责 emit），
 * 不自己改 active —— 步骤状态必须由页面唯一持有，避免两处状态打架。</p>
 */
const props = defineProps({
  /** 步骤项：[{ key, title, desc? }]，desc 仅作悬停提示，不渲染 */
  items: { type: Array, required: true },
  /** 当前步骤索引（0 起） */
  active: { type: Number, default: 0 },
  /** 是否允许点击切换（点击只 emit select，不改自身状态） */
  clickable: { type: Boolean, default: false },
  /** 当前步是否失败（世界初始化失败时用） */
  error: { type: Boolean, default: false },
  /** 无障碍标签 */
  label: { type: String, default: '步骤' }
})

const emit = defineEmits(['select'])

/** 单步状态类名 */
function stateClass(i) {
  if (i === props.active) return props.error ? 'is-error' : 'is-active'
  return i < props.active ? 'is-done' : 'is-todo'
}

/** 点击切换：当前步、不可点击时忽略 */
function pick(i) {
  if (!props.clickable || i === props.active) return
  emit('select', i)
}
</script>

<style scoped>
.cstep {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
  min-width: 0;
}
.cstep__item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  font-size: var(--density-font-sm, 0.82rem);
  line-height: 1;
  color: var(--text-tertiary);
  transition: color 0.2s;
}
.cstep__dot {
  flex: 0 0 auto;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12.5px;
  font-variant-numeric: tabular-nums;
  border: 1px solid var(--border-l2, rgba(255, 255, 255, 0.18));
  color: var(--text-tertiary);
  transition: background 0.2s, border-color 0.2s, color 0.2s;
}
.cstep__label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 细线连接段：唯一的分隔手段（不画箭头，避免与品牌色抢视线） */
.cstep__line {
  flex: 0 0 auto;
  width: 26px;
  height: 1px;
  margin: 0 2px;
  background: var(--border-l2, rgba(255, 255, 255, 0.18));
}
/* 当前步：实心品牌色圆点 + 主文字色加粗 */
.is-active .cstep__dot {
  background: var(--accent);
  border-color: var(--accent);
  color: var(--accent-contrast, #fff);
}
.is-active .cstep__label { color: var(--text-primary); font-weight: 600; }
.is-active .cstep__line { background: var(--accent-border); }
/* 已完成：描边品牌色 + 数字保留（不换成勾，避免 HIcon 未知图标静默空白） */
.is-done .cstep__dot { border-color: var(--accent-border); color: var(--accent-text); }
.is-done .cstep__label { color: var(--text-secondary); }
.is-done .cstep__line { background: var(--accent-border); }
/* 当前步失败 */
.is-error .cstep__dot {
  background: var(--state-danger, #e5484d);
  border-color: var(--state-danger, #e5484d);
  color: #fff;
}
.is-error .cstep__label { color: var(--state-danger, #e5484d); font-weight: 600; }
/* 可点击步：指针 + 悬停提亮（键盘用户不依赖它，页面另有按钮兜底） */
.is-clickable { cursor: pointer; }
.is-clickable:hover .cstep__label { color: var(--accent-text); }
.is-clickable:hover .cstep__dot { border-color: var(--accent-border); }
</style>
