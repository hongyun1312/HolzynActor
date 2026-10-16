<!--
  Copyright (c) 2026 HONGYUN
  调试页内嵌「项目级 AI 助手」面板（M5 上下文入口深化：调试页内直接解释决策，不再跳转新标签页）。
  复用主后端 /api/assistant 会话 + SSE 流式接口：自动取最近会话或新建，流式渲染回答/工具/来源。
-->
<template>
  <div class="ata-root" :class="{ open }">
    <!-- 浮动开关（右下角） -->
    <button class="ata-toggle" :class="{ 'is-busy': streaming }" :title="open ? '收起助手' : '打开助手'" @click="toggle">
      🤖
    </button>
    <!-- 面板 -->
    <div v-if="open" class="ata-panel">
      <div class="ata-head">
        <span class="ata-title">🤖 项目级 AI 助手</span>
        <span class="ata-sub">解释决策 · 查询世界 · 项目导览</span>
        <button class="ata-close" title="收起" @click="open = false">✕</button>
      </div>
      <div class="ata-session-row">
        <span class="ata-session-label">会话</span>
        <select v-model="sessionId" class="ata-session-select" @change="onSessionChange">
          <option v-for="s in sessions" :key="s.id" :value="s.id">{{ s.title || '新会话' }}</option>
        </select>
        <button class="ata-new" title="新建会话" @click="newSession">＋</button>
      </div>
      <div ref="listEl" class="ata-list">
        <template v-if="messages.length">
          <div v-for="m in messages" :key="m.key" class="ata-msg" :class="`is-${m.role}`">
            <div v-if="m.toolCalls && m.toolCalls.length" class="ata-tools">
              <span v-for="(t, i) in m.toolCalls" :key="i" class="ata-tool-chip">🔍 {{ t.summary || t.name }}</span>
            </div>
            <div v-if="m.role === 'assistant'" class="ata-bubble markdown-body" v-html="renderMarkdown(displayContent(m.content || (m.streaming ? '正在思考…' : '')))"></div>
            <div v-else class="ata-bubble">{{ m.content || (m.streaming ? '正在思考…' : '') }}</div>
            <details v-if="m.sources && m.sources.length" class="ata-sources">
              <summary>来源（{{ m.sources.length }}）</summary>
              <div v-for="(s, i) in m.sources" :key="i" class="ata-source">
                <span class="ata-source-name">{{ s.source }}</span>
                <span v-if="s.detail" class="ata-source-detail">{{ s.detail }}</span>
              </div>
            </details>
          </div>
        </template>
        <div v-else class="ata-empty">
          调试页内嵌助手：选中一条决策链路点「解释这次决策」，或直接提问——
          <br />「最近的 AI 调用统计」「这个世界现在怎么样？」
        </div>
        <div v-if="error" class="ata-error">{{ error }}</div>
      </div>
      <div class="ata-input">
        <textarea
          v-model="draft"
          rows="2"
          class="ata-textarea"
          placeholder="问世界、问决策、问项目…（Enter 发送，Shift+Enter 换行）"
          :disabled="streaming"
          @keydown.enter.exact.prevent="send"
        ></textarea>
        <button class="ata-send" :disabled="streaming || !draft.trim()" @click="send">发送</button>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 调试页内嵌项目级 AI 助手（M5）。
 * 复用主后端 /api/assistant（会话 CRUD + POST chat SSE 流式），与主前端侧边栏同一接口；
 * 提供「解释这次决策」等调试场景的内联对话（不跳转新标签页）。
 */
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { get, post } from '../api/index'
import { postEventStream } from '../api/stream'

const props = defineProps({
  /** 当前项目 ID（必填；按项目隔离会话） */
  projectId: { type: [Number, String], default: null },
  /** 预填问题（父组件「解释这次决策」写入；面板打开且会话就绪后自动发送一次） */
  initialQuestion: { type: String, default: '' }
})

const open = ref(false)
const sessions = ref([])
const sessionId = ref(null)
const messages = ref([])
const draft = ref('')
const streaming = ref(false)
const error = ref('')
const listEl = ref(null)
let msgSeq = 0
let stream = null
let lastAutoAsk = null

function pushMsg(msg) {
  msgSeq += 1
  messages.value.push({ key: `m${msgSeq}`, ...msg })
  scrollBottom()
}

async function loadSessions() {
  if (!props.projectId) return
  try {
    const list = await get(`/assistant/projects/${props.projectId}/sessions`)
    sessions.value = list || []
    if (!list || !list.length) {
      await newSession()
    } else {
      sessionId.value = list[0].id
    }
    return sessionId.value
  } catch (e) {
    error.value = '会话加载失败：' + (e.message || e)
    return null
  }
}

async function newSession() {
  if (!props.projectId) return
  try {
    const s = await post(`/assistant/projects/${props.projectId}/sessions`, {})
    sessions.value.unshift(s)
    sessionId.value = s.id
    return s.id
  } catch (e) {
    error.value = '新建会话失败：' + (e.message || e)
    return null
  }
}

function onSessionChange() {
  messages.value = []
  error.value = ''
}

/** 发送一轮对话（SSE 流式渲染）。 */
function send(question) {
  const q = (question || draft.value || '').trim()
  if (!q || !props.projectId || !sessionId.value || streaming.value) return
  error.value = ''
  if (!question) draft.value = ''
  pushMsg({ role: 'user', content: q })
  const answer = { role: 'assistant', content: '', streaming: true, toolCalls: [] }
  pushMsg(answer)
  streaming.value = true
  stream = postEventStream(`/api/assistant/projects/${props.projectId}/sessions/${sessionId.value}/chat`, { question: q }, {
    onEvent: (name, data) => {
      if (name === 'tool' && data) answer.toolCalls.push({ name: data.name, summary: data.summary })
      else if (name === 'token' && data && data.delta) {
        answer.content += data.delta
        scrollBottom()
      } else if (name === 'done' && data) {
        answer.content = data.content || answer.content
        answer.sources = data.sources || []
        answer.toolCalls = (data.toolCalls && data.toolCalls.length) ? data.toolCalls : answer.toolCalls
      }
    },
    onError: (err) => {
      answer.content = (answer.content || '') + (err?.message || '回答生成失败')
      answer.error = true
      answer.streaming = false
      streaming.value = false
      scrollBottom()
    },
    onDone: () => {
      answer.streaming = false
      streaming.value = false
      scrollBottom()
    }
  })
}

/** 面板打开 + 会话就绪后自动发送预填问题（「解释这次决策」）。 */
async function drainInitial() {
  const q = props.initialQuestion
  if (!q || !q.trim() || !sessionId.value || streaming.value) return
  if (lastAutoAsk === q) return
  lastAutoAsk = q
  send(q)
}

async function toggle() {
  open.value = !open.value
  if (open.value) {
    if (!sessionId.value) {
      const sid = await loadSessions()
      if (sid) drainInitial()
    } else {
      drainInitial()
    }
  }
}

watch(() => props.initialQuestion, (q) => {
  if (q && q.trim() && open.value) drainInitial()
})

watch(() => props.projectId, () => {
  sessions.value = []
  sessionId.value = null
  messages.value = []
  lastAutoAsk = null
})

/** 父组件入口：打开面板并立即发送一条问题（调试页「解释这次决策」）。 */
async function openWith(question) {
  open.value = true
  if (!sessionId.value) {
    const sid = await loadSessions()
    if (sid && question && question.trim()) send(question)
  } else if (question && question.trim()) {
    send(question)
  }
}

defineExpose({ openWith })

function scrollBottom() {
  nextTick(() => {
    if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
  })
}

onBeforeUnmount(() => {
  if (stream) stream.close()
})

/**
 * 轻量 Markdown 渲染（调试页零依赖实现；主前端用 markdown-it，此处覆盖对话常见语法：
 * 标题/粗体/斜体/行内代码/代码块/列表/引用/链接/表格/换行）。与 .markdown-body 样式配合。
 */
/** 回答显示预处理：模型输出常为无换行的连续长句 → 在句末标点后补换行以分句显示；已有换行（代码块/列表）原样保留。 */
function displayContent(src) {
  const s = String(src == null ? '' : src)
  if (!s || s.includes('\n')) return s
  return s.replace(/([。！？；!?;])\s*(?=\S)/g, '$1\n')
}

function escapeHtml(s) {
  return String(s == null ? '' : s)
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;')
}

function renderMarkdown(src) {
  let text = String(src == null ? '' : src)
  let html = ''
  let inFence = false
  let fenceLang = ''
  let fenceBuf = []
  let inUl = false
  const lines = text.split(/\r?\n/)
  const flushUl = () => { if (inUl) { html += '</ul>'; inUl = false } }
  const esc = escapeHtml
  const inline = (s) => {
    let r = esc(s)
    r = r.replace(/`([^`]+)`/g, '<code>$1</code>')
    r = r.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    r = r.replace(/\*([^*]+)\*/g, '<em>$1</em>')
    r = r.replace(/\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>')
    return r
  }
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    const fence = line.match(/^```(\w*)/)
    if (fence) {
      if (inFence) {
        html += `<pre><code>${escapeHtml(fenceBuf.join('\n'))}</code></pre>`
        inFence = false; fenceBuf = []
      } else {
        flushUl(); inFence = true; fenceLang = fence[1]
      }
      continue
    }
    if (inFence) { fenceBuf.push(line); continue }
    const heading = line.match(/^(#{1,4})\s+(.*)/)
    if (heading) { flushUl(); const lv = heading[1].length; html += `<h${lv}>${inline(heading[2])}</h${lv}>`; continue }
    if (/^\s*[-*]\s+/.test(line)) {
      if (!inUl) { html += '<ul>'; inUl = true }
      html += `<li>${inline(line.replace(/^\s*[-*]\s+/, ''))}</li>`; continue
    }
    flushUl()
    if (/^\s*>\s?/.test(line)) { html += `<blockquote>${inline(line.replace(/^\s*>\s?/, ''))}</blockquote>`; continue }
    if (/^\|/.test(line) && /^\|/.test(lines[i + 1] || '')) {
      // 表头行（分隔行直接跳过；简单逐行转 <tr>）
      const cells = line.replace(/^\||\|$/g, '').split('|').map((c) => c.trim())
      html += '<table><tr>' + cells.map((c) => `<th>${inline(c)}</th>`).join('') + '</tr>'
      i++
      while (i + 1 < lines.length && /^\|/.test(lines[i + 1])) {
        i++
        const row = lines[i].replace(/^\||\|$/g, '').split('|').map((c) => c.trim())
        if (row.every((c) => /^:?-+:?$/.test(c))) continue
        html += '<tr>' + row.map((c) => `<td>${inline(c)}</td>`).join('') + '</tr>'
      }
      html += '</table>'
      continue
    }
    if (!line.trim()) { html += ''; continue }
    html += `<p>${inline(line)}</p>`
  }
  if (inFence) html += `<pre><code>${escapeHtml(fenceBuf.join('\n'))}</code></pre>`
  flushUl()
  return html
}
</script>

<style scoped>
.ata-root { position: fixed; right: 20px; bottom: 84px; z-index: 3000; pointer-events: none; }
.ata-root > * { pointer-events: auto; }
.ata-toggle {
  position: absolute;
  right: 0; bottom: 0;
  width: 46px; height: 46px;
  border-radius: 50%;
  border: none;
  background: linear-gradient(135deg, #4f6ef7, #7c5cff);
  color: #fff;
  font-size: 19px;
  cursor: pointer;
  box-shadow: 0 6px 18px rgba(79, 110, 247, 0.4);
}
.ata-toggle.is-busy { animation: ata-pulse 1.2s ease-in-out infinite; }
@keyframes ata-pulse { 0%,100% { box-shadow: 0 0 0 0 rgba(124, 92, 255, 0.5); } 50% { box-shadow: 0 0 0 10px rgba(124, 92, 255, 0); } }
.ata-panel {
  position: absolute;
  right: 0; bottom: 58px;
  width: 380px; height: 520px;
  display: flex;
  flex-direction: column;
  background: #fff;
  border: 1px solid #e5e9f2;
  border-radius: 12px;
  box-shadow: 0 14px 36px rgba(31, 42, 68, 0.2);
  overflow: hidden;
}
.ata-head {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid #eef1f6;
  background: color-mix(in srgb, #4f6ef7 6%, #fff);
}
.ata-title { font-size: 13.5px; font-weight: 700; color: #1f2a44; }
.ata-sub { flex: 1; font-size: 11px; color: #8a94a8; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ata-close { border: none; background: transparent; color: #8a94a8; cursor: pointer; font-size: 13px; }
.ata-session-row { display: flex; align-items: center; gap: 6px; padding: 6px 12px; border-bottom: 1px solid #eef1f6; }
.ata-session-label { font-size: 11px; color: #8a94a8; }
.ata-session-select { flex: 1; min-width: 0; padding: 4px 6px; border: 1px solid #e5e9f2; border-radius: 6px; font-size: 12px; color: #3a4660; }
.ata-new { width: 24px; height: 24px; border: 1px solid #e5e9f2; border-radius: 6px; background: #fff; color: #4f6ef7; cursor: pointer; font-size: 13px; }
.ata-list { flex: 1; min-height: 0; overflow-y: auto; padding: 10px 12px; display: flex; flex-direction: column; gap: 8px; }
.ata-msg { max-width: 92%; display: flex; flex-direction: column; gap: 4px; }
.ata-msg.is-user { align-self: flex-end; align-items: flex-end; }
.ata-msg.is-assistant { align-self: flex-start; align-items: flex-start; }
.ata-bubble { padding: 8px 11px; border-radius: 11px; font-size: 12.5px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; color: #3a4660; }
.ata-bubble.markdown-body { white-space: normal; line-height: 1.65; }
.ata-bubble.markdown-body > *:first-child { margin-top: 0; }
.ata-bubble.markdown-body > *:last-child { margin-bottom: 0; }
.ata-bubble.markdown-body p { margin: 0 0 8px; }
.ata-bubble.markdown-body ul, .ata-bubble.markdown-body ol { margin: 0 0 8px; padding-left: 20px; }
.ata-bubble.markdown-body code { font-size: 11.5px; }
.ata-bubble.markdown-body pre { max-width: 100%; overflow-x: auto; }
.ata-msg.is-user .ata-bubble { background: #4f6ef7; color: #fff; border-bottom-right-radius: 4px; }
.ata-msg.is-assistant .ata-bubble { background: #f5f7fb; border: 1px solid #eef1f6; border-bottom-left-radius: 4px; }
.ata-tools { display: flex; flex-wrap: wrap; gap: 4px; }
.ata-tool-chip { font-size: 10.5px; color: #8a94a8; background: #eef1f6; padding: 1px 7px; border-radius: 999px; }
.ata-sources { font-size: 11px; color: #8a94a8; }
.ata-sources summary { cursor: pointer; user-select: none; }
.ata-source { padding: 1px 0; }
.ata-source-name { color: #3a4660; }
.ata-source-detail { color: #8a94a8; }
.ata-empty { color: #8a94a8; font-size: 12px; line-height: 1.8; text-align: center; margin-top: 18px; }
.ata-error { color: #e5484d; font-size: 12px; }
.ata-input { display: flex; gap: 8px; padding: 10px 12px; border-top: 1px solid #eef1f6; }
.ata-textarea { flex: 1; resize: none; border: 1px solid #e5e9f2; border-radius: 9px; padding: 7px 9px; font-size: 12.5px; line-height: 1.5; color: #3a4660; font-family: inherit; }
.ata-textarea:focus { outline: none; border-color: #4f6ef7; }
.ata-send { align-self: flex-end; padding: 7px 15px; border: none; border-radius: 9px; background: #4f6ef7; color: #fff; font-size: 12.5px; cursor: pointer; }
.ata-send:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
