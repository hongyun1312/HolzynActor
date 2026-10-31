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
    新首页 2D 世界地图画布（M4 建；第 65 次重做投影与可读性）。
    职责：以 SVG 渲染「村落俯视图」——区域圆（地点卡片，点击详情）+ 道路 + 建筑摆放点 +
    住所（同居共宅）+ 角色圆点（金=特殊 / 蓝=人群，含姓名名牌）+ 移动中角色轨迹线。
    纯展示 + 拾取：坐标统一为【中心化坐标】，由父组件换算后传入；不发起任何请求。

    第 65 次修复要点（用户反馈「地图全挤到一起」）：
    ① **内容自适应投影**：不再把整个 500×500 场景硬投影到画布（实测村落本体只占场景 ±40 格
       → 全部内容被压在画布中段一小块，10 个区域名 + 12 个角色名 + 7 个住所叠成一团），
       改为先算内容包围盒再等比铺满画布（保比例、居中、留边）；
    ② **画布比例自适应**：viewBox 高度跟随容器纵横比（原先正方形 viewBox 在 596×816 栏里
       上下各留一条空白带）；
    ③ **屏幕恒定尺寸**：标签/圆点/字形按「屏幕像素」反算用户单位（地图缩小时文字不跟着变小，
       放大时不跟着变成巨型字）；
    ④ **标签避让**：所有标签按优先级（选中 > 悬停 > 特殊角色 > 区域 > 人群）贪心去重，
       冲突的自动隐藏（点仍有 tooltip），从根上消灭「文字叠成一坨」；
    ⑤ **同名同址展开**：坐标重叠的角色圆点按圆周微展开，不再互相盖死；
    ⑥ **缩放/平移**：滚轮缩放（1~8×，以光标为中心）、空白处拖拽平移、双击/按钮复位；
    ⑦ **制图观感**：底图比例网格（对齐世界格、随缩放换挡）+ 区域按地貌着色（聚落/山地/水域/
       林地/疆域，自然与疆域用虚界）+ 道路「路基 + 路面」双层圆角 + 建筑按类别着色的圆角房基 +
       角色圆点描边（压在任何底色上都可辨）；底部控件拆到左右两端，窄栏不再挤成两行胶囊。
  -->
  <div ref="boxEl" class="hm-map" :class="{ 'is-compact': compact }">
    <svg
      ref="svgEl"
      class="hm-svg"
      :class="{ 'is-panning': panning }"
      :viewBox="`0 0 ${vbW} ${vbH}`"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="onPointerUp"
      @pointerleave="onPointerUp"
      @wheel.prevent="onWheel"
      @dblclick="resetView"
    >
      <defs>
        <radialGradient id="hm-bg" cx="50%" cy="42%" r="78%">
          <stop class="hm-stop-0" offset="0%" />
          <stop class="hm-stop-1" offset="100%" />
        </radialGradient>
      </defs>

      <!-- 地图底（不随缩放平移；点击空白=取消选中） -->
      <rect class="hm-bg" :x="0" :y="0" :width="vbW" :height="vbH" fill="url(#hm-bg)" @click="onBgClick" />

      <!-- 内容层（受缩放/平移变换） -->
      <g :transform="viewTransform">
        <!-- 比例网格（按世界格对齐，随缩放自动换挡；纯装饰，给地图一个尺度参照） -->
        <g class="hm-grid" aria-hidden="true">
          <line v-for="g in gridLines.v" :key="'gv' + g.k" :x1="g.p" :y1="-vbH" :x2="g.p" :y2="vbH * 2" :stroke-width="gridW" />
          <line v-for="g in gridLines.h" :key="'gh' + g.k" :x1="-vbW" :y1="g.p" :x2="vbW * 2" :y2="g.p" :stroke-width="gridW" />
        </g>

        <!-- 内容主体（网格之外的一切，供「是否铺满画布」等度量与图层排序） -->
        <g class="hm-content">
        <!-- 区域（地点卡片：按地貌着色，大区域在下、小区域在上；点击看详情） -->
        <g
          v-for="r in regionMarks" :key="r.locationId"
          class="hm-region" :class="[r.kind, { active: activeLocationId === r.locationId }]"
        >
          <circle
            v-if="r.ok"
            :cx="r.x" :cy="r.y" :r="r.rad"
            class="hm-region-shape" :stroke-width="U(1.4)"
            :stroke-dasharray="r.dash"
            :class="{ 'is-active': activeLocationId === r.locationId }"
            @click.stop="emit('pick-region', r.raw)"
          />
          <title>{{ r.title }}</title>
        </g>

        <!-- 道路：路基 + 路面两层（圆角连接），才像一条路而不是一根虚线 -->
        <g v-if="show.roads">
          <g v-for="(rd, i) in roads" :key="'rd' + i">
            <polyline :points="roadPoints(rd)" class="hm-road-casing" :stroke-width="roadW(rd.width) + U(2.6)" />
            <polyline :points="roadPoints(rd)" class="hm-road" :stroke-width="roadW(rd.width)">
              <title>{{ rd.name || '道路' }}</title>
            </polyline>
          </g>
        </g>

        <!-- 建筑摆放点（圆角房基，按类别着色 + 类别 tooltip） -->
        <g v-if="show.buildings">
          <g v-for="p in placementMarks" :key="p.id" class="hm-bld">
            <rect
              :x="p.x - p.half" :y="p.y - p.half" :width="p.half * 2" :height="p.half * 2"
              :rx="U(1.6)" class="hm-building" :class="'cat-' + p.cat"
              :stroke-width="U(1.2)"
              @click.stop="emit('pick-placement', p.raw)"
            />
            <title>{{ p.title }}</title>
          </g>
        </g>

        <!-- 住所（同居共宅，房屋符号；点住所有住户名单提示） -->
        <g v-if="show.homes">
          <g v-for="h in homes" :key="h.id" class="hm-home" @click.stop="emit('pick-home', h)">
            <path v-if="finite(h.posX, h.posZ)" :d="homeGlyph(h)" class="hm-home-glyph" :stroke-width="U(1)" />
            <title>{{ homeTitle(h) }}</title>
          </g>
        </g>

        <!-- 移动轨迹（移动中角色：from→to 虚线 + 方向点；默认压暗，选中/悬停该角色时高亮，
             否则 3 条长线横穿地图会像多出来几条路） -->
        <g
          v-for="m in movements" :key="m.npcRef" class="hm-trail"
          :class="{ 'is-sel': selectedRef === m.npcRef || hoverRef === m.npcRef }"
        >
          <line
            v-if="finite(m.fromX, m.fromZ) && finite(m.toX, m.toZ)"
            :x1="bx(m.fromX)" :y1="by(m.fromZ)" :x2="bx(m.toX)" :y2="by(m.toZ)"
            class="hm-trail-line" :stroke-dasharray="`${U(5)} ${U(4)}`"
            :stroke-width="selectedRef === m.npcRef || hoverRef === m.npcRef ? U(2.2) : U(1.4)"
          />
          <circle v-if="finite(trailX(m), trailZ(m))" :cx="bx(trailX(m))" :cy="by(trailZ(m))" :r="U(3.4)" class="hm-trail-dot" />
        </g>

        <!-- 角色圆点（金=特殊 / 蓝=人群；点击选中；重叠圆点自动微展开） -->
        <g v-for="mk in npcMarkers" :key="mk.npcRef" class="hm-npc"
           :class="{ selected: selectedRef === mk.npcRef }"
           @click.stop="emit('pick-npc', mk.npcRef)"
           @pointerenter="hoverRef = mk.npcRef"
           @pointerleave="hoverRef = null">
          <circle
            :cx="mk.x" :cy="mk.y" :r="mk.r"
            :class="'hm-npc-dot ' + mk.cls"
            :stroke="selectedRef === mk.npcRef || hoverRef === mk.npcRef ? 'var(--hm-text)' : 'var(--hm-npc-ring)'"
            :stroke-width="U(1.6)"
          />
          <title>{{ mk.title }}</title>
        </g>

        <!-- 标签层（统一避让后排布：区域名 + 角色名） -->
        <g class="hm-labels">
          <text
            v-for="lb in visibleLabels" :key="lb.key"
            :x="lb.x" :y="lb.y" :style="{ fontSize: lb.fs + 'px' }"
            :class="lb.cls" :stroke-width="U(3)"
            @click.stop="lb.onClick && lb.onClick()"
          >{{ lb.text }}</text>
        </g>
        </g>
      </g>
    </svg>

    <!-- 底部控件：左=图层，右=缩放+图例（分列两端；窄栏自动换行，不再挤成两行胶囊） -->
    <div v-if="!compact" class="hm-ctls">
      <div class="hm-ctl-pill hm-map-bar">
        <button type="button" class="hm-chip" :class="{ on: show.roads }" @click="emit('toggle-layer', 'roads', !show.roads)">道路</button>
        <button type="button" class="hm-chip" :class="{ on: show.buildings }" @click="emit('toggle-layer', 'buildings', !show.buildings)">建筑</button>
        <button type="button" class="hm-chip" :class="{ on: show.homes }" @click="emit('toggle-layer', 'homes', !show.homes)">住所</button>
        <button type="button" class="hm-chip" :class="{ on: showNames }" title="显示角色姓名（特殊角色常显；此处可全关）" @click="toggleNames">姓名</button>
      </div>
      <div class="hm-ctl-pill hm-bar-zoom">
        <button type="button" class="hm-zoom" title="缩小" @click="zoomBy(1 / 1.45)">−</button>
        <span class="hm-zoom-val">{{ zoomPct }}%</span>
        <button type="button" class="hm-zoom" title="放大" @click="zoomBy(1.45)">＋</button>
        <button type="button" class="hm-zoom is-text" title="复位视图（双击地图同效）" @click="resetView">复位</button>
        <span class="hm-bar-sep" />
        <span class="hm-lg"><i class="lg-dot gold" />特殊</span>
        <span class="hm-lg"><i class="lg-dot blue" />人群</span>
        <span class="hm-lg"><i class="lg-home" />住所</span>
      </div>
    </div>

    <!-- 缩放操作提示（首次进入、仅缩放==1 时显示，提示可交互） -->
    <div v-if="!compact && zoom === 1 && !hintDismissed" class="hm-map-hint">
      滚轮缩放 · 拖拽平移
      <button type="button" class="hm-hint-close" title="知道了" @click="dismissHint">✕</button>
    </div>
  </div>
</template>

<script setup>
/**
 * 2D 世界地图画布（M4 新首页中栏主角）。
 * <p>职责：SVG 渲染村落俯视图——区域圆（地点卡片）/道路/建筑/住所/角色圆点/移动轨迹；
 * 拾取事件：pick-region / pick-placement / pick-home / pick-npc / pick-empty；
 * 图层开关上抛：toggle-layer。</p>
 * <p>坐标域：全部接收【中心化坐标】（父组件用 homeCoord 换算）。</p>
 * <p>投影：{@link computeContentBounds} → {@link fitProjection}（内容自适应、等比、居中）+
 * 用户缩放平移（屏幕像素语义，见 {@link U}）。</p>
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { computeContentBounds, fitProjection } from '../homeCoord'

const props = defineProps({
  /** 区域（中心化坐标） */
  regions: { type: Array, default: () => [] },
  /** 道路（中心化坐标 path） */
  roads: { type: Array, default: () => [] },
  /** 建筑摆放（中心化坐标） */
  placements: { type: Array, default: () => [] },
  /** 住所（中心化坐标） */
  homes: { type: Array, default: () => [] },
  /** 在途移动（MovementVO 中心化坐标） */
  movements: { type: Array, default: () => [] },
  /** 角色圆点（沙盘状态，中心化坐标 posX/posZ） */
  npcs: { type: Array, default: () => [] },
  /** 场景边长 */
  sceneSize: { type: Number, default: 500 },
  /** 选中角色引用 */
  selectedRef: { type: String, default: null },
  /** 激活地点（地图上的高亮） */
  activeLocationId: { type: [Number, String], default: null },
  /** 图层筛选开关 */
  show: { type: Object, default: () => ({ roads: true, buildings: true, homes: true, npcs: true }) },
  /** 是否紧凑模式（隐藏文字与控制条，用于抽屉/弹窗里的小地图） */
  compact: { type: Boolean, default: false }
})
const emit = defineEmits(['pick-region', 'pick-placement', 'pick-home', 'pick-npc', 'pick-empty', 'toggle-layer'])

const NAMES_KEY = 'holzyn-actor.home.map.names.v1'
const HINT_KEY = 'holzyn-actor.home.map.hint.v1'

const boxEl = ref(null)
const svgEl = ref(null)
const hoverRef = ref(null)
const showNames = ref(loadFlag(NAMES_KEY, true))
const hintDismissed = ref(loadFlag(HINT_KEY, false))

function loadFlag(key, dft) {
  try {
    const raw = localStorage.getItem(key)
    if (raw === '1') return true
    if (raw === '0') return false
  } catch (_) { /* 忽略 */ }
  return dft
}
function saveFlag(key, v) {
  try { localStorage.setItem(key, v ? '1' : '0') } catch (_) { /* 忽略 */ }
}

// ==================== 画布尺寸（viewBox 跟随容器纵横比） ====================
const renderW = ref(600)
const renderH = ref(600)
let ro = null

function measure() {
  const r = boxEl.value?.getBoundingClientRect()
  if (r && r.width > 2 && r.height > 2) {
    renderW.value = r.width
    renderH.value = r.height
  }
}

onMounted(() => {
  measure()
  if (typeof ResizeObserver !== 'undefined' && boxEl.value) {
    ro = new ResizeObserver(measure)
    ro.observe(boxEl.value)
  } else {
    window.addEventListener('resize', measure)
  }
})
onBeforeUnmount(() => {
  ro?.disconnect(); ro = null
  window.removeEventListener('resize', measure)
})

/** viewBox 宽（用户单位，固定 100） */
const vbW = 100
/** viewBox 高：跟随容器纵横比（16~400 夹紧，仅为挡住极端值；不夹会让 viewBox 与容器比例
 *  不一致，SVG 默认 preserveAspectRatio=meet 会横向留出与底图渐变不同的空边） */
const vbH = computed(() => {
  const h = vbW * (renderH.value / Math.max(1, renderW.value))
  return Math.max(16, Math.min(400, h))
})
/** 「每屏幕像素」对应的用户单位数（缩放=1 时；用于把所有视觉尺寸锚定到屏幕像素） */
const u = computed(() => vbH.value / Math.max(1, renderH.value))

// ==================== 缩放 / 平移 ====================
const zoom = ref(1)
const panX = ref(0)
const panY = ref(0)
const panning = ref(false)
let dragStart = null
let dragMoved = 0
let suppressBgClick = false

/** 视图中心（基准用户坐标）：内容默认居中于画布，pan 以屏幕像素给出 */
const viewCenterX = computed(() => vbW / 2 + panX.value * u.value)
const viewCenterY = computed(() => vbH.value / 2 + panY.value * u.value)

/** 内容层变换：先把视图中心搬到画布中心，再按 zoom 缩放 */
const viewTransform = computed(() => {
  const k = zoom.value
  const cx = viewCenterX.value
  const cy = viewCenterY.value
  return `translate(${vbW / 2} ${vbH.value / 2}) scale(${k}) translate(${-cx} ${-cy})`
})

/** 期望屏幕像素 → 用户单位（自动抵消 zoom，保证圆点/文字屏幕尺寸恒定） */
function U(px) { return px * u.value / zoom.value }

const zoomPct = computed(() => Math.round(zoom.value * 100))

/** 平移夹紧：内容中心必须始终留在视口内（避免拖到「看不见世界」） */
function clampPan() {
  if (zoom.value <= 1.001) { panX.value = 0; panY.value = 0; return }
  const halfViewW = vbW / (2 * zoom.value)
  const halfViewH = vbH.value / (2 * zoom.value)
  const maxX = halfViewW / u.value
  const maxY = halfViewH / u.value
  panX.value = Math.max(-maxX, Math.min(maxX, panX.value))
  panY.value = Math.max(-maxY, Math.min(maxY, panY.value))
}

function resetView() {
  zoom.value = 1
  panX.value = 0
  panY.value = 0
}

/** 以画布中心为基准缩放（按钮用） */
function zoomBy(factor) {
  zoom.value = Math.max(1, Math.min(8, zoom.value * factor))
  clampPan()
}

/** 屏幕坐标（相对 svg 左上）→ 基准用户坐标 */
function screenToBase(sx, sy) {
  const k = zoom.value
  return {
    x: viewCenterX.value + (sx - renderW.value / 2) * u.value / k,
    y: viewCenterY.value + (sy - renderH.value / 2) * u.value / k
  }
}

/** 滚轮缩放：以光标处世界点为中心（主流地图交互） */
function onWheel(e) {
  const rect = svgEl.value?.getBoundingClientRect()
  if (!rect) return
  const sx = e.clientX - rect.left
  const sy = e.clientY - rect.top
  const before = screenToBase(sx, sy)
  const next = Math.max(1, Math.min(8, zoom.value * Math.exp(-e.deltaY * 0.0015)))
  if (Math.abs(next - zoom.value) < 0.001) return
  zoom.value = next
  if (next <= 1.001) { panX.value = 0; panY.value = 0; return }
  const cx = before.x - (sx - renderW.value / 2) * u.value / next
  const cy = before.y - (sy - renderH.value / 2) * u.value / next
  panX.value = (cx - vbW / 2) / u.value
  panY.value = (cy - vbH.value / 2) / u.value
  clampPan()
}

/** 指针按下：仅在「空白」（背景矩形/svg 自身）上开始拖拽，标记点击不受影响 */
function onPointerDown(e) {
  if (e.button !== 0) return
  const t = e.target
  const onBg = t === svgEl.value || (t?.classList?.contains?.('hm-bg') ?? false)
  if (!onBg) return
  panning.value = true
  dragMoved = 0
  dragStart = { x: e.clientX, y: e.clientY }
  try { svgEl.value.setPointerCapture(e.pointerId) } catch (_) { /* 忽略 */ }
}

function onPointerMove(e) {
  if (!panning.value || !dragStart) return
  const dx = e.clientX - dragStart.x
  const dy = e.clientY - dragStart.y
  dragMoved += Math.abs(dx) + Math.abs(dy)
  dragStart = { x: e.clientX, y: e.clientY }
  panX.value += dx
  panY.value += dy
  clampPan()
}

function onPointerUp(e) {
  if (!panning.value) return
  panning.value = false
  dragStart = null
  // 拖拽超过阈值 → 视为平移而非点击，抑制随后的 pick-empty
  suppressBgClick = dragMoved > 4
  if (suppressBgClick) setTimeout(() => { suppressBgClick = false }, 0)
  try { svgEl.value?.releasePointerCapture?.(e.pointerId) } catch (_) { /* 忽略 */ }
}

function onBgClick() {
  if (suppressBgClick) return
  emit('pick-empty')
}

function toggleNames() {
  showNames.value = !showNames.value
  saveFlag(NAMES_KEY, showNames.value)
}
function dismissHint() {
  hintDismissed.value = true
  saveFlag(HINT_KEY, true)
}

// ==================== 投影 ====================
/** 内容包围盒（区域/道路/建筑/住所/角色/轨迹 全参与） */
const bounds = computed(() => computeContentBounds({
  regions: props.regions,
  roads: props.roads,
  placements: props.placements,
  homes: props.homes,
  npcs: props.npcs,
  movements: props.movements,
  sceneSize: props.sceneSize
}))

/** 内容自适应投影（等比、居中、留边） */
const proj = computed(() => fitProjection(bounds.value, vbW, vbH.value, { sceneSize: props.sceneSize }))

/** 中心化坐标 → 基准用户坐标 */
function bx(x) { return proj.value.x0 + Number(x) * proj.value.scale }
function by(z) { return proj.value.z0 + Number(z) * proj.value.scale }

/** 坐标可用性（避免 null/undefined 画到 (0,0)） */
function finite(...vals) { return vals.every((v) => Number.isFinite(Number(v)) && v !== null && v !== undefined && v !== '') }

/** 区域半径（用户单位；地理尺寸，随缩放变化） */
function regionR(r) {
  const rad = Math.max(Number(r?.radius) || 0, 4)
  return rad * proj.value.scale
}

// ==================== 地貌分类（名称关键词 → 视觉类别） ====================
/**
 * 世界地图数据里没有地形字段（style 只有 "village"），这里按地点名关键词归类，
 * 只为「一眼分辨山 / 水 / 林 / 聚落」的制图观感服务；认不出来的一律 plain（中性蓝）。
 * 顺序即优先级：先判定「山地 → 聚落 → 水域 → 林地 → 疆域」，
 * 例如「临溪镇」判聚落、「灵溪渡口」判水域、「青禾村古井」判聚落。
 */
const KIND_RULES = [
  ['mountain', /山|峰|岭|崖|岩|坡|谷/],
  ['settlement', /村|镇|城|市|街|巷|堂|铺|院|宅|驿|关|集|府|井/],
  ['water', /溪|河|湖|海|江|泉|渡|潭|湾|港|泊/],
  ['forest', /林|竹|树|森|秘境|幽|草|原|田|野/],
  ['realm', /朝|国|邦|域|州|郡|界/]
]
/** 自然/疆域类用虚界（制图惯例：虚线=非精确边界） */
const DASHED_KINDS = ['mountain', 'forest', 'realm']

function regionKind(r) {
  const name = String(r?.name || '')
  for (const [kind, re] of KIND_RULES) if (re.test(name)) return kind
  return 'plain'
}

/** 区域绘制项（大区域先画在下层，避免半径 85 的「青禾村」盖住村内小地点） */
const regionMarks = computed(() => (props.regions || [])
  .filter((r) => finite(r?.centerX, r?.centerZ))
  .slice()
  .sort((a, b) => (Number(b?.radius) || 0) - (Number(a?.radius) || 0))
  .map((r) => {
    const kind = regionKind(r)
    return {
      locationId: r.locationId,
      raw: r,
      kind,
      ok: true,
      x: bx(r.centerX),
      y: by(r.centerZ),
      rad: regionR(r),
      dash: DASHED_KINDS.includes(kind) ? `${U(3.2)} ${U(2.6)}` : null,
      title: `${r.name || '区域'}${r.regionDesc ? ` · ${r.regionDesc}` : ''}`
    }
  }))

/** 建筑类别 → 中文 + 房基半径（屏幕像素，保证缩小时也是一块可见的「房」） */
const CAT_LABEL = { landmark: '地标', store: '店铺', farm: '农田', road: '道路' }
const CAT_HALF = { landmark: 3.4, store: 3.2, farm: 3.0, road: 2.2 }

const placementMarks = computed(() => (props.placements || [])
  .filter((p) => finite(p?.posX, p?.posZ))
  .map((p) => ({
    id: p.id,
    raw: p,
    x: bx(p.posX),
    y: by(p.posZ),
    half: U(CAT_HALF[p.category] ?? 3),
    cat: CAT_LABEL[p.category] ? p.category : 'other',
    title: `${p.name || '建筑'}${p.category ? ` · ${CAT_LABEL[p.category] || p.category}` : ''}`
  })))

/** 比例网格换挡档位（世界格） */
const GRID_STEPS = [5, 10, 20, 25, 50, 100, 200]

/**
 * 比例网格：线永远落在「世界坐标整数格」上，所以缩放/平移时不会飘；
 * 屏幕格距保持在 22px 以上（缩小时自动换到更大的档，放大时换到更小的档）。
 */
const gridLines = computed(() => {
  const p = proj.value
  if (!p || !Number.isFinite(p.scale) || p.scale <= 0) return { v: [], h: [] }
  const pxPerGrid = (p.scale / u.value) * zoom.value
  let step = GRID_STEPS[GRID_STEPS.length - 1]
  for (const s of GRID_STEPS) {
    if (s * pxPerGrid >= 22) { step = s; break }
  }
  const gb = step * p.scale
  if (!Number.isFinite(gb) || gb <= 0) return { v: [], h: [] }
  const halfW = vbW / (2 * zoom.value)
  const halfH = vbH.value / (2 * zoom.value)
  const build = (from, to, origin) => {
    const out = []
    const i0 = Math.floor((from - origin) / gb)
    const i1 = Math.ceil((to - origin) / gb)
    if (!Number.isFinite(i0) || !Number.isFinite(i1) || i1 - i0 > 200) return out
    for (let i = i0; i <= i1; i++) out.push({ k: i, p: origin + i * gb })
    return out
  }
  return {
    v: build(viewCenterX.value - halfW, viewCenterX.value + halfW, p.x0),
    h: build(viewCenterY.value - halfH, viewCenterY.value + halfH, p.z0)
  }
})
/** 网格线宽（1 屏幕像素，缩放时不变粗） */
const gridW = computed(() => U(1))

/** 道路 polyline points 串 */
function roadPoints(rd) {
  return (rd.path || [])
    .filter((pt) => Array.isArray(pt) && finite(pt[0], pt[1]))
    .map((pt) => `${bx(pt[0])},${by(pt[1])}`)
    .join(' ')
}

/** 道路线宽（地理宽 → 用户单位；不小于 2 屏幕像素） */
function roadW(w) {
  return Math.max(U(2), ((Number(w) || 3) * proj.value.scale))
}

/** 住所房屋符号 path（屏幕恒定尺寸的小房子轮廓） */
function homeGlyph(h) {
  const x = bx(h.posX)
  const y = by(h.posZ)
  const w = U(11)
  const hw = w / 2
  return `M${x - hw} ${y} L${x - hw} ${y + w * 0.62} L${x + hw} ${y + w * 0.62} L${x + hw} ${y} L${x} ${y - hw * 0.85} Z`
}

/** 住所 title（名称 + 住户名单） */
function homeTitle(h) {
  const members = (h.members || []).map((m) => m.characterName || m.characterRef).join('、')
  return `${h.name || '住所'}${members ? `（${members}）` : ''}`
}

function trailX(m) { return m.fromX != null ? interpolate(m.fromX, m.toX, m.progress) : null }
function trailZ(m) { return m.fromZ != null ? interpolate(m.fromZ, m.toZ, m.progress) : null }
function interpolate(a, b, t) {
  const p = Math.max(0, Math.min(1, Number(t) || 0))
  return Number(a) + (Number(b) - Number(a)) * p
}

/** 角色圆点 title */
function npcTitle(n) {
  const parts = [n.npcName || n.npcRef]
  if (n.actionText) parts.push(n.actionText)
  if (n.thoughtText) parts.push(`想法：${n.thoughtText}`)
  if (n.moveState === 'walking') parts.push('移动中')
  return parts.join(' · ')
}

/**
 * 角色圆点（含重叠展开）。
 * <p>村落本体的角色坐标常只差几格（例如清晨多人都在自家/古井），直接画会互相盖死。
 * 这里按「屏幕像素阈值」聚类：同簇 ≥2 个时沿圆周微展开（位移 ≤ 9 屏幕像素，
 * 保持地理语义不失真，但每个点都可点可辨）。</p>
 */
const npcMarkers = computed(() => {
  const out = []
  const thr = 13 * u.value
  const groups = []
  for (const n of props.npcs || []) {
    if (!finite(n?.posX, n?.posZ)) continue
    const x = bx(n.posX)
    const y = by(n.posZ)
    let g = groups.find((it) => Math.hypot(it.cx - x, it.cy - y) < thr)
    if (!g) { g = { cx: x, cy: y, items: [] }; groups.push(g) }
    g.items.push({ n, x, y })
  }
  for (const g of groups) {
    const k = g.items.length
    const radius = k <= 1 ? 0 : (k === 2 ? U(5) : U(8))
    g.items.forEach((it, i) => {
      const ang = (Math.PI * 2 * i) / k - Math.PI / 2
      const sel = props.selectedRef === it.n.npcRef
      out.push({
        npcRef: it.n.npcRef,
        x: it.x + (radius ? Math.cos(ang) * radius : 0),
        y: it.y + (radius ? Math.sin(ang) * radius : 0),
        r: U(it.n.npcType === 'special' ? 5.4 : 4.4) * (sel ? 1.15 : 1),
        cls: it.n.npcType === 'special' ? 'special' : 'crowd',
        title: npcTitle(it.n)
      })
    })
  }
  return out
})

/** 角色基准锚点（标签定位用，取簇中心而非展开点） */
const npcAnchor = computed(() => {
  const map = new Map()
  for (const n of props.npcs || []) {
    if (!finite(n?.posX, n?.posZ)) continue
    map.set(n.npcRef, { x: bx(n.posX), y: by(n.posZ), n })
  }
  return map
})

/**
 * 标签避让（简易贪心）：
 * 按优先级（选中 > 悬停 > 特殊角色 > 区域 > 人群）依次尝试放标签，
 * 与已放置标签的估算矩形相交则跳过——宁可少显示一个名字，也不叠成一坨。
 * 被跳过的条目仍可通过圆点 tooltip / 点击查看。
 */
const visibleLabels = computed(() => {
  const npcFs = U(11.5)
  const regionFs = U(11)
  const cands = []

  if (!props.compact) {
    for (const n of props.npcs || []) {
      const a = npcAnchor.value.get(n.npcRef)
      if (!a) continue
      const isSel = props.selectedRef === n.npcRef
      const isHover = hoverRef.value === n.npcRef
      const isSpecial = n.npcType === 'special'
      if (!isSel && !isHover && !(showNames.value && isSpecial)) continue
      cands.push({
        key: 'npc:' + n.npcRef,
        text: n.npcName || n.npcRef,
        x: a.x, y: a.y + U(16), fs: npcFs,
        cls: 'hm-npc-name' + (isSel ? ' selected' : ''),
        priority: isSel ? 0 : (isHover ? 1 : (isSpecial ? 2 : 4))
      })
    }
    for (const r of props.regions || []) {
      if (!finite(r?.centerX, r?.centerZ)) continue
      cands.push({
        key: 'reg:' + r.locationId,
        text: r.name,
        // 区域名放在圆的下缘外侧，避免压在圆点上
        x: bx(r.centerX), y: by(r.centerZ) + regionR(r) + U(11),
        fs: regionFs,
        cls: 'hm-region-name' + (props.activeLocationId === r.locationId ? ' is-active' : ''),
        priority: props.activeLocationId === r.locationId ? 1 : 3,
        region: r
      })
    }
  }

  cands.sort((a, b) => a.priority - b.priority)
  const placed = []
  const result = []
  const boxOf = (c) => ({
    x1: c.x - (String(c.text).length * c.fs * 0.96) / 2 - U(2),
    x2: c.x + (String(c.text).length * c.fs * 0.96) / 2 + U(2),
    y1: c.y - c.fs * 1.05 - U(2),
    y2: c.y + c.fs * 0.3 + U(2)
  })
  const hit = (a, b) => !(a.x2 < b.x1 || a.x1 > b.x2 || a.y2 < b.y1 || a.y1 > b.y2)
  for (const c of cands) {
    const bb = boxOf(c)
    if (placed.some((p) => hit(p, bb))) continue
    placed.push(bb)
    result.push({
      key: c.key, text: c.text, x: c.x, y: c.y, fs: c.fs, cls: c.cls,
      onClick: c.region ? () => emit('pick-region', c.region) : null
    })
  }
  return result
})
</script>

<style scoped>
.hm-map {
  position: relative;
  width: 100%; height: 100%;
  background: var(--hm-page-bg);
  border-radius: 12px;
  overflow: hidden;
  touch-action: none;
}
.hm-svg {
  display: block;
  width: 100%; height: 100%;
  cursor: grab;
}
.hm-svg.is-panning { cursor: grabbing; }
/* 地图底渐变（SVG stop 不能直接用属性引用 CSS 变量，改用样式） */
.hm-stop-0 { stop-color: var(--hm-map-bg-0); }
.hm-stop-1 { stop-color: var(--hm-map-bg-1); }

/* 底图比例网格 */
.hm-grid line { stroke: var(--hm-grid); shape-rendering: crispEdges; }

/* 区域（按地貌着色：聚落=暖沙、山水林=青、水域=蓝、疆域=灰，虚界=自然/疆域） */
.hm-region { cursor: pointer; }
.hm-region-shape { transition: fill .2s, stroke .2s; }
.hm-region.settlement .hm-region-shape { fill: var(--hm-region-settlement); stroke: var(--hm-region-settlement-stroke); }
.hm-region.mountain .hm-region-shape { fill: var(--hm-region-nature); stroke: var(--hm-region-nature-stroke); }
.hm-region.forest .hm-region-shape { fill: var(--hm-region-nature); stroke: var(--hm-region-nature-stroke); }
.hm-region.water .hm-region-shape { fill: var(--hm-region-water); stroke: var(--hm-region-water-stroke); }
.hm-region.realm .hm-region-shape { fill: var(--hm-region-realm); stroke: var(--hm-region-realm-stroke); }
.hm-region.plain .hm-region-shape { fill: var(--hm-map-fill); stroke: var(--hm-map-stroke); }
.hm-region:hover .hm-region-shape,
.hm-region-shape.is-active {
  fill: color-mix(in srgb, var(--hm-gold) 18%, transparent);
  stroke: color-mix(in srgb, var(--hm-gold) 85%, transparent);
  stroke-dasharray: none;
}
.hm-region-name {
  fill: var(--hm-text-soft); font-weight: 600;
  text-anchor: middle; pointer-events: auto; cursor: pointer;
  user-select: none;
  paint-order: stroke; stroke: var(--hm-page-bg); stroke-linejoin: round;
}
.hm-region-name.is-active { fill: var(--hm-gold-soft); }

/* 道路：路基（深） + 路面（浅）两层 */
.hm-road-casing {
  fill: none; stroke: var(--hm-road-casing);
  stroke-linecap: round; stroke-linejoin: round;
}
.hm-road {
  fill: none; stroke: var(--hm-road-fill);
  stroke-linecap: round; stroke-linejoin: round;
}

/* 建筑（圆角房基，按类别着色） */
.hm-bld { cursor: pointer; }
.hm-building { stroke: var(--hm-building-stroke); transition: fill .15s; }
.hm-building.cat-landmark { fill: var(--hm-gold); }
.hm-building.cat-store { fill: var(--hm-blue); }
.hm-building.cat-farm { fill: var(--hm-farm); }
.hm-building.cat-road { fill: var(--hm-building); }
.hm-building.cat-other { fill: var(--hm-building); }
.hm-bld:hover .hm-building { fill: var(--hm-text); }

/* 住所 */
.hm-home { cursor: pointer; }
.hm-home-glyph {
  fill: var(--hm-home); stroke: var(--hm-home-stroke);
  pointer-events: all;
}
.hm-home:hover .hm-home-glyph { fill: color-mix(in srgb, var(--hm-gold) 85%, transparent); }

/* 移动轨迹（默认压暗，避免多条长虚线看起来像多出来的路） */
.hm-trail-line { stroke: var(--hm-trail); stroke-linecap: round; opacity: .3; transition: opacity .15s; }
.hm-trail-dot { fill: var(--hm-trail); stroke: var(--hm-page-bg); opacity: .45; transition: opacity .15s; }
.hm-trail.is-sel .hm-trail-line { opacity: .95; }
.hm-trail.is-sel .hm-trail-dot { opacity: 1; }

/* 角色圆点 */
.hm-npc { cursor: pointer; }
.hm-npc-dot { transition: stroke .15s, filter .15s; }
.hm-npc-dot.special { fill: var(--hm-gold); filter: drop-shadow(0 0 2px color-mix(in srgb, var(--hm-gold) 80%, transparent)); }
.hm-npc-dot.crowd { fill: var(--hm-blue); filter: drop-shadow(0 0 1.5px color-mix(in srgb, var(--hm-blue) 60%, transparent)); }
.hm-npc:hover .hm-npc-dot { filter: brightness(1.25) drop-shadow(0 0 3px color-mix(in srgb, var(--hm-text) 60%, transparent)); }
.hm-npc-name {
  fill: var(--hm-text); font-weight: 600;
  text-anchor: middle; pointer-events: none; user-select: none;
  paint-order: stroke; stroke: var(--hm-page-bg); stroke-linejoin: round;
}
.hm-npc-name.selected { fill: var(--hm-gold-soft); font-weight: 700; }

/* ===== 底部控件（左：图层 / 右：缩放 + 图例） ===== */
.hm-ctls {
  position: absolute; left: 10px; right: 10px; bottom: 10px;
  display: flex; align-items: flex-end; justify-content: space-between;
  gap: 8px; flex-wrap: wrap; pointer-events: none;
}
.hm-ctl-pill {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 4px 8px; border-radius: 999px;
  background: var(--hm-panel); backdrop-filter: blur(6px);
  border: 1px solid var(--hm-border);
  font-size: 12.5px; color: var(--hm-text-dim);
  pointer-events: auto;
}
.hm-bar-sep { width: 1px; height: 14px; margin: 0 2px; background: var(--hm-border); }
.hm-chip {
  border: 1px solid transparent; background: transparent; cursor: pointer;
  color: var(--hm-text-faint); font-size: 12.5px; padding: 2px 8px; border-radius: 999px;
  transition: all .15s var(--dsw-ease);
}
.hm-chip:hover { color: var(--hm-text); background: var(--bg-hover); }
.hm-chip.on { color: var(--hm-text); background: var(--accent-soft); border-color: var(--accent-border); }
.hm-zoom {
  border: 1px solid var(--hm-border); background: transparent; cursor: pointer;
  color: var(--hm-text-dim); width: 20px; height: 20px; border-radius: 6px;
  display: inline-flex; align-items: center; justify-content: center; font-size: 12px; line-height: 1;
}
.hm-zoom:hover { color: var(--hm-text); background: var(--bg-hover); }
.hm-zoom.is-text { width: auto; padding: 0 7px; font-size: 12.5px; }
.hm-zoom-val { font-variant-numeric: tabular-nums; min-width: 34px; text-align: center; color: var(--hm-text-dim); }
.hm-ctl-pill .hm-lg { display: inline-flex; align-items: center; gap: 4px; }
.hm-lg .lg-dot, .hm-lg .lg-home { display: inline-block; }
.hm-lg .lg-dot { width: 8px; height: 8px; border-radius: 50%; }
.hm-lg .lg-dot.gold { background: var(--hm-gold); }
.hm-lg .lg-dot.blue { background: var(--hm-blue); }
.hm-lg .lg-home { width: 8px; height: 8px; border-radius: 2px; background: var(--hm-home); }

/* 交互提示 */
.hm-map-hint {
  position: absolute; left: 10px; bottom: 44px;
  display: inline-flex; align-items: center; gap: 6px;
  padding: 4px 8px; border-radius: 8px;
  background: var(--hm-panel); backdrop-filter: blur(4px);
  border: 1px solid var(--hm-border);
  font-size: 12.5px; color: var(--hm-text-faint);
}
.hm-hint-close {
  border: none; background: transparent; cursor: pointer;
  color: var(--hm-text-faint); font-size: 12.5px; padding: 0 2px;
}
.hm-hint-close:hover { color: var(--hm-text); }
</style>
