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
    新首页地图主体（V1.1 六阶段管线的 S5 渲染端）。
    与旧 WorldMap2D.vue 的根本区别：旧组件是"纯展示 + 父组件算好坐标喂进来"，
    本组件**自己拉数据**——因为新地图的数据量级与坐标系（米）都和旧图不同：
      · 地形是后端烘好的 1024² 卫星底图（terrain.png，带版本号）；
      · 矢量要素是 /map/bundle（建筑轮廓/屋顶色/层数/朝向 + 道路折线 + 等级色板）；
      · 生成是异步任务 + SSE 分阶段进度（S0..S5），所以这里还要管"没有图"和"正在生成"两种状态。
  -->
  <div ref="boxEl" class="wm" :class="{ 'is-compact': compact }">
    <div ref="hostEl" class="wm-canvas" />

    <!-- 顶栏信息：地图名 + 尺度 + 规模（都是"这张图是什么"的最小信息集） -->
    <div v-if="overview" class="wm-top">
      <span class="wm-title">{{ overview.name || '未命名之地' }}</span>
      <span class="wm-tag">{{ sizeText }}</span>
      <span v-if="stats.buildings" class="wm-tag">{{ stats.buildings }} 栋</span>
      <span v-if="stats.roads" class="wm-tag">{{ stats.roads }} 条路</span>
      <span v-if="stats.residents" class="wm-tag">{{ stats.residents }} 居民</span>
      <span v-if="clockText" class="wm-tag">{{ clockText }}</span>
      <span v-if="ambientText" class="wm-tag">{{ ambientText }}</span>
      <span class="wm-tag" :class="aiClass">{{ aiText }}</span>
    </div>

    <!-- 生成中：阶段进度（覆盖层，避免用户以为卡住） -->
    <div v-if="busy" class="wm-mask">
      <div class="wm-mask-panel">
        <div class="wm-mask-title">{{ t('mapping', '正在生成地图') }}</div>
        <div class="wm-stages">
          <span
            v-for="(s, i) in STAGES" :key="s.code"
            class="wm-stage" :class="{ done: i < stageIndex, cur: i === stageIndex }"
          >{{ s.code }}</span>
        </div>
        <div class="wm-bar"><i :style="{ width: percent + '%' }" /></div>
        <div class="wm-mask-sub">{{ stageName || '准备中…' }} · {{ percent }}%</div>
        <div class="wm-logs">
          <div v-for="(l, i) in logs" :key="i" class="wm-log">{{ l }}</div>
        </div>
      </div>
    </div>

    <!-- 没有地图：唯一的入口就是"生成"（决策 #8：旧图作废，用户手动重生） -->
    <div v-if="!busy && !loading && !overview" class="wm-empty">
      <div class="wm-empty-icon">🗺</div>
      <div class="wm-empty-title">这张地图还没生成过</div>
      <div class="wm-empty-desc">
        新的地图管线会先读世界观，再生成地形、建筑类型、路网，最后把房子按地块摆上去。<br />
        整个过程约 1~5 分钟（后台跑，可离开本页）。
      </div>
      <el-button type="primary" size="large" :loading="starting" @click="generate()">生成地图</el-button>
    </div>

    <!-- 加载中 -->
    <div v-if="loading && !busy" class="wm-loading">正在载入地图…</div>

    <!-- 右上工具：缩放 / 视角 / 图层 / 重生成 -->
    <div v-if="!compact && overview && !busy" class="wm-tools">
      <button type="button" class="wm-tool" title="放大" @click="zoom(1.35)">＋</button>
      <button type="button" class="wm-tool" title="缩小" @click="zoom(1 / 1.35)">－</button>
      <button type="button" class="wm-tool" title="对准聚落（双击地图同效）" @click="fitSettlement">⛶</button>
      <button type="button" class="wm-tool" title="看全图" @click="fitMap">▣</button>
      <button
        type="button" class="wm-tool" :class="{ on: layers.base }"
        title="写实底图（后端烘的 4096² 分层底图）" @click="toggle('base')"
      >底</button>
      <button
        type="button" class="wm-tool" :class="{ on: layers.roads }"
        title="道路" @click="toggle('roads')"
      >路</button>
      <button
        type="button" class="wm-tool" :class="{ on: layers.features }"
        title="建筑与地类" @click="toggle('features')"
      >房</button>
      <button
        type="button" class="wm-tool" :class="{ on: layers.characters }"
        title="角色" @click="toggle('characters')"
      >人</button>
      <button
        type="button" class="wm-tool" title="换一个种子重生成" :disabled="starting"
        @click="regenerate"
      >⟳</button>
    </div>

    <!-- 图例：路网等级 + 角色活动（颜色都来自后端 palette / 前端同一份常量） -->
    <div v-if="!compact && overview && !busy" class="wm-legend">
      <div v-for="g in legendRoads" :key="g.grade" class="wm-legend-row">
        <i class="wm-legend-bar" :style="{ background: g.color, height: g.px + 'px' }" />
        <span>{{ g.label }}</span>
      </div>
      <div v-if="legendActivities.length && layers.characters" class="wm-legend-sep" />
      <div v-for="a in (layers.characters ? legendActivities : [])" :key="a.key" class="wm-legend-row">
        <i class="wm-legend-dot" :style="{ background: a.color }" />
        <span>{{ a.label }}</span>
      </div>
    </div>

    <!-- 调试面板：按等级选择性显示道路（仅调试用，不参与正式交互） -->
    <div v-if="!compact && overview && !busy" class="wm-road-debug">
      <button type="button" class="wm-road-debug-head" @click="roadDebugOpen = !roadDebugOpen">
        <span class="wm-road-debug-dot" /> 调试 · 道路等级
        <span class="wm-road-debug-caret">{{ roadDebugOpen ? '▾' : '▸' }}</span>
      </button>
      <div v-if="roadDebugOpen" class="wm-road-debug-body">
        <label v-for="g in roadGradeOptions" :key="g.grade" class="wm-road-debug-row">
          <input
            type="checkbox" :checked="roadGrades.includes(g.grade)"
            @change="toggleRoadGrade(g.grade)"
          />
          <i class="wm-legend-bar" :style="{ background: g.color, height: g.px + 'px' }" />
          <span>{{ g.label }}</span>
          <em>{{ roadGradeCount(g.grade) }}</em>
        </label>
        <div class="wm-road-debug-actions">
          <button type="button" @click="setAllRoadGrades(true)">全选</button>
          <button type="button" @click="setAllRoadGrades(false)">全不选</button>
        </div>
        <div class="wm-road-debug-note">仅调试用：不影响生成数据，只改画布上的道路图层</div>
      </div>
    </div>

    <!-- 选中要素卡（点地图上的房子/田地/码头） -->
    <div v-if="picked" class="wm-pick">
      <div class="wm-pick-head">
        <span class="wm-pick-name">{{ picked.name || picked.code }}</span>
        <el-tag size="small" effect="plain">{{ kindLabel(picked) }}</el-tag>
        <button type="button" class="wm-pick-close" @click="clearPick">✕</button>
      </div>
      <div class="wm-pick-body">
        <span v-if="picked.category">{{ picked.category }}</span>
        <span v-if="picked.floors">{{ picked.floors }} 层</span>
        <span v-if="picked.w && picked.d">{{ Math.round(picked.w) }}×{{ Math.round(picked.d) }} 米</span>
        <span v-if="picked.locationCode">属 {{ picked.locationCode }}</span>
      </div>
    </div>

    <!-- 错误提示（AI 失败/后端异常时不留白屏） -->
    <div v-if="error" class="wm-error">
      {{ error }}
      <button type="button" class="wm-pick-close" @click="error = ''">✕</button>
    </div>
  </div>
</template>

<script setup>
/**
 * 世界地图（PixiJS）：地形底图 + 矢量道路 + 程序化建筑 + 角色点 + 昼夜。
 *
 * <p><b>为什么这一层必须自己拉数据</b>：旧首页地图是"父组件把 500 格坐标换算好喂进来"。
 * 新地图的坐标是米（1 格 = 1 米、1024 米见方）、要素有 553 个体、还要跟"生成进度"联动——
 * 这些都不是父组件该操心的。地图组件内部管：数据获取 → 舞台构建 → 相机 → LOD → 拾取。</p>
 *
 * <p><b>角色点为什么是"投影"而不是"真坐标"</b>：M7（尺度迁移）之前，角色的位置还写在
 * 旧图的场景格坐标里。这里用一次仿射把它按"旧聚落包围盒 → 新聚落包围盒"贴过去，
 * 目的是**别让角色在地图上消失**（双主角布局里人是主角）。M7 会把角色的住所/位置
 * 改挂到新图的要素锚点上，届时删掉 {@code projectNpcs} 这一段即可。</p>
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch, nextTick } from 'vue'
import { ElButton, ElTag } from 'element-plus'
import { MapStage } from '../map/mapStage'
import { ACTIVITY_LEGEND } from '../map/mapRenderCharacters'
import {
  fetchMapBundle,
  fetchMapOverview,
  generateWorldMap,
  mapStreamUrl
} from '@/shared/api/worldMapPipeline'

const props = defineProps({
  /** 项目 ID（地图按项目隔离） */
  projectId: { type: [Number, String], required: true },
  /** 窄栏模式：隐藏图例与工具条（工具栏仍可用键盘/双击） */
  compact: { type: Boolean, default: false },
  /** 夜色强度 0~1（父组件用世界时钟算好传入） */
  night: { type: Number, default: 0 },
  /** 角色表（旧坐标域；M7 后改为要素锚点） */
  npcs: { type: Array, default: () => [] },
  /** 旧图场景边长（角色坐标换算用） */
  sceneSize: { type: Number, default: 500 },
  /** 当前选中角色 ref（地图上高亮） */
  selectedRef: { type: String, default: '' }
})

const emit = defineEmits(['pick-feature', 'pick-empty', 'view', 'ready', 'day-tick'])

/** 阶段码（与后端 MapGenOrchestrator.STAGE_NAMES 对齐） */
const STAGES = [
  { code: 'S0', key: 'profile' },
  { code: 'S1', key: 'terrain' },
  { code: 'S2', key: 'assets' },
  { code: 'S3', key: 'roads' },
  { code: 'S4', key: 'placement' },
  { code: 'S5', key: 'render' }
]

const boxEl = ref(null)
const hostEl = ref(null)
const loading = ref(true)
const starting = ref(false)
const busy = ref(false)
const stageIndex = ref(0)
const stageName = ref('')
const percent = ref(0)
const logs = ref([])
const error = ref('')
const picked = ref(null)
const clockText = ref('')
const ambientText = ref('')
const layers = reactive({ base: true, roads: true, features: true, characters: true })
// ---- 调试：按等级显示道路（仅前端过滤，不请求后端、不改数据）----
const roadDebugOpen = ref(false)
const roadGrades = ref([3, 2, 1, 0])
let stage = null
let source = null
let disposed = false

const overview = computed(() => state.overview)
const bundle = computed(() => state.bundle)
const stats = computed(() => state.overview?.stats || {})
const state = reactive({ overview: null, bundle: null })

const sizeText = computed(() => {
  const w = Number(state.overview?.widthMeters) || 0
  return w ? `${Math.round(w)}×${Math.round(Number(state.overview?.heightMeters) || w)} 米` : ''
})
const aiText = computed(() => {
  const p = state.overview?.profile
  if (!p) return ''
  return p.aiGenerated ? 'AI 档案' : '内置档案'
})
const aiClass = computed(() => (state.overview?.profile?.aiGenerated ? 'is-ai' : 'is-fallback'))
const legendRoads = computed(() => {
  const palette = state.bundle?.palette || {}
  return [3, 2, 1, 0].map((grade) => ({
    grade,
    label: palette.roadLabels?.[String(grade)] || `等级 ${grade}`,
    color: palette.roadColors?.[String(grade)] || '#a68f6a',
    px: 2 + grade * 1.6
  }))
})
const legendActivities = computed(() => (
  (state.bundle?.characters || []).length ? ACTIVITY_LEGEND : []
))

/** i18n 占位（本页文案直接中文；保留函数便于后续接 i18n 抽词） */
const t = (_k, fallback) => fallback

/** 环境一行文案：季节·天气·潮汐·气温。 */
function ambientLabel(a) {
  if (!a) return ''
  const bits = []
  if (a.seasonName) bits.push(a.seasonName)
  if (a.weatherName) bits.push(a.weatherName)
  if (a.tideName) bits.push(`潮·${a.tideName}`)
  if (Number.isFinite(Number(a.temperatureC))) bits.push(`${Math.round(Number(a.temperatureC))}℃`)
  return bits.join(' · ')
}

/** 世界时刻文案（时钟回调每 120ms 一次，只在分钟变化时改 ref）。 */
function clockLabel(minute) {
  const m = Math.floor(((Number(minute) || 0) % 1440 + 1440) % 1440)
  const hh = String(Math.floor(m / 60)).padStart(2, '0')
  const mm = String(m % 60).padStart(2, '0')
  return `${hh}:${mm}`
}

/** 要素类型 → 中文（用户看到的是"渔家石屋"而不是 "building"） */
const kindLabel = (f) => {
  const map = {
    building: '建筑', field: '农田', forest: '林地', plaza: '广场',
    pier: '码头', bridge: '桥', wall: '院墙'
  }
  return map[f?.kind] || f?.kind || '要素'
}

// ==================== 数据 ====================

/** 拉概览 + 渲染包（有图才拉包）。 */
async function load() {
  loading.value = true
  error.value = ''
  try {
    const ov = await fetchMapOverview(props.projectId)
    state.overview = ov || null
    if (ov && ov.status === 'running') {
      busy.value = true
      openStream()
    }
    if (ov && ov.status === 'ready') await loadBundle()
  } catch (e) {
    error.value = e?.message || '地图数据加载失败'
  } finally {
    loading.value = false
  }
}

/** 拉渲染包并装进舞台。 */
async function loadBundle() {
  const data = await fetchMapBundle(props.projectId)
  state.bundle = data || null
  if (!stage || !data) return
  stage.setBundle(data)
  await stage.setTerrain(data.overview?.terrainUrl || state.overview?.terrainUrl)
  // 调试筛选项要跨「换种子重生成」保留：新 bundle 进来后重新套一次等级过滤
  stage.setRoadGrades(roadGrades.value)
  stage.fitToSettlement()
  // M7 之后角色位置由日程（bundle.characters）决定；没有日程才退回旧的仿射投影。
  if (!(data.characters || []).length) projectNpcs()
  ambientText.value = ambientLabel(data.ambient)
  emit('ready', data.overview || state.overview)
}

/** 触发生成（后台任务；种子相同 ⇒ 结果相同）。 */
async function generate(seed) {
  starting.value = true
  error.value = ''
  try {
    const body = Number.isFinite(Number(seed)) ? { seed: Number(seed) } : {}
    percent.value = 0
    stageIndex.value = 0
    logs.value = []
    busy.value = true
    openStream()
    await generateWorldMap(props.projectId, body)
  } catch (e) {
    busy.value = false
    error.value = e?.message || '生成请求失败'
  } finally {
    starting.value = false
  }
}

/** 换种子重生成（用户「重掷」入口；种子是决定论的，重复同一句号就重复同一张图）。 */
function regenerate() {
  const seed = 20260000 + Math.floor(Math.random() * 9999)
  generate(seed)
}

// ==================== SSE ====================

/** 订阅生成进度（事件名与后端 SseLifecycle 一致）。 */
function openStream() {
  closeStream()
  const es = new EventSource(mapStreamUrl(props.projectId))
  source = es
  es.addEventListener('stage', (e) => {
    const d = parse(e)
    if (!d) return
    stageIndex.value = Math.max(0, (Number(d.index) || 1) - 1)
    stageName.value = d.name || d.stage || ''
    if (Number.isFinite(Number(d.percent))) percent.value = Number(d.percent)
  })
  es.addEventListener('progress', (e) => {
    const d = parse(e)
    if (!d) return
    if (Number.isFinite(Number(d.percent))) percent.value = Number(d.percent)
    if (d.message) pushLog(d.message)
  })
  es.addEventListener('log', (e) => {
    const d = parse(e)
    if (d?.message) pushLog(d.message)
  })
  es.addEventListener('result', async (e) => {
    const d = parse(e)
    percent.value = 100
    busy.value = false
    closeStream()
    if (d) state.overview = d
    try {
      await loadBundle()
    } catch (err) {
      error.value = err?.message || '地图已生成，但载入渲染包失败'
    }
  })
  es.addEventListener('error', (e) => {
    // 同名冲突：传输层错误没有 data（服务端关闭时我们自己已经关过了）
    const d = parse(e)
    if (!d) return
    busy.value = false
    closeStream()
    error.value = d.message || '地图生成失败'
  })
}

/** 关流（服务端完成后会主动 close，但浏览器会重连，必须自己关）。 */
function closeStream() {
  if (source) {
    source.close()
    source = null
  }
}

/** 解析 SSE 载荷（JSON 失败就当纯文本）。 */
function parse(e) {
  const raw = e?.data
  if (raw == null || raw === '') return null
  try {
    return JSON.parse(raw)
  } catch {
    return { message: String(raw) }
  }
}

/** 日志只留最后 4 条（空间有限）。 */
function pushLog(msg) {
  const line = String(msg || '').trim()
  if (!line) return
  logs.value = [...logs.value.slice(-3), line]
}

// ==================== 相机与交互 ====================

const zoom = (factor) => {
  if (!stage) return
  stage.zoomAt(factor, {
    x: (hostEl.value?.clientWidth || 1) / 2,
    y: (hostEl.value?.clientHeight || 1) / 2
  })
}
const fitSettlement = () => stage?.fitToSettlement()
const fitMap = () => stage?.fitToMap()

/** 图层开关（画布内的图层，不影响左侧角色面板）。 */
function toggle(key) {
  layers[key] = !layers[key]
  stage?.setLayers({ [key]: layers[key] })
}

/** 调试面板：四个道路等级的选项（颜色/名称与图例同源）。 */
const roadGradeOptions = computed(() => [3, 2, 1, 0].map((grade) => ({
  grade,
  label: state.bundle?.palette?.roadLabels?.[String(grade)] || `等级 ${grade}`,
  color: state.bundle?.palette?.roadColors?.[String(grade)] || '#a68f6a',
  px: 2 + grade * 1.6
})))

/** 某等级在 bundle 里有几条（调试时用来看"这一档到底生成了多少"）。 */
function roadGradeCount(grade) {
  const roads = state.bundle?.roads || []
  return roads.filter((r) => (Number(r.grade) || 0) === grade).length
}

/** 调试：勾选/取消某个道路等级。 */
function toggleRoadGrade(grade) {
  const next = roadGrades.value.includes(grade)
    ? roadGrades.value.filter((v) => v !== grade)
    : [...roadGrades.value, grade].sort((a, b) => b - a)
  roadGrades.value = next
  stage?.setRoadGrades(next)
}

/** 调试：全选 / 全不选。 */
function setAllRoadGrades(on) {
  roadGrades.value = on ? [3, 2, 1, 0] : []
  stage?.setRoadGrades(roadGrades.value)
}

/** 点选要素 → 卡片 + 冒泡给父组件（父组件可据此联动左栏/右栏）。 */
function onPick(feature, point) {
  picked.value = feature || null
  if (feature) emit('pick-feature', feature)
  else emit('pick-empty', point)
}

function clearPick() {
  picked.value = null
  stage?.setHighlight(null)
}

// ==================== 角色点（M7 前的临时映射） ====================

/**
 * 把旧坐标域的角色位置仿射到新图的聚落包围盒上。
 *
 * <p>为什么要做这一步：M7 之前，角色位置的权威数据仍是旧图的场景格坐标
 * （500×500，0.2m/格，中心化）。直接画到米制新图上会全部堆在原点附近。
 * 这里用一次**保持相对布局**的仿射（旧包围盒 → 新聚落包围盒）：村落内部谁在东边、
 * 谁靠码头，相对关系不变，只是尺度换成了新岛。这不是最终态——M7 会把住所锚点挂到
 * 新图要素上（`feature_id`），届时删掉本函数。</p>
 */
function projectNpcs() {
  if (!stage) return
  const list = (props.npcs || []).filter(
    (n) => Number.isFinite(Number(n?.posX)) && Number.isFinite(Number(n?.posZ))
  )
  const box = stage.bounds
  if (list.length === 0 || !box) {
    stage.setCharacters([])
    return
  }
  let minX = Infinity
  let minZ = Infinity
  let maxX = -Infinity
  let maxZ = -Infinity
  for (const n of list) {
    const x = Number(n.posX)
    const z = Number(n.posZ)
    if (x < minX) minX = x
    if (z < minZ) minZ = z
    if (x > maxX) maxX = x
    if (z > maxZ) maxZ = z
  }
  const w = Math.max(8, maxX - minX)
  const h = Math.max(8, maxZ - minZ)
  const bw = Math.max(40, box.maxX - box.minX)
  const bh = Math.max(40, box.maxZ - box.minZ)
  const kx = bw / w
  const kz = bh / h
  stage.setCharacters(list.map((n) => ({
    key: n.ref || n.npcRef || n.name,
    x: box.minX + (Number(n.posX) - minX) * kx,
    z: box.minZ + (Number(n.posZ) - minZ) * kz,
    special: !!n.special,
    selected: (n.ref || n.npcRef) === props.selectedRef
  })))
}

// ==================== 生命周期 ====================

onMounted(async () => {
  await nextTick()
  if (!hostEl.value) return
  stage = new MapStage(hostEl.value, {
    onPick,
    onView: (v) => emit('view', v),
    onClock: ({ minute, crossedDay }) => {
      const next = clockLabel(minute)
      if (next !== clockText.value) clockText.value = next
      if (crossedDay) emit('day-tick')
    }
  })
  try {
    await stage.init()
  } catch (e) {
    error.value = 'WebGL 初始化失败（浏览器不支持或显卡驱动异常）：' + (e?.message || '')
    return
  }
  if (disposed) {
    stage.destroy()
    stage = null
    return
  }
  stage.setNight(props.night)
  await load()
})

onBeforeUnmount(() => {
  disposed = true
  closeStream()
  stage?.destroy()
  stage = null
})

watch(() => props.projectId, () => {
  closeStream()
  busy.value = false
  picked.value = null
  state.bundle = null
  state.overview = null
  load()
})

watch(() => props.night, (v) => stage?.setNight(v))

watch(() => props.selectedRef, (v) => {
  if (stage && stage.characters.length) stage.selectCharacter(v)
  else projectNpcs()
})

watch(() => props.npcs, () => projectNpcs(), { deep: false })
</script>

<style scoped>
.wm {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 240px;
  overflow: hidden;
  border-radius: 10px;
  background: radial-gradient(120% 90% at 50% 40%, #123044 0%, #0b1a24 70%);
  border: 1px solid var(--hm-border-soft, rgba(120, 160, 255, 0.18));
}
.wm-canvas {
  position: absolute;
  inset: 0;
}

/* 顶栏信息 */
.wm-top {
  position: absolute;
  left: 10px;
  top: 10px;
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
  pointer-events: none;
  z-index: 2;
}
.wm-title {
  font-weight: 700;
  font-size: 15px;
  color: #f2f7ff;
  text-shadow: 0 1px 3px rgba(0, 0, 0, 0.75);
}
.wm-tag {
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 999px;
  color: #d8e6ff;
  background: rgba(9, 16, 28, 0.62);
  border: 1px solid rgba(140, 175, 255, 0.28);
  backdrop-filter: blur(6px);
}
.wm-tag.is-ai { color: #ffe9a8; border-color: rgba(255, 212, 92, 0.5); }
.wm-tag.is-fallback { color: #b7c8e6; }

/* 工具条 */
.wm-tools {
  position: absolute;
  right: 10px;
  top: 10px;
  display: flex;
  flex-direction: column;
  gap: 5px;
  z-index: 3;
}
.wm-tool {
  width: 28px;
  height: 28px;
  border-radius: 7px;
  border: 1px solid rgba(140, 175, 255, 0.3);
  background: rgba(9, 16, 28, 0.66);
  color: #d8e6ff;
  font-size: 13px;
  line-height: 1;
  cursor: pointer;
  backdrop-filter: blur(8px);
  transition: background 0.15s, color 0.15s, border-color 0.15s;
}
.wm-tool:hover { background: rgba(30, 48, 76, 0.9); color: #fff; }
.wm-tool.on { border-color: rgba(255, 212, 92, 0.65); color: #ffe9a8; }
.wm-tool:disabled { opacity: 0.45; cursor: not-allowed; }

/* 图例 */
.wm-legend {
  position: absolute;
  left: 10px;
  bottom: 10px;
  padding: 6px 9px;
  border-radius: 8px;
  background: rgba(9, 16, 28, 0.6);
  border: 1px solid rgba(140, 175, 255, 0.22);
  backdrop-filter: blur(8px);
  display: flex;
  flex-direction: column;
  gap: 4px;
  z-index: 2;
}
.wm-legend-row {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: #c6d6ef;
}
.wm-legend-bar {
  display: inline-block;
  width: 16px;
  border-radius: 2px;
}
.wm-legend-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin: 0 4px;
}
.wm-legend-sep {
  height: 1px;
  margin: 3px 0;
  background: rgba(140, 175, 255, 0.22);
}

/* ---- 调试面板：按等级显示道路（仅调试用；样式与图例一致，靠左下堆叠） ---- */
.wm-road-debug {
  position: absolute;
  left: 10px;
  bottom: 96px;
  min-width: 168px;
  border-radius: 8px;
  background: rgba(9, 16, 28, 0.72);
  border: 1px dashed rgba(255, 196, 120, 0.38);
  backdrop-filter: blur(8px);
  color: #d8c9a8;
  font-size: 11px;
  z-index: 3;
  overflow: hidden;
}
.wm-road-debug-head {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: 6px 9px;
  border: 0;
  background: transparent;
  color: inherit;
  font-size: 11px;
  cursor: pointer;
}
.wm-road-debug-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #ffc478;
  box-shadow: 0 0 6px rgba(255, 196, 120, 0.8);
}
.wm-road-debug-caret {
  margin-left: auto;
  opacity: 0.7;
}
.wm-road-debug-body {
  padding: 2px 9px 8px;
}
.wm-road-debug-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 2px 0;
  cursor: pointer;
}
.wm-road-debug-row input {
  accent-color: #ffc478;
}
.wm-road-debug-row em {
  margin-left: auto;
  font-style: normal;
  opacity: 0.55;
}
.wm-road-debug-actions {
  display: flex;
  gap: 6px;
  margin: 6px 0 4px;
}
.wm-road-debug-actions button {
  flex: 1;
  padding: 2px 0;
  border-radius: 5px;
  border: 1px solid rgba(255, 196, 120, 0.35);
  background: rgba(255, 196, 120, 0.08);
  color: #ffd9a8;
  font-size: 10px;
  cursor: pointer;
}
.wm-road-debug-note {
  opacity: 0.45;
  line-height: 1.35;
}

/* 选中卡 */
.wm-pick {
  position: absolute;
  right: 10px;
  bottom: 10px;
  min-width: 180px;
  max-width: 260px;
  padding: 8px 10px;
  border-radius: 9px;
  background: rgba(9, 16, 28, 0.86);
  border: 1px solid rgba(140, 175, 255, 0.3);
  backdrop-filter: blur(10px);
  z-index: 3;
}
.wm-pick-head {
  display: flex;
  align-items: center;
  gap: 6px;
}
.wm-pick-name {
  font-size: 13px;
  font-weight: 600;
  color: #f2f7ff;
}
.wm-pick-close {
  margin-left: auto;
  border: none;
  background: transparent;
  color: #8fa8cc;
  cursor: pointer;
  font-size: 12px;
}
.wm-pick-body {
  margin-top: 5px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 11px;
  color: #a8bcda;
}

/* 生成遮罩 */
.wm-mask {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  background: rgba(6, 12, 22, 0.72);
  backdrop-filter: blur(3px);
  z-index: 4;
}
.wm-mask-panel {
  width: min(360px, 84%);
  padding: 16px 18px;
  border-radius: 12px;
  background: rgba(13, 22, 38, 0.94);
  border: 1px solid rgba(140, 175, 255, 0.28);
}
.wm-mask-title {
  font-size: 14px;
  font-weight: 600;
  color: #f2f7ff;
  margin-bottom: 10px;
}
.wm-stages {
  display: flex;
  gap: 6px;
  margin-bottom: 8px;
}
.wm-stage {
  flex: 1;
  text-align: center;
  font-size: 11px;
  padding: 3px 0;
  border-radius: 5px;
  color: #7f93b3;
  background: rgba(255, 255, 255, 0.05);
}
.wm-stage.done { color: #9fe0b4; background: rgba(82, 214, 138, 0.14); }
.wm-stage.cur { color: #ffe9a8; background: rgba(255, 212, 92, 0.18); }
.wm-bar {
  height: 5px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.1);
  overflow: hidden;
}
.wm-bar > i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #6fa8ff, #ffd45c);
  transition: width 0.3s ease;
}
.wm-mask-sub {
  margin-top: 7px;
  font-size: 11px;
  color: #a8bcda;
}
.wm-logs {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.wm-log {
  font-size: 10.5px;
  color: #7f93b3;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 空态 */
.wm-empty {
  position: absolute;
  inset: 0;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 8px;
  text-align: center;
  padding: 20px;
  z-index: 2;
}
.wm-empty-icon { font-size: 34px; opacity: 0.7; }
.wm-empty-title { font-size: 15px; font-weight: 600; color: #eaf2ff; }
.wm-empty-desc {
  font-size: 12px;
  color: #9db4d4;
  line-height: 1.7;
  max-width: 380px;
}
.wm-loading {
  position: absolute;
  inset: 0;
  display: grid;
  place-content: center;
  font-size: 12px;
  color: #9db4d4;
  z-index: 2;
}

/* 错误条 */
.wm-error {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  bottom: 10px;
  max-width: 76%;
  padding: 6px 10px;
  border-radius: 8px;
  font-size: 11.5px;
  color: #ffd7d7;
  background: rgba(90, 20, 20, 0.82);
  border: 1px solid rgba(255, 107, 107, 0.5);
  display: flex;
  align-items: center;
  gap: 8px;
  z-index: 5;
}
</style>
