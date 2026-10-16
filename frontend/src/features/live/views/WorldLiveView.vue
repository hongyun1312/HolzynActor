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

  世界直播（WorldLiveView）：把「此刻世界正在发生什么」变成一页可看的东西。

  为什么要有这一页（实测依据，V2.0 §4.1）：
    改造前回答「现在世界在发生什么」只有一处入口——沙盘地图右栏一小块「世界实时事件流」，
    而且必须先进入地图页、还要在 3D 画布旁边挤着看 8 行小字。
    对一个目标是「让世界像真的在运行」的产品，这与「观测优先」原则直接冲突。
    §4.2 P4 的方案是：把「世界实时事件流 + 主要角色当前行动 + 宏观指标 + 世界时钟」
    聚合为一页，作为侧栏置顶第 3 项。

  数据来源（全部复用既有端点，不新增后端接口）：
    · 沙盘总览  GET /api/projects/:id/sandbox/overview   → 角色当前状态快照（states[]）
    · 沙盘实时流 GET /api/projects/:id/sandbox/stream     → 13 类事件（SSE，与沙盘同一契约）
    · 世界时钟  GET /api/projects/:id/world-clock         → 时间锚点（前端每秒自行推算）
    · 宏观状态  GET /api/projects/:id/macro/state         → 人口/粮价/民怨/季节收成

  设计取舍：
    · 本页**只读观测 + 两个时间控制**（开始推进 / 推进一轮），不做干预注入——干预属于沙盘页的职责，
      同一功能两处入口正是本方案要消除的问题（§3 原则 4「同一件事只有一个入口」）。
    · 事件流保留 200 条上限：长时间挂机不会把内存吃满，也不会让用户觉得「刷不完」。
    · 时间控制按钮的初始状态是「未知」而不是假装「运行中」——pause 状态来自 SSE 的 clock 事件，
      在首个事件到达前显示「连接中」，避免错误地告诉用户世界正在推进。
-->
<template>
  <PageShell width="wide" title="世界直播" desc="此刻世界正在发生什么：实时事件流、角色当前行动与宏观指标，不进地图也能看">
    <template #actions>
      <RuntimeStatusPill
        :status="runStatus"
        :status-label="runStatusLabel"
        :time-text="clockText"
        :time-tooltip="clockTooltip"
        :primary-text="runStatus === 'paused' ? '开始推进' : '开始推进'"
        pause-text="暂停"
        advance-text="推进一轮"
        :busy="clockBusy"
        @primary="toggleRun"
        @advance="advanceOne"
      />
    </template>

    <!-- ===== 顶部：连接状态与口径说明（一行，永不折行） ===== -->
    <div class="wl-bar">
      <span class="wl-live" :class="{ 'is-off': !liveConnected }">
        <i class="wl-dot" aria-hidden="true"></i>
        {{ liveConnected ? '实时连接中' : '未连接实时流（正在重试）' }}
      </span>
      <span class="wl-bar-sep"></span>
      <span class="wl-bar-item">世界时钟速率 ×{{ clock.rate ?? '—' }}</span>
      <span class="wl-bar-item">世界第 {{ (clock.gameDay || 0) + 1 }} 日</span>
      <span class="wl-bar-spacer"></span>
      <span class="wl-bar-item wl-muted">本页只读观测；干预与地图定位在「世界地图」页</span>
    </div>

    <!-- ===== 主体两栏：左=实时事件流，右=角色当前行动 + 宏观快照 ===== -->
    <div class="wl-grid">
      <!-- 左：此刻动态流 -->
      <section class="wl-card wl-feed-card">
        <header class="wl-card-head">
          <h2 class="wl-card-title">此刻动态</h2>
          <el-tag size="small" effect="plain">{{ feed.length }} 条</el-tag>
          <span class="wl-card-spacer"></span>
          <el-button size="small" text :disabled="!feed.length" @click="feed = []">清空</el-button>
        </header>

        <EmptyState
          v-if="!feed.length"
          size="panel"
          title="世界里还没有发生过任何事"
          desc="本页会载入最近的历史事件，并实时接续新事件。世界每推进一步，角色的行动、对话与想法都会出现在这里。"
          primary-text="去世界地图推进世界"
          @primary="go('/sandbox')"
        />
        <ol v-else class="wl-feed">
          <li v-for="(it, i) in feedRows" :key="i" class="wl-feed-item">
            <span v-if="it.showDay" class="wl-feed-day">{{ it.dayLabel }}</span>
            <span class="wl-feed-time">{{ it.time }}</span>
            <el-tag size="small" effect="plain" class="wl-feed-badge">{{ it.badge }}</el-tag>
            <span class="wl-feed-text">{{ it.text }}</span>
          </li>
        </ol>
      </section>

      <!-- 右：角色当前行动 + 宏观快照 -->
      <div class="wl-side">
        <section class="wl-card">
          <header class="wl-card-head">
            <h2 class="wl-card-title">角色当前行动</h2>
            <el-tag size="small" effect="plain">{{ actorTotal }}</el-tag>
            <span class="wl-card-spacer"></span>
            <el-button size="small" text @click="go('/characters')">全部角色 →</el-button>
          </header>
          <EmptyState
            v-if="!actors.length"
            size="panel"
            title="没有在场景里的角色"
            desc="世界开始推进后，角色会进入场景并产生行动。"
          />
          <ul v-else class="wl-actors">
            <li
              v-for="a in actors"
              :key="a.npcRef"
              class="wl-actor"
              :title="a.count > 1 ? `同名且当前状态相同的 ${a.count} 个角色（本页合并显示：${a.refs.join('、')}）；完整名单见「普通人群」页` : ''"
              @click="go('/characters')"
            >
              <span class="wl-actor-name">{{ a.npcName || a.npcRef }}</span>
              <el-tag v-if="a.npcType !== 'common'" size="small" effect="plain" type="warning">特殊</el-tag>
              <span v-if="a.count > 1" class="wl-actor-dup">×{{ a.count }}</span>
              <span class="wl-actor-action">{{ a.actionText || a.statusText || '暂无行动记录' }}</span>
            </li>
          </ul>
        </section>

        <section class="wl-card">
          <header class="wl-card-head">
            <h2 class="wl-card-title">宏观快照</h2>
            <span class="wl-card-spacer"></span>
            <el-button size="small" text @click="go('/graph')">世界图谱 →</el-button>
          </header>
          <div class="wl-metrics">
            <MetricCard label="人口" :value="macro?.population" unit="口" hint="宏观人口池总量" />
            <MetricCard
              label="粮价" :value="macro?.grainPrice != null ? Math.round(macro.grainPrice) : null" unit="文/石"
              :hint="macro?.marketActivity != null ? `市集活跃度 ${Math.round(macro.marketActivity)}` : '市集活跃度 —'"
            />
            <MetricCard
              label="民怨" :value="macro?.grievance != null ? Math.round(macro.grievance) : null"
              :hint="macro?.topIssue ? `当前主要议题：${macro.topIssue}` : '暂无显著议题'"
            />
            <MetricCard
              label="季节 · 收成" :value="seasonText"
              :missing="!macro?.season"
              missing-reason="世界运行满 1 天后生成"
              :hint="macro?.topFaction ? `主导势力：${macro.topFaction}` : ''"
            />
          </div>
        </section>
      </div>
    </div>
  </PageShell>
</template>

<script setup>
/**
 * 世界直播页（V2.0 §4.2 P4）。
 *
 * @see docs/设计文档/HolzynActor_界面布局与IA人性化重构_方案_V2.0.md §4.2 P4
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageShell from '@/shared/layout/PageShell.vue'
import EmptyState from '@/shared/components/EmptyState.vue'
import MetricCard from '@/shared/components/MetricCard.vue'
import RuntimeStatusPill from '@/shared/components/RuntimeStatusPill.vue'
import { createSubscription } from '@/shared/sse/sseClient'
import { engineSecondNow, gameSecondNow, formatWorldTimeMinute, calendarNameOf } from '@/shared/worldClock'
import {
  fetchSandboxOverview, sandboxStreamUrl, sandboxInterveneClock,
  fetchWorldClock, fetchMacroState
} from '@/shared/api/home'
import { fetchWorldEvents } from '@/shared/api/world'

const route = useRoute()
const router = useRouter()
const projectId = Number(route.params.id)

/** 实时连接状态（false 时顶部条会明说「未连接」，而不是让用户对着空列表猜） */
const liveConnected = ref(false)
/** 时间控制请求进行中 */
const clockBusy = ref(false)
/** SSE 订阅句柄 */
let sub = null
/** 每秒推进的「当前游戏时刻」（前端按锚点自行推算，不轮询后端） */
let clockTick = null

/** 世界时钟锚点（字段与沙盘页同一份契约） */
const clock = reactive({
  rate: null, gameDay: 0, paused: null,
  engineGameSecond: null, engineRefRealTime: null, engineTickGameSeconds: null,
  gameSecond: null, refRealTime: null, calendarName: ''
})
const liveGameSecond = ref(null)
/** 宏观状态（读失败静默：本页不该因为宏观未初始化而整页报错） */
const macro = ref(null)
/** 此刻动态流（最新在前，上限 200 条） */
const feed = ref([])
/** 角色状态表（npc:state 事件持续覆盖同一 npcRef） */
const statesMap = reactive({})

const FEED_LIMIT = 200
const ACTOR_LIMIT = 10

// ==================== 派生状态 ====================

/**
 * 运行状态：`paused === null` 表示「还没有拿到时钟事件」，此时如实显示未知。
 * <p>刻意不把「未知」渲染成「运行中」——本页存在的意义就是让用户相信屏幕上的世界是真的。</p>
 */
const runStatus = computed(() => {
  if (clock.paused === null || clock.paused === undefined) return 'idle'
  return clock.paused ? 'paused' : 'running'
})
const runStatusLabel = computed(() => (runStatus.value === 'idle' ? '连接中' : ''))

/** 世界时间文本（沙盘页同款格式：青禾村历 0420年05月15日 06时00分） */
const clockText = computed(() => {
  if (liveGameSecond.value == null) return ''
  return formatWorldTimeMinute(liveGameSecond.value, clock.calendarName || '世界历')
})
const clockTooltip = computed(() => {
  if (liveGameSecond.value == null) return '正在从实时流获取世界时间…'
  return `${clockText.value}（世界时钟速率 ×${clock.rate ?? '—'}；时间由锚点 + 速率在前端推算，每秒刷新）`
})

/** 季节 · 收成文本（缺一不可，避免出现「— · 」这类半截值） */
const seasonText = computed(() => {
  const s = macro.value?.season
  const h = macro.value?.harvestFactor
  if (!s) return null
  return h ? `${s} · ${Number(h).toFixed(2)}` : s
})

/**
 * 角色当前行动：特殊 NPC 优先，其次按紧迫度、最后按名字稳定排序。
 *
 * <p>先按「名字 + 当前行动」合并同名同状态的角色。这不是为了少显示几行，而是因为
 * 实测样例世界里 10 个普通 NPC 只有 5 个不同名字（`npc:8..17` → 沈穗/柳三娘/铁柱/陈墨/林小竹 各两个），
 * 两行完全一样时读者无法判断是同一人被列了两次、还是真有两个人，只好去数名单——
 * 合并后标注「×N」并给出 ref 列表 tooltip，信息不减、歧义消除。</p>
 *
 * <p>排序必须稳定，否则每次 SSE 覆盖状态都会让列表跳动，读者无法连续追踪某个角色。</p>
 */
const actors = computed(() => {
  const byKey = new Map()
  for (const a of Object.values(statesMap)) {
    const key = `${a.npcName || a.npcRef}|${a.actionText || a.statusText || ''}`
    const hit = byKey.get(key)
    if (hit) { hit.count += 1; hit.refs.push(a.npcRef) }
    else byKey.set(key, { ...a, count: 1, refs: [a.npcRef] })
  }
  const arr = [...byKey.values()]
  arr.sort((a, b) => {
    const ta = a.npcType === 'common' ? 1 : 0
    const tb = b.npcType === 'common' ? 1 : 0
    if (ta !== tb) return ta - tb
    const ua = Number(a.urgencyLevel || 0)
    const ub = Number(b.urgencyLevel || 0)
    if (ua !== ub) return ub - ua
    return String(a.npcName || a.npcRef).localeCompare(String(b.npcName || b.npcRef), 'zh-Hans-CN')
  })
  return arr.slice(0, ACTOR_LIMIT)
})

/** 场景内角色总数（标签写总数、列表只渲染前 ACTOR_LIMIT 行，避免「10」让人误以为是全部） */
const actorTotal = computed(() => Object.keys(statesMap).length)

// ==================== 数据加载 ====================

function go(path) { router.push(`/project/${projectId}${path}`) }

/** 世界时间锚点 → 前端每秒推算 */
function applyClockPayload(data) {
  if (!data) return
  Object.assign(clock, data)
  const cal = data.calendarName || calendarNameOf(data.gameTimeText)
  if (cal) clock.calendarName = cal
  liveGameSecond.value = calcLiveGameSecond()
}
function calcLiveGameSecond() {
  if (clock.engineGameSecond != null) {
    return engineSecondNow(clock.engineGameSecond, clock.engineRefRealTime, clock.rate, clock.paused, clock.engineTickGameSeconds)
  }
  return gameSecondNow(clock.gameSecond, clock.refRealTime, clock.rate, clock.paused)
}
function startClockTick() {
  stopClockTick()
  clockTick = setInterval(() => { liveGameSecond.value = calcLiveGameSecond() }, 1000)
}
function stopClockTick() { if (clockTick) { clearInterval(clockTick); clockTick = null } }

/** 角色状态：同一 npcRef 只保留一条（与沙盘页 ensureNpc 的语义一致） */
function applyStates(list) {
  for (const s of (list || [])) {
    if (!s || !s.npcRef) continue
    statesMap[s.npcRef] = { ...(statesMap[s.npcRef] || {}), ...s }
  }
}

/** 事件类型 → 中文分类徽标（后端 kind 是英文枚举，不能直接上屏） */
const KIND_BADGE = {
  move_to: '移动', moved: '移动', walking: '移动', arrived: '到达', arrive: '到达',
  talk: '对话', thought: '想法', encounter: '相遇',
  join: '登场', leave: '退场',
  world_frame: '世界帧', frame: '世界帧', weather: '天气',
  decision: '决策', action: '行动', event: '事件', clock: '时钟'
}

function mkItem(time, badge, text) {
  // 世界时间文本形如「青禾村历 0420年05月15日 06时00分」：拆成「日历 + 日期」与「时刻」两段。
  // 同一世界日内的 30 条事件若每条都重复完整的 20 字日期，读者要花力气才能挑出时刻；
  // 因此日期只在「当天第一条」显示（分组标题），其余行只留时刻。
  const m = /^(.*?)\s*(\d{4}年\d{2}月\d{2}日)\s*(\d{2}时\d{2}分)$/.exec(String(time || ''))
  return {
    time: m ? m[3] : String(time || ''),
    dayLabel: m ? `${m[1] ? m[1] + ' ' : ''}${m[2]}`.trim() : '',
    badge,
    text: humanize(String(text).slice(0, 300))
  }
}

const feedRows = computed(() => {
  let lastDay = null
  return feed.value.map((it) => {
    const showDay = !!it.dayLabel && it.dayLabel !== lastDay
    if (it.dayLabel) lastDay = it.dayLabel
    return { ...it, showDay }
  })
})

/**
 * 把开发者标识换成人名：后端事件标题里带 `npc:12` 这类内部引用（实测直接上屏成
 * 「npc:1：开始移动（守脉堂正院）」）。能查到名字就换成人名，查不到则保留原文——
 * 宁可露出 ref，也不要显示成「未知角色」把有用信息抹掉。
 */
function humanize(text) {
  if (!text || !text.includes('npc:')) return text
  return text.replace(/npc:(\d+)/g, (whole, id) => statesMap[`npc:${id}`]?.npcName || whole)
}

/** 时间戳 → HH:MM:SS（实时事件没有「世界时间」字段，用本机时钟；历史事件用世界时间） */
function timeOf(v) {
  const d = v instanceof Date ? v : new Date(v)
  if (Number.isNaN(d.getTime())) return '--:--'
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`
}

/** 追加一条动态（最新在前；超出上限丢弃最旧的） */
function pushFeed(badge, text) {
  if (!text) return
  feed.value.unshift(mkItem(timeOf(new Date()), badge, text))
  if (feed.value.length > FEED_LIMIT) feed.value.length = FEED_LIMIT
}

/**
 * 首屏铺最近的历史事件。
 *
 * <p>必要性（实测）：世界默认是暂停的，只订阅 SSE 的话「此刻动态」在用户第一次进页时永远是空面板——
 * 用户第一眼看到的不是「世界已经运行过」的证据，而是一个空框，反而削弱了「世界在真实运行」的感受。
 * 这里把最近 30 条世界事件按时间倒序铺上去，之后由 SSE 增量接续。</p>
 */
async function loadRecentEvents() {
  try {
    const res = await fetchWorldEvents(projectId, { limit: 30 })
    const arr = Array.isArray(res) ? res : (res?.records || res?.list || [])
    const sorted = [...arr].sort((a, b) => Number(b.id || b.eventId || 0) - Number(a.id || a.eventId || 0))
    feed.value = sorted.map(ev => mkItem(
      ev.gameTimeText || timeOf(ev.createdAt),
      KIND_BADGE[ev.kind] || '世界',
      `${ev.title || ''}${ev.content ? ' — ' + ev.content : ''}`
    )).filter(it => it.text).slice(0, FEED_LIMIT)
  } catch (_) { /* 历史事件拿不到不影响实时流 */ }
}

// ==================== SSE 分发（13 事件契约，与沙盘页同源） ====================

function handleSseEvent(name, data) {
  if (!data) return
  switch (name) {
    case 'sandbox:init':
      if (data.clock) applyClockPayload(data.clock)
      applyStates(data.states || [])
      break
    case 'npc:state': {
      const cur = statesMap[data.npcRef]
      if (cur) Object.assign(cur, data)
      else if (data.npcRef) statesMap[data.npcRef] = { ...data }
      break
    }
    case 'npc:thought':
      pushFeed('想法', `${nameOf(data.npcRef)}：${data.text}`)
      break
    case 'npc:talk':
      pushFeed('对话', `${nameOf(data.npcRef)}：${data.text || ''}`)
      break
    case 'npc:encounter':
      pushFeed('相遇', data.greet || data.message || '')
      break
    case 'npc:walking':
      pushFeed('移动', `${data.npcName || nameOf(data.npcRef)} 正走向 ${data.targetLocationName || '某处'}`)
      break
    case 'npc:arrived':
      pushFeed('到达', `${nameOf(data.npcRef)} 到达${data.locationName ? `（${data.locationName}）` : ''}`)
      break
    case 'npc:join':
      if (data.npcRef) statesMap[data.npcRef] = { ...(statesMap[data.npcRef] || {}), npcRef: data.npcRef, npcName: data.name, npcType: data.npcType }
      pushFeed('登场', `${data.name || data.npcRef} 进入场景`)
      break
    case 'npc:leave':
      delete statesMap[data.npcRef]
      pushFeed('退场', `${data.name || data.npcRef} 离开场景`)
      break
    case 'scene:event':
      pushFeed('事件', `${data.title || ''}${data.content ? ' — ' + data.content : ''}`)
      break
    case 'clock': {
      const was = clock.paused
      applyClockPayload(data)
      if (was !== data.paused) pushFeed('时钟', data.paused ? '世界已暂停' : '世界继续运行')
      break
    }
    case 'weather':
      pushFeed('天气', data.message || `天气变化：${data.condition || ''}`)
      break
    default: break
  }
}

/** npcRef → 可读名字（状态表里没有时回落到 ref 本身，绝不显示 undefined） */
function nameOf(ref) {
  if (!ref) return '未知角色'
  return statesMap[ref]?.npcName || ref
}

// ==================== 时间控制（唯一两处，语义已在按钮 tooltip 说明） ====================

/** 主按钮：当前暂停 → 开始持续推进；当前运行 → 暂停 */
async function toggleRun() {
  if (runStatus.value === 'idle') { ElMessage.info('正在连接世界实时流，稍后再试'); return }
  clockBusy.value = true
  try {
    await sandboxInterveneClock(projectId, { worldRunning: clock.paused ? 1 : 0 })
    await refreshClock()
    ElMessage.success(clock.paused ? '世界已开始推进' : '世界已暂停')
  } catch (e) {
    ElMessage.error(`时间控制失败：${e?.message || '未知错误'}`)
  } finally { clockBusy.value = false }
}

/** 次按钮：只推进一轮（单步），与「开始推进」的持续运行不同 */
async function advanceOne() {
  clockBusy.value = true
  try {
    await sandboxInterveneClock(projectId, { advance: true })
    await refreshClock()
  } catch (e) {
    ElMessage.error(`推进失败：${e?.message || '未知错误'}`)
  } finally { clockBusy.value = false }
}

async function refreshClock() {
  try { applyClockPayload(await fetchWorldClock(projectId)) } catch (_) { /* 静默：SSE 会补正 */ }
}

// ==================== 生命周期 ====================

async function loadSnapshot() {
  try { applyStates((await fetchSandboxOverview(projectId))?.states || []) } catch (_) { /* 静默 */ }
  try { macro.value = await fetchMacroState(projectId) } catch (_) { macro.value = null }
  await Promise.all([refreshClock(), loadRecentEvents()])
}

function connectStream() {
  try {
    sub = createSubscription({
      url: sandboxStreamUrl(projectId),
      events: [
        'sandbox:init', 'npc:walking', 'npc:arrived', 'npc:state', 'npc:thought',
        'npc:talk', 'npc:join', 'npc:leave', 'scene:event', 'clock', 'weather', 'npc:encounter'
      ],
      heartbeatTimeoutMs: 45000,
      onConnected: () => { liveConnected.value = true },
      onEvent: handleSseEvent,
      onError: () => { liveConnected.value = false },
      onGiveUp: () => {
        liveConnected.value = false
        ElMessage.warning('实时连接重试达上限：本页数据已停止刷新，可刷新页面重连')
      }
    })
  } catch (_) { /* 连接失败静默；顶部状态条已经说明 */ }
}

onMounted(async () => {
  await loadSnapshot()
  startClockTick()
  connectStream()
})

// 项目切换（顶栏项目切换器会复用同一路由）时必须重建订阅，否则会继续显示旧项目的事件
watch(() => route.params.id, async (id) => {
  if (!id) return
  sub?.close?.()
  sub = null
  Object.keys(statesMap).forEach(k => delete statesMap[k])
  feed.value = []
  clock.paused = null
  macro.value = null
  liveConnected.value = false
  await loadSnapshot()
  connectStream()
})

onBeforeUnmount(() => {
  sub?.close?.()
  sub = null
  stopClockTick()
})
</script>

<style scoped>
/* ===== 顶部状态条：一行，永不折行（窄屏靠省略号让位，不换行） ===== */
.wl-bar {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 12px;
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-md, 10px);
  font-size: 0.78rem; color: var(--text-secondary);
  white-space: nowrap; overflow: hidden;
}
.wl-live { display: inline-flex; align-items: center; gap: 6px; flex-shrink: 0; color: var(--state-success, #22c55e); font-weight: 600; }
.wl-live.is-off { color: var(--state-warn, #f59e0b); }
.wl-dot { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.wl-bar-sep { width: 1px; height: 14px; background: var(--border-l2); flex-shrink: 0; }
.wl-bar-item { flex-shrink: 0; }
.wl-bar-spacer { flex: 1; min-width: 8px; }
.wl-muted { color: var(--text-tertiary); overflow: hidden; text-overflow: ellipsis; }

/* ===== 两栏栅格：左宽右窄；1024 档以下由外壳进入 medium，本页随之收成单栏 ===== */
.wl-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.55fr) minmax(340px, 1fr);
  gap: var(--content-gap, 16px);
  align-items: start;
}
.wl-side { display: flex; flex-direction: column; gap: var(--content-gap, 16px); min-width: 0; }

/* ===== 卡片 ===== */
.wl-card {
  display: flex; flex-direction: column;
  background: var(--bg-layer-1);
  border: 1px solid var(--border-l1);
  border-radius: var(--radius-md, 10px);
  padding: 12px 14px;
  min-width: 0;
}
.wl-card-head { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.wl-card-title { margin: 0; font-size: 0.92rem; font-weight: 700; color: var(--text-primary); }
.wl-card-spacer { flex: 1; }

/* ===== 动态流：整页唯一的长列表，限高后自行滚动（不再把整页撑长） ===== */
.wl-feed-card { max-height: 62vh; }
.wl-feed {
  list-style: none; margin: 0; padding: 0;
  overflow-y: auto; min-height: 0;
}
.wl-feed-item {
  display: flex; align-items: baseline; gap: 8px;
  padding: 6px 2px;
  border-bottom: 1px dashed var(--border-l1);
  font-size: 0.8rem; line-height: 1.6;
}
.wl-feed-item:last-child { border-bottom: none; }
/* 世界日期分组标题：同一世界日只出现一次，避免 30 行重复同一串长日期 */
.wl-feed-day { flex-shrink: 0; color: var(--text-placeholder); font-size: 0.72rem; font-weight: 600; }
.wl-feed-time { flex-shrink: 0; color: var(--text-tertiary); font-variant-numeric: tabular-nums; font-size: 0.72rem; }
.wl-feed-badge { flex-shrink: 0; }
.wl-feed-text { color: var(--text-regular); min-width: 0; word-break: break-word; }

/* ===== 角色当前行动 ===== */
.wl-actors { list-style: none; margin: 0; padding: 0; max-height: 34vh; overflow-y: auto; }
.wl-actor {
  display: flex; align-items: baseline; gap: 8px;
  padding: 6px 2px; cursor: pointer;
  border-bottom: 1px dashed var(--border-l1);
  font-size: 0.8rem;
}
.wl-actor:last-child { border-bottom: none; }
.wl-actor:hover .wl-actor-name { color: var(--accent-text); }
.wl-actor-name { flex-shrink: 0; font-weight: 600; color: var(--text-primary); }
/* 同名同状态合并标记：弱化但可见，鼠标悬停给出 ref 列表 */
.wl-actor-dup {
  flex-shrink: 0;
  font-size: 0.7rem; font-weight: 600;
  padding: 0 5px; border-radius: 999px;
  color: var(--text-tertiary);
  background: var(--bg-layer-2);
  border: 1px solid var(--border-l1);
}
.wl-actor-action { color: var(--text-secondary); min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* ===== 宏观快照：一卡一数（复用 MetricCard，2×2） ===== */
.wl-metrics { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }

/* 1024 档以下：单栏（与外壳 shellMode 的降级方向一致） */
@media (max-width: 1024px) {
  .wl-grid { grid-template-columns: 1fr; }
  .wl-feed-card { max-height: none; }
}
</style>