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
    命令面板（Ctrl+K）：全局唯一的「键盘驱动入口」。
    聚合三类命令：① 骨架系统命令（导航/主题/密度/抽屉，由 AppShell 注册）；
    ② 各业务页在 onMounted 注册的命令；③ 批次③ 接入的全局搜索结果（角色/记忆/事件）。
    与酒馆的关系：酒馆没有命令面板（只有分散的快捷键），这是本项目为「页面多、功能深」所做的增强。
  -->
  <teleport to="body">
    <transition name="cmdk-fade">
      <div v-if="visible" class="cmdk-mask" @click.self="close">
        <div class="cmdk" role="dialog" aria-label="命令面板">
          <div class="cmdk-inputrow">
            <el-icon :size="16"><HIcon name="Search" /></el-icon>
            <input
              ref="inputRef"
              v-model="keyword"
              class="cmdk-input"
              placeholder="输入以搜索页面、动作与命令…"
              @keydown.down.prevent="move(1)"
              @keydown.up.prevent="move(-1)"
              @keydown.enter.prevent="runActive"
              @keydown.esc.stop.prevent="close"
            />
            <kbd class="cmdk-kbd">Esc</kbd>
          </div>

          <div v-if="searching" class="cmdk-searching">正在搜索角色 / 地点 / 事件 / 知识…</div>

          <div v-if="!allItems.length" class="cmdk-empty">
            没有匹配的命令与结果。<span class="cmdk-empty-hint">试试「世界」「角色」「密度」「主题」。</span>
          </div>

          <div v-else class="cmdk-list">
            <template v-for="grp in grouped" :key="grp.name">
              <div class="cmdk-group">{{ grp.name }}</div>
              <button
                v-for="item in grp.items"
                :key="item.id"
                class="cmdk-item"
                :class="{ active: item.id === activeId, 'is-search': !!item.navRoute }"
                @mouseenter="activeId = item.id"
                @click="run(item)"
              >
                <el-icon class="cmdk-item-icon" :size="15"><HIcon :name="item.icon || 'ic_public_file'" /></el-icon>
                <SpotlightPill class="cmdk-item-title" :text="item.title" :keyword="keyword" />
                <SpotlightPill v-if="item.hint" class="cmdk-item-hint" :text="item.hint" :keyword="keyword" :max-length="72" />
                <kbd v-if="item.hotkey" class="cmdk-kbd">{{ item.hotkey }}</kbd>
              </button>
            </template>
          </div>

          <div v-if="searchError" class="cmdk-search-error">搜索服务暂不可用（仍可使用上方命令）</div>

          <div class="cmdk-foot">
            <span><kbd class="cmdk-kbd">↑</kbd><kbd class="cmdk-kbd">↓</kbd> 选择</span>
            <span><kbd class="cmdk-kbd">Enter</kbd> 执行</span>
            <span><kbd class="cmdk-kbd">Esc</kbd> 关闭</span>
            <span class="cmdk-foot-right">{{ flat.length }} 条命令{{ searchItems.length ? ` · ${searchItems.length} 条结果` : '' }}</span>
          </div>
        </div>
      </div>
    </transition>
  </teleport>
</template>

<script setup>
/**
 * 命令面板组件。
 *
 * <p>职责：读 {@link filterCommands} 的过滤结果，做分组展示与键盘导航，并把执行委托给
 * {@link executeCommand}（统一 try/catch，单条命令失败不影响面板）。</p>
 *
 * <p>键盘契约：↓/↑ 移动（循环）、Enter 执行、Esc 关闭。所有按键都 `.prevent`，
 * 避免在页面其它元素上产生副作用（尤其 Esc 的分层关闭由 AppShell 统一处理，
 * 面板打开时优先被本组件消费）。</p>
 */
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useLayoutStore } from '@/shared/store/layout'
import { fetchGlobalSearch } from '@/shared/api'
import SpotlightPill from '@/shared/components/SpotlightPill.vue'
import { filterCommands, executeCommand } from './commandRegistry'

const layout = useLayoutStore()
const router = useRouter()
const route = useRoute()
const visible = computed(() => layout.commandOpen)

const keyword = ref('')
const activeId = ref('')
const inputRef = ref(null)

/** 过滤结果（keyword 为空时展示全部，最多 40 条） */
const flat = computed(() => filterCommands(keyword.value, 40))

/**
 * 搜索结果（服务端兜底：覆盖前端未加载的分页数据）。
 * <p>结果与命令**合并展示**：命令在前（用户多半在找入口），搜索命中在后并单独分组。
 * 键盘上下与 Enter 在合并列表上统一工作，避免「两套焦点」。</p>
 */
const searchGroups = ref([])
const searching = ref(false)
const searchError = ref(false)
/** 请求令牌：丢弃过期响应（快速连打时后到的旧响应不得覆盖新结果） */
let searchToken = 0
let searchTimer = null

/** 搜索结果 → 面板条目（图标按类型选，均取自已注册图标名，避免空白图标） */
const SEARCH_ICONS = {
  character: 'User',
  location: 'Location',
  event: 'Clock',
  knowledge: 'Reading',
  project: 'Document'
}

const searchItems = computed(() => searchGroups.value.flatMap(g => g.items))

/** 合并列表：命令在前、搜索在后（决定键盘移动顺序） */
const allItems = computed(() => [...flat.value, ...searchItems.value])

/** 按 group 聚类（保持打分顺序：组内顺序即相关度顺序） */
const grouped = computed(() => {
  const map = new Map()
  for (const c of allItems.value) {
    const g = c.group || '其它'
    if (!map.has(g)) map.set(g, [])
    map.get(g).push(c)
  }
  return [...map.entries()].map(([name, items]) => ({ name, items }))
})

/** 当前项目 ID（无项目上下文时不做服务端搜索——接口按项目聚合） */
const projectId = computed(() => {
  const m = route.path.match(/^\/world\/(\d+)/)
  return m ? m[1] : null
})

/**
 * 触发服务端搜索（250ms 防抖；关键词为空/过短/无项目则清空结果）。
 *
 * @param {string} q 关键词
 */
function scheduleSearch(q) {
  if (searchTimer) { clearTimeout(searchTimer); searchTimer = null }
  const kw = (q || '').trim()
  if (!kw || !projectId.value) {
    searchGroups.value = []
    searching.value = false
    searchError.value = false
    return
  }
  searching.value = true
  searchError.value = false
  searchTimer = setTimeout(async () => {
    const token = ++searchToken
    try {
      const res = await fetchGlobalSearch(projectId.value, { q: kw, limit: 16 })
      if (token !== searchToken) return
      searchGroups.value = (res?.groups || []).map(g => ({
        name: `搜索 · ${g.label}`,
        items: (g.items || []).map(h => ({
          id: `search:${h.type}:${h.id}`,
          title: h.title || '(无标题)',
          hint: [h.meta, h.snippet].filter(Boolean).join(' · ').slice(0, 60),
          icon: SEARCH_ICONS[h.type] || 'ic_public_file',
          group: `搜索 · ${g.label}`,
          navRoute: h.route
        }))
      }))
    } catch (_) {
      if (token !== searchToken) return
      searchGroups.value = []
      searchError.value = true
    } finally {
      if (token === searchToken) searching.value = false
    }
  }, 250)
}

/** 打开时：清空关键词、聚焦输入、默认选中第一条 */
watch(visible, async (open) => {
  if (!open) return
  keyword.value = ''
  activeId.value = ''
  searchGroups.value = []
  searching.value = false
  searchError.value = false
  await nextTick()
  inputRef.value?.focus?.()
  activeId.value = allItems.value[0]?.id || ''
})

/** 关键词变化：重置高亮到首条 + 触发服务端搜索 */
watch(keyword, (q) => {
  activeId.value = allItems.value[0]?.id || ''
  scheduleSearch(q)
})

/** 上下移动（越界循环） */
function move(delta) {
  const list = allItems.value
  if (!list.length) return
  const idx = list.findIndex(c => c.id === activeId.value)
  const next = (idx + delta + list.length) % list.length
  activeId.value = list[next].id
}

/** 执行当前高亮项 */
function runActive() {
  const cmd = allItems.value.find(c => c.id === activeId.value) || allItems.value[0]
  if (cmd) run(cmd)
}

/** 执行：搜索命中 → 路由跳转；命令 → 命令注册表执行（执行失败也关闭，避免面板卡住） */
function run(item) {
  close()
  if (item.navRoute) {
    router.push(item.navRoute)
    return
  }
  executeCommand(item.id)
}

function close() { layout.setCommandOpen(false) }
</script>

<style scoped>
.cmdk-mask {
  position: fixed; inset: 0;
  background: var(--bg-scrim);
  backdrop-filter: blur(2px);
  z-index: var(--z-command, 70);
  display: flex; justify-content: center; align-items: flex-start;
  padding-top: 12vh;
}
.cmdk {
  width: min(680px, 92vw);
  max-height: 66vh;
  display: flex; flex-direction: column;
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l2);
  border-radius: var(--radius-lg, 16px);
  box-shadow: var(--shadow-lv3);
  overflow: hidden;
}

.cmdk-inputrow {
  display: flex; align-items: center; gap: var(--space-2, 8px);
  padding: 12px 14px;
  border-bottom: 1px solid var(--border-l1);
  color: var(--text-secondary);
}
.cmdk-input {
  /* V2.0 a11y：不写裸 outline:none —— 命令面板是键盘主入口，焦点必须可见
     （global.css 的 input:focus-visible 提供内缩实心焦点环） */
  flex: 1; background: none; border: none;
  color: var(--text-primary);
  font-size: 1rem;
}
.cmdk-input::placeholder { color: var(--text-placeholder); }

.cmdk-list { flex: 1; min-height: 0; overflow-y: auto; padding: 6px; }
.cmdk-group {
  padding: 8px 10px 4px;
  font-size: 0.72rem; font-weight: 600; letter-spacing: .04em;
  color: var(--text-tertiary);
  text-transform: uppercase;
}
.cmdk-item {
  display: flex; align-items: center; gap: 10px;
  width: 100%;
  padding: 8px 10px;
  background: none; border: none;
  border-radius: var(--radius-sm, 8px);
  color: var(--text-regular);
  font-size: var(--density-font, 0.9rem);
  text-align: left;
  cursor: pointer;
}
.cmdk-item.active { background: var(--accent-soft); color: var(--accent-text); }
.cmdk-item-icon { flex-shrink: 0; }
.cmdk-item-title { flex-shrink: 0; }
.cmdk-item-hint {
  flex: 1; min-width: 0;
  font-size: 0.76rem; color: var(--text-tertiary);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.cmdk-item.active .cmdk-item-hint { color: var(--text-secondary); }

.cmdk-empty { padding: 28px 16px; text-align: center; color: var(--text-tertiary); font-size: 0.88rem; }
.cmdk-empty-hint { display: block; margin-top: 6px; font-size: 0.78rem; }
/* 搜索态：结果条目用左侧细条与命令条目区分 */
.cmdk-item.is-search .cmdk-item-title { font-weight: 500; }
.cmdk-item.is-search { border-left: 2px solid var(--accent-border); }
.cmdk-searching { padding: 8px 16px; font-size: 0.78rem; color: var(--text-tertiary); }
.cmdk-search-error { padding: 8px 16px; font-size: 0.76rem; color: var(--state-warn); }

.cmdk-foot {
  display: flex; align-items: center; gap: var(--space-3, 12px);
  padding: 8px 14px;
  border-top: 1px solid var(--border-l1);
  background: var(--bg-layer-2);
  font-size: 0.74rem; color: var(--text-tertiary);
}
.cmdk-foot-right { margin-left: auto; }

kbd.cmdk-kbd {
  font-family: var(--dsw-font-family-code);
  font-size: 0.7rem;
  padding: 1px 5px;
  margin-right: 3px;
  border: 1px solid var(--border-l2);
  border-bottom-width: 2px;
  border-radius: 4px;
  color: var(--text-tertiary);
}

/* 出入场：轻微上浮 + 淡入（125ms，与酒馆的 --animation-duration 同量级） */
.cmdk-fade-enter-active, .cmdk-fade-leave-active { transition: opacity .12s var(--dsw-ease); }
.cmdk-fade-enter-from, .cmdk-fade-leave-to { opacity: 0; }
</style>
