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
  <aside class="dialogue-session-list">
    <!-- 头部：新建对谈 -->
    <div class="dsl-header">
      <span class="dsl-title">对谈会话</span>
      <el-button size="small" type="primary" text @click="$emit('create')">
        <el-icon><HIcon name="Plus" /></el-icon>&nbsp;新建
      </el-button>
    </div>

    <!-- 搜索 -->
    <el-input
      v-model="keyword"
      size="small"
      clearable
      placeholder="搜索标题 / 角色名 / 地点"
      class="dsl-search"
    >
      <template #prefix><el-icon><HIcon name="Search" /></el-icon></template>
    </el-input>

    <!-- 会话列表（按角色分组；记忆态=落盘） -->
    <!-- V2.0（§5.6 空态规范）：侧栏**不放独立空态卡**——同屏只能有 1 个主体空态（中栏那张
         「与角色对谈」教学卡），侧栏无内容时只留一行提示；这里也不重复「+ 新建」CTA
         （标题栏已有），避免"同一屏三个新建入口"。 -->
    <EmptyState
      v-if="props.sessions.length === 0"
      size="panel"
      title="还没有对谈会话"
    />
    <EmptyState
      v-else-if="filteredGroups.length === 0"
      size="panel"
      title="没有匹配的会话"
    />
    <div v-else class="dsl-groups">
      <div v-for="g in filteredGroups" :key="g.key" class="dsl-group">
        <div class="dsl-group-head">
          <el-avatar v-if="g.characterName" :size="18" class="dsl-group-avatar">{{ g.characterName.charAt(0) }}</el-avatar>
          <span class="dsl-group-name">{{ g.characterName || '群聊' }}</span>
          <span class="dsl-group-count">{{ g.sessions.length }}<template v-if="g.sessions.length > 1"> 分支</template></span>
          <button
            type="button"
            class="dsl-branch-plus"
            :title="g.type === 'group' ? '新建群聊' : '与' + (g.characterName || '该角色') + '新建分支会话'"
            @click="$emit('create-branch', g)"
          >+</button>
        </div>
        <div
          v-for="s in g.sessions"
          :key="s.id"
          class="dsl-item"
          :class="{ active: s.id === currentId }"
          @click="$emit('select', s)"
        >
          <div class="dsl-item-main">
            <div class="dsl-item-title" :title="s.title || '未命名对谈'">{{ s.title || '未命名对谈' }}</div>
            <div class="dsl-item-sub">
              <span v-if="s.messageCount != null">共 {{ s.messageCount }} 条</span>
              <span v-if="s.updatedAt">· {{ fmtTime(s.updatedAt) }}</span>
            </div>
          </div>
          <div class="dsl-item-ops" @click.stop>
            <el-button size="small" text type="primary" title="重命名" @click="$emit('rename', s)">
              <el-icon><HIcon name="Edit" /></el-icon>
            </el-button>
            <el-button size="small" text type="danger" title="关闭会话" @click="$emit('close', s)">
              <el-icon><HIcon name="Delete" /></el-icon>
            </el-button>
          </div>
        </div>
      </div>
    </div>
  </aside>
</template>

<script setup>
/**
 * 对谈会话列表（左栏）。
 * <p>职责：会话的搜索 / 按角色分组展示（分支）/ 选择 / 重命名 / 关闭；只做展示与转发事件，
 * 数据与操作由 DialogueView 统一持有。</p>
 * <p>分组语义：single 会话按 characterName 分组（同一角色多个会话=分支，组头「+」新建分支）；
 * group（M5）归入「群聊」。搜索匹配标题/角色名/群聊地点名（M6 打磨）。</p>
 */
import { computed, ref } from 'vue'
import EmptyState from '@/shared/components/EmptyState.vue'

const props = defineProps({
  /** 会话列表（DialogueSessionVO[]） */
  sessions: { type: Array, default: () => [] },
  /** 当前选中会话 ID */
  currentId: { type: [Number, null], default: null }
})
defineEmits(['create', 'create-branch', 'select', 'rename', 'close'])

const keyword = ref('')

/** 时间简写（MM-DD HH:mm） */
function fmtTime(v) {
  if (!v) return ''
  const d = new Date(v)
  const p = (n) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/** 会话是否命中搜索关键字（标题/角色名/群聊地点名，大小写不敏感）。 */
function matches(s, kw) {
  if ((s.title || '').toLowerCase().includes(kw)) return true
  if ((s.characterName || '').toLowerCase().includes(kw)) return true
  if ((s.locationName || '').toLowerCase().includes(kw)) return true
  if (s.type === 'group' && '群聊'.includes(kw)) return true
  return false
}

/** 搜索过滤后的会话分组（按角色名分组，组内按 updatedAt 倒序；组头携带分支新建信息） */
const filteredGroups = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  const filtered = kw ? props.sessions.filter(s => matches(s, kw)) : props.sessions
  const map = new Map()
  for (const s of filtered) {
    const key = s.characterName || '群聊'
    if (!map.has(key)) map.set(key, [])
    map.get(key).push(s)
  }
  return [...map.entries()].map(([name, list]) => ({
    key: name,
    characterName: name,
    type: list[0] && list[0].type === 'group' ? 'group' : 'single',
    characterRef: list[0] && list[0].type === 'group' ? null : (list[0] && list[0].characterRef) || null,
    sessions: [...list].sort((a, b) => new Date(b.updatedAt) - new Date(a.updatedAt))
  }))
})
</script>

<style scoped>
.dialogue-session-list {
  width: var(--page-aside-w, 260px);
  background: var(--bg-layer-1);
  border: 1px solid var(--border-light);
  border-radius: var(--radius-lg);
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
  overflow: hidden;
  flex-shrink: 0;
}
.dsl-header { display: flex; align-items: center; justify-content: space-between; padding: 2px 4px; }
.dsl-title { font-weight: 700; font-size: 0.9rem; color: var(--text-primary); }
.dsl-search { flex-shrink: 0; }
.dsl-groups { flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 10px; }
.dsl-empty { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; color: var(--text-secondary); font-size: 0.82rem; text-align: center; padding: 12px; }
.dsl-empty-sub { color: var(--text-tertiary); font-size: 0.72rem; margin-top: 6px; }
.dsl-group-head { display: flex; align-items: center; gap: 6px; padding: 2px 4px 4px; color: var(--text-secondary); font-size: 0.76rem; font-weight: 600; }
.dsl-group-avatar { font-size: 0.6rem; }
.dsl-group-count { margin-left: auto; color: var(--text-tertiary); font-size: 0.7rem; }
.dsl-branch-plus {
  border: 1px solid var(--border-l2); background: var(--bg-layer-1); cursor: pointer;
  color: var(--text-tertiary); font-size: 0.8rem; line-height: 1;
  width: 16px; height: 16px; border-radius: 4px; display: inline-flex;
  align-items: center; justify-content: center; padding: 0;
}
.dsl-branch-plus:hover { color: var(--state-primary); border-color: color-mix(in srgb, var(--state-primary) 50%, transparent); }
.dsl-item {
  display: flex; align-items: center; gap: 6px;
  padding: 7px 8px; border-radius: 8px; cursor: pointer;
  border: 1px solid transparent; transition: all 0.15s;
}
.dsl-item:hover { background: var(--bg-layer-2); }
.dsl-item.active { background: color-mix(in srgb, var(--state-primary) 12%, transparent); border-color: color-mix(in srgb, var(--state-primary) 40%, transparent); }
.dsl-item-main { flex: 1; min-width: 0; }
.dsl-item-title { font-size: 0.82rem; color: var(--text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.dsl-item-sub { font-size: 0.68rem; color: var(--text-tertiary); margin-top: 2px; }
.dsl-item-ops { display: none; align-items: center; flex-shrink: 0; }
.dsl-item:hover .dsl-item-ops, .dsl-item.active .dsl-item-ops { display: inline-flex; }
</style>
