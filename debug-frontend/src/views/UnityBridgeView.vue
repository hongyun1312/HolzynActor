<template>
  <div class="unity-bridge">
    <div class="head-row">
      <h2 class="page-title">Unity 对接 U1（服务端对接层：WS 通道 / 时钟权威 / 感知 / 环境 / 干预 / 坐标）</h2>
      <div class="toolbar">
        <el-button size="small" type="primary" :disabled="!online" @click="refreshStatus">刷新状态</el-button>
        <span class="hint">本页为 Unity 客户端模拟器：连接 /ws/unity/{projectId}，上行 clock_report 驱动引擎推进、
          perception 触发 NPC 即时决策、environment 覆盖帧天气；下行事件流实时回放（envelope 契约 v1）</span>
      </div>
    </div>

    <!-- ==================== 一、连接与状态 ==================== -->
    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-title">连接与通道状态（GET /api/projects/{id}/unity/status）</div>
      </template>
      <div class="connect-row">
        <el-input v-model="projectId" placeholder="项目 ID（测试项目=37 青禾村）" style="width: 220px" />
        <el-button size="small" type="success" :disabled="!!ws || !projectId" @click="connect">连接</el-button>
        <el-button size="small" type="danger" :disabled="!ws" @click="disconnect">断开</el-button>
        <el-button size="small" :disabled="!ws" @click="sendHello">hello</el-button>
        <el-button size="small" :disabled="!ws" @click="sendHeartbeat">heartbeat</el-button>
        <el-button size="small" :disabled="!ws" @click="pullInit">拉取 init（REST）</el-button>
        <el-tag v-if="ws" size="small" type="success" effect="dark">已连接（seq={{ seq }}）</el-tag>
        <el-tag v-else size="small" type="info" effect="plain">未连接</el-tag>
      </div>
      <el-descriptions v-if="status" :column="4" size="small" border class="status-desc">
        <el-descriptions-item label="enabled">{{ status.enabled }}</el-descriptions-item>
        <el-descriptions-item label="wsPath">{{ status.wsPath }}</el-descriptions-item>
        <el-descriptions-item label="connected">{{ status.connected }}</el-descriptions-item>
        <el-descriptions-item label="sessionCount">{{ status.sessionCount }}</el-descriptions-item>
        <el-descriptions-item label="lastSeq">{{ status.lastSeq }}</el-descriptions-item>
        <el-descriptions-item label="externalClockActive">
          <el-tag size="small" :type="status.externalClockActive ? 'success' : 'info'" effect="plain">
            {{ status.externalClockActive ? 'Unity 时钟权威（引擎不自走）' : '内部时钟（自走）' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="worldActive">
          <el-tag size="small" :type="status.worldActive ? 'success' : 'warning'" effect="plain">
            {{ status.worldActive ? '激活' : '静止' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="坐标换算">
          <span class="mono">{{ status.coordinate?.meterPerCell }} 米/格 原点({{ status.coordinate?.originOffsetX }},{{ status.coordinate?.originOffsetZ }})</span>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- ==================== 二、上行发送面板 ==================== -->
    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-title">上行发送（Unity → HA；seq 自动递增；载荷编辑后点发送）</div>
      </template>
      <div class="send-row">
        <el-select v-model="sendType" size="small" style="width: 170px">
          <el-option label="clock_report 时钟上报" value="clock_report" />
          <el-option label="perception 感知上报" value="perception" />
          <el-option label="environment 环境上报" value="environment" />
          <el-option label="player_action 玩家行动" value="player_action" />
          <el-option label="intervention 干预(WS兜底)" value="intervention" />
          <el-option label="hello 握手" value="hello" />
          <el-option label="heartbeat 心跳" value="heartbeat" />
        </el-select>
        <el-button size="small" type="primary" :disabled="!ws" @click="sendPayload">发送 envelope</el-button>
        <!-- 快捷动作 -->
        <el-button size="small" :disabled="!ws" @click="quickClock('+1 游戏分钟')">时钟 +1 分钟</el-button>
        <el-button size="small" :disabled="!ws" @click="quickClock('+1 游戏小时')">时钟 +1 小时</el-button>
        <el-button size="small" :disabled="!ws" @click="quickClock('+1 游戏日')">时钟 +1 日</el-button>
        <el-button size="small" :disabled="!ws" @click="quickPerception">感知（沈砚秋/陆沉舟）</el-button>
        <el-button size="small" :disabled="!ws" @click="quickEnvironment">环境（雨）</el-button>
      </div>
      <div class="payload-editor">
        <el-input v-model="sendPayloadText" type="textarea" :rows="6" class="mono payload-input"
          placeholder="payload JSON（发送时自动包 envelope：{type, seq, projectId, gameSecond, payload, traceId}）" />
        <div class="payload-actions">
          <span class="dim">当前 gameSecond 基准：{{ lastClockGameSecond ?? '未知（先发 clock_report）' }}</span>
        </div>
      </div>
    </el-card>

    <!-- ==================== 三、下行事件流 ==================== -->
    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-title">下行事件流（HA → Unity，envelope 实时回放）</div>
      </template>
      <div class="event-toolbar">
        <el-button size="small" @click="clearEvents">清空</el-button>
        <el-checkbox v-model="autoScroll" size="small">自动滚动</el-checkbox>
        <el-checkbox v-model="showAll" size="small">显示心跳</el-checkbox>
        <span class="dim">{{ events.length }} 条</span>
      </div>
      <div ref="eventBox" class="event-box">
        <div v-for="(e, i) in visibleEvents" :key="i" class="event-row" :class="rowClass(e.type)">
          <span class="event-time mono dim">{{ e.time }}</span>
          <el-tag size="small" :type="tagOf(e.type)" effect="plain" class="event-tag">{{ e.type }}</el-tag>
          <span class="event-json mono">{{ e.json }}</span>
        </div>
        <div v-if="!events.length" class="dim center-pad">暂无下行事件（连接后等待 clock/state/event…）</div>
      </div>
    </el-card>

    <!-- ==================== 四、init / 状态 JSON ==================== -->
    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-title">init 全量初始化 / status 状态 JSON（REST）</div>
      </template>
      <div class="init-toolbar">
        <el-button size="small" type="primary" @click="pullInit">拉取 /init</el-button>
        <el-button size="small" @click="refreshStatus">拉取 /status</el-button>
      </div>
      <pre class="json-view mono">{{ prettyJson(initJson) }}</pre>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { get } from '../api'

const projectId = ref('37')
const online = ref(false)
const status = ref(null)

// ==================== WS ====================
const ws = ref(null)
const seq = ref(0)
const events = ref([])
const autoScroll = ref(true)
const showAll = ref(false)
const eventBox = ref(null)
const lastClockGameSecond = ref(null)

const sendType = ref('clock_report')
const sendPayloadText = ref('{\n  "gameSecond": 3600,\n  "rate": 60\n}')

function wsUrl() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  return `${proto}://${location.host}/ws/unity/${projectId.value.trim()}`
}

function connect() {
  if (!projectId.value || ws.value) return
  const sock = new WebSocket(wsUrl())
  ws.value = sock
  sock.onopen = () => {
    pushEvent('ws_open', { message: '连接建立', url: wsUrl() })
    sendHello()
    refreshStatus()
  }
  sock.onmessage = (ev) => {
    let obj = null
    try { obj = JSON.parse(ev.data) } catch (_) { obj = { raw: ev.data } }
    if (obj && obj.type === 'clock' && obj.payload && obj.payload.gameSecond != null) {
      lastClockGameSecond.value = obj.payload.gameSecond
    }
    pushEvent(obj?.type || 'raw', obj)
  }
  sock.onclose = (ev) => {
    pushEvent('ws_close', { code: ev.code, reason: ev.reason })
    ws.value = null
    refreshStatus()
  }
  sock.onerror = () => pushEvent('ws_error', { message: '连接错误' })
}

function disconnect() {
  if (ws.value) {
    ws.value.close(1000, 'client close')
    ws.value = null
  }
}

function sendEnvelope(type, payload) {
  if (!ws.value) return
  seq.value += 1
  const env = {
    type,
    seq: seq.value,
    projectId: Number(projectId.value),
    gameSecond: type === 'clock_report' ? (payload?.gameSecond ?? null) : null,
    payload: payload || {},
    traceId: 'dbg-' + Date.now().toString(36)
  }
  ws.value.send(JSON.stringify(env))
  pushEvent('↑ ' + type, env)
}

function sendHello() { sendEnvelope('hello', { client: 'debug-frontend', version: '1.0' }) }
function sendHeartbeat() { sendEnvelope('heartbeat', {}) }

function sendPayload() {
  let payload = {}
  try {
    payload = sendPayloadText.value.trim() ? JSON.parse(sendPayloadText.value) : {}
  } catch (_) {
    pushEvent('parse_error', { message: 'payload JSON 解析失败' })
    return
  }
  sendEnvelope(sendType.value, payload)
}

// 快捷动作
function quickClock(label) {
  const base = lastClockGameSecond.value ?? 0
  const plus = label.includes('日') ? 86400 : label.includes('小时') ? 3600 : 60
  const payload = { gameSecond: base + plus, rate: 60 }
  sendPayloadText.value = JSON.stringify(payload, null, 2)
  sendEnvelope('clock_report', payload)
}

function quickPerception() {
  const base = lastClockGameSecond.value ?? null
  const payload = {
    targetNpcRefs: ['npc:110', 'npc:111'],
    text: '玩家走进青禾村，目光在你们身上停留，似乎在寻找什么',
    gameSecond: base,
    broadcast: false
  }
  sendPayloadText.value = JSON.stringify(payload, null, 2)
  sendEnvelope('perception', payload)
}

function quickEnvironment() {
  const payload = { weather: '雨', temperature: 18.0, wind: '东风2级' }
  sendPayloadText.value = JSON.stringify(payload, null, 2)
  sendEnvelope('environment', payload)
}

// ==================== REST ====================
const initJson = ref(null)

async function pullInit() {
  try {
    initJson.value = await get(`/api/projects/${projectId.value.trim()}/unity/init`)
    pushEvent('init', { counts: initJson.value?.counts, clock: initJson.value?.clock })
  } catch (e) {
    pushEvent('init_error', { message: String(e?.message || e) })
  }
}

async function refreshStatus() {
  try {
    status.value = await get(`/api/projects/${projectId.value.trim()}/unity/status`)
    online.value = true
  } catch (_) {
    online.value = false
  }
}

// ==================== 事件回放 ====================
const visibleEvents = computed(() => showAll.value ? events.value : events.value.filter(e => e.type !== 'heartbeat'))

function pushEvent(type, data) {
  events.value.push({
    time: new Date().toLocaleTimeString(),
    type,
    json: JSON.stringify(data)
  })
  if (events.value.length > 500) events.value.splice(0, events.value.length - 500)
  if (autoScroll.value) {
    nextTick(() => {
      if (eventBox.value) eventBox.value.scrollTop = eventBox.value.scrollHeight
    })
  }
}

function clearEvents() { events.value = [] }

function tagOf(type) {
  if (type.includes('error') || type === 'ws_close') return 'danger'
  if (type.startsWith('↑')) return 'warning'
  if (['connected', 'clock', 'weather', 'init'].includes(type)) return 'success'
  if (type === 'heartbeat') return 'info'
  return 'primary'
}

function rowClass(type) {
  if (type.includes('error')) return 'row-error'
  if (type.startsWith('↑')) return 'row-up'
  return ''
}

function prettyJson(obj) {
  return obj ? JSON.stringify(obj, null, 2) : '（点击上方按钮拉取）'
}

onMounted(() => { refreshStatus(); if (online.value === false) pullInit() })
onBeforeUnmount(() => disconnect())
</script>

<style scoped>
.head-row { display: flex; align-items: baseline; justify-content: space-between; gap: 16px; margin-bottom: 14px; flex-wrap: wrap; }
.page-title { margin: 0; font-size: 17px; color: var(--text-primary); }
.toolbar { display: flex; align-items: center; gap: 10px; }
.hint { font-size: 12px; color: var(--text-tertiary); max-width: 720px; }
.card { margin-bottom: 14px; }
.card-title { font-weight: 600; font-size: 13px; color: var(--text-primary); }
.connect-row { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.status-desc { margin-top: 4px; }
.send-row { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
.payload-editor { display: flex; gap: 12px; align-items: flex-start; }
.payload-input { flex: 1; font-size: 12px; }
.payload-actions { width: 240px; padding-top: 4px; }
.event-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 8px; }
.event-box { height: 300px; overflow-y: auto; background: var(--bg-layer-0, #0d1117); border: 1px solid var(--border-l2); border-radius: var(--radius-sm, 6px); padding: 8px; font-size: 12px; }
.event-row { display: flex; align-items: flex-start; gap: 8px; padding: 3px 0; border-bottom: 1px dashed var(--border-l1, #222); }
.event-row.row-error { background: rgba(244, 63, 94, 0.08); }
.event-row.row-up { background: rgba(245, 158, 11, 0.06); }
.event-time { flex-shrink: 0; width: 92px; }
.event-tag { flex-shrink: 0; min-width: 90px; text-align: center; }
.event-json { flex: 1; word-break: break-all; white-space: pre-wrap; }
.center-pad { padding: 24px 0; text-align: center; }
.init-toolbar { display: flex; gap: 8px; margin-bottom: 8px; }
.json-view { max-height: 420px; overflow-y: auto; background: var(--bg-layer-0, #0d1117); border: 1px solid var(--border-l2); border-radius: var(--radius-sm, 6px); padding: 10px; font-size: 12px; white-space: pre-wrap; word-break: break-all; }
.mono { font-family: 'JetBrains Mono', Consolas, monospace; }
.dim { color: var(--text-tertiary); font-size: 12px; }
</style>
