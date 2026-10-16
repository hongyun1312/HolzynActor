/*
 * Copyright (c) 2026 HONGYUN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import { nodeSizeOf } from './relationPalette.js'

/**
 * 确定性布局引擎 —— UI V3.0 关系工作台的「位置」唯一来源。
 *
 * <p><b>为什么自己算布局</b>：G6/力导向布局默认带随机初值，同一个世界每次进页面节点都换位置，
 * 用户下次找不到刚才那个人——这是图谱产品最伤体验的一类细节（也是「看起来像坏了」的常见来源）。
 * 本模块用<b>固定种子 + 固定迭代次数</b>自己算位置，同一份数据（同项目、同布局、同节点集合）
 * 必然得到同一张图；再配合调用方的 localStorage 缓存，跨会话也稳定。</p>
 *
 * <p>五种布局各司其职（对应五类观察意图）：</p>
 * <ul>
 *   <li><b>community</b>（默认）：圈子分区总览——各圈领地按成员数铺成整齐网格，圈内用黄金角
 *       螺旋排列 + 重叠松弛；一眼看出「分成了哪几拨人、哪拨人多」；</li>
 *   <li><b>force</b>：种子化力导向（社区引力加权），看整体亲疏结构；</li>
 *   <li><b>concentric</b>：按重要度同心圆，看「这个世界的权力/关注度分布」；</li>
 *   <li><b>radial</b>：以度数最高者为中心按跳数分层，看「以谁为中心辐射出去」；</li>
 *   <li><b>lanes</b>：圈子泳道（横排），关系密集时比圆环更易读；</li>
 * </ul>
 *
 * <p>注意：<b>只有 community / force / lanes 画圈子外框有意义</b>——concentric 与 radial
 * 是按「重要度 / 跳数」重排的，同一个圈子的人会被打散到不同环上，外框只会变成几个穿过整张图的
 * 大椭圆（实测就是这样，圈名还会叠在一起）。哪个布局支持外框见 {@link HULL_LAYOUTS}。</p>
 *
 * <p>纯函数（无 DOM、无 G6、无随机源），可在 Node 里直接断言「同输入 → 逐像素同输出」。</p>
 */

/** 布局 ID → 中文名（工具栏切换与缓存键展示用） */
export const LAYOUT_LABEL = {
  community: '圈子分区',
  force: '关系力导',
  concentric: '重要度同心',
  radial: '以人为心',
  lanes: '圈子泳道'
}

/** 全部布局 ID（顺序即工具栏顺序） */
export const LAYOUT_IDS = ['community', 'force', 'concentric', 'radial', 'lanes']

/**
 * 会画圈子外框的布局。
 *
 * <p>concentric / radial 把同一个圈子的人打散到不同环上，外框会变成穿过整张图的巨型椭圆，
 * 圈名还会互相叠字——所以这两个布局下不画外框（用户在外观开关里的偏好仍然保留，切回分区布局即恢复）。</p>
 */
export const HULL_LAYOUTS = ['community', 'force', 'lanes']

/** 黄金角（弧度）：螺旋排布用，保证任意点数都不重叠、且顺序确定 */
const GOLDEN_ANGLE = Math.PI * (3 - Math.sqrt(5))

/**
 * 字符串 → 32 位无符号哈希（FNV-1a 变体；种子与确定性抖动都用它）。
 *
 * @param {string} str 输入
 *
 * @returns {number} 32 位哈希
 */
export function hashString(str) {
  let h = 2166136261
  const s = String(str ?? '')
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return h >>> 0
}

/**
 * mulberry32 伪随机数发生器（同种子 → 同序列；用于力导向的确定性抖动）。
 *
 * @param {number} seed 种子
 *
 * @returns {Function} () => [0,1)
 */
export function seededRandom(seed) {
  let a = seed >>> 0
  return function next() {
    a = (a + 0x6D2B79F5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/**
 * 计算布局。
 *
 * @param {object} input 输入
 *
 * @param {Array} input.nodes 归一化节点 [{ id, name, kind, importance, isProtagonist, meta }]
 *
 * @param {Array} input.edges 归一化边 [{ id, source, target, affinity, trust, weight }]
 *
 * @param {Array} input.clusters 圈子（buildClusters 结果，按 size 降序）
 *
 * @param {string} [input.layout] 布局 ID（community/force/concentric/radial/lanes）
 *
 * @param {object} [input.options] 可选参数
 *
 * @param {string|null} [input.options.focusId] 焦点角色（radial 中心；缺省取度数最高者）
 *
 * @param {number} [input.options.nodeGap] 节点最小间距（默认 34）
 *
 * @returns {{ positions: Object, clusters: Array, size: {width:number,height:number} }}
 *          位置表（角色 ID → {x,y}）、带几何信息的圈子（含圆心/半径/包围盒）、整体尺寸
 */
export function computeLayout({ nodes = [], edges = [], clusters = [], layout = 'community', options = {} } = {}) {
  // 30（原先 26）：图是自适应铺满画布的，留白越大 → 内容跨度越大 → 自适应缩放越小 →
  // 屏幕上的圆和姓名一起变小、彼此更好分辨。这是"节点看起来偏大"的真正解，
  // 单纯改世界直径会被自适应缩放吃掉（见 relationPalette.nodeSizeOf 的说明）。
  // 但留白不能一味加大：缩放降下去，姓名（世界 11px）也会跟着变小，泳道/同心这类
  // 环排布局的跨度本来就大，实测 34 时它们的缩放掉到 0.75 上下、姓名只有 8px。
  // 30 是"圆明显小一圈"与"各布局姓名仍可读"之间取到的实测平衡点。
  const gap = options.nodeGap ?? 30
  const ids = nodes.map(n => n.id)
  const seed = hashString(ids.slice().sort().join('|') + '#' + layout)
  const rand = seededRandom(seed)
  const degree = computeDegree(nodes, edges)
  const sizeOf = (n) => nodeRadius(n, degree[n.id] || 0)

  let positions
  switch (layout) {
    case 'force':
      positions = forceLayout(nodes, edges, clusters, { rand, gap, sizeOf })
      break
    case 'concentric':
      positions = concentricLayout(nodes, { gap, sizeOf })
      break
    case 'radial':
      positions = radialLayout(nodes, edges, { focusId: options.focusId, degree, gap, sizeOf })
      break
    case 'lanes':
      positions = lanesLayout(nodes, clusters, { gap, sizeOf })
      break
    case 'community':
    default:
      positions = communityLayout(nodes, clusters, { gap, sizeOf })
      break
  }

  const settled = relaxOverlaps(nodes, positions, { gap, sizeOf })
  const size = boundsOf(nodes, settled, sizeOf)
  const shapedClusters = shapeClusters(clusters, settled, sizeOf, gap)
  return { positions: settled, clusters: shapedClusters, size }
}

// ==================== 布局算法 ====================

/**
 * 圈子分区布局（默认）：圈子按成员数排在确定性圆环上，圈内黄金角螺旋排列。
 *
 * <p>「先看分成哪几拨人」要求的不是力学最优，而是<b>可读的块状结构</b>：每个圈子占据一块
 * 互不重叠的圆形领地，块的位置按圈子大小顺时针排列，同级按名称排序（确定性）。</p>
 *
 * @param {Array} nodes 节点
 *
 * @param {Array} clusters 圈子
 *
 * @param {object} ctx 上下文（gap/sizeOf）
 *
 * @returns {Object} 位置表
 */
function communityLayout(nodes, clusters, ctx) {
  const { gap, sizeOf } = ctx
  const positions = {}
  const list = clusters.length ? clusters : [{ id: 'all', members: nodes.map(n => n.id) }]
  const nodeById = new Map(nodes.map(n => [n.id, n]))
  // ① 圈内相对位置：黄金角螺旋（确定性），间距按「节点直径 + 留白」给——
  //    姓名标签挂在节点下方，间距必须按"标签也算占位"来给，否则圈内 4~5 个人时标签会互相压字。
  const discs = list.map(cluster => {
    const members = cluster.members
      .map(id => nodeById.get(id))
      .filter(Boolean)
      // 圈内顺序确定：主角/重要度优先，其次 ID（保证每次进入同一位置）
      .sort((a, b) => (b.isProtagonist ? 1 : 0) - (a.isProtagonist ? 1 : 0)
        || (b.importance || 0) - (a.importance || 0)
        || String(a.id).localeCompare(String(b.id)))
    const offsets = []
    let radius = 0
    members.forEach((n, k) => {
      const r = k === 0 ? 0 : Math.sqrt(k) * (sizeOf(n) + gap * 0.9)
      const a = k * GOLDEN_ANGLE
      offsets.push({ id: n.id, x: Math.round(Math.cos(a) * r * 100) / 100, y: Math.round(Math.sin(a) * r * 100) / 100 })
      radius = Math.max(radius, r + sizeOf(n) / 2)
    })
    // 领地半径 = 圈内实际外扩 + 外框留白（外框要能罩住所有人，否则两个圈会咬在一起）
    return { id: cluster.id, offsets, radius: radius + 18 }
  })
  // ② 圈心：网格铺开（确定性）——比圆环紧凑得多。圆环在 5 个圈时会把领地推到半径 300+，
  //    17 人村庄的缩放被压到 0.63（人名小到看不清）；网格是 3×2，同样内容缩放能到 0.85 左右。
  const centers = gridCenters(discs.map(d => d.radius), gap * 1.6)
  discs.forEach((disc, i) => {
    const center = centers[i]
    disc.offsets.forEach(o => {
      positions[o.id] = { x: center.x + o.x, y: center.y + o.y }
    })
  })
  return positions
}

/**
 * 种子化力导向布局：社区内强引力 + 全局斥力 + 向心力，固定迭代次数。
 *
 * @param {Array} nodes 节点
 *
 * @param {Array} edges 边
 *
 * @param {Array} clusters 圈子（同圈强引力）
 *
 * @param {object} ctx 上下文
 *
 * @returns {Object} 位置表
 */
function forceLayout(nodes, edges, clusters, ctx) {
  const { rand, gap, sizeOf } = ctx
  const n = nodes.length
  if (n === 0) return {}
  // 目标边长（世界 px）：约 2.4 个节点直径，让「有关系的两个人」看得出是一段边而不是一根长线
  const linkDist = 108
  // 画布尺度只用来定初值与向心力，真正决定结果的尺度由下面的收敛 + 收口保证
  const width = Math.max(520, Math.sqrt(n) * linkDist * 1.35)
  const height = width * 0.72
  const pos = {}
  // 初值：确定性网格 + 种子抖动（同数据同初值）
  nodes.forEach((node, i) => {
    const cols = Math.ceil(Math.sqrt(n))
    pos[node.id] = {
      x: ((i % cols) + 0.5) * (width / cols) + (rand() - 0.5) * 8,
      y: (Math.floor(i / cols) + 0.5) * (height / Math.ceil(n / cols)) + (rand() - 0.5) * 8
    }
  })
  const clusterOf = {}
  clusters.forEach(c => c.members.forEach(id => { clusterOf[id] = c.id }))
  const iterations = 320
  // ⚠ k 不再取「画布面积/节点数」的平方根（那是 FR 算法配 0.045 引力系数的写法，
  // 在 17 人的村庄里会算出 250px 的理想边长，整张图散成一片：实测缩放被压到 0.17）。
  // 这里直接按「期望的理想边长」反解 k：平衡距离 d* = k·(斥力系数/引力系数/权重)^(1/3)。
  const REPULSION = 0.55
  const ATTRACTION = 0.045
  const MEAN_WEIGHT = 1.3
  const k = linkDist / Math.cbrt(REPULSION / (ATTRACTION * MEAN_WEIGHT))
  for (let it = 0; it < iterations; it++) {
    const disp = {}
    nodes.forEach(a => { disp[a.id] = { x: 0, y: 0 } })
    // 斥力
    for (let i = 0; i < n; i++) {
      const a = nodes[i]
      for (let j = i + 1; j < n; j++) {
        const b = nodes[j]
        let dx = pos[a.id].x - pos[b.id].x
        let dy = pos[a.id].y - pos[b.id].y
        let d2 = dx * dx + dy * dy
        if (d2 < 0.01) { dx = (rand() - 0.5) * 0.5; dy = (rand() - 0.5) * 0.5; d2 = 0.25 }
        const d = Math.sqrt(d2)
        const rep = ((k * k) / d) * REPULSION
        const ux = dx / d
        const uy = dy / d
        disp[a.id].x += ux * rep
        disp[a.id].y += uy * rep
        disp[b.id].x -= ux * rep
        disp[b.id].y -= uy * rep
      }
    }
    // 边引力（权重越高越紧）
    for (const e of edges) {
      const a = pos[e.source]
      const b = pos[e.target]
      if (!a || !b) continue
      const dx = a.x - b.x
      const dy = a.y - b.y
      const d = Math.max(0.01, Math.sqrt(dx * dx + dy * dy))
      const w = 0.55 + Math.min(1, (e.weight ?? 1) / 3) * 0.9
      const att = ((d * d) / k) * ATTRACTION * w
      const ux = dx / d
      const uy = dy / d
      disp[e.source].x -= ux * att
      disp[e.source].y -= uy * att
      disp[e.target].x += ux * att
      disp[e.target].y += uy * att
    }
    // 同圈引力（把一拨人拉在一起，是「圈子」在力导里的体现）
    const byCluster = new Map()
    nodes.forEach(nd => {
      const cid = clusterOf[nd.id] || 'x'
      if (!byCluster.has(cid)) byCluster.set(cid, [])
      byCluster.get(cid).push(nd.id)
    })
    for (const members of byCluster.values()) {
      if (members.length < 2) continue
      let cx = 0
      let cy = 0
      members.forEach(id => { cx += pos[id].x; cy += pos[id].y })
      cx /= members.length
      cy /= members.length
      members.forEach(id => {
        disp[id].x += (cx - pos[id].x) * 0.05
        disp[id].y += (cy - pos[id].y) * 0.05
      })
    }
    // 向心力 + 位移限幅（温度退火，保证收敛且确定）
    const temp = (1 - it / iterations) * (linkDist * 0.6)
    for (const nd of nodes) {
      disp[nd.id].x += (width / 2 - pos[nd.id].x) * 0.02
      disp[nd.id].y += (height / 2 - pos[nd.id].y) * 0.02
      const d = Math.max(0.01, Math.hypot(disp[nd.id].x, disp[nd.id].y))
      const lim = Math.min(d, temp)
      pos[nd.id].x += (disp[nd.id].x / d) * lim
      pos[nd.id].y += (disp[nd.id].y / d) * lim
    }
  }
  const out = {}
  for (const nd of nodes) out[nd.id] = { x: round2(pos[nd.id].x), y: round2(pos[nd.id].y) }
  // 收口：力导的最终尺度不该由「迭代收敛到什么程度」决定——同一份数据在 extremes 上
  // 会散成 3000px（缩放被压到 0.17，节点小成米粒）。这里按村庄尺度只收不放：
  // 超出目标跨度就按质心等比缩回来，相对结构（谁离谁近）完全保留。
  return clampSpan(out, nodes, Math.max(460, Math.sqrt(n) * linkDist * 1.35))
}

/**
 * 把位置表按质心等比收拢到给定跨度以内（只缩不放，相对结构不变）。
 *
 * @param {Object} positions 位置表（就地修改）
 *
 * @param {Array} nodes 节点列表
 *
 * @param {number} maxSpan 允许的最大跨度（世界 px）
 *
 * @returns {Object} 位置表
 */
function clampSpan(positions, nodes, maxSpan) {
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  for (const nd of nodes) {
    const p = positions[nd.id]
    if (!p) continue
    minX = Math.min(minX, p.x)
    maxX = Math.max(maxX, p.x)
    minY = Math.min(minY, p.y)
    maxY = Math.max(maxY, p.y)
  }
  if (!Number.isFinite(minX)) return positions
  const span = Math.max(maxX - minX, maxY - minY)
  if (span <= maxSpan || span === 0) return positions
  const scale = maxSpan / span
  const cx = (minX + maxX) / 2
  const cy = (minY + maxY) / 2
  for (const nd of nodes) {
    const p = positions[nd.id]
    if (!p) continue
    p.x = round2(cx + (p.x - cx) * scale)
    p.y = round2(cy + (p.y - cy) * scale)
  }
  return positions
}


/**
 * 重要度同心圆：重要度 5 在最内环，1 在最外环；环内按名称确定性排序。
 *
 * @param {Array} nodes 节点
 *
 * @param {object} ctx 上下文
 *
 * @returns {Object} 位置表
 */
function concentricLayout(nodes, ctx) {
  const { gap, sizeOf } = ctx
  const rings = [5, 4, 3, 2, 1]
  const positions = {}
  const sorted = nodes.slice().sort((a, b) => (b.importance || 0) - (a.importance || 0)
    || String(a.name).localeCompare(String(b.name), 'zh-Hans-CN'))
  let radius = 0
  rings.forEach((level, ri) => {
    const members = sorted.filter(n => Math.min(5, Math.max(1, Number(n.importance) || 3)) === level)
    if (members.length === 0) return
    const maxSize = Math.max(...members.map(sizeOf))
    radius = ri === 0
      ? Math.max(60, (members.length * (maxSize + gap)) / (2 * Math.PI))
      : radius + maxSize + gap * 2.2
    members.forEach((n, i) => {
      const a = (i / members.length) * Math.PI * 2 - Math.PI / 2
      positions[n.id] = { x: Math.cos(a) * radius, y: Math.sin(a) * radius }
    })
  })
  return positions
}

/**
 * 以人为心放射：中心（度数最高或指定角色）居中，按 BFS 跳数分层，同层按名称排序。
 *
 * @param {Array} nodes 节点
 *
 * @param {Array} edges 边
 *
 * @param {object} ctx 上下文（focusId/degree/gap/sizeOf）
 *
 * @returns {Object} 位置表
 */
function radialLayout(nodes, edges, ctx) {
  const { focusId, degree, gap, sizeOf } = ctx
  const adj = new Map()
  nodes.forEach(n => adj.set(n.id, []))
  edges.forEach(e => {
    if (adj.has(e.source) && adj.has(e.target)) {
      adj.get(e.source).push(e.target)
      adj.get(e.target).push(e.source)
    }
  })
  const center = focusId && adj.has(focusId)
    ? focusId
    : nodes.slice().sort((a, b) => (degree[b.id] || 0) - (degree[a.id] || 0)
      || String(a.id).localeCompare(String(b.id)))[0]?.id
  const positions = {}
  if (!center) return positions
  const depth = new Map([[center, 0]])
  const queue = [center]
  while (queue.length) {
    const cur = queue.shift()
    for (const nxt of adj.get(cur) || []) {
      if (!depth.has(nxt)) {
        depth.set(nxt, depth.get(cur) + 1)
        queue.push(nxt)
      }
    }
  }
  // 孤立节点（无边可达）放到最后一环，按名称排序
  const maxDepth = Math.max(1, ...depth.values())
  const orphans = nodes.filter(n => !depth.has(n.id)).sort((a, b) => String(a.name).localeCompare(String(b.name), 'zh-Hans-CN'))
  orphans.forEach(n => depth.set(n.id, maxDepth + 1))
  const byDepth = new Map()
  nodes.forEach(n => {
    const d = depth.get(n.id) || 0
    if (!byDepth.has(d)) byDepth.set(d, [])
    byDepth.get(d).push(n)
  })
  let radius = 0
  let ring = 0
  for (const d of [...byDepth.keys()].sort((a, b) => a - b)) {
    const members = byDepth.get(d).slice().sort((a, b) => String(a.name).localeCompare(String(b.name), 'zh-Hans-CN'))
    if (d === 0) {
      positions[members[0].id] = { x: 0, y: 0 }
      continue
    }
    const maxSize = Math.max(...members.map(sizeOf))
    // 环间距：只要「内圈节点的名字标签」不被外圈节点压住即可——
    // 即 内圈半径 + 标签高度 ≈ 内圈直径/2+16，再给外圈节点半个身子。取 1.2 倍 gap 后
    // 由 relaxOverlaps 兜底（它保证任意两人的圆心距 ≥ 两直径 + 半个 gap），
    // 原来是 2.6 倍，环距被撑到 102px，5 环就是 1000px 的跨度、缩放掉到 0.46（人名看不清）。
    radius = radius === 0 ? maxSize + gap * 0.9 : radius + maxSize + gap * 1.2
    ring += 1
    // ⚠ 每一环整体旋转一个黄金角：BFS 的每一层常常只有 1~2 个人，
    // 若都从 -90° 起排，单成员的环会全部落在同一条竖直线上——整张图缩成一根竖条
    // （实测就是"以人为心"看起来像一列点）。旋转后单成员环自然连成一条螺线。
    const start = ring * GOLDEN_ANGLE - Math.PI / 2
    members.forEach((n, i) => {
      const a = start + (i / members.length) * Math.PI * 2
      positions[n.id] = { x: Math.cos(a) * radius, y: Math.sin(a) * radius }
    })
  }
  return positions
}

/**
 * 圈子泳道：每个圈子一条横带，带内网格排列（关系密集时比圆环更易读）。
 *
 * @param {Array} nodes 节点
 *
 * @param {Array} clusters 圈子
 *
 * @param {object} ctx 上下文
 *
 * @returns {Object} 位置表
 */
function lanesLayout(nodes, clusters, ctx) {
  const { gap, sizeOf } = ctx
  const positions = {}
  const nodeById = new Map(nodes.map(n => [n.id, n]))
  const placed = new Set()
  const list = clusters.length ? clusters.slice() : [{ id: 'all', members: nodes.map(n => n.id) }]
  let y = 0
  /**
   * 铺一条泳道：成员沿水平方向铺开（宽泳道才像泳道）。
   *
   * <p>旧实现把每条泳道铺成 sqrt(n) 的小方阵再逐条往下堆，结果 5 个圈子叠成一根细长条
   * （实测缩放 0.65、两侧全是空白）。泳道的语义是「同一拨人排成一行」，
   * 所以这里固定泳道宽度、成员等距横排，圈子外框自然变成一条横向胶囊。</p>
   */
  const lane = (members) => {
    if (members.length === 0) return
    const maxSize = Math.max(...members.map(sizeOf))
    const step = maxSize + gap * 2.2
    // 泳道宽度：至少按 5 个位算，3~4 人的圈子也不会挤成一小撮；人多了按人数加宽
    const laneWidth = Math.max(5, members.length) * step
    const gapX = laneWidth / Math.max(1, members.length)
    members.forEach((n, i) => {
      positions[n.id] = { x: (i - (members.length - 1) / 2) * gapX, y }
      placed.add(n.id)
    })
    // 泳道间距要留出「上一行的人名 + 本行外框的圈名」两层文字（实测 3 倍 gap 会叠字）
    y += maxSize + gap * 4.2
  }
  list.forEach(cluster => {
    const members = cluster.members.map(id => nodeById.get(id)).filter(Boolean)
    lane(members)
  })
  // 兜底：没被任何圈子收进去的节点（理论上不会发生，漏了就会「有人凭空不见」）
  lane(nodes.filter(n => !placed.has(n.id)))
  // 垂直居中：泳道是「一条条带」，整体居中后视觉重心才对
  const ys = Object.values(positions).map(p => p.y)
  if (ys.length) {
    const mid = (Math.min(...ys) + Math.max(...ys)) / 2
    Object.values(positions).forEach(p => { p.y -= mid })
  }
  return positions
}

// ==================== 几何工具 ====================

/** 节点半径（= 直径/2；直接复用渲染口径 nodeSizeOf，布局据此预留间距，杜绝两套尺寸公式） */
function nodeRadius(node, degree = 0) {
  return nodeSizeOf(node, degree) / 2
}

/**
 * 网格排布：给定各圈领地半径，把圈心铺在一个整齐的网格上（确定性：输入顺序 = 行优先顺序）。
 *
 * <p>为什么不用「大圆环」：环排是好看的，但代价是整张图被撑成一个巨大的圆——
 * 5 个圈子要塞进周长，环半径被推到 300+，加圈内领地就是 ~900px 的竖向跨度，
 * 而画布给得出来的安全区只有 550px 左右，缩放只能压到 0.63（人名小到看不清）。
 * 网格是行优先填充 + 行内居中，同样的内容跨度约 800×550，缩放能抬到 0.85 上下。
 * 格子边长取「最大领地直径 + 留白」，小圈在大格子里会留白，但换来了对齐与零重叠。</p>
 *
 * @param {number[]} radii 各圈领地半径
 *
 * @param {number} padding 圈间留白
 *
 * @returns {Array<{x:number,y:number}>} 各圈圆心
 */
function gridCenters(radii, padding) {
  const k = radii.length
  if (k === 0) return []
  if (k === 1) return [{ x: 0, y: 0 }]
  const cell = 2 * Math.max(...radii) + padding
  // 画布是横向的（可用区宽高比约 1.4），所以列略多于行
  const cols = Math.max(1, Math.ceil(Math.sqrt(k * 1.4)))
  const rows = Math.ceil(k / cols)
  const out = []
  for (let i = 0; i < k; i++) {
    const row = Math.floor(i / cols)
    const col = i % cols
    const inRow = Math.min(cols, k - row * cols)
    out.push({
      x: round2((col - (inRow - 1) / 2) * cell),
      y: round2((row - (rows - 1) / 2) * cell)
    })
  }
  return out
}

/**
 * 重叠松弛：确定性多轮微调，保证节点不叠在一起（同输入同输出）。
 *
 * @param {Array} nodes 节点
 *
 * @param {Object} positions 位置表
 *
 * @param {object} ctx 上下文
 *
 * @returns {Object} 松弛后的位置表（新对象）
 */
function relaxOverlaps(nodes, positions, ctx) {
  const { gap, sizeOf } = ctx
  const pos = {}
  for (const n of nodes) {
    const p = positions[n.id]
    pos[n.id] = p ? { x: p.x, y: p.y } : { x: 0, y: 0 }
  }
  const rounds = 60
  for (let r = 0; r < rounds; r++) {
    let moved = false
    for (let i = 0; i < nodes.length; i++) {
      const a = nodes[i]
      for (let j = i + 1; j < nodes.length; j++) {
        const b = nodes[j]
        const minD = sizeOf(a) + sizeOf(b) + gap * 0.5
        let dx = pos[a.id].x - pos[b.id].x
        let dy = pos[a.id].y - pos[b.id].y
        let d = Math.hypot(dx, dy)
        if (d >= minD) continue
        if (d < 0.01) {
          // 完全重合：按 ID 顺序给一个确定性偏移，避免死循环
          const k = hashString(a.id + b.id) % 360
          dx = Math.cos((k * Math.PI) / 180)
          dy = Math.sin((k * Math.PI) / 180)
          d = 1
        }
        const push = (minD - d) / 2 + 0.5
        const ux = dx / d
        const uy = dy / d
        pos[a.id].x += ux * push
        pos[a.id].y += uy * push
        pos[b.id].x -= ux * push
        pos[b.id].y -= uy * push
        moved = true
      }
    }
    if (!moved) break
  }
  const out = {}
  for (const n of nodes) out[n.id] = { x: round2(pos[n.id].x), y: round2(pos[n.id].y) }
  return out
}

/** 位置表 → 整体包围盒（含节点半径与留白） */
function boundsOf(nodes, positions, sizeOf) {
  if (nodes.length === 0) return { width: 800, height: 600 }
  let minX = Infinity
  let minY = Infinity
  let maxX = -Infinity
  let maxY = -Infinity
  for (const n of nodes) {
    const p = positions[n.id] || { x: 0, y: 0 }
    const r = sizeOf(n)
    minX = Math.min(minX, p.x - r)
    minY = Math.min(minY, p.y - r)
    maxX = Math.max(maxX, p.x + r)
    maxY = Math.max(maxY, p.y + r)
  }
  return { width: Math.max(320, maxX - minX), height: Math.max(240, maxY - minY) }
}

/** 圈子几何：由成员位置求最小外接圆（供外框与标签定位） */
function shapeClusters(clusters, positions, sizeOf, gap) {
  const nodeRadiusById = {}
  return clusters.map(c => {
    let minX = Infinity
    let minY = Infinity
    let maxX = -Infinity
    let maxY = -Infinity
    c.members.forEach(id => {
      const p = positions[id]
      if (!p) return
      const r = nodeRadiusById[id] ?? 0
      minX = Math.min(minX, p.x - r)
      minY = Math.min(minY, p.y - r)
      maxX = Math.max(maxX, p.x + r)
      maxY = Math.max(maxY, p.y + r)
    })
    if (!Number.isFinite(minX)) return { ...c, x: 0, y: 0, radius: 60 }
    const cx = (minX + maxX) / 2
    const cy = (minY + maxY) / 2
    const radius = Math.max(48, Math.hypot(maxX - cx, maxY - cy) + gap * 1.1)
    return { ...c, x: round2(cx), y: round2(cy), radius: round2(radius) }
  })
}

/** 度数（无向） */
function computeDegree(nodes, edges) {
  const d = {}
  for (const n of nodes) d[n.id] = 0
  for (const e of edges) {
    d[e.source] = (d[e.source] || 0) + 1
    d[e.target] = (d[e.target] || 0) + 1
  }
  return d
}

/** 保留两位小数（避免浮点尾差破坏「同输入同输出」的字符串比较） */
function round2(v) {
  return Math.round(v * 100) / 100
}
