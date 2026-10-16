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

  EmptyState：全站统一空态（界面布局与 IA 人性化重构 V2.0 · 批次一）。

  为什么要有它（实测依据）：
    ① 改造前全仓没有空态组件（EmptyState 使用文件数 = 0），13 个文件各自手写 el-empty；
    ② 对谈页零会话时**同屏出现 3 张空态卡**，且右栏又给了一个与中栏重复的「+ 新建对谈」CTA；
       UXMagic / Evergreen 的空态规范明确写「avoid offering a CTA twice on the same screen」；
    ③ 侧栏放独立空态卡会让首屏被同义文案占满（Gmail / Linear / Notion 一律让侧栏留白或收起）。

  规范（本组件用 API 形状强制，而不是靠口头约定）：
    size="page"   页面主体空态：允许 1 个 —— 图标 + 标题 + 说明 + ≤3 要点 + **唯一主 CTA**；
    size="panel"  侧栏/窄栏：**不渲染卡片**，只有一行提示 + 至多一个文字动作（侧栏不得有独立空态卡）；
    size="inline" 卡片内/表格内：极简一行，无按钮。
    主 CTA 唯一：本组件只提供 primary 一个按钮；secondary 渲染为文字链接（视觉权重大幅低于主 CTA），
    因此结构上不可能出现「两个同权重主行动」。

  用法：
    <EmptyState
      icon="ic_public_message"
      title="与角色对谈"
      desc="对谈是独立的静止世界模式：创建会话即冻结世界，聊天期间世界零推进。"
      :points="['创建即冻结', '聊天期间世界零推进', '可随时手动恢复']"
      primary-text="新建对谈" primary-icon="Plus"
      @primary="create"
    >
      <template #extra>…（如角色快捷入口，可选）…</template>
    </EmptyState>
-->
<template>
  <!-- panel：侧栏一行提示，不套卡片（规范强制） -->
  <div v-if="size === 'panel'" class="es-panel">
    <span class="es-panel-text">{{ title || desc }}</span>
    <button v-if="primaryText" type="button" class="es-panel-link" @click="emit('primary')">
      {{ primaryText }}
    </button>
  </div>

  <!-- inline：卡片内极简 -->
  <div v-else-if="size === 'inline'" class="es-inline" :class="`is-${tone}`">
    <el-icon v-if="icon" :size="14"><HIcon :name="icon" /></el-icon>
    <span class="es-inline-text">{{ title }}</span>
    <button v-if="primaryText" type="button" class="es-panel-link" @click="emit('primary')">
      {{ primaryText }}
    </button>
  </div>

  <!-- page：页面主体空态（空态即教学） -->
  <div v-else class="es-page" :class="`is-${tone}`">
    <div class="es-card">
      <div v-if="icon" class="es-art" aria-hidden="true">
        <el-icon :size="30"><HIcon :name="icon" /></el-icon>
      </div>
      <h3 class="es-title">{{ title }}</h3>
      <p v-if="desc" class="es-desc">{{ desc }}</p>

      <ul v-if="points && points.length" class="es-points">
        <li v-for="(p, i) in points.slice(0, 3)" :key="i" class="es-point">
          <span class="es-point-dot" aria-hidden="true">{{ i === 0 ? '◷' : i === 1 ? '❙❙' : '▶' }}</span>
          <span>{{ p }}</span>
        </li>
      </ul>

      <div v-if="primaryText || secondaryText" class="es-actions">
        <el-button
          v-if="primaryText"
          type="primary"
          class="es-primary"
          @click="emit('primary')"
        >
          <el-icon v-if="primaryIcon" :size="14"><HIcon :name="primaryIcon" /></el-icon>
          {{ primaryText }}
        </el-button>
        <button v-if="secondaryText" type="button" class="es-secondary" @click="emit('secondary')">
          {{ secondaryText }}
        </button>
      </div>

      <div v-if="$slots.extra" class="es-extra">
        <slot name="extra"></slot>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 统一空态组件。
 *
 * <p>把「空态即教学」做成可复用形状：主标题（这是什么）+ 说明（为什么空）+ ≤3 条要点
 * （怎么开始 / 会发生什么）+ 唯一主 CTA。范本来自对谈页中栏空态（审计评为全站最佳实践）。</p>
 *
 * @see docs/设计文档/HolzynActor_界面布局与IA人性化重构_方案_V2.0.md §5.6
 */
defineProps({
  /** page（页面主体，默认）| panel（侧栏一行提示）| inline（卡片内极简） */
  size: { type: String, default: 'page' },
  /** 标题；panel/inline 档下作为唯一文本 */
  title: { type: String, default: '' },
  /** 说明（page 档） */
  desc: { type: String, default: '' },
  /** 前导图标（HIcon 名） */
  icon: { type: String, default: '' },
  /** ≤3 条要点（超出自动截断，避免空态变成说明书） */
  points: { type: Array, default: () => [] },
  /** 主 CTA 文案（唯一主行动；留空则不渲染按钮） */
  primaryText: { type: String, default: '' },
  /** 主 CTA 图标 */
  primaryIcon: { type: String, default: '' },
  /** 次级动作：渲染为文字链接（非按钮），保证与主 CTA 的视觉权重差 */
  secondaryText: { type: String, default: '' },
  /** 语气：default | warn（如"数据未生成/出错了"） */
  tone: { type: String, default: 'default' }
})
const emit = defineEmits(['primary', 'secondary'])
</script>

<style scoped>
/* ===== page 档：居中卡片 ===== */
.es-page { flex: 1; display: flex; align-items: center; justify-content: center; padding: 24px; overflow-y: auto; }
.es-card {
  display: flex; flex-direction: column; align-items: center; gap: 10px;
  width: 100%; max-width: 520px;
  padding: 26px 28px;
  text-align: center;
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-xl, 16px);
  box-shadow: var(--shadow-lv2);
}
.es-page.is-warn .es-card { border-color: color-mix(in srgb, var(--state-warn, #f59e0b) 45%, transparent); }
.es-art {
  width: 56px; height: 56px; border-radius: 16px;
  display: flex; align-items: center; justify-content: center;
  background: var(--accent-soft); color: var(--accent-text);
}
.es-title { margin: 0; font-size: 1.22rem; font-weight: 700; color: var(--text-primary); }
/* 空态说明是短文本（不超过 3 行），但仍约束阅读宽度，避免大屏上拉满 */
.es-desc { margin: 0; max-width: 34em; color: var(--text-secondary); font-size: 0.82rem; line-height: 1.8; }
.es-points { display: flex; flex-wrap: wrap; justify-content: center; gap: 6px 14px; margin: 2px 0; }
.es-point { display: inline-flex; align-items: center; gap: 5px; font-size: 0.74rem; color: var(--text-tertiary); }
.es-point-dot { font-size: 0.7rem; color: var(--accent-text); }
.es-actions { display: flex; flex-direction: column; align-items: center; gap: 6px; margin-top: 4px; }
.es-primary { min-width: 150px; }
.es-secondary {
  border: none; background: transparent; cursor: pointer;
  color: var(--text-tertiary); font-size: 0.76rem; padding: 2px 4px;
}
.es-secondary:hover { color: var(--accent-text); text-decoration: underline; }
.es-extra { width: 100%; margin-top: 10px; padding-top: 14px; border-top: 1px dashed var(--border-l1); }

/* ===== panel 档：侧栏一行提示（刻意不套卡片） ===== */
.es-panel {
  display: flex; flex-direction: column; align-items: center; gap: 2px;
  padding: 10px 10px 12px; text-align: center;
}
.es-panel-text { font-size: 0.74rem; color: var(--text-tertiary); line-height: 1.6; }
.es-panel-link {
  border: none; background: transparent; cursor: pointer;
  color: var(--accent-text); font-size: 0.74rem; padding: 2px 4px;
}
.es-panel-link:hover { text-decoration: underline; }

/* ===== inline 档：卡片内极简一行 ===== */
.es-inline {
  display: flex; align-items: center; gap: 6px;
  padding: 8px 10px; border-radius: var(--radius-sm, 8px);
  background: var(--bg-layer-2); color: var(--text-tertiary); font-size: 0.76rem;
}
.es-inline.is-warn { color: var(--state-warn, #f59e0b); }
.es-inline-text { min-width: 0; }
</style>