<template>
  <div class="logs">
    <h2 class="page-title">实时日志</h2>

    <!-- 工具条（DSH 令牌） -->
    <div class="toolbar">
      <el-select v-model="level" size="small" style="width: 110px" @change="restart">
        <el-option label="全部级别" value="" />
        <el-option v-for="l in ['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR']" :key="l" :label="l" :value="l" />
      </el-select>
      <el-input v-model="keyword" size="small" placeholder="关键词过滤（消息包含）" clearable style="width: 220px"
        @keyup.enter="restart" @clear="restart" />
      <el-button size="small" :type="streaming ? 'danger' : 'success'" @click="togglePause">
        {{ streaming ? '暂停' : '继续' }}
      </el-button>
      <el-button size="small" @click="clearScreen">清屏</el-button>
      <el-button size="small" @click="exportTxt">导出 txt</el-button>
      <el-switch v-model="autoScroll" size="small" active-text="自动滚动" style="margin-left: 6px" />
      <span class="conn-state mono" :class="{ ok: connected, err: !connected && tried }">
        {{ connected ? '● 已连接' : (tried ? '○ 重连中…' : '○ 未连接') }} · 已显示 {{ visible.length }}
      </span>
    </div>

    <!-- 终端日志区（始终深色，对齐 WorkflowConsole；深浅主题均保持） -->
    <div class="terminal" ref="termEl">
      <div v-for="line in visible" :key="line.seq" class="log-line">
        <span class="l-time mono">{{ line.time }}</span>
        <span class="l-level mono" :class="levelClass(line.level)">{{ padLevel(line.level) }}</span>
        <span class="l-thread mono">{{ shortThread(line.thread) }}</span>
        <span class="l-msg">{{ line.message }}</span>
      </div>
      <div v-if="!visible.length" class="empty-hint">暂无日志（日志采集开关 holzyn.actor.debug.log-append.enabled 需为 true）</div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { openEventStream } from '../api/stream'
import { get } from '../api'

const level = ref('')
const keyword = ref('')
const streaming = ref(true)
const autoScroll = ref(true)
const connected = ref(false)
const tried = ref(false)

const lines = ref([]) // 全部（上限防内存膨胀）
const visible = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  return lines.value.filter((l) => {
    if (level.value && l.level !== level.value) return false
    if (kw && !(l.message || '').toLowerCase().includes(kw)) return false
    return true
  })
})

const termEl = ref(null)
let stream = null
let maxLines = 5000

function levelClass(l) {
  return {
    TRACE: 'lv-trace',
    DEBUG: 'lv-debug',
    INFO: 'lv-info',
    WARN: 'lv-warn',
    ERROR: 'lv-err'
  }[l] || 'lv-info'
}

function padLevel(l) {
  return (l || 'INFO').padEnd(5)
}

function shortThread(t) {
  return (t || '?').slice(0, 14)
}

function push(line) {
  lines.value.push(line)
  if (lines.value.length > maxLines) {
    lines.value.splice(0, lines.value.length - maxLines)
  }
  if (autoScroll.value) {
    nextTick(() => {
      if (termEl.value) termEl.value.scrollTop = termEl.value.scrollHeight
    })
  }
}

function connect() {
  tried.value = true
  connected.value = false
  stream = openEventStream(`/api/debug/logs/stream?level=${encodeURIComponent(level.value)}&keyword=${encodeURIComponent(keyword.value)}`, {
    onOpen: () => { connected.value = true },
    onEvent: (name, data) => {
      if (name === 'log' && data?.seq) push(data)
    },
    onError: () => { connected.value = false }
  })
}

function restart() {
  stream?.close()
  connect()
}

function togglePause() {
  streaming.value = !streaming.value
  stream?.setPaused(!streaming.value)
  if (streaming.value) {
    stream?.resume()
  }
}

function clearScreen() {
  lines.value = []
}

function exportTxt() {
  const text = visible.value
    .map((l) => `${l.time} [${l.level}] ${l.thread} - ${l.message}`)
    .join('\n')
  const blob = new Blob([text], { type: 'text/plain;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `debug-logs-${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.txt`
  a.click()
  URL.revokeObjectURL(a.href)
}

async function loadInitialBuffer() {
  try {
    const r = await get('/debug/logs/buffer', { page: 1, size: 300 })
    const items = r.items || []
    lines.value = items
    if (autoScroll.value) {
      nextTick(() => {
        if (termEl.value) termEl.value.scrollTop = termEl.value.scrollHeight
      })
    }
  } catch (_) { /* 后端未就绪忽略 */ }
}

onMounted(() => {
  loadInitialBuffer()
  connect()
})

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
  flex-wrap: wrap;
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

/* 终端日志区：始终深色（深浅主题均保持），对齐 WorkflowConsole 风格 */
.terminal {
  height: calc(100vh - 190px);
  min-height: 360px;
  background: #0b0e13;
  border: 1px solid #1f2630;
  border-radius: var(--radius-md);
  padding: 10px 12px;
  overflow-y: auto;
  font-size: 12px;
  line-height: 1.75;
}
.log-line {
  display: flex;
  gap: 10px;
  white-space: pre-wrap;
  word-break: break-all;
}
.l-time {
  color: #6b7686;
  flex-shrink: 0;
}
.l-thread {
  color: #8fa1b3;
  flex-shrink: 0;
  width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.l-msg {
  color: #d7dee8;
}
.lv-trace {
  color: #9aa4b1;
}
.lv-debug {
  color: #6ea8fe;
}
.lv-info {
  color: #58c28b;
}
.lv-warn {
  color: #e6b84c;
}
.lv-err {
  color: #f26d6d;
}
.empty-hint {
  color: #6b7686;
  padding: 20px;
  text-align: center;
}
</style>
