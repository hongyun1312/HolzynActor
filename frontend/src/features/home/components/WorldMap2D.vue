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
    新首页 2D 世界地图画布（M4，features/home/components）。
    职责：以 SVG 渲染「村落俯视图」——区域圆（地点卡片，点击详情）+ 道路 + 建筑摆放点 +
    住所（同居共宅）+ 角色圆点（金=特殊 / 蓝=人群，含姓名名牌）+ 移动中角色轨迹线。
    纯展示 + 拾取：坐标统一为【中心化坐标】，由父组件换算后传入；不发起任何请求。
    图例与筛选（区域/道路/建筑/角色开关）由父组件传入筛选状态。
  -->
  <div class="hm-map" :class="{ 'is-compact': compact }">
    <svg
      ref="svgEl"
      class="hm-svg"
      :viewBox="`0 0 ${VB} ${VB}`"
      @click.self="emit('pick-empty')"
    >
      <defs>
        <radialGradient id="hm-bg" cx="50%" cy="50%" r="70%">
          <stop offset="0%" stop-color="#152238" />
          <stop offset="100%" stop-color="#0b1220" />
        </radialGradient>
      </defs>

      <!-- 地图底 -->
      <rect :x="0" :y="0" :width="VB" :height="VB" fill="url(#hm-bg)" />

      <!-- 区域圆（地点卡片：点击详情） -->
      <g v-for="r in regions" :key="r.locationId" class="hm-region"
         :class="{ active: activeLocationId === r.locationId }">
        <circle
          :cx="px(r.centerX)" :cy="py(r.centerZ)" :r="pr(r.radius)"
          :fill="regionFill(r)" :stroke="regionStroke(r)" stroke-width="1.5"
          :class="{ 'is-active': activeLocationId === r.locationId }"
          @click.stop="emit('pick-region', r)"
        />
        <text v-if="!compact" :x="px(r.centerX)" :y="py(r.centerZ)" class="hm-region-name"
              :class="{ 'is-active': activeLocationId === r.locationId }"
              @click.stop="emit('pick-region', r)">
          {{ r.name }}
        </text>
      </g>

      <!-- 道路 -->
      <g v-if="show.roads">
        <polyline
          v-for="(rd, i) in roads" :key="i"
          :points="roadPoints(rd)" class="hm-road"
          :stroke-width="roadW(rd.width)"
        />
      </g>

      <!-- 建筑摆放点 -->
      <g v-if="show.buildings">
        <circle
          v-for="p in placements" :key="p.id"
          :cx="px(p.posX)" :cy="py(p.posZ)" r="2.4" class="hm-building"
          @click.stop="emit('pick-placement', p)"
        />
      </g>

      <!-- 住所（同居共宅，房屋符号；点住所有住户名单提示） -->
      <g v-if="show.homes">
        <g v-for="h in homes" :key="h.id" class="hm-home" @click.stop="emit('pick-home', h)">
          <path :d="homeGlyph(h)" class="hm-home-glyph" />
          <title>{{ homeTitle(h) }}</title>
        </g>
      </g>

      <!-- 移动轨迹（移动中角色：from→to 连线 + 方向箭头 + 进度点） -->
      <g v-for="m in movements" :key="m.npcRef" class="hm-trail">
        <line
          :x1="px(m.fromX)" :y1="py(m.fromZ)" :x2="px(m.toX)" :y2="py(m.toZ)"
          class="hm-trail-line" :stroke-dasharray="`6 4`"
        />
        <circle :cx="px(trailX(m))" :cy="py(trailZ(m))" r="3.2" class="hm-trail-dot" />
        <text :x="px(trailX(m))" :y="py(trailZ(m)) - 6" class="hm-trail-label">{{ m.npcName || m.npcRef }}</text>
      </g>

      <!-- 角色圆点（金=特殊 / 蓝=人群；点击选中） -->
      <g v-for="n in npcs" :key="n.npcRef" class="hm-npc"
         :class="{ selected: selectedRef === n.npcRef }"
         @click.stop="emit('pick-npc', n.npcRef)">
        <circle
          :cx="px(n.posX)" :cy="py(n.posZ)" r="npcR(n)"
          :class="'hm-npc-dot ' + dotCls(n)" :stroke="selectedRef === n.npcRef ? '#fff' : 'none'"
          stroke-width="1.6"
        />
        <text v-if="!compact" :x="px(n.posX)" :y="py(n.posZ) + npcR(n) + 10"
              class="hm-npc-name" :class="{ selected: selectedRef === n.npcRef }">
          {{ n.npcName || n.npcRef }}
        </text>
        <title>{{ npcTitle(n) }}</title>
      </g>
    </svg>

    <!-- 图例 -->
    <div v-if="showLegend" class="hm-legend">
      <span class="hm-lg"><i class="lg-dot gold" />特殊 NPC</span>
      <span class="hm-lg"><i class="lg-dot blue" />普通人群</span>
      <span class="hm-lg"><i class="lg-road" />道路</span>
      <span class="hm-lg"><i class="lg-home" />住所</span>
    </div>
  </div>
</template>

<script setup>
/**
 * 2D 世界地图画布（M4 新首页中栏主角）。
 * <p>职责：SVG 渲染村落俯视图——区域圆（地点卡片）/道路/建筑/住所/角色圆点/移动轨迹；
 * 拾取事件：pick-region / pick-placement / pick-home / pick-npc / pick-empty。</p>
 * <p>坐标域：全部接收【中心化坐标】（父组件用 homeCoord 换算），本组件内部直接投影到画布百分比。</p>
 * <p>渲染范式：与旧 3D 沙盘对照——旧版 Three.js 像素地形 + GLB 摆放；新版纯 SVG 俯视图
 * （地图即 UI 主角，参照 a16z ai-town 2D 范式）。</p>
 */
import { computed } from 'vue'
import { toPercent } from '../homeCoord'

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
  /** 是否紧凑模式（隐藏文字） */
  compact: { type: Boolean, default: false },
  /** 是否显示图例 */
  showLegend: { type: Boolean, default: true }
})
const emit = defineEmits(['pick-region', 'pick-placement', 'pick-home', 'pick-npc', 'pick-empty'])

/** SVG 视口边长（正方形百分比坐标系 0..100） */
const VB = 100

/** 中心化坐标 → 画布 X% */
function px(x) { return toPercent(x, props.sceneSize) }
/** 中心化坐标 → 画布 Y%（Z 轴 → 屏幕向下，同向映射） */
function py(z) { return toPercent(z, props.sceneSize) }
/** 半径（格）→ 画布半径%（与 toPercent 缩放一致：90% 覆盖 -0.5..0.5） */
function pr(r) {
  const n = Number(r) || 0
  return (n / props.sceneSize) * 90
}

/** 角色圆点半径（特殊 3.2 / 人群 2.6；紧凑模式放大以便点选） */
function npcR(n) {
  return props.compact ? 3.6 : (n?.npcType === 'special' ? 3.2 : 2.6)
}

/** 圆点样式类 */
function dotCls(n) {
  return n?.npcType === 'special' ? 'special' : 'crowd'
}

/** 道路 polyline points 串 */
function roadPoints(rd) {
  return (rd.path || []).map((pt) => `${px(pt[0])},${py(pt[1])}`).join(' ')
}

/** 道路线宽（格 → px；默认 3m≈15 格≈画布 13.5%） */
function roadW(w) {
  return Math.max(0.8, ((Number(w) || 3) / props.sceneSize) * 90)
}

/** 区域填充色（稳定按 index 取色；可被 active 覆盖） */
function regionFill(r, idx) {
  return 'rgba(77, 159, 255, 0.10)'
}
function regionStroke(r, idx) {
  return 'rgba(120, 160, 255, 0.55)'
}

/** 住所房屋符号 path（小房子轮廓） */
function homeGlyph(h) {
  const x = px(h.posX)
  const y = py(h.posZ)
  const w = 6
  const hw = w / 2
  return `M${x - hw} ${y} L${x - hw} ${y + w * 0.7} L${x + hw} ${y + w * 0.7} L${x + hw} ${y} L${x} ${y - hw * 0.8} Z`
}

/** 住所 title（名称 + 住户名单） */
function homeTitle(h) {
  const members = (h.members || []).map((m) => m.characterName || m.characterRef).join('、')
  return `${h.name || '住所'}${members ? `（${members}）` : ''}`
}

/** 移动轨迹当前进度点 X/Z（中心化坐标，由父组件传 progress 算出的 trailX/trailZ） */
function trailX(m) {
  return m._trailX != null ? m._trailX : interpolate(m.fromX, m.toX, m.progress)
}
function trailZ(m) {
  return m._trailZ != null ? m._trailZ : interpolate(m.fromZ, m.toZ, m.progress)
}
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
</script>

<style scoped>
.hm-map {
  position: relative;
  width: 100%; height: 100%;
  display: flex; align-items: center; justify-content: center;
  background: #0b1220;
  border-radius: 12px;
  overflow: hidden;
}
.hm-svg {
  width: 100%; height: 100%;
  max-width: 100%; max-height: 100%;
  cursor: crosshair;
}

/* 区域 */
.hm-region { cursor: pointer; }
.hm-region circle { transition: fill 0.2s, stroke 0.2s; }
.hm-region circle:hover, .hm-region circle.is-active {
  fill: rgba(255, 212, 92, 0.16);
  stroke: rgba(255, 212, 92, 0.8);
}
.hm-region-name {
  fill: #cfe0ff; font-size: 4.2px; font-weight: 600;
  text-anchor: middle; pointer-events: none;
  user-select: none;
}
.hm-region-name.is-active { fill: #ffe9a8; }

/* 道路 */
.hm-road {
  fill: none; stroke: rgba(160, 180, 210, 0.4);
  stroke-linecap: round; stroke-linejoin: round;
}

/* 建筑 */
.hm-building { fill: rgba(200, 210, 235, 0.55); cursor: pointer; }
.hm-building:hover { fill: #fff; }

/* 住所 */
.hm-home { cursor: pointer; }
.hm-home-glyph {
  fill: rgba(255, 212, 92, 0.55); stroke: rgba(255, 212, 92, 0.9);
  stroke-width: 0.5; pointer-events: all;
}
.hm-home:hover .hm-home-glyph { fill: rgba(255, 212, 92, 0.85); }

/* 移动轨迹 */
.hm-trail-line {
  stroke: #ffd45c; stroke-width: 1.6; stroke-linecap: round;
  opacity: 0.7;
}
.hm-trail-dot { fill: #ffd45c; stroke: #0b1220; stroke-width: 0.6; }
.hm-trail-label {
  fill: #ffd45c; font-size: 3px; font-weight: 600;
  text-anchor: middle; pointer-events: none;
}

/* 角色圆点 */
.hm-npc { cursor: pointer; }
.hm-npc-dot { transition: stroke 0.15s, filter 0.15s; }
.hm-npc-dot.special { fill: #ffd45c; filter: drop-shadow(0 0 2px rgba(255, 212, 92, 0.8)); }
.hm-npc-dot.crowd { fill: #8fb1ff; filter: drop-shadow(0 0 1.5px rgba(143, 177, 255, 0.6)); }
.hm-npc:hover .hm-npc-dot { filter: brightness(1.25) drop-shadow(0 0 3px rgba(255, 255, 255, 0.6)); }
.hm-npc-name {
  fill: #e8f0ff; font-size: 3px; font-weight: 500;
  text-anchor: middle; pointer-events: none; user-select: none;
}
.hm-npc-name.selected { fill: #ffe9a8; font-weight: 700; }

/* 图例 */
.hm-legend {
  position: absolute; bottom: 10px; left: 10px;
  display: flex; flex-wrap: wrap; gap: 10px;
  padding: 6px 10px; border-radius: 8px;
  background: rgba(13, 20, 36, 0.8); backdrop-filter: blur(4px);
  border: 1px solid rgba(120, 160, 255, 0.25);
  font-size: 11px; color: #9db8e8;
}
.hm-lg { display: inline-flex; align-items: center; gap: 5px; }
.lg-dot, .lg-road, .lg-home { display: inline-block; width: 9px; height: 9px; border-radius: 50%; }
.lg-dot.gold { background: #ffd45c; box-shadow: 0 0 4px rgba(255, 212, 92, 0.7); }
.lg-dot.blue { background: #8fb1ff; box-shadow: 0 0 4px rgba(143, 177, 255, 0.7); }
.lg-road { width: 12px; height: 2px; border-radius: 1px; background: rgba(160, 180, 210, 0.6); }
.lg-home { width: 8px; height: 8px; border-radius: 2px; background: rgba(255, 212, 92, 0.6); }
</style>
