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
    新首页（M4，features/home/views）——项目默认落地页，取代旧 3D 沙盘。
    「双主角并置」：左栏角色活动流（活着的角色）+ 右栏世界演化面板（演化的世界），
    中栏 2D 世界地图串联两者（a16z ai-town 范式：地图即 UI 主角）。
    数据源全复用：/world-map + /movements + 沙盘 SSE + world-graph + /macro/* + /ai-runtime/status（零新后端）。
  -->
  <div class="hm-home">
    <!-- 顶栏控制条 -->
    <HomeTopbar
      :clock="clock"
      :clock-text="clockText"
      :rt-decision="rtDecision"
      :weather="weather"
      :live-connected="liveConnected"
      :clock-busy="clockBusy"
      :advancing="advancing"
      :rt-busy="rtBusy"
      :weather-busy="weatherBusy"
      @rate-change="onRateChange"
      @toggle-pause="togglePause"
      @advance="advanceOne"
      @rt-toggle="toggleRtManual"
      @weather-change="onWeatherChange"
      @open-intervene="interveneOpen = true"
      @open-roleplay="roleplayOpen = true"
    />

    <!-- 三层主体 -->
    <div class="hm-body">
      <!-- 左栏：角色活动流 -->
      <aside class="hm-col hm-col-left">
        <CharacterActivityPanel
          :npcs="npcList"
          :selected-ref="selectedRef"
          :moving-map="movingMap"
          :filter="filter"
          @select="selectNpc"
          @change-filter="filter = $event"
          @talk="doTalk"
          @act="doAction"
          @event-inject="doEvent"
        />
      </aside>

      <!-- 中栏：2D 世界地图（主角） -->
      <main class="hm-col hm-col-center">
        <WorldMap2D
          :regions="regions"
          :roads="roads"
          :placements="placements"
          :homes="homes"
          :movements="movements"
          :npcs="npcList"
          :scene-size="sceneSize"
          :selected-ref="selectedRef"
          :active-location-id="activeLocationId"
          :show="showLayers"
          @pick-region="onPickRegion"
          @pick-placement="onPickPlacement"
          @pick-home="onPickHome"
          @pick-npc="onPickNpc"
          @pick-empty="onPickEmpty"
        />
        <!-- 图层筛选（中栏地图右下角） -->
        <div class="hm-layers">
          <el-checkbox v-model="showLayers.roads" size="small">道路</el-checkbox>
          <el-checkbox v-model="showLayers.buildings" size="small">建筑</el-checkbox>
          <el-checkbox v-model="showLayers.homes" size="small">住所</el-checkbox>
        </div>
        <!-- 地点详情卡（点击区域/建筑/住所弹出） -->
        <div v-if="locationDetail" class="hm-loc-card">
          <div class="hm-loc-head">
            <span class="hm-loc-name">{{ locationDetail.name }}</span>
            <el-tag v-if="locationDetail.type" size="small" effect="plain">{{ locationDetail.type }}</el-tag>
            <el-button text size="small" class="hm-loc-close" @click="locationDetail = null">✕</el-button>
          </div>
          <div v-if="locationDetail.intro" class="hm-loc-intro">{{ locationDetail.intro }}</div>
          <div class="hm-loc-stats">
            <span class="hm-loc-stat">在场 {{ locationDetail.nearby?.length ?? 0 }} 人</span>
            <span class="hm-loc-stat">住所 {{ (locationDetail.homes || []).length }} 间</span>
            <span class="hm-loc-stat">摆放 {{ (locationDetail.placements || []).length }} 处</span>
          </div>
          <div v-if="locationDetail.nearby?.length" class="hm-loc-nearby">
            <div class="hm-loc-sub">在场 NPC</div>
            <div v-for="n in locationDetail.nearby" :key="n" class="hm-loc-npc" @click="selectNpcByName(n)">
              <i class="hm-loc-dot" />{{ n }}
            </div>
          </div>
        </div>
      </main>

      <!-- 右栏：世界演化面板 -->
      <aside class="hm-col hm-col-right">
        <WorldEvolutionPanel
          :feed="feed"
          :macro="macro"
          :curve-data="curveData"
          :curve-open="curveOpen"
          :meso-groups="mesoGroups"
          :emergence-events="emergenceEvents"
          :ai-runtime="aiRuntime"
          :ai-window="aiWindow"
          @toggle-curve="toggleCurve"
          @clear-feed="feed = []"
        />
      </aside>
    </div>

    <!-- 干预抽屉 -->
    <InterventionDrawer
      v-model="interveneOpen"
      :npcs="npcList"
      :clock="clock"
      @talk="doTalk"
      @act="doAction"
      @event-inject="doEvent"
      @rate-change="onRateChange"
      @pause="togglePause"
      @advance="advanceOne"
    />

    <!-- 对戏/演绎抽屉（旧演化页废弃后保留的核心能力） -->
    <RoleplayDrawer v-model="roleplayOpen" :project-id="projectId" />

    <!-- 初始加载态 -->
    <div v-if="!loaded" v-loading="true" class="hm-loading" />
  </div>
</template>

<script setup>
/**
 * 新首页（M4）——项目默认落地页，取代旧 3D 沙盘（WorldSandboxView + Three.js）。
 * <p>职责：三层壳内「双主角并置」——左栏角色活动流 / 中栏 2D 世界地图（主角）/ 右栏世界演化面板，
 * 顶栏世界时钟 + 实时决策 + 天气 + 干预 + 对戏抽屉。数据源全复用（零新后端）：</p>
 * <ul>
 *   <li>地图：GET /world-map（区域/道路/建筑/住所，坐标已由 home.js 换算中心化）+ GET /movements（在途）；</li>
 *   <li>角色实时：沙盘 SSE（sandbox:init / npc:moved / npc:walking / npc:arrived / npc:state / npc:thought /
 *       npc:talk / npc:join / npc:leave / scene:event / clock / weather / npc:encounter）；</li>
 *   <li>演化面板：/macro/*（状态/事件/中观/涌现/快照曲线）+ /ai-runtime/status（AI 运行状态）；</li>
 *   <li>干预：POST /sandbox/intervene/*（插话/行动/事件/时钟）+ 天气切换 + 实时决策激活。</li>
 * </ul>
 * <p>废弃承接：旧 3D 沙盘与旧演化页（EvolveView）路由不再作为主入口；对戏/演绎能力封装为
 * {@link RoleplayDrawer} 抽屉保留（用户决策：废弃演化页、保留对戏能力）。</p>
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { createSubscription } from '@/shared/sse/sseClient'
import { gameSecondNow, engineSecondNow, formatWorldTimeMinute, formatCompactDayTime, calendarNameOf } from '@/shared/worldClock'
import { useAiRuntime } from '@/shared/layout/useAiRuntime'
import {
  fetchHomeWorldMap, fetchHomeLocationDetail, fetchHomeMovements,
  fetchSandboxOverview, sandboxStreamUrl,
  sandboxInterveneTalk, sandboxInterveneAction, sandboxInterveneEvent, sandboxInterveneClock,
  sandboxSwitchWeather,
  fetchWorldClock, fetchWorldFrame, fetchEnvironmentContext,
  fetchRealtimeDecision, setRealtimeDecision,
  fetchMacroState, fetchMacroEvents, fetchMacroGroups, fetchMacroEmergence, fetchMacroSnapshots,
  fetchWorldLocations
} from '@/shared/api/home'
import HomeTopbar from '../components/HomeTopbar.vue'
import WorldMap2D from '../components/WorldMap2D.vue'
import CharacterActivityPanel from '../components/CharacterActivityPanel.vue'
import WorldEvolutionPanel from '../components/WorldEvolutionPanel.vue'
import InterventionDrawer from '../components/InterventionDrawer.vue'
import RoleplayDrawer from '../components/RoleplayDrawer.vue'

const route = useRoute()
const projectId = Number(route.params.id)

// ==================== 状态 ====================
const loaded = ref(false)
const liveConnected = ref(false)

/** 世界时钟 */
const clock = reactive({ gameDay: 1, gameHour: 0, gameSecond: 0, paused: 0, rate: 4, gameTimeText: '', refRealTime: null, calendarName: null })
/** 本地插值后的当前游戏秒 */
const liveGameSecond = ref(0)
let clockTick = null

/** NPC 状态表：npcRef → 状态对象 */
const statesMap = reactive({})

/** 世界地图（中心化坐标，home.js 已换算） */
const sceneSize = ref(500)
const regions = ref([])
const roads = ref([])
const placements = ref([])
const homes = ref([])

/** 在途移动表（刷新/SSE walking 增量） */
const movements = ref([])

/** 地点表（world-locations：名称/类型/简介/重要度） */
const worldLocations = ref([])

/** 图层筛选 */
const showLayers = reactive({ roads: true, buildings: true, homes: true, npcs: true })

/** 抽屉开关 */
const interveneOpen = ref(false)
const roleplayOpen = ref(false)

/** 事件流 */
const feed = ref([])
const selectedRef = ref(null)
const filter = ref('')
const activeLocationId = ref(null)
const locationDetail = ref(null)

/** 干预 busy 态 */
const talking = ref(false)
const acting = ref(false)
const injecting = ref(false)
const clockBusy = ref(false)
const advancing = ref(false)
const weatherBusy = ref(false)
const rtBusy = ref(false)

/** 实时决策激活状态 */
const rtDecision = reactive({ active: false, manualEnabled: false, sandboxActive: false, evolutionActive: false })

/** 天气 */
const weather = reactive({ condition: null, temperature: null, wind: null, region: null, season: null, seasonName: null, month: null, phase: null, phaseName: null, sunrise: null, sunset: null, feelsLikeC: null })

/** 宏观 + 曲线 */
const macro = ref(null)
const macroEvents = ref([])
const mesoGroups = ref([])
const emergenceEvents = ref([])
const curveData = ref([])
const curveOpen = ref(false)
let macroTimer = null

/** AI 运行状态（顶栏/右栏共用轮询快照） */
const aiWindow = 30
const aiRuntime = useAiRuntime(() => projectId)

// ==================== 派生 ====================

/** 全部 NPC 状态列表（按引用排序） */
const npcList = computed(() =>
  Object.values(statesMap)
    .filter((n) => (filter.value ? n.npcType === filter.value : true))
    .sort((a, b) => (a.npcRef < b.npcRef ? -1 : 1))
)

/** 选中 NPC */
const selected = computed(() => (selectedRef.value ? statesMap[selectedRef.value] : null))

/** 移动表 → 左栏移动中状态映射（npcRef → {targetLocationName, progress}） */
const movingMap = computed(() => {
  const m = {}
  for (const mv of movements.value) {
    m[mv.npcRef] = { targetLocationName: mv.targetLocationName, progress: mv.progress }
  }
  return m
})

/** 时钟文案（本地插值 → 后端文本 → 紧凑回退） */
const clockText = computed(() => {
  const gs = liveGameSecond.value
  if (clock.refRealTime != null && clock.rate) return formatWorldTimeMinute(gs, clock.calendarName)
  if (clock.gameTimeText) return clock.gameTimeText
  if (clock.gameDay != null) return formatCompactDayTime(gs || clock.gameSecond || 0)
  return '—'
})

// ==================== 初始化 + SSE ====================

let sub = null

onMounted(async () => {
  await Promise.all([loadOverview(), loadWorldMap(), loadMovements(), loadWeatherAndLocations()])
  connectStream()
  startClockTick()
  activateSandbox()
  loadMacro()
  macroTimer = setInterval(loadMacro, 60000)
  loaded.value = true
})

onBeforeUnmount(() => {
  if (sub) { sub.close(); sub = null }
  if (macroTimer) { clearInterval(macroTimer); macroTimer = null }
  stopClockTick()
  deactivateSandbox()
})

/** 初始总览：场景尺寸 + 全部 NPC 状态（SSE 断线兜底） */
async function loadOverview() {
  try {
    const ov = await fetchSandboxOverview(projectId)
    if (ov?.mainScene?.sceneSize) sceneSize.value = ov.mainScene.sceneSize
    applyStates(ov?.states || [])
  } catch (e) { ElMessage.error(e.message || '加载沙盘失败') }
}

/** 世界地图：区域/道路/建筑/住所（home.js 已换算中心化坐标） */
async function loadWorldMap() {
  try {
    const m = await fetchHomeWorldMap(projectId)
    if (m?.sceneSize) sceneSize.value = m.sceneSize
    regions.value = m.regions || []
    roads.value = m.roads || []
    placements.value = m.placements || []
    homes.value = m.homes || []
  } catch (_) { /* 无地图静默（空画布） */ }
}

/** 在途移动列表 */
async function loadMovements() {
  try {
    movements.value = await fetchHomeMovements(projectId)
  } catch (_) { movements.value = [] }
}

/** 并行加载天气与地点表 */
async function loadWeatherAndLocations() {
  try { applyFrameWeather(await fetchWorldFrame(projectId)) } catch (_) { /* 静默 */ }
  try { applyWeatherPayload(await fetchEnvironmentContext(projectId)) } catch (_) { /* 静默 */ }
  try { worldLocations.value = await fetchWorldLocations(projectId) || [] } catch (_) { worldLocations.value = [] }
}

/** 订阅沙盘实时流（13 事件契约，含 M2 npc:walking 与 M3 npc:encounter） */
function connectStream() {
  try {
    sub = createSubscription({
      url: sandboxStreamUrl(projectId),
      events: [
        'sandbox:init', 'npc:moved', 'npc:walking', 'npc:arrived', 'npc:state', 'npc:thought',
        'npc:talk', 'npc:join', 'npc:leave', 'scene:event', 'clock', 'weather', 'npc:encounter'
      ],
      heartbeatTimeoutMs: 45000,
      onConnected: () => { liveConnected.value = true },
      onEvent: handleSseEvent,
      onError: () => { liveConnected.value = false },
      onGiveUp: () => { liveConnected.value = false; ElMessage.warning('沙盘实时连接重试达上限，请刷新页面重连') }
    })
  } catch (_) { /* 连接失败静默 */ }
}

/** SSE 统一分发 */
function handleSseEvent(name, data) {
  if (!data) return
  switch (name) {
    case 'sandbox:init': {
      if (data.mainScene?.sceneSize) sceneSize.value = data.mainScene.sceneSize
      if (data.clock) applyClockPayload(data.clock)
      applyStates(data.states || [])
      break
    }
    case 'npc:moved': {
      const npc = ensureNpc(data.npcRef)
      if (npc && Array.isArray(data.to) && data.to.length >= 3) {
        npc.moveState = 'walking'
        npc.posX = data.to[0]; npc.posZ = data.to[2]
      }
      pushFeed('moved', '移动', `${npcName(npc)} 开始移动`)
      break
    }
    case 'npc:walking': {
      // M2：真实移动开始/途中（增量更新坐标 + 移动表）
      const npc = ensureNpc(data.npcRef)
      if (npc) {
        npc.moveState = 'walking'
        if (Array.isArray(data.from) && data.from.length >= 3) {
          npc.posX = data.from[0]; npc.posZ = data.from[2]
        }
      }
      upsertMovement(data)
      pushFeed('moved', '移动', `${data.npcName || data.npcRef || ''} 正走向 ${data.targetLocationName || ''}`)
      break
    }
    case 'npc:arrived': {
      const npc = ensureNpc(data.npcRef)
      if (npc) {
        npc.moveState = 'idle'
        if (Array.isArray(data.pos) && data.pos.length >= 3) {
          npc.posX = data.pos[0]; npc.posZ = data.pos[2]; npc.heading = data.heading
        }
      }
      removeMovement(data.npcRef)
      pushFeed('arrived', '到达', `${npcName(npc)} 到达${data.locationName ? `（${data.locationName}）` : ''}`)
      break
    }
    case 'npc:state': {
      const npc = ensureNpc(data.npcRef)
      if (npc) {
        if (data.moveState != null) npc.moveState = data.moveState
        if (data.actionText != null) npc.actionText = data.actionText
        if (data.statusText != null) npc.statusText = data.statusText
        if (data.gameSecond != null) npc.gameSecond = data.gameSecond
        mergeInternalState(npc, data)
      }
      break
    }
    case 'npc:thought': {
      const npc = ensureNpc(data.npcRef)
      if (npc) { npc.thoughtText = data.text; npc.thoughtSource = data.source }
      pushFeed('thought', '想法', `${npcName(npc)}：${data.text}`)
      break
    }
    case 'npc:talk': {
      pushFeed('talk', '对话', `${nameOf(data.npcRef)}：${data.text || ''}`)
      break
    }
    case 'npc:encounter': {
      // M3：途中相遇（移动者 vs 驻留者，greet 文本）
      const text = data.greet || data.message || ''
      if (text) pushFeed('talk', '相遇', text)
      break
    }
    case 'npc:join': {
      ensureNpc(data.npcRef, { npcType: data.npcType, npcName: data.name })
      pushFeed('join', '登场', `${data.name || data.npcRef} 进入场景`)
      break
    }
    case 'npc:leave': {
      delete statesMap[data.npcRef]
      if (selectedRef.value === data.npcRef) selectedRef.value = null
      pushFeed('leave', '退场', `${data.name || data.npcRef} 离开场景`)
      break
    }
    case 'scene:event': {
      const title = data.title || ''
      const content = data.content || ''
      if (data.kind === 'world_frame' && String(data.sourceKey || '').startsWith('frame:')) refreshWeather()
      pushFeed('event', '事件', `${title}${content ? ' — ' + content : ''}`)
      break
    }
    case 'clock': {
      const wasPaused = clock.paused
      applyClockPayload(data)
      if (wasPaused !== data.paused) pushFeed('clock', '时钟', data.paused ? '世界暂停' : '世界继续运行')
      break
    }
    case 'weather': {
      applyWeatherPayload(data)
      pushFeed('clock', '天气', data.message || `天气变化：${data.condition || ''}`)
      break
    }
    default: break
  }
}

// ==================== 状态管理 ====================

function ensureNpc(npcRef, extra = {}) {
  if (!npcRef) return null
  if (!statesMap[npcRef]) {
    statesMap[npcRef] = reactive({
      npcRef, npcType: 'special', npcName: null,
      posX: null, posZ: null, heading: null,
      moveState: 'idle', actionText: '', statusText: '', thoughtText: '', thoughtSource: null, gameSecond: null,
      hunger: null, thirst: null, energy: null, bladder: null, health: null, mood: null,
      dominantEmotion: null, emotionIntensity: null,
      emotionPleasure: null, emotionArousal: null, emotionDominance: null,
      urgencyLevel: 0, urgencyText: '', ...extra
    })
  }
  if (extra.npcName) statesMap[npcRef].npcName = extra.npcName
  return statesMap[npcRef]
}

const INTERNAL_STATE_KEYS = [
  'hunger', 'thirst', 'energy', 'bladder', 'health', 'mood',
  'dominantEmotion', 'emotionIntensity',
  'emotionPleasure', 'emotionArousal', 'emotionDominance',
  'urgencyLevel', 'urgencyText'
]

function mergeInternalState(npc, data) {
  if (!npc || !data) return
  for (const k of INTERNAL_STATE_KEYS) if (data[k] != null) npc[k] = data[k]
}

function applyStates(states) {
  for (const s of states || []) {
    if (!s || !s.npcRef) continue
    ensureNpc(s.npcRef, { npcType: s.npcType, npcName: s.npcName })
    Object.assign(statesMap[s.npcRef], {
      posX: s.posX, posZ: s.posZ, heading: s.heading,
      moveState: s.moveState, actionText: s.actionText, statusText: s.statusText,
      thoughtText: s.thoughtText, thoughtSource: s.thoughtSource, gameSecond: s.gameSecond
    })
    mergeInternalState(statesMap[s.npcRef], s)
  }
}

/** SSE walking 增量 → 移动表 upsert（无 from 时保留旧条目） */
function upsertMovement(data) {
  const idx = movements.value.findIndex((m) => m.npcRef === data.npcRef)
  if (idx >= 0) {
    const old = movements.value[idx]
    movements.value.splice(idx, 1, {
      ...old, ...data,
      fromX: Array.isArray(data.from) && data.from.length >= 3 ? data.from[0] : old.fromX,
      fromZ: Array.isArray(data.from) && data.from.length >= 3 ? data.from[2] : old.fromZ,
      toX: Array.isArray(data.to) && data.to.length >= 3 ? data.to[0] : old.toX,
      toZ: Array.isArray(data.to) && data.to.length >= 3 ? data.to[2] : old.toZ
    })
  } else {
    movements.value.push({
      npcRef: data.npcRef, npcName: data.npcName,
      fromX: Array.isArray(data.from) && data.from.length >= 3 ? data.from[0] : null,
      fromZ: Array.isArray(data.from) && data.from.length >= 3 ? data.from[2] : null,
      toX: Array.isArray(data.to) && data.to.length >= 3 ? data.to[0] : null,
      toZ: Array.isArray(data.to) && data.to.length >= 3 ? data.to[2] : null,
      progress: data.progress != null ? data.progress : 0,
      targetLocationName: data.targetLocationName,
      reason: data.reason
    })
  }
}

function removeMovement(npcRef) {
  const idx = movements.value.findIndex((m) => m.npcRef === npcRef)
  if (idx >= 0) movements.value.splice(idx, 1)
}

// ==================== 拾取交互 ====================

function selectNpc(npcRef) {
  selectedRef.value = selectedRef.value === npcRef ? null : npcRef
}

function selectNpcByName(name) {
  const hit = Object.values(statesMap).find((n) => (n.npcName || '') === name)
  if (hit) { selectNpc(hit.npcRef); locationDetail.value = null }
}

function onPickNpc(npcRef) { selectNpc(npcRef); locationDetail.value = null }
function onPickEmpty() { selectedRef.value = null; locationDetail.value = null; activeLocationId.value = null }

/** 点击区域 → 地点详情卡（world-map/location/{id}） */
async function onPickRegion(r) {
  activeLocationId.value = r.locationId
  selectedRef.value = null
  try {
    const detail = await fetchHomeLocationDetail(projectId, r.locationId)
    // 在场角色：detail.characters 为名称数组（服务端按 actor_profile.location_id）
    locationDetail.value = {
      ...detail,
      name: detail.name || r.name,
      nearby: detail.characters || []
    }
  } catch (e) {
    // 详情接口失败降级：仅区域信息 + 本地就近匹配
    locationDetail.value = {
      name: r.name,
      type: '',
      intro: r.regionDesc || '',
      nearby: nearbyNpcNames(r)
    }
  }
}

/** 点击建筑 → 地点详情卡（按摆放名匹配地点表 + 就近 NPC） */
function onPickPlacement(p) {
  const size = validSceneSize(sceneSize.value)
  const px = p.posX, pz = p.posZ
  const loc = matchWorldLocation(p.name)
  const nearby = []
  for (const n of Object.values(statesMap)) {
    if (n.posX == null || n.posZ == null) continue
    if (Math.hypot(n.posX - px, n.posZ - pz) <= 10) nearby.push(n.npcName || n.npcRef)
  }
  locationDetail.value = {
    name: loc ? loc.name : (p.name || '未知位置'),
    type: loc ? loc.type : (p.category || ''),
    intro: loc ? loc.intro : '',
    nearby
  }
  activeLocationId.value = null
}

/** 点击住所 → 详情卡（名称 + 住户名单） */
function onPickHome(h) {
  activeLocationId.value = null
  locationDetail.value = {
    name: h.name || '住所',
    type: '住所',
    intro: `${h.locationName || ''}${h.region ? ' · ' + h.region : ''}${h.note ? ' · ' + h.note : ''}`,
    nearby: (h.members || []).map((m) => m.characterName || m.characterRef)
  }
}

/** 区域内就近 NPC 名称（详情接口失败降级） */
function nearbyNpcNames(r) {
  const names = []
  for (const n of Object.values(statesMap)) {
    if (n.posX == null || n.posZ == null) continue
    if (Math.hypot(n.posX - r.centerX, n.posZ - r.centerZ) <= (Number(r.radius) || 0) + 1) {
      names.push(n.npcName || n.npcRef)
    }
  }
  return names
}

function matchWorldLocation(name) {
  const list = worldLocations.value || []
  if (!list.length || !name) return null
  const hit = list.find((l) => l.name === name)
  if (hit) return hit
  return list.find((l) => (l.name && name.includes(l.name)) || (l.name && l.name.includes(name))) || null
}

// ==================== 干预 ====================

async function doTalk({ npcRef, content }) {
  if (!npcRef || !content) return
  talking.value = true
  try {
    const res = await sandboxInterveneTalk(projectId, { npcRef, content })
    pushFeed('talk', '插话', `你对${nameOf(npcRef)}说：${content}`)
    ElMessage.success(res?.message || '插话已发送')
  } catch (e) { ElMessage.error(e.message || '插话失败') }
  finally { talking.value = false }
}

async function doAction(npcRef) {
  if (!npcRef) return
  acting.value = true
  try {
    const res = await sandboxInterveneAction(projectId, { npcRef, reason: '新首页手动触发' })
    pushFeed('action', '行动', `${nameOf(npcRef)} 触发行动（${res.status || 'planned'}）`)
    ElMessage.success(`已触发 ${nameOf(npcRef)} 的行动（${res.status || 'planned'}）`)
  } catch (e) { ElMessage.error(e.message || '触发行动失败') }
  finally { acting.value = false }
}

async function doEvent(content) {
  if (!content) return
  injecting.value = true
  try {
    const ev = await sandboxInterveneEvent(projectId, { content })
    pushFeed('event', '注入', `事件已注入：${content}`)
    ElMessage.success(`事件 #${ev?.id ?? ''} 已注入世界`)
  } catch (e) { ElMessage.error(e.message || '事件注入失败') }
  finally { injecting.value = false }
}

async function togglePause() {
  clockBusy.value = true
  try {
    const res = await sandboxInterveneClock(projectId, { paused: !clock.paused })
    applyClockPayload(res?.clock || {})
  } catch (e) { ElMessage.error(e.message || '时钟操作失败') }
  finally { clockBusy.value = false }
}

async function advanceOne() {
  advancing.value = true
  try {
    const res = await sandboxInterveneClock(projectId, { advance: true })
    pushFeed('clock', '推进', `推进一轮（${res?.gameHour ?? ''} 游戏小时）`)
    refreshClockAnchor()
    refreshWeather()
    loadMovements()
  } catch (e) { ElMessage.error(e.message || '推进失败') }
  finally { advancing.value = false }
}

async function onRateChange(rate) {
  clockBusy.value = true
  try {
    const res = await sandboxInterveneClock(projectId, { rate: Number(rate) })
    applyClockPayload(res?.clock || {})
    ElMessage.success(`速率已设为 ×${Number(rate)}`)
  } catch (e) { ElMessage.error(e.message || '速率设置失败') }
  finally { clockBusy.value = false }
}

async function onWeatherChange(condition) {
  if (!condition) return
  weatherBusy.value = true
  try {
    const vo = await sandboxSwitchWeather(projectId, condition)
    applyWeatherPayload(vo)
    pushFeed('clock', '天气', vo?.message || `天气已切换为「${condition}」`)
    ElMessage.success(vo?.message || `天气已切换为「${condition}」`)
  } catch (e) {
    refreshWeather()
    ElMessage.error(e.message || '天气切换失败')
  } finally { weatherBusy.value = false }
}

// ==================== 实时决策 ====================

async function activateSandbox() {
  try {
    const st = await setRealtimeDecision(projectId, { action: 'open', reason: 'sandbox' })
    applyRtState(st)
    pushFeed('clock', '实时决策', '新首页打开：世界已激活，继续推进')
  } catch (_) { /* 静默 */ }
}

async function deactivateSandbox() {
  try {
    const st = await setRealtimeDecision(projectId, { action: 'close', reason: 'sandbox' })
    applyRtState(st)
  } catch (_) { /* 静默 */ }
}

async function toggleRtManual(enabled) {
  rtBusy.value = true
  try {
    const st = await setRealtimeDecision(projectId, { action: 'set', enabled })
    applyRtState(st)
    pushFeed('clock', '实时决策', enabled ? '手动常开：世界持续推进' : '手动关闭：仅页面打开时世界推进')
  } catch (e) { ElMessage.error(e.message || '实时决策开关操作失败') }
  finally { rtBusy.value = false }
}

function applyRtState(st) {
  if (!st) return
  rtDecision.active = !!st.active
  rtDecision.manualEnabled = !!st.manualEnabled
  rtDecision.sandboxActive = !!st.sandboxActive
  rtDecision.evolutionActive = !!st.evolutionActive
}

// ==================== 时钟 ====================

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
  liveGameSecond.value = calcLiveGameSecond()
}

function stopClockTick() { if (clockTick) { clearInterval(clockTick); clockTick = null } }

async function refreshClockAnchor() {
  try { applyClockPayload(await fetchWorldClock(projectId)) } catch (_) { /* 静默 */ }
}

// ==================== 天气 ====================

function applyFrameWeather(frame) {
  if (!frame || !frame.weatherJson) return
  try {
    const parsed = JSON.parse(frame.weatherJson)
    const keys = Object.keys(parsed || {})
    if (!keys.length) return
    const region = keys.includes('主区域') ? '主区域' : keys[0]
    const entry = parsed[region] || {}
    weather.condition = entry.condition || null
    weather.temperature = entry.temperature != null ? entry.temperature : null
    weather.wind = entry.wind || null
    weather.region = region
  } catch (_) { /* 忽略 */ }
}

function applyWeatherPayload(data) {
  if (!data) return
  if (data.condition != null) weather.condition = data.condition
  if (data.temperature != null) weather.temperature = data.temperature
  if (data.wind != null) weather.wind = data.wind
  if (data.region != null) weather.region = data.region
  if (data.season != null) weather.season = data.season
  if (data.seasonName != null) weather.seasonName = data.seasonName
  if (data.month != null) weather.month = data.month
  if (data.phase != null) weather.phase = data.phase
  if (data.phaseName != null) weather.phaseName = data.phaseName
  if (data.sunrise != null) weather.sunrise = data.sunrise
  if (data.sunset != null) weather.sunset = data.sunset
  if (data.feelsLikeC != null) weather.feelsLikeC = data.feelsLikeC
}

async function refreshWeather() {
  try { applyFrameWeather(await fetchWorldFrame(projectId)) } catch (_) { /* 静默 */ }
  try { applyWeatherPayload(await fetchEnvironmentContext(projectId)) } catch (_) { /* 静默 */ }
}

// ==================== 宏观 + 曲线 ====================

async function loadMacro() {
  try { macro.value = await fetchMacroState(projectId) } catch (_) { /* 未初始化静默 */ }
  try { macroEvents.value = await fetchMacroEvents(projectId, { limit: 6 }) || [] } catch (_) { macroEvents.value = [] }
  try { mesoGroups.value = (await fetchMacroGroups(projectId) || []).slice(0, 6) } catch (_) { mesoGroups.value = [] }
  try { emergenceEvents.value = await fetchMacroEmergence(projectId, { limit: 5 }) || [] } catch (_) { emergenceEvents.value = [] }
}

async function toggleCurve() {
  curveOpen.value = !curveOpen.value
  if (curveOpen.value && !curveData.value.length) {
    try {
      const snap = await fetchMacroSnapshots(projectId, { limit: 30 })
      curveData.value = Array.isArray(snap) ? snap : []
    } catch (_) { curveData.value = [] }
  }
}

// ==================== 事件流 ====================

const kindBadge = {
  moved: '移动', arrived: '到达', state: '状态', thought: '想法', talk: '对话',
  join: '登场', leave: '退场', event: '事件', clock: '时钟', action: '行动', injected: '注入'
}

function pushFeed(kind, badge, text) {
  feed.value.push({ kind, badge: badge || kindBadge[kind] || kind, text, time: nowTime() })
  if (feed.value.length > 200) feed.value.splice(0, feed.value.length - 200)
}

// ==================== 工具 ====================

function npcName(npc) { return npc?.npcName || npc?.npcRef || '' }
function nameOf(npcRef) { return statesMap[npcRef]?.npcName || npcRef || '' }
function nowTime() {
  const d = new Date()
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`
}
function validSceneSize(v) {
  const n = Number(v)
  return Number.isFinite(n) && n >= 100 ? n : 500
}
</script>

<style scoped>
.hm-home {
  display: flex; flex-direction: column;
  width: 100%; height: 100%; min-height: 0;
  gap: 10px; padding: 10px;
  background: #0b1220;
  box-sizing: border-box;
}
.hm-body {
  flex: 1; min-height: 0;
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr) 340px;
  gap: 10px;
}
.hm-col { min-width: 0; min-height: 0; position: relative; }
.hm-loading { position: absolute; inset: 0; z-index: 9; display: flex; align-items: center; justify-content: center; }

/* 中栏地图 + 图层筛选 + 地点卡 */
.hm-col-center { display: flex; align-items: center; justify-content: center; }
.hm-layers {
  position: absolute; right: 10px; top: 10px; z-index: 3;
  display: flex; align-items: center; gap: 10px;
  padding: 5px 10px; border-radius: 8px;
  background: rgba(13, 20, 36, 0.8); backdrop-filter: blur(4px);
  border: 1px solid rgba(120, 160, 255, 0.25);
  font-size: 11px; color: #9db8e8;
}
.hm-layers :deep(.el-checkbox__label) { font-size: 11px; color: #9db8e8; }
.hm-loc-card {
  position: absolute; left: 12px; top: 12px; z-index: 4;
  width: 280px; max-height: 62%; overflow-y: auto;
  display: flex; flex-direction: column; gap: 8px;
  background: rgba(13, 20, 36, 0.94); backdrop-filter: blur(8px);
  border: 1px solid rgba(120, 160, 255, 0.35); border-radius: 12px;
  padding: 12px 14px; color: #e8f0ff; box-shadow: 0 6px 24px rgba(0, 0, 0, 0.5);
}
.hm-loc-head { display: flex; align-items: center; gap: 8px; }
.hm-loc-name { font-size: 14px; font-weight: 700; color: #fff; }
.hm-loc-close { margin-left: 0; color: #6b7fa8; }
.hm-loc-intro { font-size: 11.5px; color: #cfe0ff; line-height: 1.7; }
.hm-loc-stats { display: flex; gap: 10px; font-size: 11px; color: #9db8e8; }
.hm-loc-stat { font-variant-numeric: tabular-nums; }
.hm-loc-nearby { border-top: 1px solid rgba(120, 160, 255, 0.18); padding-top: 8px; display: flex; flex-direction: column; gap: 4px; }
.hm-loc-sub { font-size: 11px; font-weight: 700; color: #9db8e8; }
.hm-loc-npc {
  display: flex; align-items: center; gap: 7px; font-size: 12px; color: #cfe0ff;
  padding: 3px 6px; border-radius: 6px; cursor: pointer;
}
.hm-loc-npc:hover { background: rgba(77, 159, 255, 0.12); }
.hm-loc-dot { width: 7px; height: 7px; border-radius: 50%; background: #8fb1ff; flex-shrink: 0; }
.hm-loc-card::-webkit-scrollbar { width: 5px; }
.hm-loc-card::-webkit-scrollbar-thumb { background: rgba(120, 160, 255, 0.3); border-radius: 3px; }

/* 响应式：窄屏堆叠 */
@media (max-width: 1280px) {
  .hm-body { grid-template-columns: 260px minmax(0, 1fr) 300px; }
}
</style>
