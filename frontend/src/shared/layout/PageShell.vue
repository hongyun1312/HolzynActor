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

  PageShell：全站统一「页面骨架」（界面布局与 IA 人性化重构 V2.0 · 批次一）。

  为什么要有它（实测依据）：
    改造前同一产品的页面级内容宽度出现 5 类——内联 1100（仪表盘）、内联 1200（归档/API 设置）、
    令牌 1440（对谈）、铺满（角色/沙盘）；1920 宽下首个内容块左边界在 x=300 / 378 / 548 之间跳动，
    用户切页时右边界会跳。根因是「页面级内联 max-width」散落 12 个文件、9 个不同值。
    V2.0 规范：页面不再自写 max-width，一律经本组件声明档位。

  档位（与 variables.css 的 --page-w-* 同源）：
    form       720px   表单 / 向导 / 账户 / 设置单页
    content   1200px   正文 / 详情 / 列表（默认档）
    wide      1440px   数据 / 图谱 / 表格 / 画布容器
    fullbleed  无上限   画布类整高页（拓扑、图谱工作区）
    「居中列 / 全宽」用户开关仍然有效：html[data-content-mode="wide"] 会把三档置为 none。

  左右留白由外壳 `.shell-content` 统一提供（--content-pad-x，随密度档 18/24/28），本组件只负责
  「限宽 + 居中 + 纵向节奏」，避免出现第二处留白来源。

  用法：
    <PageShell width="content" title="世界详情" desc="只读全文展示" icon="ic_public_file">
      <template #actions><el-button>修改</el-button></template>
      ...页面内容...
    </PageShell>

  只想要限宽、页头自己写（页面已用 <PageHeader>）时：不传 title 即可，页头插槽不会渲染。
  需要整高不滚动（画布类）时：width="fullbleed" fill（或 fill 单独使用）。
-->
<template>
  <div
    class="page-shell"
    :class="[`is-${width}`, { 'is-fill': fill }]"
    :data-page-width="width"
  >
    <!-- 页头：仅在显式传 title / 使用 #header 插槽时渲染，避免与页面自带的 <PageHeader> 重复 -->
    <template v-if="hasHeader">
      <slot name="header">
        <PageHeader :title="title" :desc="desc" :icon="icon">
          <template v-if="$slots.actions" #actions><slot name="actions"></slot></template>
        </PageHeader>
      </slot>
    </template>

    <!-- 页面主体：唯一的内容列，承载限宽与纵向节奏 -->
    <div class="ps-body">
      <slot></slot>
    </div>
  </div>
</template>

<script setup>
/**
 * 统一页面骨架。
 *
 * <p>只做三件事：① 把 `--content-max-w` 按档位本地覆写（历史页面里已有的
 * `var(--content-max-w, 1440px)` 因此自动跟随）；② 居中；③ 提供确定的整高链路
 * （`height:100%` 一路传递，逐步替换各页 `calc(100vh - Npx)` 魔法高度）。</p>
 *
 * @see docs/设计文档/HolzynActor_界面布局与IA人性化重构_方案_V2.0.md §5.2
 */
import { computed, useSlots } from 'vue'
import PageHeader from './PageHeader.vue'

const props = defineProps({
  /** 页面宽度档位：form | content | wide | fullbleed */
  width: { type: String, default: 'content' },
  /** 页头标题（不传则不渲染页头） */
  title: { type: String, default: '' },
  /** 页头说明 */
  desc: { type: String, default: '' },
  /** 页头前导图标 */
  icon: { type: String, default: '' },
  /**
   * 是否让骨架成为「整高不滚动容器」（页面内部自己滚动/自绘画布时为 true）。
   * 与 width 无关，可单独使用（如 content 档但内部是三栏工作区）。
   */
  fill: { type: Boolean, default: false }
})

const slots = useSlots()
const hasHeader = computed(() => !!props.title || !!slots.header)
</script>

<style scoped>
/* 骨架：整高纵向，内容列居中 */
.page-shell {
  display: flex;
  flex-direction: column;
  gap: var(--content-gap, 16px);
  width: 100%;
  min-width: 0;
}

/* 限宽 + 居中：以 --content-max-w 为唯一出口，兼容历史页面的 var(--content-max-w) */
.page-shell > .ps-body,
.page-shell > :deep(.page-header) {
  width: 100%;
  max-width: var(--content-max-w, var(--page-w-wide, 1440px));
  margin: 0 auto;
  min-width: 0;
}

/* 页面主体自带纵向节奏（页面不必再各写 gap；这也让"页面垂直节奏"只改一处） */
.page-shell > .ps-body {
  display: flex;
  flex-direction: column;
  gap: var(--content-gap, 16px);
  min-height: 0;
}

/* 各档位把 --content-max-w 指到对应令牌（纯 CSS 变量链，无需 JS 参与；
   全宽模式由 html[data-content-mode="wide"] 把 --page-w-* 置为 none，本组规则自动跟随） */
.is-form { --content-max-w: var(--page-w-form, 720px); }
.is-content { --content-max-w: var(--page-w-content, 1200px); }
.is-wide { --content-max-w: var(--page-w-wide, 1440px); }
.is-fullbleed { --content-max-w: none; }

/* 整高模式：让页面内部拿到确定高度（替代 calc(100vh - Npx)） */
.page-shell.is-fill { height: 100%; min-height: 0; }
.page-shell.is-fill > .ps-body { flex: 1; }

/* fullbleed 本身通常也是整高页 */
.is-fullbleed { height: 100%; min-height: 0; }
.is-fullbleed > .ps-body { flex: 1; }
</style>