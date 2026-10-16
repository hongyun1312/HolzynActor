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
  <!-- 全局助手：浮窗按钮 + 右侧滑入侧边栏（跨页面保持；仅项目页可用） -->
  <div v-if="projectId" class="assistant-root">
    <!-- 默认态：右下角浮窗按钮（可拖拽 + 呼吸光效随世界运行亮灭；开/关带缩放淡入动画，避免生硬消失） -->
    <transition name="assistant-float-toggle">
      <button
        v-if="!assistantState.sidebarOpen"
        class="assistant-float"
        :class="{ 'is-running': worldRunning }"
        :style="floatStyle"
        data-testid="assistant-float"
        title="项目级 AI 助手（Ctrl+Shift+A）"
        @mousedown="startFloatDrag"
        @click="onFloatClick"
      >
        <span class="assistant-float-glow"></span>
        <svg viewBox="0 0 24 24" width="20" height="20" fill="currentColor" aria-hidden="true">
          <path d="M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2z" />
        </svg>
      </button>
    </transition>

    <!-- 展开态：右侧侧边栏（滑入/滑出动画） -->
    <transition name="assistant-slide">
      <div
        v-if="assistantState.sidebarOpen"
        class="assistant-sidebar"
        :style="{ width: assistantState.width + 'px' }"
        data-testid="assistant-sidebar"
      >
      <div class="assistant-resize-handle" title="拖拽调宽" @mousedown="startWidthDrag"></div>
      <div class="assistant-head">
        <div class="assistant-head-title">
          <span class="assistant-head-dot" :class="{ 'is-running': worldRunning }"></span>
          <span>项目级 AI 助手</span>
        </div>
        <div class="assistant-head-actions">
          <button class="assistant-icon-btn" title="新建会话" @click="newSession">＋</button>
          <button class="assistant-icon-btn" title="收起（Esc）" @click="closeAssistant">✕</button>
        </div>
      </div>

      <!-- 会话选择 -->
      <div class="assistant-session-bar">
        <select v-model="assistantState.activeSessionId" class="assistant-session-select" @change="onSessionChange">
          <option v-for="s in assistantState.sessions" :key="s.id" :value="s.id">
            {{ s.title || '新会话' }}{{ s.defaultMode === 'fact' ? ' · 事实' : '' }}
          </option>
        </select>
        <span class="assistant-mode-badge" :title="'默认模式：' + (assistantState.defaultMode === 'fact' ? '严格事实' : '叙事')">
          {{ assistantState.defaultMode === 'fact' ? '事实' : '叙事' }}
        </span>
        <select v-model="confirmModeSel" class="assistant-confirm-select" title="干预确认粒度" @change="onConfirmModeChange">
          <option value="standard">标准确认</option>
          <option value="strict">严格确认</option>
          <option value="trust">信任执行</option>
        </select>
      </div>

      <!-- 消息列表 -->
      <div ref="listEl" class="assistant-list" data-testid="assistant-list">
        <template v-if="assistantState.messages.length">
          <div v-for="m in assistantState.messages" :key="m.key" class="assistant-msg" :class="`is-${m.role}`">
            <div v-if="m.toolCalls && m.toolCalls.length" class="assistant-tools">
              <span v-for="(t, i) in m.toolCalls" :key="i" class="assistant-tool-chip">🔍 {{ t.summary || t.name }}</span>
            </div>
            <AssistantCard v-if="m.card && m.card.fields && m.card.fields.length && !m.confirmPlan" :card="m.card" class="assistant-card-slot" />
            <AssistantConfirmCard
              v-if="m.confirmPlan"
              :plan="m.confirmPlan"
              :busy="m.confirmBusy"
              :done="m.confirmDone"
              :result-text="m.confirmResult"
              class="assistant-card-slot"
              @confirm="onInterventionConfirm(m)"
              @revise="onInterventionRevise(m)"
              @cancel="onInterventionCancel(m)"
            />
            <div class="assistant-msg-meta" v-if="m.role === 'assistant' && !m.streaming">
              <span v-if="m.mode" class="assistant-mode-chip" :class="`is-${m.mode}`">{{ m.mode === 'fact' ? '事实' : '叙事' }}</span>
            </div>
            <div v-if="m.role === 'assistant'" class="assistant-msg-content markdown-body" :class="{ 'is-error': m.error }"
                 v-html="renderMarkdown(displayContent(m.content || (m.streaming ? '正在思考…' : '')))"></div>
            <div v-else class="assistant-msg-content" :class="{ 'is-error': m.error }">{{ m.content || (m.streaming ? '正在思考…' : '') }}</div>
            <div v-if="m.streaming" class="assistant-typing"></div>
            <div v-if="m.quickFollows && m.quickFollows.length" class="assistant-follows">
              <button v-for="(q, i) in m.quickFollows" :key="i" class="assistant-follow" @click="askFollow(q)">{{ q }}</button>
            </div>
            <details v-if="m.sources && m.sources.length" class="assistant-sources">
              <summary>来源（{{ m.sources.length }}）</summary>
              <div v-for="(s, i) in m.sources" :key="i" class="assistant-source">
                <span class="assistant-source-icon">{{ sourceIcon(s.source) }}</span>
                <span class="assistant-source-name">{{ s.source }}</span>
                <span v-if="s.detail" class="assistant-source-detail">{{ s.detail }}</span>
              </div>
            </details>
          </div>
        </template>
        <div v-else class="assistant-empty">
          我是你的世界讲解助手：可以问我——
          <br />「沈守拙现在在做什么？」
          <br />「青禾村最近发生了什么？」
          <br />「介绍一下这个世界 / 这个项目怎么用？」
          <br />「为什么沈守拙做了这个决定？」（运行观测）
          <br />「把天气切成雨天」（干预，需确认）
        </div>
        <div v-if="assistantState.lastError" class="assistant-error">{{ assistantState.lastError }}</div>
      </div>

      <!-- 输入区 -->
      <div class="assistant-input">
        <textarea
          v-model="draft"
          class="assistant-textarea"
          rows="2"
          placeholder="问世界、问角色、问项目…（Enter 发送，Shift+Enter 换行）"
          :disabled="assistantState.streaming"
          @keydown.enter.exact.prevent="send"
        ></textarea>
        <button class="assistant-send" :disabled="assistantState.streaming || !draft.trim()" @click="send">
          发送
        </button>
      </div>
      </div>
    </transition>
  </div>
</template>

<script setup>
/**
 * 项目级 AI 助手全局组件（M1+M2）。
 *
 * <p>职责：全局浮窗按钮（拖拽 + 世界运行呼吸光效）→ 右侧 380px 可调宽侧边栏；
 * 会话持久化（后端按项目隔离）+ 消息流式渲染（SSE）+ 数据卡片 / 来源标注 / 快速追问；
 * 快捷键 Ctrl+Shift+A 唤起 / 收起，Esc 收起。</p>
 *
 * <p>状态纪律：面板宽度与展开态=记忆态（落盘保持）；当前会话与消息=会话态（刷新回空）。
 * 本项目页才展示（projectId 由路由推导），跨路由保持不关闭。</p>
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AssistantCard from './components/AssistantCard.vue'
import AssistantConfirmCard from './components/AssistantConfirmCard.vue'
import { renderMarkdown } from '@/shared/markdown'
import {
  assistantState,
  closeAssistant,
  enterProject,
  openAssistant,
  askAssistant,
  setAssistantWidth,
  setConfirmMode,
  ASSISTANT_WIDTH_RANGE,
} from './assistantStore'
import {
  listAssistantSessions,
  createAssistantSession,
  listAssistantMessages,
  deleteAssistantSession,
  chatAssistant,
  updateAssistantSessionConfirmMode,
  confirmAssistantIntervention,
  cancelAssistantIntervention,
  reviseAssistantIntervention,
} from '@/shared/api/assistant'
import http from '@/shared/api/http'

const route = useRoute()
const router = useRouter()

/** 当前项目 ID（仅项目页可用） */
const projectId = computed(() => {
  const id = route.params?.id
  return id ? Number(id) : null
})

const listEl = ref(null)
const draft = ref('')
let msgSeq = 0

/** 确认粒度选择器（双向同步到会话态，变更时落库） */
const confirmModeSel = computed({
  get: () => assistantState.confirmMode || 'standard',
  set: (v) => {
    assistantState.confirmMode = v
    setConfirmMode(v)
  }
})

/** 已消费的 ask 参数（防路由回环重复处理） */
let lastAskHandled = null

async function onConfirmModeChange() {
  const mode = confirmModeSel.value
  if (!projectId.value || !assistantState.activeSessionId) return
  try {
    const s = await updateAssistantSessionConfirmMode(projectId.value, assistantState.activeSessionId, mode)
    if (s) assistantState.confirmMode = s.confirmMode || mode
  } catch (e) {
    assistantState.lastError = e?.message || '确认粒度更新失败'
    assistantState.confirmMode = 'standard'
  }
}

/** 世界是否运行（浮窗光效；每 20s 轮询一次时钟） */
const worldRunning = ref(false)
let clockTimer = null

/** 浮窗拖拽位置（会话态，仅内存） */
const floatPos = ref({ x: null, y: null })
const floatStyle = computed(() =>
  floatPos.value.x != null ? { left: `${floatPos.value.x}px`, top: `${floatPos.value.y}px` } : {}
)
let floatDragging = false
let floatMoved = false
let widthDragging = false

// ==================== 会话与消息 ====================

function pushMsg(msg) {
  msgSeq += 1
  assistantState.messages.push({ key: `m${msgSeq}`, ...msg })
  scrollBottom()
  // 关键：返回「响应式代理包裹后的元素」——流式回调（content/card/sources/confirmPlan/streaming）
  // 对返回对象的原地变更才会被模板追踪。直接持有 push 前的原始对象变更不会触发更新
  // （第 61 轮冒烟抓修：此前返回 void，answer 的变更全部丢失，卡片/确认卡/流式内容均不渲染）。
  return assistantState.messages[assistantState.messages.length - 1]
}

async function loadSessions() {
  if (!projectId.value) return
  enterProject(projectId.value)
  assistantState.loading = true
  try {
    const list = await listAssistantSessions(projectId.value)
    assistantState.sessions = list || []
    if (!list || !list.length) {
      await newSession()
    } else {
      const last = list[0]
      await selectSession(last.id)
    }
  } catch (e) {
    assistantState.lastError = e?.message || '会话加载失败'
  } finally {
    assistantState.loading = false
  }
}

async function newSession() {
  if (!projectId.value) return
  try {
    const s = await createAssistantSession(projectId.value, {})
    assistantState.sessions.unshift(s)
    await selectSession(s.id)
  } catch (e) {
    assistantState.lastError = e?.message || '新建会话失败'
  }
}

async function selectSession(id) {
  if (!projectId.value) return
  assistantState.activeSessionId = id
  assistantState.messages = []
  try {
    const s = assistantState.sessions.find((x) => x.id === id)
    assistantState.defaultMode = s?.defaultMode || 'narrative'
    assistantState.confirmMode = s?.confirmMode || 'standard'
    const list = await listAssistantMessages(projectId.value, id)
    assistantState.messages = (list || []).map((m) => {
      const cp = confirmPlanOf(safeJson(m.cardJson))
      return {
        key: `h${m.id}`,
        role: m.role,
        content: m.content || '',
        mode: m.mode,
        card: safeJson(m.cardJson),
        sources: safeJson(m.sourcesJson),
        quickFollows: safeJson(m.quickFollowsJson),
        toolCalls: safeJson(m.toolCallsJson),
        confirmPlan: cp,
        confirmDone: !!cp,
        confirmResult: cp ? '该干预已处理（历史消息）' : ''
      }
    })
    scrollBottom()
    drainPendingAsks()
  } catch (e) {
    assistantState.lastError = e?.message || '消息加载失败'
  }
}

/** 历史消息里的确认卡片 → 还原为 confirm 计划载荷（展示执行/取消态）。 */
function confirmPlanOf(card) {
  if (!card || card.type !== 'confirm') return null
  const field = (label) => {
    const hit = (card.fields || []).find((f) => f && f.label === label)
    return hit ? hit.value : null
  }
  // 历史消息无 planId（已处理），标记为已处理态不可再操作
  return {
    operation: null,
    operationLabel: card.title ? card.title.replace(/^干预确认\s*·\s*/, '') : '世界干预',
    detail: field('操作详情'),
    impact: field('影响范围'),
    risk: field('风险等级') === '高' ? 'high' : 'low',
    riskLabel: field('风险等级') || '低',
    status: 'resolved'
  }
}

function onSessionChange() {
  selectSession(assistantState.activeSessionId)
}

/** 发送消息（SSE 流式）。 */
async function send() {
  const question = (draft.value || '').trim()
  if (!question || !projectId.value || !assistantState.activeSessionId || assistantState.streaming) return
  draft.value = ''
  sendQuestion(question)
}

/** 直接以给定问题发起一轮对话（内部入口共用）。 */
function sendQuestion(question) {
  if (!question || !projectId.value || !assistantState.activeSessionId || assistantState.streaming) return
  assistantState.lastError = null
  pushMsg({ role: 'user', content: question })
  // answer 必须是 pushMsg 返回的响应式元素，流式回调对其原地变更才能驱动模板更新
  const answer = pushMsg({ role: 'assistant', content: '', streaming: true, toolCalls: [] })
  assistantState.streaming = true
  chatAssistant(projectId.value, assistantState.activeSessionId, question, undefined, {
    onMeta: (data) => {
      if (data?.mode) assistantState.defaultMode = data.mode
    },
    onTool: (data) => {
      if (data) answer.toolCalls.push({ name: data.name, summary: data.summary })
    },
    onConfirm: (data) => {
      if (data && data.planId) {
        answer.confirmPlan = { ...data, status: 'pending' }
        scrollBottom()
      }
    },
    onToken: (data) => {
      if (data && data.delta) {
        answer.content += data.delta
        scrollBottom()
      }
    },
    onDone: (data) => {
      if (data) {
        answer.content = data.content || answer.content
        answer.card = data.card || null
        answer.sources = data.sources || []
        answer.quickFollows = data.quickFollows || []
        if (data.toolCalls && data.toolCalls.length) answer.toolCalls = data.toolCalls
        if (data.awaitingConfirm && data.confirm && data.confirm.planId) {
          answer.confirmPlan = { ...data.confirm, status: 'pending' }
        }
      }
      answer.streaming = false
      assistantState.streaming = false
      refreshSessionsQuietly()
      drainPendingAsks()
      scrollBottom()
    },
    onError: (err) => {
      answer.content = (answer.content || '') + (err?.message || '回答生成失败')
      answer.error = true
      answer.streaming = false
      assistantState.streaming = false
      scrollBottom()
    },
  })
}

/** 确认执行干预。 */
async function onInterventionConfirm(m) {
  const plan = m.confirmPlan
  if (!plan || !plan.planId || !projectId.value || !assistantState.activeSessionId) return
  m.confirmBusy = true
  assistantState.lastError = null
  try {
    const r = await confirmAssistantIntervention(projectId.value, assistantState.activeSessionId, plan.planId)
    m.confirmDone = true
    m.confirmResult = r?.resultText || '已执行'
    if (r?.detail) m.content = r.detail
    refreshSessionsQuietly()
  } catch (e) {
    assistantState.lastError = e?.message || '干预执行失败'
    m.confirmResult = ''
    m.confirmDone = false
  } finally {
    m.confirmBusy = false
  }
}

/** 取消干预。 */
async function onInterventionCancel(m) {
  const plan = m.confirmPlan
  if (!plan || !plan.planId || !projectId.value || !assistantState.activeSessionId) return
  m.confirmBusy = true
  assistantState.lastError = null
  try {
    const r = await cancelAssistantIntervention(projectId.value, assistantState.activeSessionId, plan.planId)
    m.confirmDone = true
    m.confirmResult = r?.resultText || '已取消'
    refreshSessionsQuietly()
  } catch (e) {
    assistantState.lastError = e?.message || '取消失败'
    m.confirmDone = false
  } finally {
    m.confirmBusy = false
  }
}

/** 修改后执行（M5）：用户修改计划参数后直接执行。 */
async function onInterventionRevise(m, payload) {
  const plan = m.confirmPlan || (payload && payload.plan)
  if (!plan || !plan.planId || !projectId.value || !assistantState.activeSessionId) return
  const params = (payload && payload.params) || {}
  m.confirmBusy = true
  assistantState.lastError = null
  try {
    const r = await reviseAssistantIntervention(projectId.value, assistantState.activeSessionId, plan.planId, params)
    if (r?.status === 'failed') {
      // 修改不合法：计划保持 pending，提示后用户可继续修改重试
      assistantState.lastError = r.resultText || '修改后执行失败（参数不合法）'
      m.confirmDone = false
      return
    }
    m.confirmDone = true
    m.confirmResult = r?.resultText || '已执行'
    if (r?.detail) m.content = r.detail
    refreshSessionsQuietly()
  } catch (e) {
    assistantState.lastError = e?.message || '修改后执行失败'
    m.confirmDone = false
  } finally {
    m.confirmBusy = false
  }
}

/** 消费待发送问题队列（会话就绪后逐条发送）。 */
function drainPendingAsks() {
  if (!assistantState.activeSessionId) return
  while (assistantState.pendingAsks.length && !assistantState.streaming) {
    const q = assistantState.pendingAsks.shift()
    if (q) sendQuestion(q)
  }
}

/** 处理路由 ask 参数（调试页「解释这次决策」跳转入口）。 */
function handleRouteAsk() {
  const ask = route.query.ask
  if (!ask || typeof ask !== 'string' || !ask.trim()) return
  if (lastAskHandled === ask) return
  lastAskHandled = ask
  if (projectId.value) {
    askAssistant(ask.trim())
    router.replace({ query: { ...route.query, ask: undefined } }).catch(() => {})
  }
}

/** 点击快速追问（直接作为新问题发送）。 */
function askFollow(q) {
  draft.value = q
  send()
}

/** 静默刷新会话列表（更新时间/标题变化）。 */
async function refreshSessionsQuietly() {
  try {
    assistantState.sessions = (await listAssistantSessions(projectId.value)) || []
  } catch (_) {
    // 静默
  }
}

function scrollBottom() {
  nextTick(() => {
    if (listEl.value) listEl.value.scrollTop = listEl.value.scrollHeight
  })
}

// ==================== 浮窗拖拽 / 宽度拖拽 ====================

function startFloatDrag(ev) {
  if (ev.button !== 0) return
  floatDragging = true
  floatMoved = false
  const startX = ev.clientX
  const startY = ev.clientY
  const rect = ev.currentTarget.getBoundingClientRect()
  const dx = ev.clientX - rect.left
  const dy = ev.clientY - rect.top
  const move = (e) => {
    if (!floatDragging) return
    // 位移超过阈值才算「拖拽」：拖动期间不移动按钮，纯点击不触发拖动
    if (Math.abs(e.clientX - startX) > 4 || Math.abs(e.clientY - startY) > 4) {
      floatMoved = true
      floatPos.value = {
        x: Math.max(8, Math.min(window.innerWidth - rect.width - 8, e.clientX - dx)),
        y: Math.max(8, Math.min(window.innerHeight - rect.height - 8, e.clientY - dy)),
      }
    }
  }
  const up = () => {
    floatDragging = false
    window.removeEventListener('mousemove', move)
    window.removeEventListener('mouseup', up)
  }
  window.addEventListener('mousemove', move)
  window.addEventListener('mouseup', up)
}

function onFloatClick() {
  // 注意：浏览器在 mouseup 之后才触发 click，此时 floatDragging 已被置回 false，
  // 因此必须用 floatMoved 判定是否真的拖拽过——否则拖拽结束总会误打开侧边栏
  if (!floatMoved) openAssistant()
}

function startWidthDrag(ev) {
  if (ev.button !== 0) return
  widthDragging = true
  const startX = ev.clientX
  const startWidth = assistantState.width
  const move = (e) => {
    if (!widthDragging) return
    const next = startWidth - (e.clientX - startX)
    const { min, max } = ASSISTANT_WIDTH_RANGE
    setAssistantWidth(Math.min(max, Math.max(min, next)))
  }
  const up = () => {
    widthDragging = false
    window.removeEventListener('mousemove', move)
    window.removeEventListener('mouseup', up)
    document.body.style.cursor = ''
  }
  document.body.style.cursor = 'col-resize'
  window.addEventListener('mousemove', move)
  window.addEventListener('mouseup', up)
}

// ==================== 快捷键 / 生命周期 ====================

function onKeydown(ev) {
  const ctrl = ev.ctrlKey || ev.metaKey
  if (ctrl && ev.shiftKey && !ev.altKey && (ev.key === 'A' || ev.key === 'a')) {
    ev.preventDefault()
    if (projectId.value) {
      if (assistantState.sidebarOpen) closeAssistant()
      else openAssistant()
    }
    return
  }
  // Esc 收起侧边栏（输入框内不拦截）
  if (ev.key === 'Escape' && assistantState.sidebarOpen) {
    const tag = ev.target?.tagName?.toLowerCase()
    const editable = tag === 'input' || tag === 'textarea' || tag === 'select' || ev.target?.isContentEditable
    if (!editable) closeAssistant()
  }
}

/** 世界运行状态轮询（浮窗呼吸光效）。 */
async function pollWorldRunning() {
  if (!projectId.value) return
  try {
    const vo = await http.get(`/api/projects/${projectId.value}/world-clock`)
    worldRunning.value = vo && (vo.worldRunning === 1 || vo.worldRunning === true)
  } catch (_) {
    // 静默：时钟不可用时光效按静止处理
  }
}

watch(projectId, (pid, oldPid) => {
  if (pid && pid !== oldPid) {
    enterProject(pid)
    loadSessions()
    pollWorldRunning()
    handleRouteAsk()
  }
})

// 会话就绪后消费待发送问题队列（「问问助手」可能先于会话加载完成触发）
watch(
  () => assistantState.activeSessionId,
  (id) => {
    if (id) drainPendingAsks()
  }
)

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
  if (projectId.value) {
    enterProject(projectId.value)
    loadSessions()
    pollWorldRunning()
    handleRouteAsk()
  }
  clockTimer = setInterval(pollWorldRunning, 20000)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  if (clockTimer) clearInterval(clockTimer)
})

/** 后端 JSON 字符串 → 对象（损坏返回 null）。 */
function safeJson(s) {
  if (!s) return null
  try {
    return JSON.parse(s)
  } catch (_) {
    return null
  }
}

/** 回答显示预处理：模型输出常为无换行的连续长句（如「…一件事：1. 讲解项目：…2. 播报世界：…」全在一行）→
 *  在句末标点后补换行以分句显示；内容本身已有换行（含代码块/列表）则原样交给 markdown-it（breaks:true 生效）。 */
function displayContent(src) {
  const s = src || ''
  if (!s || s.includes('\n')) return s
  return s.replace(/([。！？；!?;])\s*(?=\S)/g, '$1\n')
}

/** 来源类型图标（M5 来源标注强化：按数据源前缀归类，让来源更可读）。 */
function sourceIcon(source) {
  const s = source || ''
  if (s.includes('世界知识库') || s.includes('人物知识卡')) return '🔍'
  if (s.includes('docs') || s.includes('项目文档')) return '📄'
  if (s.includes('决策链路') || s.includes('actor_ai_call_log') || s.includes('统计')) return '⏱'
  if (s.includes('沙盘干预')) return '🛠'
  if (s.includes('WorldGraphQueryService')) return '🗺'
  if (s.includes('NpcStateService') || s.includes('actor_state')) return '👤'
  if (s.includes('actor_event')) return '📜'
  return '📊'
}
</script>

<style scoped>
.assistant-root {
  position: fixed;
  inset: 0;
  pointer-events: none;
  z-index: var(--z-drawer, 40);
}
.assistant-root > * { pointer-events: auto; }

/* ===== 浮窗按钮 ===== */
.assistant-float {
  position: absolute;
  right: 22px;
  bottom: 22px;
  width: 48px;
  height: 48px;
  border-radius: 50%;
  border: none;
  cursor: pointer;
  color: #fff;
  background: linear-gradient(135deg, var(--color-primary, #4f6ef7), color-mix(in srgb, var(--color-primary, #4f6ef7) 70%, #7c5cff));
  box-shadow: 0 6px 20px rgba(79, 110, 247, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.15s ease;
}
.assistant-float:hover { transform: scale(1.06); }
.assistant-float-glow {
  position: absolute;
  inset: -6px;
  border-radius: 50%;
  border: 2px solid color-mix(in srgb, var(--color-primary, #4f6ef7) 45%, transparent);
  opacity: 0.5;
  animation: assistant-breathe 3.2s ease-in-out infinite;
}
.assistant-float.is-running .assistant-float-glow {
  opacity: 0.9;
  border-color: color-mix(in srgb, var(--color-success, #34c77b) 70%, transparent);
  animation-duration: 1.8s;
}
@keyframes assistant-breathe {
  0%, 100% { transform: scale(0.92); opacity: 0.35; }
  50% { transform: scale(1.12); opacity: 0.9; }
}

/* ===== 浮窗开/关动画（缩放 + 淡入淡出，避免生硬消失） ===== */
.assistant-float-toggle-enter-active,
.assistant-float-toggle-leave-active {
  transition: transform 0.22s ease, opacity 0.22s ease;
}
.assistant-float-toggle-enter-from,
.assistant-float-toggle-leave-to {
  transform: scale(0.5);
  opacity: 0;
}

/* ===== 侧边栏滑入/滑出动画 ===== */
.assistant-slide-enter-active,
.assistant-slide-leave-active {
  transition: transform 0.24s ease, opacity 0.24s ease;
}
.assistant-slide-enter-from,
.assistant-slide-leave-to {
  transform: translateX(32px);
  opacity: 0;
}

/* ===== 侧边栏 ===== */
.assistant-sidebar {
  position: absolute;
  /* 顶栏 z-index(50) 高于助手抽屉(40)：侧边栏从顶栏下方开始，避免头部按钮（新建会话等）被顶栏遮挡 */
  top: var(--shell-topbar-h, 52px);
  right: 0;
  bottom: 0;
  display: flex;
  flex-direction: column;
  background: var(--bg-panel, #fbfcfe);
  border-left: 1px solid var(--border-l1, #eef1f6);
  box-shadow: var(--shadow-lv3, 0 12px 32px rgba(31, 42, 68, 0.16));
}
.assistant-resize-handle {
  position: absolute;
  left: -3px;
  top: 0;
  bottom: 0;
  width: 6px;
  cursor: col-resize;
}
.assistant-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid var(--border-l1, #eef1f6);
}
.assistant-head-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 14px;
  color: var(--text-strong, #1f2a44);
}
.assistant-head-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--text-dim, #8a94a8);
}
.assistant-head-dot.is-running { background: var(--color-success, #34c77b); box-shadow: 0 0 6px var(--color-success, #34c77b); }
.assistant-head-actions { display: flex; gap: 4px; }
.assistant-icon-btn {
  width: 26px;
  height: 26px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--text-dim, #8a94a8);
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
}
.assistant-icon-btn:hover { background: var(--bg-hover, #eef1f6); color: var(--text-main, #3a4660); }

.assistant-session-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border-bottom: 1px solid var(--border-l1, #eef1f6);
}
.assistant-session-select {
  flex: 1;
  min-width: 0;
  padding: 5px 8px;
  border: 1px solid var(--border-l2, #e5e9f2);
  border-radius: 8px;
  background: var(--bg-input, #fff);
  color: var(--text-main, #3a4660);
  font-size: 12.5px;
}
.assistant-mode-badge {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--color-primary, #4f6ef7) 12%, transparent);
  color: var(--color-primary, #4f6ef7);
  white-space: nowrap;
}
.assistant-confirm-select {
  font-size: 11px;
  padding: 2px 6px;
  border: 1px solid var(--border-l2, #e5e9f2);
  border-radius: 999px;
  background: var(--bg-input, #fff);
  color: var(--color-warning, #f0a020);
  white-space: nowrap;
  cursor: pointer;
}

.assistant-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.assistant-empty {
  color: var(--text-dim, #8a94a8);
  font-size: 12.5px;
  line-height: 1.9;
  text-align: center;
  margin-top: 24px;
}
.assistant-msg {
  max-width: 92%;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.assistant-msg.is-user { align-self: flex-end; align-items: flex-end; }
.assistant-msg.is-assistant { align-self: flex-start; align-items: flex-start; }
.assistant-msg-content {
  padding: 9px 12px;
  border-radius: 12px;
  font-size: 13px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--text-main, #3a4660);
}
.assistant-msg-content.markdown-body {
  white-space: normal;
  line-height: 1.7;
}
.assistant-msg-content.markdown-body > *:first-child { margin-top: 0; }
.assistant-msg-content.markdown-body > *:last-child { margin-bottom: 0; }
.assistant-msg-content.markdown-body p { margin: 0 0 10px; }
.assistant-msg-content.markdown-body ul, .assistant-msg-content.markdown-body ol { margin: 0 0 10px; padding-left: 20px; }
.assistant-msg-content.markdown-body code { font-size: 12px; }
.assistant-msg-content.markdown-body pre { max-width: 100%; overflow-x: auto; }
.assistant-msg.is-user .assistant-msg-content {
  background: var(--color-primary, #4f6ef7);
  color: #fff;
  border-bottom-right-radius: 4px;
}
.assistant-msg.is-assistant .assistant-msg-content {
  background: var(--bg-elev, #fff);
  border: 1px solid var(--border-l1, #eef1f6);
  border-bottom-left-radius: 4px;
}
.assistant-msg-content.is-error { color: var(--color-danger, #e5484d); }
.assistant-msg-meta { display: flex; align-items: center; gap: 6px; }
.assistant-mode-chip {
  font-size: 10.5px;
  padding: 1px 8px;
  border-radius: 999px;
  color: var(--text-dim, #8a94a8);
  background: var(--bg-hover, #eef1f6);
}
.assistant-mode-chip.is-fact {
  color: var(--color-success, #34c77b);
  background: color-mix(in srgb, var(--color-success, #34c77b) 12%, transparent);
}
.assistant-typing {
  width: 22px;
  height: 10px;
  background: radial-gradient(circle, var(--text-dim, #8a94a8) 3px, transparent 4px) 0 0 / 8px 10px;
  animation: assistant-typing 0.9s linear infinite;
}
@keyframes assistant-typing { to { background-position: 16px 0; } }
.assistant-card-slot { width: 100%; }

.assistant-tools { display: flex; flex-wrap: wrap; gap: 4px; }
.assistant-tool-chip {
  font-size: 11px;
  color: var(--text-dim, #8a94a8);
  background: var(--bg-hover, #eef1f6);
  padding: 2px 8px;
  border-radius: 999px;
}
.assistant-follows { display: flex; flex-wrap: wrap; gap: 6px; }
.assistant-follow {
  font-size: 12px;
  border: 1px dashed var(--border-l2, #e5e9f2);
  background: transparent;
  color: var(--color-primary, #4f6ef7);
  border-radius: 999px;
  padding: 2px 10px;
  cursor: pointer;
}
.assistant-follow:hover { background: color-mix(in srgb, var(--color-primary, #4f6ef7) 8%, transparent); }
.assistant-sources { font-size: 11.5px; color: var(--text-dim, #8a94a8); }
.assistant-sources summary { cursor: pointer; user-select: none; }
.assistant-source { padding: 2px 0; display: flex; align-items: baseline; gap: 6px; }
.assistant-source-icon { flex: 0 0 auto; }
.assistant-source-name { color: var(--text-main, #3a4660); }
.assistant-source-detail { color: var(--text-dim, #8a94a8); }
.assistant-error { color: var(--color-danger, #e5484d); font-size: 12px; }

.assistant-input {
  display: flex;
  gap: 8px;
  padding: 10px 14px;
  border-top: 1px solid var(--border-l1, #eef1f6);
}
.assistant-textarea {
  flex: 1;
  resize: none;
  border: 1px solid var(--border-l2, #e5e9f2);
  border-radius: 10px;
  padding: 8px 10px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--text-main, #3a4660);
  background: var(--bg-input, #fff);
}
.assistant-textarea:focus { outline: none; border-color: var(--color-primary, #4f6ef7); }
/* V2.0 a11y：:focus 上的 outline:none 会连键盘焦点一起吃掉（WCAG 2.4.7）。
   鼠标聚焦仍走上面的边框变色；键盘聚焦补一圈实心焦点环。 */
.assistant-textarea:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; }
.assistant-send {
  align-self: flex-end;
  padding: 8px 16px;
  border: none;
  border-radius: 10px;
  background: var(--color-primary, #4f6ef7);
  color: #fff;
  font-size: 13px;
  cursor: pointer;
}
.assistant-send:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
