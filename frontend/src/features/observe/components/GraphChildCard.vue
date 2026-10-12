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
    并置面板内容：在「地点详情 / 角色详情」旁边再开一栏时，显示被点开的关联对象。
    本组件**只渲染 + 用面板上下文存自己的展开态**：数据仍由页面拉取并 props 传入
    （同一份数据不在各面板各拉一次）。
  -->
  <div class="gc" :data-child-kind="target?.kind || ''">
    <div v-loading="loading" class="gc-body">
      <div class="gc-head">
        <span class="gc-title">{{ title }}</span>
        <el-tag v-if="typeText" size="small" effect="plain">{{ typeText }}</el-tag>
      </div>
      <div v-if="!target" class="gc-empty">点开左侧详情里的关联对象，即在此并置查看（不影响左侧选中）。</div>
      <template v-else>
        <div v-if="metaRows.length" class="gc-meta">
          <div v-for="m in metaRows" :key="m.label" class="gc-meta-row">
            <span class="gc-meta-label">{{ m.label }}</span>
            <span class="gc-meta-value">{{ m.value }}</span>
          </div>
        </div>

        <div class="gc-sections">
          <button
            v-for="s in sections"
            :key="s.key"
            type="button"
            class="gc-sec-btn"
            :class="{ 'is-on': section === s.key }"
            :data-section="s.key"
            @click="setSection(s.key)"
          >{{ s.label }}（{{ s.count }}）</button>
        </div>

        <div v-if="section === 'relations'" class="gc-list">
          <div v-for="(r, i) in relations.slice(0, 12)" :key="'r' + i" class="gc-row">
            <span class="gc-row-main">{{ r.targetName || r.sourceName || '—' }}</span>
            <span class="gc-row-sub">好感 {{ r.favor ?? '—' }} · 信任 {{ r.trust ?? '—' }}</span>
          </div>
          <div v-if="!relations.length" class="gc-dim">无关系记录</div>
        </div>

        <div v-else-if="section === 'factions'" class="gc-list">
          <div v-for="(f, i) in factions.slice(0, 12)" :key="'f' + i" class="gc-row">
            <span class="gc-row-main">{{ f.role || '成员' }}</span>
            <span class="gc-row-sub">{{ f.factionId != null ? '势力 #' + f.factionId : '' }}</span>
          </div>
          <div v-if="!factions.length" class="gc-dim">无所属组织</div>
        </div>

        <div v-else class="gc-list">
          <div v-for="(e, i) in events.slice(0, 12)" :key="'e' + i" class="gc-row">
            <el-tag size="small" effect="plain">{{ kindText(e.kind) }}</el-tag>
            <span class="gc-row-main">{{ e.title }}</span>
            <span class="gc-row-sub">{{ e.gameHour != null ? '第 ' + Math.floor(e.gameHour / 24) + ' 天' : '' }}</span>
          </div>
          <div v-if="!events.length" class="gc-dim">无参与事件</div>
        </div>

        <div class="gc-foot">
          <el-button v-if="target.kind === 'character'" size="small" text type="primary" @click="$emit('open-tab', 'characters', target.id)">
            在「角色」切面打开
          </el-button>
          <el-button v-else size="small" text type="primary" @click="$emit('open-tab', 'factions', target.id)">
            在「势力」切面打开
          </el-button>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
/**
 * 并置「关联对象」面板（世界图谱页的第三栏）。
 *
 * <p>存在价值：上一版点开地点详情里的角色 chip 会**整页切到角色切面**，用户随即丢失正在看的地点；
 * 本面板让「保留左栏上下文」与「深入看关联对象」同时成立——这正是可并置多面板工作区要解决的问题。</p>
 *
 * <p>**上下文隔离的落点**：本组件用 {@link usePanelContext} 存自己「当前展开的分区」
 * （关系/组织/事件）。并置两栏时，两栏各自有一份 `state.section`，点一栏不会翻另一栏——
 * 这正是页面级 `ref` 做不到的（那是「可并置」必须先抽面板上下文的原因）。</p>
 */
import { computed } from 'vue'
import { usePanelContext } from '@/shared/layout/usePanelContext'

const props = defineProps({
  /** 被点开的目标 `{ kind: 'character'|'faction', id, name }` */
  target: { type: Object, default: null },
  /** 角色视角数据（`/characters/{id}/view`） */
  characterView: { type: Object, default: null },
  /** 势力视角数据（`/factions/{id}/view`） */
  factionView: { type: Object, default: null },
  /** 加载中 */
  loading: { type: Boolean, default: false }
})
defineEmits(['open-tab'])

const panel = usePanelContext({ paneKey: 'child', initial: { section: 'relations' } })

/** 当前分区（存本面板上下文，不是页面级 state） */
const section = computed(() => panel.state.section || 'relations')

function setSection(key) {
  panel.set('section', key)
}

const character = computed(() => props.characterView?.character || null)
const faction = computed(() => props.factionView?.faction || null)

const title = computed(() => {
  if (!props.target) return '关联对象'
  return props.target.kind === 'character'
    ? (character.value?.name || props.target.name || '角色')
    : (faction.value?.name || props.target.name || '势力')
})

const typeText = computed(() => {
  if (!props.target) return ''
  if (props.target.kind === 'character') {
    const t = character.value?.actorType
    return t === 'special' ? '特殊 NPC' : (t === 'ordinary' ? '普通 NPC' : (t || '角色'))
  }
  return faction.value?.type || '势力'
})

const relations = computed(() => props.characterView?.relations || [])
const factions = computed(() => props.characterView?.factions || [])
const events = computed(() => props.characterView?.events || [])

const metaRows = computed(() => {
  if (!props.target) return []
  if (props.target.kind === 'character') {
    const c = character.value
    const loc = props.characterView?.location
    return [
      { label: '所在位置', value: loc?.name || '—' },
      { label: '重要度', value: c?.importance ?? '—' },
      { label: '关系数', value: relations.value.length },
      { label: '所属组织', value: factions.value.length }
    ]
  }
  const f = faction.value
  return [
    { label: '类型', value: f?.type || '—' },
    { label: '成员数', value: props.factionView?.members?.length ?? 0 },
    { label: '组织关系', value: props.factionView?.relations?.length ?? 0 }
  ]
})

const sections = computed(() => {
  if (!props.target) return []
  if (props.target.kind === 'character') {
    return [
      { key: 'relations', label: '关系', count: relations.value.length },
      { key: 'factions', label: '组织', count: factions.value.length },
      { key: 'events', label: '事件', count: events.value.length }
    ]
  }
  return []
})

/** 事件类型中文名（与图谱页同口径；未知类型原样显示） */
function kindText(kind) {
  const map = { relation: '关系变化', roleplay: '角色扮演', action: '角色行动', event: '世界事件', world: '世界演化' }
  return map[kind] || kind || '事件'
}
</script>

<style scoped>
.gc {
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
}
.gc-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
}
.gc-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.gc-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.gc-empty {
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.7;
}
.gc-meta {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--bg-layer-1);
}
.gc-meta-row {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  font-size: 12px;
}
.gc-meta-label {
  color: var(--text-secondary);
}
.gc-meta-value {
  color: var(--text-primary);
  font-variant-numeric: tabular-nums;
}
.gc-sections {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.gc-sec-btn {
  border: 1px solid var(--border-l1);
  background: transparent;
  color: var(--text-regular);
  border-radius: 6px;
  padding: 3px 8px;
  font-size: 12px;
  cursor: pointer;
  font-family: inherit;
}
.gc-sec-btn:hover {
  background: var(--bg-hover);
}
.gc-sec-btn.is-on {
  border-color: var(--color-primary, var(--el-color-primary));
  color: var(--color-primary, var(--el-color-primary));
}
.gc-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: min(420px, 46vh);
  overflow-y: auto;
}
.gc-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  padding: 3px 0;
  border-bottom: 1px dashed var(--border-l1);
}
.gc-row-main {
  flex: 1;
  min-width: 0;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.gc-row-sub {
  color: var(--text-secondary);
  flex: none;
  font-variant-numeric: tabular-nums;
}
.gc-dim {
  font-size: 12px;
  color: var(--text-secondary);
}
.gc-foot {
  padding-top: 4px;
  border-top: 1px solid var(--border-l1);
}
</style>
