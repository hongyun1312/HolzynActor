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
      :left-collapsed="leftCollapsed"
      :right-collapsed="rightCollapsed"
      @rate-change="onRateChange"
      @toggle-pause="togglePause"
      @advance="advanceOne"
      @rt-toggle="toggleRtManual"
      @weather-change="onWeatherChange"
      @open-intervene="interveneOpen = true"
      @open-roleplay="roleplayOpen = true"
      @toggle-left="toggleLeftPanel"
      @toggle-right="toggleRightPanel"
      @world-running-change="onWorldRunningChange"
      @enable-run="enableWorldRun"
    />

    <!-- 三层主体 -->
    <div class="hm-body" :class="{ 'no-left': leftCollapsed, 'no-right': rightCollapsed }">
      <!-- 左栏：角色活动流 -->
      <aside v-show="!leftCollapsed" class="hm-col hm-col-left">
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
          @ask="askAssistantAbout"
        />
      </aside>

      <!-- 中栏：世界地图（主角）
           V1.1：换用六阶段管线产出的米制地图（PixiJS）。旧 WorldMap2D 是 500×0.2m 的
           "父组件算坐标喂进来"式组件，已被本组件取代；旧数据（regions/roads/placements/homes）
           在 M7 尺度迁移完成前一并保留，仅用于 locationDetail 兜底与 sceneSize。 -->
      <main class="hm-col hm-col-center">
        <WorldMapPixi
          :project-id="projectId"
          :night="nightAmount"
          :npcs="npcList"
          :scene-size="sceneSize"
          :selected-ref="selectedRef"
          @pick-feature="onPickMapFeature"
          @pick-empty="onPickEmpty"
          @ready="onMapReady"
        />
        <!-- 时钟暂停：单行细横幅（纯提示，不自带 CTA）
             V2.0：世界时间状态的**唯一控制入口**已收敛到工具条（时钟徽标 + ▶ 开始推进 / ⏸ 暂停），
             这里若再放一个「开始推进」按钮就是同一动作的第二个入口（审计 UI-07 的核心问题）。
             因此本横幅只做"首次进入的一次性提示"，点一次「知道了」不再出现。 -->
        <div
          v-if="pausedBanner" class="hm-banner"
          :title="`世界时钟已暂停 · ${pausedBanner.time} · 角色不会自主推进`"
        >
          <span class="hm-banner-icon">⏸</span>
          <b class="hm-banner-title">世界已暂停</b>
          <span class="hm-banner-hint">角色不会自主推进 · 用上方「▶ 开始推进」继续</span>
          <button type="button" class="hm-banner-close" title="知道了，不再提示" @click="pausedHintDismissed = true">✕</button>
        </div>
        <!-- 阻塞态已下线（V1.1 地图换芯）。
             旧 WorldMap2D 是"父组件拉 /world-map → 算坐标 → 喂进来"，所以父组件必须替它
             表达"未初始化 / 加载失败"两种空态。新 WorldMapPixi **自己拉数据**，空态（还没生成地图
             + 「生成地图」CTA）、生成中（S0–S5 阶段进度）、错误（底部错误条）都由它内部表达，
             父组件再盖一层大卡只会挡住地图 —— 因此这里整块移除。 -->
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
      <aside v-show="!rightCollapsed" class="hm-col hm-col-right">
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
 * 新首页（M4）——项目默认落地页，取代旧 3D 沙盘（WorldSandboxView + Three.js 已于 P0-4 删除）。
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
import { useRoute, useRouter } from 'vue-router'
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
import WorldMapPixi from '../components/WorldMapPixi.vue'
import CharacterActivityPanel from '../components/CharacterActivityPanel.vue'
import WorldEvolutionPanel from '../components/WorldEvolutionPanel.vue'
import InterventionDrawer from '../components/InterventionDrawer.vue'
import RoleplayDrawer from '../components/RoleplayDrawer.vue'
import { askAssistant } from '@/features/assistant/assistantStore'

const route = useRoute()
const router = useRouter()
const projectId = Number(route.params.id)

// ==================== 状态 ====================
const loaded = ref(false)
const liveConnected = ref(false)

/**
 * 首屏地图数据加载状态（P0-2）：
 * - loading：首次加载中（v-loading 已覆盖，不弹卡）
 * - ok：地图数据就绪（有区域/建筑/住所任一项）
 * - empty：地图加载成功但世界未初始化（无任何布局数据）
 * - error：地图接口失败（显示错误 + 重试）
 */
const mapLoadState = ref('loading')
const mapLoadError = ref('')
/** 时钟暂停提示是否被用户手动关闭（会话内） */
const pausedHintDismissed = ref(false)

/** 世界时钟 */
const clock = reactive({ gameDay: 1, gameHour: 0, gameSecond: 0, paused: 0, rate: 4, worldRunning: 1, gameTimeText: '', refRealTime: null, calendarName: null })
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
const weather = reactive({ condition: null, temperature: null, wind: null, region: null, season: null, seasonName: null, month: null, phase: null, phaseName: null, sunrise: null, sunset: null, feelsLikeC: null, framePending: false, frameHint: '' })

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

/**
 * 夜色强度 0~1（新地图的昼夜层）。
 *
 * <p>为什么在这里算而不在后端：新地图的底色是烘死的正午卫星图，夜色只是一层乘算色片，
 * 属于**表现**而不是世界状态。世界时钟（gameSecond）才是权威，前端按"一日内时刻"插值就够了：
 * 05:00 前仍是深夜，05:00–06:00 渐亮，06:00–18:00 全白天，18:00–20:00 渐暗，之后全黑。</p>
 */
const nightAmount = computed(() => {
  const daySecond = ((Number(liveGameSecond.value) || 0) % 86400 + 86400) % 86400
  const hour = daySecond / 3600
  if (hour < 5) return 1
  if (hour < 6) return 1 - (hour - 5)
  if (hour < 18) return 0
  if (hour < 20) return (hour - 18) / 2
  return 1
})

/** 时钟文案（本地插值 → 后端文本 → 紧凑回退） */
const clockText = computed(() => {
  const gs = liveGameSecond.value
  if (clock.refRealTime != null && clock.rate) return formatWorldTimeMinute(gs, clock.calendarName)
  if (clock.gameTimeText) return clock.gameTimeText
  if (clock.gameDay != null) return formatCompactDayTime(gs || clock.gameSecond || 0)
  return '—'
})

/**
 * 首屏阻塞态提示（P0-2 保留：世界未初始化 / 地图加载失败）。
 * <p>第 65 次拆分：**时钟暂停不再走这里**——它原来是一张盖住地图正中的大卡（240×300），
 * 世界一停就把地图挡掉一半；现改为地图顶部一条紧凑横幅（见 pausedBanner），
 * 只有「没有地图内容可看」的阻塞态才继续居中提示。</p>
 */
const homeStatus = computed(() => {
  // ① 地图接口失败：错误 + 重试
  if (mapLoadState.value === 'error') {
    return {
      kind: 'error',
      icon: '⚠️',
      title: '世界地图加载失败',
      desc: mapLoadError.value || '暂时无法获取世界地图，请检查网络后重试。',
      action: '重新加载',
      run: reloadMap
    }
  }
  // ② 世界未初始化：地图为空 → 引导去初始化
  if (mapLoadState.value === 'empty') {
    return {
      kind: 'uninit',
      icon: '🗺️',
      title: '世界尚未初始化',
      desc: '还没有生成世界地图与角色场景。初始化后，角色才会在这片大地上生活、移动。',
      action: '去初始化',
      run: goInit
    }
  }
  return null
})

/** 时钟暂停横幅（第 65 次：由居中大卡改为地图顶部紧凑横幅） */
const pausedBanner = computed(() => {
  if (mapLoadState.value !== 'ok' || !clock.paused || pausedHintDismissed.value) return null
  return { time: clockText.value }
})

// ==================== 面板收放（第 65 次：数据栏太多 → 可一键收起，把画面还给地图） ====================
const PANEL_KEY = 'holzyn-actor.home.panels.v1'
/** 左栏（角色活动流）是否收起 */
const leftCollapsed = ref(loadPanel('left'))
/** 右栏（世界演化）是否收起 */
const rightCollapsed = ref(loadPanel('right'))

function loadPanel(side) {
  try {
    const raw = localStorage.getItem(PANEL_KEY)
    if (raw) return !!JSON.parse(raw)[side]
  } catch (_) { /* 忽略 */ }
  return false
}
function savePanels() {
  try {
    localStorage.setItem(PANEL_KEY, JSON.stringify({ left: leftCollapsed.value, right: rightCollapsed.value }))
  } catch (_) { /* 忽略 */ }
}
function toggleLeftPanel() { leftCollapsed.value = !leftCollapsed.value; savePanels() }
function toggleRightPanel() { rightCollapsed.value = !rightCollapsed.value; savePanels() }

// ==================== 初始化 + SSE ====================

let sub = null

onMounted(async () => {
  await Promise.all([loadOverview(), loadWorldMap(), loadMovements(), loadWeatherAndLocations()])
  connectStream()
  startClockTick()
  refreshClockAnchor()
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
  mapLoadState.value = 'loading'
  mapLoadError.value = ''
  try {
    const m = await fetchHomeWorldMap(projectId)
    if (m?.sceneSize) sceneSize.value = m.sceneSize
    regions.value = m.regions || []
    roads.value = m.roads || []
    placements.value = m.placements || []
    homes.value = m.homes || []
    // 世界未初始化 = 地图加载成功但没有任何布局数据（区域/建筑/住所全空）
    mapLoadState.value = (regions.value.length || placements.value.length || homes.value.length) ? 'ok' : 'empty'
  } catch (e) {
    mapLoadState.value = 'error'
    mapLoadError.value = e?.message || '加载世界地图失败'
  }
}

/** P0-2：地图加载失败后的「重新加载」（地图 + 在途移动一起重拉） */
async function reloadMap() {
  await loadWorldMap()
  await loadMovements()
}

/** P0-2：去初始化 → 进入世界初始化页（独立全屏，6 步工作流；默认「跳过已生成」幂等模式） */
function goInit() {
  router.push(`/project/${projectId}/init`)
}

/** 在途移动列表 */
async function loadMovements() {
  try {
    movements.value = await fetchHomeMovements(projectId)
  } catch (_) { movements.value = [] }
}

/** 并行加载天气与地点表 */
async function loadWeatherAndLocations() {
  try { applyFrameWeather(await fetchWorldFrame(projectId)) } catch (_) { /* 仅网络/服务异常时兜底：未生成已由 generated 字段表达，不再是异常 */ }
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

/**
 * 新地图点选要素 → 地点详情卡。
 *
 * <p>新图的要素（建筑/农田/林地/码头…）是**米坐标 + 语义标签**，不再有旧图的 region/placement
 * 三套模型。这里把要素就地摊成详情卡能显示的最小集：名字、类别、一句构成说明（语义 + 层数 + 尺寸）。
 * 若要素带 locationCode 且能对上世界地点表，则补上它的简介——那条链路（地点 → 在场角色）
 * 要等 M7 把 actor 的位置锚到要素上才通，现在只取静态字段。</p>
 *
 * @param {object} f /map/bundle 里的单个 feature
 */
function onPickMapFeature(f) {
  if (!f) return onPickEmpty()
  selectedRef.value = null
  activeLocationId.value = null
  const loc = f.locationCode ? matchWorldLocation(f.locationCode) : null
  // semantic 是摆放器写下的**结构化标注**（一般是 {"compound":true,"part":0} 这类内部字段），
  // 直接 join 进文案会把 JSON 摆在用户脸上，所以只接受"看起来是人话"的字符串。
  const semanticText = typeof f.semantic === 'string' && f.semantic && !f.semantic.trim().startsWith('{')
    ? f.semantic
    : ''
  const parts = [semanticText, f.category, f.floors ? `${f.floors} 层` : '',
    f.w && f.d ? `${Math.round(f.w)}×${Math.round(f.d)} 米` : '']
  locationDetail.value = {
    name: f.name || (loc && loc.name) || f.code || '未知之地',
    type: f.category || (loc && loc.type) || '',
    intro: [loc?.intro, parts.filter(Boolean).join(' · ')].filter(Boolean).join('　'),
    nearby: [],
    homes: [],
    placements: []
  }
}

/**
 * 地图首次渲染就绪 → 结束"地图加载中"的阻塞态。
 *
 * <p>新地图自己拉数据，父组件的 mapLoadState 是旧管线（/world-map）留下的三态机。
 * 这里把"就绪"回写进去，避免旧的空态/错误卡盖在新地图上。</p>
 */
function onMapReady() {
  mapLoadState.value = 'ok'
  mapLoadError.value = ''
}

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

/** 沙盘「问问助手」：把选中角色上下文带入助手提问（唤起全局助手侧边栏，问题带 npcRef 提升实体解析鲁棒性）。 */
function askAssistantAbout(npcRef) {
  const npc = npcList.value.find((n) => n.npcRef === npcRef)
  const name = npc?.npcName || npcRef || '这个角色'
  askAssistant(`讲讲${name}（${npcRef}）现在的情况`)
}

async function togglePause() {
  clockBusy.value = true
  try {
    const res = await sandboxInterveneClock(projectId, { paused: !clock.paused })
    applyClockPayload(res?.clock || {})
  } catch (e) { ElMessage.error(e.message || '时钟操作失败') }
  finally { clockBusy.value = false }
}

/**
 * 世界运行主开关（V2.2 新增）：直接控制常驻运行 worldRunning。
 * 关闭 = 引擎完全不推进本项目；开启 = 常驻自转（同时恢复暂停，避免开启后仍卡在暂停态）。
 */
async function onWorldRunningChange(v) {
  clockBusy.value = true
  try {
    const res = await sandboxInterveneClock(projectId, { worldRunning: v ? 1 : 0, paused: v ? false : undefined })
    applyClockPayload(res?.clock || {})
    pushFeed('clock', '世界运行', v ? '世界运行已开启（常驻自转）' : '世界运行已关闭（引擎不再推进）')
    ElMessage.success(v ? '世界运行已开启' : '世界运行已关闭')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
    refreshClockAnchor()
  } finally { clockBusy.value = false }
}

/** 主按钮（世界停止时）：开启世界运行并恢复推进（与开关同语义的一次性动作） */
async function enableWorldRun() {
  clockBusy.value = true
  try {
    const res = await sandboxInterveneClock(projectId, { worldRunning: 1, paused: false })
    applyClockPayload(res?.clock || {})
    pushFeed('clock', '世界运行', '世界运行已开启：世界开始推进')
    ElMessage.success('世界运行已开启：世界开始推进')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
    refreshClockAnchor()
  } finally { clockBusy.value = false }
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
    pushFeed('clock', '实时决策', '世界已开始运行')
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

/**
 * 应用世界帧里的天气。
 *
 * <p>UI V2.0 §8.4 契约（2026-10-15 改判）：`GET /world-frame` <b>恒 200</b>——首个游戏日 00:00
 * 之前还没有世界帧，这是世界的正常状态而非错误。未生成时 `generated === false` 且带 `hint`，
 * 此处<b>显式读字段</b>并把原因透出（顶栏「⋯ 更多 → 天气」会显示它），
 * 不再像改造前那样靠 `catch` 吞掉 404——那样"未生成"和"接口挂了"在前端无从区分，
 * 且每次首访都在 DevTools 留一条红色 4xx（实测唯一 4xx 来源）。</p>
 */
function applyFrameWeather(frame) {
  if (!frame) return
  if (frame.generated === false) {
    weather.framePending = true
    weather.frameHint = frame.hint || ''
    return
  }
  weather.framePending = false
  weather.frameHint = ''
  if (!frame.weatherJson) return
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
  try { applyFrameWeather(await fetchWorldFrame(projectId)) } catch (_) { /* 仅网络/服务异常时兜底：未生成已由 generated 字段表达，不再是异常 */ }
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
  background: var(--hm-page-bg);
  box-sizing: border-box;
}
.hm-body {
  flex: 1; min-height: 0;
  display: grid;
  grid-template-columns: 300px minmax(0, 1fr) 340px;
  gap: 10px;
}
/* 面板收起：把宽度还给地图（第 65 次「数据栏太多」的兜底手段）
   ⚠ 三列必须显式声明 grid-column：面板用 v-show 收起后 display:none 的网格项不再占位，
   若不指定列号，剩下的元素会自动落到第 1 条轨道（= 收起的 0px 轨道）→ 地图宽度变 0。 */
.hm-body.no-left { grid-template-columns: 0 minmax(0, 1fr) 340px; }
.hm-body.no-right { grid-template-columns: 300px minmax(0, 1fr) 0; }
.hm-body.no-left.no-right { grid-template-columns: 0 minmax(0, 1fr) 0; }
.hm-col { min-width: 0; min-height: 0; position: relative; }
.hm-col-left { grid-column: 1; }
.hm-col-center { grid-column: 2; }
.hm-col-right { grid-column: 3; }
.hm-loading { position: absolute; inset: 0; z-index: 9; display: flex; align-items: center; justify-content: center; }
/* 右下角常驻「AI 助手」悬浮球（fixed 48px + 22px 边距）：两栏都收起时地图会一直顶到视口
   右下角，底部控件必须给悬浮球让位，否则图例最后一项被盖住 */
.hm-body.no-left.no-right :deep(.hm-ctls) { right: 84px; }

/* 中栏地图 + 状态提示 + 地点卡 */
.hm-col-center { display: flex; align-items: center; justify-content: center; }
/* 时钟暂停横幅（单行细条，停在地图顶部，不遮挡地图主体） */
.hm-banner {
  position: absolute; left: 50%; top: 10px; transform: translateX(-50%);
  z-index: 5;
  display: flex; align-items: center; gap: 8px;
  max-width: calc(100% - 32px);
  padding: 4px 8px 4px 12px;
  border-radius: 999px;
  background: var(--hm-panel-strong); backdrop-filter: blur(10px);
  border: 1px solid color-mix(in srgb, var(--hm-gold) 55%, transparent);
  box-shadow: var(--shadow-lv2);
  color: var(--hm-text);
  font-size: 12px; white-space: nowrap;
}
.hm-banner-icon { font-size: 13px; line-height: 1; }
/* V2.0：800 → 700（分片只保留 400/700；原写法会被浏览器就近解析到 900 字重，多下一个完整字重） */
.hm-banner-title { font-size: 12.5px; font-weight: 700; }
.hm-banner-time { font-style: normal; font-size: 12px; color: var(--hm-gold); font-variant-numeric: tabular-nums; }
.hm-banner-hint { font-size: 11px; color: var(--hm-text-faint); }
.hm-banner-close {
  border: none; background: transparent; cursor: pointer;
  color: var(--hm-text-faint); font-size: 12px; padding: 0 2px; line-height: 1;
}
.hm-banner-close:hover { color: var(--hm-text); }

/* P0-2 阻塞态卡（未初始化 / 加载失败）：地图本身无内容，居中提示 */
.hm-status {
  position: absolute; left: 50%; top: 50%;
  transform: translate(-50%, -50%);
  z-index: 5;
  width: min(360px, 86%);
  display: flex; flex-direction: column; align-items: center; gap: 8px;
  padding: 22px 24px;
  text-align: center;
  background: var(--hm-panel-strong); backdrop-filter: blur(10px);
  border: 1px solid var(--hm-border); border-radius: 16px;
  color: var(--hm-text); box-shadow: var(--shadow-lv3);
}
.hm-status.is-error { border-color: color-mix(in srgb, var(--hm-red) 60%, transparent); }
.hm-status-icon { font-size: 30px; line-height: 1; }
/* V2.0：800 → 700（分片只保留 400/700；原写法会被浏览器就近解析到 900 字重，多下一个完整字重） */
.hm-status-title { font-size: 16px; font-weight: 700; color: var(--hm-text); }
.hm-status-desc { font-size: 12.5px; color: var(--hm-text-dim); line-height: 1.7; }
.hm-status-btn { margin-top: 6px; min-width: 140px; }
.hm-loc-card {
  position: absolute; left: 12px; top: 12px; z-index: 4;
  width: 280px; max-height: 62%; overflow-y: auto;
  display: flex; flex-direction: column; gap: 8px;
  background: var(--hm-panel-strong); backdrop-filter: blur(8px);
  border: 1px solid var(--hm-border); border-radius: 12px;
  padding: 12px 14px; color: var(--hm-text); box-shadow: var(--shadow-lv2);
}
.hm-loc-head { display: flex; align-items: center; gap: 8px; }
.hm-loc-name { font-size: 14px; font-weight: 700; color: var(--hm-text); }
.hm-loc-close { margin-left: 0; color: var(--hm-text-faint); }
.hm-loc-intro { font-size: 11.5px; color: var(--hm-text-soft); line-height: 1.7; }
.hm-loc-stats { display: flex; gap: 10px; font-size: 11px; color: var(--hm-text-dim); }
.hm-loc-stat { font-variant-numeric: tabular-nums; }
.hm-loc-nearby { border-top: 1px solid var(--hm-border-soft); padding-top: 8px; display: flex; flex-direction: column; gap: 4px; }
.hm-loc-sub { font-size: 11px; font-weight: 700; color: var(--hm-text-dim); }
.hm-loc-npc {
  display: flex; align-items: center; gap: 7px; font-size: 12px; color: var(--hm-text-soft);
  padding: 3px 6px; border-radius: 6px; cursor: pointer;
}
.hm-loc-npc:hover { background: var(--accent-soft); }
.hm-loc-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--hm-blue); flex-shrink: 0; }
.hm-loc-card::-webkit-scrollbar { width: 5px; }
.hm-loc-card::-webkit-scrollbar-thumb { background: var(--hm-border); border-radius: 3px; }

/* 响应式（V2.0：断点收敛为档位值 1440 / 1024 两档，原有 1500/1280/1199/1023/860 五个野值已归并）。
   原则：**窄屏降级的是"栏"，不是中央内容**——地图是主角，不许被两侧数据栏压扁。
   - ≥1441：三栏（左角色活动流 | 中地图 | 右世界演化）
   - 1025–1440：**地图独占整列 + 右侧 320px 侧轨**（角色活动流与世界演化上下两卡），
     地图框恢复竖长（实测 1036×480 的横向空框问题即由此解决）
   - ≤1024：地图整行 + 两卡在下并排（<1024 只做"不破相"兜底，桌面端 minWidth=1024 不会到达）
   ⚠ 此处不改 `.hm-home` 的高度链：`.hm-body` 保持 flex:1 + min-height:0 才有确定高度，
   否则 auto 高度下的 1fr 行会按内容撑开（实测被面板撑到 1242px）。 */
@media (max-width: 1440px) {
  .hm-body {
    grid-template-columns: minmax(0, 1fr) 320px;
    grid-template-rows: minmax(0, 1fr) minmax(0, 1fr);
  }
  .hm-col-center { grid-column: 1; grid-row: 1 / 3; }
  .hm-col-left { grid-column: 2; grid-row: 1; }
  .hm-col-right { grid-column: 2; grid-row: 2; }
  .hm-body.no-left { grid-template-columns: minmax(0, 1fr) 0; }
  .hm-body.no-left .hm-col-right { grid-column: 2; grid-row: 1 / 3; }
  .hm-body.no-right { grid-template-columns: minmax(0, 1fr) 0; }
  .hm-body.no-right .hm-col-left { grid-column: 2; grid-row: 1 / 3; }
  .hm-body.no-left.no-right { grid-template-columns: minmax(0, 1fr) 0; }
}
@media (max-width: 1024px) {
  .hm-home { height: auto; min-height: 100%; }
  .hm-body {
    flex: none;
    grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
    grid-template-rows: minmax(400px, 56vh) auto;
  }
  .hm-col-center { grid-column: 1 / -1; grid-row: 1; }
  .hm-col-left { grid-column: 1; grid-row: 2; height: 420px; }
  .hm-col-right { grid-column: 2; grid-row: 2; height: 420px; }
  .hm-body.no-left,
  .hm-body.no-right,
  .hm-body.no-left.no-right { grid-template-columns: minmax(0, 1fr); }
  .hm-body.no-left .hm-col-right,
  .hm-body.no-right .hm-col-left { grid-column: 1 / -1; grid-row: 2; }
}
</style>
