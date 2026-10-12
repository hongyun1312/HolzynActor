<template>
  <div class="events">
    <h2 class="page-title">事件流</h2>

    <div class="toolbar">
      <el-select v-model="kindFilter" size="small" style="width: 120px">
        <el-option label="全部类型" value="" />
        <el-option label="行动事件" value="action" />
        <el-option label="世界事件" value="world" />
      </el-select>
      <el-button size="small" :type="streaming ? 'danger' : 'success'" @click="togglePause">
        {{ streaming ? '暂停' : '继续' }}
      </el-button>
      <el-button size="small" @click="clearList">清空</el-button>
      <el-button size="small" @click="exportTxt">导出</el-button>
      <span class="conn-state mono" :class="{ ok: connected, err: !connected && tried }">
        {{ connected ? '● 已连接' : (tried ? '○ 重连中…' : '○ 未连接') }} · 已显示 {{ visible.length }}
      </span>
    </div>

    <div class="event-list">
      <div v-for="ev in visible" :key="ev.id" class="event-item" :class="ev.kind">
        <div class="ev-head">
          <el-tag :type="ev.kind === 'world' ? 'primary' : 'success'" size="small" effect="plain">
            {{ ev.kind === 'world' ? '世界事件' : '行动' }}
          </el-tag>
          <span class="mono dim">{{ ev.time }}</span>
          <span v-if="ev.projectId" class="mono dim">项目 #{{ ev.projectId }}</span>
        </div>
        <div class="ev-body">
          <template v-if="ev.kind === 'world'">
            <div class="ev-content">{{ ev.content }}</div>
          </template>
          <template v-else>
            <div class="ev-kv mono">
              <span v-if="ev.characterId">角色 #{{ ev.characterId }}</span>
              <span v-if="ev.action">动作：{{ ev.action }}</span>
              <span v-if="ev.status">状态：{{ ev.status }}</span>
              <span v-if="ev.planId">计划 #{{ ev.planId }}</span>
            </div>
            <div class="ev-content mono">{{ ev.message || '' }}</div>
          </template>
        </div>
      </div>
      <div v-if="!visible.length" class="empty-hint">暂无事件（世界事件注入 / 行动模拟执行后实时出现）</div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { openEventStream } from '../api/stream'

const kindFilter = ref('')
const streaming = ref(true)
const connected = ref(false)
const tried = ref(false)
const events = ref([])
let seq = 0
let stream = null
const maxEvents = 2000

const visible = computed(() =>
  events.value.filter((e) => !kindFilter.value || e.kind === kindFilter.value)
)

function push(ev) {
  ev.id = ++seq
  events.value.push(ev)
  if (events.value.length > maxEvents) {
    events.value.splice(0, events.value.length - maxEvents)
  }
}

function connect() {
  tried.value = true
  connected.value = false
  stream = openEventStream('/api/debug/events/stream', {
    onOpen: () => { connected.value = true },
    onEvent: (name, data) => {
      if (name === 'connected') return
      if (name === 'action') {
        push({ kind: 'action', ...(data || {}) })
      } else if (name === 'world') {
        push({ kind: 'world', ...(data || {}) })
      }
    },
    onError: () => { connected.value = false }
  })
}

function togglePause() {
  streaming.value = !streaming.value
  stream?.setPaused(!streaming.value)
  if (streaming.value) stream?.resume()
}

function clearList() {
  events.value = []
}

function exportTxt() {
  const text = visible.value
    .map((e) => `[${e.kind}] ${e.time || ''} 项目#${e.projectId || '-'} ${e.kind === 'world' ? e.content : (e.action || e.message || '')}`)
    .join('\n')
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `debug-events-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.txt`
  a.click()
  URL.revokeObjectURL(a.href)
}

onMounted(connect)
onBeforeUnmount(() => stream?.close())
</script>

<style scoped>
.page-title {
  margin: 0 0 14px;
  font-size: 18px;
  color: var(--text-primary);
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.conn-state {
  font-size: 12px;
  color: var(--text-tertiary);
  margin-left: auto;
}
.conn-state.ok {
  color: var(--state-success);
}
.conn-state.err {
  color: var(--state-error);
}
.event-list {
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-md);
  padding: 10px;
  height: calc(100vh - 180px);
  min-height: 360px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
  box-shadow: var(--shadow-lv1);
}
.event-item {
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-sm);
  padding: 8px 12px;
  background: var(--bg-layer-2);
}
.event-item.world {
  border-left: 3px solid var(--accent);
}
.event-item.action {
  border-left: 3px solid var(--state-success);
}
.ev-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
}
.ev-kv {
  display: flex;
  gap: 14px;
  color: var(--text-secondary);
  font-size: 12px;
  margin-bottom: 4px;
  flex-wrap: wrap;
}
.ev-content {
  font-size: 13px;
  color: var(--text-primary);
  white-space: pre-wrap;
  word-break: break-all;
}
.dim {
  color: var(--text-tertiary);
  font-size: 12px;
}
.empty-hint {
  color: var(--text-tertiary);
  text-align: center;
  padding: 30px;
}
</style>
