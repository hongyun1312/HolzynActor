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
    世界沙盘 S4：Three.js 3D 场景画布（像素地形 + 河路 + GLB 资产摆放 + NPC 标记/名牌/气泡）
    左键=旋转、右键=平移、滚轮=缩放（OrbitControls 默认键位，与用户确认一致）；
    单击拾取：NPC → 选入右侧面板，建筑/地点 → 地点浮窗，空地 → 取消选中/关浮窗
  -->
  <div ref="containerEl" class="sb3d-container">
    <div v-if="fallback" class="sb3d-fallback">当前环境不支持 WebGL 3D 渲染</div>
  </div>
</template>

<script setup>
/**
 * 世界沙盘 3D 场景组件（S4 版，features/sandbox/components）。
 * <p>职责：以 Three.js 渲染「正方形场景」——由四个图层组成（features/sandbox/sandbox3d/）：</p>
 * <ul>
 * <li><b>TerrainLayer 像素地形</b>：程序地形（style+seed 确定性噪声分类，10 类色板，1 块=1 米）
 * + 河/路栅格化色带（layout_json.rivers/roads），canvas 纹理 NearestFilter 单 draw call；</li>
 * <li><b>PlacementLayer 资产摆放</b>：按摆放表加载 GLB（meshopt 解码 + 缓存克隆）落位，
 * 加载失败回退占位盒，加载进度经 {@code progress} 事件上报；</li>
 * <li><b>NpcMarkerLayer NPC 标记</b>：特殊 NPC 金圆锥 / 人群蓝圆柱（InstancedMesh）+
 * 脚下光环（S4：俯视一眼分辨谁是 NPC、特殊还是普通），行走线性插值，选中高亮；</li>
 * <li><b>NpcLabelLayer 名牌/气泡</b>（S4，CSS2D）：头顶名牌常显（名字，特殊金/普通蓝/选中白），
 * 动态气泡（动作/状态/想法/对话/事件）变化时弹出数秒淡出。</li>
 * </ul>
 * <p>S4 新增能力：</p>
 * <ul>
 * <li><b>点击拾取</b>——Raycaster 单击（拖拽/长按不算）：NPC 标记/光环实例 → pick-npc；
 * 摆放 GLB/占位盒（userData.placementId）→ pick-placement；空地 → pick-empty；</li>
 * <li><b>晨昏昼夜</b>——按 {@code gameSecond}（引擎实际位置）驱动天空背景色 + 光照强度/色温
 * 插值（白天亮蓝 / 晨昏暖橙 / 夜间深蓝，保持最低可见亮度）；</li>
 * <li><b>天气粒子</b>——按 {@code weather.condition} 切换雨（快速下落）/雪（缓降摆荡）/无。</li>
 * </ul>
 * <p>相机约定（用户确认）：看向原点 (0,0,0)、俯仰 35°、方位 45°、距离自适应取景；
 * 键位左键=旋转、右键=平移、滚轮=缩放。坐标口径：地形/摆放坐标 0..size 渲染时偏移 -size/2
 * 到中心化；NPC 状态坐标已中心化直接用。</p>
 * <p>生命周期：容器挂载后初始化，props 变化增量重建对应图层，卸载释放全部 GPU 资源。</p>
 */
import * as THREE from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import { ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { TerrainLayer } from '../sandbox3d/terrain.js'
import { PlacementLayer } from '../sandbox3d/placements.js'
import { NpcMarkerLayer } from '../sandbox3d/npcMarkers.js'
import { NpcLabelLayer } from '../sandbox3d/labelLayer.js'
import { WeatherParticleLayer } from '../sandbox3d/weatherParticles.js'

/** ==================== 固定常量（用户确认，双端统一） ==================== */

/** 相机俯仰角（与地面夹角，度；S3.1 用户确认 =35°） */
const CAMERA_ELEVATION_DEG = 35

/** 相机水平方位角（度，默认 45°） */
const CAMERA_AZIMUTH_DEG = 45

/** 相机视场角（度，垂直方向） */
const CAMERA_FOV_DEG = 45

/** 相机初始化朝向点（用户确认：看向原点） */
const CAMERA_TARGET = { x: 0, y: 0, z: 0 }

/** 单击判定阈值（像素；拖拽/缩放移动超过该值不算点击） */
const CLICK_MOVE_PX = 6
/** 单击判定时长（毫秒；长按不算点击） */
const CLICK_MAX_MS = 700

/** ==================== 组件定义 ==================== */

const props = defineProps({
  /** 场景边长（米，正方形；缺省 500） */
  sceneSize: { type: Number, default: 500 },
  /** 布局数据（overview mainScene.layout）：{scene:{size,style,seed}, rivers[], roads[]}；null=纯地面回退 */
  layout: { type: Object, default: null },
  /** 资产摆放列表（ScenePlacementVO[]：assetId/posX/posZ/rotY/scale + filePath） */
  placements: { type: Array, default: () => [] },
  /** NPC 状态列表（SandboxStateVO[]：npcRef/npcType/posX/posZ/heading/moveState） */
  npcs: { type: Array, default: () => [] },
  /** 选中 NPC 引用（与右侧面板联动高亮） */
  selectedRef: { type: String, default: null },
  /** S4：当前游戏秒（引擎实际位置；驱动晨昏昼夜光照） */
  gameSecond: { type: Number, default: 0 },
  /** S4：当前天气（{condition, temperature, wind}；condition=雨/雪 驱动粒子） */
  weather: { type: Object, default: null },
  /** S4：气泡队列（[{seq, npcRef, kind, text}]；seq 递增去重，kind ∈ action/state/thought/talk/event） */
  bubbles: { type: Array, default: () => [] },
  /** S4：地点锚定气泡队列（[{seq, x, z, kind, text}]；无当事人事件在地点坐标弹一次性气泡） */
  locationBubbles: { type: Array, default: () => [] }
})

const emit = defineEmits(['progress', 'pick-npc', 'pick-placement', 'pick-empty'])

const containerEl = ref(null)
const fallback = ref(false)

let renderer = null
let scene = null
let camera = null
let controls = null
let hemi = null
let sun = null
let fill = null
let terrain = null
let placementsLayer = null
let markers = null
let labels = null
let weatherLayer = null
let boundary = null
let rafId = null
let resizeObserver = null
let lastFrameAt = 0
let raycaster = null
let pointerDown = null
let lastBubbleSeq = 0
let lastLocationBubbleSeq = 0

/** 初始化：渲染器 / 相机 / 控制 / 灯光 / 四图层 + 动画循环 */
function init() {
  const el = containerEl.value
  if (!el) return
  const size = validSize(props.sceneSize)

  try {
    renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: true })
  } catch (_) {
    fallback.value = true
    return
  }
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
  renderer.setSize(el.clientWidth || 1, el.clientHeight || 1)
  el.appendChild(renderer.domElement)

  scene = new THREE.Scene()
  scene.background = new THREE.Color(0x0b1220)

  camera = new THREE.PerspectiveCamera(CAMERA_FOV_DEG, (el.clientWidth || 1) / (el.clientHeight || 1), 0.1, size * 10)
  positionCamera(size)

  controls = new OrbitControls(camera, renderer.domElement)
  controls.target.set(CAMERA_TARGET.x, CAMERA_TARGET.y, CAMERA_TARGET.z)
  controls.enableDamping = true
  controls.dampingFactor = 0.08
  controls.minDistance = size * 0.05
  controls.maxDistance = size * 3
  controls.maxPolarAngle = Math.PI / 2 - 0.03
  controls.screenSpacePanning = true
  controls.addEventListener('start', () => { renderer.domElement.style.cursor = 'grabbing' })
  controls.addEventListener('end', () => { renderer.domElement.style.cursor = 'grab' })
  renderer.domElement.style.cursor = 'grab'

  // 灯光（GLB 多为 PBR 材质，需环境光 + 方向光；S4 起强度随晨昏昼夜动态插值）
  hemi = new THREE.HemisphereLight(0xdfe9ff, 0x3a4a2a, 1.0)
  scene.add(hemi)
  sun = new THREE.DirectionalLight(0xfff2dd, 1.5)
  sun.position.set(size * 0.4, size * 0.6, size * 0.3)
  scene.add(sun)
  fill = new THREE.DirectionalLight(0x9fb8ff, 0.3)
  fill.position.set(-size * 0.3, size * 0.25, -size * 0.4)
  scene.add(fill)

  // 四图层
  terrain = new TerrainLayer(scene)
  placementsLayer = new PlacementLayer(scene, { onProgress: (p) => emit('progress', p) })
  markers = new NpcMarkerLayer(scene)
  labels = new NpcLabelLayer(el, scene)
  weatherLayer = createWeatherLayer(size)
  buildBoundary(size)

  // 拾取
  raycaster = new THREE.Raycaster()
  renderer.domElement.addEventListener('pointerdown', onPointerDown)
  renderer.domElement.addEventListener('pointerup', onPointerUp)

  // 初始数据
  rebuildTerrain()
  rebuildPlacements()
  markers.setNpcs(props.npcs)
  markers.setSelected(props.selectedRef)
  // S4：拾取粗判球覆盖全场景（InstancedMesh.raycast 粗判需含实例偏移，否则标记点不中）
  markers.setPickRadius(Math.hypot(size / 2, size / 2) + 12)
  labels.setNpcs(props.npcs)
  labels.setSelected(props.selectedRef)
  labels.setSize(el.clientWidth || 1, el.clientHeight || 1)
  applyWeather(props.weather)
  applyDayNight(props.gameSecond)

  // dev 调试钩子（S4：浏览器冒烟/调试用——暴露场景内部状态供程序化验证；仅 dev 构建）
  if (import.meta.env.DEV) {
    window.__sb3d = {
      scene, camera, renderer, terrain, placementsLayer, markers, labels, weatherLayer, controls,
      raycaster, // 拾取射线（验证用：程序化判定某屏幕/世界点命中哪个实例）
      applyDayNight, // 昼夜光照（验证用：直接传入游戏秒观察背景/光照变化）
      validSize
    }
  }

  animate()
  setupResize()
}

/** 相机初始化位置（看向原点 + 俯仰 35° + 方位 45° + 距离自适应取景） */
function positionCamera(size) {
  const polar = THREE.MathUtils.degToRad(90 - CAMERA_ELEVATION_DEG)
  const az = THREE.MathUtils.degToRad(CAMERA_AZIMUTH_DEG)
  const dirX = Math.sin(polar) * Math.sin(az)
  const dirY = Math.cos(polar)
  const dirZ = Math.sin(polar) * Math.cos(az)
  const radius = (size / 2) * Math.SQRT2
  const dist = radius / Math.sin(THREE.MathUtils.degToRad(CAMERA_FOV_DEG / 2))
  camera.position.set(dirX * dist, dirY * dist, dirZ * dist)
  camera.lookAt(CAMERA_TARGET.x, CAMERA_TARGET.y, CAMERA_TARGET.z)
}

/** 正方形边界框（中心原点，四角 (±half, 0, ±half) 闭合线框） */
function buildBoundary(size) {
  if (boundary && scene) {
    scene.remove(boundary)
    boundary.geometry.dispose()
    boundary.material.dispose()
  }
  const half = size / 2
  const pts = [
    new THREE.Vector3(-half, 0, -half),
    new THREE.Vector3(half, 0, -half),
    new THREE.Vector3(half, 0, half),
    new THREE.Vector3(-half, 0, half)
  ]
  boundary = new THREE.LineLoop(
    new THREE.BufferGeometry().setFromPoints(pts),
    new THREE.LineBasicMaterial({ color: 0x3a5a8c })
  )
  boundary.position.y = 0.05
  scene.add(boundary)
}

/** 重建地形（layout 变化 / 尺寸变化） */
function rebuildTerrain() {
  if (!terrain) return
  const lay = props.layout
  const size = validSize(props.sceneSize)
  if (lay && lay.scene) {
    terrain.build({
      size,
      styleCode: lay.scene.style || lay.scene.styleCode,
      seed: lay.scene.seed,
      rivers: lay.rivers || [],
      roads: lay.roads || [],
      palette: lay.scene.palette
    })
  } else {
    // 无布局回退：纯色地面（原 S3 纯地面语义）
    terrain.build({ size, styleCode: 'plain', seed: 1, rivers: [], roads: [] })
  }
}

/** 重建摆放（placements 变化 / 尺寸变化） */
function rebuildPlacements() {
  if (!placementsLayer) return
  placementsLayer.load(props.placements || [], { size: validSize(props.sceneSize) })
}

/** 创建天气粒子层（尺寸变化时重建） */
function createWeatherLayer(size) {
  if (weatherLayer) weatherLayer.dispose()
  const layer = new WeatherParticleLayer(scene, size)
  if (props.weather) layer.setWeather(props.weather.condition)
  return layer
}

// ==================== S4：晨昏昼夜光照 ====================

const BG_NIGHT = new THREE.Color(0x0b1220)
const BG_DAY = new THREE.Color(0x7fb2d9)
const BG_DUSK = new THREE.Color(0xff9a6b)
const SUN_DAY = new THREE.Color(0xfff2dd)
const SUN_DUSK = new THREE.Color(0xff8f5f)

/** 由游戏秒计算昼夜相位：daylight=白天包络 0..1，warm=晨昏暖色 0..1 */
function daylightAt(gameSecond) {
  const sec = ((Number(gameSecond) || 0) % 86400 + 86400) % 86400
  const hour = sec / 3600
  let daylight = 0
  if (hour >= 5.5 && hour <= 18.5) {
    daylight = Math.sin((Math.PI * (hour - 5.5)) / 13) // 5:30 日出 → 12:00 峰值 → 18:30 日落
  }
  const warm = Math.exp(-Math.pow(hour - 6.8, 2) / 2.2) + Math.exp(-Math.pow(hour - 17.2, 2) / 2.2)
  return { daylight: clamp01(daylight), warm: clamp01(warm) }
}

function clamp01(v) {
  return v < 0 ? 0 : v > 1 ? 1 : v
}

/** 应用昼夜光照（每帧调用；天气雨/雪轻微减光） */
function applyDayNight(gameSecond) {
  if (!scene || !sun || !hemi || !fill) return
  const { daylight, warm } = daylightAt(gameSecond)
  const wet = props.weather && (props.weather.condition === '雨' || props.weather.condition === '雪')
  const dim = wet ? 0.72 : 1
  // 天空背景：夜 → 昼 插值 + 晨昏暖色叠加
  scene.background.copy(BG_NIGHT).lerp(BG_DAY, daylight)
  if (warm > 0) scene.background.lerp(BG_DUSK, warm * (0.3 + 0.45 * daylight))
  // 光照：夜间保持最低可见亮度（标记/建筑不没入黑暗）
  sun.intensity = 0.22 + 1.45 * daylight * dim
  sun.color.copy(SUN_DAY).lerp(SUN_DUSK, warm * 0.55)
  hemi.intensity = 0.4 + 0.75 * daylight
  fill.intensity = 0.15 + 0.25 * daylight
}

// ==================== S4：点击拾取（Raycaster 单击） ====================

function onPointerDown(e) {
  pointerDown = { x: e.clientX, y: e.clientY, t: performance.now() }
}

function onPointerUp(e) {
  if (!pointerDown) return
  const dx = e.clientX - pointerDown.x
  const dy = e.clientY - pointerDown.y
  const dt = performance.now() - pointerDown.t
  pointerDown = null
  if (Math.hypot(dx, dy) > CLICK_MOVE_PX || dt > CLICK_MAX_MS) return // 拖拽旋转/平移不算点击
  pickAt(e)
}

/** 拾取判定顺序：NPC 标记/光环实例 → 资产摆放（递归网格） → 空地 */
function pickAt(e) {
  if (!renderer || !camera || !markers || !placementsLayer) return
  const rect = renderer.domElement.getBoundingClientRect()
  const ndc = new THREE.Vector2(
    ((e.clientX - rect.left) / (rect.width || 1)) * 2 - 1,
    -((e.clientY - rect.top) / (rect.height || 1)) * 2 + 1
  )
  raycaster.setFromCamera(ndc, camera)

  // ① NPC 标记（圆锥/圆柱/脚下光环实例）
  const markerHits = raycaster.intersectObjects(
    [markers.specialMesh, markers.crowdMesh, markers.ringMesh], false)
  if (markerHits.length > 0) {
    const ref = npcRefByInstance(markerHits[0].object, markerHits[0].instanceId)
    if (ref) {
      emit('pick-npc', ref)
      return
    }
  }
  // ② 资产摆放（GLB 克隆 / 占位盒；递归到叶子网格后沿父链找 userData.placementId）
  const placementHits = raycaster.intersectObjects(placementsLayer.group.children, true)
  for (const h of placementHits) {
    let o = h.object
    while (o) {
      if (o.userData && o.userData.placementId != null) {
        emit('pick-placement', {
          placementId: o.userData.placementId,
          name: o.userData.name || ''
        })
        return
      }
      o = o.parent
    }
  }
  // ③ 空地 → 取消选中 / 关闭浮窗
  emit('pick-empty')
}

/** 实例命中 → npcRef（标记实例按 index，光环实例按 ringIndex） */
function npcRefByInstance(mesh, instanceId) {
  if (instanceId == null) return null
  for (const [ref, e] of markers.entries) {
    if (mesh === markers.ringMesh) {
      if (e.ringIndex === instanceId) return ref
    } else if (e.mesh === mesh && e.index === instanceId) {
      return ref
    }
  }
  return null
}

// ==================== S4：天气粒子 ====================

function applyWeather(w) {
  if (!weatherLayer) return
  weatherLayer.setWeather(w && w.condition)
}

/** 动画循环（阻尼控制 + NPC 插值 + 名牌跟随 + 天气粒子 + 昼夜 + 渲染） */
function animate() {
  rafId = requestAnimationFrame(animate)
  const now = performance.now()
  const dt = lastFrameAt ? Math.min((now - lastFrameAt) / 1000, 0.1) : 0.016
  lastFrameAt = now
  if (controls) controls.update()
  if (markers) markers.update(dt)
  if (labels) labels.syncPositions(markers ? markers.entries : null)
  if (weatherLayer) weatherLayer.update(dt)
  applyDayNight(props.gameSecond)
  if (renderer && scene && camera) {
    renderer.render(scene, camera)
    if (labels) labels.render(scene, camera)
  }
}

/** 容器尺寸自适应 */
function setupResize() {
  resizeObserver = new ResizeObserver(() => {
    const el = containerEl.value
    if (!el || !renderer || !camera) return
    const w = el.clientWidth || 1
    const h = el.clientHeight || 1
    camera.aspect = w / h
    camera.updateProjectionMatrix()
    renderer.setSize(w, h)
    if (labels) labels.setSize(w, h)
  })
  if (containerEl.value) resizeObserver.observe(containerEl.value)
}

/** 卸载：取消循环 + 释放控制/图层/渲染器 + 移除画布 */
function dispose() {
  if (rafId) cancelAnimationFrame(rafId)
  rafId = null
  if (resizeObserver) { resizeObserver.disconnect(); resizeObserver = null }
  if (controls) { controls.dispose(); controls = null }
  if (renderer) {
    renderer.domElement.removeEventListener('pointerdown', onPointerDown)
    renderer.domElement.removeEventListener('pointerup', onPointerUp)
  }
  if (terrain) { terrain.dispose(); terrain = null }
  if (placementsLayer) { placementsLayer.dispose(); placementsLayer = null }
  if (markers) { markers.dispose(); markers = null }
  if (labels) { labels.dispose(); labels = null }
  if (weatherLayer) { weatherLayer.dispose(); weatherLayer = null }
  if (boundary && scene) {
    scene.remove(boundary)
    boundary.geometry.dispose()
    boundary.material.dispose()
    boundary = null
  }
  if (renderer) {
    renderer.dispose()
    renderer.domElement.remove()
    renderer = null
  }
  // dev 钩子卫生：实例卸载清除 __sb3d（避免残留 stale 引用误导程序化验证/调试）
  if (import.meta.env.DEV && window.__sb3d) {
    delete window.__sb3d
  }
  scene = null
  camera = null
}

/** 场景尺寸变化：重建边界 + 相机范围 + 地形 + 摆放 + 天气粒子 + 拾取粗判球 */
watch(() => props.sceneSize, (v) => {
  if (!scene || !camera || !controls) return
  const size = validSize(v)
  buildBoundary(size)
  positionCamera(size)
  controls.target.set(CAMERA_TARGET.x, CAMERA_TARGET.y, CAMERA_TARGET.z)
  camera.far = size * 10
  camera.updateProjectionMatrix()
  controls.minDistance = size * 0.05
  controls.maxDistance = size * 3
  rebuildTerrain()
  rebuildPlacements()
  if (markers) markers.setPickRadius(Math.hypot(size / 2, size / 2) + 12)
  weatherLayer = createWeatherLayer(size)
  // dev 钩子同步：sceneSize 变化重建天气层后，__sb3d 引用保持最新实例（防断言读到已 dispose 旧层）
  if (import.meta.env.DEV && window.__sb3d) window.__sb3d.weatherLayer = weatherLayer
})

/** 布局变化 → 重建地形 */
watch(() => props.layout, () => { if (scene) rebuildTerrain() }, { deep: true })

/** 摆放变化 → 重建摆放层 */
watch(() => props.placements, () => { if (scene) rebuildPlacements() }, { deep: true })

/** NPC 变化 → 全量同步标记 + 名牌（SSE 增量已在父组件合并） */
watch(() => props.npcs, (list) => {
  if (markers) markers.setNpcs(list || [])
  if (labels) labels.setNpcs(list || [])
}, { deep: true })

/** 选中变化 → 高亮（标记 + 名牌） */
watch(() => props.selectedRef, (ref2) => {
  if (markers) markers.setSelected(ref2)
  if (labels) labels.setSelected(ref2)
})

/** S4：天气变化 → 粒子切换 */
watch(() => props.weather, (w) => { if (scene) applyWeather(w) }, { deep: true })

/** S4：气泡队列 → 逐条弹出（seq 递增去重；同一 NPC 新气泡替换旧气泡） */
watch(() => props.bubbles, (list) => {
  if (!labels || !list) return
  for (const b of list) {
    if (!b || !b.npcRef || !b.text) continue
    if (b.seq != null && b.seq <= lastBubbleSeq) continue
    labels.showBubble(b.npcRef, { kind: b.kind, text: b.text, durationMs: b.durationMs })
    if (b.seq != null) lastBubbleSeq = Math.max(lastBubbleSeq, b.seq)
  }
}, { deep: true })

/** S4：地点锚定气泡队列 → 逐条弹出（seq 递增去重；到期自动淡出） */
watch(() => props.locationBubbles, (list) => {
  if (!labels || !list) return
  for (const b of list) {
    if (!b || !b.text) continue
    if (b.seq != null && b.seq <= lastLocationBubbleSeq) continue
    labels.showLocationBubble(b.x, b.z, { kind: b.kind, text: b.text, durationMs: b.durationMs })
    if (b.seq != null) lastLocationBubbleSeq = Math.max(lastLocationBubbleSeq, b.seq)
  }
}, { deep: true })

onMounted(init)
onBeforeUnmount(dispose)

/** 场景边长兜底（非法/过小用 500） */
function validSize(v) {
  const n = Number(v)
  return Number.isFinite(n) && n >= 100 ? n : 500
}
</script>

<style scoped>
.sb3d-container {
  position: absolute;
  /* 3D 画布向左延伸覆盖侧边栏区域（--sb-inset-left 由 ProjectLayout.is-fullbleed 提供） */
  left: calc(-1 * var(--sb-inset-left, 264px));
  right: 0;
  top: 0;
  bottom: 0;
  overflow: hidden;
  background: #0b1220;
}
.sb3d-container :deep(canvas) {
  display: block;
  outline: none;
}
.sb3d-fallback {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #9db8e8;
  font-size: 13px;
}

/* ==================== S4：头顶名牌（CSS2D，跟随 3D 位置，恒定屏幕大小） ==================== */
.sb3d-container :deep(.sb3d-labels) {
  overflow: hidden;
}
.sb3d-container :deep(.sb3d-tag) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 1px 8px;
  border-radius: 9px;
  background: rgba(13, 20, 36, 0.78);
  border: 1px solid rgba(120, 160, 255, 0.4);
  color: #e8f0ff;
  font-size: 11px;
  font-weight: 700;
  line-height: 1.5;
  white-space: nowrap;
  transform: translate(-50%, -50%);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.35);
  pointer-events: none;
  user-select: none;
  transition: border-color 0.2s, box-shadow 0.2s;
}
/* 特殊 NPC 名牌：金 */
.sb3d-container :deep(.sb3d-tag.special) {
  border-color: rgba(255, 212, 92, 0.8);
  color: #ffe9a8;
  background: rgba(40, 32, 10, 0.82);
}
/* 普通人群名牌：蓝 */
.sb3d-container :deep(.sb3d-tag.crowd) {
  border-color: rgba(143, 177, 255, 0.75);
  color: #cfe0ff;
}
/* 选中：白亮高亮 */
.sb3d-container :deep(.sb3d-tag.selected) {
  border-color: #ffffff;
  box-shadow: 0 0 10px rgba(255, 255, 255, 0.65);
}

/* ==================== S4：动态气泡（变化时弹出，数秒淡出） ==================== */
.sb3d-container :deep(.sb3d-bubble) {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  max-width: 230px;
  padding: 3px 10px;
  border-radius: 11px;
  background: rgba(13, 20, 36, 0.88);
  border: 1px solid rgba(120, 160, 255, 0.45);
  color: #e8f0ff;
  font-size: 11.5px;
  font-weight: 600;
  line-height: 1.5;
  word-break: break-all;
  transform: translate(-50%, -50%);
  transition: opacity 0.4s ease, transform 0.4s ease;
  box-shadow: 0 3px 10px rgba(0, 0, 0, 0.4);
  pointer-events: none;
  user-select: none;
}
.sb3d-container :deep(.sb3d-bubble.hide) {
  opacity: 0;
  transform: translate(-50%, -85%);
}
.sb3d-container :deep(.sb3d-bubble-icon) {
  flex-shrink: 0;
  font-size: 12px;
}
/* 气泡按类配色（想法金/对话绿/动作蓝/状态橙/事件红） */
.sb3d-container :deep(.sb3d-bubble.bubble-thought) { border-color: rgba(255, 212, 92, 0.65); color: #ffe9a8; }
.sb3d-container :deep(.sb3d-bubble.bubble-talk) { border-color: rgba(82, 214, 138, 0.65); color: #c9f5dd; }
.sb3d-container :deep(.sb3d-bubble.bubble-action) { border-color: rgba(143, 177, 255, 0.65); color: #cfe0ff; }
.sb3d-container :deep(.sb3d-bubble.bubble-state) { border-color: rgba(255, 184, 77, 0.65); color: #ffd9a8; }
.sb3d-container :deep(.sb3d-bubble.bubble-event) { border-color: rgba(255, 143, 107, 0.65); color: #ffc4b0; }
</style>
