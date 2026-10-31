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
    开发者控制台的分组卡片。

    为什么直接复用全局 .set-card 系列类：这套「卡片材质 + 标题 0.95rem/700 + 说明 0.82rem/1.7」
    已经是全站唯一口径（设置页在用）。开发者页若自写一套卡片样式，就会出现「同一个产品两种卡片」，
    而这正是本次要消灭的问题。
  -->
  <section class="set-card dev-card" :class="{ 'is-danger': danger, 'is-flush': flush }">
    <div v-if="title || tip || $slots.actions" class="set-card-head" :class="{ 'has-pad': flush }">
      <h3 class="set-card-title">
        {{ title }}
        <span v-if="tip" class="set-tip dev-card-tip">{{ tip }}</span>
      </h3>
      <div v-if="$slots.actions" class="dev-card-actions">
        <slot name="actions"></slot>
      </div>
    </div>
    <p v-if="desc" class="set-card-desc" :class="{ 'has-pad': flush }">{{ desc }}</p>
    <div :class="flush ? 'dev-card-flush-body' : 'dev-card-body'">
      <slot></slot>
    </div>
  </section>
</template>

<script setup>
/**
 * 分组卡片（与设置页同一材质）。
 *
 * <p>用途：把每个 Tab 里的信息切成「一块一块」——指标、表格、JSON、表单都用它包一层，
 * 保证 9 个域、30 多个子面板的圆角、描边、内边距、标题字号完全一致。</p>
 *
 * <p>`flush` 用于表格：表格贴卡片边缘（表格自带单元格内边距），否则 24px + 表格内边距
 * 会让首列文字离边框太远，看起来像没对齐。</p>
 */
defineProps({
  /** 卡片标题 */
  title: { type: String, default: '' },
  /** 卡片说明（标题下方一行灰字） */
  desc: { type: String, default: '' },
  /** 标题右侧弱化提示 */
  tip: { type: String, default: '' },
  /** 危险语义（描边染色，用于「保存并重启」等不可逆操作） */
  danger: { type: Boolean, default: false },
  /** 表格模式：去掉内边距，让表格贴边 */
  flush: { type: Boolean, default: false }
})
</script>

<style scoped>
.dev-card { min-width: 0; }
.dev-card-actions { display: flex; align-items: center; gap: var(--space-2, 8px); flex-wrap: wrap; }
.dev-card-tip { margin-left: var(--space-2, 8px); }
.dev-card-body { min-width: 0; }
/* 表格贴边：卡片自身去内边距，头/说明单独补回左右 24px */
.dev-card.is-flush { padding: 0; }
.dev-card .has-pad { padding-left: 24px; padding-right: 24px; }
.dev-card.is-flush .set-card-head { padding-top: var(--density-card-pad, 16px); margin-bottom: 0; }
.dev-card.is-flush .set-card-desc { margin-top: 6px; }
.dev-card.is-flush .dev-card-flush-body { min-width: 0; }
</style>
