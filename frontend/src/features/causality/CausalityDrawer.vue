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
  <el-drawer
    :model-value="modelValue"
    size="560px"
    :with-header="false"
    class="cz-drawer"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <div class="cz-head">
      <div>
        <div class="cz-eyebrow">WORLD CAUSALITY</div>
        <h2 class="cz-title">这件事，为什么会发生</h2>
      </div>
      <el-button text size="small" @click="$emit('update:modelValue', false)">关闭</el-button>
    </div>

    <div v-if="loading" class="cz-state">正在梳理因果…</div>
    <div v-else-if="error" class="cz-state is-error">
      <div>{{ error }}</div>
      <el-button size="small" text type="primary" @click="reload">重试</el-button>
    </div>

    <template v-else-if="data && data.event">
      <!-- 当前事件 -->
      <section class="cz-current">
        <div class="cz-row">
          <el-tag size="small" effect="plain">{{ kindLabel(data.event.kind) }}</el-tag>
          <span class="cz-time">{{ eventTime(data.event) }}</span>
          <el-tag v-if="data.event.importance != null" size="small" effect="light" :type="impTag(data.event.importance)">
            重要度 {{ data.event.importance }}
          </el-tag>
        </div>
        <h3 class="cz-current-title">{{ data.event.title || '（无标题事件）' }}</h3>
        <p v-if="data.event.content" class="cz-current-text">{{ data.event.content }}</p>
        <div class="cz-meta">
          <span v-if="data.event.locationName">地点：{{ data.event.locationName }}</span>
          <span v-if="data.event.source">来源：{{ data.event.source }}</span>
          <span class="cz-mono">#{{ data.event.id }}</span>
        </div>
      </section>

      <el-alert v-if="data.cycle" type="warning" :closable="false" show-icon
        title="检测到因果环：这条链在某处绕回了自己，已停止继续回溯。" class="cz-alert" />

      <!-- 父链 -->
      <section class="cz-section">
        <div class="cz-section-title">
          前因
          <span class="cz-dim">{{ data.ancestors?.length || 0 }} 层</span>
        </div>
        <div v-if="!data.ancestors?.length" class="cz-empty">没有更早的父事件（这是因果链的起点）。</div>
        <ol v-else class="cz-chain">
          <li v-for="a in data.ancestors" :key="a.id" class="cz-node" @click="openEvent(a.id)">
            <span class="cz-node-dot"></span>
            <div class="cz-node-body">
              <div class="cz-node-top">
                <el-tag size="small" effect="plain">{{ kindLabel(a.kind) }}</el-tag>
                <span class="cz-time">{{ eventTime(a) }}</span>
              </div>
              <div class="cz-node-title">{{ a.title || '（无标题事件）' }}</div>
              <div v-if="a.content" class="cz-node-text">{{ a.content }}</div>
            </div>
            <span class="cz-arrow">→</span>
          </li>
        </ol>
        <div v-if="data.truncatedUp" class="cz-note">上游还有因果，但父事件已不在当前世界（或超过回溯上限）。</div>
      </section>

      <!-- 直接后果 -->
      <section class="cz-section">
        <div class="cz-section-title">
          直接后果
          <span class="cz-dim">{{ data.children?.length || 0 }} 条</span>
        </div>
        <div v-if="!data.children?.length" class="cz-empty">这件事暂时没有直接引发其他事件。</div>
        <div v-else class="cz-children">
          <button v-for="c in data.children" :key="c.id" type="button" class="cz-child" @click="openEvent(c.id)">
            <span class="cz-child-main">
              <el-tag size="small" effect="plain">{{ kindLabel(c.kind) }}</el-tag>
              <span class="cz-child-title">{{ c.title || '（无标题事件）' }}</span>
            </span>
            <span class="cz-child-sub">{{ eventTime(c) }}<template v-if="c.locationName"> · {{ c.locationName }}</template></span>
          </button>
        </div>
        <div v-if="data.truncatedDown" class="cz-note">还有更多直接后果，未全部展开（单次最多显示 50 条）。</div>
      </section>

      <div class="cz-foot">
        因果来自事件总线：父链沿 causeEventId 回溯，子事件按 causeEventId 反查；点击任一条可继续展开。
      </div>
    </template>
  </el-drawer>
</template>

<script setup>
/**
 * 因果抽屉。
 *
 * <p>只读：打开时按 eventId 取「当前事件 + 父链 + 一层直接子事件」，点击父/子节点继续
 * 以该节点为当前事件懒加载。父链 root-first，子事件单次上限 50；断链/自环由后端标记，
 * 前端只负责如实说明，不伪造不存在的父事件。</p>
 */
import { ref, watch } from 'vue'
import { fetchEventCausality } from '@/shared/api/world'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  projectId: { type: Number, required: true },
  eventId: { type: [Number, String], default: null }
})
defineEmits(['update:modelValue'])

const loading = ref(false)
const error = ref('')
const data = ref(null)
let seq = 0

watch(() => [props.modelValue, props.eventId], ([open, id]) => {
  if (open && id != null) load(Number(id))
}, { immediate: true })

function reload() {
  if (props.eventId != null) load(Number(props.eventId))
}

async function load(id) {
  const token = ++seq
  loading.value = true
  error.value = ''
  try {
    const res = await fetchEventCausality(props.projectId, id, { upDepth: 6, childLimit: 20 })
    if (token !== seq) return
    data.value = res
  } catch (e) {
    if (token !== seq) return
    data.value = null
    error.value = e?.message || '因果链加载失败'
  } finally {
    if (token === seq) loading.value = false
  }
}

function openEvent(id) {
  if (id != null) load(Number(id))
}

const KIND_LABEL = {
  action: '行动', dialog: '对话', fact: '事实', world_frame: '世界帧', weather: '天气',
  relation: '关系', roleplay: '角色扮演', event: '事件', world: '世界演化',
  birth: '出生', death: '死亡', evolution: '演化', crowd: '人群', memory: '记忆'
}
function kindLabel(kind) { return KIND_LABEL[kind] || kind || '事件' }
function impTag(i) { return i >= 4 ? 'danger' : i === 3 ? 'warning' : 'info' }
function eventTime(e) {
  if (!e) return '—'
  if (e.gameTimeText) return e.gameTimeText
  if (e.gameHour != null) {
    const day = Math.floor(Number(e.gameHour) / 24) + 1
    return `第 ${day} 天 ${String(Number(e.gameHour) % 24).padStart(2, '0')}:00`
  }
  return '—'
}
</script>

<style scoped>
.cz-drawer :deep(.el-drawer__body) { padding: 0; overflow-y: auto; }
.cz-head {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 12px;
  padding: 20px 22px 14px; border-bottom: 1px solid var(--border-l1, #e5e7eb);
  position: sticky; top: 0; z-index: 2; background: var(--bg-layer-1, #fff);
}
.cz-eyebrow { font-size: 10px; letter-spacing: .16em; color: var(--text-tertiary, #9ca3af); }
.cz-title { margin: 4px 0 0; font-size: 18px; font-weight: 700; color: var(--text-primary, #111827); }
.cz-state { padding: 48px 22px; text-align: center; color: var(--text-secondary, #6b7280); font-size: 13px; }
.cz-state.is-error { color: var(--state-danger, #dc2626); display: flex; flex-direction: column; gap: 8px; align-items: center; }
.cz-current { margin: 18px 22px 0; padding: 14px 16px; border: 1px solid var(--border-l1, #e5e7eb); border-radius: 10px; background: var(--bg-layer-1, #fff); }
.cz-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.cz-time { font-size: 11px; color: var(--text-tertiary, #9ca3af); font-variant-numeric: tabular-nums; }
.cz-current-title { margin: 10px 0 6px; font-size: 16px; line-height: 1.45; color: var(--text-primary, #111827); }
.cz-current-text { margin: 0; font-size: 13px; line-height: 1.65; color: var(--text-regular, #374151); }
.cz-meta { display: flex; gap: 12px; flex-wrap: wrap; margin-top: 8px; font-size: 11.5px; color: var(--text-secondary, #6b7280); }
.cz-mono { font-family: ui-monospace, Consolas, monospace; }
.cz-alert { margin: 12px 22px 0; }
.cz-section { margin: 20px 22px 0; }
.cz-section-title { display: flex; align-items: baseline; gap: 8px; font-size: 13px; font-weight: 700; color: var(--text-primary, #111827); margin-bottom: 8px; }
.cz-dim { font-size: 11px; font-weight: 400; color: var(--text-tertiary, #9ca3af); }
.cz-empty { font-size: 12px; color: var(--text-tertiary, #9ca3af); padding: 8px 0; }
.cz-chain { list-style: none; margin: 0; padding: 0; }
.cz-node { position: relative; display: flex; gap: 10px; padding: 8px 8px 8px 0; border-radius: 8px; cursor: pointer; }
.cz-node:hover, .cz-child:hover { background: var(--bg-hover, #f9fafb); }
.cz-node-dot { width: 7px; height: 7px; margin-top: 7px; border-radius: 50%; background: var(--color-primary, #2563eb); flex: none; }
.cz-node-body { flex: 1; min-width: 0; }
.cz-node-top { display: flex; align-items: center; gap: 8px; }
.cz-node-title { margin-top: 4px; font-size: 13px; line-height: 1.5; color: var(--text-primary, #111827); }
.cz-node-text { margin-top: 2px; font-size: 12px; line-height: 1.55; color: var(--text-secondary, #6b7280); }
.cz-arrow { align-self: center; color: var(--text-tertiary, #9ca3af); font-size: 13px; }
.cz-note { margin-top: 8px; font-size: 11.5px; line-height: 1.55; color: var(--state-warn, #d97706); }
.cz-children { display: flex; flex-direction: column; gap: 4px; }
.cz-child { display: flex; flex-direction: column; align-items: flex-start; gap: 3px; width: 100%; padding: 8px 10px; border: 0; border-radius: 8px; background: transparent; text-align: left; cursor: pointer; }
.cz-child-main { display: flex; align-items: center; gap: 8px; max-width: 100%; }
.cz-child-title { font-size: 13px; color: var(--text-primary, #111827); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cz-child-sub { font-size: 11px; color: var(--text-tertiary, #9ca3af); }
.cz-foot { margin: 22px; padding-top: 12px; border-top: 1px dashed var(--border-l1, #e5e7eb); font-size: 11px; line-height: 1.6; color: var(--text-tertiary, #9ca3af); }
</style>
