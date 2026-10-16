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

  TopologyCanvas：关系工作台的画布层（AntV G6 v5）。

  为什么把它单列成组件（而不是继续写在 RelationTopology.vue 里）：
    画布层只关心「怎么把给定的图数据和位置画出来、以及把交互事件抛出去」——
    数据从哪来（后端/筛选/ego）、布局选哪个、选中后右侧显示什么，全部属于外层工作台。
    拆开后本组件可以独立验证（尺寸变化、主题切换、大图 LOD、导出 PNG），
    也让「位置」这件事只有一个来源：props.positions（deterministicLayout 算好的）。

  与旧实现的关键差异：
    ① 位置不再由 G6 力导向现场算（同一世界每次进来位置都变，用户找不到刚才那个人），
       而是接收确定性位置表；切换布局时用 translateElementTo 做位移动画（平滑过渡）；
    ② 圈子用 hull 外框（背景层，不遮节点）+ 圈名标签，让「哪几拨人」一眼可读；
    ③ 关系文字默认按需显示（大厂图谱的常规做法），避免几百条边同时打标；
    ④ LOD：节点多/缩得小时只保留重要与高连接节点的姓名；
    ⑤ 缩放/平移/拖动/右键/悬停全部走 G6 内建行为，事件统一 emit 给工作台。
-->
<template>
  <div ref="rootRef" class="topo-canvas" :class="[variant, { 'is-empty': !hasNodes }]">
    <div ref="stageRef" class="topo-canvas__stage"></div>

    <!--
      小地图：外置容器（v-show 保证插件创建时容器必然存在）。
      ⚠ 必须再套一层定位壳：G6 的小地图插件会给传入的容器写 inline style「position: relative」，
      直接把这个容器 absolute 定位会被 inline 覆盖成文档流元素，于是它被推到画布底部之外
      （实测：容器 y=836/848，只露出 12px，小地图整个看不见）。定位交给外层壳，内层随插件折腾。
    -->
    <div v-show="showMinimap && hasNodes" class="topo-canvas__minimap-slot">
      <div ref="minimapRef" class="topo-canvas__minimap"></div>
    </div>

    <!-- 画布内空提示（筛选后无结果时不要把用户丢在一片空白里） -->
    <div v-if="!hasNodes" class="topo-canvas__blank">
      <slot name="empty"></slot>
    </div>
  </div>
</template>

<script setup>
/**
 * 关系画布（G6 v5 封装）。
 *
 * <p>对外只暴露两件事：<b>渲染</b>（props 进）与<b>交互</b>（events 出）；生命周期与
 * 数据 diff 由内部管理，外层不必关心 G6 的实例细节。</p>
 *
 * <p>性能约定：节点集合不变时只做样式重绘（draw），位置变化走位移动画（translate），
 * 只有「节点集合/主题」变化才重建画布——保证 20~60 节点的村庄规模每次交互都是即时响应。</p>
 */
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { Graph } from '@antv/g6'
import { buildCanvasData, createNodeStyle, createEdgeStyle, nodeStateStyles, edgeStateStyles, shouldShowAllEdgeLabels, labelSetOf, tooltipOf } from '../lib/g6Adapter.js'
import { nodeSizeOf } from '../lib/relationPalette.js'

const props = defineProps({
  /** 可见节点（已过筛选；normalizeGraph 形状） */
  nodes: { type: Array, default: () => [] },
  /** 可见边（已双向合并；normalizeGraph 形状） */
  edges: { type: Array, default: () => [] },
  /** 确定性位置表：角色 ID → {x,y} */
  positions: { type: Object, default: () => ({}) },
  /** 圈子列表（含 type/label/typeLabel） */
  clusters: { type: Array, default: () => [] },
  /** 角色 ID → 圈子 ID */
  clusterOf: { type: Object, default: () => ({}) },
  /** 角色 ID → 度数 */
  degree: { type: Object, default: () => ({}) },
  /** 主题包（topologyTheme.buildTheme 结果） */
  theme: { type: Object, required: true },
  /** 选中节点 ID（''=未选） */
  selectedNodeId: { type: String, default: '' },
  /** 选中边 ID（''=未选） */
  selectedEdgeId: { type: String, default: '' },
  /** 详情栏悬停的边 ID（仅视觉高亮，不改选中） */
  hoverEdgeId: { type: String, default: '' },
  /** 搜索命中的节点 ID（高亮环） */
  highlightIds: { type: Array, default: () => [] },
  /** 是否画圈子外框 */
  showHulls: { type: Boolean, default: true },
  /** 关系文字档位（EDGE_LABEL_MODE） */
  showEdgeLabels: { type: String, default: 'smart' },
  /** 是否显示小地图 */
  showMinimap: { type: Boolean, default: true },
  /** 是否显示点状网格底纹 */
  showGrid: { type: Boolean, default: true },
  /** 是否允许自由拖动单个节点（默认关：拖动会破坏确定性布局） */
  draggable: { type: Boolean, default: false },
  /**
   * 浮层安全边距（px）：自适应视野时把图内容居中到「没被工具条/图例/状态条/小地图压住的区域」。
   * 这是图谱工作台的常规处理——否则 fitView 会把节点正好放到浮动面板底下。
   */
  insets: {
    type: Object,
    default: () => ({ top: 100, left: 24, right: 24, bottom: 44 })
  },
  /** 变体：page=整页工作台 / tab=角色页内嵌 */
  variant: { type: String, default: 'page' }
})

const emit = defineEmits([
  'node-click', 'edge-click', 'blank-click',
  'node-menu', 'edge-menu', 'blank-menu',
  'node-dblclick', 'viewport-change', 'ready'
])

const rootRef = ref(null)
const stageRef = ref(null)
const minimapRef = ref(null)

const hasNodes = computed(() => props.nodes.length > 0)

// ==================== G6 实例 ====================
let graph = null
let resizeObserver = null
/** 已渲染的节点 ID 指纹（判断「集合是否变化」以决定重建还是位移动画） */
let nodeSig = ''
/** 已应用的插件指纹（避免每次重绘都重建插件导致小地图闪烁） */
let pluginSig = ''

/** 当前是否处于「缩得很小」的视野（LOD 依据） */
const zoomedOut = ref(false)

/**
 * 常显标签的节点集合（LOD）。
 *
 * @returns {Set<string>} 节点 ID 集合
 */
function currentLabelSet() {
  return labelSetOf({ nodes: props.nodes, degree: props.degree, zoomOut: zoomedOut.value })
}

/**
 * 关系文字是否常显。
 *
 * @returns {boolean} 是否全显
 */
function showAllEdgeLabels() {
  return shouldShowAllEdgeLabels({ mode: props.showEdgeLabels, edgeCount: props.edges.length })
}

/** 节点样式函数（每次重建/选项变化都重新生成：内部闭包持有当前 LOD 判定） */
function nodeStyleFn() {
  const labelSet = currentLabelSet()
  return createNodeStyle({
    theme: props.theme,
    degree: props.degree,
    visibleLabel: (id) => labelSet.has(id)
  })
}

/** 边样式函数 */
function edgeStyleFn() {
  const all = showAllEdgeLabels()
  return createEdgeStyle({
    theme: props.theme,
    showLabel: () => all
  })
}

/** 节点选项（type + 样式回调 + 状态样式；setNode 是整体替换，必须每次给全） */
function nodeOptions() {
  return {
    type: 'circle',
    style: nodeStyleFn(),
    state: nodeStateStyles(props.theme)
  }
}

/** 边选项 */
function edgeOptions() {
  return {
    type: 'line',
    style: edgeStyleFn(),
    state: edgeStateStyles(props.theme)
  }
}

/**
 * 圈子外框插件配置（key=hull-<圈子 ID>）。
 *
 * @param {Array} hulls buildCanvasData 输出的圈子外框
 *
 * @returns {Array} 插件配置数组
 */
function hullPlugins(hulls) {
  return hulls.map(h => ({
    type: 'hull',
    key: `hull-${h.id}`,
    members: h.members,
    padding: 14,
    corner: 'smooth',
    concavity: Infinity,
    // ⚠ 关键：$layer=background 让外框画在节点之下（Hull 默认 appendChild 到 main 层会盖住节点）
    $layer: 'background',
    fill: props.theme.hullFill(h.color),
    stroke: props.theme.hullStroke(h.color),
    lineWidth: 1.8,
    lineDash: [7, 5],
    label: true,
    labelText: `${h.label}${h.typeLabel ? ' · ' + h.typeLabel : ''}（${h.size}）`,
    labelPlacement: 'top',
    // 中文标签不跟随外框曲线旋转（默认 closeToPath+autoRotate 会把"往来商帮"拧成斜的，很难读）
    labelCloseToPath: false,
    labelAutoRotate: false,
    labelFill: props.theme.hullLabelFill,
    labelFontSize: 11,
    labelFontWeight: 600,
    labelBackground: true,
    labelBackgroundFill: props.theme.hullLabelBg,
    labelBackgroundRadius: 6,
    labelPadding: [2, 6],
    labelOffsetY: -4,
    zIndex: 0
  }))
}

/** 稳定插件（小地图/网格/提示）——只有配置真变了才重建 */
function basePlugins() {
  const list = [{
    type: 'tooltip',
    key: 'tip',
    trigger: 'hover',
    getContent: (ev) => {
      const id = String(ev?.target?.id || '')
      const datum = id ? graph?.getElementData(id) : null
      if (!datum) return ''
      const kind = id.startsWith('e:') ? 'edge' : 'node'
      return tooltipOf({ datum, kind })
    }
  }]
  if (props.showGrid) {
    list.push({
      type: 'grid-line',
      key: 'grid',
      size: 30,
      stroke: props.theme.gridStroke,
      border: false,
      follow: true,
      zIndex: -2
    })
  }
  if (props.showMinimap && minimapRef.value) {
    list.push({
      type: 'minimap',
      key: 'minimap',
      container: minimapRef.value,
      size: [162, 108],
      padding: 5,
      shape: 'key',
      // 边不画进小地图（小地图的用途是「我在哪」，不是看关系细节）
      filter: (id, elementType) => elementType === 'node'
    })
  }
  return list
}

/** 全部插件（稳定插件 + 圈子外框） */
function allPlugins(hulls) {
  return [...basePlugins(), ...(props.showHulls ? hullPlugins(hulls) : [])]
}

/** 插件指纹（变了才 setPlugins，避免小地图无谓重建） */
function pluginSignature(hulls) {
  if (!props.showHulls) return `nohull|${props.showMinimap}|${props.showGrid}`
  return `${props.showMinimap}|${props.showGrid}|` + hulls.map(h => `${h.id}:${h.members.join(',')}:${h.label}`).join(';')
}

/**
 * 创建画布实例。
 *
 * @param {object} data buildCanvasData 输出
 *
 * @returns {Graph|null} 实例
 */
function createGraph(data) {
  const el = stageRef.value
  if (!el) return null
  const width = el.clientWidth || 800
  const height = el.clientHeight || 520
  if (width < 20 || height < 20) return null

  const g = new Graph({
    container: el,
    width,
    height,
    // 位置完全由我们提供（确定性布局），因此不配置 layout —— 见 lib/deterministicLayout.js
    data: { nodes: data.nodes, edges: data.edges },
    node: nodeOptions(),
    edge: edgeOptions(),
    behaviors: [
      'drag-canvas',
      'zoom-canvas',
      // 自由拖动按需开启：默认关闭，避免用户误拖破坏确定性布局
      ...(props.draggable ? [{ type: 'drag-element' }] : []),
      { type: 'hover-activate', degree: 1, state: 'active', inactiveState: 'inactive' }
    ],
    plugins: allPlugins(data.hulls),
    theme: props.theme.isDark ? 'dark' : 'light',
    // 背景交给 CSS（fullbleed 时与外壳同色，画布不再是一个"白框"）
    background: 'transparent',
    padding: 56,
    zoomRange: [0.12, 3.5],
    autoResize: false,
    animation: false
  })
  bindEvents(g)
  return g
}

/**
 * 绑定交互事件（统一转成工作台语义：选中谁 / 右键谁 / 点了空白）。
 *
 * @param {Graph} g 画布实例
 */
function bindEvents(g) {
  g.on('node:click', (ev) => emit('node-click', String(ev?.target?.id || ''), ev))
  g.on('edge:click', (ev) => emit('edge-click', String(ev?.target?.id || ''), ev))
  g.on('canvas:click', () => emit('blank-click'))
  g.on('node:dblclick', (ev) => emit('node-dblclick', String(ev?.target?.id || ''), ev))
  g.on('node:contextmenu', (ev) => {
    ev?.preventDefault?.()
    emit('node-menu', String(ev?.target?.id || ''), ev)
  })
  g.on('edge:contextmenu', (ev) => {
    ev?.preventDefault?.()
    emit('edge-menu', String(ev?.target?.id || ''), ev)
  })
  g.on('canvas:contextmenu', (ev) => {
    ev?.preventDefault?.()
    emit('blank-menu', ev)
  })
  g.on('viewport:change', () => {
    const z = g.getZoom()
    const out = z < 0.55
    if (out !== zoomedOut.value) {
      zoomedOut.value = out
      // 视野档位变化才重算 LOD 标签（避免每次平移都重排标签）
      void applyStyleOptions()
    }
    emit('viewport-change', { zoom: z, position: g.getPosition() })
  })
}

/**
 * 应用节点/边样式选项（不重跑数据、不重置视口）。
 *
 * <p>用于：LOD 档位变化、关系文字档位切换——只重绘元素，位置与视口保持不动。</p>
 *
 * @returns {Promise<void>} 完成
 */
async function applyStyleOptions() {
  if (!graph || graph.destroyed) return
  graph.setNode(nodeOptions())
  graph.setEdge(edgeOptions())
  await graph.draw()
  await applyStates()
}

/**
 * 应用选中/搜索高亮状态。
 *
 * @returns {Promise<void>} 完成
 */
async function applyStates() {
  if (!graph || graph.destroyed) return
  const selNodes = new Set()
  const selEdges = new Set()
  const center = props.selectedNodeId
  if (center) {
    selNodes.add(center)
    for (const e of props.edges) {
      if (e.source === center || e.target === center) {
        selEdges.add(e.id)
        selNodes.add(e.source)
        selNodes.add(e.target)
      }
    }
  }
  const highlight = new Set(props.highlightIds || [])
  const hasFocus = !!center
  const map = {}
  for (const n of props.nodes) {
    if (highlight.has(n.id)) map[n.id] = ['highlight']
    else if (!hasFocus) map[n.id] = []
    else map[n.id] = selNodes.has(n.id) ? ['selected'] : ['inactive']
  }
  for (const e of props.edges) {
    if (hasFocus) {
      if (e.id === props.selectedEdgeId) map[e.id] = ['selected']
      else if (e.id === props.hoverEdgeId) map[e.id] = ['active']
      else if (selEdges.has(e.id)) map[e.id] = ['active']
      else map[e.id] = ['inactive']
    } else if (e.id === props.selectedEdgeId) {
      map[e.id] = ['selected']
    } else if (e.id === props.hoverEdgeId) {
      map[e.id] = ['active']
    } else if (highlight.size && (highlight.has(e.source) || highlight.has(e.target))) {
      map[e.id] = ['highlight']
    } else {
      map[e.id] = []
    }
  }
  await graph.setElementState(map, false)
}

/**
 * 完整重建数据（节点集合或主题变化时）。
 *
 * @param {object} data buildCanvasData 输出
 *
 * @param {object} [opts] 选项
 *
 * @param {boolean} [opts.fit] 渲染后是否自适应视野
 *
 * @returns {Promise<void>} 完成
 */
async function rebuildData(data, opts = {}) {
  if (!graph || graph.destroyed) return
  graph.setData({ nodes: data.nodes, edges: data.edges })
  await graph.render()
  nodeSig = signatureOf(data.nodes)
  if (opts.fit) await fitView(true)
  await applyStates()
}

/** 节点集合指纹（顺序无关） */
function signatureOf(list) {
  return list.map(n => String(n.id)).sort().join('|')
}

/**
 * 同步数据（自动选择「重建」还是「位移动画」）。
 *
 * @param {object} [opts] 选项
 *
 * @param {boolean} [opts.animate] 位置变化时是否走位移动画
 *
 * @param {boolean} [opts.fit] 是否自适应视野
 *
 * @returns {Promise<void>} 完成
 */
async function sync(opts = {}) {
  if (!hasNodes.value) {
    destroyGraph()
    return
  }
  if (!stageRef.value) return
  const data = buildCanvasData({
    nodes: props.nodes,
    edges: props.edges,
    positions: props.positions,
    clusterOf: props.clusterOf,
    clusters: props.clusters,
    degree: props.degree,
    theme: props.theme
  })

    if (!graph || graph.destroyed) {
    graph = createGraph(data)
    if (!graph) return
    pluginSig = pluginSignature(data.hulls)
    await graph.render()
    nodeSig = signatureOf(data.nodes)
    await fitView(false)
    await applyStates()
    // 开发期调试钩子（生产构建下 import.meta.env.DEV 为 false，整段被摇树掉）：
    // 现场验收脚本需要读取真实缩放/元素包围盒来判断"图有没有真的铺满画布"
    if (import.meta.env.DEV) window.__holzynTopo = graph
    emit('ready')
    return
  }

  // 尺寸变化（外壳折叠/展开、窗口缩放）
  syncSize()

  const sig = signatureOf(data.nodes)
  const sigChanged = sig !== nodeSig
  const pluginChanged = pluginSignature(data.hulls) !== pluginSig

  // ⚠ 插件必须先于任何 render 同步：圈子外框的成员列表若还停留在上一轮的可见集合上，
  // 它会在 AFTER_RENDER 里对已被筛掉的节点调用 getNodeData → graphlib 抛
  // "Node not found for id"。先换插件再渲染，外框成员与画面永远一致。
  if (pluginChanged) {
    graph.setPlugins(allPlugins(data.hulls))
    pluginSig = pluginSignature(data.hulls)
  }

  if (sigChanged) {
    // 节点集合变了（筛选/ego/圈子收窄）：重画后按安全区整体适配——
    // 筛完只剩一个圈子却还停在原来那张全图的视野里，是图谱产品最常见的"点了没反应"观感
    await rebuildData(data, { fit: true })
  } else {
    // 节点集合没变：只更新边与样式（保留用户当前视野，不打断观察）
    graph.setData({ nodes: data.nodes, edges: data.edges })
    graph.setEdge(edgeOptions())
    graph.setNode(nodeOptions())
    if (opts.animate) {
      // ⚠ G6 v5 的 Point 类型是 [x, y] 数组（不是 {x,y} 对象）：传对象会在
      // translateNodeTo 里解构失败并抛 "position is not iterable"，动画与后续同步一起中断。
      const moved = {}
      for (const n of data.nodes) {
        const p = props.positions[n.id]
        if (p) moved[n.id] = [p.x, p.y]
      }
      await graph.translateElementTo(moved, true)
    }
    await graph.draw()
    // 新装的圈子外框要等一次完整渲染才会画出来（AFTER_RENDER）
    if (pluginChanged) await graph.render()
    // 位置变了（换布局）：节点滑到位后再把视野适配到安全区，形成"整体滑过去"的过渡
    if (opts.animate) await fitView(true)
  }

  if (opts.fit) await fitView(true)
  await applyStates()
}

/** 画布尺寸与容器同步 */
function syncSize() {
  if (!graph || graph.destroyed || !stageRef.value) return
  const w = stageRef.value.clientWidth
  const h = stageRef.value.clientHeight
  if (w < 20 || h < 20) return
  graph.setSize(w, h)
}

// ==================== 对外能力（工作台通过 ref 调用） ====================

/**
 * 浮层之外的安全区（舞台内像素坐标）。
 *
 * <p>左侧额外做了一次收口：图例展开时是一条宽面板，但内嵌（tab）变体的画布只有 400 多像素宽，
 * 直接让开整块面板会把图挤成一个点。所以规则是「让位，但必须给内容留下至少 320px」——
 * 一开始写成「最多让出画布宽度的 34%」，结果 828px 的画布只让 281px，图例右边 85px 里的人
 * 照样压在面板下面（验收脚本的「浮层不压住任何人」断言抓到就是这条）。</p>
 *
 * @returns {{x0:number,y0:number,x1:number,y1:number}} 安全区矩形
 */
function safeArea() {
  const w = stageRef.value ? stageRef.value.clientWidth : 0
  const h = stageRef.value ? stageRef.value.clientHeight : 0
  const ins = props.insets || {}
  const left = Math.min(ins.left || 0, Math.max(0, w - 320))
  return { x0: left, y0: ins.top || 0, x1: w - (ins.right || 0), y1: h - (ins.bottom || 0) }
}

/**
 * 一组节点的外接尺寸（世界坐标）：节点圆 + 下方姓名标签 + 圈子外框留白的估算。
 *
 * <p>为什么不直接用 G6 的 getElementRenderBounds：那要等元素渲染完成才准，
 * 而"该缩到多大"必须在缩放前就知道。这里用与渲染同源的尺寸口径（nodeSizeOf）
 * 加标签宽度估算，代价是略有保守（名字长时多留一点），收益是自适应稳定、不抖动。</p>
 *
 * @param {Array} list 节点列表
 *
 * @returns {{minX:number,minY:number,maxX:number,maxY:number}|null} 包围盒
 */
function extentOf(list) {
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  for (const n of list) {
    const p = props.positions[n.id]
    if (!p) continue
    const size = nodeSizeOf(n, props.degree[n.id] || 0)
    const halfW = Math.max(size / 2, Math.min(76, String(n.name || '').length * 6.2)) + 20
    const halfH = size / 2 + 15 + 20
    minX = Math.min(minX, p.x - halfW)
    maxX = Math.max(maxX, p.x + halfW)
    minY = Math.min(minY, p.y - halfH)
    maxY = Math.max(maxY, p.y + halfH)
  }
  if (!Number.isFinite(minX)) return null
  return { minX, minY, maxX, maxY }
}

/**
 * 当前可见内容的包围盒。
 *
 * @returns {{minX:number,minY:number,maxX:number,maxY:number}|null} 包围盒
 */
function contentExtent() {
  return extentOf(props.nodes)
}

/**
 * 「某人 + 一跳邻居」的包围盒（聚焦用：只看这个人，会把他身边的处境也一起框进来）。
 *
 * @param {string} id 节点 ID
 *
 * @returns {{minX:number,minY:number,maxX:number,maxY:number}|null} 包围盒
 */
function egoExtent(id) {
  const ego = new Set([id])
  for (const e of props.edges) {
    if (e.source === id) ego.add(e.target)
    else if (e.target === id) ego.add(e.source)
  }
  return extentOf(props.nodes.filter(n => ego.has(n.id))) || extentOf(props.nodes.filter(n => n.id === id))
}

/**
 * 世界坐标 → 舞台内像素坐标。
 *
 * <p>⚠ G6 v5 的相机是「位置 + 缩放」两个量，且 <b>getPosition 返回的是"世界单位"位移</b>，
 * 真正的屏幕映射是 {@code px = (world + position) × zoom}（不是 {@code world × zoom + position}）。
 * 这一点在源码里看不出来，只能靠 viewportByCanvas([0,0]) 反推：
 * position=[514,489]、zoom=0.6697 时 viewportByCanvas([0,0])=[344.19,327.41]=position×zoom。
 * 按错的口径算「重新居中」会偏出上百像素，表现为"切到力导后整张图跑到画布外"（实测就是这个 bug）。
 * 注意：{@code graph.translateBy(dx, dy)} 的入参是<b>屏幕像素</b>（它会自己除以 zoom 写回 position），
 * 所以「算位移」与「下指令」两处单位不同，不要混用。</p>
 *
 * @param {{x:number,y:number}} world 世界坐标
 *
 * @param {number} zoom 缩放
 *
 * @param {Array<number>} pos getPosition() 结果
 *
 * @returns {Array<number>} [px, py] 舞台内像素坐标
 */
function worldToStage(world, zoom, pos) {
  return [(world.x + pos[0]) * zoom, (world.y + pos[1]) * zoom]
}

/**
 * 把某个包围盒适配进「浮层之外的安全区」（缩放 + 居中一次算完）。
 *
 * @param {object} bb 包围盒（世界坐标）
 *
 * @param {object} [opts] 选项
 *
 * @param {boolean} [opts.animate] 是否动画
 *
 * @param {number} [opts.maxZoom] 缩放上限（聚焦时用：别把全图上下文缩没）
 *
 * @returns {Promise<void>} 完成
 */
async function fitExtent(bb, opts = {}) {
  if (!graph || graph.destroyed || !stageRef.value || !bb) return
  const area = safeArea()
  const availW = Math.max(120, area.x1 - area.x0)
  const availH = Math.max(120, area.y1 - area.y0)
  const range = graph.getZoomRange() || [0.12, 3.5]
  const spanX = Math.max(1, bb.maxX - bb.minX)
  const spanY = Math.max(1, bb.maxY - bb.minY)
  let target = Math.min(availW / spanX, availH / spanY)
  if (opts.maxZoom) target = Math.min(target, opts.maxZoom)
  const scale = Math.min(range[1], Math.max(range[0], target))
  const timing = opts.animate ? { duration: 260 } : false
  await graph.zoomTo(scale, timing)
  await recenterExtent(bb, opts.animate)
}

/**
 * 自适应视野：把内容缩放到「浮层之外的安全区」里并居中。
 *
 * <p>只调用 G6 的 fitView 会有两个问题：① 它按整块画布留白，图会正好居中在画布正中，
 * 而四周恰好是工具条/图例/小地图/状态条（节点藏在面板下面）；② 它不认浮层，
 * 浮层一变就得整体重算。这里自己按 insets 算目标缩放与目标中心，浮层开合只影响边长。</p>
 *
 * @param {boolean} [animate] 是否带动画
 *
 * @returns {Promise<void>} 完成
 */
async function fitView(animate = true) {
  await fitExtent(contentExtent(), { animate })
}

/**
 * 把某个包围盒的中心移到安全区中心（不改缩放）。
 *
 * <p>用途：① 图例/小地图展开收起后画布可用区域变了，但用户当前的缩放不该被打断——
 * 大厂图谱里「面板一动图就跳回全图」是很烦人的一类交互；② 聚焦某人时先算包围盒再居中。</p>
 *
 * @param {object} bb 包围盒（世界坐标）
 *
 * @param {boolean} [animate] 是否带动画
 *
 * @returns {Promise<void>} 完成
 */
async function recenterExtent(bb, animate = true) {
  if (!graph || graph.destroyed || !stageRef.value || !bb) return
  const zoom = graph.getZoom()
  const pos = graph.getPosition()
  const center = { x: (bb.minX + bb.maxX) / 2, y: (bb.minY + bb.maxY) / 2 }
  const [contentX, contentY] = worldToStage(center, zoom, pos)
  const area = safeArea()
  const safeX = (area.x0 + area.x1) / 2
  const safeY = (area.y0 + area.y1) / 2
  // translateBy 的入参是「屏幕像素」（实测 translateBy([100,0]) 让 getPosition 增了 100/zoom，
  // 而世界原点在视口里正好右移 100px），所以这里直接用屏幕位移，不要再除以 zoom。
  const dx = safeX - contentX
  const dy = safeY - contentY
  if (Math.abs(dx) > 1.5 || Math.abs(dy) > 1.5) {
    await graph.translateBy([dx, dy], animate ? { duration: 240 } : false)
  }
}

/**
 * 只按浮层安全边距重新居中（不改缩放）。
 *
 * @param {boolean} [animate] 是否带动画
 *
 * @returns {Promise<void>} 完成
 */
async function recenterToSafeArea(animate = true) {
  await recenterExtent(contentExtent(), animate)
}

/** 容器尺寸变化时：只有内容装不下才重新适配（否则会打断用户当前的缩放/平移） */
async function fitViewIfOverflow() {
  if (!graph || graph.destroyed || !stageRef.value) return
  const bb = contentExtent()
  if (!bb) return
  const z = graph.getZoom()
  const area = safeArea()
  const availW = area.x1 - area.x0
  const availH = area.y1 - area.y0
  if ((bb.maxX - bb.minX) * z > availW || (bb.maxY - bb.minY) * z > availH) {
    await fitView(false)
  }
}

/**
 * 缩放到指定倍数。
 *
 * @param {number} zoom 倍数
 *
 * @returns {Promise<void>} 完成
 */
async function zoomTo(zoom) {
  if (!graph || graph.destroyed) return
  await graph.zoomTo(zoom, { duration: 180 })
}

/**
 * 在当前倍数上按比例缩放。
 *
 * @param {number} ratio 比例
 *
 * @returns {Promise<void>} 完成
 */
async function zoomBy(ratio) {
  if (!graph || graph.destroyed) return
  await graph.zoomBy(ratio, { duration: 160 })
}

/** 当前缩放倍数 */
function getZoom() {
  return graph && !graph.destroyed ? graph.getZoom() : 1
}

/**
 * 聚焦某个角色：把他和「一跳邻居」一起框进安全区，并平滑过渡。
 *
 * <p>为什么不用 G6 的 focusElement：它会把缩放直接拉到 1（或指定 zoom），
 * 对一个 0.67 倍的全景来说等于「一按定位，村里其他人全出画」——用户刚建立的空间记忆被抹掉。
 * 这里改成「聚焦 ego 邻域 + 缩放置顶 1 倍」：看得清这个人，也看得见他身边的人。</p>
 *
 * @param {string} id 节点 ID
 *
 * @param {object} [opts] 选项
 *
 * @param {number} [opts.maxZoom] 缩放上限（默认 1=原始大小；超过 1 会让村里其他人整片出画）
 *
 * @returns {Promise<void>} 完成
 */
async function focusNode(id, opts = {}) {
  if (!graph || graph.destroyed || !id) return
  await fitExtent(egoExtent(id), { animate: true, maxZoom: opts.maxZoom || 1 })
}

/**
 * 闪烁若干条边（实时关系事件到达时的视觉提示）。
 *
 * @param {Array<string>} edgeIds 边 ID
 *
 * @param {number} [duration] 闪烁时长（ms）
 *
 * @returns {Promise<void>} 完成
 */
async function flashEdges(edgeIds, duration = 1800) {
  if (!graph || graph.destroyed) return
  const ids = (edgeIds || []).filter(Boolean)
  if (!ids.length) return
  const map = {}
  ids.forEach(id => { map[id] = ['pulse'] })
  await graph.setElementState(map, false)
  window.setTimeout(() => { void applyStates() }, duration)
}

/**
 * 导出当前画布为 PNG DataURL。
 *
 * @returns {Promise<string>} DataURL（失败返回空串）
 */
async function exportPNG() {
  if (!graph || graph.destroyed) return ''
  try {
    return await graph.toDataURL({ mode: 'overall', type: 'image/png' })
  } catch (e) {
    return ''
  }
}

/** 销毁画布实例 */
function destroyGraph() {
  if (graph) {
    try { graph.destroy() } catch (e) { /* 忽略销毁异常 */ }
    graph = null
  }
  nodeSig = ''
  pluginSig = ''
}

// ==================== 生命周期与响应式桥接 ====================

/** 主题变化：状态样式与插件配色都烘焙在实例里，直接重建最稳 */
watch(() => props.theme, () => {
  destroyGraph()
  void nextTick(() => sync({ fit: true }))
})

/** 数据/位置/圈子：决定重建还是位移动画 */
watch(
  [() => props.nodes, () => props.edges, () => props.clusters, () => props.positions],
  ([, , , newPos], [, , , oldPos]) => {
    const animated = !!newPos && !!oldPos && newPos !== oldPos && props.nodes.length > 0
    void sync({ animate: animated })
  }
)

/** 样式档位（关系文字/LOD/圈子外框/小地图/网格） */
watch(
  [() => props.showEdgeLabels, () => props.showHulls, () => props.showMinimap, () => props.showGrid],
  () => { void sync({ animate: false }) }
)

/** 交互状态：只重算状态，不重绘数据（保证点选零延迟） */
watch([() => props.selectedNodeId, () => props.selectedEdgeId, () => props.hoverEdgeId, () => props.highlightIds], () => {
  void applyStates()
})

/** 拖动开关：行为集合变化只能重建 */
watch(() => props.draggable, () => {
  destroyGraph()
  void nextTick(() => sync({ fit: false }))
})

/** 浮层安全边距变化（图例/小地图开合）：只重新居中，不打断用户当前缩放 */
watch(() => props.insets, () => { void recenterToSafeArea(true) })

onMounted(async () => {
  await nextTick()
  await sync({ fit: true })
  if (stageRef.value) {
    resizeObserver = new ResizeObserver(() => {
      syncSize()
      void fitViewIfOverflow()
    })
    resizeObserver.observe(stageRef.value)
  }
})

onBeforeUnmount(() => {
  if (resizeObserver) { resizeObserver.disconnect(); resizeObserver = null }
  destroyGraph()
})

defineExpose({
  fitView, zoomTo, zoomBy, getZoom, focusNode, flashEdges, exportPNG, applyStates, recenterToSafeArea
})</script>

<style scoped>
.topo-canvas {
  position: relative;
  width: 100%;
  height: 100%;
  min-height: 0;
  background: var(--bg-layer-1);
  overflow: hidden;
}
/* 整页工作台：画布铺满外壳，圆角由外壳决定（不再是一个悬浮的白框） */
.topo-canvas.page { border-radius: 0; }
/* 角色页内嵌：保持卡片感（与页内其它卡片一致） */
.topo-canvas.tab {
  min-height: 380px;
  max-height: 720px;
  border: 1px solid var(--border-light);
  border-radius: var(--radius-md);
}
.topo-canvas__stage { width: 100%; height: 100%; }
.topo-canvas.tab .topo-canvas__stage { height: var(--canvas-h-embedded, min(52vh, 620px)); min-height: 380px; }

/* 小地图：右下角浮动（避开左侧图例与底部状态条）。
   外层壳负责定位与外观，内层只负责装插件画布（插件会覆盖内层的 position）。 */
.topo-canvas__minimap-slot {
  position: absolute;
  right: 12px;
  bottom: 12px;
  width: 164px;
  height: 110px;
  box-sizing: border-box;
  border: 1px solid var(--border-l2);
  border-radius: 10px;
  background: color-mix(in srgb, var(--bg-layer-1) 88%, transparent);
  backdrop-filter: blur(6px);
  box-shadow: var(--shadow-lv2);
  overflow: hidden;
  z-index: 6;
}
.topo-canvas__minimap {
  width: 162px;
  height: 108px;
}

.topo-canvas__blank {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 4;
  pointer-events: none;
}
</style>

<style>
/* 非 scoped：G6 Tooltip 的 HTML 注入到 body/画布容器，必须全局选择器命中 */
.topo-tip {
  font-size: 12px;
  line-height: 1.65;
  color: var(--text-primary);
  max-width: 280px;
  word-break: break-word;
}
.topo-tip .tw-title { font-weight: 600; margin-bottom: 3px; }
.topo-tip .tw-row { display: flex; gap: 6px; align-items: baseline; flex-wrap: wrap; }
.topo-tip .tw-k { color: var(--text-tertiary); flex-shrink: 0; }
.topo-tip .tw-dot { width: 8px; height: 8px; border-radius: 50%; display: inline-block; align-self: center; }
.topo-tip .tw-note { color: var(--text-secondary); font-size: 11px; margin-top: 3px; }
.topo-tip .tw-tag {
  margin-left: 6px;
  font-size: 10px;
  color: #fff;
  background: var(--accent);
  border-radius: 3px;
  padding: 0 4px;
  font-weight: 500;
}
</style>
