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

/**
 * G6 画布适配层 —— 把「工作台模型」翻译成 G6 v5 的图数据与样式。
 *
 * <p><b>为什么独立成层</b>：视觉口径（节点怎么配色/边怎么算粗细/哪些标签在什么缩放下显示）
 * 是关系图最容易被反复改坏的部分，散在组件里既难复用也难验证。这里全部收敛成
 * <b>纯函数</b>：输入（节点/边/位置/圈子/主题/选项）→ 输出（G6 data + 样式函数），
 * 不碰 DOM、不碰 Graph 实例。</p>
 *
 * <p><b>视觉编码（一图一语义，互不抢戏）</b></p>
 * <ul>
 *   <li><b>节点颜色 = 圈子</b>：一眼看出「分成了哪几拨人」（用户第一诉求）；</li>
 *   <li><b>节点大小 = 重要度 + 度数</b>：重要且关系多的人自然更显眼；</li>
 *   <li><b>节点线型 = 数据完整度</b>：实线=NPC/人群，虚线=关系表引用但角色不存在的「待补充」；</li>
 *   <li><b>边颜色 = 关系类型</b>（语义色板），<b>边粗细 = 互动强度</b>（深仇与挚爱都粗），
 *       <b>边透明度 = 新鲜度</b>（久不往来自然变淡）；</li>
 *   <li><b>标签按需出现</b>：关系文字默认只在选中/悬停时显示——几百条边同时打标是
 *       「看起来乱」的头号原因，不是信息量不够。</li>
 * </ul>
 */

import { colorForCluster, colorForType, edgeWidthOf, freshnessOpacity, nodeSizeOf, relStateLabel } from './relationPalette.js'

/** 关系文字显示档位（工具栏可切；默认 smart=小图全显、大图只显选中） */
export const EDGE_LABEL_MODE = {
  /** 智能：边数 ≤ SMART_EDGE_LIMIT 时全显，否则只显选中/悬停 */
  SMART: 'smart',
  /** 全显 */
  ALL: 'all',
  /** 只显选中/悬停 */
  SELECTED: 'selected',
  /** 全不显 */
  NONE: 'none'
}

/** 「智能」档的全显阈值（超过这么多条边，全显关系文字只会互相压字） */
export const SMART_EDGE_LIMIT = 24

/** 标签降级阈值：节点数超过它时，只有「重要/高连接」节点保留常显标签（LOD） */
export const LOD_NODE_LIMIT = 70

/**
 * 构建 G6 图数据。
 *
 * @param {object} input 输入
 *
 * @param {Array} input.nodes 归一化节点
 *
 * @param {Array} input.edges 归一化边（已双向合并）
 *
 * @param {Object} input.positions 位置表（角色 ID → {x,y}）
 *
 * @param {Object} input.clusterOf 角色 ID → 圈子 ID
 *
 * @param {Array} input.clusters 圈子列表（含 type/label/source/typeLabel）
 *
 * @param {Object} input.degree 角色 ID → 度数
 *
 * @param {object} input.theme 主题包（topologyTheme.buildTheme）
 *
 * @param {number} [input.nowMs] 当前时刻（新鲜度基准；缺省 Date.now）
 *
 * @returns {{ nodes: Array, edges: Array, hulls: Array }} G6 数据 + 圈子外框定义
 */
export function buildCanvasData(input) {
  const {
    nodes = [], edges = [], positions = {}, clusterOf = {}, clusters = [],
    degree = {}, theme, nowMs = Date.now()
  } = input
  const clusterById = new Map(clusters.map(c => [c.id, c]))

  const g6Nodes = nodes.map(n => {
    const cid = clusterOf[n.id]
    const cluster = clusterById.get(cid) || null
    const color = cluster ? colorForCluster(cluster) : theme.accent
    const p = positions[n.id] || { x: 0, y: 0 }
    return {
      id: n.id,
      data: {
        node: n,
        clusterId: cid || null,
        clusterLabel: cluster ? cluster.label : '',
        clusterType: cluster ? cluster.type : 'unknown',
        color,
        degree: degree[n.id] || 0
      },
      // ⚠ 位置写在 data.style 上：G6 v5 的 positionOf/hull 都读 datum.style.x/y，
      // 且 dataStyle 优先级高于 options.node.style 回调（见 runtime/element.js）。
      style: { x: p.x, y: p.y }
    }
  })

  const g6Edges = edges.map(e => ({
    id: e.id,
    source: e.source,
    target: e.target,
    data: { edge: e, color: colorForType(e.relationType) }
  }))

  // 圈子外框：只对「有 2 个以上成员」的圈子画（单人圈子画个圈只是噪声）
  const hulls = clusters
    .filter(c => c.members.filter(id => positions[id]).length >= 2)
    .map(c => ({
      id: c.id,
      members: c.members.filter(id => positions[id]),
      label: c.label,
      typeLabel: c.typeLabel || '',
      color: colorForCluster(c),
      size: c.size
    }))

  return { nodes: g6Nodes, edges: g6Edges, hulls }
}

/**
 * 节点样式回调工厂。
 *
 * @param {object} ctx 上下文
 *
 * @param {object} ctx.theme 主题包
 *
 * @param {Function} ctx.visibleLabel (id) => 是否常显标签（LOD 判定由组件给出）
 *
 * @param {Object} ctx.degree 度数表
 *
 * @returns {Function} (datum) => 样式对象
 */
export function createNodeStyle(ctx) {
  const theme = ctx.theme
  const visibleLabel = ctx.visibleLabel || (() => true)
  return function nodeStyle(datum) {
    const d = datum.data || {}
    const node = d.node || {}
    const color = d.color || theme.accent
    const size = nodeSizeOf(node, d.degree || 0)
    const ghost = node.kind === 'ghost'
    const isCrowd = node.kind === 'crowd'
    const showLabel = visibleLabel(datum.id)
    const badges = []
    if (node.isProtagonist) {
      badges.push({
        text: '主角',
        placement: 'right-top',
        background: true,
        backgroundFill: theme.accent,
        backgroundRadius: 3,
        fill: '#ffffff',
        fontSize: 9,
        padding: [1, 3]
      })
    }
    if (ghost) {
      badges.push({
        text: '待补',
        placement: 'right-top',
        background: true,
        backgroundFill: theme.textPlaceholder,
        backgroundRadius: 3,
        fill: '#ffffff',
        fontSize: 9,
        padding: [1, 3]
      })
    }
    return {
      size,
      fill: ghost ? theme.ghostFill : theme.nodeFill(color),
      stroke: ghost ? theme.ghostStroke : theme.nodeStroke(color),
      lineWidth: node.isProtagonist ? 3 : 2,
      lineDash: ghost ? [4, 2] : null,
      // 主角额外光环：重要人物在缩小时也能被一眼找到
      halo: !!node.isProtagonist,
      haloStroke: theme.protagonistHalo,
      haloLineWidth: 8,
      haloOpacity: node.isProtagonist ? 0.18 : 0,
      zIndex: node.isProtagonist ? 2 : 1,
      // 标签：默认放在节点下方，带半透明底避免压线不可读
      labelText: showLabel ? (node.name || '') : '',
      labelPlacement: 'bottom',
      labelOffsetY: 3,
      labelFontSize: isCrowd ? 10 : 11,
      labelFontWeight: node.isProtagonist ? 600 : 500,
      labelFill: ghost ? theme.textSecondary : theme.textPrimary,
      labelBackground: true,
      labelBackgroundFill: theme.labelBg,
      labelBackgroundRadius: 4,
      labelPadding: [1, 4],
      // ⚠ 基样式必须显式声明状态用到的属性（G6 v5.1.1 updateStyle 合并缺陷：
      // 只存在于状态样式里的属性在状态清除后不会回退，会残留在元素 attributes 上）
      opacity: 1,
      labelOpacity: 1,
      badges
    }
  }
}

/**
 * 边样式回调工厂。
 *
 * @param {object} ctx 上下文
 *
 * @param {object} ctx.theme 主题包
 *
 * @param {Function} ctx.showLabel (edgeId) => 是否常显关系文字
 *
 * @param {number} [ctx.nowMs] 新鲜度基准时刻
 *
 * @returns {Function} (datum) => 样式对象
 */
export function createEdgeStyle(ctx) {
  const theme = ctx.theme
  const showLabel = ctx.showLabel || (() => false)
  const nowMs = ctx.nowMs || Date.now()
  return function edgeStyle(datum) {
    const d = datum.data || {}
    const edge = d.edge || {}
    const color = d.color || theme.accent
    const label = showLabel(datum.id)
    return {
      stroke: color,
      lineWidth: edge.width || edgeWidthOf(edge.affinity, edge.trust),
      opacity: edge.opacity == null ? freshnessOpacity(edge.lastInteractionAt, nowMs) : edge.opacity,
      lineDash: null,
      // 两端箭头表达方向：双向关系合并成一条带双箭头的边（并排两条反向线是最常见的「难看」来源）
      startArrow: !!edge.bidirectional,
      startArrowSize: 7,
      endArrow: true,
      endArrowSize: 7,
      zIndex: 0,
      // ⚠ 同样显式声明状态属性（见 nodeStyle 注释）
      labelText: label ? (edge.relationType || '') : '',
      labelFontSize: 10,
      labelFill: color,
      labelFontWeight: 500,
      labelBackground: true,
      labelBackgroundFill: theme.edgeLabelBg,
      labelBackgroundRadius: 3,
      labelPadding: [1.5, 5],
      labelPlacement: 'center',
      // 关系文字保持水平：G6 边标签默认沿边旋转（labelAutoRotate: true），
      // 斜边上的中文会被拧成 45°～70°，读起来比不标还费劲；水平标签 + 底色药丸才是可读的做法。
      labelAutoRotate: false,
      labelOffsetX: 0,
      labelOffsetY: 0
    }
  }
}

/**
 * 节点状态样式（选中/悬停/弱化）。
 *
 * @param {object} theme 主题包
 *
 * @returns {object} G6 state 配置
 */
export function nodeStateStyles(theme) {
  return {
    selected: {
      halo: true,
      haloStroke: theme.accent,
      haloLineWidth: 16,
      haloOpacity: 0.34,
      stroke: theme.accent,
      lineWidth: 3,
      opacity: 1,
      labelOpacity: 1,
      zIndex: 3
    },
    active: {
      halo: true,
      haloStroke: theme.accent,
      haloLineWidth: 11,
      haloOpacity: 0.26,
      stroke: theme.accent,
      lineWidth: 2.5,
      opacity: 1,
      labelOpacity: 1
    },
    // 弱化但绝不「看不见」：旧实现 0.18 在半数场景下等于把用户选中的关系网藏掉了。
    // 也不靠单纯降 opacity：节点填充本来就是「圈子色 42% + 底色」的浅色，再乘 0.4 在白底上等于消失。
    // 改成「抽掉圈子色，只留一圈灰边 + 灰名字」——一眼能看出"这些人在场但不相关"。
    inactive: {
      opacity: 1,
      fill: theme.ghostFill,
      stroke: theme.ghostStroke,
      lineWidth: 1,
      labelFill: theme.textPlaceholder,
      labelOpacity: 0.85
    },
    /** 搜索命中 */
    highlight: {
      halo: true,
      haloStroke: '#f59e0b',
      haloLineWidth: 14,
      haloOpacity: 0.45,
      stroke: '#f59e0b',
      lineWidth: 3,
      opacity: 1,
      labelOpacity: 1,
      zIndex: 3
    }
  }
}

/**
 * 边状态样式。
 *
 * @param {object} theme 主题包
 *
 * @returns {object} G6 state 配置
 */
export function edgeStateStyles(theme) {
  return {
    selected: {
      lineWidth: 3.5,
      opacity: 1,
      halo: true,
      haloStroke: theme.accent,
      haloLineWidth: 8,
      haloOpacity: 0.22,
      zIndex: 2,
      labelFontSize: 11,
      labelBackgroundFill: theme.edgeLabelBg
    },
    active: {
      lineWidth: 3,
      opacity: 1,
      labelFontSize: 10
    },
    inactive: { opacity: 0.12, labelText: '' },
    /** 搜索命中节点之间的边 */
    highlight: { lineWidth: 3, opacity: 1, halo: true, haloStroke: '#f59e0b', haloLineWidth: 6, haloOpacity: 0.2 },
    /** 实时事件闪烁（关系变迁：好感涨跌/状态跃迁由 SSE 推来） */
    pulse: {
      lineWidth: 4,
      opacity: 1,
      halo: true,
      haloStroke: '#f59e0b',
      haloLineWidth: 14,
      haloOpacity: 0.5,
      zIndex: 3,
      labelFontSize: 11
    }
  }
}

/**
 * 关系文字是否常显（由组件按档位 + 边数决定，这里只做纯计算）。
 *
 * @param {object} input 输入
 *
 * @param {string} input.mode EDGE_LABEL_MODE
 *
 * @param {number} input.edgeCount 可见边数
 *
 * @returns {boolean} 是否全部常显
 */
export function shouldShowAllEdgeLabels({ mode, edgeCount }) {
  if (mode === EDGE_LABEL_MODE.ALL) return true
  if (mode === EDGE_LABEL_MODE.SELECTED || mode === EDGE_LABEL_MODE.NONE) return false
  return edgeCount <= SMART_EDGE_LIMIT
}

/**
 * 标签降级（LOD）：节点过多时只给「重要 / 连接多」的节点常显姓名。
 *
 * @param {object} input 输入
 *
 * @param {Array} input.nodes 可见节点
 *
 * @param {Object} input.degree 度数表
 *
 * @param {boolean} [input.zoomOut] 当前是否处于「缩得很小」的视野
 *
 * @returns {Set<string>} 常显标签的节点 ID 集合
 */
export function labelSetOf({ nodes = [], degree = {}, zoomOut = false }) {
  const keep = new Set()
  if (nodes.length <= LOD_NODE_LIMIT && !zoomOut) {
    nodes.forEach(n => keep.add(n.id))
    return keep
  }
  // 大图：主角 + 重要度 ≥4 + 度数前 12 名
  const ranked = nodes.slice().sort((a, b) => (degree[b.id] || 0) - (degree[a.id] || 0)
    || String(a.id).localeCompare(String(b.id)))
  const top = new Set(ranked.slice(0, 12).map(n => n.id))
  for (const n of nodes) {
    if (n.isProtagonist || (Number(n.importance) || 0) >= 4 || top.has(n.id)) keep.add(n.id)
  }
  return keep
}

/**
 * 生成 Tooltip HTML（节点/边两套；内容口径与详情栏一致，避免「悬停看到的和点开的不一样」）。
 *
 * @param {object} input 输入
 *
 * @param {object} input.datum G6 元素数据
 *
 * @param {string} input.kind 'node' | 'edge'
 *
 * @returns {string} HTML
 */
export function tooltipOf({ datum, kind }) {
  const d = datum?.data || {}
  if (kind === 'edge') {
    const e = d.edge || {}
    const aff = Number(e.affinity) || 0
    const parts = []
    parts.push(`<div class="tw-title">${esc(e.items?.[0]?.fromName || '')} ⇄ ${esc(e.items?.[0]?.toName || '')}</div>`)
    parts.push(`<div class="tw-row"><span class="tw-k">关系</span><span class="tw-dot" style="background:${colorForType(e.relationType)}"></span>${esc((e.relationTypes || [e.relationType]).join(' / '))}</div>`)
    parts.push(`<div class="tw-row"><span class="tw-k">好感</span><b>${aff > 0 ? '+' + aff : aff}</b><span class="tw-k">信任</span><b>${Number(e.trust) || 0}</b><span class="tw-k">状态</span>${esc(relStateLabel(e.relationState, e.relationStateLabel))}</div>`)
    if (e.bidirectional) parts.push('<div class="tw-note">双向关系（已合并为一条边，展开可见两条原始记录）</div>')
    if (e.description) parts.push(`<div class="tw-row"><span class="tw-k">描述</span>${esc(e.description)}</div>`)
    parts.push('<div class="tw-note">单击查看双方 · 右键编辑/删除</div>')
    return `<div class="topo-tip">${parts.join('')}</div>`
  }
  const n = d.node || {}
  const parts = [`<div class="tw-title">${esc(n.name)}${n.isProtagonist ? '<span class="tw-tag">主角</span>' : ''}</div>`]
  if (n.kind === 'ghost') {
    parts.push('<div class="tw-note">关系表引用了该角色，但角色档案与普通人群里都没有对应数据。点击可在右侧「前往补充」。</div>')
    return `<div class="topo-tip">${parts.join('')}</div>`
  }
  if (d.clusterLabel) parts.push(`<div class="tw-row"><span class="tw-k">圈子</span><span class="tw-dot" style="background:${d.color}"></span>${esc(d.clusterLabel)}</div>`)
  if (n.meta?.occupation) parts.push(`<div class="tw-row"><span class="tw-k">职业</span>${esc(n.meta.occupation)}</div>`)
  if (n.title) parts.push(`<div class="tw-row"><span class="tw-k">头衔</span>${esc(n.title)}</div>`)
  parts.push(`<div class="tw-row"><span class="tw-k">关系</span><b>${d.degree || 0}</b> 条<span class="tw-k">重要度</span>${'★'.repeat(Math.max(0, Number(n.importance) || 0))}</div>`)
  parts.push('<div class="tw-note">单击在右侧展开档案与关系清单</div>')
  return `<div class="topo-tip">${parts.join('')}</div>`
}

/** HTML 转义（Tooltip 走 innerHTML，必须转义业务文本） */
export function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, c => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  })[c])
}
