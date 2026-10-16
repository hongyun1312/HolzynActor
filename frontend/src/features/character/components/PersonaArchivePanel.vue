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
  <div class="pa-panel" :class="{ 'is-compact': compact }">
    <!-- 无档案：不构造空面板（调用方可能已给出"去生成"入口） -->
    <EmptyState v-if="!card" size="inline" title="该角色还没有 11 维档案" />

    <template v-else>
      <!-- 顶部：角色名 + 档案版本 + 来源 + 更新时间 -->
      <div class="pa-head">
        <div class="pa-head-main">
          <span class="pa-name">{{ displayName }}</span>
          <el-tag size="small" effect="plain">档案版本 v{{ view.meta.version ?? '—' }}</el-tag>
          <el-tag size="small" type="success" effect="plain">来源：{{ sourceLabel(view.meta.source) }}</el-tag>
          <el-tag v-if="sceneText" size="small" type="info" effect="plain">生成场景：{{ sceneText }}</el-tag>
          <el-tag v-if="view.meta.schemaVersion" size="small" type="info" effect="plain">schema v{{ view.meta.schemaVersion }}</el-tag>
        </div>
        <div class="pa-head-side">
          <span class="pa-time">更新：{{ fmtDateTime(view.meta.updatedAt) }}</span>
          <el-button size="small" text type="primary" :disabled="!view.hasRenderText" @click="copyRenderText">
            复制 11 维文本
          </el-button>
        </div>
      </div>

      <!-- 渲染文本缺失时的降级提示（后端未升级/旧数据；不阻断展示） -->
      <el-alert
        v-if="!view.hasRenderText"
        type="warning"
        :closable="false"
        class="pa-alert"
        title="服务端未返回 11 维渲染文本（personaRenderText 为空）"
        description="各维改为展示档案内该维的原始 JSON。请确认后端已升级到 G1a 契约（CharacterCardVO.personaRenderText）。"
      />

      <!-- 维索引：点击滚动到对应分区（11 维顺序 ①→⑪） -->
      <div class="pa-index">
        <button
          v-for="d in view.dims"
          :key="d.key"
          type="button"
          class="pa-index-item"
          :class="{ 'is-empty': !d.hasContent }"
          @click="scrollToDim(d.key)"
        >{{ d.no }}{{ d.title }}</button>
      </div>

      <!-- 主体：11 维分区（服务端渲染文本按维切分；空维显示占位文案而非空白） -->
      <div class="pa-dims">
        <section
          v-for="d in view.dims"
          :key="d.key"
          :ref="el => setDimRef(d.key, el)"
          class="pa-dim"
          :class="{ 'is-empty': !d.hasContent }"
        >
          <header class="pa-dim-head">
            <span class="pa-dim-no">{{ d.no }}</span>
            <span class="pa-dim-title">{{ d.title }}</span>
            <span class="pa-dim-meta" :title="KIND_NOTES[d.kind] || ''">{{ d.shards }} · {{ d.kind }}</span>
          </header>
          <div class="pa-dim-body">
            <!-- V2.0：改用公共 DefinitionList（标签定宽右对齐 + 值列对齐 + 长值阅读宽度/CJK 40 字/行 +
                 超长值折叠）。此前这里是本页自写的 .pa-line 行，与角色概览的标签值网格、
                 其它页的定义行各写一套，导致同类数据在不同页面的对齐与行长不一致。 -->
            <DefinitionList
              v-if="d.hasContent"
              :items="d.rows"
              :label-width="compact ? '56px' : '88px'"
              prose
              :clamp-lines="8"
            />
            <div v-else class="pa-empty">{{ DIM_EMPTY_TEXT }}</div>

            <!-- 降级：服务端渲染文本缺失时，给出该维档案原始 JSON（不做前端二次渲染，避免与后端渲染规则漂移） -->
            <details v-if="!view.hasRenderText && d.rawDimJson" class="pa-raw-dim">
              <summary>该维原始 JSON</summary>
              <pre class="pa-pre">{{ d.rawDimJson }}</pre>
            </details>
          </div>
        </section>
      </div>

      <!-- 原始 JSON 折叠面板（便于核对；默认折叠） -->
      <el-collapse v-if="view.rawJson" class="pa-collapse">
        <el-collapse-item name="raw">
          <template #title>
            <span class="pa-collapse-title">原始 JSON（persona_json · schema v{{ view.meta.schemaVersion ?? 2 }} 的 11 维档案原文）</span>
          </template>
          <pre class="pa-pre pa-pre-raw">{{ view.rawJson }}</pre>
        </el-collapse-item>
      </el-collapse>
    </template>
  </div>
</template>

<script setup>
/**
 * 角色档案（11 维）只读展示面板。
 * <p><b>职责</b>：把后端 {@code CharacterCardVO} 渲染成"角色名 + 版本 + 来源 + 更新时间 + 11 维分区"的只读视图。
 * 文案与数据全部来自服务端 {@code personaRenderText}（{@code PersonaService.renderFull}）——
 * 本组件只按 {@code 【维名】} 标记切分并排版，<b>不重新实现任何字段渲染规则</b>（契约 §6：渲染规则只有一份实现）。</p>
 * <p><b>只读</b>：不提供任何编辑入口。空维显示"（该维暂无内容）"，不留白。</p>
 * <p><b>双前端复用</b>：主前端与调试前端各持一份同源镜像（两个 Vite 工程各自独立构建，
 * 沿用既有 {@code EChart.vue} 在两侧各存一份的工程惯例；逻辑层 {@code personaArchive.js} 同样镜像）。</p>
 */
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
// V2.0：维内「标签 → 值」行改用公共定义列表组件（统一对齐与阅读宽度）
import DefinitionList from '@/shared/components/DefinitionList.vue'
// V2.0 批次二：空态统一走 EmptyState
import EmptyState from '@/shared/components/EmptyState.vue'
import {
  DIM_EMPTY_TEXT,
  buildArchive,
  fmtDateTime,
  sceneLabel,
  sourceLabel,
  splitDimLine
} from '../personaArchive'

const props = defineProps({
  /** 档案 VO（CharacterCardVO；null=该角色尚未生成档案） */
  card: { type: Object, default: null },
  /** 角色姓名兜底（VO.characterName 为空时用列表里的姓名） */
  characterName: { type: String, default: '' },
  /** 紧凑模式（版本历史对话框等窄容器内使用） */
  compact: { type: Boolean, default: false }
})

/** 来源类型说明（悬停提示：让人看出"这一维权威在谁那里"，对齐契约 §2 总表） */
const KIND_NOTES = {
  PERSONA: '该维全部字段落档案（persona_json）',
  MODULE: '该维权威在既有模块（如 ③ 生理基线的 actor_state），档案不存第二份，渲染时投影补齐',
  MIXED: '该维主体落既有模块，仅无归属字段落档案'
}

/** 11 维展示结构（服务端渲染文本按维切分 + 空维标记） */
const view = computed(() => {
  const archive = buildArchive(props.card)
  return {
    ...archive,
    dims: archive.dims.map(d => ({ ...d, rows: d.lines.map(splitDimLine) }))
  }
})

const displayName = computed(() => view.value.meta.characterName || props.characterName || '未命名角色')
const sceneText = computed(() => sceneLabel(view.value.meta.scene))

/** 维分区 DOM 引用（维索引点击滚动用） */
const dimRefs = ref({})
function setDimRef(key, el) {
  if (el) dimRefs.value[key] = el
}

/** 点击维索引滚动到该维分区 */
function scrollToDim(key) {
  const el = dimRefs.value[key]
  if (el && typeof el.scrollIntoView === 'function') {
    el.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  }
}

/** 复制完整 11 维渲染文本（只读核对用） */
async function copyRenderText() {
  const text = props.card?.personaRenderText || ''
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制 11 维渲染文本')
  } catch (_) {
    ElMessage.info('复制失败，请在「原始 JSON」面板中手动选择')
  }
}
</script>

<style scoped>
.pa-panel { display: flex; flex-direction: column; gap: 12px; }

/* ===== 顶部概览 ===== */
.pa-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.pa-head-main { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.pa-name { font-size: 16px; font-weight: 700; color: var(--text-primary); }
.pa-head-side { display: flex; align-items: center; gap: 10px; }
.pa-time { font-size: 12px; color: var(--text-tertiary); font-family: var(--font-mono); }
.pa-alert { margin: 0; }

/* ===== 维索引 ===== */
.pa-index { display: flex; flex-wrap: wrap; gap: 6px; }
.pa-index-item {
  cursor: pointer; font-size: 12px; line-height: 1.4; padding: 3px 10px;
  color: var(--text-secondary); background: var(--bg-layer-2);
  border: 1px solid var(--border-l1); border-radius: 999px;
  transition: color .2s var(--dsw-ease), border-color .2s var(--dsw-ease), background-color .2s var(--dsw-ease);
}
.pa-index-item:hover { color: var(--accent-text); border-color: var(--accent-border); background: var(--accent-softer); }
.pa-index-item.is-empty { color: var(--text-placeholder); }

/* ===== 11 维分区 ===== */
.pa-dims { display: grid; grid-template-columns: repeat(auto-fill, minmax(360px, 1fr)); gap: 12px; align-items: start; }
.pa-dim {
  border: 1px solid var(--border-l1); border-radius: var(--radius-md);
  background: var(--bg-layer-1); box-shadow: var(--shadow-lv1); overflow: hidden;
}
.pa-dim.is-empty { background: var(--bg-layer-2); box-shadow: none; }
.pa-dim-head {
  display: flex; align-items: center; gap: 8px; padding: 8px 12px;
  border-bottom: 1px solid var(--border-l1); background: var(--accent-softer);
}
.pa-dim-no { font-size: 13px; font-weight: 700; color: var(--accent-text); }
.pa-dim-title { font-size: 13px; font-weight: 700; color: var(--text-primary); }
.pa-dim-meta { margin-left: auto; font-size: 11px; color: var(--text-tertiary); font-family: var(--font-mono); cursor: help; }
.pa-dim-body { padding: 8px 12px 10px; display: flex; flex-direction: column; gap: 5px; }

.pa-line { display: flex; gap: 8px; font-size: 12.5px; line-height: 1.75; }
.pa-line-label {
  flex: 0 0 auto; min-width: 62px; color: var(--text-tertiary);
  font-size: 12px; padding-top: 1px;
}
.pa-line-value { flex: 1; color: var(--text-regular); word-break: break-word; }
.pa-empty { font-size: 12px; color: var(--text-placeholder); padding: 2px 0; }

/* ===== 原始 JSON ===== */
.pa-collapse { border-top: 1px solid var(--border-l1); }
.pa-collapse-title { font-size: 12.5px; color: var(--text-secondary); }
.pa-pre {
  margin: 0; padding: 10px 12px; background: var(--bg-base);
  border: 1px dashed var(--border-l1); border-radius: var(--radius-sm);
  font-family: var(--font-mono); font-size: 12px; line-height: 1.65;
  color: var(--text-regular); white-space: pre-wrap; word-break: break-word;
  max-height: 420px; overflow: auto;
}
.pa-pre-raw { max-height: 520px; }
.pa-raw-dim { margin-top: 4px; }
.pa-raw-dim summary { cursor: pointer; font-size: 11.5px; color: var(--text-tertiary); margin-bottom: 4px; }
.pa-raw-dim .pa-pre { max-height: 240px; }

/* ===== 紧凑模式（版本历史对话框等窄容器） ===== */
.is-compact .pa-dims { grid-template-columns: 1fr; gap: 8px; }
.is-compact .pa-name { font-size: 14px; }
.is-compact .pa-dim-head { padding: 6px 10px; }
.is-compact .pa-dim-body { padding: 6px 10px 8px; }
.is-compact .pa-line { font-size: 12px; line-height: 1.7; }
.is-compact .pa-line-label { min-width: 56px; font-size: 11.5px; }
</style>