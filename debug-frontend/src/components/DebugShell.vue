<!--
  SPDX-License-Identifier: AGPL-3.0-or-later
  Copyright (C) 2026 HolzynActor

  DebugShell：调试控制台唯一外壳（界面布局重构 V1.0 批次②）。

  背景：调试台此前把 48px 顶栏 + 168px 导航 + 25 个视图的平铺清单全部写在 App.vue 里，
  导航是一条 25 项的长列表，没有任何分组，找一个视图要逐行扫；且外壳逻辑与主前端两套。
  现在：
   1) 外壳独立成组件（App.vue 只保留健康检查/主题等状态并透传），与主前端 AppShell 同构；
   2) 25 个视图按域分组（总览 / 运行观测 / 记忆与注入 / 状态与生理 / 决策与治理 / 对接接入 / 数据工具）；
   3) 分组可折叠且记忆到 localStorage；顶栏提供视图过滤（Ctrl+K 聚焦），25 项也能秒定位；
   4) 顶栏高度/配色/主题按钮与主前端一致（48px、--bg-nav、accent 悬浮）。

  用法（App.vue）：
    <DebugShell :online="online" :version="version" :sse-count="sseCount" @toggle-theme="toggleMode">
      <router-view />
    </DebugShell>
-->
<template>
  <div class="debug-shell">
    <!-- 顶栏（与主前端 ShellTopbar 同源观感：48px + --bg-nav + accent 悬浮） -->
    <header class="dbg-topbar">
      <div class="tb-left">
        <span class="logo-dot" :style="{ background: online ? 'var(--state-success)' : 'var(--state-error)' }"></span>
        <span class="tb-title">HolzynActor 调试控制台</span>
        <span class="tb-ver mono">{{ version }}</span>
        <span :class="online ? 'tb-online' : 'tb-offline'">{{ online ? '后端在线' : '后端未连接' }}</span>
      </div>
      <div class="tb-right">
        <el-tag v-if="sseCount >= 0" size="small" type="info" effect="plain">活跃 SSE：{{ sseCount }}</el-tag>
        <el-input
          ref="filterEl"
          v-model="filter"
          class="tb-filter"
          size="small"
          clearable
          placeholder="过滤视图"
          :prefix-icon="undefined"
        />
        <button class="theme-btn" :title="isDark ? '切换浅色主题' : '切换深色主题'" @click="$emit('toggle-theme')">
          {{ isDark ? '☀ 浅色' : '🌙 深色' }}
        </button>
      </div>
    </header>

    <div class="dbg-body">
      <!-- 分组侧栏（域分组 + 折叠记忆 + 过滤结果高亮） -->
      <nav class="dbg-rail">
        <div v-for="g in visibleGroups" :key="g.key" class="rail-group">
          <button class="rail-head" type="button" @click="toggleGroup(g.key)">
            <span class="rail-caret" :class="{ open: !collapsed.has(g.key) }"></span>
            <span class="rail-group-name">{{ g.name }}</span>
            <span class="rail-count">{{ g.items.length }}</span>
          </button>
          <div v-show="filter || !collapsed.has(g.key)" class="rail-items">
            <router-link
              v-for="it in g.items"
              :key="it.path"
              :to="it.path"
              class="rail-item"
              :class="{ active: $route.path === it.path }"
            >
              <span class="rail-icon">{{ it.icon }}</span>
              <span class="rail-label">{{ it.label }}</span>
            </router-link>
          </div>
        </div>
        <p v-if="visibleGroups.length === 0" class="rail-empty">无匹配视图</p>
      </nav>

      <!-- 内容区：唯一滚动容器 -->
      <main class="dbg-content">
        <slot></slot>
      </main>
    </div>
  </div>
</template>

<script setup>
/**
 * 调试台外壳。
 * <p>状态（在线/版本/SSE 数/主题）由宿主 App.vue 持有并透传，外壳本身无副作用，
 * 只负责结构、分组导航与折叠记忆。</p>
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

defineProps({
  /** 后端是否在线（决定顶栏状态点颜色与文案） */
  online: { type: Boolean, default: false },
  /** 后端版本号文案 */
  version: { type: String, default: '' },
  /** 活跃 SSE 连接数（-1 = 未知） */
  sseCount: { type: Number, default: -1 }
})
defineEmits(['toggle-theme'])

/** 视图分组：域顺序 = 「先看总览 → 再看运行 → 再看心智内部 → 最后工具」的排查动线 */
const GROUPS = [
  {
    key: 'overview',
    name: '总览与配置',
    items: [
      { path: '/overview', label: '总览', icon: '▣' },
      { path: '/runtime', label: 'AgentRuntime', icon: '◉' },
      { path: '/config', label: '配置', icon: '⚙' }
    ]
  },
  {
    key: 'observe',
    name: '运行观测',
    items: [
      { path: '/logs', label: '实时日志', icon: '≋' },
      { path: '/ai', label: 'AI Trace', icon: '◈' },
      { path: '/ai-call-detail', label: 'AI 调用详情', icon: '⛃' },
      { path: '/usage', label: '用量图', icon: '▤' },
      { path: '/events', label: '事件流', icon: '⇄' }
    ]
  },
  {
    key: 'memory',
    name: '记忆与注入',
    items: [
      { path: '/world-memory', label: '项目记忆', icon: '✎' },
      { path: '/injection', label: '记忆注入', icon: '➤' },
      { path: '/mem-o1', label: '记忆内核 O1', icon: '◍' }
    ]
  },
  {
    key: 'state',
    name: '状态与生理',
    items: [
      { path: '/state', label: '状态曲线', icon: '〽' },
      { path: '/state-tuning', label: '状态调参', icon: '⚙' },
      { path: '/stimulus', label: '刺激日志', icon: '✦' },
      { path: '/life', label: '生命与人格 O6', icon: '♜' }
    ]
  },
  {
    key: 'governance',
    name: '决策与治理',
    items: [
      { path: '/decision-coord', label: '决策协调 O2', icon: '⇌' },
      { path: '/eval-test', label: '行为评估 O2.5', icon: '⚖' },
      { path: '/ai-policy', label: '护栏与路由 O3', icon: '⛨' },
      { path: '/prompt-eng', label: 'Prompt 工程 O4', icon: '✍' },
      { path: '/drift', label: '漂移度量', icon: '≈' },
      { path: '/macro', label: '宏观面板', icon: '◬' }
    ]
  },
  {
    key: 'bridge',
    name: '对接与接入',
    items: [
      { path: '/unity-bridge', label: 'Unity 对接 U1', icon: '◈' },
      { path: '/nanojev-decision', label: 'NanoJev 判定 N3/N4', icon: '⨯' },
      { path: '/n5-cognition', label: '认知 LOD + 微决策 N5', icon: '✧' }
    ]
  },
  {
    key: 'tools',
    name: '数据工具',
    items: [{ path: '/db', label: 'DB 浏览', icon: '▦' }]
  }
]

const COLLAPSE_KEY = 'holzyn.debug.shell.v1'

/** 折叠的分组键集合（持久化到 localStorage） */
const collapsed = ref(loadCollapsed())
/** 视图过滤关键字（命中则临时展开所有分组） */
const filter = ref('')
const filterEl = ref(null)
const isDark = ref(document.documentElement.hasAttribute('data-theme'))

/** 过滤后的分组（按分组名或视图名匹配，忽略大小写） */
const visibleGroups = computed(() => {
  const q = filter.value.trim().toLowerCase()
  if (!q) return GROUPS
  return GROUPS
    .map(g => ({
      ...g,
      items: g.items.filter(it => it.label.toLowerCase().includes(q) || it.path.toLowerCase().includes(q))
    }))
    .filter(g => g.items.length > 0 || g.name.toLowerCase().includes(q))
})

function loadCollapsed() {
  try {
    const raw = localStorage.getItem(COLLAPSE_KEY)
    return new Set(raw ? JSON.parse(raw) : [])
  } catch (_) { return new Set() }
}

function toggleGroup(key) {
  const next = new Set(collapsed.value)
  next.has(key) ? next.delete(key) : next.add(key)
  collapsed.value = next
  try { localStorage.setItem(COLLAPSE_KEY, JSON.stringify([...next])) } catch (_) { /* 隐私模式下静默 */ }
}

/** Ctrl/⌘ + K：聚焦视图过滤（与主前端命令面板同一快捷键习惯） */
function onKeydown(e) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    filterEl.value?.focus?.()
  }
}

/** 主题切换后同步按钮文案（宿主切换 <html data-theme>，这里观察属性变化） */
let themeObserver = null

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  themeObserver = new MutationObserver(() => { isDark.value = document.documentElement.hasAttribute('data-theme') })
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  themeObserver?.disconnect()
})

// 过滤关键字变化时自动聚焦，便于连续筛选
watch(filter, () => { /* 由输入框自身持有焦点，无需额外处理 */ })
</script>

<style scoped>
.debug-shell {
  height: 100vh;
  display: flex;
  flex-direction: column;
}

/* ===== 顶栏（48px，与主前端同源） ===== */
.dbg-topbar {
  height: 48px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 16px;
  background: var(--bg-nav);
  border-bottom: 1px solid var(--border-l2);
}
.tb-left { display: flex; align-items: center; gap: 10px; min-width: 0; }
.logo-dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; }
.tb-title { font-weight: 600; font-size: 15px; color: var(--text-primary); white-space: nowrap; }
.tb-ver { color: var(--text-tertiary); font-size: 12px; }
.tb-online { color: var(--state-success); font-size: 12px; white-space: nowrap; }
.tb-offline { color: var(--state-error); font-size: 12px; white-space: nowrap; }
.tb-right { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }
.tb-filter { width: 180px; }
.theme-btn {
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l2);
  color: var(--text-regular);
  border-radius: var(--radius-sm);
  padding: 4px 12px;
  cursor: pointer;
  font-size: 12px;
  transition: all 0.2s var(--dsw-ease);
}
.theme-btn:hover { border-color: var(--accent); color: var(--accent-text); }

/* ===== 主体：分组侧栏 + 内容区 ===== */
.dbg-body { flex: 1; display: flex; min-height: 0; }

.dbg-rail {
  width: 208px;
  flex-shrink: 0;
  background: var(--bg-nav);
  border-right: 1px solid var(--border-l2);
  padding: 8px 8px 14px;
  overflow-y: auto;
}
.rail-group + .rail-group { margin-top: 6px; }
.rail-head {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 8px;
  background: transparent;
  border: 0;
  cursor: pointer;
  color: var(--text-tertiary);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-align: left;
}
.rail-head:hover { color: var(--text-secondary); }
.rail-caret {
  width: 6px; height: 6px; flex-shrink: 0;
  border-right: 1.6px solid currentColor;
  border-bottom: 1.6px solid currentColor;
  transform: rotate(-45deg);
  transition: transform 0.15s var(--dsw-ease);
}
.rail-caret.open { transform: rotate(45deg); }
.rail-group-name { flex: 1; }
.rail-count { color: var(--text-tertiary); font-weight: 400; }

.rail-items { display: flex; flex-direction: column; gap: 2px; }
.rail-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-radius: var(--radius-sm);
  color: var(--text-secondary);
  text-decoration: none;
  font-size: 13px;
  transition: background 0.15s var(--dsw-ease), color 0.15s var(--dsw-ease);
}
.rail-item:hover { background: var(--bg-nav-hover); color: var(--text-primary); }
.rail-item.active { background: var(--accent-soft); color: var(--accent-text); font-weight: 600; }
.rail-icon { width: 16px; text-align: center; flex-shrink: 0; }
.rail-label { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rail-empty { padding: 12px 10px; color: var(--text-tertiary); font-size: 12px; }

/* 内容区：唯一滚动容器（与主前端 .shell-content 同责） */
.dbg-content {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  padding: 18px 22px;
  background: var(--bg-base);
}
</style>
